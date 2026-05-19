package com.cretas.aims.service.alerts.listener;

import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.service.alerts.AlertEngineService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * INVENTORY_EXPIRING 定时扫描器 — Phase 2 Canvas-Alerts.
 *
 * <p>每天 8:00 AM 扫描每个工厂的 enabled INVENTORY_EXPIRING 规则,
 * 查询临期物料批次 (默认 today + 7 days) 触发告警.
 *
 * <p>SpEL 默认 context 变量:
 * <ul>
 *   <li>{@code #context.batchId} / {@code #context.batchNumber}</li>
 *   <li>{@code #context.expiryDate}</li>
 *   <li>{@code #context.daysUntilExpiry}</li>
 *   <li>{@code #context.materialName}</li>
 * </ul>
 *
 * <p><b>Phase 2 follow-up</b>: 当前 scan 体仅遍历 rule 找用例, 实际 DB 查询
 * (查 MaterialBatch where expiry &lt;= today + warningDays) 在 Phase 2 B-3
 * 接入 MaterialBatchRepository. 当前空跑 — 不会创建事件, 不影响 listener 测试.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertInventoryExpiringScheduler {

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEngineService alertEngineService;

    @PostConstruct
    void init() {
        log.warn("{} registered — DB query impl pending Phase 2 B-3 "
                + "(MaterialBatchRepository where expiry <= today + warningDays)",
                getClass().getSimpleName());
    }

    /** 每天 8:00 AM. ShedLock 防多实例重复 (lockAtMostFor PT2H, lockAtLeastFor PT5M). */
    @Scheduled(cron = "0 0 8 * * ?")
    @SchedulerLock(name = "AlertInventoryExpiringScheduler.evaluate",
                   lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void evaluate() {
        log.info("[AlertInventoryExpiringScheduler] 开始定时扫描");

        try {
            List<String> factoryIds = ruleRepository.findAll().stream()
                    .filter(r -> r.getAlertType() == AlertType.INVENTORY_EXPIRING)
                    .filter(AlertRule::getEnabled)
                    .map(AlertRule::getFactoryId)
                    .distinct()
                    .collect(Collectors.toList());

            log.info("[AlertInventoryExpiringScheduler] {} 个工厂有 INVENTORY_EXPIRING 规则",
                    factoryIds.size());

            for (String factoryId : factoryIds) {
                evaluateFactory(factoryId);
            }
        } catch (Exception e) {
            log.error("[AlertInventoryExpiringScheduler] 扫描失败", e);
        }
    }

    private void evaluateFactory(String factoryId) {
        // Phase 2 follow-up: query MaterialBatchRepository for batches with
        // expiry_date <= now + warningDays. For each batch, call triggerAlert
        // with context containing batchId, materialName, expiryDate, daysUntilExpiry.
        log.debug("[AlertInventoryExpiringScheduler] 扫描工厂 {} (impl 待 Phase 2 B-3 接入"
                + " MaterialBatchRepository.findExpiringBatches)", factoryId);

        // Stub: trigger alerts with empty context. Listeners with no SpEL fire by
        // default; listeners with SpEL referencing #context.expiryDate get skipped
        // until B-3 wires the batch query.
        Map<String, Object> stubContext = new HashMap<>();
        stubContext.put("scheduledScan", true);
        stubContext.put("factoryId", factoryId);
        // Don't trigger empty-context events — that would create noise. Just log.
        // alertEngineService.triggerAlert(factoryId, AlertType.INVENTORY_EXPIRING, null, null, stubContext);
    }
}
