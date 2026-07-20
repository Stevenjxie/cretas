package com.cretas.aims.ai.capability;

import com.cretas.aims.ai.capability.FactoryCapabilityPack.WorkflowReference;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.repository.FactoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Fail-closed routing boundary for the version-controlled factory capability packs.
 *
 * <p>The policy reads the server-side factory type exactly once at the top-level route. Match
 * terms only decide whether a request enters the selected pack's constrained domain; they never
 * select a tool. Tool and workflow decisions are made only after the existing deterministic
 * phrase/intent recognizer has selected an intent.
 */
@Component
public final class FactoryCapabilityPackRoutingPolicy {
    public static final String ENABLED_PROPERTY = "cretas.ai.factory-capability-routing.enabled";

    private final FactoryRepository factoryRepository;
    private final FactoryCapabilityPackSelector selector;
    private final boolean enabled;

    public FactoryCapabilityPackRoutingPolicy(
            FactoryRepository factoryRepository,
            FactoryCapabilityPackSelector selector,
            @Value("${" + ENABLED_PROPERTY + ":false}") boolean enabled) {
        this.factoryRepository = Objects.requireNonNull(factoryRepository, "factoryRepository");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.enabled = enabled;
    }

    /** Resolve the immutable policy context once for one top-level execute request. */
    public Route evaluate(String factoryId, String userRole, String query) {
        if (!enabled) {
            return Route.disabled();
        }

        Optional<FactoryUserRole> role = trustedRole(userRole);
        if (role.isEmpty()) {
            return Route.notApplicable("untrusted-role");
        }

        Optional<FactoryCapabilityPack> rolePack = selector.select(
                role.get(), FactoryType.FACTORY).or(() -> selector.select(
                        role.get(), FactoryType.CENTRAL_KITCHEN));
        if (rolePack.isEmpty()) {
            return Route.notApplicable("role-without-pack");
        }

        final Optional<Factory> factory;
        try {
            factory = factoryRepository.findById(factoryId == null ? "" : factoryId);
        } catch (RuntimeException unavailable) {
            return Route.blocked(rolePack.get(), null, role.get(), "factory-type-unavailable");
        }
        if (factory.isEmpty()
                || !Boolean.TRUE.equals(factory.get().getIsActive())
                || factory.get().getType() == null) {
            return Route.blocked(rolePack.get(), null, role.get(), "factory-type-unavailable");
        }

        FactoryType factoryType = factory.get().getType();
        if (factoryType == FactoryType.RESTAURANT) {
            return Route.notApplicable("restaurant-excluded");
        }
        if (factoryType != FactoryType.FACTORY
                && factoryType != FactoryType.CENTRAL_KITCHEN) {
            return Route.notApplicable("business-type-not-supported");
        }

        Optional<FactoryCapabilityPack> selected = selector.select(role.get(), factoryType);
        if (selected.isEmpty()) {
            return Route.notApplicable("role-without-pack");
        }

        try {
            if (selector.match(role.get(), factoryType, query).isPresent()) {
                return Route.constrained(selected.get(), factoryType, role.get());
            }
        } catch (IllegalArgumentException unsafeQuery) {
            return Route.blocked(selected.get(), factoryType, role.get(), "unsafe-query");
        }
        return Route.blocked(selected.get(), factoryType, role.get(), "outside-pack-domain");
    }

    /**
     * Authorize the recognizer's result. A pack match never chooses {@code toolName}; it can only
     * admit an already-selected READ tool, or turn a declared workflow reference into guidance.
     */
    public ExecutionDecision authorize(
            Route route, String intentCode, String toolName, boolean writeIntent) {
        if (route == null || !route.isConstrained()) {
            return ExecutionDecision.notApplicable();
        }

        Optional<WorkflowReference> workflow = workflowFor(route.pack(), intentCode);
        if (writeIntent) {
            return workflow.filter(WorkflowReference::mutation)
                    .map(ExecutionDecision::guidance)
                    .orElseGet(() -> ExecutionDecision.noMatch("mutation-outside-workflow"));
        }

        if (toolName != null && route.pack().readToolAllowlist().contains(toolName)) {
            return ExecutionDecision.allowRead();
        }
        if (workflow.isPresent()) {
            return ExecutionDecision.guidance(workflow.get());
        }
        return ExecutionDecision.noMatch(
                toolName == null || toolName.isBlank()
                        ? "intent-outside-workflow"
                        : "read-tool-outside-allowlist");
    }

    private static Optional<FactoryUserRole> trustedRole(String userRole) {
        if (userRole == null || userRole.isBlank()) {
            return Optional.empty();
        }
        try {
            FactoryUserRole role = FactoryUserRole.valueOf(userRole);
            return role.isActive() ? Optional.of(role) : Optional.empty();
        } catch (IllegalArgumentException invalidRole) {
            return Optional.empty();
        }
    }

    private static Optional<WorkflowReference> workflowFor(
            FactoryCapabilityPack pack, String intentCode) {
        if (intentCode == null || intentCode.isBlank()) {
            return Optional.empty();
        }
        return pack.workflowReferences().stream()
                .filter(reference -> {
                    int separator = reference.referenceId().indexOf(':');
                    return separator >= 0
                            && reference.referenceId().substring(separator + 1).equals(intentCode);
                })
                .findFirst();
    }

    public enum RouteStatus {
        DISABLED,
        NOT_APPLICABLE,
        CONSTRAINED,
        BLOCKED
    }

    public record Route(
            RouteStatus status,
            FactoryCapabilityPack pack,
            FactoryType factoryType,
            FactoryUserRole role,
            String reason) {

        public boolean isConstrained() {
            return status == RouteStatus.CONSTRAINED;
        }

        public boolean shouldBlock() {
            return status == RouteStatus.BLOCKED;
        }

        static Route disabled() {
            return new Route(RouteStatus.DISABLED, null, null, null, "feature-disabled");
        }

        static Route notApplicable(String reason) {
            return new Route(RouteStatus.NOT_APPLICABLE, null, null, null, reason);
        }

        static Route constrained(
                FactoryCapabilityPack pack, FactoryType factoryType, FactoryUserRole role) {
            return new Route(RouteStatus.CONSTRAINED, pack, factoryType, role, "matched-pack-domain");
        }

        static Route blocked(
                FactoryCapabilityPack pack,
                FactoryType factoryType,
                FactoryUserRole role,
                String reason) {
            return new Route(RouteStatus.BLOCKED, pack, factoryType, role, reason);
        }
    }

    public enum ExecutionStatus {
        NOT_APPLICABLE,
        ALLOW_READ,
        GUIDANCE,
        NO_MATCH
    }

    public record ExecutionDecision(
            ExecutionStatus status, WorkflowReference workflowReference, String reason) {

        public boolean allowsReadExecution() {
            return status == ExecutionStatus.ALLOW_READ;
        }

        static ExecutionDecision notApplicable() {
            return new ExecutionDecision(ExecutionStatus.NOT_APPLICABLE, null, "route-not-applicable");
        }

        static ExecutionDecision allowRead() {
            return new ExecutionDecision(ExecutionStatus.ALLOW_READ, null, "allowlisted-read-tool");
        }

        static ExecutionDecision guidance(WorkflowReference reference) {
            return new ExecutionDecision(ExecutionStatus.GUIDANCE, reference, "declared-workflow");
        }

        static ExecutionDecision noMatch(String reason) {
            return new ExecutionDecision(ExecutionStatus.NO_MATCH, null, reason);
        }
    }
}
