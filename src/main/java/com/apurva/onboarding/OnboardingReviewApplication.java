package com.apurva.onboarding;

import com.apurva.onboarding.config.OnboardingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(OnboardingProperties.class)
public class OnboardingReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(OnboardingReviewApplication.class, args);
    }
}

