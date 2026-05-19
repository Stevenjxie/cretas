package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购订单创建事件 — Phase 2 Canvas-Alerts 引入.
 *
 * <p>触发 {@code AlertPoAmountThresholdListener}, 评估 PO_AMOUNT_THRESHOLD 规则
 * (e.g. amount &gt;= 50000 → 大额采购预警).
 *
 * <p><b>Phase 2 follow-up</b>: PurchaseServiceImpl.createPurchaseOrder 应在创建
 * 成功后 publish 此事件. 当前 Listener 注册待发布事件状态 (log.warn @PostConstruct),
 * Phase 2 follow-up issue 在 PurchaseService 加 publish 调用.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Getter
public class PurchaseOrderCreatedEvent extends ApplicationEvent {

    private final String factoryId;
    private final String purchaseOrderId;
    private final String orderNumber;
    private final String supplierId;
    private final BigDecimal totalAmount;
    private final LocalDateTime createdAt;

    public PurchaseOrderCreatedEvent(Object source, String factoryId, String purchaseOrderId,
                                     String orderNumber, String supplierId, BigDecimal totalAmount) {
        super(source);
        this.factoryId = factoryId;
        this.purchaseOrderId = purchaseOrderId;
        this.orderNumber = orderNumber;
        this.supplierId = supplierId;
        this.totalAmount = totalAmount;
        this.createdAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return String.format(
                "PurchaseOrderCreatedEvent[factoryId=%s, orderId=%s, supplierId=%s, totalAmount=%s]",
                factoryId, purchaseOrderId, supplierId, totalAmount);
    }
}
