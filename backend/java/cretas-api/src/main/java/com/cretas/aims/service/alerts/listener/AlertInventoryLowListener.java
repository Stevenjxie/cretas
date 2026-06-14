package com.cretas.aims.service.alerts.listener;

import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.event.InventoryStockChangedEvent;
import com.cretas.aims.service.alerts.AlertEngineService;
import com.cretas.aims.service.alerts.LowStockDualAlertService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

    /** F-034: 实时双向报警 (仓库+采购). */
    @Autowired
    private LowStockDualAlertService lowStockDualAlertService;

    @PostConstruct
    void init() {
        log.info("AlertInventoryLowListener registered — "
                + "F-034 dual alert (warehouse_manager + procurement_manager) active. "
                + "Scheduler fallback every 15 min.");
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
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

            // F-034: 同步推双向通知 (仓库 + 采购)
            lowStockDualAlertService.checkAndNotify(
                    event.getFactoryId(),
                    event.getMaterialTypeId(),
                    event.getCurrentStock(),
                    event.getMinStockLevel(),
                    event.getUnit());
        } catch (Exception e) {
            log.error("AlertInventoryLowListener: failed to process event {}", event, e);
        }
    }
}
