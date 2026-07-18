package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.enums.FactoryType;

import java.util.Set;

/**
 * Versioned governance metadata for one registered tool.
 *
 * <p>Legacy metadata remains visibly inferred. External metadata remains visibly untrusted and
 * must provide every field needed for a later policy decision before it can enter a registry.</p>
 */
public record ToolDescriptor(
        String toolName,
        ToolExecutor.ActionType actionType,
        ToolExecutor.RiskLevel riskLevel,
        Set<String> requiredPermissions,
        Set<String> allowedRoles,
        Set<FactoryType> allowedBusinessTypes,
        Set<String> domainTags,
        String version,
        boolean supportsPreview,
        ConfirmationPolicy confirmationPolicy,
        ApprovalPolicy approvalPolicy,
        IdempotencyPolicy idempotencyPolicy,
        DataClassification dataClassification,
        Set<ToolExecutionSource> allowedSources,
        ToolEgressPolicy egressPolicy,
        DescriptorProvenance provenance) {

    public ToolDescriptor {
        toolName = ContractValidation.requireNonBlank(toolName, "toolName");
        actionType = ContractValidation.requireNonNull(actionType, "actionType");
        riskLevel = ContractValidation.requireNonNull(riskLevel, "riskLevel");
        requiredPermissions = ContractValidation.immutableNonBlankSet(
                requiredPermissions, "requiredPermissions");
        allowedRoles = ContractValidation.immutableNonBlankSet(allowedRoles, "allowedRoles");
        allowedBusinessTypes = ContractValidation.immutableNonNullSet(
                allowedBusinessTypes, "allowedBusinessTypes");
        if (allowedBusinessTypes.isEmpty()) {
            throw new IllegalArgumentException("allowedBusinessTypes must not be empty");
        }
        domainTags = ContractValidation.immutableNonBlankSet(domainTags, "domainTags");
        version = ContractValidation.requireNonBlank(version, "version");
        confirmationPolicy = ContractValidation.requireNonNull(
                confirmationPolicy, "confirmationPolicy");
        approvalPolicy = ContractValidation.requireNonNull(approvalPolicy, "approvalPolicy");
        idempotencyPolicy = ContractValidation.requireNonNull(
                idempotencyPolicy, "idempotencyPolicy");
        dataClassification = ContractValidation.requireNonNull(
                dataClassification, "dataClassification");
        allowedSources = ContractValidation.immutableNonNullSet(allowedSources, "allowedSources");
        if (allowedSources.isEmpty()) {
            throw new IllegalArgumentException("allowedSources must not be empty");
        }
        egressPolicy = ContractValidation.requireNonNull(egressPolicy, "egressPolicy");
        provenance = ContractValidation.requireNonNull(provenance, "provenance");

        if (provenance == DescriptorProvenance.EXTERNAL_UNTRUSTED) {
            requireExternalGovernance(requiredPermissions, domainTags, egressPolicy);
        }
        if (provenance == DescriptorProvenance.EXPLICIT
                && egressPolicy.mode() == EgressMode.LEGACY_UNSPECIFIED) {
            throw new IllegalArgumentException(
                    "explicit descriptors require an explicit egress policy");
        }
        if (provenance == DescriptorProvenance.EXPLICIT
                && requiredPermissions.isEmpty()
                && allowedRoles.isEmpty()) {
            throw new IllegalArgumentException(
                    "explicit descriptors require permissions or an allowed role");
        }
    }

    private static void requireExternalGovernance(
            Set<String> requiredPermissions,
            Set<String> domainTags,
            ToolEgressPolicy egressPolicy) {
        if (requiredPermissions.isEmpty()) {
            throw new IllegalArgumentException(
                    "external descriptors require explicit permissions");
        }
        if (domainTags.isEmpty()) {
            throw new IllegalArgumentException(
                    "external descriptors require at least one explicit domain tag");
        }
        if (egressPolicy.mode() == EgressMode.LEGACY_UNSPECIFIED) {
            throw new IllegalArgumentException(
                    "external descriptors require an explicit egress policy");
        }
    }
}
