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
 * SALES_DECLINE 定时扫描器 — Phase 2 Canvas-Alerts.
 *
 * <p>每天 10:00 AM 扫描每个工厂的销售环比下滑情况.
 *
 * <p>SpEL 默认 context 变量:
 * <ul>
 *   <li>{@code #context.currentPeriodSales} / {@code #context.previousPeriodSales}</li>
 *   <li>{@code #context.declinePct} — 下滑百分比 (e.g. -0.15 表示下滑 15%)</li>
 *   <li>{@code #context.periodType} — "WEEK" / "MONTH" / "QUARTER"</li>
 * </ul>
 *
 * <p><b>Phase 2 follow-up</b>: 实际 DB 查询 SalesOrderRepository 计算 period-over-period
 * 在 Phase 2 B-3.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertSalesDeclineScheduler {

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEngineService alertEngineService;

    @Scheduled(cron = "0 0 10 * * ?")
    @SchedulerLock(name = "AlertSalesDeclineScheduler.evaluate",
                   lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void evaluate() {
        log.info("[AlertSalesDeclineScheduler] 开始定时扫描");

        try {
            List<String> factoryIds = ruleRepository.findAll().stream()
                    .filter(r -> r.getAlertType() == AlertType.SALES_DECLINE)
                    .filter(AlertRule::getEnabled)
                    .map(AlertRule::getFactoryId)
                    .distinct()
                    .collect(Collectors.toList());

            log.info("[AlertSalesDeclineScheduler] {} 个工厂有该类型规则", factoryIds.size());

            for (String factoryId : factoryIds) {
                evaluateFactory(factoryId);
            }
        } catch (Exception e) {
            log.error("[AlertSalesDeclineScheduler] 扫描失败", e);
        }
    }

    private void evaluateFactory(String factoryId) {
        // Phase 2 follow-up: query SalesOrderRepository, aggregate by period.
        // Calc declinePct = (current - previous) / previous. Trigger alert if
        // SpEL evaluates true.
        log.debug("[AlertSalesDeclineScheduler] 扫描工厂 {} (impl 待 Phase 2 B-3)",
                factoryId);
    }
}
