package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
                : resolve(command, command.principal().permissions());
    }

    /**
     * Resolves against permissions reloaded from the current authorization database.
     * Gateway callers must use this overload rather than trusting permissions carried by the
     * command envelope.
     */
    public Optional<ToolDescriptor> resolve(
            ToolExecutionCommand command,
            Set<String> trustedCurrentPermissions) {
        if (command == null) {
            return Optional.empty();
        }
        Set<String> permissions = trustedCurrentPermissions == null
                ? Set.of()
                : Set.copyOf(trustedCurrentPermissions);
        return registry.findApproved(command.toolName())
                .filter(descriptor -> descriptor.provenance() == DescriptorProvenance.EXPLICIT)
                .filter(descriptor -> descriptor.version().equals(
                        command.expectedDescriptorVersion()))
                .filter(descriptor -> descriptor.allowedSources().contains(command.source()))
                .filter(descriptor -> permissions.containsAll(descriptor.requiredPermissions()));
    }
}
