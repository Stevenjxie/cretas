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
 * Canvas-Cron TaskHandler bean for CUSTOMER_PAYMENT_OVERDUE 定时扫描.
 *
 * <p>Mirrors the business logic of
 * {@link com.cretas.aims.service.alerts.listener.AlertCustomerPaymentOverdueScheduler#evaluate()}.
 * Both the legacy {@code @Scheduled(cron="0 0 9 * * ?")} and Canvas-Cron DB rows
 * referencing {@code handler_bean_name = 'alertCustomerPaymentOverdueTaskHandler'}
 * can drive this same logic.
 *
 * <p>SpEL default context variables (Phase 2 B-3 will wire actual values):
 * <ul>
 *   <li>{@code #context.customerId} / {@code #context.customerName}</li>
 *   <li>{@code #context.invoiceNumber}</li>
 *   <li>{@code #context.agingDays}</li>
 *   <li>{@code #context.overdueAmount}</li>
 * </ul>
 *
 * @since 2026-05-21 (Canvas Cron Phase 2 — handler bean migration batch 1)
 */
@Slf4j
@Component("alertCustomerPaymentOverdueTaskHandler")
public class AlertCustomerPaymentOverdueTaskHandler implements TaskHandler {

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEngineService alertEngineService;

    @Override
    public void execute(Map<String, Object> context) {
        log.info("[AlertCustomerPaymentOverdueTaskHandler] 开始定时扫描 — context={}", context);

        String scopeFactoryId = (String) context.get("factoryId");

        try {
            List<String> factoryIds = ruleRepository.findAll().stream()
                    .filter(r -> r.getAlertType() == AlertType.CUSTOMER_PAYMENT_OVERDUE)
                    .filter(AlertRule::getEnabled)
                    .map(AlertRule::getFactoryId)
                    .filter(fid -> scopeFactoryId == null || scopeFactoryId.equals(fid))
                    .distinct()
                    .collect(Collectors.toList());

            log.info("[AlertCustomerPaymentOverdueTaskHandler] {} 个工厂有该类型规则",
                    factoryIds.size());

            int scanned = 0;
            for (String factoryId : factoryIds) {
                evaluateFactory(factoryId);
                scanned++;
            }
            context.put("scannedFactories", scanned);
        } catch (Exception e) {
            log.error("[AlertCustomerPaymentOverdueTaskHandler] 扫描失败", e);
            throw new RuntimeException("CUSTOMER_PAYMENT_OVERDUE scan failed: " + e.getMessage(), e);
        }
    }

    private void evaluateFactory(String factoryId) {
        // Phase 2 follow-up: query InvoiceRecordRepository for entries with
        // aging_days > N, group by customer. For each overdue invoice, call
        // triggerAlert with full context.
        log.debug("[AlertCustomerPaymentOverdueTaskHandler] 扫描工厂 {} (impl 待 Phase 2 B-3)",
                factoryId);
    }
}
