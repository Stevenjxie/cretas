package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FACTORY_LU_VACUUM_PACK_PASS_RATE — 卤味真空包装合格率 (Sprint 12 Phase C, Steve §3 KPI 3).
 *
 * <p>{@code SUM(pass_count) * 100 / SUM(pass_count + fail_count)} over quality_inspections
 * WHERE {@code inspection_mode = 'VACUUM_PACKING'} (best-guess enum). 只统计真空包装质检,
 * 不混入其他质检 (区别于 FACTORY_QUALITY_REJECT_RATE 全局质检).
 *
 * <p>Ratio strategy → null-preserve. F006 quality_inspections 当前 0 行 + 无 VACUUM_PACKING
 * mode → 永远 null → UI "—", 等 F006 录入真空包装质检并 tag inspection_mode='VACUUM_PACKING'
 * 后自动填. Steve 后续确认 inspection_mode 真实枚举值 (best-guess 'VACUUM_PACKING' 对不上则调).
 *
 * <p>诚实: 宁可返 "—" (无真空包装质检数据) 也不混入全局质检冒充真空包装合格率 (Rule 21).
 */
@Slf4j
@Component
public class FactoryLuVacuumPackPassRateStrategy implements IndicatorComputationStrategy {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String getIndicatorCode() {
        return "FACTORY_LU_VACUUM_PACK_PASS_RATE";
    }

    @Override
    public BigDecimal compute(String factoryId, LocalDate periodStart, LocalDate periodEnd) {
        LocalDate start = periodStart != null ? periodStart : LocalDate.now().withDayOfMonth(1);
        LocalDate end = periodEnd != null ? periodEnd : LocalDate.now();

        Object result = em.createNativeQuery(
                "SELECT SUM(pass_count) * 100.0 / NULLIF(SUM(pass_count + fail_count), 0) " +
                "FROM quality_inspections " +
                "WHERE factory_id = ?1 " +
                "  AND inspection_mode = 'VACUUM_PACKING' " +
                "  AND inspection_date BETWEEN ?2 AND ?3 " +
                "  AND deleted_at IS NULL")
                .setParameter(1, factoryId)
                .setParameter(2, start)
                .setParameter(3, end)
                .getSingleResult();

        BigDecimal value = result instanceof BigDecimal ? (BigDecimal) result : null;
        log.debug("FACTORY_LU_VACUUM_PACK_PASS_RATE factory={} period=[{},{}] → {}",
                factoryId, start, end, value);
        return value;
    }
}
