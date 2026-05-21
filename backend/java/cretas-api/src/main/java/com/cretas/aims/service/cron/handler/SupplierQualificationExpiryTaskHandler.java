package com.cretas.aims.service.cron.handler;

import com.cretas.aims.scheduler.SupplierQualificationExpiryScheduler;
import com.cretas.aims.service.cron.TaskHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Canvas-Cron TaskHandler bean for 供应商食品资质过期预警 — Sprint 9 P2.C.
 *
 * <p>Delegates to the existing implementation in
 * {@link SupplierQualificationExpiryScheduler#dailyExpiryCheck()}. Real business
 * logic (3-tier expired / urgent / warning + notify QUALITY_MANAGER) lives in
 * the legacy scheduler; this handler exposes the same job to Canvas-Cron DB rows
 * so users can pause/reschedule it from the UI without JVM restart.
 *
 * <p>Bean name: {@code supplierQualificationExpiryTaskHandler} — reference from
 * {@code scheduled_tasks.handler_bean_name} for global cross-factory scans.
 *
 * <p>Backward compat: the legacy {@code @Scheduled(cron="0 0 8 * * ?")} stays
 * active. To migrate to Canvas-Cron, operators (1) insert a {@code scheduled_tasks}
 * row referencing this bean, then (2) flip the application property
 * {@code supplier.qualification-scheduler.enabled=false} to silence the legacy
 * path. Both paths share the same ShedLock name so they'll never double-execute.
 *
 * @since 2026-05-21 (Canvas Cron Phase 2 — handler bean migration batch 1)
 */
@Slf4j
@Component("supplierQualificationExpiryTaskHandler")
public class SupplierQualificationExpiryTaskHandler implements TaskHandler {

    @Autowired
    private SupplierQualificationExpiryScheduler legacyScheduler;

    @Override
    public void execute(Map<String, Object> context) {
        log.info("[SupplierQualificationExpiryTaskHandler] 触发资质过期扫描 — context={}", context);

        // Delegate to legacy implementation. The legacy method respects the
        // 'enabled' property; Canvas-Cron operators should set enabled=true on
        // the scheduled_tasks row + enabled=false on the legacy property to
        // avoid double-runs (ShedLock would skip the second anyway, but UI
        // clarity is the goal).
        try {
            legacyScheduler.dailyExpiryCheck();
            context.put("status", "DELEGATED_SUCCESS");
        } catch (Exception e) {
            log.error("[SupplierQualificationExpiryTaskHandler] 资质扫描失败", e);
            throw new RuntimeException(
                    "supplier qualification expiry scan failed: " + e.getMessage(), e);
        }
    }
}
