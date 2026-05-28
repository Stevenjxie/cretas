package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * B2B_TOTAL_REVENUE_MTD — 本月销售总额 (Sprint 12 Phase B).
 *
 * <p>SQL: {@code SELECT SUM(total_amount) FROM sales_orders WHERE factory_id=? AND status NOT IN
 * (DRAFT,CANCELLED,FINANCE_REJECTED) AND order_date >= date_trunc('month', NOW())::date}.
 *
 * <p>MTD = Month-To-Date. 忽略 caller 的 periodStart/End — 永远是 当月初 → 今日.
 * 设计选择: 老板看 dashboard 期望 "本月" 一目了然, 不让 BI 用 date filter 干扰核心数字.
 */
@Slf4j
@Component
public class B2BTotalRevenueMtdStrategy implements IndicatorComputationStrategy {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String getIndicatorCode() {
        return "B2B_TOTAL_REVENUE_MTD";
    }

    @Override
    public BigDecimal compute(String factoryId, LocalDate periodStart, LocalDate periodEnd) {
        // MTD: 忽略 caller period, 永远月初 → 今日
        Object result = em.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) " +
                "FROM sales_orders " +
                "WHERE factory_id = ?1 " +
                "  AND status NOT IN ('DRAFT', 'CANCELLED', 'FINANCE_REJECTED') " +
                "  AND order_date >= date_trunc('month', NOW())::date " +
                "  AND deleted_at IS NULL")
                .setParameter(1, factoryId)
                .getSingleResult();

        BigDecimal value = result instanceof BigDecimal ? (BigDecimal) result : BigDecimal.ZERO;
        log.debug("B2B_TOTAL_REVENUE_MTD factory={} → {}", factoryId, value);
        return value;
    }
}
