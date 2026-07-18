package com.cretas.aims.ai.tool.gateway;

import java.util.Set;

public record ToolEgressPolicy(EgressMode mode, Set<String> allowedDestinations) {

    public ToolEgressPolicy {
        mode = ContractValidation.requireNonNull(mode, "mode");
        allowedDestinations = ContractValidation.immutableNonBlankSet(
                allowedDestinations, "allowedDestinations");

        if (mode == EgressMode.ALLOWLIST_ONLY && allowedDestinations.isEmpty()) {
            throw new IllegalArgumentException(
                    "ALLOWLIST_ONLY egress requires at least one allowed destination");
        }
        if (mode != EgressMode.ALLOWLIST_ONLY && !allowedDestinations.isEmpty()) {
            throw new IllegalArgumentException(
                    mode + " egress cannot contain allowed destinations");
        }
    }

    public static ToolEgressPolicy denyAll() {
        return new ToolEgressPolicy(EgressMode.DENY_ALL, Set.of());
    }

    public static ToolEgressPolicy allowlistOnly(Set<String> allowedDestinations) {
        return new ToolEgressPolicy(EgressMode.ALLOWLIST_ONLY, allowedDestinations);
    }

    public static ToolEgressPolicy legacyUnspecified() {
        return new ToolEgressPolicy(EgressMode.LEGACY_UNSPECIFIED, Set.of());
    }
}
