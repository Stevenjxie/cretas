package com.cretas.aims.repository;

import com.cretas.aims.entity.ReportReversalLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 整单撤回审批日志 Repository — SP2。
 *
 * <p>幂等键: UNIQUE(batch_id, reversal_scope)。{@link #findByBatchIdAndReversalScope} 用于幂等检查。
 */
@Repository
public interface ReportReversalLogRepository extends JpaRepository<ReportReversalLog, Long> {

    /**
     * 幂等检查: 给定批次+范围是否已存在未软删除的撤回记录。
     * 主要用于: DONE → 直接返 already-reverted; PENDING → 返 409 已有待审批撤回。
     */
    Optional<ReportReversalLog> findByBatchIdAndReversalScopeAndDeletedAtIsNull(
            Long batchId,
            ReportReversalLog.ReversalScope reversalScope);

    /**
     * 列出工厂所有待审批的撤回申请 (主管审批页面)。
     */
    List<ReportReversalLog> findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId,
            ReportReversalLog.ReversalStatus status);

    /**
     * 列出工厂所有撤回记录 (审计历史页面), 按创建时间倒序。
     */
    List<ReportReversalLog> findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(String factoryId);

    /**
     * 检查给定批次是否已有 PENDING 状态的撤回申请 (守卫: 禁止并发申请)。
     */
    boolean existsByBatchIdAndStatusAndDeletedAtIsNull(
            Long batchId,
            ReportReversalLog.ReversalStatus status);

    /**
     * 查询给定批次的所有撤回记录 (batch detail 关联展示)。
     */
    @Query("SELECT r FROM ReportReversalLog r WHERE r.batchId = :batchId AND r.deletedAt IS NULL "
            + "ORDER BY r.createdAt DESC")
    List<ReportReversalLog> findByBatchIdAndDeletedAtIsNull(@Param("batchId") Long batchId);
}
