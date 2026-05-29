package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FACTORY_LU_MATERIAL_TURNOVER_DAYS — 卤味原料周转天数 (Sprint 12 Phase C, Steve §3 KPI 6).
 *
 * <p>业务 intent: "库存量 / 日均消耗" = 当前库存够用多少天. Best-guess 实现:
 * {@code SUM(receipt_quantity) * 30 / SUM(used_quantity)} —— 当前在库量 / (月消耗量/30) =
 * 剩余可用天数. ({@code receipt_quantity} 是当前剩余, per MaterialBatch entity quirk;
 * {@code used_quantity} 是累计消耗).
 *
 * <p>Ratio strategy → null-preserve (无消耗记录 → 分母 0 → null → UI "—"). 没消耗数据时
 * "周转天数" 无意义 (∞), 返 null 比返巨大数字诚实.
 *
 * <p>F006 实测 (test cretas_db): 8 ACTIVE batches 但 used_quantity 多为 0 → 大概率 null.
 */
@Slf4j
@Component
public class FactoryLuMaterialTurnoverDaysStrategy implements IndicatorComputationStrategy {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String getIndicatorCode() {
        return "FACTORY_LU_MATERIAL_TURNOVER_DAYS";
    }

    @Override
    public BigDecimal compute(String factoryId, LocalDate periodStart, LocalDate periodEnd) {
        // periodStart/End 忽略 — 周转天数是 instant snapshot (当前库存 / 近期日均消耗)
        Object result = em.createNativeQuery(
                "SELECT SUM(receipt_quantity) * 30.0 / NULLIF(SUM(used_quantity), 0) " +
                "FROM material_batches " +
                "WHERE factory_id = ?1 " +
                "  AND status = 'ACTIVE' " +
                "  AND deleted_at IS NULL")
                .setParameter(1, factoryId)
                .getSingleResult();

        BigDecimal value = result instanceof BigDecimal ? (BigDecimal) result : null;
        log.debug("FACTORY_LU_MATERIAL_TURNOVER_DAYS factory={} → {}", factoryId, value);
        return value;
    }
}
