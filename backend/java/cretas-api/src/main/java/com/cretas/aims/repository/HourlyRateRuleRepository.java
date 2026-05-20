package com.cretas.aims.repository;

import com.cretas.aims.entity.HourlyRateRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Sprint 6 Track W4-B: HourlyRateRule 仓储.
 *
 * @since 2026-05-19
 */
@Repository
public interface HourlyRateRuleRepository extends JpaRepository<HourlyRateRule, Long> {

    /**
     * 列员工所有 rule (历史 + 当前).
     */
    List<HourlyRateRule> findByFactoryIdAndEmployeeIdOrderByEffectiveFromDesc(
            String factoryId, Long employeeId);

    /**
     * 列工厂所有 rule (admin UI).
     */
    List<HourlyRateRule> findByFactoryIdOrderByEmployeeIdAscEffectiveFromDesc(String factoryId);

    /**
     * 查找员工指定日期 effective 的 rule (取 effective_from ≤ date AND (effective_to IS NULL OR effective_to ≥ date) 中最新一条).
     */
    @Query("SELECT r FROM HourlyRateRule r WHERE r.factoryId = :factoryId " +
           "AND r.employeeId = :employeeId AND r.isActive = TRUE " +
           "AND r.effectiveFrom <= :date " +
           "AND (r.effectiveTo IS NULL OR r.effectiveTo >= :date) " +
           "ORDER BY r.effectiveFrom DESC")
    List<HourlyRateRule> findEffectiveRules(
            @Param("factoryId") String factoryId,
            @Param("employeeId") Long employeeId,
            @Param("date") LocalDate date);

    /**
     * 取员工指定日期 effective 的 rule (第一条, 兜底 Optional).
     */
    default Optional<HourlyRateRule> findEffectiveOn(String factoryId, Long employeeId, LocalDate date) {
        List<HourlyRateRule> rules = findEffectiveRules(factoryId, employeeId, date);
        return rules.isEmpty() ? Optional.empty() : Optional.of(rules.get(0));
    }
}
