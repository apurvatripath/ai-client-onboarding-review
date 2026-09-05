package com.apurva.onboarding.extraction;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;

/** Retryable fixed-range writes, never append. An uncertain HTTP outcome reuses exactly the same row. */
@Component
public class SheetOutbox {
    private final ExtractionStore store;
    private final Environment env;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String base;
    private String token="";
    private Instant expires=Instant.EPOCH;
    @org.springframework.beans.factory.annotation.Autowired
    public SheetOutbox(ExtractionStore store,Environment env) { this(store,env,"https://sheets.googleapis.com/v4/spreadsheets/"); }
    SheetOutbox(ExtractionStore store,Environment env,String base) { this.store=store; this.env=env; this.base=base; }
    public synchronized Map<String,Object> sync() throws Exception {
        if(!env.getProperty("EXTRACTION_SHEETS_ENABLED","false").equals("true")) return Map.of("state","DISABLED","pending",store.pending().size());
        String spreadsheet=env.getRequiredProperty("EXTRACTION_DEMO_SPREADSHEET_ID");
        String tab=env.getProperty("EXTRACTION_DEMO_SHEET_TAB","ExtractionDemo");
        if(!spreadsheet.matches("[a-zA-Z0-9_-]+") || !tab.matches("[a-zA-Z][a-zA-Z0-9_]{0,60}")) throw new IllegalArgumentException("Invalid isolated Sheet configuration");
        store.bindSheet(spreadsheet+"/"+tab);
        List<String> headers=new ArrayList<>(List.of("submissionId","inputHash","createdAt","documentType","provider","reviewStatus"));
        headers.addAll(IntakeSchema.FIELDS); headers.addAll(List.of("fieldConfidence","reviewQueue","lineItemCount","approvalStatus"));
        String headerRange=tab+"!A1:R1";
        var current=json.readTree(request("GET",spreadsheet,headerRange,null));
        if(!current.path("values").isEmpty() && !current.path("values").path(0).equals(json.valueToTree(headers)))
            throw new IllegalStateException("Sheet headers differ: use a new dedicated demo tab");
        if(current.path("values").isEmpty()) request("PUT",spreadsheet,headerRange,Map.of("values",List.of(headers)));
        int sent=0;
        for(ExtractionResult result:store.pending().stream().limit(100).toList()) {
            long row=store.row(result.submissionId());
            String range=tab+"!A"+row+":R"+row;
            var existing=json.readTree(request("GET",spreadsheet,range,null));
            String existingId=existing.path("values").path(0).path(0).asText("");
            if(!existingId.isBlank() && !existingId.equals(result.submissionId())) throw new IllegalStateException("Sheet row ownership changed; restore row order before retrying");
            List<Object> values=new ArrayList<>(List.of(result.submissionId(),result.inputHash(),result.createdAt(),result.documentType(),result.provider(),result.reviewStatus()));
            Map<String,Double> confidences=new LinkedHashMap<>();
            for(String field:IntakeSchema.FIELDS) {
                values.add(Objects.toString(result.fields().get(field).value(),""));
                confidences.put(field,result.fields().get(field).confidence());
            }
            values.add(json.writeValueAsString(confidences)); values.add(String.join(", ",result.reviewQueue()));
            values.add(result.lineItems().size()); values.add("PENDING");
            if(values.stream().anyMatch(v->v.toString().length()>40_000)) throw new IllegalStateException("Sheet cell too large; full result remains in the local audit store");
            store.audit(result.submissionId(),Map.of("timestamp",Instant.now().toString(),"stage","SHEET_WRITE_STARTED","inputHash",result.inputHash(),"row",row));
            request("PUT",spreadsheet,range,Map.of("values",List.of(values)));
            store.audit(result.submissionId(),Map.of("timestamp",Instant.now().toString(),"stage","SHEET_WRITE_CONFIRMED","inputHash",result.inputHash(),"row",row));
            store.markSynced(result); sent++;
        }
        return Map.of("state","SYNCED","written",sent,"pending",store.pending().size());
    }
    private String request(String method,String spreadsheet,String range,Object body) throws Exception {
        URI uri=URI.create(base+spreadsheet+"/values/"+URLEncoder.encode(range,StandardCharsets.UTF_8)+"?valueInputOption=RAW");
        for(int attempt=0;attempt<3;attempt++) {
            var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+accessToken());
            if(body==null) builder.GET();
            else builder.header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            try {
                var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
                int status=response.statusCode();
                if(status>=200 && status<300) return response.body();
                if(status==401) expires=Instant.EPOCH;
                if(status!=401 && status!=429 && status!=408 && status<500) throw new IllegalStateException("Sheet HTTP "+status+"; record remains pending");
                if(attempt==2) throw new IllegalStateException("Sheet retries exhausted; record remains pending");
            } catch(java.io.IOException e) { if(attempt==2) throw e; }
            Thread.sleep(500L*(1L<<attempt));
        }
        throw new IllegalStateException("Sheet retries exhausted");
    }
    private String accessToken() throws Exception {
        // Access-token override is useful for isolated local contract tests and short manual sessions.
        String supplied=env.getProperty("EXTRACTION_SHEETS_ACCESS_TOKEN","");
        if(!supplied.isBlank()) return supplied;
        if(Instant.now().isBefore(expires.minusSeconds(60))) return token;
        Path file=Path.of(env.getRequiredProperty("EXTRACTION_SHEETS_SERVICE_ACCOUNT_FILE"));
        var credentials=json.readTree(Files.readString(file));
        if(!credentials.path("type").asText().equals("service_account")) throw new IllegalArgumentException("Use a demo-only service account file");
        String email=credentials.path("client_email").asText();
        long now=Instant.now().getEpochSecond();
        String header=b64(json.writeValueAsBytes(Map.of("alg","RS256","typ","JWT")));
        String claims=b64(json.writeValueAsBytes(Map.of("iss",email,"scope","https://www.googleapis.com/auth/spreadsheets",
                "aud","https://oauth2.googleapis.com/token","iat",now,"exp",now+3600)));
        String pem=credentials.path("private_key").asText().replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");
        PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Signature signer=Signature.getInstance("SHA256withRSA"); signer.initSign(key); signer.update((header+"."+claims).getBytes(StandardCharsets.UTF_8));
        String jwt=header+"."+claims+"."+b64(signer.sign());
        var request=HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token")).timeout(Duration.ofSeconds(30))
                .header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="+jwt)).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200) throw new IllegalStateException("Demo Sheet credential refresh failed");
        var payload=json.readTree(response.body()); token=payload.path("access_token").asText();
        if(token.isBlank()) throw new IllegalStateException("No Sheet access token returned");
        expires=Instant.now().plusSeconds(payload.path("expires_in").asLong(3600));
        return token;
    }
    private String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
