package com.apurva.onboarding.gateway;

import com.apurva.onboarding.domain.OnboardingRequest;
import com.apurva.onboarding.domain.ReviewOutcome;

public interface AutomationGateway {

    ReviewOutcome review(OnboardingRequest request);
}

