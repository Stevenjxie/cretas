package com.cretas.aims.entity.ai;

import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Minimal, payload-free audit event for one gateway invocation. */
@Entity
@Table(name = "tool_execution_audit_events", indexes = {
        @Index(name = "idx_tea_tenant_started", columnList = "tenant_id, started_at"),
        @Index(name = "idx_tea_trace_fingerprint", columnList = "trace_fingerprint"),
        @Index(name = "idx_tea_tool_status", columnList = "tool_name, outcome_status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ToolExecutionAuditEvent {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "request_fingerprint", length = 64, nullable = false, updatable = false)
    private String requestFingerprint;

    @Column(name = "correlation_fingerprint", length = 64, nullable = false, updatable = false)
    private String correlationFingerprint;

    @Column(name = "trace_fingerprint", length = 64, nullable = false, updatable = false)
    private String traceFingerprint;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 32, nullable = false, updatable = false)
    private ToolExecutionSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", length = 16, nullable = false, updatable = false)
    private ToolExecutionMode executionMode;

    @Column(name = "command_digest", length = 64)
    private String commandDigest;

    @Column(name = "confirmation_fingerprint", length = 64)
    private String confirmationFingerprint;

    @Column(name = "idempotency_key_hash", length = 64)
    private String idempotencyKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 16, nullable = false)
    private State state;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome_status", length = 32)
    private ToolExecutionStatus outcomeStatus;

    @Column(name = "result_code", length = 64)
    private String resultCode;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public static ToolExecutionAuditEvent start(
            String id,
            String requestFingerprint,
            String correlationFingerprint,
            String traceFingerprint,
            String tenantId,
            String businessType,
            PrincipalType principalType,
            String principalId,
            String toolName,
            String descriptorVersion,
            ToolExecutionSource source,
            ToolExecutionMode executionMode,
            LocalDateTime now) {
        ToolExecutionAuditEvent event = new ToolExecutionAuditEvent();
        event.id = id;
        event.requestFingerprint = requestFingerprint;
        event.correlationFingerprint = correlationFingerprint;
        event.traceFingerprint = traceFingerprint;
        event.tenantId = tenantId;
        event.businessType = businessType;
        event.principalType = principalType;
        event.principalId = principalId;
        event.toolName = toolName;
        event.descriptorVersion = descriptorVersion;
        event.source = source;
        event.executionMode = executionMode;
        event.state = State.STARTED;
        event.startedAt = now;
        return event;
    }

    public enum State {
        STARTED,
        COMPLETED
    }
}
