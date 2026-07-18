package com.cretas.aims.mcp;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.mcp.MCPExternalEndpoint;
import com.cretas.aims.ai.tool.gateway.mcp.MCPExternalSchemaDigest;
import com.cretas.aims.ai.tool.gateway.mcp.MCPExternalToolPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MCPToolProxyTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object", "properties", Map.of("period", Map.of("type", "string")));

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsOnlyPolicyAllowlistedContextFieldsToExactCallPath() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://mcp.example.com/api/mcp/tools/call"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "jsonrpc":"2.0",
                          "id":"call-1",
                          "method":"tools/call",
                          "params":{
                            "name":"restaurant_summary",
                            "arguments":{"period":"week"},
                            "context":{"factoryId":"F006","userId":"user-1"}
                          }
                        }
                        """, true))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"call-1\",\"result\":{\"ok\":true}}",
                        MediaType.APPLICATION_JSON));
        MCPToolProxy proxy = proxy(restTemplate);
        Map<String, Object> context = Map.of(
                MCPExternalToolPolicy.EXECUTION_SOURCE_CONTEXT_KEY, ToolExecutionSource.AI_CHAT,
                "factoryId", "F006",
                "userId", "user-1",
                "jwt", "must-not-egress",
                "permissions", List.of("admin:*"));

        String result = proxy.execute(
                ToolCall.of("call-1", "mcp_restaurant_summary", "{\"period\":\"week\"}"),
                context);

        assertThat(result).isEqualTo("{\"ok\":true}");
        server.verify();
    }

    @Test
    void malformedOrNonObjectArgumentsFailClosedBeforeNetwork() {
        MCPToolProxy proxy = proxy(new RestTemplate());
        Map<String, Object> context = Map.of(
                MCPExternalToolPolicy.EXECUTION_SOURCE_CONTEXT_KEY, "AI_CHAT");

        assertThatThrownBy(() -> proxy.execute(
                ToolCall.of("call", "mcp_restaurant_summary", "{broken"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid JSON");
        assertThatThrownBy(() -> proxy.execute(
                ToolCall.of("call", "mcp_restaurant_summary", "[]"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    void missingOrDisallowedSourceFailsClosedBeforeNetwork() {
        MCPToolProxy proxy = proxy(new RestTemplate());
        ToolCall call = ToolCall.of("call", "mcp_restaurant_summary", "{}");

        assertThatThrownBy(() -> proxy.execute(call, Map.of("factoryId", "F006")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("source is required");
        assertThatThrownBy(() -> proxy.execute(call, Map.of(
                MCPExternalToolPolicy.EXECUTION_SOURCE_CONTEXT_KEY, ToolExecutionSource.SCHEDULER)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void redirectResponseIsRejectedInsteadOfAcceptedAsMcpResult() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://mcp.example.com/api/mcp/tools/call"))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .location(java.net.URI.create("https://evil.example.com/tools/call")));

        assertThatThrownBy(() -> proxy(restTemplate).execute(
                ToolCall.of("call", "mcp_restaurant_summary", "{}"),
                Map.of(MCPExternalToolPolicy.EXECUTION_SOURCE_CONTEXT_KEY, "AI_CHAT")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 302");
        server.verify();
    }

    @Test
    void metadataComesFromPolicyNotToolExecutorDefaults() {
        MCPToolProxy proxy = proxy(new RestTemplate());

        assertThat(proxy.getActionType()).isEqualTo(ToolExecutor.ActionType.ANALYZE);
        assertThat(proxy.getRiskLevel()).isEqualTo(ToolExecutor.RiskLevel.HIGH);
        assertThat(proxy.getRequiredPermissions()).containsExactly("restaurant:read");
        assertThat(proxy.getDomainTags()).containsExactly("restaurant.analytics");
        assertThat(proxy.getVersion()).isEqualTo("2.1.0");
        assertThat(proxy.requiresPermission()).isTrue();
        assertThat(proxy.hasPermission("factory_admin")).isFalse();
    }

    private MCPToolProxy proxy(RestTemplate restTemplate) {
        MCPExternalToolPolicy policy = new MCPExternalToolPolicy(
                MCPExternalEndpoint.of("restaurant-bi", "https://mcp.example.com", "/api/mcp"),
                "restaurant_summary",
                MCPExternalSchemaDigest.sha256(SCHEMA, objectMapper),
                "mcp_restaurant_summary",
                "Local governed restaurant summary",
                ToolExecutor.ActionType.ANALYZE,
                ToolExecutor.RiskLevel.HIGH,
                Set.of("restaurant:read"),
                Set.of("restaurant.analytics"),
                "2.1.0",
                Set.of(ToolExecutionSource.AI_CHAT),
                Set.of("factoryId", "userId"));
        return new MCPToolProxy(policy, SCHEMA, restTemplate, objectMapper);
    }
}
