package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.SsopExecutionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository — Sprint 9 P2.E {@link SsopExecutionRecord} (清洁执行记录).
 *
 * <p>核心查询:
 * <ul>
 *   <li>{@link #findByFactoryIdAndExecutionDate} — 今日全部任务 (任意 status)</li>
 *   <li>{@link #findByFactoryIdAndExecutionDateAndStatusIn} — gate 用 (查未完成)</li>
 *   <li>{@link #findByFactoryIdAndExecutionDateBetween} — 月度报表 audit</li>
 *   <li>{@link #existsByProcedureIdAndExecutionDate} — Scheduler 幂等 check</li>
 * </ul>
 */
@Repository
public interface SsopExecutionRecordRepository extends JpaRepository<SsopExecutionRecord, Long> {

    /** 今日所有任务 (workdesk widget + ssop_execution_today tool 用). */
    List<SsopExecutionRecord> findByFactoryIdAndExecutionDateOrderByShiftAscIdAsc(
            String factoryId, LocalDate executionDate);

    /** Production gate: 查指定日期且 status 在 list 中的所有 records. */
    List<SsopExecutionRecord> findByFactoryIdAndExecutionDateAndStatusIn(
            String factoryId, LocalDate executionDate, List<String> statuses);

    /** 月度 audit report 用 — 工厂内日期区间所有 records. */
    List<SsopExecutionRecord> findByFactoryIdAndExecutionDateBetween(
            String factoryId, LocalDate startDate, LocalDate endDate);

    /** Scheduler 幂等 — 同 procedureId 同日期已存在 record 则不重创. */
    boolean existsByProcedureIdAndExecutionDate(Long procedureId, LocalDate executionDate);

    /** 月度 audit: 按 status 计数 (用于 completion rate 计算). */
    @Query("SELECT r.status, COUNT(r) FROM SsopExecutionRecord r " +
            "WHERE r.factoryId = :factoryId " +
            "AND r.executionDate BETWEEN :startDate AND :endDate " +
            "GROUP BY r.status")
    List<Object[]> countByStatusInRange(@Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
