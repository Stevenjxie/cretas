package com.cretas.aims.repository;

import com.cretas.aims.entity.ReworkRecord;
import com.cretas.aims.entity.enums.ReworkStatus;
import com.cretas.aims.entity.enums.ReworkType;
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
 * 返工记录Repository
 * 提供返工记录的数据访问方法
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-11-05
 */
@Repository
public interface ReworkRecordRepository extends JpaRepository<ReworkRecord, Long> {

    // ===================================================================
    // 基础查询方法
    // ===================================================================

    /**
     * 根据工厂ID查询返工记录
     */
    Page<ReworkRecord> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 根据工厂ID和状态查询返工记录
     */
    Page<ReworkRecord> findByFactoryIdAndStatus(String factoryId, ReworkStatus status, Pageable pageable);

    /**
     * 根据工厂ID和返工类型查询
     */
    Page<ReworkRecord> findByFactoryIdAndReworkType(String factoryId, ReworkType reworkType, Pageable pageable);

    /**
     * 根据质检记录ID查询返工记录
     */
    List<ReworkRecord> findByQualityInspectionId(String qualityInspectionId);

    /**
     * 根据生产批次ID查询返工记录
     */
    List<ReworkRecord> findByProductionBatchId(String productionBatchId);

    /**
     * 根据原材料批次ID查询返工记录
     */
    List<ReworkRecord> findByMaterialBatchId(String materialBatchId);

    /**
     * 根据负责人ID查询返工记录
     */
    Page<ReworkRecord> findBySupervisorId(Long supervisorId, Pageable pageable);

    // ===================================================================
    // 状态查询方法
    // ===================================================================

    /**
     * 查询进行中的返工记录（PENDING + IN_PROGRESS）
     */
    @Query("SELECT r FROM ReworkRecord r WHERE r.factoryId = :factoryId " +
           "AND r.status IN ('PENDING', 'IN_PROGRESS') " +
           "ORDER BY r.startTime DESC")
    List<ReworkRecord> findActiveReworks(@Param("factoryId") String factoryId);

    /**
     * 查询待处理的返工记录
     */
    List<ReworkRecord> findByFactoryIdAndStatus(String factoryId, ReworkStatus status);

    /**
     * 统计工厂的返工记录数量（按状态）
     */
    Long countByFactoryIdAndStatus(String factoryId, ReworkStatus status);

    // ===================================================================
    // 时间范围查询
    // ===================================================================

    /**
     * 查询指定时间范围内的返工记录
     */
    @Query("SELECT r FROM ReworkRecord r WHERE r.factoryId = :factoryId " +
           "AND r.startTime BETWEEN :startDate AND :endDate " +
           "ORDER BY r.startTime DESC")
    List<ReworkRecord> findByDateRange(
        @Param("factoryId") String factoryId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    /**
     * 分页查询指定时间范围内的返工记录
     */
    @Query("SELECT r FROM ReworkRecord r WHERE r.factoryId = :factoryId " +
           "AND r.startTime BETWEEN :startDate AND :endDate")
    Page<ReworkRecord> findByDateRange(
        @Param("factoryId") String factoryId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate,
        Pageable pageable
    );

    // ===================================================================
    // 统计查询方法
    // ===================================================================

    /**
     * 统计工厂的总返工次数
     */
    Long countByFactoryId(String factoryId);

    /**
     * 统计指定 startTime 时间范围内的返工次数 (KPI 返工率分子).
     */
    long countByFactoryIdAndStartTimeBetween(String factoryId, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 统计特定类型的返工次数
     */
    Long countByFactoryIdAndReworkType(String factoryId, ReworkType reworkType);

    /**
     * 查询返工成功率统计（使用原生SQL）
     */
    @Query(value = "SELECT AVG(CASE WHEN rework_quantity > 0 " +
           "THEN (success_quantity / rework_quantity) * 100 " +
           "ELSE 0 END) " +
           "FROM rework_records WHERE factory_id = :factoryId AND status = 'COMPLETED'",
           nativeQuery = true)
    Double calculateAverageSuccessRate(@Param("factoryId") String factoryId);

    /**
     * 统计总返工成本
     */
    @Query("SELECT COALESCE(SUM(r.reworkCost), 0) FROM ReworkRecord r " +
           "WHERE r.factoryId = :factoryId " +
           "AND r.startTime BETWEEN :startDate AND :endDate")
    Double calculateTotalReworkCost(
        @Param("factoryId") String factoryId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    /**
     * 统计返工数量统计
     */
    @Query("SELECT COALESCE(SUM(r.reworkQuantity), 0) FROM ReworkRecord r " +
           "WHERE r.factoryId = :factoryId " +
           "AND r.startTime BETWEEN :startDate AND :endDate")
    Double calculateTotalReworkQuantity(
        @Param("factoryId") String factoryId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    // ===================================================================
    // 批量操作
    // ===================================================================

    /**
     * 批量更新返工状态
     */
    @Query("UPDATE ReworkRecord r SET r.status = :newStatus " +
           "WHERE r.id IN :ids AND r.factoryId = :factoryId")
    void batchUpdateStatus(
        @Param("ids") List<Long> ids,
        @Param("newStatus") ReworkStatus newStatus,
        @Param("factoryId") String factoryId
    );

    /**
     * 查找超时未完成的返工记录（超过指定小时数）
     */
    @Query("SELECT r FROM ReworkRecord r WHERE r.status = 'IN_PROGRESS' " +
           "AND r.startTime < :thresholdTime")
    List<ReworkRecord> findOverdueReworks(@Param("thresholdTime") LocalDateTime thresholdTime);

    // ===================================================================
    // 关联查询
    // ===================================================================

    /**
     * 查询批次的所有返工记录（包括成功和失败）
     */
    @Query("SELECT r FROM ReworkRecord r WHERE " +
           "(r.productionBatchId = :batchId OR r.materialBatchId = :batchId) " +
           "ORDER BY r.startTime DESC")
    List<ReworkRecord> findAllByBatchId(@Param("batchId") String batchId);

    /**
     * 检查批次是否有进行中的返工
     */
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM ReworkRecord r " +
           "WHERE (r.productionBatchId = :batchId OR r.materialBatchId = :batchId) " +
           "AND r.status IN ('PENDING', 'IN_PROGRESS')")
    boolean hasActiveRework(@Param("batchId") String batchId);
}
