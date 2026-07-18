package com.cretas.aims.entity.ai;

import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Persistent exactly-once reservation for a governed tool command.
 *
 * <p>No raw idempotency key, confirmation token, parameters, downstream payload, or downstream
 * message is stored here. A row in {@link State#IN_PROGRESS} is intentionally sticky after a
 * crash: it represents an uncertain outcome and therefore blocks automatic re-execution.</p>
 */
@Entity
@Table(name = "tool_execution_idempotency", uniqueConstraints = {
        @UniqueConstraint(name = "uk_tei_replay_locator", columnNames = {
                "tenant_id", "principal_type", "principal_id", "tool_name",
                "descriptor_version", "idempotency_key_hash"
        })
}, indexes = {
        @Index(name = "idx_tei_state_updated", columnList = "state, updated_at"),
        @Index(name = "idx_tei_command_digest", columnList = "command_digest")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ToolExecutionIdempotencyRecord {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "tenant_id", length = 50, nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "business_type", length = 32, nullable = false, updatable = false)
    private String businessType;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", length = 16, nullable = false, updatable = false)
    private PrincipalType principalType;

    @Column(name = "principal_id", length = 100, nullable = false, updatable = false)
    private String principalId;

    @Column(name = "tool_name", length = 150, nullable = false, updatable = false)
    private String toolName;

    @Column(name = "descriptor_version", length = 64, nullable = false, updatable = false)
    private String descriptorVersion;

    @Column(name = "idempotency_key_hash", length = 64, nullable = false, updatable = false)
    private String idempotencyKeyHash;

    @Column(name = "command_digest", length = 64, nullable = false, updatable = false)
    private String commandDigest;

    @Column(name = "confirmation_fingerprint", length = 64, nullable = false, updatable = false)
    private String confirmationFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 20, nullable = false)
    private State state;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome_status", length = 32)
    private ToolExecutionStatus outcomeStatus;

    @Column(name = "original_audit_event_id", length = 36, nullable = false, updatable = false)
    private String originalAuditEventId;

    @Column(name = "result_code", length = 64)
    private String resultCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public static ToolExecutionIdempotencyRecord reserve(
            String id,
            String tenantId,
            String businessType,
            PrincipalType principalType,
            String principalId,
            String toolName,
            String descriptorVersion,
            String idempotencyKeyHash,
            String commandDigest,
            String confirmationFingerprint,
            String originalAuditEventId,
            LocalDateTime now) {
        ToolExecutionIdempotencyRecord record = new ToolExecutionIdempotencyRecord();
        record.id = id;
        record.tenantId = tenantId;
        record.businessType = businessType;
        record.principalType = principalType;
        record.principalId = principalId;
        record.toolName = toolName;
        record.descriptorVersion = descriptorVersion;
        record.idempotencyKeyHash = idempotencyKeyHash;
        record.commandDigest = commandDigest;
        record.confirmationFingerprint = confirmationFingerprint;
        record.state = State.IN_PROGRESS;
        record.originalAuditEventId = originalAuditEventId;
        record.createdAt = now;
        record.updatedAt = now;
        return record;
    }

    public enum State {
        IN_PROGRESS,
        SUCCEEDED,
        FAILED,
        IN_DOUBT
    }
}
