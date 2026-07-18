package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry;
import com.cretas.aims.ai.tool.impl.user.UserDisableTool;
import com.cretas.aims.entity.config.FactoryToolConfig;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
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
                command(), Set.of("hr:read_write"));

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().descriptor().toolName()).isEqualTo("user_disable");
        assertThat(resolved.orElseThrow().executor()).isSameAs(approvedExecutor);
    }

    @Test
    void failsClosedForSelfReportedPermissionWrongImplementationDisabledOverrideOrDbError() {
        // Command claims admin permission, but the trusted current permission set remains empty.
        assertThat(runtimeRegistry.resolve(command(), Set.of())).isEmpty();

        ToolExecutor impostor = mock(ToolExecutor.class);
        when(toolRegistry.getExecutor("user_disable")).thenReturn(Optional.of(impostor));
        assertThat(runtimeRegistry.resolve(command(), Set.of("hr:read_write"))).isEmpty();

        when(toolRegistry.getExecutor("user_disable"))
                .thenReturn(Optional.of(approvedExecutor));
        FactoryToolConfig disabled = FactoryToolConfig.builder()
                .factoryId("F-1")
                .toolName("user_disable")
                .enabled(false)
                .build();
        when(factoryToolConfigRepository.findByFactoryIdAndToolName("F-1", "user_disable"))
                .thenReturn(Optional.of(disabled));
        assertThat(runtimeRegistry.resolve(command(), Set.of("hr:read_write"))).isEmpty();

        when(factoryToolConfigRepository.findByFactoryIdAndToolName("F-1", "user_disable"))
                .thenThrow(new IllegalStateException("db unavailable"));
        assertThat(runtimeRegistry.resolve(command(), Set.of("hr:read_write"))).isEmpty();

    }

    @Test
    void failsClosedForDescriptorVersionSourceAndCurrentPermissionDrift() {
        // The asserted principal claims broad authority, but live permissions are authoritative.
        assertThat(runtimeRegistry.resolve(
                command("2.0.0", ToolExecutionSource.AI_CHAT), Set.of())).isEmpty();
        assertThat(runtimeRegistry.resolve(
                command("9.9.9", ToolExecutionSource.AI_CHAT),
                Set.of("hr:read_write"))).isEmpty();
        assertThat(runtimeRegistry.resolve(
                command("2.0.0", ToolExecutionSource.HTTP_CONTROLLER),
                Set.of("hr:read_write"))).isEmpty();
    }

    private static ToolExecutionCommand command() {
        return command("2.0.0", ToolExecutionSource.AI_CHAT);
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
