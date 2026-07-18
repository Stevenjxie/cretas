package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry;

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
    public Optional<ToolDescriptor> resolve(ToolExecutionCommand command) {
        if (command == null) {
            return Optional.empty();
        }
        return registry.findApproved(command.toolName())
                .filter(descriptor -> descriptor.provenance() == DescriptorProvenance.EXPLICIT)
                .filter(descriptor -> descriptor.version().equals(
                        command.expectedDescriptorVersion()))
                .filter(descriptor -> descriptor.allowedSources().contains(command.source()))
                .filter(descriptor -> command.principal().permissions()
                        .containsAll(descriptor.requiredPermissions()));
    }
}
