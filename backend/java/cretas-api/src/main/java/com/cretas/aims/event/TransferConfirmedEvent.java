package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * 调拨单<b>确认入库</b>事件 —— 由 {@code TransferServiceImpl.confirmTransfer} 发布。
 *
 * <p><b>为什么新增</b> (2026-08-09): INVENTORY_TRANSFER 凭证原先挂在
 * {@link TransferCreatedEvent} 上 —— 调拨单一建出来 (还是草稿, 库存一分没动) 账上就有凭证了。
 * 从没被确认、或中途被放弃/取消的草稿, 都会在账上留下对应不到实物流的凭证。六膳门
 * TRF-20260809-1790 的 ¥10,000 {@code V-2026-0023} 就是这么来的。
 *
 * <p>凭证改挂本事件后, 只有<b>库存真正完成搬运</b>的调拨才入账 —— 与采购侧 2026-07-04 Bug 4
 * 的修法完全一致 (那次把 PURCHASE_PAYMENT 凭证从"入库单草稿创建"迁到
 * {@link PurchaseReceiveConfirmedEvent} "确认入库", 理由逐字相同)。
 *
 * <p><b>入账日期不受影响</b>: {@code InventoryTransferVoucherGenerator.extractVoucherDate} 取的是
 * 单据上的 {@code transferDate} (业务调拨日期), 不是创建/确认的时间戳。改的只是"什么时候生成",
 * 不是"记在哪一天"。
 *
 * <p><b>factoryId 必须是调出方</b>: 同厂调拨两边相同, 但跨厂调拨由<b>调入方</b>执行确认, 而凭证
 * 一直归属调出方 (原 {@code onTransferCreated} 用的就是 sourceFactoryId)。这里照旧传调出方,
 * 不要图省事传当前操作工厂。
 */
@Getter
public class TransferConfirmedEvent extends ApplicationEvent {

    /** 调出方工厂 —— 凭证归属方, 不是执行确认的那一方。 */
    private final String sourceFactoryId;
    private final String transferId;
    private final LocalDateTime confirmedAt;

    public TransferConfirmedEvent(Object source, String sourceFactoryId, String transferId) {
        super(source);
        this.sourceFactoryId = sourceFactoryId;
        this.transferId = transferId;
        this.confirmedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return String.format("TransferConfirmedEvent[sourceFactoryId=%s, transferId=%s]",
                sourceFactoryId, transferId);
    }
}
