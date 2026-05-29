package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FACTORY_LU_YIELD_RATE — 卤味出品率 (Sprint 12 Phase C, Steve §3 KPI 1).
 *
 * <p>业务 intent (Steve): "100 斤生料出多少斤成品". Best-guess 实现:
 * {@code SUM(good_quantity) * 100 / SUM(planned_quantity)} over COMPLETED production_batches —
 * 良品产出 vs 计划投产. 真正 "成品/投料原料重量" 需 join material 消耗 (production_plan_id),
 * 当前数据规模 (F006 0 batches) 不值得复杂 join, 后续 Steve 微调.
 *
 * <p>Ratio strategy → null-preserve (无 batch / 分母 0 返 null → UI "—").
 * F006 实测 (test cretas_db 2026-05-29): 0 COMPLETED production_batches → null.
 */
@Slf4j
@Component
public class FactoryLuYieldRateStrategy implements IndicatorComputationStrategy {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String getIndicatorCode() {
        return "FACTORY_LU_YIELD_RATE";
    }

    @Override
    public BigDecimal compute(String factoryId, LocalDate periodStart, LocalDate periodEnd) {
        LocalDate start = periodStart != null ? periodStart : LocalDate.now().withDayOfMonth(1);
        LocalDate end = periodEnd != null ? periodEnd : LocalDate.now();

        Object result = em.createNativeQuery(
                "SELECT SUM(good_quantity) * 100.0 / NULLIF(SUM(planned_quantity), 0) " +
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
        log.debug("FACTORY_LU_YIELD_RATE factory={} period=[{},{}] → {}", factoryId, start, end, value);
        return value;
    }
}
