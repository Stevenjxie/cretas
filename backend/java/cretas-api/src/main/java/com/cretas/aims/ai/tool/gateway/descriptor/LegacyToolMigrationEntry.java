package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.ApprovalPolicy;
import com.cretas.aims.ai.tool.gateway.ConfirmationPolicy;
import com.cretas.aims.ai.tool.gateway.DataClassification;
import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;
import com.cretas.aims.ai.tool.gateway.IdempotencyPolicy;
import com.cretas.aims.ai.tool.gateway.ToolDescriptor;
import com.cretas.aims.ai.tool.gateway.ToolEgressPolicy;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.entity.enums.FactoryType;

import java.util.Objects;
import java.util.Set;

/**
 * One deliberately narrow binding between the frozen legacy inventory and the Gateway.
 *
 * <p>This is not an approval policy. The source inventory entry remains visibly
 * {@code LEGACY_INFERRED + REVIEW_REQUIRED}; the migration manifest can only make an exact,
 * read-only, no-egress subset executable from the dedicated intent-dispatch lane.</p>
 */
public record LegacyToolMigrationEntry(
        String implementationClass,
        String toolName,
        ToolExecutor.ActionType actionType,
        ToolExecutor.RiskLevel riskLevel,
        boolean supportsPreview,
        boolean requiresPermission,
        Set<String> requiredPermissions,
        Set<String> allowedRoles,
        Set<FactoryType> allowedBusinessTypes,
        String version,
        Set<String> domainTags,
        DataClassification dataClassification) {

    public LegacyToolMigrationEntry {
        implementationClass = requireNonBlank(implementationClass, "implementationClass");
        toolName = requireNonBlank(toolName, "toolName");
        actionType = Objects.requireNonNull(actionType, "actionType");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        requiredPermissions = Set.copyOf(Objects.requireNonNull(
                requiredPermissions, "requiredPermissions"));
        allowedRoles = Set.copyOf(Objects.requireNonNull(allowedRoles, "allowedRoles"));
        allowedBusinessTypes = Set.copyOf(Objects.requireNonNull(
                allowedBusinessTypes, "allowedBusinessTypes"));
        version = requireNonBlank(version, "version");
        domainTags = Set.copyOf(Objects.requireNonNull(domainTags, "domainTags"));
        dataClassification = Objects.requireNonNull(dataClassification, "dataClassification");

        boolean readOnly = actionType == ToolExecutor.ActionType.READ
                || actionType == ToolExecutor.ActionType.ANALYZE;
        if (!readOnly || riskLevel != ToolExecutor.RiskLevel.LOW
                || supportsPreview || requiresPermission
                || !requiredPermissions.isEmpty() || !allowedRoles.isEmpty()) {
            throw new IllegalArgumentException(
                    "legacy migration entries must be READ/ANALYZE, LOW, no-preview, "
                            + "and unrestricted by legacy permission metadata");
        }
        if (allowedBusinessTypes.isEmpty()
                || !Set.of(FactoryType.RESTAURANT, FactoryType.BRANCH)
                .containsAll(allowedBusinessTypes)) {
            throw new IllegalArgumentException(
                    "legacy migration entries are limited to RESTAURANT/BRANCH");
        }
        if (domainTags.isEmpty()) {
            throw new IllegalArgumentException(
                    "legacy migration entries require an exact domain tag set");
        }
    }

    public ToolDescriptor toDescriptor() {
        return new ToolDescriptor(
                toolName,
                actionType,
                riskLevel,
                requiredPermissions,
                allowedRoles,
                allowedBusinessTypes,
                domainTags,
                version,
                false,
                ConfirmationPolicy.NOT_REQUIRED,
                ApprovalPolicy.NOT_REQUIRED,
                IdempotencyPolicy.NOT_REQUIRED,
                dataClassification,
                Set.of(ToolExecutionSource.AI_INTENT_DISPATCH),
                ToolEgressPolicy.denyAll(),
                DescriptorProvenance.LEGACY_INFERRED);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
