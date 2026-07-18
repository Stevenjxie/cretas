package com.cretas.aims.ai.tool.gateway.descriptor;

import java.util.List;
import java.util.Objects;

/** Immutable root document for explicit runtime tool policies. */
public record RuntimeToolPolicyManifest(
        int schemaVersion,
        int expectedPolicyCount,
        List<RuntimeToolPolicyEntry> policies) {

    public RuntimeToolPolicyManifest {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        if (expectedPolicyCount < 0) {
            throw new IllegalArgumentException("expectedPolicyCount must not be negative");
        }
        policies = List.copyOf(Objects.requireNonNull(policies, "policies"));
        if (expectedPolicyCount != policies.size()) {
            throw new IllegalArgumentException("expectedPolicyCount does not match policies size");
        }
    }
}
