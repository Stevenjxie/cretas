package com.cretas.aims.service.alerts.listener;

import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.service.alerts.AlertEngineService;
import com.cretas.aims.service.alerts.LowStockDualAlertService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * INVENTORY_LOW 定时兜底扫描器 — Phase 2 Canvas-Alerts.
 *
 * <p>{@link AlertInventoryLowListener} 是首选 (实时事件触发), 本 scheduler
 * 兜底每 15 分钟扫一次, 防 event publish 漏发.
 *
 * <p><b>Phase 2 follow-up</b>: 查 MaterialBatchRepository 聚合 current_stock
 * by material_type, 跟 minStockLevel 比, 触发告警.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertInventoryLowScheduler {

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEngineService alertEngineService;

    /** F-034: 兜底双向报警 (仓库+采购). */
    @Autowired
    private LowStockDualAlertService lowStockDualAlertService;

    @PostConstruct
    void init() {
        log.info("{} registered — F-034 dual-alert sweep (warehouse_manager + procurement_manager) "
                + "active, every 15 min via ShedLock.", getClass().getSimpleName());
    }

    /** 每 15 分钟. ShedLock 防多实例. */
    @Scheduled(fixedRate = 15L * 60L * 1000L, initialDelay = 60L * 1000L)
    @SchedulerLock(name = "AlertInventoryLowScheduler.evaluate",
                   lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void evaluate() {
        log.debug("[AlertInventoryLowScheduler] 兜底扫描启动");

        try {
            List<String> factoryIds = ruleRepository.findAll().stream()
                    .filter(r -> r.getAlertType() == AlertType.INVENTORY_LOW)
                    .filter(AlertRule::getEnabled)
                    .map(AlertRule::getFactoryId)
                    .distinct()
                    .collect(Collectors.toList());

            for (String factoryId : factoryIds) {
                evaluateFactory(factoryId);
            }
        } catch (Exception e) {
            log.error("[AlertInventoryLowScheduler] 扫描失败", e);
        }
    }

    private void evaluateFactory(String factoryId) {
        // F-034: 双向报警 (仓库+采购) — 兜底扫描
        lowStockDualAlertService.sweepFactory(factoryId);
    }
}
