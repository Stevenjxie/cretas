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
 * Canvas-Cron TaskHandler bean for SUPPLIER_PAYABLE_DUE 定时扫描.
 *
 * <p>Mirrors the business logic of
 * {@link com.cretas.aims.service.alerts.listener.AlertSupplierPayableDueScheduler#evaluate()}.
 * Both the legacy {@code @Scheduled(cron="0 30 9 * * ?")} and Canvas-Cron DB rows
 * referencing {@code handler_bean_name = 'alertSupplierPayableDueTaskHandler'} can
 * drive this same logic.
 *
 * <p>SpEL default context variables (Phase 2 B-3 will wire actual values):
 * <ul>
 *   <li>{@code #context.supplierId} / {@code #context.supplierName}</li>
 *   <li>{@code #context.purchaseOrderId} / {@code #context.dueDate}</li>
 *   <li>{@code #context.daysUntilDue}</li>
 *   <li>{@code #context.payableAmount}</li>
 * </ul>
 *
 * @since 2026-05-21 (Canvas Cron Phase 2 — handler bean migration batch 1)
 */
@Slf4j
@Component("alertSupplierPayableDueTaskHandler")
public class AlertSupplierPayableDueTaskHandler implements TaskHandler {

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEngineService alertEngineService;

    @Override
    public void execute(Map<String, Object> context) {
        log.info("[AlertSupplierPayableDueTaskHandler] 开始定时扫描 — context={}", context);

        String scopeFactoryId = (String) context.get("factoryId");

        try {
            List<String> factoryIds = ruleRepository.findAll().stream()
                    .filter(r -> r.getAlertType() == AlertType.SUPPLIER_PAYABLE_DUE)
                    .filter(AlertRule::getEnabled)
                    .map(AlertRule::getFactoryId)
                    .filter(fid -> scopeFactoryId == null || scopeFactoryId.equals(fid))
                    .distinct()
                    .collect(Collectors.toList());

            log.info("[AlertSupplierPayableDueTaskHandler] {} 个工厂有该类型规则",
                    factoryIds.size());

            int scanned = 0;
            for (String factoryId : factoryIds) {
                evaluateFactory(factoryId);
                scanned++;
            }
            context.put("scannedFactories", scanned);
        } catch (Exception e) {
            log.error("[AlertSupplierPayableDueTaskHandler] 扫描失败", e);
            throw new RuntimeException("SUPPLIER_PAYABLE_DUE scan failed: " + e.getMessage(), e);
        }
    }

    private void evaluateFactory(String factoryId) {
        // Phase 2 follow-up: query PayableRepository for entries with
        // due_date <= today + N days, group by supplier. For each, call triggerAlert.
        log.debug("[AlertSupplierPayableDueTaskHandler] 扫描工厂 {} (impl 待 Phase 2 B-3)",
                factoryId);
    }
}
