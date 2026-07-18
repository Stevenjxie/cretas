package com.cretas.aims.ai.tool.gateway.descriptor;

import java.util.List;
import java.util.Objects;

/** Immutable root document for the source-derived tool descriptor inventory. */
public record ToolDescriptorInventory(
        int schemaVersion,
        int expectedToolCount,
        int expectedLegacyCount,
        List<ToolDescriptorInventoryEntry> descriptors) {

    public ToolDescriptorInventory {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        if (expectedToolCount < 0 || expectedLegacyCount < 0) {
            throw new IllegalArgumentException("expected counts must not be negative");
        }
        descriptors = List.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
    }
}
