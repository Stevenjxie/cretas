package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FACTORY_LU_DAILY_OUTPUT — 卤味日均产量 (Sprint 12 Phase C, Steve §3 KPI 5).
 *
 * <p>{@code SUM(actual_quantity) / COUNT(DISTINCT 生产日)} over COMPLETED production_batches —
 * 即 "有生产的日子里平均每天产出". 用 DISTINCT 生产日做分母 (不是日历天) 避免没生产的日子
 * 拉低均值.
 *
 * <p>Average strategy → null-preserve (无 batch → 分母 0 → null → UI "—").
 * F006 实测 (test cretas_db): 0 COMPLETED production_batches → null.
 */
@Slf4j
@Component
public class FactoryLuDailyOutputStrategy implements IndicatorComputationStrategy {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String getIndicatorCode() {
        return "FACTORY_LU_DAILY_OUTPUT";
    }

    @Override
    public BigDecimal compute(String factoryId, LocalDate periodStart, LocalDate periodEnd) {
        LocalDate start = periodStart != null ? periodStart : LocalDate.now().withDayOfMonth(1);
        LocalDate end = periodEnd != null ? periodEnd : LocalDate.now();

        Object result = em.createNativeQuery(
                "SELECT SUM(actual_quantity) / NULLIF(COUNT(DISTINCT start_time::date), 0) " +
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
        log.debug("FACTORY_LU_DAILY_OUTPUT factory={} period=[{},{}] → {}", factoryId, start, end, value);
        return value;
    }
}
