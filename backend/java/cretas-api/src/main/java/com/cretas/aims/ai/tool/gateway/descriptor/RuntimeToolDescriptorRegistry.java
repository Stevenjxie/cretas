package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;
import com.cretas.aims.ai.tool.gateway.ToolDescriptor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fail-closed registry of policies that are explicit in source, approved in D1, and present in
 * the independent runtime manifest.
 *
 * <p>This registry does not know about {@code ToolRegistry} and cannot execute a tool.</p>
 */
public final class RuntimeToolDescriptorRegistry {

    private final Map<String, ToolDescriptor> descriptorsByName;

    public static RuntimeToolDescriptorRegistry loadDefault() {
        return new RuntimeToolDescriptorRegistry(
                new ToolDescriptorInventoryLoader().loadDefault(),
                new RuntimeToolPolicyLoader().loadDefault());
    }

    public RuntimeToolDescriptorRegistry(
            ToolDescriptorInventory inventory,
            RuntimeToolPolicyManifest manifest) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(manifest, "manifest");

        Map<String, ToolDescriptorInventoryEntry> approvedInventory = inventory.descriptors().stream()
                .filter(entry -> entry.governanceStatus() == ToolGovernanceStatus.APPROVED)
                .collect(Collectors.toMap(
                        ToolDescriptorInventoryEntry::toolName,
                        entry -> entry,
                        (left, right) -> {
                            throw new IllegalArgumentException(
                                    "duplicate approved inventory toolName: " + left.toolName());
                        },
                        LinkedHashMap::new));
        Map<String, RuntimeToolPolicyEntry> runtimePolicies = manifest.policies().stream()
                .collect(Collectors.toMap(
                        RuntimeToolPolicyEntry::toolName,
                        entry -> entry,
                        (left, right) -> {
                            throw new IllegalArgumentException(
                                    "duplicate runtime policy toolName: " + left.toolName());
                        },
                        LinkedHashMap::new));

        approvedInventory.values().forEach(
                RuntimeToolDescriptorRegistry::validateApprovedInventoryEntry);

        if (!approvedInventory.keySet().equals(runtimePolicies.keySet())) {
            Set<String> missingRuntimePolicies = approvedInventory.keySet().stream()
                    .filter(toolName -> !runtimePolicies.containsKey(toolName))
                    .collect(Collectors.toUnmodifiableSet());
            Set<String> unapprovedRuntimePolicies = runtimePolicies.keySet().stream()
                    .filter(toolName -> !approvedInventory.containsKey(toolName))
                    .collect(Collectors.toUnmodifiableSet());
            throw new IllegalArgumentException(
                    "runtime policy set must exactly match APPROVED inventory; missing="
                            + missingRuntimePolicies + ", unapproved=" + unapprovedRuntimePolicies);
        }

        Map<String, ToolDescriptor> approvedDescriptors = new LinkedHashMap<>();
        for (Map.Entry<String, RuntimeToolPolicyEntry> binding : runtimePolicies.entrySet()) {
            ToolDescriptorInventoryEntry inventoryEntry = approvedInventory.get(binding.getKey());
            RuntimeToolPolicyEntry runtimeEntry = binding.getValue();
            validateAlignment(inventoryEntry, runtimeEntry);
            approvedDescriptors.put(binding.getKey(), runtimeEntry.toDescriptor());
        }
        this.descriptorsByName = Collections.unmodifiableMap(approvedDescriptors);
    }

    public Optional<ToolDescriptor> findApproved(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(descriptorsByName.get(toolName));
    }

    public Set<String> approvedToolNames() {
        return descriptorsByName.keySet();
    }

    private static void validateAlignment(
            ToolDescriptorInventoryEntry inventory,
            RuntimeToolPolicyEntry runtime) {
        ToolDescriptor descriptor = runtime.toDescriptor();
        if (inventory.provenance() != DescriptorProvenance.EXPLICIT
                || inventory.governanceStatus() != ToolGovernanceStatus.APPROVED
                || descriptor.provenance() != DescriptorProvenance.EXPLICIT) {
            throw drift(runtime.toolName(), "provenance/governance");
        }
        if (!inventory.implementationClass().equals(runtime.implementationClass())) {
            throw drift(runtime.toolName(), "implementationClass");
        }
        if (inventory.actionType() != descriptor.actionType()) {
            throw drift(runtime.toolName(), "actionType");
        }
        if (inventory.riskLevel() != descriptor.riskLevel()) {
            throw drift(runtime.toolName(), "riskLevel");
        }
        if (!inventory.requiredPermissions().equals(descriptor.requiredPermissions())) {
            throw drift(runtime.toolName(), "requiredPermissions");
        }
        if (!inventory.requiresPermission()) {
            throw drift(runtime.toolName(), "requiresPermission");
        }
        if (!inventory.domainTags().equals(descriptor.domainTags())) {
            throw drift(runtime.toolName(), "domainTags");
        }
        if (!inventory.version().equals(descriptor.version())) {
            throw drift(runtime.toolName(), "version");
        }
        if (inventory.supportsPreview() != descriptor.supportsPreview()) {
            throw drift(runtime.toolName(), "supportsPreview");
        }
    }

    private static void validateApprovedInventoryEntry(ToolDescriptorInventoryEntry inventory) {
        ToolDescriptorOverrideFlags flags = inventory.overrideFlags();
        boolean completeSourceMetadata = flags.actionType()
                && flags.riskLevel()
                && flags.supportsPreview()
                && flags.requiresPermission()
                && flags.hasPermission()
                && flags.requiredPermissions()
                && flags.version()
                && flags.domainTags();
        if (inventory.provenance() != DescriptorProvenance.EXPLICIT
                || inventory.governanceStatus() != ToolGovernanceStatus.APPROVED
                || !completeSourceMetadata
                || !inventory.requiresPermission()
                || inventory.requiredPermissions().isEmpty()
                || inventory.domainTags().isEmpty()) {
            throw drift(inventory.toolName(), "explicit source approval");
        }
    }

    private static IllegalArgumentException drift(String toolName, String field) {
        return new IllegalArgumentException(
                "runtime policy drift for " + toolName + ": " + field);
    }
}
