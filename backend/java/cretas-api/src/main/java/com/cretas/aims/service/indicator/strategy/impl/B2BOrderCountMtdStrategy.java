package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * B2B_ORDER_COUNT_MTD — 本月订单数 (Sprint 12 Phase B).
 *
 * <p>SQL: {@code SELECT COUNT(*) FROM sales_orders WHERE factory_id=? AND status NOT IN
 * (DRAFT,CANCELLED,FINANCE_REJECTED) AND order_date >= date_trunc('month', NOW())::date}.
 *
 * <p>MTD 同 {@code B2BTotalRevenueMtdStrategy} — 永远本月.
 */
@Slf4j
@Component
public class B2BOrderCountMtdStrategy implements IndicatorComputationStrategy {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String getIndicatorCode() {
        return "B2B_ORDER_COUNT_MTD";
    }

    @Override
    public BigDecimal compute(String factoryId, LocalDate periodStart, LocalDate periodEnd) {
        Object result = em.createNativeQuery(
                "SELECT COUNT(*) " +
                "FROM sales_orders " +
                "WHERE factory_id = ?1 " +
                "  AND status NOT IN ('DRAFT', 'CANCELLED', 'FINANCE_REJECTED') " +
                "  AND order_date >= date_trunc('month', NOW())::date " +
                "  AND deleted_at IS NULL")
                .setParameter(1, factoryId)
                .getSingleResult();

        // COUNT(*) returns Long in postgres; convert to BigDecimal for the Indicator.lastValue contract.
        Long count = result instanceof Number ? ((Number) result).longValue() : 0L;
        BigDecimal value = BigDecimal.valueOf(count);
        log.debug("B2B_ORDER_COUNT_MTD factory={} → {}", factoryId, value);
        return value;
    }
}
