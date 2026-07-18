package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One source-derived descriptor in the migration inventory.
 *
 * <p>This type deliberately remains separate from the runtime {@code ToolDescriptor}. The D1
 * inventory records legacy truth and review debt; it must not be adapted into runtime policy until
 * governance decisions have been made.</p>
 */
public record ToolDescriptorInventoryEntry(
        String toolName,
        String implementationClass,
        DescriptorProvenance provenance,
        ToolExecutor.ActionType actionType,
        ToolExecutor.RiskLevel riskLevel,
        boolean supportsPreview,
        boolean requiresPermission,
        Set<String> requiredPermissions,
        Set<String> allowedRoles,
        String version,
        Set<String> domainTags,
        ToolDescriptorOverrideFlags overrideFlags,
        ToolGovernanceStatus governanceStatus) {

    public ToolDescriptorInventoryEntry {
        toolName = requireNonBlank(toolName, "toolName");
        implementationClass = requireNonBlank(implementationClass, "implementationClass");
        provenance = Objects.requireNonNull(provenance, "provenance");
        actionType = Objects.requireNonNull(actionType, "actionType");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        requiredPermissions = immutableStrings(requiredPermissions, "requiredPermissions");
        allowedRoles = immutableStrings(allowedRoles, "allowedRoles");
        version = requireNonBlank(version, "version");
        domainTags = immutableStrings(domainTags, "domainTags");
        overrideFlags = Objects.requireNonNull(overrideFlags, "overrideFlags");
        governanceStatus = Objects.requireNonNull(governanceStatus, "governanceStatus");
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static Set<String> immutableStrings(Set<String> values, String field) {
        Objects.requireNonNull(values, field);
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            copy.add(requireNonBlank(value, field + " entry"));
        }
        return Set.copyOf(copy);
    }
}
