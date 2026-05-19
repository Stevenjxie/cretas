package com.cretas.aims.service.alerts.listener;

import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.event.SalesOrderCreatedEvent;
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
 * SO_AMOUNT_THRESHOLD 事件路由 — Phase 2 Canvas-Alerts.
 *
 * <p>监听 Phase 1 已有 {@link SalesOrderCreatedEvent}, 派发到
 * {@link AlertEngineService#triggerAlert}.
 *
 * <p>SpEL 默认 context 变量:
 * <ul>
 *   <li>{@code #context.amount} — 订单总金额</li>
 *   <li>{@code #context.orderId}</li>
 *   <li>{@code #context.customerId}</li>
 * </ul>
 *
 * <p>事件 publish 已就位 (Phase 1
 * {@code SalesServiceImpl.createSalesOrder} line 306), listener 即刻生效.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertSoAmountThresholdListener {

    @Autowired
    private AlertEngineService alertEngineService;

    @PostConstruct
    void init() {
        log.info("AlertSoAmountThresholdListener registered — "
                + "listens to SalesOrderCreatedEvent (publish已就位).");
    }

    @EventListener
    @Async
    @Transactional
    public void onSalesOrderCreated(SalesOrderCreatedEvent event) {
        try {
            log.debug("Received {}", event);
            Map<String, Object> context = new HashMap<>();
            context.put("amount", event.getTotalAmount());
            context.put("orderId", event.getOrderId());
            context.put("customerId", event.getCustomerId());

            BigDecimal amount = event.getTotalAmount();
            if (amount != null && event.getOrderId() != null) {
                context.put("message", String.format(
                        "销售订单 %s 金额 ¥%s 触发预警",
                        event.getOrderId(), amount.toPlainString()));
            }

            alertEngineService.triggerAlert(
                    event.getFactoryId(),
                    AlertType.SO_AMOUNT_THRESHOLD,
                    "SALES_ORDER",
                    event.getOrderId(),
                    context);
        } catch (Exception e) {
            log.error("AlertSoAmountThresholdListener: failed to process event {}", event, e);
        }
    }
}
