package com.apurva.onboarding.extraction;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ExtractionPipelineTest {
    @TempDir Path dir;
    ExtractionSettings settings;
    ExtractionStore store;
    static final String[] FIRST={"Company name: Northstar Studio","Primary contact: Alex Morgan",
            "Contact email: alex@example.com","Service requested: Client onboarding automation"};
    static final String[] SECOND={"Budget: USD 2400","Target launch date: 30 September 2026",
            "Project summary: Build a validated intake workflow.","Dependencies: Approved checklist"};
    static final String[] TABLE={"Service items","Description | Quantity | Unit price",
            "Workflow setup | 1 | USD 1800","Review training | 2 | USD 300"};
    @BeforeEach void open() throws Exception {
        settings=new ExtractionSettings(new MockEnvironment().withProperty("EXTRACTION_DATA_DIR",dir.toString()));
        store=new ExtractionStore(settings);
    }
    @AfterEach void close() throws Exception { store.close(); }
    ExtractionPipeline pipeline(FieldExtractor extractor,OcrEngine ocr) { return new ExtractionPipeline(new DocumentReader(),ocr,extractor,store,settings); }
    OcrEngine noOcr() { return image->{throw new AssertionError("OCR should not be called for a complete digital form");}; }
    static byte[] pdf(String[]... pages) throws Exception {
        try(PDDocument doc=new PDDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            for(String[] lines:pages) {
                PDPage page=new PDPage(); doc.addPage(page);
                try(var canvas=new PDPageContentStream(doc,page)) {
                    canvas.beginText(); canvas.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                    canvas.newLineAtOffset(40,730); canvas.setLeading(24);
                    for(String line:lines) { canvas.showText(line); canvas.newLine(); }
                    canvas.endText();
                }
            }
            doc.save(bytes); return bytes.toByteArray();
        }
    }
    static String[] concat(String[]... arrays) { return Arrays.stream(arrays).flatMap(Arrays::stream).toArray(String[]::new); }
    @Test void extractsAllPagesAndOrderedTableRows() throws Exception {
        var result=pipeline(new LocalFieldExtractor(),noOcr()).process(pdf(FIRST,concat(SECOND,TABLE)),"multi");
        assertThat(result.reviewStatus()).isEqualTo("COMPLETE");
        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(result.fields().get("targetLaunchDate").value()).isEqualTo("2026-09-30");
        assertThat(result.fields().get("budget").pages()).containsExactly(2);
        assertThat(result.lineItems()).hasSize(2);
        assertThat(result.lineItems().get(1).get("quantity").value()).isEqualTo("2");
    }
    @Test void absentValuesRouteMissingWithoutInventingThem() throws Exception {
        var result=pipeline(new LocalFieldExtractor(),noOcr()).process(pdf(FIRST),"missing");
        assertThat(result.reviewStatus()).isEqualTo("MISSING_INFORMATION");
        assertThat(result.missingFields()).containsExactlyInAnyOrder("budget","targetLaunchDate","projectSummary","dependencies");
        assertThat(result.fields().get("budget").value()).isNull();
    }
    @Test void crossPageConflictsAreNotOverwritten() throws Exception {
        var result=pipeline(new LocalFieldExtractor(),noOcr()).process(pdf(concat(FIRST,SECOND),new String[]{"Budget: USD 9000"}),"conflict");
        assertThat(result.reviewStatus()).isEqualTo("MANUAL_REVIEW");
        assertThat(result.fields().get("budget").status()).isEqualTo("CONFLICT");
        assertThat(result.fields().get("budget").evidence()).contains("2400","9000");
    }
    @Test void retriesMalformedResponseAndRetainsRawAttempts() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        var fake=new FieldExtractor() {
            public String name(){return "test-model";}
            public String extract(String text) throws Exception {
                int call=calls.incrementAndGet();
                if(call==1) throw new java.net.http.HttpTimeoutException("test timeout");
                if(call==2) return "{malformed";
                return new LocalFieldExtractor().extract(text);
            }
        };
        var result=pipeline(fake,noOcr()).process(pdf(concat(FIRST,SECOND)),"retry");
        assertThat(result.reviewStatus()).isEqualTo("COMPLETE");
        assertThat(calls.get()).isEqualTo(3);
        String audit=new ObjectMapper().writeValueAsString(store.audit("retry"));
        assertThat(audit).contains("{malformed","ATTEMPT_FAILED","backoffMillis","FINAL",result.inputHash());
    }
    @Test void malformedSingleFieldDoesNotDiscardGoodFields() throws Exception {
        FieldExtractor fake=new FieldExtractor() {
            public String name(){return "test-model";}
            public String extract(String text) {
                String original=new LocalFieldExtractor().extract(text);
                return original.replace("\"budget\":{\"value\":\"USD 2400\",\"confidence\":0.93", "\"budget\":{\"value\":\"USD 2400\",\"confidence\":\"invalid\"");
            }
        };
        var result=pipeline(fake,image->new OcrEngine.Text(String.join("\n",concat(FIRST,SECOND)),.95))
                .process(pdf(concat(FIRST,SECOND)),"partial");
        assertThat(result.fields().get("companyName").value()).isEqualTo("Northstar Studio");
        assertThat(result.fields().get("budget").status()).isEqualTo("FAILED");
        assertThat(result.reviewQueue()).contains("budget");
        assertThat(result.reviewStatus()).isEqualTo("MANUAL_REVIEW");
    }
    @Test void failedPageKeepsEarlierFieldsAndFlagsUnknowns() throws Exception {
        var result=pipeline(new LocalFieldExtractor(),image->{throw new IOException("offline");})
                .process(pdf(FIRST,new String[]{}),"failed-page");
        assertThat(result.fields().get("companyName").status()).isEqualTo("ACCEPTED");
        assertThat(result.fields().get("budget").status()).isEqualTo("FAILED");
        assertThat(result.reviewStatus()).isEqualTo("MANUAL_REVIEW");
    }
    @Test void lowConfidenceIsLoggedAndTriggersOcrBeforeReview() throws Exception {
        AtomicInteger ocrCalls=new AtomicInteger();
        FieldExtractor fake=new FieldExtractor() {
            public String name(){return "test-model";}
            public String extract(String text){return new LocalFieldExtractor().extract(text).replace("0.93","0.4");}
        };
        var result=pipeline(fake,image->{ocrCalls.incrementAndGet();return new OcrEngine.Text(String.join("\n",concat(FIRST,SECOND)),.99);})
                .process(pdf(concat(FIRST,SECOND)),"low");
        assertThat(ocrCalls.get()).isEqualTo(1);
        assertThat(result.fields().get("budget").confidence()).isEqualTo(.4);
        assertThat(result.fields().get("budget").status()).isEqualTo("LOW_CONFIDENCE");
        assertThat(store.reviews()).hasSize(1);
    }
    @Test void unsupportedHallucinatedValuesCannotBeAccepted() throws Exception {
        FieldExtractor fake=new FieldExtractor() {
            public String name(){return "test-model";}
            public String extract(String text){return new LocalFieldExtractor().extract(text).replace("Northstar Studio","Invented Company");}
        };
        var result=pipeline(fake,image->{throw new IOException();}).process(pdf(concat(FIRST,SECOND)),"hallucinated");
        assertThat(result.fields().get("companyName").status()).isEqualTo("UNSUPPORTED");
        assertThat(result.reviewStatus()).isEqualTo("MANUAL_REVIEW");
    }
    @Test void duplicatesSurviveRestartAndAliasIdsAndRejectDifferentBytes() throws Exception {
        byte[] bytes=pdf(concat(FIRST,SECOND));
        var first=pipeline(new LocalFieldExtractor(),noOcr()).process(bytes,"first");
        store.close(); store=new ExtractionStore(settings);
        FieldExtractor never=new FieldExtractor(){public String name(){return "test";} public String extract(String t){throw new AssertionError("must use cache");}};
        var second=pipeline(never,noOcr()).process(bytes,"alias");
        assertThat(second).isEqualTo(first);
        assertThat(store.get("alias")).isEqualTo(first);
        assertThat(store.pending()).hasSize(1);
        assertThatThrownBy(()->pipeline(never,noOcr()).process(pdf(FIRST),"first")).isInstanceOf(ExtractionStore.IdConflict.class);
    }
    @Test void concurrentDuplicatesMakeOneExtractionAndOneRecord() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        FieldExtractor extractor=new FieldExtractor(){public String name(){return "test";} public String extract(String t){calls.incrementAndGet();return new LocalFieldExtractor().extract(t);}};
        var pipeline=pipeline(extractor,noOcr()); byte[] bytes=pdf(concat(FIRST,SECOND));
        try(var pool=Executors.newFixedThreadPool(4)) {
            List<Future<ExtractionResult>> results=new ArrayList<>();
            for(int i=0;i<8;i++) { String id="concurrent-"+i; results.add(pool.submit(()->pipeline.process(bytes,id))); }
            for(var result:results) assertThat(result.get().inputHash()).isNotBlank();
        }
        assertThat(calls.get()).isEqualTo(1);
        assertThat(store.pending()).hasSize(1);
    }
    @Test void explicitReprocessingUpdatesSameRowAndRetainsHistory() throws Exception {
        byte[] bytes=pdf(concat(FIRST,SECOND));
        var pipeline=pipeline(new LocalFieldExtractor(),noOcr());
        var first=pipeline.process(bytes,"rerun");
        long row=store.row(first.submissionId());int events=store.audit("rerun").size();
        pipeline.process(bytes,"rerun",true);
        assertThat(store.row("rerun")).isEqualTo(row);
        assertThat(store.audit("rerun").size()).isGreaterThan(events);
        assertThat(store.pending()).hasSize(1);
    }
    @Test void partialTableCellsEnterReviewAndDuplicateRowsArePreserved() throws Exception {
        var result=pipeline(new LocalFieldExtractor(),image->{throw new IOException();}).process(
                pdf(concat(FIRST,SECOND,new String[]{"Description | Quantity | Unit price","Setup | 1 | USD 10","Setup | 1 | USD 10","Training |  | USD 20"})),"rows");
        assertThat(result.lineItems()).hasSize(3);
        assertThat(result.reviewQueue()).contains("lineItems[2].quantity");
        assertThat(result.fields().get("companyName").status()).isEqualTo("ACCEPTED");
    }
    @Test void ocrWithoutATableCannotEraseAnIncompletePrimaryTable() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        var fake=new FieldExtractor() {
            public String name(){return "test-model";}
            public String extract(String text){
                if(calls.incrementAndGet()==1) return new LocalFieldExtractor().extract(String.join("\n",concat(FIRST,SECOND,
                        new String[]{"Description | Quantity | Unit price","damaged row"})));
                return new LocalFieldExtractor().extract(String.join("\n",concat(FIRST,SECOND)));
            }
        };
        var result=pipeline(fake,image->new OcrEngine.Text(String.join("\n",concat(FIRST,SECOND)),.99))
                .process(pdf(concat(FIRST,SECOND)),"lost-table");
        assertThat(result.reviewStatus()).isEqualTo("MANUAL_REVIEW");
        assertThat(result.issues()).anyMatch(i->i.contains("table incomplete"));
    }
    @Test void ocrRepairsOneWeakTableCellWithoutLosingGoodCells() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        String[] lines=concat(FIRST,SECOND,new String[]{"Description | Quantity | Unit price","Setup | 1 | USD 10"});
        var fake=new FieldExtractor() {
            public String name(){return "test-model";}
            public String extract(String text){
                String output=new LocalFieldExtractor().extract(text);
                return calls.incrementAndGet()==1?output.replace("\"quantity\":{\"value\":\"1\",\"confidence\":0.93",
                        "\"quantity\":{\"value\":\"1\",\"confidence\":0.4"):output;
            }
        };
        var result=pipeline(fake,image->new OcrEngine.Text(String.join("\n",lines),.99)).process(pdf(lines),"cell-repair");
        assertThat(result.reviewStatus()).isEqualTo("COMPLETE");
        assertThat(result.lineItems().getFirst().get("quantity").confidence()).isEqualTo(.93);
    }
    @Test void anOldSheetWriteCannotAcknowledgeANewerResult() throws Exception {
        var pipeline=pipeline(new LocalFieldExtractor(),noOcr());byte[] bytes=pdf(concat(FIRST,SECOND));
        var old=pipeline.process(bytes,"sheet-race");
        pipeline.process(bytes,"sheet-race",true);
        store.markSynced(old);
        assertThat(store.pending()).hasSize(1);
    }
    @Test void realLocalOcrReadsScannedImage() throws Exception {
        BufferedImage image=new BufferedImage(1700,1000,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); g.setColor(Color.WHITE);g.fillRect(0,0,1700,1000);
        g.setColor(Color.BLACK);g.setFont(new Font("Arial",Font.PLAIN,32));
        int y=65;for(String line:concat(FIRST,SECOND)){g.drawString(line,45,y);y+=80;} g.dispose();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        ImageIO.write(image,"png",Path.of("target/ocr-fixture.png").toFile());
        var result=pipeline(new LocalFieldExtractor(),new PythonOcrEngine(settings)).process(bytes.toByteArray(),"scan");
        assertThat(result.fields().get("companyName").value()).withFailMessage(new ObjectMapper().writeValueAsString(store.audit("scan"))).isEqualTo("Northstar Studio");
        assertThat(result.fields().get("contactEmail").value()).isEqualTo("alex@example.com");
        assertThat(result.fields().get("targetLaunchDate").value()).isEqualTo("2026-09-30");
        assertThat(new ObjectMapper().writeValueAsString(store.audit("scan"))).contains("OCR_FINISHED");
    }
    @Test void invalidFilesAndOversizedPdfPageCountsAreRejected() throws Exception {
        assertThatThrownBy(()->new DocumentReader().open("not a pdf".getBytes())).isInstanceOf(DocumentReader.BadDocument.class);
        String[][] pages=new String[21][];Arrays.fill(pages,FIRST);
        assertThatThrownBy(()->new DocumentReader().open(pdf(pages))).isInstanceOf(DocumentReader.BadDocument.class);
    }
}
