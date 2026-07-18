package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry;
import com.cretas.aims.ai.tool.impl.user.UserDisableTool;
import com.cretas.aims.ai.tool.impl.workprocess.WorkProcessCatalogTool;
import com.cretas.aims.entity.config.FactoryToolConfig;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.cretas.aims.service.WorkProcessService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolRuntimeRegistryTest {

    @Mock ToolRegistry toolRegistry;
    @Mock FactoryToolConfigRepository factoryToolConfigRepository;

    ToolRuntimeRegistry runtimeRegistry;
    UserDisableTool approvedExecutor;

    @BeforeEach
    void setUp() {
        runtimeRegistry = new ToolRuntimeRegistry(
                toolRegistry,
                factoryToolConfigRepository,
                RuntimeToolDescriptorRegistry.loadDefault());
        approvedExecutor = new UserDisableTool();
    }

    @Test
    void resolvesOnlyExactApprovedImplementationAndInheritsGlobalEnableWithoutOverride() {
        when(toolRegistry.getExecutor("user_disable"))
                .thenReturn(Optional.of(approvedExecutor));
        when(factoryToolConfigRepository.findByFactoryIdAndToolName("F-1", "user_disable"))
                .thenReturn(Optional.empty());

        Optional<ToolRuntimeRegistry.ResolvedTool> resolved = runtimeRegistry.resolve(
                command(), current(Set.of("hr:read_write")));

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().descriptor().toolName()).isEqualTo("user_disable");
        assertThat(resolved.orElseThrow().executor()).isSameAs(approvedExecutor);
    }

    @Test
    void failsClosedForSelfReportedPermissionWrongImplementationDisabledOverrideOrDbError() {
        // Command claims admin permission, but the trusted current permission set remains empty.
        assertThat(runtimeRegistry.resolve(command(), current(Set.of()))).isEmpty();

        ToolExecutor impostor = mock(ToolExecutor.class);
        when(toolRegistry.getExecutor("user_disable")).thenReturn(Optional.of(impostor));
        assertThat(runtimeRegistry.resolve(
                command(), current(Set.of("hr:read_write")))).isEmpty();

        when(toolRegistry.getExecutor("user_disable"))
                .thenReturn(Optional.of(approvedExecutor));
        FactoryToolConfig disabled = FactoryToolConfig.builder()
                .factoryId("F-1")
                .toolName("user_disable")
                .enabled(false)
                .build();
        when(factoryToolConfigRepository.findByFactoryIdAndToolName("F-1", "user_disable"))
                .thenReturn(Optional.of(disabled));
        assertThat(runtimeRegistry.resolve(
                command(), current(Set.of("hr:read_write")))).isEmpty();

        when(factoryToolConfigRepository.findByFactoryIdAndToolName("F-1", "user_disable"))
                .thenThrow(new IllegalStateException("db unavailable"));
        assertThat(runtimeRegistry.resolve(
                command(), current(Set.of("hr:read_write")))).isEmpty();

    }

    @Test
    void failsClosedForDescriptorVersionSourceAndCurrentPermissionDrift() {
        // The asserted principal claims broad authority, but live permissions are authoritative.
        assertThat(runtimeRegistry.resolve(
                command("2.0.0", ToolExecutionSource.AI_CHAT), current(Set.of()))).isEmpty();
        assertThat(runtimeRegistry.resolve(
                command("9.9.9", ToolExecutionSource.AI_CHAT),
                current(Set.of("hr:read_write")))).isEmpty();
        assertThat(runtimeRegistry.resolve(
                command("2.0.0", ToolExecutionSource.HTTP_CONTROLLER),
                current(Set.of("hr:read_write")))).isEmpty();
    }

    @Test
    void canvasPreviewResolvesForPermissionAdminAndRejectsRoleOrBusinessTypeDrift() {
        WorkProcessCatalogTool canvasExecutor =
                new WorkProcessCatalogTool(mock(WorkProcessService.class));
        ToolExecutionCommand factoryCommand = canvasCommand("FACTORY", "permission_admin");
        when(toolRegistry.getExecutor("canvas_work_process_catalog"))
                .thenReturn(Optional.of(canvasExecutor));
        when(factoryToolConfigRepository.findByFactoryIdAndToolName(
                "F-1", "canvas_work_process_catalog"))
                .thenReturn(Optional.empty());

        assertThat(runtimeRegistry.resolve(factoryCommand, factoryCommand.principal())).isPresent();
        assertThat(runtimeRegistry.resolve(
                factoryCommand, current("FACTORY", "operator", Set.of()))).isEmpty();

        ToolExecutionCommand restaurantCommand =
                canvasCommand("RESTAURANT", "permission_admin");
        assertThat(runtimeRegistry.resolve(
                restaurantCommand, restaurantCommand.principal())).isEmpty();
    }

    private static ToolExecutionCommand command() {
        return command("2.0.0", ToolExecutionSource.AI_CHAT);
    }

    private static ExecutionPrincipal current(Set<String> permissions) {
        return current("FACTORY", "ADMIN", permissions);
    }

    private static ExecutionPrincipal current(
            String businessType,
            String role,
            Set<String> permissions) {
        return new ExecutionPrincipal(
                "F-1", businessType, "42", PrincipalType.USER,
                Set.of(role), permissions, Set.of());
    }

    private static ToolExecutionCommand canvasCommand(String businessType, String role) {
        ExecutionPrincipal principal = current(businessType, role, Set.of());
        return new ToolExecutionCommand(
                "request-canvas", "correlation-canvas", "trace-canvas",
                "canvas_work_process_catalog", "1.0.0",
                JsonNodeFactory.instance.objectNode().put("action", "create"),
                principal, ToolExecutionSource.HTTP_CONTROLLER, ToolExecutionMode.PREVIEW,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Instant.now().plusSeconds(60));
    }

    private static ToolExecutionCommand command(
            String descriptorVersion,
            ToolExecutionSource source) {
        ExecutionPrincipal asserted = new ExecutionPrincipal(
                "F-1", "FACTORY", "42", PrincipalType.USER,
                Set.of("ADMIN"), Set.of("*:admin"), Set.of());
        return new ToolExecutionCommand(
                "request-1", "correlation-1", "trace-1", "user_disable", descriptorVersion,
                JsonNodeFactory.instance.objectNode().put("userId", 7), asserted,
                source, ToolExecutionMode.EXECUTE,
                Optional.of("idem-1"), Optional.empty(), Optional.empty(),
                Instant.now().plusSeconds(60));
    }
}
