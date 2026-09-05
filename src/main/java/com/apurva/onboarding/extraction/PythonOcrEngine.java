package com.apurva.onboarding.extraction;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

@Component
public class PythonOcrEngine implements OcrEngine {
    private final ExtractionSettings settings;
    public PythonOcrEngine(ExtractionSettings settings) { this.settings=settings; }
    @Override public Text read(BufferedImage image) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory(settings.dataDir, "ocr-");
        Path input=dir.resolve("page.png"), output=dir.resolve("result.json"), log=dir.resolve("stderr.txt");
        Process process=null;
        try {
            ImageIO.write(image,"png",input.toFile());
            process = new ProcessBuilder(settings.python, Path.of("scripts/ocr.py").toAbsolutePath().toString(),
                    input.toAbsolutePath().toString()).redirectOutput(output.toFile()).redirectError(log.toFile()).start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) throw new IOException("OCR_TIMEOUT");
            if (process.exitValue()!=0) throw new IOException("OCR_UNAVAILABLE: run scripts/setup-ocr.ps1");
            var root = new ObjectMapper().readTree(Files.readString(output));
            String text=root.path("text").asText("");
            double confidence=root.path("confidence").asDouble(0);
            if(text.length()>40_000 || !Double.isFinite(confidence) || confidence<0 || confidence>1) throw new IOException("OCR_INVALID_OUTPUT");
            return new Text(text, confidence);
        } finally {
            if (process!=null && process.isAlive()) {
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(input); Files.deleteIfExists(output); Files.deleteIfExists(log); Files.deleteIfExists(dir);
        }
    }
}
