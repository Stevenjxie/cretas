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

        assertThat(resolver.resolve(allowed))
                .get()
                .extracting(ToolDescriptor::toolName)
                .isEqualTo("user_disable");
        assertThat(resolver.resolve(command(
                "user_disable", "2.0.1", ToolExecutionSource.AI_CHAT,
                Set.of("hr:read_write")))).isEmpty();
        assertThat(resolver.resolve(command(
                "user_disable", "2.0.0", ToolExecutionSource.WORKFLOW,
                Set.of("hr:read_write")))).isEmpty();
        assertThat(resolver.resolve(command(
                "user_disable", "2.0.0", ToolExecutionSource.AI_CHAT,
                Set.of("hr:read")))).isEmpty();
    }

    @Test
    void neverFallsBackForLegacyReviewBlockedUnknownOrNullCommands() {
        assertThat(resolver.resolve(command(
                "restaurant_sales_overview", "1.0.0", ToolExecutionSource.AI_CHAT,
                Set.of("restaurant:read_write")))).isEmpty();
        assertThat(resolver.resolve(command(
                "canvas_set_user_permission", "1.0.0", ToolExecutionSource.AI_CHAT,
                Set.of("permission:any")))).isEmpty();
        assertThat(resolver.resolve(command(
                "user_disable_typo", "2.0.0", ToolExecutionSource.AI_CHAT,
                Set.of("hr:read_write")))).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
    }

    private ToolExecutionCommand command(
            String toolName,
            String version,
            ToolExecutionSource source,
            Set<String> permissions) {
        ExecutionPrincipal principal = new ExecutionPrincipal(
                "F001",
                "RESTAURANT",
                "1001",
                PrincipalType.USER,
                Set.of("factory_admin"),
                permissions,
                Set.of());
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
}
