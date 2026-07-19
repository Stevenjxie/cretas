package com.cretas.aims.ai.capability;

import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventory;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventoryLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FactoryCapabilityPackLoaderTest {
    private final FactoryCapabilityPackLoader loader = new FactoryCapabilityPackLoader();
    private final ToolDescriptorInventory inventory = new ToolDescriptorInventoryLoader().loadDefault();

    @Test
    void rejectsDuplicateUnknownAliasAndOversizedYaml() throws IOException {
        String valid = resource("ai/capability-packs/operator-v1.yaml");
        assertInvalid(valid.replace(
                "version: \"1.0.0\"", "version: \"1.0.0\"\nversion: \"1.0.1\""));
        assertInvalid(valid.replace(
                "status: PUBLISHED", "status: PUBLISHED\nunknownRoot: true"));
        assertInvalid(valid.replace("status: PUBLISHED", "status: &published PUBLISHED"));
        assertThatThrownBy(() -> loader.load(
                new StringReader("x".repeat(FactoryCapabilityPackLoader.YAML_CODE_POINT_LIMIT + 1)),
                "oversized.yaml", inventory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void rejectsIllegalVersionStatusRoleBusinessTypeAndOutputSchema() throws IOException {
        String valid = resource("ai/capability-packs/operator-v1.yaml");
        assertInvalid(valid.replace("version: \"1.0.0\"", "version: \"v1\""));
        assertInvalid(valid.replace("status: PUBLISHED", "status: ACTIVE"));
        assertInvalid(valid.replace("roles: [operator, yield_operator]", "roles: [invented_role]"));
        assertInvalid(valid.replace(
                "businessTypes: [FACTORY, CENTRAL_KITCHEN]", "businessTypes: [RESTAURANT]"));
        assertInvalid(valid.replace("type: STRING", "type: MAGIC"));
        assertInvalid(valid.replace("name: \"summary\"", "name: \"bad field\""));
    }

    @Test
    void rejectsMissingInventoryNonReadAndWriteLikeTools() throws IOException {
        String valid = resource("ai/capability-packs/operator-v1.yaml");
        assertInvalid(valid.replace("processing_batch_list", "not_in_inventory"));
        assertInvalid(valid.replace("processing_batch_list", "restaurant_cost_rigidity_analysis"));
        assertInvalid(valid.replace("processing_batch_list", "processing_batch_start"));
    }

    @Test
    void rejectsEvalReferencesOutsidePackContracts() throws IOException {
        String valid = resource("ai/capability-packs/operator-v1.yaml");
        assertInvalid(valid.replace(
                "expectedReadTools: [\"workreport_progress\"]",
                "expectedReadTools: [\"report_inventory\"]"));
        assertInvalid(valid.replace(
                "expectedWorkflowReference: \"FORM:PRODUCTION_REPORT\"",
                "expectedWorkflowReference: \"FORM:UNKNOWN\""));
    }

    private void assertInvalid(String source) {
        assertThatThrownBy(() -> loader.load(
                new StringReader(source), "test.yaml", inventory))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String resource(String path) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IOException("missing test resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
