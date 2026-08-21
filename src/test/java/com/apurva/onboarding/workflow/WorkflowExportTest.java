package com.apurva.onboarding.workflow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowExportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exportIsImportableJsonWithoutEmbeddedSecrets() throws Exception {
        Path export = Path.of(".n8n", "ai-client-onboarding-document-review.json");
        String source = Files.readString(export);
        JsonNode workflow = objectMapper.readTree(source);

        assertThat(workflow.path("name").asText()).isEqualTo("AI Client Onboarding & Document Review");
        assertThat(workflow.path("active").asBoolean()).isFalse();
        assertThat(workflow.path("nodes")).hasSize(11);
        assertThat(source)
                .doesNotContain("credentials\":")
                .doesNotContain("webhook-test/")
                .doesNotContain("webhook/")
                .doesNotContain("@gmail.com")
                .contains("CONFIGURE_AFTER_IMPORT");
    }
}
