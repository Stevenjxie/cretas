package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable lookup and statistics facade over the D1 inventory.
 *
 * <p>This catalog has no Spring annotations and is not wired into execution. It is an audit and
 * migration input only.</p>
 */
public final class ToolDescriptorCatalog {

    private final ToolDescriptorInventory inventory;
    private final Map<String, ToolDescriptorInventoryEntry> byToolName;
    private final Map<String, ToolDescriptorInventoryEntry> byImplementationClass;
    private final ToolDescriptorStatistics statistics;

    public static ToolDescriptorCatalog loadDefault() {
        return new ToolDescriptorCatalog(new ToolDescriptorInventoryLoader().loadDefault());
    }

    public ToolDescriptorCatalog(ToolDescriptorInventory inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        Map<String, ToolDescriptorInventoryEntry> names = new LinkedHashMap<>();
        Map<String, ToolDescriptorInventoryEntry> classes = new LinkedHashMap<>();
        for (ToolDescriptorInventoryEntry entry : inventory.descriptors()) {
            if (names.putIfAbsent(entry.toolName(), entry) != null) {
                throw new IllegalArgumentException("duplicate toolName: " + entry.toolName());
            }
            if (classes.putIfAbsent(entry.implementationClass(), entry) != null) {
                throw new IllegalArgumentException(
                        "duplicate implementationClass: " + entry.implementationClass());
            }
        }
        this.byToolName = Collections.unmodifiableMap(names);
        this.byImplementationClass = Collections.unmodifiableMap(classes);
        this.statistics = calculateStatistics(inventory);
    }

    public ToolDescriptorInventory inventory() {
        return inventory;
    }

    public Optional<ToolDescriptorInventoryEntry> findByToolName(String toolName) {
        return Optional.ofNullable(byToolName.get(toolName));
    }

    public Optional<ToolDescriptorInventoryEntry> findByImplementationClass(
            String implementationClass) {
        return Optional.ofNullable(byImplementationClass.get(implementationClass));
    }

    public ToolDescriptorStatistics statistics() {
        return statistics;
    }

    private static ToolDescriptorStatistics calculateStatistics(ToolDescriptorInventory inventory) {
        EnumMap<ToolExecutor.ActionType, Long> actionTypes = zeroed(ToolExecutor.ActionType.class);
        EnumMap<ToolExecutor.RiskLevel, Long> riskLevels = zeroed(ToolExecutor.RiskLevel.class);
        EnumMap<ToolGovernanceStatus, Long> governanceStatuses = zeroed(ToolGovernanceStatus.class);
        long legacy = 0;
        long preview = 0;
        long permission = 0;
        for (ToolDescriptorInventoryEntry entry : inventory.descriptors()) {
            actionTypes.compute(entry.actionType(), (ignored, count) -> count + 1);
            riskLevels.compute(entry.riskLevel(), (ignored, count) -> count + 1);
            governanceStatuses.compute(entry.governanceStatus(), (ignored, count) -> count + 1);
            if (entry.provenance() == DescriptorProvenance.LEGACY_INFERRED) {
                legacy++;
            }
            if (entry.supportsPreview()) {
                preview++;
            }
            if (entry.requiresPermission()) {
                permission++;
            }
        }
        return new ToolDescriptorStatistics(
                inventory.descriptors().size(),
                Math.toIntExact(legacy),
                Collections.unmodifiableMap(actionTypes),
                Collections.unmodifiableMap(riskLevels),
                preview,
                permission,
                Collections.unmodifiableMap(governanceStatuses));
    }

    private static <E extends Enum<E>> EnumMap<E, Long> zeroed(Class<E> type) {
        EnumMap<E, Long> counts = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            counts.put(value, 0L);
        }
        return counts;
    }
}
