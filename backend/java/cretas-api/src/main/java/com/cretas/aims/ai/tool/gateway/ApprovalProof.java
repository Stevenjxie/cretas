package com.cretas.aims.ai.tool.gateway;

import java.time.Instant;

/** Parameter-bound approval evidence to be verified by a future gateway. */
public record ApprovalProof(
        String approvalId,
        String commandDigest,
        String approverPrincipalId,
        Instant approvedAt,
        Instant expiresAt) {

    public ApprovalProof {
        approvalId = ContractValidation.requireNonBlank(approvalId, "approvalId");
        commandDigest = ContractValidation.requireNonBlank(commandDigest, "commandDigest");
        approverPrincipalId = ContractValidation.requireNonBlank(
                approverPrincipalId, "approverPrincipalId");
        approvedAt = ContractValidation.requireNonNull(approvedAt, "approvedAt");
        expiresAt = ContractValidation.requireNonNull(expiresAt, "expiresAt");
        if (approvedAt.isAfter(expiresAt)) {
            throw new IllegalArgumentException("approvedAt must not be after expiresAt");
        }
    }
}
