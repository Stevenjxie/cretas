package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FACTORY_QUALITY_REJECT_RATE — 质检不合格率 (Sprint 12 Phase B step 5).
 *
 * <p>Ratio strategy: {@code SUM(fail_count) * 100 / SUM(pass_count + fail_count)} from
 * {@code quality_inspections} table in caller period (default MTD).
 *
 * <p><b>Phase B step 5 null-preserve convention</b>: 无 inspections / 分母为 0 → 返 null
 * (per interface javadoc + IndicatorComputationStrategy contract update). UI 渲 "—" 而非
 * 误导的 "0% reject = perfect". 当 F006 真有 quality_inspections 数据后, strategy
 * 自动返真值, 不需要代码改动.
 *
 * <p>实测 F006 (test cretas_db 2026-05-29): 0 inspections → 返 null → UI "—".
 */
@Slf4j
@Component
public class FactoryQualityRejectRateStrategy implements IndicatorComputationStrategy {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String getIndicatorCode() {
        return "FACTORY_QUALITY_REJECT_RATE";
    }

    @Override
    public BigDecimal compute(String factoryId, LocalDate periodStart, LocalDate periodEnd) {
        LocalDate start = periodStart != null ? periodStart : LocalDate.now().withDayOfMonth(1);
        LocalDate end = periodEnd != null ? periodEnd : LocalDate.now();

        // SQL: SUM(fail) / NULLIF(SUM(pass+fail), 0) — 无样本 / 分母 0 自然返 NULL
        // (Postgres SUM over empty set returns NULL, not 0)
        Object result = em.createNativeQuery(
                "SELECT SUM(fail_count) * 100.0 / NULLIF(SUM(pass_count + fail_count), 0) " +
                "FROM quality_inspections " +
                "WHERE factory_id = ?1 " +
                "  AND inspection_date BETWEEN ?2 AND ?3 " +
                "  AND deleted_at IS NULL")
                .setParameter(1, factoryId)
                .setParameter(2, start)
                .setParameter(3, end)
                .getSingleResult();

        BigDecimal value = result instanceof BigDecimal ? (BigDecimal) result : null;
        log.debug("FACTORY_QUALITY_REJECT_RATE factory={} period=[{},{}] → {}",
                factoryId, start, end, value);
        return value;
    }
}
