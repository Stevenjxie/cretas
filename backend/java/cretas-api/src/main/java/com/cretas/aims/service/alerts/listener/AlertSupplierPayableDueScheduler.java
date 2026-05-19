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
 * SUPPLIER_PAYABLE_DUE 定时扫描器 — Phase 2 Canvas-Alerts.
 *
 * <p>每天 9:30 AM 扫描每个工厂的应付账款到期情况.
 *
 * <p>SpEL 默认 context 变量:
 * <ul>
 *   <li>{@code #context.supplierId} / {@code #context.supplierName}</li>
 *   <li>{@code #context.purchaseOrderId} / {@code #context.dueDate}</li>
 *   <li>{@code #context.daysUntilDue}</li>
 *   <li>{@code #context.payableAmount}</li>
 * </ul>
 *
 * <p><b>Phase 2 follow-up</b>: 实际 DB 查询接入 PayableRepository 在 Phase 2 B-3.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertSupplierPayableDueScheduler {

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEngineService alertEngineService;

    @Scheduled(cron = "0 30 9 * * ?")
    @SchedulerLock(name = "AlertSupplierPayableDueScheduler.evaluate",
                   lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void evaluate() {
        log.info("[AlertSupplierPayableDueScheduler] 开始定时扫描");

        try {
            List<String> factoryIds = ruleRepository.findAll().stream()
                    .filter(r -> r.getAlertType() == AlertType.SUPPLIER_PAYABLE_DUE)
                    .filter(AlertRule::getEnabled)
                    .map(AlertRule::getFactoryId)
                    .distinct()
                    .collect(Collectors.toList());

            log.info("[AlertSupplierPayableDueScheduler] {} 个工厂有该类型规则", factoryIds.size());

            for (String factoryId : factoryIds) {
                evaluateFactory(factoryId);
            }
        } catch (Exception e) {
            log.error("[AlertSupplierPayableDueScheduler] 扫描失败", e);
        }
    }

    private void evaluateFactory(String factoryId) {
        // Phase 2 follow-up: query PayableRepository for entries with
        // due_date <= today + N days, group by supplier. For each, call triggerAlert.
        log.debug("[AlertSupplierPayableDueScheduler] 扫描工厂 {} (impl 待 Phase 2 B-3)",
                factoryId);
    }
}
