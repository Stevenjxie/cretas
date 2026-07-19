package com.cretas.aims.repository;

import com.cretas.aims.entity.BatchWorkSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 批次工作会话数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface BatchWorkSessionRepository extends JpaRepository<BatchWorkSession, Long> {

    /**
     * 根据批次ID查找工作会话
     */
    @Query("SELECT bws FROM BatchWorkSession bws " +
           "LEFT JOIN FETCH bws.workSession ws " +
           "LEFT JOIN FETCH ws.user u " +
           "LEFT JOIN FETCH ws.workType wt " +
           "WHERE bws.batchId = :batchId")
    List<BatchWorkSession> findByBatchIdWithDetails(@Param("batchId") Long batchId);

    /**
     * 根据批次ID查找工作会话，包含员工信息
     */
    @Query("SELECT bws FROM BatchWorkSession bws " +
           "LEFT JOIN FETCH bws.employee e " +
           "LEFT JOIN FETCH bws.assigner a " +
           "WHERE bws.batchId = :batchId " +
           "ORDER BY bws.checkInTime DESC")
    List<BatchWorkSession> findByBatchIdWithEmployees(@Param("batchId") Long batchId);

    /**
     * 根据批次ID查找工作会话
     */
    List<BatchWorkSession> findByBatchId(Long batchId);

    /**
     * 根据工作会话ID查找
     */
    List<BatchWorkSession> findByWorkSessionId(Long workSessionId);

    /**
     * 根据批次ID和员工ID查找
     */
    Optional<BatchWorkSession> findByBatchIdAndEmployeeId(Long batchId, Long employeeId);

    /**
     * 根据批次ID和员工ID查找活跃的工作会话
     */
    @Query("SELECT bws FROM BatchWorkSession bws " +
           "WHERE bws.batchId = :batchId AND bws.employeeId = :employeeId " +
           "AND bws.status IN ('assigned', 'working')")
    Optional<BatchWorkSession> findActiveByBatchIdAndEmployeeId(
        @Param("batchId") Long batchId, @Param("employeeId") Long employeeId);

    /**
     * 计算批次的总人工成本
     */
    @Query("SELECT SUM(bws.laborCost) FROM BatchWorkSession bws WHERE bws.batchId = :batchId")
    BigDecimal calculateTotalLaborCostByBatch(@Param("batchId") Long batchId);

    /**
     * 计算批次的总工作时长
     */
    @Query("SELECT SUM(bws.workMinutes) FROM BatchWorkSession bws WHERE bws.batchId = :batchId")
    Integer calculateTotalWorkMinutesByBatch(@Param("batchId") Long batchId);

    // ========== 员工AI分析相关查询 ==========

    /**
     * 按员工和时间范围查询批次工作会话
     */
    @Query("SELECT bws FROM BatchWorkSession bws " +
           "LEFT JOIN FETCH bws.batch b " +
           "WHERE bws.employeeId = :employeeId " +
           "AND bws.createdAt BETWEEN :startTime AND :endTime")
    List<BatchWorkSession> findByEmployeeIdAndTimeRange(
        @Param("employeeId") Long employeeId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime);

    /**
     * 统计员工参与的不同批次数
     */
    @Query("SELECT COUNT(DISTINCT bws.batchId) FROM BatchWorkSession bws " +
           "WHERE bws.employeeId = :employeeId " +
           "AND bws.createdAt BETWEEN :startTime AND :endTime")
    long countDistinctBatchesByEmployeeAndTimeRange(
        @Param("employeeId") Long employeeId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime);

    /**
     * 统计指定工厂内员工参与的不同批次数。
     */
    @Query("SELECT COUNT(DISTINCT bws.batchId) FROM BatchWorkSession bws " +
           "JOIN ProductionBatch pb ON bws.batchId = pb.id " +
           "WHERE pb.factoryId = :factoryId AND bws.employeeId = :employeeId " +
           "AND bws.createdAt BETWEEN :startTime AND :endTime")
    long countDistinctBatchesByFactoryIdAndEmployeeAndTimeRange(
        @Param("factoryId") String factoryId,
        @Param("employeeId") Long employeeId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime);

    /**
     * 统计指定工厂内员工的批次工作会话总数。
     */
    @Query("SELECT COUNT(bws) FROM BatchWorkSession bws " +
           "JOIN ProductionBatch pb ON bws.batchId = pb.id " +
           "WHERE pb.factoryId = :factoryId AND bws.employeeId = :employeeId " +
           "AND bws.createdAt BETWEEN :startTime AND :endTime")
    long countByFactoryIdAndEmployeeAndTimeRange(
        @Param("factoryId") String factoryId,
        @Param("employeeId") Long employeeId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime);

    /**
     * 统计员工批次工作总时长
     */
    @Query("SELECT COALESCE(SUM(bws.workMinutes), 0) FROM BatchWorkSession bws " +
           "WHERE bws.employeeId = :employeeId " +
           "AND bws.createdAt BETWEEN :startTime AND :endTime")
    Integer sumWorkMinutesByEmployeeAndTimeRange(
        @Param("employeeId") Long employeeId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime);

    /**
     * 统计指定工厂内员工的批次工作分钟数。
     */
    @Query("SELECT COALESCE(SUM(bws.workMinutes), 0) FROM BatchWorkSession bws " +
           "JOIN ProductionBatch pb ON bws.batchId = pb.id " +
           "WHERE pb.factoryId = :factoryId AND bws.employeeId = :employeeId " +
           "AND bws.createdAt BETWEEN :startTime AND :endTime")
    Integer sumWorkMinutesByFactoryIdAndEmployeeAndTimeRange(
        @Param("factoryId") String factoryId,
        @Param("employeeId") Long employeeId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime);

    /**
     * Count check-ins within a time range scoped to a factory (P0-1 MEDIUM fix).
     *
     * BatchWorkSession itself has no factoryId column — scope is enforced via
     * its parent ProductionBatch. Spring Data cannot derive this from the
     * method name, so we use an explicit JPQL join.
     */
    @Query("SELECT COUNT(bws) FROM BatchWorkSession bws " +
           "JOIN ProductionBatch pb ON bws.batchId = pb.id " +
           "WHERE pb.factoryId = :factoryId " +
           "AND bws.checkInTime BETWEEN :start AND :end")
    long countByFactoryIdAndCheckInTimeBetween(
        @Param("factoryId") String factoryId,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end);

    /**
     * @deprecated 全局计数, 跨工厂不准确. 使用 {@link #countByFactoryIdAndCheckInTimeBetween}.
     */
    @Deprecated
    long countByCheckInTimeBetween(LocalDateTime start, LocalDateTime end);

    /**
     * 统计员工已完成的批次工作会话数
     */
    @Query("SELECT COUNT(bws) FROM BatchWorkSession bws " +
           "WHERE bws.employeeId = :employeeId " +
           "AND bws.status = 'completed' " +
           "AND bws.createdAt BETWEEN :startTime AND :endTime")
    long countCompletedByEmployeeAndTimeRange(
        @Param("employeeId") Long employeeId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime);

    /**
     * 统计指定工厂内员工已完成的批次工作会话数。
     */
    @Query("SELECT COUNT(bws) FROM BatchWorkSession bws " +
           "JOIN ProductionBatch pb ON bws.batchId = pb.id " +
           "WHERE pb.factoryId = :factoryId AND bws.employeeId = :employeeId " +
           "AND bws.status = 'completed' " +
           "AND bws.createdAt BETWEEN :startTime AND :endTime")
    long countCompletedByFactoryIdAndEmployeeAndTimeRange(
        @Param("factoryId") String factoryId,
        @Param("employeeId") Long employeeId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime);
}
