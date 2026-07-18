package com.cretas.aims.ai.tool.gateway;

import java.time.Instant;

/** Opaque, parameter-bound confirmation evidence to be verified by a future gateway. */
public record ConfirmationProof(
        String proofToken,
        String commandDigest,
        Instant expiresAt) {

    public ConfirmationProof {
        proofToken = ContractValidation.requireNonBlank(proofToken, "proofToken");
        commandDigest = ContractValidation.requireNonBlank(commandDigest, "commandDigest");
        expiresAt = ContractValidation.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "ConfirmationProof[proofToken=<redacted>, commandDigest=" + commandDigest
                + ", expiresAt=" + expiresAt + "]";
    }
}
