package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.ToolConfirmationLease.Lease;
import com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService.Reservation;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy.RehydratedPrincipal;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.ResolvedTool;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;

/** Production fail-closed implementation of the governed Tool execution boundary. */
@Component
@RequiredArgsConstructor
public class DefaultToolExecutionGateway implements ToolExecutionGateway {

    private final ToolPrincipalPolicy principalPolicy;
    private final ToolRuntimeRegistry runtimeRegistry;
    private final ToolConfirmationLease confirmationLease;
    private final ToolExecutionLedgerService ledgerService;
    private final ObjectMapper objectMapper;

    @Override
    public ToolExecutionResult execute(ToolExecutionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }

        Optional<RehydratedPrincipal> rehydrated =
                principalPolicy.rehydrate(command.principal());
        if (rehydrated.isEmpty()) {
            String auditEventId = ledgerService.beginAudit(command, command.principal());
            ledgerService.completeAudit(
                    auditEventId, ToolExecutionStatus.DENIED, GatewayResultCode.PRINCIPAL_DENIED);
            return result(command, auditEventId, ToolExecutionStatus.DENIED,
                    emptyPayload(), "Principal rejected", false);
        }
        RehydratedPrincipal current = rehydrated.get();
        String auditEventId = ledgerService.beginAudit(command, current.principal());

        if (!Instant.now().isBefore(command.deadline())) {
            ledgerService.completeAudit(
                    auditEventId, ToolExecutionStatus.TIMEOUT, GatewayResultCode.DEADLINE_EXPIRED);
            return result(command, auditEventId, ToolExecutionStatus.TIMEOUT,
                    emptyPayload(), "Execution deadline expired", false);
        }

        Optional<ResolvedTool> resolvedOptional =
                runtimeRegistry.resolve(command, current.principal().permissions());
        if (resolvedOptional.isEmpty()) {
            ledgerService.completeAudit(
                    auditEventId, ToolExecutionStatus.DENIED, GatewayResultCode.POLICY_DENIED);
            return result(command, auditEventId, ToolExecutionStatus.DENIED,
                    emptyPayload(), "Tool policy rejected", false);
        }
        ResolvedTool resolved = resolvedOptional.get();
        ToolDescriptor descriptor = resolved.descriptor();

        if (command.mode() == ToolExecutionMode.PREVIEW) {
            ledgerService.completeAudit(
                    auditEventId,
                    ToolExecutionStatus.PREVIEW_UNSUPPORTED,
                    GatewayResultCode.PREVIEW_UNSUPPORTED);
            return result(command, auditEventId, ToolExecutionStatus.PREVIEW_UNSUPPORTED,
                    emptyPayload(), "Preview is not supported", false);
        }
        if (descriptor.egressPolicy().mode() != EgressMode.DENY_ALL) {
            ledgerService.completeAudit(
                    auditEventId, ToolExecutionStatus.DENIED, GatewayResultCode.POLICY_DENIED);
            return result(command, auditEventId, ToolExecutionStatus.DENIED,
                    emptyPayload(), "Tool egress policy rejected", false);
        }
        if (descriptor.approvalPolicy() != ApprovalPolicy.NOT_REQUIRED) {
            ledgerService.completeAudit(
                    auditEventId,
                    ToolExecutionStatus.APPROVAL_REQUIRED,
                    GatewayResultCode.POLICY_DENIED);
            return result(command, auditEventId, ToolExecutionStatus.APPROVAL_REQUIRED,
                    emptyPayload(), "Approval policy is not implemented", false);
        }
        if (descriptor.confirmationPolicy() != ConfirmationPolicy.REQUIRED_FOR_EXECUTION
                || command.confirmationProof().isEmpty()) {
            ledgerService.completeAudit(
                    auditEventId,
                    ToolExecutionStatus.CONFIRMATION_REQUIRED,
                    GatewayResultCode.CONFIRMATION_REQUIRED);
            return result(command, auditEventId, ToolExecutionStatus.CONFIRMATION_REQUIRED,
                    emptyPayload(), "Confirmation is required", false);
        }
        if (descriptor.idempotencyPolicy() != IdempotencyPolicy.REQUIRED_FOR_EXECUTION
                || command.idempotencyKey().isEmpty()) {
            ledgerService.completeAudit(
                    auditEventId,
                    ToolExecutionStatus.DENIED,
                    GatewayResultCode.IDEMPOTENCY_REQUIRED);
            return result(command, auditEventId, ToolExecutionStatus.DENIED,
                    emptyPayload(), "Idempotency key is required", false);
        }

