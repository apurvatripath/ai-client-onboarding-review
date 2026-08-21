package com.apurva.onboarding.service;

import com.apurva.onboarding.api.OnboardingSubmissionForm;
import com.apurva.onboarding.config.OnboardingProperties;
import com.apurva.onboarding.domain.ReviewOutcome;
import com.apurva.onboarding.domain.ReviewStatus;
import com.apurva.onboarding.gateway.AutomationGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnboardingServiceTest {

    private CapturingGateway gateway;
    private OnboardingService service;

    @BeforeEach
    void setUp() {
        gateway = new CapturingGateway();
        service = new OnboardingService(
                gateway,
                new OnboardingProperties("https://example.test/webhook", "token", 5_242_880)
        );
    }

    @Test
    void forwardsAValidatedRequestToAutomation() {
        MockMultipartFile document = new MockMultipartFile(
                "document",
                "brief.pdf",
                "application/pdf",
                "fictional brief".getBytes()
        );

        ReviewOutcome result = service.process(validForm(document));

        assertThat(result.reviewStatus()).isEqualTo(ReviewStatus.COMPLETE);
        assertThat(gateway.request.companyName()).isEqualTo("Northstar Studio");
        assertThat(gateway.request.contactEmail()).isEqualTo("alex@example.com");
        assertThat(gateway.request.submissionId()).isNotBlank();
    }

    @Test
    void rejectsUnsupportedDocumentsBeforeCallingAutomation() {
        MockMultipartFile document = new MockMultipartFile(
                "document",
                "brief.exe",
                "application/octet-stream",
                "not allowed".getBytes()
        );

        assertThatThrownBy(() -> service.process(validForm(document)))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessage("Only PDF, JPG and PNG files are accepted.");
        assertThat(gateway.request).isNull();
    }

    @Test
    void rejectsIncompleteWebsiteUrls() {
        OnboardingSubmissionForm form = new OnboardingSubmissionForm(
                "Northstar Studio",
                "northstar.example",
                "Alex Morgan",
                "alex@example.com",
                "Client onboarding automation",
                LocalDate.now().plusDays(7),
                "",
                new MockMultipartFile("document", "brief.pdf", "application/pdf", "brief".getBytes())
        );

        assertThatThrownBy(() -> service.process(form))
                .isInstanceOf(InvalidSubmissionException.class)
                .hasMessage("must be a complete http or https URL");
        assertThat(gateway.request).isNull();
    }

    private OnboardingSubmissionForm validForm(MockMultipartFile document) {
        return new OnboardingSubmissionForm(
                " Northstar Studio ",
                "https://northstar.example",
                " Alex Morgan ",
                "ALEX@EXAMPLE.COM",
                "Client onboarding automation",
                LocalDate.now().plusDays(7),
                "Fictional demonstration submission.",
                document
        );
    }

    private static final class CapturingGateway implements AutomationGateway {
        private com.apurva.onboarding.domain.OnboardingRequest request;

        @Override
        public ReviewOutcome review(com.apurva.onboarding.domain.OnboardingRequest request) {
            this.request = request;
            return new ReviewOutcome(
                    request.submissionId(),
                    ReviewStatus.COMPLETE,
                    Map.of("companyName", request.companyName()),
                    List.of(),
                    "All required fields are present.",
                    ""
            );
        }
    }
}

