package com.apurva.onboarding.extraction;

import java.util.List;
import java.util.Map;

public record ExtractionResult(String submissionId, String inputHash, String documentType,
        String provider, String pipelineVersion, String createdAt, String reviewStatus,
        Map<String, Field> fields, List<Map<String, Field>> lineItems,
        List<String> missingFields, List<String> reviewQueue, List<String> issues,
        int pageCount, String approvalStatus) {
    public record Field(String value, double confidence, String confidenceSource,
                        String status, List<Integer> pages, String evidence) {}
}
