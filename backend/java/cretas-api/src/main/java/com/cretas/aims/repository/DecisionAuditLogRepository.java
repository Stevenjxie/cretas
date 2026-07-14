package com.cretas.aims.repository;

import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.entity.DecisionAuditLog.DecisionType;
import com.cretas.aims.entity.DecisionAuditLog.ApprovalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 决策审计日志仓库
 */
@Repository
public interface DecisionAuditLogRepository extends JpaRepository<DecisionAuditLog, String> {

    // 按工厂ID查询
    Page<DecisionAuditLog> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    // 按实体查询
    List<DecisionAuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            String entityType, String entityId);

    // 按决策类型查询
    Page<DecisionAuditLog> findByFactoryIdAndDecisionTypeOrderByCreatedAtDesc(
            String factoryId, DecisionType decisionType, Pageable pageable);

    // 按审批状态查询
    Page<DecisionAuditLog> findByFactoryIdAndApprovalStatusOrderByCreatedAtDesc(
            String factoryId, ApprovalStatus approvalStatus, Pageable pageable);

    // 待审批列表
    @Query("SELECT d FROM DecisionAuditLog d WHERE d.factoryId = :factoryId " +
           "AND d.requiresApproval = true AND d.approvalStatus = 'PENDING' " +
           "ORDER BY d.createdAt DESC")
    Page<DecisionAuditLog> findPendingApprovals(
            @Param("factoryId") String factoryId, Pageable pageable);

    // 按时间范围查询
    @Query("SELECT d FROM DecisionAuditLog d WHERE d.factoryId = :factoryId " +
           "AND d.createdAt BETWEEN :startTime AND :endTime " +
           "ORDER BY d.createdAt DESC")
    Page<DecisionAuditLog> findByFactoryIdAndTimeRange(
            @Param("factoryId") String factoryId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);

    // 按执行者查询
    List<DecisionAuditLog> findByFactoryIdAndExecutorIdOrderByCreatedAtDesc(
            String factoryId, Long executorId);

    // 统计查询
    @Query("SELECT d.decisionType, COUNT(d) FROM DecisionAuditLog d " +
           "WHERE d.factoryId = :factoryId " +
           "AND d.createdAt >= :startTime " +
           "GROUP BY d.decisionType")
    List<Object[]> countByDecisionType(
            @Param("factoryId") String factoryId,
            @Param("startTime") LocalDateTime startTime);

    // 按实体类型统计
    @Query("SELECT d.entityType, COUNT(d) FROM DecisionAuditLog d " +
           "WHERE d.factoryId = :factoryId " +
           "AND d.createdAt >= :startTime " +
           "GROUP BY d.entityType")
    List<Object[]> countByEntityType(
            @Param("factoryId") String factoryId,
            @Param("startTime") LocalDateTime startTime);

    // 可回放的决策
    List<DecisionAuditLog> findByEntityTypeAndEntityIdAndIsReplayableTrueOrderByCreatedAtAsc(
            String entityType, String entityId);

    // ========== 特批放行查询 ==========

    /**
     * 查询质检特批待审批列表
     */
    @Query("SELECT d FROM DecisionAuditLog d WHERE d.factoryId = :factoryId " +
           "AND d.entityType = 'QualityInspection' " +
           "AND d.requiresApproval = true AND d.approvalStatus = 'PENDING' " +
           "ORDER BY d.createdAt DESC")
    List<DecisionAuditLog> findQualityPendingApprovals(@Param("factoryId") String factoryId);

    /**
     * 查询质检特批待审批列表 (可选按处置动作过滤)
     * 新增重载: 保留上面无过滤版本给既有调用方 (controller 默认路径 + SpecialApprovalServiceImpl),
     * 本重载供 /quality-disposition/pending 列表页新增的"处置动作"筛选下拉使用。
     * PG-safe: CAST(:decisionMade AS string) 让 Hibernate 在参数为 null 时也能推断类型 (database-entity-sync.md)。
     */
    @Query("SELECT d FROM DecisionAuditLog d WHERE d.factoryId = :factoryId " +
           "AND d.entityType = 'QualityInspection' " +
           "AND d.requiresApproval = true AND d.approvalStatus = 'PENDING' " +
           "AND (CAST(:decisionMade AS string) IS NULL OR d.decisionMade = :decisionMade) " +
           "ORDER BY d.createdAt DESC")
    List<DecisionAuditLog> findQualityPendingApprovals(
            @Param("factoryId") String factoryId,
            @Param("decisionMade") String decisionMade);

    /**
     * 查询我的特批申请
     */
    @Query("SELECT d FROM DecisionAuditLog d WHERE d.factoryId = :factoryId " +
           "AND d.entityType = 'QualityInspection' " +
           "AND d.executorId = :requesterId " +
           "AND d.requiresApproval = true " +
           "ORDER BY d.createdAt DESC")
    List<DecisionAuditLog> findMyQualityApprovalRequests(
            @Param("factoryId") String factoryId,
            @Param("requesterId") Long requesterId);

    /**
     * 查询我审批过的特批申请
     */
    @Query("SELECT d FROM DecisionAuditLog d WHERE d.factoryId = :factoryId " +
           "AND d.entityType = 'QualityInspection' " +
           "AND d.approverId = :approverId " +
           "AND d.approvalStatus IN ('APPROVED', 'REJECTED') " +
           "ORDER BY d.approvedAt DESC")
    List<DecisionAuditLog> findMyQualityApprovalDecisions(
            @Param("factoryId") String factoryId,
            @Param("approverId") Long approverId);

    /**
     * 根据工厂ID、实体类型和实体ID查询审计日志
     * 用于质检处置历史查询
     */
    List<DecisionAuditLog> findByFactoryIdAndEntityTypeAndEntityId(
            String factoryId, String entityType, String entityId);

    /**
     * 批量查询：根据工厂ID、实体类型和多个实体ID查询审计日志
     * 用于避免 N+1 查询问题
     */
    @Query("SELECT d FROM DecisionAuditLog d WHERE d.factoryId = :factoryId " +
           "AND d.entityType = :entityType AND d.entityId IN :entityIds " +
           "ORDER BY d.createdAt DESC")
    List<DecisionAuditLog> findByFactoryIdAndEntityTypeAndEntityIdIn(
            @Param("factoryId") String factoryId,
            @Param("entityType") String entityType,
            @Param("entityIds") java.util.Collection<String> entityIds);
}