        Long currentUserId = Long.valueOf(current.principal().principalId());
        String commandDigest = ToolCommandDigest.commandDigest(
                current.principal().tenantId(),
                currentUserId,
                command.toolName(),
                descriptor.version(),
                command.mode(),
                command.parameters());
        ConfirmationProof proof = command.confirmationProof().orElseThrow();
        if (!constantTimeEquals(commandDigest, proof.commandDigest())) {
            ledgerService.completeAudit(
                    auditEventId,
                    ToolExecutionStatus.CONFIRMATION_REQUIRED,
                    GatewayResultCode.CONFIRMATION_REQUIRED);
            return result(command, auditEventId, ToolExecutionStatus.CONFIRMATION_REQUIRED,
                    emptyPayload(), "Confirmation binding rejected", false);
        }
        String confirmationFingerprint =
                ToolCommandDigest.persistentSecretFingerprint(proof.proofToken());
        String idempotencyKeyHash = ToolCommandDigest.persistentSecretFingerprint(
                command.idempotencyKey().orElseThrow());
        ledgerService.bindSecurityEvidence(
                auditEventId, commandDigest, confirmationFingerprint, idempotencyKeyHash);

        Reservation reservation = new Reservation(
                current.principal(),
                command.toolName(),
                descriptor.version(),
                idempotencyKeyHash,
                commandDigest,
                confirmationFingerprint,
                auditEventId);
        ToolExecutionIdempotencyRecord idempotencyRecord;
        try {
            idempotencyRecord = ledgerService.reserve(reservation);
        } catch (DataIntegrityViolationException conflict) {
            return classifyExisting(command, auditEventId, reservation);
        } catch (RuntimeException ledgerUnavailable) {
            ledgerService.completeAudit(
                    auditEventId,
                    ToolExecutionStatus.OUTCOME_UNKNOWN,
                    GatewayResultCode.PERSISTENCE_FINALIZATION_UNCERTAIN);
            return result(command, auditEventId, ToolExecutionStatus.OUTCOME_UNKNOWN,
                    emptyPayload(), "Idempotency ledger unavailable", false);
        }

        if (!Instant.now().isBefore(proof.expiresAt())) {
            return finishReserved(
                    command,
                    idempotencyRecord,
                    auditEventId,
                    ToolExecutionIdempotencyRecord.State.FAILED,
                    ToolExecutionStatus.CONFIRMATION_REQUIRED,
                    GatewayResultCode.CONFIRMATION_REJECTED,
                    emptyPayload(),
                    "Confirmation expired");
        }

        Optional<Lease> leaseOptional;
        try {
            leaseOptional = confirmationLease.claim(
                    command, current.principal(), commandDigest);
        } catch (RuntimeException claimUncertain) {
            return finishReserved(
                    command,
                    idempotencyRecord,
                    auditEventId,
                    ToolExecutionIdempotencyRecord.State.IN_DOUBT,
                    ToolExecutionStatus.OUTCOME_UNKNOWN,
                    GatewayResultCode.CONFIRMATION_CLAIM_UNCERTAIN,
                    emptyPayload(),
                    "Confirmation claim requires reconciliation");
        }
        if (leaseOptional.isEmpty()) {
            return finishReserved(
                    command,
                    idempotencyRecord,
                    auditEventId,
                    ToolExecutionIdempotencyRecord.State.FAILED,
                    ToolExecutionStatus.CONFIRMATION_REQUIRED,
                    GatewayResultCode.CONFIRMATION_REJECTED,
                    emptyPayload(),
                    "Confirmation rejected");
        }
        Lease lease = leaseOptional.get();

