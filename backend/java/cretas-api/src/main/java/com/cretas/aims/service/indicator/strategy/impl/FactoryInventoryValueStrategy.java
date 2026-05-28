package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FACTORY_INVENTORY_VALUE — 库存总价值 (Sprint 12 Phase B step 2).
 *
 * <p>SQL: {@code SELECT SUM(receipt_quantity * unit_price) FROM material_batches WHERE
 * factory_id=? AND status='ACTIVE' AND deleted_at IS NULL}.
 *
 * <p><b>Schema quirk</b>: per {@code MaterialBatch.java:81-91} comment,
 * {@code receipt_quantity} 在批次被消耗时会扣减到 0 (实现把消耗 写回 receipt_quantity 而非
 * used_quantity). 所以 {@code receipt_quantity} 就是当前剩余, 不需要 减去 {@code used_quantity}.
 *
 * <p>Period 参数被忽略 — 库存价值是 instant snapshot, 不依赖时间窗.
 *
 * <p>实测 F006 (test cretas_db 2026-05-29): 8 个 ACTIVE batches → ¥50,095.00.
 */
@Slf4j
@Component
public class FactoryInventoryValueStrategy implements IndicatorComputationStrategy {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String getIndicatorCode() {
        return "FACTORY_INVENTORY_VALUE";
    }

    @Override
    public BigDecimal compute(String factoryId, LocalDate periodStart, LocalDate periodEnd) {
        // periodStart/End 忽略 — 库存是 instant snapshot
        Object result = em.createNativeQuery(
                "SELECT COALESCE(SUM(receipt_quantity * COALESCE(unit_price, 0)), 0) " +
                "FROM material_batches " +
                "WHERE factory_id = ?1 " +
                "  AND status = 'ACTIVE' " +
                "  AND deleted_at IS NULL")
                .setParameter(1, factoryId)
                .getSingleResult();

        BigDecimal value = result instanceof BigDecimal ? (BigDecimal) result : BigDecimal.ZERO;
        log.debug("FACTORY_INVENTORY_VALUE factory={} → {}", factoryId, value);
        return value;
    }
}
