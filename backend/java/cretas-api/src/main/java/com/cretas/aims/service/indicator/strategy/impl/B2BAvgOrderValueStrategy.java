package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * B2B_AVG_ORDER_VALUE — 平均订单金额 (Sprint 12 Phase B).
 *
 * <p>SQL: {@code SELECT AVG(total_amount) FROM sales_orders WHERE factory_id=? AND status NOT IN
 * (DRAFT,CANCELLED,FINANCE_REJECTED) AND order_date BETWEEN ? AND ? AND deleted_at IS NULL}.
 *
 * <p>Period 默认: 若 caller 没传 periodStart/End, 用当月 (date_trunc('month', NOW()) → NOW()).
 *
 * <p>替换 Sprint 11 4-B band-aid {@code B2BRealDataSection.vue} 前端 reduce 行为 (它 fetch
 * 最近 200 单算 avg). 后端 strategy 没有 200 单限制, 全量 aggregate.
 */
@Slf4j
@Component
public class B2BAvgOrderValueStrategy implements IndicatorComputationStrategy {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String getIndicatorCode() {
        return "B2B_AVG_ORDER_VALUE";
    }

    @Override
    public BigDecimal compute(String factoryId, LocalDate periodStart, LocalDate periodEnd) {
        LocalDate start = periodStart != null ? periodStart : LocalDate.now().withDayOfMonth(1);
        LocalDate end = periodEnd != null ? periodEnd : LocalDate.now();

        Object result = em.createNativeQuery(
                "SELECT COALESCE(AVG(total_amount), 0) " +
                "FROM sales_orders " +
                "WHERE factory_id = ?1 " +
                "  AND status NOT IN ('DRAFT', 'CANCELLED', 'FINANCE_REJECTED') " +
                "  AND order_date BETWEEN ?2 AND ?3 " +
                "  AND deleted_at IS NULL")
                .setParameter(1, factoryId)
                .setParameter(2, start)
                .setParameter(3, end)
                .getSingleResult();

        BigDecimal value = result instanceof BigDecimal ? (BigDecimal) result : BigDecimal.ZERO;
        log.debug("B2B_AVG_ORDER_VALUE factory={} period=[{},{}] → {}", factoryId, start, end, value);
        return value;
    }
}
