package com.cretas.aims.dto.ai;

import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Parameter-bound confirmation request. The opaque proof token is deliberately excluded from
 * JSON and must be supplied only through {@code X-Cretas-Confirmation-Token}.
 *
 * <p>{@code requestId} and {@code idempotencyKey} reserve the future Gateway HTTP contract. They
 * are validated here but are not yet persisted or used as execution/idempotency authority. In
 * this phase, single-use execution is guaranteed only by the database-backed atomic token claim.
 */
public record IntentConfirmationRequest(
        @NotBlank(message = "commandDigest is required")
        @Pattern(regexp = "^[0-9a-f]{64}$",
                message = "commandDigest must be 64 lowercase hexadecimal characters")
        String commandDigest,

        @NotNull(message = "expiresAt is required")
        @Future(message = "confirmation proof has expired")
        Instant expiresAt,

        @NotBlank(message = "requestId is required")
        @Size(min = 8, max = 128, message = "requestId length must be between 8 and 128")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]*$",
                message = "requestId contains unsupported characters")
        String requestId,

        @NotBlank(message = "idempotencyKey is required")
        @Size(min = 8, max = 128, message = "idempotencyKey length must be between 8 and 128")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]*$",
                message = "idempotencyKey contains unsupported characters")
        String idempotencyKey) {

    public ConfirmationProof toConfirmationProof(String proofToken) {
        return new ConfirmationProof(proofToken, commandDigest, expiresAt);
    }

    /** Reject every field outside the four-field JSON contract without echoing its value. */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignoredValue) {
        throw new IllegalArgumentException("Unsupported confirmation request field");
    }
}
