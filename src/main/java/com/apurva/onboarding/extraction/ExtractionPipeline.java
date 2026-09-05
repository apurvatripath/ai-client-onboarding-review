package com.apurva.onboarding.extraction;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static com.apurva.onboarding.extraction.ExtractionResult.Field;

@Service
public class ExtractionPipeline {
    public static final String VERSION="intake-v1";
    private final DocumentReader reader;
    private final OcrEngine ocr;
    private final FieldExtractor extractor;
    private final ExtractionStore store;
    private final ExtractionSettings settings;
    private final ObjectMapper json=new ObjectMapper();
    public ExtractionPipeline(DocumentReader reader, OcrEngine ocr, FieldExtractor extractor,
                              ExtractionStore store, ExtractionSettings settings) {
        this.reader=reader; this.ocr=ocr; this.extractor=extractor; this.store=store; this.settings=settings;
    }
    /** Serial execution is intentional: bounded memory, no duplicate in-flight model calls. H2 locks other processes. */
    public synchronized ExtractionResult process(byte[] bytes, String requestedId) throws Exception {
        return process(bytes,requestedId,false);
    }
    public synchronized ExtractionResult process(byte[] bytes, String requestedId, boolean reprocess) throws Exception {
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if(requestedId==null || requestedId.isBlank()) requestedId="doc-"+hash;
        if(!requestedId.matches("[a-zA-Z0-9_-]{1,100}")) throw new IllegalArgumentException("submissionId must be 1-100 letters, digits, hyphens or underscores");
        String id=store.reserve(requestedId,hash);
        ExtractionResult cached=store.get(id);
        if(cached!=null && !reprocess) { event(id,hash,0,"CACHE_HIT",Map.of("requestedId",requestedId)); return cached; }
        event(id,hash,0,"RECEIVED",Map.of("reprocess",reprocess,"bytes",bytes.length));
        try(var document=reader.open(bytes)) {
            event(id,hash,0,"START",Map.of("provider",extractor.name(),"pipelineVersion",VERSION,"pages",document.pages()));
            Map<String,Field> fields=new LinkedHashMap<>();
            List<Map<String,Field>> rows=new ArrayList<>();
            List<String> issues=new ArrayList<>();
            for(int page=1;page<=document.pages();page++) {
                String text="";
                try { text=document.text(page); event(id,hash,page,"PDF_TEXT",Map.of("characters",text.length())); }
                catch(Exception e) { event(id,hash,page,"PDF_TEXT_FAILED",Map.of("error",e.getClass().getSimpleName())); }
                Page parsed=null;
                if(usable(text)) parsed=attempt(id,hash,page,"TEXT",text,1);
                boolean fallback=parsed==null || parsed.needsOcr();
                if(fallback) {
                    event(id,hash,page,"OCR_STARTED",Map.of());
                    try {
                        OcrEngine.Text recovered=ocr.read(document.image(page));
                        event(id,hash,page,"OCR_FINISHED",Map.of("characters",recovered.text().length(),"confidence",recovered.confidence(),"ocrText",recovered.text()));
                        Page alternative=usable(recovered.text()) ? attempt(id,hash,page,"OCR",recovered.text(),recovered.confidence()) : null;
                        if(alternative!=null) parsed=parsed==null ? alternative : combine(parsed,alternative);
                        if(alternative==null) issues.add("page["+page+"]: OCR could not recover structured content");
                    } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
                    catch(Exception e) {
                        event(id,hash,page,"OCR_FAILED",Map.of("error",safeError(e)));
                        issues.add("page["+page+"]: OCR failed; inspect this page");
                    }
                }
                if(parsed==null) { issues.add("page["+page+"]: extraction failed"); continue; }
                parsed.fields.forEach((name,field)->fields.merge(name,field,this::merge));
                rows.addAll(parsed.rows);
                if(!parsed.tableComplete) issues.add("page["+page+"]: service table incomplete or ambiguous");
                if(!parsed.problems.isEmpty()) issues.addAll(parsed.problems);
            }
            List<String> missing=new ArrayList<>(), queue=new ArrayList<>();
            for(String name:IntakeSchema.FIELDS) {
                Field field=fields.get(name);
                if(field==null || (field.value()==null && field.status().equals("MISSING"))) {
                    field=new Field(null,0,"absence",issues.isEmpty()?"MISSING":"FAILED",List.of(),"");
                    fields.put(name,field); missing.add(name);
                }
                if(!Set.of("ACCEPTED","MISSING").contains(field.status())) queue.add(name);
            }
            for(int i=0;i<rows.size();i++) for(String cell:IntakeSchema.CELLS) {
                if(!rows.get(i).get(cell).status().equals("ACCEPTED")) queue.add("lineItems["+i+"]."+cell);
            }
            queue.addAll(issues);
            String status=!queue.isEmpty()?"MANUAL_REVIEW":!missing.isEmpty()?"MISSING_INFORMATION":"COMPLETE";
            ExtractionResult result=new ExtractionResult(id,hash,"INTAKE_FORM",extractor.name(),VERSION,
                    Instant.now().toString(),status,fields,rows,missing,queue,issues,document.pages(),"PENDING");
            store.save(result);
            return result;
        } catch(DocumentReader.BadDocument e) {
            event(id,hash,0,"INPUT_REJECTED",Map.of("error",e.getMessage()));
            throw e;
        }
    }
    private boolean usable(String text) { return text!=null && text.chars().filter(Character::isLetterOrDigit).count()>=12 && text.indexOf('\uFFFD')<0; }
    private Page attempt(String id,String hash,int page,String path,String text,double ocrConfidence) throws Exception {
        for(int attempt=1;attempt<=settings.attempts;attempt++) {
            String raw="";
            String attemptId=UUID.randomUUID().toString();
            event(id,hash,page,"ATTEMPT_STARTED",Map.of("attemptId",attemptId,"attempt",attempt,"path",path,"provider",extractor.name()));
            try {
                raw=extractor.extract(text);
                Page result=parse(raw,text,page,path,ocrConfidence);
                event(id,hash,page,"ATTEMPT_FINISHED",Map.of("attemptId",attemptId,"attempt",attempt,"path",path,
                        "modelOutput",raw,"fields",result.fields,"lineItems",result.rows,"tableComplete",result.tableComplete));
                return result;
            } catch(InterruptedException e) {
                event(id,hash,page,"ATTEMPT_FAILED",Map.of("attemptId",attemptId,"error","INTERRUPTED","modelOutput",raw));
                Thread.currentThread().interrupt(); throw e;
            } catch(Exception e) {
                // Audit failures must fail closed: do not proceed without a durable attempt history.
                if(e instanceof java.sql.SQLException) throw e;
                boolean retry=!(e instanceof FieldExtractor.CallFailure f) || f.retryable;
                long requested=e instanceof FieldExtractor.CallFailure f ? f.retryAfterMillis:0;
                long delay=Math.max(requested,settings.backoffMillis*(1L<<(attempt-1))+ThreadLocalRandom.current().nextLong(100));
                event(id,hash,page,"ATTEMPT_FAILED",Map.of("attemptId",attemptId,"attempt",attempt,"path",path,
                        "error",safeError(e),"modelOutput",raw.length()>200_000?raw.substring(0,200_000):raw,
                        "willRetry",retry&&attempt<settings.attempts,"backoffMillis",delay));
                if(!retry || attempt==settings.attempts) return null;
                Thread.sleep(delay);
            }
        }
        return null;
    }
    private String safeError(Exception e) {
        if(e instanceof FieldExtractor.CallFailure) return e.getMessage();
        if(e instanceof java.io.IOException && e.getMessage()!=null && e.getMessage().startsWith("OCR_")) return e.getMessage();
        return e.getClass().getSimpleName();
    }
    private void event(String id,String hash,int page,String stage,Map<String,?> details) throws Exception {
        Map<String,Object> event=new LinkedHashMap<>(details);
        event.put("timestamp",Instant.now().toString()); event.put("inputHash",hash); event.put("page",page); event.put("stage",stage);
        store.audit(id,event);
    }
    Page parse(String raw,String text,int page,String path,double ocrConfidence) {
        if(raw==null || raw.length()>200_000) throw new IllegalArgumentException("Invalid model response size");
        JsonNode root=json.readTree(raw);
        if(!root.isObject() || !root.path("fields").isObject() || !root.path("lineItems").isArray()
                || !root.path("tablePresent").isBoolean() || !root.path("tableComplete").isBoolean())
            throw new IllegalArgumentException("Malformed model response");
        Map<String,Field> fields=new LinkedHashMap<>();
        for(String name:IntakeSchema.FIELDS) {
            JsonNode node=root.path("fields").path(name);
            if(!node.isMissingNode() || extractor.name().startsWith("gemini/")) fields.put(name,field(name,node,text,page,path,ocrConfidence));
        }
        List<Map<String,Field>> rows=new ArrayList<>();
        if(root.path("lineItems").size()>100) throw new IllegalArgumentException("Too many table rows on page");
        for(JsonNode row:root.path("lineItems")) {
            Map<String,Field> cells=new LinkedHashMap<>();
            for(String name:IntakeSchema.CELLS) cells.put(name,field(name,row.path(name),text,page,path,ocrConfidence));
            rows.add(cells);
        }
        boolean present=root.path("tablePresent").asBoolean();
        boolean complete=root.path("tableComplete").asBoolean() && (!present || !rows.isEmpty());
        // Do not trust a false 'no table' flag in the presence of a visible service-table heading.
        if(text.toLowerCase(Locale.ROOT).matches("(?s).*description.*quantity.*unit\\s*price.*") && rows.isEmpty()) complete=false;
        return new Page(fields,rows,complete,new ArrayList<>());
    }
    private Field field(String name,JsonNode node,String text,int page,String path,double ocrConfidence) {
        String source=extractor.name().startsWith("local")?"rule-heuristic":"model-self-report";
        if(path.equals("OCR")) source+="+ocr-floor";
        if(!node.isObject() || !(node.path("value").isString() || node.path("value").isNull())
                || !node.path("confidence").isNumber() || !node.path("evidence").isString())
            return new Field(null,0,source,"FAILED",List.of(page),"");
        double score=node.path("confidence").asDouble();
        String original=node.path("value").isNull()?null:node.path("value").asText();
        String value=IntakeSchema.canonical(name,original), evidence=node.path("evidence").asText();
        if(!Double.isFinite(score)||score<0||score>1) return new Field(value,0,source,"FAILED",List.of(page),evidence);
        score=Math.min(score,ocrConfidence);
        if(value==null) return new Field(null,0,source,"MISSING",List.of(page),evidence);
        boolean grounded=!evidence.isBlank() && IntakeSchema.normalize(text).contains(IntakeSchema.normalize(evidence))
                && IntakeSchema.normalize(evidence).contains(IntakeSchema.normalize(original));
        String status=!IntakeSchema.valid(name,value)?"INVALID":!grounded?"UNSUPPORTED":score<IntakeSchema.THRESHOLD?"LOW_CONFIDENCE":"ACCEPTED";
        if(!grounded) score=0;
        return new Field(value,score,source,status,List.of(page),evidence);
    }
    private Field merge(Field first,Field second) {
        if(first.value()==null) return second.value()==null && first.status().equals("FAILED")?first:second;
        if(second.value()==null) return first;
        if(IntakeSchema.normalize(first.value()).equals(IntakeSchema.normalize(second.value()))) {
            Field best=first.confidence()>=second.confidence()?first:second;
            List<Integer> pages=new ArrayList<>(first.pages()); second.pages().forEach(p->{if(!pages.contains(p)) pages.add(p);});
            String status=first.status().equals("CONFLICT") || second.status().equals("CONFLICT")?"CONFLICT":best.status();
            return new Field(best.value(),best.confidence(),best.confidenceSource(),status,pages,best.evidence());
        }
        if(!first.status().equals("ACCEPTED") && second.status().equals("ACCEPTED") && !first.status().equals("CONFLICT")) return second;
        if(first.status().equals("ACCEPTED") && !second.status().equals("ACCEPTED")) return first;
        List<Integer> pages=new ArrayList<>(first.pages()); second.pages().forEach(p->{if(!pages.contains(p)) pages.add(p);});
        return new Field(first.value(),Math.min(first.confidence(),second.confidence()),first.confidenceSource(),"CONFLICT",pages,
                first.evidence()+" | conflicting evidence: "+second.evidence());
    }
    private Page combine(Page text,Page ocr) {
        Map<String,Field> fields=new LinkedHashMap<>(text.fields);
        ocr.fields.forEach((k,v)->fields.merge(k,v,this::merge));
        List<String> problems=new ArrayList<>();
        if(text.rows.isEmpty() || ocr.rows.isEmpty()) {
            Page chosen=text.rows.isEmpty()?ocr:text;
            boolean anyTable=!text.tableComplete || !ocr.tableComplete || !chosen.rows.isEmpty();
            return new Page(fields,chosen.rows,chosen.tableComplete && (!anyTable || !chosen.rows.isEmpty()),problems);
        }
        if(text.rows.size()!=ocr.rows.size()) {
            Page chosen=text.tableComplete?text:ocr;
            problems.add("service table row count differs between text and OCR; inspect source");
            return new Page(fields,chosen.rows,false,problems);
        }
        List<Map<String,Field>> combined=new ArrayList<>();
        for(int i=0;i<text.rows.size();i++) {
            Map<String,Field> cells=new LinkedHashMap<>();
            for(String name:IntakeSchema.CELLS) cells.put(name,merge(text.rows.get(i).get(name),ocr.rows.get(i).get(name)));
            combined.add(cells);
        }
        return new Page(fields,combined,text.tableComplete||ocr.tableComplete,problems);
    }
    record Page(Map<String,Field> fields,List<Map<String,Field>> rows,boolean tableComplete,List<String> problems) {
        boolean needsOcr() {
            return !tableComplete || (rows.isEmpty() && fields.values().stream().noneMatch(f->f.value()!=null))
                    || fields.values().stream().anyMatch(f->!Set.of("ACCEPTED","MISSING").contains(f.status()))
                    || rows.stream().flatMap(r->r.values().stream()).anyMatch(f->!f.status().equals("ACCEPTED"));
        }
    }
}
