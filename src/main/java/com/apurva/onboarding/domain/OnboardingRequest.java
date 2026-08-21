package com.apurva.onboarding.domain;

import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

public record OnboardingRequest(
        String submissionId,
        String companyName,
        String website,
        String contactName,
        String contactEmail,
        String serviceRequested,
        LocalDate desiredStartDate,
        String notes,
        MultipartFile document
) {
}

