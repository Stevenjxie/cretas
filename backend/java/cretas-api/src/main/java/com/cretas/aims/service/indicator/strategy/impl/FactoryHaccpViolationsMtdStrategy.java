package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FACTORY_HACCP_VIOLATIONS_MTD — 本月 HACCP 违规次数 (Sprint 12 Phase B step 5).
 *
 * <p>Two-step strategy: 先 check 当月有 HACCP audit records, 再 count is_deviation=true.
 * 如果 0 audits → 返 null (区分 "0 真违规" vs "0 audits"). 如果 N audits → 返 violation count.
 *
 * <p>SQL:
 * <pre>
 * Step 1: SELECT COUNT(*) FROM haccp_monitoring_records WHERE factory_id=? AND MTD
 *         → if 0 then return null
 * Step 2: SELECT COUNT(*) WHERE is_deviation=true AND MTD
 *         → return as BigDecimal
 * </pre>
 *
 * <p><b>Phase B step 5 null-preserve convention</b>: 无 audit 数据 → 返 null (per interface
 * javadoc + 区分 "0 violations 真" vs "0 audits 没监测"). UI 渲 "—".
 *
 * <p>实测 F006 (test cretas_db 2026-05-29): 0 haccp_monitoring_records → 返 null → UI "—".
 */
@Slf4j
@Component
public class FactoryHaccpViolationsMtdStrategy implements IndicatorComputationStrategy {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String getIndicatorCode() {
        return "FACTORY_HACCP_VIOLATIONS_MTD";
    }

    @Override
    public BigDecimal compute(String factoryId, LocalDate periodStart, LocalDate periodEnd) {
        // periodStart/End 忽略 — MTD 永远是当月

        // Step 1: 当月有任何 audit records?
        Object totalResult = em.createNativeQuery(
                "SELECT COUNT(*) FROM haccp_monitoring_records " +
                "WHERE factory_id = ?1 " +
                "  AND monitoring_time >= date_trunc('month', NOW()) " +
                "  AND deleted_at IS NULL")
                .setParameter(1, factoryId)
                .getSingleResult();
        long total = totalResult instanceof Number ? ((Number) totalResult).longValue() : 0L;

        if (total == 0) {
            // 没 audit 记录 — 区分 "0 真违规" vs "0 没监测"
            log.debug("FACTORY_HACCP_VIOLATIONS_MTD factory={} → null (0 audits this month)", factoryId);
            return null;
        }

        // Step 2: 真有 audit, 数 is_deviation=true 的
        Object violationResult = em.createNativeQuery(
                "SELECT COUNT(*) FROM haccp_monitoring_records " +
                "WHERE factory_id = ?1 " +
                "  AND is_deviation = TRUE " +
                "  AND monitoring_time >= date_trunc('month', NOW()) " +
                "  AND deleted_at IS NULL")
                .setParameter(1, factoryId)
                .getSingleResult();
        long violations = violationResult instanceof Number ? ((Number) violationResult).longValue() : 0L;

        BigDecimal value = BigDecimal.valueOf(violations);
        log.debug("FACTORY_HACCP_VIOLATIONS_MTD factory={} → {} (of {} audits)",
                factoryId, value, total);
        return value;
    }
}
