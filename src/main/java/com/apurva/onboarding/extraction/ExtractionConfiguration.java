package com.apurva.onboarding.extraction;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExtractionConfiguration {
    @Bean FieldExtractor fieldExtractor(ExtractionSettings settings) {
        return settings.provider.equals("gemini") ? new GeminiFieldExtractor(settings) : new LocalFieldExtractor();
    }
}
