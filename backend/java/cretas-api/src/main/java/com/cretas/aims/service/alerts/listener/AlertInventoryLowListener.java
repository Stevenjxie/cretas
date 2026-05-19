package com.cretas.aims.service.alerts.listener;

import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.event.InventoryStockChangedEvent;
import com.cretas.aims.service.alerts.AlertEngineService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * INVENTORY_LOW 事件路由 — Phase 2 Canvas-Alerts.
 *
 * <p>监听 {@link InventoryStockChangedEvent}, 派发到 {@link AlertEngineService#triggerAlert}.
 *
 * <p>SpEL 默认 context 变量 (可在规则中引用):
 * <ul>
 *   <li>{@code #context.currentStock} — 当前库存量</li>
 *   <li>{@code #context.minStockLevel} — 最低阈值</li>
 *   <li>{@code #context.materialName} — 物料名称</li>
 *   <li>{@code #context.unit} — 计量单位</li>
 * </ul>
 *
 * <p>典型 SpEL: {@code "#context.currentStock < #context.minStockLevel"} 或
 * {@code "#context.currentStock < 30"} (硬编码阈值).
 *
 * <p>Phase 2 follow-up: 业务 Service (WMS / MaterialBatchServiceImpl) 应在写
 * 操作后 publish {@link InventoryStockChangedEvent}. 当前 @PostConstruct
 * log.warn 提示, scheduler 兜底.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertInventoryLowListener {

    @Autowired
    private AlertEngineService alertEngineService;

    @PostConstruct
    void init() {
        log.warn("AlertInventoryLowListener registered — "
                + "Phase 2 follow-up: WMS/MaterialBatchServiceImpl should publish "
                + "InventoryStockChangedEvent on stock writes. Scheduler "
                + "(AlertInventoryLowScheduler) provides fallback every 15min.");
    }

    @EventListener
    @Async
    @Transactional
    public void onInventoryStockChanged(InventoryStockChangedEvent event) {
        try {
            log.debug("Received {}", event);
            Map<String, Object> context = new HashMap<>();
            context.put("currentStock", event.getCurrentStock());
            context.put("minStockLevel", event.getMinStockLevel());
            context.put("materialTypeId", event.getMaterialTypeId());
            context.put("materialName", event.getMaterialName());
            context.put("unit", event.getUnit());
            context.put("changeType", event.getChangeType());

            // Pre-format message if SpEL evaluates to true.
            BigDecimal current = event.getCurrentStock();
            BigDecimal min = event.getMinStockLevel();
            if (current != null && min != null && event.getMaterialName() != null) {
                context.put("message", String.format(
                        "%s 库存 %s%s 已低于阈值 %s%s",
                        event.getMaterialName(),
                        current.toPlainString(),
                        event.getUnit() != null ? event.getUnit() : "",
                        min.toPlainString(),
                        event.getUnit() != null ? event.getUnit() : ""));
            }

            alertEngineService.triggerAlert(
                    event.getFactoryId(),
                    AlertType.INVENTORY_LOW,
                    "MATERIAL",
                    event.getMaterialTypeId(),
                    context);
        } catch (Exception e) {
            log.error("AlertInventoryLowListener: failed to process event {}", event, e);
        }
    }
}
