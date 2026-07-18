package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.enums.FactoryType;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolExecutionContractsTest {

    @Test
    void commandKeepsParametersAndSecurityEvidenceSeparateAndImmutable() {
        ObjectNode parameters = JsonNodeFactory.instance.objectNode()
                .put("tenantId", "attacker-tenant")
                .put("userId", "target-user")
                .put("role", "ADMIN")
                .put("confirmationToken", "forged-token")
                .put("quantity", 12);
        ExecutionPrincipal principal = userPrincipal();

        ToolExecutionCommand command = command(parameters, principal);
        parameters.put("quantity", 999);
        ((ObjectNode) command.parameters()).put("quantity", 888);

        assertThat(command.parameters().get("quantity").asInt()).isEqualTo(12);
        assertThat(command.parameters().get("tenantId").asText()).isEqualTo("attacker-tenant");
        assertThat(command.principal().tenantId()).isEqualTo("tenant-F006");
        assertThat(command.principal().principalId()).isEqualTo("user-42");
        assertThat(command.principal().roles()).containsExactly("ANALYST");
        assertThat(command.confirmationProof()).isEmpty();
        assertThat(command.approvalProof()).isEmpty();
        assertThat(command.source()).isEqualTo(ToolExecutionSource.AI_CHAT);
    }

    @Test
    void userUsesRolesWhileServiceAndSystemUseScopes() {
        Set<String> roles = new HashSet<>(Set.of("ANALYST"));
        ExecutionPrincipal user = new ExecutionPrincipal(
                "tenant-F006", "RESTAURANT", "user-42", PrincipalType.USER,
                roles, Set.of("inventory:read"), Set.of());
        roles.add("ADMIN");

        ExecutionPrincipal service = new ExecutionPrincipal(
                "tenant-F006", "RESTAURANT", "restaurant-agent", PrincipalType.SERVICE,
                Set.of(), Set.of("inventory:read"), Set.of("restaurant.read"));
        ExecutionPrincipal system = new ExecutionPrincipal(
                "tenant-F006", "FACTORY", "nightly-scheduler", PrincipalType.SYSTEM,
                Set.of(), Set.of(), Set.of("scheduled.read"));

        assertThat(user.roles()).containsExactly("ANALYST");
        assertThat(service.roles()).isEmpty();
        assertThat(service.scopes()).containsExactly("restaurant.read");
        assertThat(system.scopes()).containsExactly("scheduled.read");
        assertThatThrownBy(() -> new ExecutionPrincipal(
                "tenant-F006", "RESTAURANT", "user-42", PrincipalType.USER,
                Set.of(), Set.of(), Set.of("restaurant.read")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USER principals");
        assertThatThrownBy(() -> new ExecutionPrincipal(
                "tenant-F006", "RESTAURANT", "restaurant-agent", PrincipalType.SERVICE,
                Set.of(), Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SERVICE principals");
    }

    @Test
    void descriptorCollectionsAndPoliciesAreImmutableAndExplicit() {
        Set<String> permissions = new HashSet<>(Set.of("inventory:read"));
        Set<String> domains = new HashSet<>(Set.of("restaurant.inventory"));
        Set<ToolExecutionSource> sources = new HashSet<>(Set.of(ToolExecutionSource.AI_CHAT));
        ToolDescriptor descriptor = descriptor(
                DescriptorProvenance.EXPLICIT,
                permissions,
                domains,
                sources,
                ToolEgressPolicy.denyAll());
        permissions.add("inventory:write");
        domains.add("factory.inventory");
        sources.add(ToolExecutionSource.MCP);

        assertThat(descriptor.requiredPermissions()).containsExactly("inventory:read");
        assertThat(descriptor.domainTags()).containsExactly("restaurant.inventory");
        assertThat(descriptor.allowedSources()).containsExactly(ToolExecutionSource.AI_CHAT);
        assertThat(descriptor.supportsPreview()).isTrue();
        assertThat(descriptor.confirmationPolicy()).isEqualTo(ConfirmationPolicy.NOT_REQUIRED);
        assertThat(descriptor.idempotencyPolicy()).isEqualTo(IdempotencyPolicy.OPTIONAL);
        assertThat(descriptor.dataClassification()).isEqualTo(DataClassification.CONFIDENTIAL);
        assertThatThrownBy(() -> descriptor.requiredPermissions().add("admin:*"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void commandCarriesIndependentParameterBoundProofsAndVersionLock() {
        Instant now = Instant.parse("2026-07-18T12:00:00Z");
        ConfirmationProof confirmation = new ConfirmationProof(
                "opaque-confirmation", "sha256:command", now.plusSeconds(300));
        ApprovalProof approval = new ApprovalProof(
                "approval-7", "sha256:command", "manager-9", now, now.plusSeconds(600));

        ToolExecutionCommand command = new ToolExecutionCommand(
                "request-1",
                "correlation-1",
                "trace-1",
                "inventory_adjust",
                "2.0.0",
                JsonNodeFactory.instance.objectNode().put("delta", 4),
                userPrincipal(),
                ToolExecutionSource.AI_CHAT,
                ToolExecutionMode.EXECUTE,
                Optional.of("idempotency-1"),
                Optional.of(confirmation),
                Optional.of(approval),
                now.plusSeconds(30));

        assertThat(command.expectedDescriptorVersion()).isEqualTo("2.0.0");
        assertThat(command.idempotencyKey()).contains("idempotency-1");
        assertThat(command.confirmationProof()).contains(confirmation);
        assertThat(command.approvalProof()).contains(approval);
        assertThat(command.deadline()).isEqualTo(now.plusSeconds(30));
        assertThat(command.toString())
                .contains("proofToken=<redacted>")
                .doesNotContain("opaque-confirmation");
    }

    @Test
    void resultPayloadIsImmutableAndReplayStatusIsUnambiguous() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("rows", 7);
        ToolExecutionResult result = result(ToolExecutionStatus.SUCCEEDED, payload, false);
        payload.put("rows", 999);
        ((ObjectNode) result.payload()).put("rows", 888);

        ToolExecutionResult replay = result(
                ToolExecutionStatus.IDEMPOTENT_REPLAY,
                JsonNodeFactory.instance.objectNode().put("rows", 7),
                true);

        assertThat(result.payload().get("rows").asInt()).isEqualTo(7);
        assertThat(result.descriptorVersion()).isEqualTo("2.0.0");
        assertThat(result.auditEventId()).isEqualTo("audit-1");
        assertThat(result.traceId()).isEqualTo("trace-1");
        assertThat(replay.replayed()).isTrue();
        assertThatThrownBy(() -> result(ToolExecutionStatus.SUCCEEDED,
                JsonNodeFactory.instance.objectNode(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IDEMPOTENT_REPLAY");
    }

    @Test
    void externalDescriptorFailsClosedWhenGovernanceMetadataIsMissing() {
        assertThatThrownBy(() -> externalDescriptor(null, ToolExecutor.RiskLevel.HIGH,
                Set.of("inventory:write"), Set.of("restaurant.inventory"), "1.0.0",
                Set.of(ToolExecutionSource.MCP), ToolEgressPolicy.denyAll()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actionType");
        assertThatThrownBy(() -> externalDescriptor(ToolExecutor.ActionType.WRITE, null,
                Set.of("inventory:write"), Set.of("restaurant.inventory"), "1.0.0",
                Set.of(ToolExecutionSource.MCP), ToolEgressPolicy.denyAll()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("riskLevel");
        assertThatThrownBy(() -> externalDescriptor(ToolExecutor.ActionType.WRITE,
                ToolExecutor.RiskLevel.HIGH, Set.of(), Set.of("restaurant.inventory"), "1.0.0",
                Set.of(ToolExecutionSource.MCP), ToolEgressPolicy.denyAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permissions");
        assertThatThrownBy(() -> externalDescriptor(ToolExecutor.ActionType.WRITE,
                ToolExecutor.RiskLevel.HIGH, Set.of("inventory:write"), Set.of(), "1.0.0",
                Set.of(ToolExecutionSource.MCP), ToolEgressPolicy.denyAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domain");
        assertThatThrownBy(() -> externalDescriptor(ToolExecutor.ActionType.WRITE,
                ToolExecutor.RiskLevel.HIGH, Set.of("inventory:write"),
                Set.of("restaurant.inventory"), " ", Set.of(ToolExecutionSource.MCP),
                ToolEgressPolicy.denyAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> externalDescriptor(ToolExecutor.ActionType.WRITE,
                ToolExecutor.RiskLevel.HIGH, Set.of("inventory:write"),
                Set.of("restaurant.inventory"), "1.0.0", Set.of(),
                ToolEgressPolicy.denyAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedSources");
        assertThatThrownBy(() -> externalDescriptor(ToolExecutor.ActionType.WRITE,
                ToolExecutor.RiskLevel.HIGH, Set.of("inventory:write"),
                Set.of("restaurant.inventory"), "1.0.0", Set.of(ToolExecutionSource.MCP), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("egressPolicy");
        assertThatThrownBy(() -> externalDescriptor(ToolExecutor.ActionType.WRITE,
                ToolExecutor.RiskLevel.HIGH, Set.of("inventory:write"),
                Set.of("restaurant.inventory"), "1.0.0", Set.of(ToolExecutionSource.MCP),
                ToolEgressPolicy.legacyUnspecified()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit egress");
    }

    @Test
    void validatedExternalDescriptorRemainsUntrustedAndLegacyRemainsInferred() {
        ToolDescriptor external = externalDescriptor(
                ToolExecutor.ActionType.READ,
                ToolExecutor.RiskLevel.MEDIUM,
                Set.of("inventory:read"),
                Set.of("restaurant.inventory"),
                "1.2.3",
                Set.of(ToolExecutionSource.MCP),
                ToolEgressPolicy.allowlistOnly(Set.of("inventory.internal.example")));
        ToolDescriptor legacy = new ToolDescriptor(
                "legacy_inventory_query",
                ToolExecutor.ActionType.READ,
                ToolExecutor.RiskLevel.LOW,
                Set.of(),
                Set.of(),
                Set.of(FactoryType.FACTORY),
                Set.of(),
                "1.0.0",
                false,
                ConfirmationPolicy.NOT_REQUIRED,
                ApprovalPolicy.NOT_REQUIRED,
                IdempotencyPolicy.NOT_REQUIRED,
                DataClassification.INTERNAL,
                Set.of(ToolExecutionSource.INTERNAL_SERVICE),
                ToolEgressPolicy.legacyUnspecified(),
                DescriptorProvenance.LEGACY_INFERRED);

        assertThat(external.provenance()).isEqualTo(DescriptorProvenance.EXTERNAL_UNTRUSTED);
        assertThat(legacy.provenance()).isEqualTo(DescriptorProvenance.LEGACY_INFERRED);
        assertThat(legacy.egressPolicy().mode()).isEqualTo(EgressMode.LEGACY_UNSPECIFIED);
    }

    @Test
    void constructorsRejectBlankOrStructurallyInvalidValues() {
        assertThatThrownBy(() -> new ToolExecutionCommand(
                " ", "correlation-1", "trace-1", "inventory_query", "2.0.0",
                JsonNodeFactory.instance.objectNode(), userPrincipal(),
                ToolExecutionSource.AI_CHAT, ToolExecutionMode.EXECUTE,
                Optional.empty(), Optional.empty(), Optional.empty(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestId");
        assertThatThrownBy(() -> new ToolExecutionCommand(
                "request-1", "correlation-1", "trace-1", "inventory_query", "2.0.0",
                JsonNodeFactory.instance.arrayNode(), userPrincipal(),
                ToolExecutionSource.AI_CHAT, ToolExecutionMode.EXECUTE,
                Optional.empty(), Optional.empty(), Optional.empty(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
        assertThatThrownBy(() -> new ToolExecutionCommand(
                "request-1", "correlation-1", "trace-1", "inventory_query", "2.0.0",
                JsonNodeFactory.instance.objectNode(), userPrincipal(),
                ToolExecutionSource.AI_CHAT, ToolExecutionMode.EXECUTE,
                Optional.of(" "), Optional.empty(), Optional.empty(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
        assertThatThrownBy(() -> ToolEgressPolicy.allowlistOnly(Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        assertThatThrownBy(() -> new ApprovalProof(
                "approval-1", "sha256:command", "manager-9",
                Instant.parse("2026-07-18T12:10:00Z"),
                Instant.parse("2026-07-18T12:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approvedAt");
    }

    @Test
    void egressPermitIsPackageConstructedCommandBoundAndExact() {
        Instant deadline = Instant.now().plusSeconds(60);
        ToolEgressPermit permit = new ToolEgressPermit(
                "restaurant_margin_analysis",
                "1.0.0",
                "request-egress-1",
                deadline,
                Set.of("smartbi.internal"));

        assertThat(Modifier.isFinal(ToolEgressPermit.class.getModifiers())).isTrue();
        assertThat(ToolEgressPermit.class.getDeclaredConstructors())
                .allMatch(constructor -> !Modifier.isPublic(constructor.getModifiers()))
                .allMatch(constructor -> !Modifier.isProtected(constructor.getModifiers()));
        assertThat(Arrays.stream(ToolEgressPermit.class.getDeclaredMethods())
                .filter(method -> "requireExact".equals(method.getName()))
                .toList())
                .singleElement()
                .satisfies(method -> {
                    assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
                    assertThat(method.getParameterTypes()).containsExactly(
                            String.class, String.class, String.class, String.class);
                });
        assertThat(permit.toolName()).isEqualTo("restaurant_margin_analysis");
        assertThat(permit.toolVersion()).isEqualTo("1.0.0");
        assertThat(permit.requestId()).isEqualTo("request-egress-1");
        assertThat(permit.deadline()).isEqualTo(deadline);
        assertThat(permit.allowedDestinationIds()).containsExactly("smartbi.internal");
        assertThatThrownBy(() -> permit.allowedDestinationIds().add("attacker.example"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatCode(() -> permit.requireExact(
                "restaurant_margin_analysis", "1.0.0", "request-egress-1",
                "smartbi.internal"))
                .doesNotThrowAnyException();
    }

    @Test
    void egressPermitFailsClosedForEveryNullBlankOrWrongBinding() {
        ToolEgressPermit live = new ToolEgressPermit(
                "restaurant_margin_analysis", "1.0.0", "request-live",
                Instant.now().plusSeconds(60), Set.of("smartbi.internal"));

        assertThatThrownBy(() -> live.requireExact(
                null, "1.0.0", "request-live", "smartbi.internal"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> live.requireExact(
                " ", "1.0.0", "request-live", "smartbi.internal"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> live.requireExact(
                "restaurant_margin_analysis", null, "request-live", "smartbi.internal"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> live.requireExact(
                "restaurant_margin_analysis", " ", "request-live", "smartbi.internal"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> live.requireExact(
                "restaurant_margin_analysis", "1.0.0", null, "smartbi.internal"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> live.requireExact(
                "restaurant_margin_analysis", "1.0.0", " ", "smartbi.internal"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> live.requireExact(
                "restaurant_margin_analysis", "1.0.0", "request-live", null))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> live.requireExact(
                "restaurant_margin_analysis", "1.0.0", "request-live", " "))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> live.requireExact(
                "RESTAURANT_MARGIN_ANALYSIS", "1.0.0", "request-live",
                "smartbi.internal"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> live.requireExact(
                "restaurant_margin_analysis", "1.0.1", "request-live",
                "smartbi.internal"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> live.requireExact(
                "restaurant_margin_analysis", "1.0.0", "request-other",
                "smartbi.internal"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> live.requireExact(
                "restaurant_margin_analysis", "1.0.0", "request-live",
                "SMARTBI.INTERNAL"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> live.requireExact(
                "restaurant_margin_analysis", "1.0.0", "request-live",
                "smartbi.internal.example"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void egressPermitCannotCrossRequestBoundaryAndExpiresFailClosed() {
        ToolEgressPermit requestOne = new ToolEgressPermit(
                "restaurant_margin_analysis", "1.0.0", "request-one",
                Instant.now().plusSeconds(60), Set.of("smartbi.internal"));
        Map<String, Object> requestOneContext = ToolEgressPermit.trustedExecutionContext(
                Map.of("factoryId", "F-1"), Optional.of(requestOne));
        ToolEgressPermit reused = ToolEgressPermit.fromContext(requestOneContext).orElseThrow();
        ToolEgressPermit expired = new ToolEgressPermit(
                "restaurant_margin_analysis", "1.0.0", "request-expired",
                Instant.now().minusSeconds(1), Set.of("smartbi.internal"));

        assertThatThrownBy(() -> reused.requireExact(
                "restaurant_margin_analysis", "1.0.0", "request-two",
                "smartbi.internal"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> expired.requireExact(
                "restaurant_margin_analysis", "1.0.0", "request-expired",
                "smartbi.internal"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void trustedEgressContextCannotBeOverwrittenAndIsImmutable() {
        ToolEgressPermit first = new ToolEgressPermit(
                "restaurant_margin_analysis", "1.0.0", "request-first",
                Instant.now().plusSeconds(60), Set.of("smartbi.internal"));
        ToolEgressPermit replacement = new ToolEgressPermit(
                "restaurant_margin_analysis", "1.0.0", "request-replacement",
                Instant.now().plusSeconds(60), Set.of("smartbi.internal"));

        Map<String, Object> firstContext = ToolEgressPermit.trustedExecutionContext(
                Map.of("factoryId", "F-1"), Optional.of(first));
        Map<String, Object> replacedContext = ToolEgressPermit.trustedExecutionContext(
                firstContext, Optional.of(replacement));
        Map<String, Object> denyAllContext = ToolEgressPermit.trustedExecutionContext(
                firstContext, Optional.empty());

        assertThat(ToolEgressPermit.fromContext(null)).isEmpty();
        assertThat(ToolEgressPermit.fromContext(Map.of())).isEmpty();
        assertThat(ToolEgressPermit.fromContext(firstContext)).contains(first);
        assertThat(ToolEgressPermit.fromContext(replacedContext)).contains(replacement);
        assertThat(ToolEgressPermit.fromContext(denyAllContext)).isEmpty();
        assertThat(firstContext).containsEntry("factoryId", "F-1");
        assertThatThrownBy(() -> replacedContext.put("factoryId", "attacker"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ToolExecutionCommand command(ObjectNode parameters, ExecutionPrincipal principal) {
        return new ToolExecutionCommand(
                "request-1",
                "correlation-1",
                "trace-1",
                "inventory_query",
                "2.0.0",
                parameters,
                principal,
                ToolExecutionSource.AI_CHAT,
                ToolExecutionMode.EXECUTE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Instant.parse("2026-07-18T13:00:00Z"));
    }

    private static ExecutionPrincipal userPrincipal() {
        return new ExecutionPrincipal(
                "tenant-F006",
                "RESTAURANT",
                "user-42",
                PrincipalType.USER,
                Set.of("ANALYST"),
                Set.of("inventory:read"),
                Set.of());
    }

    private static ToolDescriptor descriptor(
            DescriptorProvenance provenance,
            Set<String> permissions,
            Set<String> domains,
            Set<ToolExecutionSource> sources,
            ToolEgressPolicy egressPolicy) {
        return new ToolDescriptor(
                "inventory_query",
                ToolExecutor.ActionType.READ,
                ToolExecutor.RiskLevel.LOW,
                permissions,
                Set.of(),
                Set.of(FactoryType.FACTORY),
                domains,
                "2.0.0",
                true,
                ConfirmationPolicy.NOT_REQUIRED,
                ApprovalPolicy.NOT_REQUIRED,
                IdempotencyPolicy.OPTIONAL,
                DataClassification.CONFIDENTIAL,
                sources,
                egressPolicy,
                provenance);
    }

    private static ToolDescriptor externalDescriptor(
            ToolExecutor.ActionType actionType,
            ToolExecutor.RiskLevel riskLevel,
            Set<String> permissions,
            Set<String> domains,
            String version,
            Set<ToolExecutionSource> sources,
            ToolEgressPolicy egressPolicy) {
        return new ToolDescriptor(
                "external_inventory_query",
                actionType,
                riskLevel,
                permissions,
                Set.of(),
                Set.of(FactoryType.FACTORY),
                domains,
                version,
                false,
                ConfirmationPolicy.REQUIRED_FOR_EXECUTION,
                ApprovalPolicy.REQUIRED_FOR_EXECUTION,
                IdempotencyPolicy.REQUIRED_FOR_EXECUTION,
                DataClassification.RESTRICTED,
                sources,
                egressPolicy,
                DescriptorProvenance.EXTERNAL_UNTRUSTED);
    }

    private static ToolExecutionResult result(
            ToolExecutionStatus status,
            ObjectNode payload,
            boolean replayed) {
        return new ToolExecutionResult(
                "request-1",
                "inventory_query",
                "2.0.0",
                "audit-1",
                "trace-1",
                status,
                payload,
                "done",
                replayed);
    }
}
