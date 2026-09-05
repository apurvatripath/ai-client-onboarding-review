package com.apurva.onboarding.extraction;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

@Component
public class DocumentReader {
    public Document open(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > 5_242_880) throw new BadDocument("Document must be 1 byte to 5 MiB");
        if (bytes.length >= 5 && new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) {
            PDDocument pdf;
            try { pdf = Loader.loadPDF(bytes); }
            catch (IOException e) { throw new BadDocument("PDF is damaged or password protected"); }
            if (pdf.isEncrypted() || pdf.getNumberOfPages() < 1 || pdf.getNumberOfPages() > 20) {
                pdf.close(); throw new BadDocument("Use an unencrypted PDF with 1 to 20 pages");
            }
            return new Document(pdf, null);
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BadDocument("Bytes are not a supported PDF, PNG or JPEG");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                if (!Set.of("png", "jpeg", "jpg").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw new BadDocument("Use PNG or JPEG");
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 12_000_000) throw new BadDocument("Image exceeds 12 megapixels");
                BufferedImage decoded=reader.read(0);
                if(decoded==null) throw new BadDocument("Image could not be decoded");
                return new Document(null, decoded);
            } finally { reader.dispose(); }
        } catch(BadDocument e) { throw e; }
        catch(IOException | RuntimeException e) { throw new BadDocument("Image is damaged or unsupported"); }
    }
    public static class Document implements AutoCloseable {
        private final PDDocument pdf;
        private final BufferedImage image;
        Document(PDDocument pdf, BufferedImage image) { this.pdf = pdf; this.image = image; }
        public int pages() { return pdf == null ? 1 : pdf.getNumberOfPages(); }
        public String text(int page) throws IOException {
            if (pdf == null) return "";
            PDFTextStripper stripper = new LayoutTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(page); stripper.setEndPage(page);
            String text = stripper.getText(pdf);
            if (text.length() > 40_000) throw new IOException("Page text exceeds 40000 characters");
            return text;
        }
        public BufferedImage image(int page) throws IOException {
            if (pdf == null) return image;
            var box = pdf.getPage(page-1).getCropBox();
            double pixels = box.getWidth() * (double)box.getHeight() * Math.pow(200.0/72,2);
            if (!Double.isFinite(pixels) || pixels <= 0 || pixels > 12_000_000) throw new IOException("Rendered page exceeds 12 megapixels");
            return new PDFRenderer(pdf).renderImageWithDPI(page-1,200);
        }
        @Override public void close() throws IOException { if (pdf != null) pdf.close(); }
    }
    /** Preserve genuine column gaps instead of flattening a table into one prose line. */
    static class LayoutTextStripper extends PDFTextStripper {
        private TextPosition previous;
        @Override protected void writeString(String text, java.util.List<TextPosition> positions) throws IOException {
            StringBuilder line=new StringBuilder();
            for(TextPosition position:positions) {
                if(previous!=null) {
                    float gap=position.getXDirAdj()-previous.getXDirAdj()-previous.getWidthDirAdj();
                    if(gap>Math.max(18,previous.getWidthOfSpace()*4)) line.append(" | ");
                    else if(gap>Math.max(1,previous.getWidthOfSpace()*.5)
                            && !previous.getUnicode().endsWith(" ") && !position.getUnicode().startsWith(" ")) line.append(' ');
                }
                line.append(position.getUnicode()); previous=position;
            }
            super.writeString(line.toString());
        }
        @Override protected void writeWordSeparator() { /* gaps come from actual glyph coordinates */ }
        @Override protected void writeLineSeparator() throws IOException { previous=null;super.writeLineSeparator(); }
    }
    public static class BadDocument extends IOException { public BadDocument(String message) { super(message); } }
}
