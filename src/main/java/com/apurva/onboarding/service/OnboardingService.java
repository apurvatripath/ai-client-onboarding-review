package com.apurva.onboarding.service;

import com.apurva.onboarding.api.OnboardingSubmissionForm;
import com.apurva.onboarding.config.OnboardingProperties;
import com.apurva.onboarding.domain.OnboardingRequest;
import com.apurva.onboarding.domain.ReviewOutcome;
import com.apurva.onboarding.gateway.AutomationGateway;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class OnboardingService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png"
    );

    private final AutomationGateway automationGateway;
    private final OnboardingProperties properties;

    public OnboardingService(AutomationGateway automationGateway, OnboardingProperties properties) {
        this.automationGateway = automationGateway;
        this.properties = properties;
    }

    public ReviewOutcome process(OnboardingSubmissionForm form) {
        validateWebsite(form.website());
        validateDocument(form);

        String submissionId = UUID.randomUUID().toString();
        OnboardingRequest request = new OnboardingRequest(
                submissionId,
                form.companyName().trim(),
                form.website().trim(),
                form.contactName().trim(),
                form.contactEmail().trim().toLowerCase(Locale.ROOT),
                form.serviceRequested().trim(),
                form.desiredStartDate(),
                form.notes() == null ? "" : form.notes().trim(),
                form.document()
        );
        return automationGateway.review(request);
    }

    private void validateWebsite(String website) {
        try {
            URI uri = new URI(website);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new InvalidSubmissionException("website", "must be a complete http or https URL");
            }
        } catch (URISyntaxException exception) {
            throw new InvalidSubmissionException("website", "must be a complete http or https URL");
        }
    }

    private void validateDocument(OnboardingSubmissionForm form) {
        if (form.document().isEmpty()) {
            throw new InvalidDocumentException("Choose one PDF, JPG or PNG file.");
        }
        if (form.document().getSize() > properties.maxFileSizeBytes()) {
            throw new InvalidDocumentException("The document must be 5 MB or smaller.");
        }
        String contentType = form.document().getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new InvalidDocumentException("Only PDF, JPG and PNG files are accepted.");
        }
    }
}

