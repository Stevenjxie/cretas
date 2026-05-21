package com.cretas.aims.service.cron.handler;

import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.service.alerts.AlertEngineService;
import com.cretas.aims.service.cron.TaskHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Canvas-Cron TaskHandler bean for INVENTORY_LOW 兜底扫描 — Phase 2 follow-up.
 *
 * <p>Mirrors the business logic of
 * {@link com.cretas.aims.service.alerts.listener.AlertInventoryLowScheduler#evaluate()}
 * so the same job can be driven either by the legacy {@code @Scheduled(fixedRate=15min)}
 * or by a {@code scheduled_tasks} DB row referencing
 * {@code handler_bean_name = 'alertInventoryLowTaskHandler'}.
 *
 * <p>Both paths coexist (backward compatibility): operators may keep the legacy
 * scheduler running while progressively migrating tenants to Canvas-Cron rows.
 *
 * <p>Bean name: {@code alertInventoryLowTaskHandler} (default @Component naming).
 *
 * @since 2026-05-21 (Canvas Cron Phase 2 — handler bean migration batch 1)
 */
@Slf4j
@Component("alertInventoryLowTaskHandler")
public class AlertInventoryLowTaskHandler implements TaskHandler {

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEngineService alertEngineService;

    @Override
    public void execute(Map<String, Object> context) {
        log.debug("[AlertInventoryLowTaskHandler] 兜底扫描启动 — context={}", context);

        // Optional: factoryId scoping. If Canvas-Cron row sets factoryId,
        // restrict scan to that factory; otherwise scan ALL enabled rules.
        String scopeFactoryId = (String) context.get("factoryId");

        try {
            List<String> factoryIds = ruleRepository.findAll().stream()
                    .filter(r -> r.getAlertType() == AlertType.INVENTORY_LOW)
                    .filter(AlertRule::getEnabled)
                    .map(AlertRule::getFactoryId)
                    .filter(fid -> scopeFactoryId == null || scopeFactoryId.equals(fid))
                    .distinct()
                    .collect(Collectors.toList());

            log.info("[AlertInventoryLowTaskHandler] {} 个工厂有 INVENTORY_LOW 规则",
                    factoryIds.size());

            int scanned = 0;
            for (String factoryId : factoryIds) {
                evaluateFactory(factoryId);
                scanned++;
            }
            // Put output back into context for run log capture (per TaskHandler contract).
            context.put("scannedFactories", scanned);
        } catch (Exception e) {
            log.error("[AlertInventoryLowTaskHandler] 扫描失败", e);
            // Re-throw to mark task as FAILED in run log + last_run_error.
            throw new RuntimeException("INVENTORY_LOW scan failed: " + e.getMessage(), e);
        }
    }

    private void evaluateFactory(String factoryId) {
        // Phase 2 follow-up: query material_batches aggregate by material_type_id,
        // compare current stock vs minStockLevel; for each below-threshold material,
        // call alertEngineService.triggerAlert with full context.
        log.trace("[AlertInventoryLowTaskHandler] 扫描工厂 {} (impl 待 Phase 2 B-3)",
                factoryId);
    }
}
