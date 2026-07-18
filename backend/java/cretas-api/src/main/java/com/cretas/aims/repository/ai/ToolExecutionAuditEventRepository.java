package com.cretas.aims.repository.ai;

import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.entity.ai.ToolExecutionAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface ToolExecutionAuditEventRepository
        extends JpaRepository<ToolExecutionAuditEvent, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ToolExecutionAuditEvent e SET e.commandDigest = :commandDigest, "
            + "e.confirmationFingerprint = :confirmationFingerprint, "
            + "e.idempotencyKeyHash = :idempotencyKeyHash "
            + "WHERE e.id = :id AND e.state = :startedState")
    int bindSecurityEvidence(
            @Param("id") String id,
            @Param("commandDigest") String commandDigest,
            @Param("confirmationFingerprint") String confirmationFingerprint,
            @Param("idempotencyKeyHash") String idempotencyKeyHash,
            @Param("startedState") ToolExecutionAuditEvent.State startedState);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ToolExecutionAuditEvent e SET e.state = :completedState, "
            + "e.outcomeStatus = :outcomeStatus, e.resultCode = :resultCode, "
            + "e.completedAt = :now WHERE e.id = :id AND e.state = :startedState")
    int completeStarted(
            @Param("id") String id,
            @Param("startedState") ToolExecutionAuditEvent.State startedState,
            @Param("completedState") ToolExecutionAuditEvent.State completedState,
            @Param("outcomeStatus") ToolExecutionStatus outcomeStatus,
            @Param("resultCode") String resultCode,
            @Param("now") LocalDateTime now);
}
