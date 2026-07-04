package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购入库单确认事件 (2026-07-04, Bug 4 修复) — 由 PurchaseServiceImpl.confirmReceive 发布。
 *
 * <p><b>为什么新增</b>: PURCHASE_PAYMENT 凭证原来挂在 {@link PurchaseReceiveCreatedEvent}
 * (入库单 DRAFT 创建时) → 从未确认/被放弃的草稿入库也会生成凭证 → 幽灵凭证。业务真实事件是
 * <b>确认入库</b> (confirmReceive: 创建物料批次 + 自动应付挂账), 凭证应在此刻生成, 与应付挂账
 * 时点一致。故把凭证生成 listener 从 DRAFT 事件迁到本确认事件。</p>
 *
 * <p><b>与 {@link MaterialReceivedEvent} 的区别</b>: MaterialReceivedEvent 在 confirmReceive
 * 内<b>逐物料行</b>发布 (供供应链联动用), 一张入库单发多次。本事件<b>每张入库单只发一次</b>,
 * 避免凭证生成 listener 被并发触发多次 (idempotent 命中唯一约束会误标 vflag=FAILED)。</p>
 *
 * <p>{@link PurchaseReceiveCreatedEvent} 仍保留 (DRAFT 创建时发, 供 QC 抽样/触发链使用), 只是
 * 不再驱动凭证生成。</p>
 */
@Getter
public class PurchaseReceiveConfirmedEvent extends ApplicationEvent {

    private final String factoryId;
    private final String receiveRecordId;
    private final String receiveNumber;
    private final String purchaseOrderId;
    private final BigDecimal totalAmount;
    private final LocalDateTime confirmedAt;

    public PurchaseReceiveConfirmedEvent(Object source, String factoryId, String receiveRecordId,
                                         String receiveNumber, String purchaseOrderId,
                                         BigDecimal totalAmount) {
        super(source);
        this.factoryId = factoryId;
        this.receiveRecordId = receiveRecordId;
        this.receiveNumber = receiveNumber;
        this.purchaseOrderId = purchaseOrderId;
        this.totalAmount = totalAmount;
        this.confirmedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return String.format("PurchaseReceiveConfirmedEvent[factoryId=%s, receiveNumber=%s, purchaseOrderId=%s, totalAmount=%s]",
                factoryId, receiveNumber, purchaseOrderId, totalAmount);
    }
}
