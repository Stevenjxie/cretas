package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry;
import com.cretas.aims.entity.enums.FactoryType;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Resolves only explicit, audit-approved runtime descriptors for a trusted execution command. */
public final class ToolDescriptorPolicyResolver {

    private final RuntimeToolDescriptorRegistry registry;

    public static ToolDescriptorPolicyResolver loadDefault() {
        return new ToolDescriptorPolicyResolver(RuntimeToolDescriptorRegistry.loadDefault());
    }

    public ToolDescriptorPolicyResolver(RuntimeToolDescriptorRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Returns no policy for unknown, legacy, review-blocked, version-drifted, source-disallowed,
     * or under-permissioned commands. There is intentionally no naming or legacy fallback.
     */
    /** Audit/test compatibility only; production execution must supply reloaded permissions. */
    @Deprecated(forRemoval = false)
    Optional<ToolDescriptor> resolve(ToolExecutionCommand command) {
        return command == null
                ? Optional.empty()
                : resolve(command, command.principal());
    }

    /**
     * Resolves against identity, roles, permissions, and business type reloaded from the current
     * authorization database.
     * Gateway callers must use this overload rather than trusting permissions carried by the
     * command envelope.
     */
    public Optional<ToolDescriptor> resolve(
            ToolExecutionCommand command,
            ExecutionPrincipal trustedCurrentPrincipal) {
        if (command == null || trustedCurrentPrincipal == null
                || !sameAuthenticatedIdentity(command.principal(), trustedCurrentPrincipal)) {
            return Optional.empty();
        }
        FactoryType currentBusinessType;
        try {
            currentBusinessType = FactoryType.valueOf(
                    trustedCurrentPrincipal.businessType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidBusinessType) {
            return Optional.empty();
        }
        return registry.findApproved(command.toolName())
                .filter(descriptor -> descriptor.provenance() == DescriptorProvenance.EXPLICIT)
                .filter(descriptor -> descriptor.version().equals(
                        command.expectedDescriptorVersion()))
                .filter(descriptor -> descriptor.allowedSources().contains(command.source()))
                .filter(descriptor -> descriptor.allowedBusinessTypes().contains(currentBusinessType))
                .filter(descriptor -> trustedCurrentPrincipal.permissions()
                        .containsAll(descriptor.requiredPermissions()))
                .filter(descriptor -> descriptor.allowedRoles().isEmpty()
                        || descriptor.allowedRoles().stream()
                        .anyMatch(trustedCurrentPrincipal.roles()::contains));
    }

    private static boolean sameAuthenticatedIdentity(
            ExecutionPrincipal asserted,
            ExecutionPrincipal current) {
        return asserted != null
                && asserted.tenantId().equals(current.tenantId())
                && asserted.principalId().equals(current.principalId())
                && asserted.principalType() == current.principalType()
                && asserted.businessType().trim().equalsIgnoreCase(current.businessType().trim());
    }
}
