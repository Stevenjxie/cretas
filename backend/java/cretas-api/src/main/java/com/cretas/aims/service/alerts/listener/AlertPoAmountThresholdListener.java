package com.cretas.aims.service.alerts.listener;

import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.event.PurchaseOrderCreatedEvent;
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
 * PO_AMOUNT_THRESHOLD 事件路由 — Phase 2 Canvas-Alerts.
 *
 * <p>监听 {@link PurchaseOrderCreatedEvent}, 派发到 {@link AlertEngineService#triggerAlert}.
 *
 * <p>SpEL 默认 context 变量:
 * <ul>
 *   <li>{@code #context.amount} — 订单总金额 (BigDecimal)</li>
 *   <li>{@code #context.orderNumber}</li>
 *   <li>{@code #context.supplierId}</li>
 * </ul>
 *
 * <p>典型 SpEL: {@code "#context.amount >= 50000"} 大额预警.
 *
 * <p>Phase 2 follow-up: PurchaseServiceImpl.createPurchaseOrder 需 publish
 * {@link PurchaseOrderCreatedEvent}. Listener 完整就绪.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertPoAmountThresholdListener {

    @Autowired
    private AlertEngineService alertEngineService;

    @PostConstruct
    void init() {
        log.warn("AlertPoAmountThresholdListener registered — "
                + "Phase 2 follow-up: PurchaseServiceImpl.createPurchaseOrder should "
                + "publish PurchaseOrderCreatedEvent.");
    }

    @EventListener
    @Async
    @Transactional
    public void onPurchaseOrderCreated(PurchaseOrderCreatedEvent event) {
        try {
            log.debug("Received {}", event);
            Map<String, Object> context = new HashMap<>();
            context.put("amount", event.getTotalAmount());
            context.put("orderNumber", event.getOrderNumber());
            context.put("supplierId", event.getSupplierId());
            context.put("purchaseOrderId", event.getPurchaseOrderId());

            BigDecimal amount = event.getTotalAmount();
            if (amount != null && event.getOrderNumber() != null) {
                context.put("message", String.format(
                        "采购订单 %s 金额 ¥%s 超过预警阈值",
                        event.getOrderNumber(), amount.toPlainString()));
            }

            alertEngineService.triggerAlert(
                    event.getFactoryId(),
                    AlertType.PO_AMOUNT_THRESHOLD,
                    "PURCHASE_ORDER",
                    event.getPurchaseOrderId(),
                    context);
        } catch (Exception e) {
            log.error("AlertPoAmountThresholdListener: failed to process event {}", event, e);
        }
    }
}
