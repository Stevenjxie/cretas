package com.cretas.aims.ai.tool.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCommandDigestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void objectKeyOrderDoesNotChangeDigestButArrayOrderDoes() throws Exception {
        JsonNode left = objectMapper.readTree("{\"z\":true,\"a\":{\"n\":1.00,\"v\":null},\"items\":[1,2]}");
        JsonNode reordered = objectMapper.readTree("{\"items\":[1,2],\"a\":{\"v\":null,\"n\":1.00},\"z\":true}");
        JsonNode arrayChanged = objectMapper.readTree("{\"items\":[2,1],\"a\":{\"v\":null,\"n\":1.00},\"z\":true}");

        assertThat(ToolCommandDigest.parametersHash(left))
                .isEqualTo(ToolCommandDigest.parametersHash(reordered))
                .isNotEqualTo(ToolCommandDigest.parametersHash(arrayChanged));
    }

    @Test
    void scalarTypesAndNumericRepresentationRemainPartOfCanonicalJson() throws Exception {
        JsonNode number = objectMapper.readTree("{\"value\":1}");
        JsonNode decimal = objectMapper.readTree("{\"value\":1.00}");
        JsonNode string = objectMapper.readTree("{\"value\":\"1\"}");
        JsonNode bool = objectMapper.readTree("{\"value\":true}");
        JsonNode nil = objectMapper.readTree("{\"value\":null}");

        assertThat(ToolCommandDigest.parametersHash(number))
                .isNotEqualTo(ToolCommandDigest.parametersHash(decimal))
                .isNotEqualTo(ToolCommandDigest.parametersHash(string))
                .isNotEqualTo(ToolCommandDigest.parametersHash(bool))
                .isNotEqualTo(ToolCommandDigest.parametersHash(nil));
    }

    @Test
    void everyTrustedCommandBindingChangesTheDigest() throws Exception {
        JsonNode parameters = objectMapper.readTree("{\"quantity\":2}");
        String baseline = ToolCommandDigest.commandDigest(
                "F001", 7L, "order_create", "2.1.0", ToolExecutionMode.EXECUTE, parameters);

        assertThat(ToolCommandDigest.commandDigest(
                "F002", 7L, "order_create", "2.1.0", ToolExecutionMode.EXECUTE, parameters))
                .isNotEqualTo(baseline);
        assertThat(ToolCommandDigest.commandDigest(
                "F001", 8L, "order_create", "2.1.0", ToolExecutionMode.EXECUTE, parameters))
                .isNotEqualTo(baseline);
        assertThat(ToolCommandDigest.commandDigest(
                "F001", 7L, "order_delete", "2.1.0", ToolExecutionMode.EXECUTE, parameters))
                .isNotEqualTo(baseline);
        assertThat(ToolCommandDigest.commandDigest(
                "F001", 7L, "order_create", "2.2.0", ToolExecutionMode.EXECUTE, parameters))
                .isNotEqualTo(baseline);
        assertThat(ToolCommandDigest.commandDigest(
                "F001", 7L, "order_create", "2.1.0", ToolExecutionMode.PREVIEW, parameters))
                .isNotEqualTo(baseline);
    }

    @Test
    void fingerprintNeverContainsBearerToken() {
        String token = "secret-confirmation-token";
        assertThat(ToolCommandDigest.tokenFingerprint(token))
                .hasSize(12)
                .doesNotContain(token);
        assertThat(ToolCommandDigest.persistentSecretFingerprint(token))
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .doesNotContain(token)
                .isNotEqualTo(ToolCommandDigest.tokenFingerprint(token));
    }
}
