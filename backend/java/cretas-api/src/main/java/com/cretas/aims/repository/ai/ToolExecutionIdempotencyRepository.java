package com.cretas.aims.repository.ai;

import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ToolExecutionIdempotencyRepository
        extends JpaRepository<ToolExecutionIdempotencyRecord, String> {

    Optional<ToolExecutionIdempotencyRecord>
    findByTenantIdAndPrincipalTypeAndPrincipalIdAndToolNameAndDescriptorVersionAndIdempotencyKeyHash(
            String tenantId,
            PrincipalType principalType,
            String principalId,
            String toolName,
            String descriptorVersion,
            String idempotencyKeyHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ToolExecutionIdempotencyRecord r SET r.state = :nextState, "
            + "r.outcomeStatus = :outcomeStatus, r.resultCode = :resultCode, "
            + "r.updatedAt = :now, r.completedAt = :now "
            + "WHERE r.id = :id AND r.state = :expectedState")
    int completeFromState(
            @Param("id") String id,
            @Param("expectedState") ToolExecutionIdempotencyRecord.State expectedState,
            @Param("nextState") ToolExecutionIdempotencyRecord.State nextState,
            @Param("outcomeStatus") ToolExecutionStatus outcomeStatus,
            @Param("resultCode") String resultCode,
            @Param("now") LocalDateTime now);
}
