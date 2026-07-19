package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolPolicyEntry;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolPolicyManifest;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventory;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventoryEntry;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorOverrideFlags;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolGovernanceStatus;
import com.cretas.aims.ai.tool.impl.user.UserDisableTool;
import com.cretas.aims.ai.tool.impl.workprocess.WorkProcessCatalogTool;
import com.cretas.aims.entity.config.FactoryToolConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.cretas.aims.service.WorkProcessService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    void springContextSelectsTheProductionConstructor() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(ToolRegistry.class, () -> toolRegistry);
            context.registerBean(
                    FactoryToolConfigRepository.class,
                    () -> factoryToolConfigRepository);
            context.register(ToolRuntimeRegistry.class);

            context.refresh();

            assertThat(context.getBean(ToolRuntimeRegistry.class)).isNotNull();
        }
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

    @Test
    void allowlistResolvesOnlyForMarkerWithExactNonEmptyDestinations() {
        TestEgressTool exact = new TestEgressTool(Set.of("smartbi.internal"));
        assertThat(resolveEgressTool(
                exact, ToolEgressPolicy.allowlistOnly(Set.of("smartbi.internal"))))
                .isPresent();

        TestTool missingMarker = new TestTool();
        assertThat(resolveEgressTool(
                missingMarker, ToolEgressPolicy.allowlistOnly(Set.of("smartbi.internal"))))
                .isEmpty();

        TestEgressTool destinationDrift = new TestEgressTool(Set.of("other.internal"));
        assertThat(resolveEgressTool(
                destinationDrift, ToolEgressPolicy.allowlistOnly(Set.of("smartbi.internal"))))
                .isEmpty();

        TestEgressTool emptyDestinations = new TestEgressTool(Set.of());
        assertThat(resolveEgressTool(
                emptyDestinations, ToolEgressPolicy.allowlistOnly(Set.of("smartbi.internal"))))
                .isEmpty();
    }

    @Test
    void denyAllRejectsEgressMarkerAndLegacyAlwaysFailsClosed() {
        assertThat(resolveEgressTool(
                new TestEgressTool(Set.of("smartbi.internal")), ToolEgressPolicy.denyAll()))
                .isEmpty();
        assertThat(ToolRuntimeRegistry.hasExactEgressBehavior(
                legacyDescriptor(), new TestTool()))
                .isFalse();
    }

    private Optional<ToolRuntimeRegistry.ResolvedTool> resolveEgressTool(
            ToolExecutor executor,
            ToolEgressPolicy egressPolicy) {
        RuntimeToolDescriptorRegistry descriptorRegistry = descriptorRegistry(executor, egressPolicy);
        runtimeRegistry = new ToolRuntimeRegistry(
                toolRegistry, factoryToolConfigRepository, descriptorRegistry);
        when(toolRegistry.getExecutor("egress_test_tool")).thenReturn(Optional.of(executor));
        return runtimeRegistry.resolve(egressCommand(), current(Set.of("hr:read_write")));
    }

    private static RuntimeToolDescriptorRegistry descriptorRegistry(
            ToolExecutor executor,
            ToolEgressPolicy egressPolicy) {
        String implementationClass = executor.getClass().getName();
        ToolDescriptorInventoryEntry inventoryEntry = new ToolDescriptorInventoryEntry(
                "egress_test_tool",
                implementationClass,
                DescriptorProvenance.EXPLICIT,
                ToolExecutor.ActionType.READ,
                ToolExecutor.RiskLevel.LOW,
                true,
                true,
                Set.of("hr:read_write"),
                Set.of(),
                "1.0.0",
                Set.of("restaurant", "analytics"),
                new ToolDescriptorOverrideFlags(
                        true, true, true, true, true, true, true, true),
                ToolGovernanceStatus.APPROVED);
        RuntimeToolPolicyEntry policyEntry = new RuntimeToolPolicyEntry(
                implementationClass,
                "egress_test_tool",
                ToolExecutor.ActionType.READ,
                ToolExecutor.RiskLevel.LOW,
                Set.of("hr:read_write"),
                Set.of(),
                Set.of(FactoryType.FACTORY),
                Set.of("restaurant", "analytics"),
                "1.0.0",
                true,
                ConfirmationPolicy.NOT_REQUIRED,
                ApprovalPolicy.NOT_REQUIRED,
                IdempotencyPolicy.NOT_REQUIRED,
                DataClassification.INTERNAL,
                Set.of(ToolExecutionSource.HTTP_CONTROLLER),
                egressPolicy,
                DescriptorProvenance.EXPLICIT);
        return new RuntimeToolDescriptorRegistry(
                new ToolDescriptorInventory(1, 1, 0, List.of(inventoryEntry)),
                new RuntimeToolPolicyManifest(1, 1, List.of(policyEntry)));
    }

    private static ToolDescriptor legacyDescriptor() {
        return new ToolDescriptor(
                "egress_test_tool",
                ToolExecutor.ActionType.READ,
                ToolExecutor.RiskLevel.LOW,
                Set.of("hr:read_write"),
                Set.of(),
                Set.of(FactoryType.FACTORY),
                Set.of("restaurant", "analytics"),
                "1.0.0",
                true,
                ConfirmationPolicy.NOT_REQUIRED,
                ApprovalPolicy.NOT_REQUIRED,
                IdempotencyPolicy.NOT_REQUIRED,
                DataClassification.INTERNAL,
                Set.of(ToolExecutionSource.HTTP_CONTROLLER),
                ToolEgressPolicy.legacyUnspecified(),
                DescriptorProvenance.LEGACY_INFERRED);
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

    private static ToolExecutionCommand egressCommand() {
        ExecutionPrincipal asserted = new ExecutionPrincipal(
                "F-1", "FACTORY", "42", PrincipalType.USER,
                Set.of("ADMIN"), Set.of("hr:read_write"), Set.of());
        return new ToolExecutionCommand(
                "request-egress", "correlation-egress", "trace-egress",
                "egress_test_tool", "1.0.0",
                JsonNodeFactory.instance.objectNode(), asserted,
                ToolExecutionSource.HTTP_CONTROLLER, ToolExecutionMode.PREVIEW,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Instant.now().plusSeconds(60));
    }

    private static class TestTool implements ToolExecutor {

        @Override
        public String getToolName() {
            return "egress_test_tool";
        }

        @Override
        public String getDescription() {
            return "test";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of();
        }

        @Override
        public String execute(ToolCall toolCall, Map<String, Object> context) {
            return "{\"success\":true}";
        }

        @Override
        public boolean requiresPermission() {
            return true;
        }

        @Override
        public boolean hasPermission(String userRole) {
            return false;
        }

        @Override
        public Set<String> getRequiredPermissions() {
            return Set.of("hr:read_write");
        }

        @Override
        public boolean supportsPreview() {
            return true;
        }

        @Override
        public Set<String> getDomainTags() {
            return Set.of("restaurant", "analytics");
        }
    }

    private static final class TestEgressTool extends TestTool
            implements EgressCapableTool {

        private final Set<String> destinationIds;

        private TestEgressTool(Set<String> destinationIds) {
            this.destinationIds = Set.copyOf(destinationIds);
        }

        @Override
        public Set<String> getEgressDestinationIds() {
            return destinationIds;
        }
    }
}
