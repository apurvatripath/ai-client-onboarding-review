package com.apurva.onboarding.extraction;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import static com.apurva.onboarding.extraction.ExtractionPipelineTest.*;
import static org.assertj.core.api.Assertions.*;

/** Reproducible fictional corpus for the HTTP harness, not real-client accuracy evidence. */
class SyntheticCorpusTest {
    @Test void generateIndependentGroundTruthAndDocuments() throws Exception {
        Path directory=Path.of("target/accuracy-fixtures");Files.createDirectories(directory);
        Map<String,Object> fields=new LinkedHashMap<>();
        fields.put("companyName","Northstar Studio");fields.put("contactName","Alex Morgan");
        fields.put("contactEmail","alex@example.com");fields.put("serviceRequested","Client onboarding automation");
        fields.put("budget","USD 2400");fields.put("targetLaunchDate","2026-09-30");
        fields.put("projectSummary","Build a validated intake workflow.");fields.put("dependencies","Approved checklist");
        List<Object> table=List.of(Map.of("description","Workflow setup","quantity","1","unitPrice","USD 1800"),
                Map.of("description","Review training","quantity","2","unitPrice","USD 300"));
        List<Object> documents=new ArrayList<>();
        byte[] single=pdf(concat(FIRST,SECOND)); Files.write(directory.resolve("single.pdf"),single);
        documents.add(answer("single.pdf",fields,List.of(),"COMPLETE"));
        Files.write(directory.resolve("multi-table.pdf"),pdf(FIRST,concat(SECOND,TABLE)));
        documents.add(answer("multi-table.pdf",fields,table,"COMPLETE"));
        try(PDDocument source=Loader.loadPDF(single)) {
            var image=new PDFRenderer(source).renderImageWithDPI(0,200);
            ImageIO.write(image,"png",directory.resolve("scanned.png").toFile());
        }
        documents.add(answer("scanned.png",fields,List.of(),"COMPLETE"));
        try(PDDocument mixed=Loader.loadPDF(pdf(FIRST));PDDocument page=Loader.loadPDF(pdf(concat(SECOND,TABLE)))) {
            var image=new PDFRenderer(page).renderImageWithDPI(0,200);
            PDPage scan=new PDPage();mixed.addPage(scan);
            try(var canvas=new PDPageContentStream(mixed,scan)){canvas.drawImage(LosslessFactory.createFromImage(mixed,image),0,0,612,792);}
            mixed.save(directory.resolve("mixed-table.pdf").toFile());
        }
        documents.add(answer("mixed-table.pdf",fields,table,"COMPLETE"));
        try(PDDocument columns=Loader.loadPDF(pdf(FIRST,SECOND))) {
            PDPage page=new PDPage();columns.addPage(page);
            try(var canvas=new PDPageContentStream(columns,page)) {
                String[][] cells={{"Description","Quantity","Unit price"},{"Workflow setup","1","USD 1800"},{"Review training","2","USD 300"}};
                for(int r=0;r<cells.length;r++) for(int c=0;c<3;c++) {
                    canvas.beginText();canvas.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                    canvas.newLineAtOffset(new int[]{40,310,420}[c],730-r*35);canvas.showText(cells[r][c]);canvas.endText();
                }
            }
            columns.save(directory.resolve("positioned-table.pdf").toFile());
            ImageIO.write(new PDFRenderer(columns).renderImageWithDPI(2,200),"png",Path.of("target/positioned-table-page3.png").toFile());
            try(PDDocument mixedColumns=Loader.loadPDF(pdf(FIRST,SECOND))) {
                PDPage scan=new PDPage();mixedColumns.addPage(scan);
                try(var canvas=new PDPageContentStream(mixedColumns,scan)) {
                    canvas.drawImage(LosslessFactory.createFromImage(mixedColumns,new PDFRenderer(columns).renderImageWithDPI(2,200)),0,0,612,792);
                }
                mixedColumns.save(directory.resolve("scanned-positioned-table.pdf").toFile());
            }
        }
        documents.add(answer("positioned-table.pdf",fields,table,"COMPLETE"));
        documents.add(answer("scanned-positioned-table.pdf",fields,table,"COMPLETE"));
        Files.write(directory.resolve("missing.pdf"),pdf(FIRST));
        Map<String,Object> missing=new LinkedHashMap<>(fields);
        for(String field:List.of("budget","targetLaunchDate","projectSummary","dependencies"))missing.put(field,null);
        documents.add(answer("missing.pdf",missing,List.of(),"MISSING_INFORMATION"));
        Files.write(directory.resolve("conflict.pdf"),pdf(concat(FIRST,SECOND),new String[]{"Budget: USD 9000"}));
        documents.add(answer("conflict.pdf",fields,List.of(),"MANUAL_REVIEW"));
        var corpus=Map.of("documentType","INTAKE_FORM","label","SYNTHETIC ONLY - eight fictional forms; not a real-document benchmark","documents",documents);
        Files.writeString(Path.of("target/accuracy-answer-key.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(corpus));
        assertThat(documents).hasSize(8);
    }
    private Map<String,Object> answer(String file,Map<String,Object> fields,List<Object> rows,String status) {
        return Map.of("file",file,"fields",fields,"lineItems",rows,"reviewStatus",status);
    }
}
