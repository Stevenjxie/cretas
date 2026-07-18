package com.cretas.aims.ai.tool.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolDescriptorPolicyResolverTest {

    private final ToolDescriptorPolicyResolver resolver = ToolDescriptorPolicyResolver.loadDefault();

    @Test
    void resolvesOnlyExactVersionSourceAndPermissionMatches() {
        ToolExecutionCommand allowed = command(
                "user_disable", "2.0.0", ToolExecutionSource.AI_CHAT, Set.of("hr:read_write"));

        assertThat(resolver.resolve(allowed, allowed.principal()))
                .get()
                .extracting(ToolDescriptor::toolName)
                .isEqualTo("user_disable");
        assertDenied(command("user_disable", "2.0.1", ToolExecutionSource.AI_CHAT,
                Set.of("hr:read_write")));
        assertDenied(command("user_disable", "2.0.0", ToolExecutionSource.WORKFLOW,
                Set.of("hr:read_write")));
        assertDenied(command("user_disable", "2.0.0", ToolExecutionSource.AI_CHAT,
                Set.of("hr:read")));
    }

    @Test
    void canvasPreviewRequiresTrustedCurrentAllowedRoleAndBusinessType() {
        ToolExecutionCommand factoryCommand = command(
                "canvas_work_process_catalog",
                "1.0.0",
                ToolExecutionSource.HTTP_CONTROLLER,
                "FACTORY",
                "permission_admin",
                Set.of());
        assertThat(resolver.resolve(factoryCommand, factoryCommand.principal())).isPresent();

        ExecutionPrincipal operator = principal("FACTORY", "operator", Set.of());
        assertThat(resolver.resolve(factoryCommand, operator)).isEmpty();

        ToolExecutionCommand restaurantCommand = command(
                "canvas_work_process_catalog",
                "1.0.0",
                ToolExecutionSource.HTTP_CONTROLLER,
                "RESTAURANT",
                "permission_admin",
                Set.of());
        assertThat(resolver.resolve(restaurantCommand, restaurantCommand.principal())).isEmpty();
    }

    @Test
    void neverFallsBackForLegacyReviewBlockedUnknownOrNullCommands() {
        assertDenied(command("restaurant_sales_overview", "1.0.0",
                ToolExecutionSource.AI_CHAT, Set.of("restaurant:read_write")));
        assertDenied(command("canvas_set_user_permission", "1.0.0",
                ToolExecutionSource.AI_CHAT, Set.of("permission:any")));
        assertDenied(command("user_disable_typo", "2.0.0",
                ToolExecutionSource.AI_CHAT, Set.of("hr:read_write")));
        assertThat(resolver.resolve(null)).isEmpty();
    }

    private ToolExecutionCommand command(
            String toolName,
            String version,
            ToolExecutionSource source,
            Set<String> permissions) {
        return command(toolName, version, source, "RESTAURANT", "factory_admin", permissions);
    }

    private ToolExecutionCommand command(
            String toolName,
            String version,
            ToolExecutionSource source,
            String businessType,
            String role,
            Set<String> permissions) {
        ExecutionPrincipal principal = principal(businessType, role, permissions);
        return new ToolExecutionCommand(
                "request-1",
                "correlation-1",
                "trace-1",
                toolName,
                version,
                new ObjectMapper().createObjectNode(),
                principal,
                source,
                ToolExecutionMode.EXECUTE,
                Optional.of("idempotency-1"),
                Optional.empty(),
                Optional.empty(),
                Instant.now().plusSeconds(60));
    }

    private ExecutionPrincipal principal(
            String businessType,
            String role,
            Set<String> permissions) {
        return new ExecutionPrincipal(
                "F001", businessType, "1001", PrincipalType.USER,
                Set.of(role), permissions, Set.of());
    }

    private void assertDenied(ToolExecutionCommand command) {
        assertThat(resolver.resolve(command, command.principal())).isEmpty();
    }
}
