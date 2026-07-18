package com.cretas.aims.ai.tool.gateway.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MCPExternalPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void canonicalDigestSortsObjectKeysRecursivelyButPreservesArrayOrderAndScalarTypes() {
        Map<String, Object> first = Map.of(
                "type", "object",
                "properties", Map.of(
                        "b", Map.of("type", "number", "examples", List.of(1, "1", true)),
                        "a", Map.of("type", "string")));
        Map<String, Object> reordered = Map.of(
                "properties", Map.of(
                        "a", Map.of("type", "string"),
                        "b", Map.of("examples", List.of(1, "1", true), "type", "number")),
                "type", "object");
        Map<String, Object> arrayReordered = Map.of(
                "properties", Map.of(
                        "a", Map.of("type", "string"),
                        "b", Map.of("examples", List.of("1", 1, true), "type", "number")),
                "type", "object");

        String digest = MCPExternalSchemaDigest.sha256(first, objectMapper);

        assertThat(MCPExternalSchemaDigest.sha256(reordered, objectMapper)).isEqualTo(digest);
        assertThat(MCPExternalSchemaDigest.sha256(arrayReordered, objectMapper)).isNotEqualTo(digest);
        assertThat(digest).matches("[0-9a-f]{64}");
    }

    @Test
    void endpointProducesOnlyExactConfiguredOriginAndPaths() {
        MCPExternalEndpoint endpoint = MCPExternalEndpoint.of(
                "restaurant-bi", "https://mcp.example.com:8443", "/api/mcp");

        assertThat(endpoint.toolsListUri())
                .isEqualTo(URI.create("https://mcp.example.com:8443/api/mcp/tools/list"));
        assertThat(endpoint.toolsCallUri())
                .isEqualTo(URI.create("https://mcp.example.com:8443/api/mcp/tools/call"));
    }

    @Test
    void endpointRejectsUnexpectedSchemeAndOriginOrPathAmbiguity() {
        assertThatThrownBy(() -> MCPExternalEndpoint.of(
                "server", "http://mcp.example.com", "/api/mcp"))
                .hasMessageContaining("https");
        assertThatThrownBy(() -> MCPExternalEndpoint.of(
                "server", "https://user@mcp.example.com", "/api/mcp"))
                .hasMessageContaining("userinfo");
        assertThatThrownBy(() -> MCPExternalEndpoint.of(
                "server", "https://mcp.example.com/api/mcp", "/api/mcp"))
                .hasMessageContaining("origin cannot contain a path");
        assertThatThrownBy(() -> MCPExternalEndpoint.of(
                "server", "https://mcp.example.com/api/..", "/api/mcp"))
                .hasMessageContaining("origin cannot contain a path");
        assertThatThrownBy(() -> MCPExternalEndpoint.of(
                "server", "https://mcp.example.com?target=evil", "/api/mcp"))
                .hasMessageContaining("query");
        assertThatThrownBy(() -> MCPExternalEndpoint.of(
                "server", "https://mcp.example.com", "/api/../mcp"))
                .hasMessageContaining("dot segments");
        assertThatThrownBy(() -> MCPExternalEndpoint.of(
                "server", "https://mcp.example.com", "/api/%2e%2e/mcp"))
                .hasMessageContaining("unsafe");
    }

    @Test
    void isolatedRuntimeRegistryExposesNoExecutorOrExecutionResolver() {
        assertThat(Arrays.stream(MCPExternalRuntimeRegistry.class.getDeclaredMethods())
                .map(Method::getReturnType))
                .noneMatch(com.cretas.aims.ai.tool.ToolExecutor.class::isAssignableFrom);
        assertThat(Arrays.stream(MCPExternalRuntimeRegistry.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("execute", "invoke", "getExecutor", "resolveExecutor");
    }
}
