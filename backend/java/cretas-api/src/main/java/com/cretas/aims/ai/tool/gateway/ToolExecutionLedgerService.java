package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.entity.ai.ToolExecutionAuditEvent;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.repository.ai.ToolExecutionAuditEventRepository;
import com.cretas.aims.repository.ai.ToolExecutionIdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Independent transactions for gateway audit and idempotency state.
 *
 * <p>Tool business work never runs inside these transactions. Consequently a process loss after
 * the business commit leaves a durable IN_PROGRESS row, which is interpreted as uncertain and
 * blocks an automatic retry.</p>
 */
@Service
@RequiredArgsConstructor
public class ToolExecutionLedgerService {

    private final ToolExecutionAuditEventRepository auditRepository;
    private final ToolExecutionIdempotencyRepository idempotencyRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String beginAudit(ToolExecutionCommand command, ExecutionPrincipal currentPrincipal) {
        LocalDateTime now = LocalDateTime.now();
        String auditEventId = UUID.randomUUID().toString();
        ToolExecutionAuditEvent event = ToolExecutionAuditEvent.start(
                auditEventId,
                ToolCommandDigest.persistentSecretFingerprint(command.requestId()),
                ToolCommandDigest.persistentSecretFingerprint(command.correlationId()),
                ToolCommandDigest.persistentSecretFingerprint(command.traceId()),
                currentPrincipal.tenantId(),
                currentPrincipal.businessType(),
                currentPrincipal.principalType(),
                currentPrincipal.principalId(),
                command.toolName(),
                command.expectedDescriptorVersion(),
                command.source(),
                command.mode(),
                now);
        auditRepository.saveAndFlush(event);
        return auditEventId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bindSecurityEvidence(
            String auditEventId,
            String commandDigest,
            String confirmationFingerprint,
            String idempotencyKeyHash) {
        int updated = auditRepository.bindSecurityEvidence(
                auditEventId,
                commandDigest,
                confirmationFingerprint,
                idempotencyKeyHash,
                ToolExecutionAuditEvent.State.STARTED);
        requireSingleUpdate(updated, "audit evidence binding");
    }

    /**
     * Creates the sticky IN_PROGRESS reservation. Unique-constraint conflicts are intentionally
     * allowed to escape so the gateway can classify the already-committed record in a new
     * transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolExecutionIdempotencyRecord reserve(Reservation reservation) {
        LocalDateTime now = LocalDateTime.now();
        ToolExecutionIdempotencyRecord record = ToolExecutionIdempotencyRecord.reserve(
                UUID.randomUUID().toString(),
                reservation.principal().tenantId(),
                reservation.principal().businessType(),
                reservation.principal().principalType(),
                reservation.principal().principalId(),
                reservation.toolName(),
                reservation.descriptorVersion(),
                reservation.idempotencyKeyHash(),
                reservation.commandDigest(),
                reservation.confirmationFingerprint(),
                reservation.auditEventId(),
                now);
        return idempotencyRepository.saveAndFlush(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ToolExecutionIdempotencyRecord> findExisting(Reservation reservation) {
        return idempotencyRepository
                .findByTenantIdAndPrincipalTypeAndPrincipalIdAndToolNameAndDescriptorVersionAndIdempotencyKeyHash(
                        reservation.principal().tenantId(),
                        reservation.principal().principalType(),
                        reservation.principal().principalId(),
                        reservation.toolName(),
                        reservation.descriptorVersion(),
                        reservation.idempotencyKeyHash());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeAudit(
            String auditEventId,
            ToolExecutionStatus outcomeStatus,
            GatewayResultCode resultCode) {
        int updated = auditRepository.completeStarted(
                auditEventId,
                ToolExecutionAuditEvent.State.STARTED,
                ToolExecutionAuditEvent.State.COMPLETED,
                outcomeStatus,
                resultCode.name(),
                LocalDateTime.now());
        requireSingleUpdate(updated, "audit completion");
    }

    /** Atomically closes the idempotency reservation and the current audit event. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeExecution(
            String idempotencyRecordId,
            String auditEventId,
            ToolExecutionIdempotencyRecord.State nextState,
            ToolExecutionStatus outcomeStatus,
            GatewayResultCode resultCode) {
        if (nextState == ToolExecutionIdempotencyRecord.State.IN_PROGRESS) {
            throw new IllegalArgumentException("IN_PROGRESS is not a completion state");
        }
        LocalDateTime now = LocalDateTime.now();
        int ledgerUpdated = idempotencyRepository.completeFromState(
                idempotencyRecordId,
                ToolExecutionIdempotencyRecord.State.IN_PROGRESS,
                nextState,
                outcomeStatus,
                resultCode.name(),
                now);
        requireSingleUpdate(ledgerUpdated, "idempotency completion");
        int auditUpdated = auditRepository.completeStarted(
                auditEventId,
                ToolExecutionAuditEvent.State.STARTED,
                ToolExecutionAuditEvent.State.COMPLETED,
                outcomeStatus,
                resultCode.name(),
                now);
        requireSingleUpdate(auditUpdated, "audit completion");
    }

    private static void requireSingleUpdate(int updated, String operation) {
        if (updated != 1) {
            throw new IllegalStateException(operation + " did not update exactly one row");
        }
    }

    public record Reservation(
            ExecutionPrincipal principal,
            String toolName,
            String descriptorVersion,
            String idempotencyKeyHash,
            String commandDigest,
            String confirmationFingerprint,
            String auditEventId) {
    }
}
