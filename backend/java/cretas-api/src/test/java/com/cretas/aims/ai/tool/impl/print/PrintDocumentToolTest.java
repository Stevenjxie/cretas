package com.cretas.aims.ai.tool.impl.print;

import com.cretas.aims.ai.dto.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PrintDocumentTool")
class PrintDocumentToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PrintDocumentTool tool;

    @BeforeEach
    void setUp() {
        tool = new PrintDocumentTool();
        ReflectionTestUtils.setField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("supports production work order in the AI schema")
    void schemaIncludesProductionWorkOrder() {
        Map<String, Object> schema = tool.getParametersSchema();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> documentType = (Map<String, Object>) properties.get("documentType");
        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) documentType.get("enum");

        assertThat(enumValues).contains("PRODUCTION_WORK_ORDER");
    }

    @Test
    @DisplayName("routes production work order to the production-work-order PDF endpoint")
    void productionWorkOrderReturnsWorkOrderDownloadUrl() throws Exception {
        ToolCall call = ToolCall.of(
                "call-1",
                "print_document",
                "{\"documentType\":\"PRODUCTION_WORK_ORDER\",\"documentId\":\"plan-001\"}");

        String response = tool.execute(call, Map.of("factoryId", "F006", "userId", 1001L));

        JsonNode root = objectMapper.readTree(response);
        assertThat(root.path("success").asBoolean()).isTrue();
        assertThat(root.path("data").path("downloadUrl").asText())
                .isEqualTo("/api/mobile/F006/print/production-work-order/plan-001");
        assertThat(root.path("data").path("documentType").asText())
                .isEqualTo("PRODUCTION_WORK_ORDER");
    }
}
