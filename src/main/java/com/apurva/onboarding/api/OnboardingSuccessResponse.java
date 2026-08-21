package com.apurva.onboarding.api;

import com.apurva.onboarding.domain.ReviewOutcome;
import com.apurva.onboarding.domain.ReviewStatus;

import java.util.List;
import java.util.Map;

public record OnboardingSuccessResponse(
        boolean success,
        String submissionId,
        ReviewStatus reviewStatus,
        Map<String, String> extractedFields,
        List<String> missingFields,
        String reviewReason,
        String followUpDraft,
        String approvalStatus
) {
    static OnboardingSuccessResponse from(ReviewOutcome outcome) {
        return new OnboardingSuccessResponse(
                true,
                outcome.submissionId(),
                outcome.reviewStatus(),
                outcome.extractedFields(),
                outcome.missingFields(),
                outcome.reviewReason(),
                outcome.followUpDraft(),
                "PENDING"
        );
    }
}

