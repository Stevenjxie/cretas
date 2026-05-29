package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FACTORY_LU_UNIT_COST — 卤味单位成本 (Sprint 12 Phase C, Steve §3 KPI 4).
 *
 * <p>{@code SUM(total_cost) / SUM(actual_quantity)} over COMPLETED production_batches in period.
 * 即 "每单位成品的综合成本" (含 material_cost + labor_cost + equipment_cost + other_cost).
 *
 * <p>Ratio strategy → null-preserve (无 batch / 0 产量返 null → UI "—").
 * F006 实测 (test cretas_db): 0 COMPLETED production_batches → null.
 */
@Slf4j
@Component
public class FactoryLuUnitCostStrategy implements IndicatorComputationStrategy {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String getIndicatorCode() {
        return "FACTORY_LU_UNIT_COST";
    }

    @Override
    public BigDecimal compute(String factoryId, LocalDate periodStart, LocalDate periodEnd) {
        LocalDate start = periodStart != null ? periodStart : LocalDate.now().withDayOfMonth(1);
        LocalDate end = periodEnd != null ? periodEnd : LocalDate.now();

        Object result = em.createNativeQuery(
                "SELECT SUM(total_cost) / NULLIF(SUM(actual_quantity), 0) " +
                "FROM production_batches " +
                "WHERE factory_id = ?1 " +
                "  AND status = 'COMPLETED' " +
                "  AND start_time::date BETWEEN ?2 AND ?3 " +
                "  AND deleted_at IS NULL")
                .setParameter(1, factoryId)
                .setParameter(2, start)
                .setParameter(3, end)
                .getSingleResult();

        BigDecimal value = result instanceof BigDecimal ? (BigDecimal) result : null;
        log.debug("FACTORY_LU_UNIT_COST factory={} period=[{},{}] → {}", factoryId, start, end, value);
        return value;
    }
}
