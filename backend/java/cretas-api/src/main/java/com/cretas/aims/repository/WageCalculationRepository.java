package com.cretas.aims.repository;

import com.cretas.aims.entity.WageCalculation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Sprint 6 Track W4-B: WageCalculation 仓储.
 *
 * @since 2026-05-19
 */
@Repository
public interface WageCalculationRepository extends JpaRepository<WageCalculation, Long> {

    /**
     * 查找指定员工 + 月份的 calculation (idempotent upsert 用).
     */
    Optional<WageCalculation> findByFactoryIdAndEmployeeIdAndPeriodMonth(
            String factoryId, Long employeeId, LocalDate periodMonth);

    /**
     * 列工厂指定月份所有 calculation (月底批量 scheduler 用).
     */
    List<WageCalculation> findByFactoryIdAndPeriodMonth(String factoryId, LocalDate periodMonth);

    /**
     * 列员工历史 calculations (近 N 月).
     */
    List<WageCalculation> findByFactoryIdAndEmployeeIdOrderByPeriodMonthDesc(
            String factoryId, Long employeeId);

    /**
     * 统计工厂指定月份 calculation 数量 (admin UI / 报表).
     */
    long countByFactoryIdAndPeriodMonth(String factoryId, LocalDate periodMonth);
}
