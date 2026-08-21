package com.apurva.onboarding.gateway;

import com.apurva.onboarding.config.OnboardingProperties;
import com.apurva.onboarding.domain.OnboardingRequest;
import com.apurva.onboarding.domain.ReviewOutcome;
import com.apurva.onboarding.domain.ReviewStatus;
import com.apurva.onboarding.service.AutomationUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class N8nAutomationGatewayTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsValidatedMultipartWithServerOnlyAuthentication() throws Exception {
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/review", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("X-Webhook-Token"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] response = """
                    {
                      "submissionId": "submission-123",
                      "reviewStatus": "COMPLETE",
                      "extractedFields": {"companyName": "Northstar Studio"},
                      "missingFields": [],
                      "reviewReason": "All required fields are present.",
                      "followUpDraft": ""
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String webhookUrl = "http://localhost:" + server.getAddress().getPort() + "/review";
        N8nAutomationGateway gateway = new N8nAutomationGateway(
                RestClient.create(),
                new OnboardingProperties(webhookUrl, "test-only-token", 5_242_880)
        );

        ReviewOutcome outcome = gateway.review(validRequest());

        assertThat(outcome.reviewStatus()).isEqualTo(ReviewStatus.COMPLETE);
        assertThat(authHeader.get()).isEqualTo("test-only-token");
        assertThat(requestBody.get())
                .contains("name=\"submissionId\"")
                .contains("submission-123")
                .contains("name=\"document\"")
                .contains("filename=\"brief.pdf\"");
    }

    @Test
    void refusesToCallAutomationWithoutServerConfiguration() {
        N8nAutomationGateway gateway = new N8nAutomationGateway(
                RestClient.create(),
                new OnboardingProperties("", "", 5_242_880)
        );

        assertThatThrownBy(() -> gateway.review(validRequest()))
                .isInstanceOf(AutomationUnavailableException.class);
    }

    @Test
    void mapsWebhookFailuresToTheSafeAutomationError() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/review", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        String webhookUrl = "http://localhost:" + server.getAddress().getPort() + "/review";
        N8nAutomationGateway gateway = new N8nAutomationGateway(
                RestClient.create(),
                new OnboardingProperties(webhookUrl, "test-only-token", 5_242_880)
        );

        assertThatThrownBy(() -> gateway.review(validRequest()))
                .isInstanceOf(AutomationUnavailableException.class);
    }

    private OnboardingRequest validRequest() {
        return new OnboardingRequest(
                "submission-123",
                "Northstar Studio",
                "https://northstar.example",
                "Alex Morgan",
                "alex@example.com",
                "Client onboarding automation",
                LocalDate.of(2026, 9, 30),
                "Fictional test submission.",
                new MockMultipartFile(
                        "document",
                        "brief.pdf",
                        "application/pdf",
                        "fictional brief".getBytes(StandardCharsets.UTF_8)
                )
        );
    }
}
