package com.apurva.onboarding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.onboarding")
public record OnboardingProperties(
        String webhookUrl,
        String webhookAuthToken,
        long maxFileSizeBytes
) {
}

