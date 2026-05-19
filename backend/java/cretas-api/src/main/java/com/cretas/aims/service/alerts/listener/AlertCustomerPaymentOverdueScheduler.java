package com.cretas.aims.service.alerts.listener;

import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.service.alerts.AlertEngineService;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CUSTOMER_PAYMENT_OVERDUE 定时扫描器 — Phase 2 Canvas-Alerts.
 *
 * <p>每天 9:00 AM 扫描每个工厂的应收账款逾期情况.
 *
 * <p>SpEL 默认 context 变量:
 * <ul>
 *   <li>{@code #context.customerId} / {@code #context.customerName}</li>
 *   <li>{@code #context.invoiceNumber}</li>
 *   <li>{@code #context.agingDays}</li>
 *   <li>{@code #context.overdueAmount}</li>
 * </ul>
 *
 * <p><b>Phase 2 follow-up</b>: 当前 scan 仅遍历 rule, 实际 DB 查询接入
 * InvoiceRecord / ReceivableRepository 在 Phase 2 B-3.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertCustomerPaymentOverdueScheduler {

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEngineService alertEngineService;

    @Scheduled(cron = "0 0 9 * * ?")
    @SchedulerLock(name = "AlertCustomerPaymentOverdueScheduler.evaluate",
                   lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void evaluate() {
        log.info("[AlertCustomerPaymentOverdueScheduler] 开始定时扫描");

        try {
            List<String> factoryIds = ruleRepository.findAll().stream()
                    .filter(r -> r.getAlertType() == AlertType.CUSTOMER_PAYMENT_OVERDUE)
                    .filter(AlertRule::getEnabled)
                    .map(AlertRule::getFactoryId)
                    .distinct()
                    .collect(Collectors.toList());

            log.info("[AlertCustomerPaymentOverdueScheduler] {} 个工厂有该类型规则", factoryIds.size());

            for (String factoryId : factoryIds) {
                evaluateFactory(factoryId);
            }
        } catch (Exception e) {
            log.error("[AlertCustomerPaymentOverdueScheduler] 扫描失败", e);
        }
    }

    private void evaluateFactory(String factoryId) {
        // Phase 2 follow-up: query InvoiceRecordRepository for entries with
        // aging_days > N, group by customer. For each overdue invoice, call
        // triggerAlert with full context.
        log.debug("[AlertCustomerPaymentOverdueScheduler] 扫描工厂 {} (impl 待 Phase 2 B-3)",
                factoryId);
    }
}
