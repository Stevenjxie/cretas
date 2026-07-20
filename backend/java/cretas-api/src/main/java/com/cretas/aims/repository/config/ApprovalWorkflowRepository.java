package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

/**
 * Graph-native 审批工作流 Repository.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-16
 */
@Repository
public interface ApprovalWorkflowRepository extends JpaRepository<ApprovalWorkflow, String> {

    /** 工厂所有 workflow (含 draft/published/archived, 不含软删除). */
    List<ApprovalWorkflow> findByFactoryIdOrderByDecisionTypeAscPriorityDesc(String factoryId);

    /** 工厂某 decisionType 的所有 workflow. */
    List<ApprovalWorkflow> findByFactoryIdAndDecisionTypeOrderByPriorityDesc(
            String factoryId, DecisionType decisionType);

    /**
     * Executor lookup: 找当前 active workflow (published + enabled + 最高 priority).
     * 同 priority 时取最新 version.
     */
    @Query("SELECT w FROM ApprovalWorkflow w WHERE w.factoryId = :factoryId " +
           "AND w.decisionType = :decisionType " +
           "AND w.publishStatus = 'published' " +
           "AND w.enabled = true " +
           "ORDER BY w.priority DESC, w.version DESC")
    List<ApprovalWorkflow> findActiveByDecisionType(
            @Param("factoryId") String factoryId,
            @Param("decisionType") DecisionType decisionType);

    /** 工厂+decisionType+name 唯一性校验 (创建前 dup check). */
    boolean existsByFactoryIdAndDecisionTypeAndName(
            String factoryId, DecisionType decisionType, String name);

    /** Exact definition lock used to bind a validated snapshot to instance creation. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM ApprovalWorkflow w WHERE w.factoryId = :factoryId AND w.id = :id")
    Optional<ApprovalWorkflow> findByFactoryIdAndIdForUpdate(
            @Param("factoryId") String factoryId,
            @Param("id") String id);

    /** 统计 — 每 decisionType 多少 workflow (含 draft, 不含软删除). */
    @Query("SELECT w.decisionType, COUNT(w) FROM ApprovalWorkflow w " +
           "WHERE w.factoryId = :factoryId " +
           "GROUP BY w.decisionType")
    List<Object[]> countByFactoryIdGroupByDecisionType(@Param("factoryId") String factoryId);
}
