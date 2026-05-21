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
 * Canvas-Cron TaskHandler bean for INVENTORY_EXPIRING 定时扫描 — Phase 2 follow-up.
 *
 * <p>Mirrors the business logic of
 * {@link com.cretas.aims.service.alerts.listener.AlertInventoryExpiringScheduler#evaluate()}.
 * Both the legacy {@code @Scheduled(cron="0 0 8 * * ?")} and Canvas-Cron DB rows
 * referencing {@code handler_bean_name = 'alertInventoryExpiringTaskHandler'} can
 * drive this same logic.
 *
 * <p>SpEL default context variables (Phase 2 B-3 will wire actual values):
 * <ul>
 *   <li>{@code #context.batchId} / {@code #context.batchNumber}</li>
 *   <li>{@code #context.expiryDate}</li>
 *   <li>{@code #context.daysUntilExpiry}</li>
 *   <li>{@code #context.materialName}</li>
 * </ul>
 *
 * @since 2026-05-21 (Canvas Cron Phase 2 — handler bean migration batch 1)
 */
@Slf4j
@Component("alertInventoryExpiringTaskHandler")
public class AlertInventoryExpiringTaskHandler implements TaskHandler {

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEngineService alertEngineService;

    @Override
    public void execute(Map<String, Object> context) {
        log.info("[AlertInventoryExpiringTaskHandler] 开始定时扫描 — context={}", context);

        String scopeFactoryId = (String) context.get("factoryId");

        try {
            List<String> factoryIds = ruleRepository.findAll().stream()
                    .filter(r -> r.getAlertType() == AlertType.INVENTORY_EXPIRING)
                    .filter(AlertRule::getEnabled)
                    .map(AlertRule::getFactoryId)
                    .filter(fid -> scopeFactoryId == null || scopeFactoryId.equals(fid))
                    .distinct()
                    .collect(Collectors.toList());

            log.info("[AlertInventoryExpiringTaskHandler] {} 个工厂有 INVENTORY_EXPIRING 规则",
                    factoryIds.size());

            int scanned = 0;
            for (String factoryId : factoryIds) {
                evaluateFactory(factoryId);
                scanned++;
            }
            context.put("scannedFactories", scanned);
        } catch (Exception e) {
            log.error("[AlertInventoryExpiringTaskHandler] 扫描失败", e);
            throw new RuntimeException("INVENTORY_EXPIRING scan failed: " + e.getMessage(), e);
        }
    }

    private void evaluateFactory(String factoryId) {
        // Phase 2 follow-up: query MaterialBatchRepository for batches with
        // expiry_date <= now + warningDays. For each batch, call triggerAlert
        // with context containing batchId, materialName, expiryDate, daysUntilExpiry.
        log.debug("[AlertInventoryExpiringTaskHandler] 扫描工厂 {} (impl 待 Phase 2 B-3)",
                factoryId);
    }
}
