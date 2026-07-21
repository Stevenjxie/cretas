package com.cretas.aims.repository.workflow;

import com.cretas.aims.entity.workflow.ApprovalHistory;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Approval history repository — Phase 1 B.3.
 *
 * <p>{@link ApprovalHistory} 不继承 BaseEntity (append-only, 无 deleted_at), 所有查询返
 * 完整记录.
 *
 * @since 2026-05-18
 */
@Repository
public interface ApprovalHistoryRepository extends JpaRepository<ApprovalHistory, Long> {

    /**
     * 时间线 UI — 单 instance 按 createdAt 升序.
     *
     * <p>覆盖 index: {@code idx_approval_history_instance_time (factory_id, instance_id, created_at)}.
     */
    List<ApprovalHistory> findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(
            String factoryId, String instanceId);

    /**
     * Phase D 报表 — factory 维度按 action + 时间区间分页.
     *
     * <p>覆盖 index: {@code idx_approval_history_factory_action_time}.
     */
    Page<ApprovalHistory> findByFactoryIdAndActionAndCreatedAtBetween(
            String factoryId, HistoryAction action,
            LocalDateTime from, LocalDateTime to, Pageable pageable);

    /**
     * OA copied-to-me read model source. Notify nodes are append-only
     * AUTO_TRANSITION rows whose notes start with "notify".
     */
    List<ApprovalHistory> findByFactoryIdAndActionOrderByCreatedAtDesc(
            String factoryId, HistoryAction action);
}
