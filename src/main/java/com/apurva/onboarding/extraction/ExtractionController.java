package com.apurva.onboarding.extraction;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api/extractions")
public class ExtractionController {
    private final ExtractionPipeline pipeline;
    private final ExtractionStore store;
    private final SheetOutbox sheets;
    public ExtractionController(ExtractionPipeline pipeline,ExtractionStore store,SheetOutbox sheets) {
        this.pipeline=pipeline; this.store=store; this.sheets=sheets;
    }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExtractionResult extract(@RequestParam("document") MultipartFile document,
            @RequestParam(value="submissionId",required=false) String submissionId,
            @RequestParam(value="reprocess",defaultValue="false") boolean reprocess) throws Exception {
        return pipeline.process(document.getBytes(),submissionId,reprocess);
    }
    @GetMapping("/review-queue") public Object reviews() throws Exception { return store.reviews(); }
    @GetMapping("/pending-sheet") public Object pending() throws Exception { return store.pending(); }
    @PostMapping("/sync-sheet") public Object sync() throws Exception { return sheets.sync(); }
    @GetMapping("/{id}/audit") public Object audit(@PathVariable String id) throws Exception { return store.audit(id); }
    @GetMapping("/{id}") public ExtractionResult result(@PathVariable String id) throws Exception {
        var result=store.get(id);
        if(result==null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
        return result;
    }
    @GetMapping("/{id}/document") public ResponseEntity<byte[]> document(@PathVariable String id) throws Exception {
        byte[] bytes=store.documentBytes(id);
        if(bytes==null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().contentType(sniff(bytes)).body(bytes);
    }
    @PostMapping("/{id}/review") public ExtractionResult review(@PathVariable String id, @RequestBody ReviewRequest body) throws Exception {
        return pipeline.review(id, body.fields(), body.lineItems(), body.approve());
    }
    private MediaType sniff(byte[] bytes) {
        if(bytes.length>=5 && new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) return MediaType.APPLICATION_PDF;
        if(bytes.length>=4 && (bytes[0]&0xFF)==0x89 && bytes[1]=='P' && bytes[2]=='N' && bytes[3]=='G') return MediaType.IMAGE_PNG;
        return MediaType.IMAGE_JPEG;
    }
    public record ReviewRequest(Map<String,String> fields, List<Map<String,String>> lineItems, boolean approve) {}
    @ExceptionHandler(ExtractionStore.IdConflict.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Object conflict() { return Map.of("code","IDEMPOTENCY_CONFLICT","message","This submissionId already belongs to different document bytes"); }
    @ExceptionHandler({DocumentReader.BadDocument.class,IllegalArgumentException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object badRequest(Exception e) { return Map.of("code","INVALID_DOCUMENT","message",e.getMessage()); }
}
