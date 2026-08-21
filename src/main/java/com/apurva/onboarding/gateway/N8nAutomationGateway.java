package com.apurva.onboarding.gateway;

import com.apurva.onboarding.config.OnboardingProperties;
import com.apurva.onboarding.domain.OnboardingRequest;
import com.apurva.onboarding.domain.ReviewOutcome;
import com.apurva.onboarding.service.AutomationUnavailableException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;

@Component
public class N8nAutomationGateway implements AutomationGateway {

    private final RestClient restClient;
    private final OnboardingProperties properties;

    public N8nAutomationGateway(RestClient restClient, OnboardingProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public ReviewOutcome review(OnboardingRequest request) {
        if (isBlank(properties.webhookUrl()) || isBlank(properties.webhookAuthToken())) {
            throw new AutomationUnavailableException();
        }

        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("submissionId", request.submissionId());
            body.add("companyName", request.companyName());
            body.add("website", request.website());
            body.add("contactName", request.contactName());
            body.add("contactEmail", request.contactEmail());
            body.add("serviceRequested", request.serviceRequested());
            body.add("desiredStartDate", request.desiredStartDate().toString());
            body.add("notes", request.notes() == null ? "" : request.notes());
            body.add("document", new NamedByteArrayResource(
                    request.document().getBytes(),
                    request.document().getOriginalFilename()
            ));

            ReviewOutcome outcome = restClient.post()
                    .uri(properties.webhookUrl())
                    .header("X-Webhook-Token", properties.webhookAuthToken())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(ReviewOutcome.class);

            if (outcome == null) {
                throw new AutomationUnavailableException();
            }
            return outcome;
        } catch (IOException | RestClientException exception) {
            throw new AutomationUnavailableException(exception);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename == null ? "document" : filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}

