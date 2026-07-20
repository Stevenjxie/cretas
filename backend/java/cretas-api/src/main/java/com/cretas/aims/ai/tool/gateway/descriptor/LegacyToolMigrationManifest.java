package com.cretas.aims.ai.tool.gateway.descriptor;

import java.util.List;
import java.util.Objects;

/** Immutable root document for the temporary intent-dispatch migration allowlist. */
public record LegacyToolMigrationManifest(
        int schemaVersion,
        int expectedToolCount,
        List<LegacyToolMigrationEntry> tools) {

    public LegacyToolMigrationManifest {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        if (expectedToolCount < 0) {
            throw new IllegalArgumentException("expectedToolCount must not be negative");
        }
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        if (expectedToolCount != tools.size()) {
            throw new IllegalArgumentException("expectedToolCount does not match tools size");
        }
    }
}
