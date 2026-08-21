package com.apurva.onboarding.domain;

import java.util.List;
import java.util.Map;

public record ReviewOutcome(
        String submissionId,
        ReviewStatus reviewStatus,
        Map<String, String> extractedFields,
        List<String> missingFields,
        String reviewReason,
        String followUpDraft
) {
}

