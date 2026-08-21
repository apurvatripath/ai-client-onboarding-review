package com.apurva.onboarding.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

public record OnboardingSubmissionForm(
        @NotBlank @Size(min = 2, max = 120) String companyName,
        @NotBlank @Size(max = 250) String website,
        @NotBlank @Size(min = 2, max = 80) String contactName,
        @NotBlank @Email @Size(max = 160) String contactEmail,
        @NotBlank @Size(min = 2, max = 120) String serviceRequested,
        @NotNull @FutureOrPresent @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desiredStartDate,
        @Size(max = 1000) String notes,
        @NotNull MultipartFile document
) {
}