        try {
            String arguments = objectMapper.writeValueAsString(lease.persistedParameters());
            ToolCall toolCall = ToolCall.of(command.requestId(), command.toolName(), arguments);
            ToolExecutor executor = resolved.executor();
            String rawResponse = executor.execute(
                    toolCall, current.executionContext());
            JsonNode payload = objectMapper.readTree(rawResponse);
            if (payload == null || !payload.isObject() || !payload.has("success")
                    || !payload.get("success").isBoolean()) {
                return outcomeUnknownAfterClaim(
                        command, idempotencyRecord, auditEventId, lease,
                        GatewayResultCode.TOOL_OUTCOME_UNCERTAIN);
            }
            if (payload.get("success").booleanValue()) {
                if (!safelyResolve(lease, true)) {
                    return finishReserved(
                            command,
                            idempotencyRecord,
                            auditEventId,
                            ToolExecutionIdempotencyRecord.State.IN_DOUBT,
                            ToolExecutionStatus.OUTCOME_UNKNOWN,
                            GatewayResultCode.CONFIRMATION_RESOLUTION_UNCERTAIN,
                            emptyPayload(),
                            "Execution outcome requires reconciliation");
                }
                return finishReserved(
                        command,
                        idempotencyRecord,
                        auditEventId,
                        ToolExecutionIdempotencyRecord.State.SUCCEEDED,
                        ToolExecutionStatus.SUCCEEDED,
                        GatewayResultCode.TOOL_SUCCEEDED,
                        payload,
                        "Tool execution succeeded");
            }
            if ("NEED_MORE_INFO".equals(payload.path("status").asText())) {
                if (!safelyResolve(lease, false)) {
                    return finishReserved(
                            command,
                            idempotencyRecord,
                            auditEventId,
                            ToolExecutionIdempotencyRecord.State.IN_DOUBT,
                            ToolExecutionStatus.OUTCOME_UNKNOWN,
                            GatewayResultCode.CONFIRMATION_RESOLUTION_UNCERTAIN,
                            emptyPayload(),
                            "Execution outcome requires reconciliation");
                }
                return finishReserved(
                        command,
                        idempotencyRecord,
                        auditEventId,
                        ToolExecutionIdempotencyRecord.State.FAILED,
                        ToolExecutionStatus.FAILED,
                        GatewayResultCode.TOOL_NEEDS_INFO,
                        payload,
                        "Tool needs more information");
            }
            return outcomeUnknownAfterClaim(
                    command, idempotencyRecord, auditEventId, lease,
                    GatewayResultCode.TOOL_OUTCOME_UNCERTAIN);
        } catch (Exception uncertainWriteFailure) {
            return outcomeUnknownAfterClaim(
                    command, idempotencyRecord, auditEventId, lease,
                    GatewayResultCode.TOOL_OUTCOME_UNCERTAIN);
        }
    }

    private ToolExecutionResult classifyExisting(
            ToolExecutionCommand command,
            String auditEventId,
            Reservation reservation) {
        Optional<ToolExecutionIdempotencyRecord> existingOptional =
                ledgerService.findExisting(reservation);
        if (existingOptional.isEmpty()) {
            ledgerService.completeAudit(
                    auditEventId,
                    ToolExecutionStatus.OUTCOME_UNKNOWN,
                    GatewayResultCode.IN_FLIGHT_OR_IN_DOUBT);
            return result(command, auditEventId, ToolExecutionStatus.OUTCOME_UNKNOWN,
                    emptyPayload(), "Idempotency state is unavailable", false);
        }
        ToolExecutionIdempotencyRecord existing = existingOptional.get();
        boolean exactBinding = reservation.principal().businessType().equals(existing.getBusinessType())
                && constantTimeEquals(reservation.commandDigest(), existing.getCommandDigest())
                && constantTimeEquals(
                        reservation.confirmationFingerprint(), existing.getConfirmationFingerprint());
        if (!exactBinding) {
            ledgerService.completeAudit(
                    auditEventId,
                    ToolExecutionStatus.DENIED,
                    GatewayResultCode.IDEMPOTENCY_CONFLICT);
            return result(command, auditEventId, ToolExecutionStatus.DENIED,
                    emptyPayload(), "Idempotency key conflicts with another command", false);
        }
        if (existing.getState() == ToolExecutionIdempotencyRecord.State.SUCCEEDED
                || existing.getState() == ToolExecutionIdempotencyRecord.State.FAILED) {
            ledgerService.completeAudit(
                    auditEventId,
                    ToolExecutionStatus.IDEMPOTENT_REPLAY,
                    GatewayResultCode.IDEMPOTENT_REPLAY);
            ObjectNode replayPayload = JsonNodeFactory.instance.objectNode();
            replayPayload.put("originalStatus", existing.getOutcomeStatus() == null
                    ? "UNKNOWN" : existing.getOutcomeStatus().name());
            return result(command, auditEventId, ToolExecutionStatus.IDEMPOTENT_REPLAY,
                    replayPayload, "Previous completed result replayed without execution", true);
        }
        ledgerService.completeAudit(
                auditEventId,
                ToolExecutionStatus.OUTCOME_UNKNOWN,
                GatewayResultCode.IN_FLIGHT_OR_IN_DOUBT);
        return result(command, auditEventId, ToolExecutionStatus.OUTCOME_UNKNOWN,
                emptyPayload(), "Execution is in progress or requires reconciliation", false);
    }

    private ToolExecutionResult outcomeUnknownAfterClaim(
            ToolExecutionCommand command,
            ToolExecutionIdempotencyRecord record,
            String auditEventId,
            Lease lease,
            GatewayResultCode resultCode) {
        safelyResolve(lease, false);
        return finishReserved(
                command,
                record,
                auditEventId,
                ToolExecutionIdempotencyRecord.State.IN_DOUBT,
                ToolExecutionStatus.OUTCOME_UNKNOWN,
                resultCode,
                emptyPayload(),
                "Execution outcome requires reconciliation");
    }

    private ToolExecutionResult finishReserved(
            ToolExecutionCommand command,
            ToolExecutionIdempotencyRecord record,
            String auditEventId,
            ToolExecutionIdempotencyRecord.State state,
            ToolExecutionStatus status,
            GatewayResultCode code,
            JsonNode payload,
            String message) {
        try {
            ledgerService.completeExecution(record.getId(), auditEventId, state, status, code);
            return result(command, auditEventId, status, payload, message, false);
        } catch (RuntimeException finalizationFailure) {
            try {
                ledgerService.completeExecution(
                        record.getId(),
                        auditEventId,
                        ToolExecutionIdempotencyRecord.State.IN_DOUBT,
                        ToolExecutionStatus.OUTCOME_UNKNOWN,
                        GatewayResultCode.PERSISTENCE_FINALIZATION_UNCERTAIN);
            } catch (RuntimeException ignored) {
                // A committed terminal row or sticky IN_PROGRESS reservation already blocks retry.
            }
            return result(command, auditEventId, ToolExecutionStatus.OUTCOME_UNKNOWN,
                    emptyPayload(), "Execution outcome requires reconciliation", false);
        }
    }

    private static ToolExecutionResult result(
            ToolExecutionCommand command,
            String auditEventId,
            ToolExecutionStatus status,
            JsonNode payload,
            String message,
            boolean replayed) {
        return new ToolExecutionResult(
                command.requestId(),
                command.toolName(),
                command.expectedDescriptorVersion(),
                auditEventId,
                command.traceId(),
                status,
                payload,
                message,
                replayed);
    }

    private static ObjectNode emptyPayload() {
        return JsonNodeFactory.instance.objectNode();
    }

    private boolean safelyResolve(Lease lease, boolean success) {
        try {
            return confirmationLease.resolve(lease, success);
        } catch (RuntimeException resolutionUncertain) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }
}
