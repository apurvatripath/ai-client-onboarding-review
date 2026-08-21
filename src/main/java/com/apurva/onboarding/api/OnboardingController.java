package com.apurva.onboarding.api;

import com.apurva.onboarding.domain.ReviewOutcome;
import com.apurva.onboarding.service.OnboardingService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public OnboardingSuccessResponse submit(@Valid @ModelAttribute OnboardingSubmissionForm form) {
        ReviewOutcome outcome = onboardingService.process(form);
        return OnboardingSuccessResponse.from(outcome);
    }
}

