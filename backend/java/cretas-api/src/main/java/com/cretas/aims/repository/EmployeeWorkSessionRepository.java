package com.cretas.aims.repository;

import com.cretas.aims.entity.EmployeeWorkSession;
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
 * 员工工作会话数据访问层
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface EmployeeWorkSessionRepository extends JpaRepository<EmployeeWorkSession, Long> {

    /**
     * 根据工厂ID分页查询工作会话
     */
    Page<EmployeeWorkSession> findByFactoryIdOrderByStartTimeDesc(String factoryId, Pageable pageable);

    /**
     * 根据工厂ID和状态查询
     */
    Page<EmployeeWorkSession> findByFactoryIdAndStatusOrderByStartTimeDesc(
            String factoryId, String status, Pageable pageable);

    /**
     * 根据用户ID查询工作会话
     */
    List<EmployeeWorkSession> findByUserIdOrderByStartTimeDesc(Long userId);

    /**
     * 根据用户ID和状态查询
     */
    List<EmployeeWorkSession> findByUserIdAndStatusOrderByStartTimeDesc(Long userId, String status);

    /**
     * 查询用户当前活跃的工作会话
     */
    Optional<EmployeeWorkSession> findByUserIdAndStatus(Long userId, String status);

    /**
     * 查询工厂指定用户的活跃会话
     */
    Optional<EmployeeWorkSession> findByFactoryIdAndUserIdAndStatus(String factoryId, Long userId, String status);

    /**
     * 根据时间范围查询
     */
    @Query("SELECT e FROM EmployeeWorkSession e WHERE e.factoryId = :factoryId " +
           "AND e.startTime BETWEEN :startTime AND :endTime " +
           "ORDER BY e.startTime DESC")
    List<EmployeeWorkSession> findByFactoryIdAndTimeRange(
            @Param("factoryId") String factoryId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 根据工作类型查询
     */
    List<EmployeeWorkSession> findByFactoryIdAndWorkTypeIdOrderByStartTimeDesc(String factoryId, Integer workTypeId);

    /**
     * 统计工厂活跃会话数量
     */
    long countByFactoryIdAndStatus(String factoryId, String status);

    /**
     * 统计用户在时间段内的工作会话数
     */
    @Query("SELECT COUNT(e) FROM EmployeeWorkSession e WHERE e.userId = :userId " +
           "AND e.startTime BETWEEN :startTime AND :endTime")
    long countByUserIdAndTimeRange(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 统计指定工厂内用户在时间段内的工作会话数。
     */
    @Query("SELECT COUNT(e) FROM EmployeeWorkSession e WHERE e.factoryId = :factoryId " +
           "AND e.userId = :userId AND e.startTime BETWEEN :startTime AND :endTime")
    long countByFactoryIdAndUserIdAndTimeRange(
            @Param("factoryId") String factoryId,
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 计算用户在时间段内的总工作分钟数
     */
    @Query("SELECT COALESCE(SUM(e.actualWorkMinutes), 0) FROM EmployeeWorkSession e " +
           "WHERE e.userId = :userId AND e.status = 'completed' " +
           "AND e.startTime BETWEEN :startTime AND :endTime")
    Integer sumActualWorkMinutesByUserIdAndTimeRange(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 计算指定工厂内用户在时间段内已完成会话的实际工作分钟数。
     */
    @Query("SELECT COALESCE(SUM(e.actualWorkMinutes), 0) FROM EmployeeWorkSession e " +
           "WHERE e.factoryId = :factoryId AND e.userId = :userId AND e.status = 'completed' " +
           "AND e.startTime BETWEEN :startTime AND :endTime")
    Integer sumActualWorkMinutesByFactoryIdAndUserIdAndTimeRange(
            @Param("factoryId") String factoryId,
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 计算工厂在时间段内的总人工成本
     */
    @Query("SELECT COALESCE(SUM(e.laborCost), 0) FROM EmployeeWorkSession e " +
           "WHERE e.factoryId = :factoryId AND e.status = 'completed' " +
           "AND e.startTime BETWEEN :startTime AND :endTime")
    java.math.BigDecimal sumLaborCostByFactoryIdAndTimeRange(
            @Param("factoryId") String factoryId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 按工作类型统计工时
     */
    @Query("SELECT e.workTypeId, SUM(e.actualWorkMinutes) FROM EmployeeWorkSession e " +
           "WHERE e.factoryId = :factoryId AND e.status = 'completed' " +
           "AND e.startTime BETWEEN :startTime AND :endTime " +
           "GROUP BY e.workTypeId")
    List<Object[]> sumWorkMinutesByWorkType(
            @Param("factoryId") String factoryId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 按用户统计工时
     */
    @Query("SELECT e.userId, SUM(e.actualWorkMinutes), SUM(e.laborCost) FROM EmployeeWorkSession e " +
           "WHERE e.factoryId = :factoryId AND e.status = 'completed' " +
           "AND e.startTime BETWEEN :startTime AND :endTime " +
           "GROUP BY e.userId")
    List<Object[]> sumWorkMinutesByUser(
            @Param("factoryId") String factoryId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);
}
