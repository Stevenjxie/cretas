package com.cretas.aims.mcp;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.mcp.MCPProtocol.MCPRequest;
import com.cretas.aims.mcp.MCPProtocol.MCPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MCPServerAdapterTest {

    private static final String API_KEY = "test-mcp-key";
    private static final String FACTORY_ID = "F006";
    private static final Long USER_ID = 42L;
    private static final String USER_ROLE = "factory_super_admin";

    @Test
    void endpointIsDisabledUnlessExplicitlyEnabled() {
        ConditionalOnProperty condition = AnnotatedElementUtils.findMergedAnnotation(
                MCPServerAdapter.class, ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("cretas.mcp");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void enabledServerFailsClosedWhenApiKeyOrPrincipalIsMissing() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ObjectMapper objectMapper = new ObjectMapper();
        WriteGuardService writeGuard = new WriteGuardService();

        assertThatThrownBy(() -> new MCPServerAdapter(
                registry, objectMapper, writeGuard, "", FACTORY_ID, "42", USER_ROLE,
                "material_batch_query"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cretas.mcp.api-key");

        assertThatThrownBy(() -> new MCPServerAdapter(
                registry, objectMapper, writeGuard, API_KEY, "", "42", USER_ROLE,
                "material_batch_query"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cretas.mcp.principal.factory-id");

        assertThatThrownBy(() -> new MCPServerAdapter(
                registry, objectMapper, writeGuard, API_KEY, FACTORY_ID, "not-a-number", USER_ROLE,
                "material_batch_query"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cretas.mcp.principal.user-id");

        assertThatThrownBy(() -> new MCPServerAdapter(
                registry, objectMapper, writeGuard, API_KEY, FACTORY_ID, "42", USER_ROLE, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cretas.mcp.allowed-tools");
    }

    @Test
    void missingOrWrongApiKeyIsRejected() {
        MCPServerAdapter adapter = configuredAdapter(mock(ToolRegistry.class));
        MCPRequest request = MCPRequest.builder().id("list-1").build();

        assertThat(adapter.listTools(request, null).getStatusCode().value()).isEqualTo(401);
        assertThat(adapter.listTools(request, "wrong-key").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void callToolUsesOnlyServerConfiguredPrincipal() throws Exception {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getToolName()).thenReturn("material_batch_query");
        when(tool.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        when(tool.execute(any(ToolCall.class), anyMap())).thenReturn("{\"success\":true}");
        when(registry.getExecutor("material_batch_query")).thenReturn(Optional.of(tool));
        when(registry.isToolEnabledForFactory(FACTORY_ID, "material_batch_query")).thenReturn(true);

        MCPServerAdapter adapter = configuredAdapter(registry);
        MCPRequest request = MCPRequest.builder()
                .id("call-1")
                .params(Map.of(
                        "name", "material_batch_query",
                        "arguments", Map.of("batchNumber", "B001"),
                        "context", Map.of(
                                "factoryId", "ATTACKER_FACTORY",
                                "userId", 999L,
                                "userRole", "platform_super_admin")))
                .build();

        ResponseEntity<MCPResponse> response = adapter.callTool(request, API_KEY);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(tool).execute(any(ToolCall.class), contextCaptor.capture());
        assertThat(contextCaptor.getValue())
                .containsEntry("factoryId", FACTORY_ID)
                .containsEntry("userId", USER_ID)
                .containsEntry("userRole", USER_ROLE)
                .containsEntry("source", "mcp")
                .doesNotContainValue("ATTACKER_FACTORY")
                .doesNotContainValue(999L)
                .doesNotContainValue("platform_super_admin");
    }

    @Test
    void writeToolIsNeverExposedForExecution() throws Exception {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getToolName()).thenReturn("material_batch_delete");
        when(tool.getActionType()).thenReturn(ToolExecutor.ActionType.DELETE);
        when(registry.getExecutor("material_batch_delete")).thenReturn(Optional.of(tool));
        when(registry.isToolEnabledForFactory(FACTORY_ID, "material_batch_delete")).thenReturn(true);

        MCPRequest request = MCPRequest.builder()
                .id("call-write")
                .params(Map.of("name", "material_batch_delete", "arguments", Map.of("id", 1)))
                .build();

        ResponseEntity<MCPResponse> response = configuredAdapter(registry).callTool(request, API_KEY);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getMessage()).contains("READ and ANALYZE");
        verify(tool, never()).execute(any(ToolCall.class), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsListOmitsWriteTools() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolExecutor readTool = mock(ToolExecutor.class);
        ToolExecutor writeTool = mock(ToolExecutor.class);
        when(readTool.getToolName()).thenReturn("material_batch_query");
        when(readTool.getDescription()).thenReturn("query");
        when(readTool.getParametersSchema()).thenReturn(Map.of());
        when(readTool.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        when(writeTool.getToolName()).thenReturn("material_batch_delete");
        when(writeTool.getActionType()).thenReturn(ToolExecutor.ActionType.DELETE);
        when(registry.getAllToolNames()).thenReturn(List.of(
                "material_batch_query", "material_batch_delete"));
        when(registry.getExecutor("material_batch_query")).thenReturn(Optional.of(readTool));
        when(registry.getExecutor("material_batch_delete")).thenReturn(Optional.of(writeTool));
        when(registry.isToolEnabledForFactory(FACTORY_ID, "material_batch_query")).thenReturn(true);
        when(registry.isToolEnabledForFactory(FACTORY_ID, "material_batch_delete")).thenReturn(true);

        ResponseEntity<MCPResponse> response = configuredAdapter(registry).listTools(
                MCPRequest.builder().id("list-read-only").build(), API_KEY);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> result = (Map<String, Object>) response.getBody().getResult();
        List<MCPProtocol.MCPToolDefinition> tools =
                (List<MCPProtocol.MCPToolDefinition>) result.get("tools");
        assertThat(tools)
                .extracting(MCPProtocol.MCPToolDefinition::getName)
                .containsExactly("material_batch_query");
    }

    @Test
    void identityFieldsInsideArgumentsAreRejectedBeforeExecution() throws Exception {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getToolName()).thenReturn("material_batch_query");
        when(tool.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        when(registry.getExecutor("material_batch_query")).thenReturn(Optional.of(tool));
        when(registry.isToolEnabledForFactory(FACTORY_ID, "material_batch_query")).thenReturn(true);

        MCPRequest request = MCPRequest.builder()
                .id("call-identity-override")
                .params(Map.of(
                        "name", "material_batch_query",
                        "arguments", Map.of("factoryId", "ATTACKER_FACTORY", "batchNumber", "B001")))
                .build();

        ResponseEntity<MCPResponse> response = configuredAdapter(registry).callTool(request, API_KEY);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getMessage()).contains("identity field");
        verify(tool, never()).execute(any(ToolCall.class), anyMap());
    }

    private MCPServerAdapter configuredAdapter(ToolRegistry registry) {
        return new MCPServerAdapter(
                registry,
                new ObjectMapper(),
                new WriteGuardService(),
                API_KEY,
                FACTORY_ID,
                USER_ID.toString(),
                USER_ROLE,
                "material_batch_query,material_batch_delete");
    }
}
