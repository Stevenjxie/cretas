package com.cretas.aims.mcp;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.mcp.MCPExternalRuntimeRegistry;
import com.cretas.aims.ai.tool.gateway.mcp.MCPExternalSchemaDigest;
import com.cretas.aims.mcp.MCPExternalProperties.ExternalTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MCPClientAdapterTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("period", Map.of("type", "string")),
            "required", List.of("period"));

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void isolatesExactAllowlistedCapabilityFromOrdinaryToolRegistryAndSpoofableContext() {
        ToolRegistry registry = new ToolRegistry();
        MCPExternalRuntimeRegistry externalRegistry = new MCPExternalRuntimeRegistry();
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://mcp.example.com/api/mcp/tools/list"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(toolListJson(
                        toolJson("restaurant_summary", SCHEMA, "untrusted description"),
                        toolJson("remote_added_without_policy", SCHEMA, "must not register")),
                        MediaType.APPLICATION_JSON));

        MCPClientAdapter adapter = new MCPClientAdapter(
                registry, objectMapper, properties(policy("mcp_restaurant_summary", digest(SCHEMA))),
                externalRegistry, restTemplate);
        adapter.discoverAndRegister();

        assertThat(externalRegistry.capabilityNames()).containsExactly("mcp_restaurant_summary");
        assertThat(registry.hasExecutor("mcp_restaurant_summary")).isFalse();
        assertThat(registry.getExecutor("mcp_restaurant_summary")).isEmpty();
        // A forged toolExecutionSource Map has nowhere to go: ordinary callers cannot resolve a proxy.
        server.verify();
    }

    @Test
    void unallowlistedRemoteOrMissingAllowlistedRemoteRegistersNothing() {
        ToolRegistry registry = mock(ToolRegistry.class);
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://mcp.example.com/api/mcp/tools/list"))
                .andRespond(withSuccess(toolListJson(
                        toolJson("different_name", SCHEMA, "remote")), MediaType.APPLICATION_JSON));

        new MCPClientAdapter(
                registry, objectMapper, properties(policy("mcp_restaurant_summary", digest(SCHEMA))),
                new MCPExternalRuntimeRegistry(), restTemplate).discoverAndRegister();

        verify(registry, never()).registerExternal(anyString(), any());
        server.verify();
    }

    @Test
    void schemaDigestMismatchRegistersNothing() {
        ToolRegistry registry = mock(ToolRegistry.class);
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://mcp.example.com/api/mcp/tools/list"))
                .andRespond(withSuccess(toolListJson(
                        toolJson("restaurant_summary", SCHEMA, "remote")), MediaType.APPLICATION_JSON));

        new MCPClientAdapter(
                registry, objectMapper, properties(policy("mcp_restaurant_summary", "0".repeat(64))),
                new MCPExternalRuntimeRegistry(), restTemplate).discoverAndRegister();

        verify(registry, never()).registerExternal(anyString(), any());
        server.verify();
    }

    @Test
    void localRegistryCollisionIsRejected() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasExecutor("mcp_restaurant_summary")).thenReturn(true);
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://mcp.example.com/api/mcp/tools/list"))
                .andRespond(withSuccess(toolListJson(
                        toolJson("restaurant_summary", SCHEMA, "remote")), MediaType.APPLICATION_JSON));

        new MCPClientAdapter(
                registry, objectMapper, properties(policy("mcp_restaurant_summary", digest(SCHEMA))),
                new MCPExternalRuntimeRegistry(), restTemplate).discoverAndRegister();

        verify(registry, never()).registerExternal(anyString(), any());
        server.verify();
    }

    @Test
    void duplicateLocalPolicyNameFailsBeforeNetworkOrRegistration() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ExternalTool first = policy("mcp_collision", digest(SCHEMA));
        ExternalTool second = policy("mcp_collision", digest(SCHEMA));
        second.setRemoteToolName("other_remote");
        MCPExternalProperties properties = properties(first, second);

        assertThatThrownBy(() -> new MCPClientAdapter(
                registry, objectMapper, properties, new MCPExternalRuntimeRegistry(),
                new RestTemplate()).discoverAndRegister())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate outbound MCP localToolName");
        verify(registry, never()).registerExternal(anyString(), any());
    }

    @Test
    void legacyExternalServersWithoutPolicyRegistersZeroAndMakesNoRequest() {
        ToolRegistry registry = mock(ToolRegistry.class);
        MCPExternalProperties properties = new MCPExternalProperties();
        properties.setExternalServers("https://legacy.example.com/api/mcp");

        new MCPClientAdapter(
                registry, objectMapper, properties, new MCPExternalRuntimeRegistry(),
                new RestTemplate()).discoverAndRegister();

        verify(registry, never()).registerExternal(anyString(), any());
    }

    private MCPExternalProperties properties(ExternalTool... tools) {
        MCPExternalProperties properties = new MCPExternalProperties();
        properties.setExternalTools(List.of(tools));
        return properties;
    }

    private ExternalTool policy(String localToolName, String digest) {
        ExternalTool tool = new ExternalTool();
        tool.setServerId("restaurant-bi");
        tool.setOrigin("https://mcp.example.com");
        tool.setBasePath("/api/mcp");
        tool.setRemoteToolName("restaurant_summary");
        tool.setSchemaDigest(digest);
        tool.setLocalToolName(localToolName);
        tool.setDescription("Local governed restaurant summary");
        tool.setActionType("ANALYZE");
        tool.setRiskLevel("HIGH");
        tool.setRequiredPermissions(List.of("restaurant:read"));
        tool.setDomainTags(List.of("restaurant.analytics"));
        tool.setVersion("2.1.0");
        tool.setAllowedSources(List.of("AI_CHAT"));
        tool.setEgressContextFields(List.of("factoryId", "userId"));
        return tool;
    }

    private String digest(Map<String, Object> schema) {
        return MCPExternalSchemaDigest.sha256(schema, objectMapper);
    }

    private String toolListJson(String... tools) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"list\",\"result\":{\"tools\":["
                + String.join(",", tools) + "]}}";
    }

    private String toolJson(String name, Map<String, Object> schema, String description) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "name", name,
                    "description", description,
                    "inputSchema", schema));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
