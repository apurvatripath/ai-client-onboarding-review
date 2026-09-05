package com.apurva.onboarding.extraction;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.file.Path;

@Component
public class ExtractionSettings {
    public final Path dataDir;
    public final String provider, apiKey, model, python;
    public final int attempts;
    public final long backoffMillis;
    public ExtractionSettings(Environment env) {
        dataDir = Path.of(env.getProperty("EXTRACTION_DATA_DIR", "data/extraction"));
        provider = env.getProperty("EXTRACTION_PROVIDER", "local");
        if (!provider.equals("local") && !provider.equals("gemini"))
            throw new IllegalArgumentException("EXTRACTION_PROVIDER must be local or gemini");
        apiKey = env.getProperty("EXTRACTION_GEMINI_API_KEY", "");
        model = env.getProperty("EXTRACTION_GEMINI_MODEL", "gemini-3.6-flash");
        if (!model.matches("[a-zA-Z0-9._-]+")) throw new IllegalArgumentException("Invalid model name");
        python = env.getProperty("EXTRACTION_PYTHON", "python");
        attempts = 3;
        backoffMillis = 500;
    }
}
