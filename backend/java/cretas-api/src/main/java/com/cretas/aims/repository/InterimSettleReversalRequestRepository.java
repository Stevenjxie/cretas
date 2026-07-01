package com.cretas.aims.repository;

import com.cretas.aims.entity.InterimSettleReversalRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link InterimSettleReversalRequest} — 撤销小结申请/审批/执行 + 审计 + 盘点告警查询。
 */
@Repository
public interface InterimSettleReversalRequestRepository
        extends JpaRepository<InterimSettleReversalRequest, String> {

    /** factory-scoped 定位申请。 */
    Optional<InterimSettleReversalRequest> findByIdAndFactoryId(String id, String factoryId);

    /** 幂等: 同 (factory, plan, sessionSeq) 是否已有待审批申请。 */
    Optional<InterimSettleReversalRequest> findByFactoryIdAndProductionPlanIdAndSessionSeqAndStatus(
            String factoryId, String productionPlanId, Integer sessionSeq,
            InterimSettleReversalRequest.Status status);

    /** 审批中心 / 审计: 工厂级列表 (可选 status + planId 过滤)。 */
    @Query("SELECT r FROM InterimSettleReversalRequest r WHERE r.factoryId = :factoryId "
            + "AND (CAST(:status AS string) IS NULL OR r.status = :status) "
            + "AND (CAST(:planId AS string) IS NULL OR r.productionPlanId = :planId) "
            + "ORDER BY r.requestedAt DESC")
    Page<InterimSettleReversalRequest> findByFactoryIdWithFilters(
            @Param("factoryId") String factoryId,
            @Param("status") InterimSettleReversalRequest.Status status,
            @Param("planId") String planId,
            Pageable pageable);

    /**
     * 盘点告警: 某工厂在时间窗内 已执行 的撤销申请 (executed_at ∈ [start, end])。
     * 半成品盘点据此在盘点列上标撤销告警点 (READ-ONLY on 撤销审计)。
     */
    List<InterimSettleReversalRequest> findByFactoryIdAndStatusAndExecutedAtBetween(
            String factoryId, InterimSettleReversalRequest.Status status,
            LocalDateTime start, LocalDateTime end);
}
