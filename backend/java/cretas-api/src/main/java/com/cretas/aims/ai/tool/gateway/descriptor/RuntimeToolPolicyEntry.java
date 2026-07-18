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

import java.util.Set;

/**
 * One explicit runtime policy plus its implementation-class binding.
 *
 * <p>The policy covers every field of {@link ToolDescriptor}. It is deliberately not an adapter
 * from D1 legacy inventory metadata.</p>
 */
public record RuntimeToolPolicyEntry(
        String implementationClass,
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

    public RuntimeToolPolicyEntry {
        if (implementationClass == null || implementationClass.isBlank()) {
            throw new IllegalArgumentException("implementationClass must not be blank");
        }
        if (provenance != DescriptorProvenance.EXPLICIT) {
            throw new IllegalArgumentException("runtime policy provenance must be EXPLICIT");
        }
        ToolDescriptor descriptor = new ToolDescriptor(
                toolName,
                actionType,
                riskLevel,
                requiredPermissions,
                allowedRoles,
                allowedBusinessTypes,
                domainTags,
                version,
                supportsPreview,
                confirmationPolicy,
                approvalPolicy,
                idempotencyPolicy,
                dataClassification,
                allowedSources,
                egressPolicy,
                provenance);
        if (descriptor.requiredPermissions().isEmpty() && descriptor.allowedRoles().isEmpty()) {
            throw new IllegalArgumentException(
                    "runtime policies require permissions or an allowed role");
        }
        if (descriptor.domainTags().isEmpty()) {
            throw new IllegalArgumentException(
                    "runtime policies require at least one explicit domain tag");
        }
        toolName = descriptor.toolName();
        actionType = descriptor.actionType();
        riskLevel = descriptor.riskLevel();
        requiredPermissions = descriptor.requiredPermissions();
        allowedRoles = descriptor.allowedRoles();
        allowedBusinessTypes = descriptor.allowedBusinessTypes();
        domainTags = descriptor.domainTags();
        version = descriptor.version();
        confirmationPolicy = descriptor.confirmationPolicy();
        approvalPolicy = descriptor.approvalPolicy();
        idempotencyPolicy = descriptor.idempotencyPolicy();
        dataClassification = descriptor.dataClassification();
        allowedSources = descriptor.allowedSources();
        egressPolicy = descriptor.egressPolicy();
        provenance = descriptor.provenance();
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
                supportsPreview,
                confirmationPolicy,
                approvalPolicy,
                idempotencyPolicy,
                dataClassification,
                allowedSources,
                egressPolicy,
                provenance);
    }
}
