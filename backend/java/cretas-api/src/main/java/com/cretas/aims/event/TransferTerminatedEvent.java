package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * 调拨单终止事件 (取消 / 驳回) —— 由 {@code TransferServiceImpl.cancelTransfer / rejectTransfer} 发布。
 *
 * <p><b>为什么新增</b> (2026-08-09, 六膳门 TRF-20260809-1790 善后): INVENTORY_TRANSFER 凭证在调拨单
 * <b>创建</b>时就由 {@code TransferVoucherListener.onTransferCreated} 生成 (草稿阶段, 库存一分没动),
 * 而 cancelTransfer / rejectTransfer 旧实现<b>只翻状态</b>, 从不作废已生成的凭证 —— 账上于是留着一张
 * 对应不到任何实物流的内部调拨凭证。实测 TRF-20260809-1790 取消后, 借贷各 ¥10,000 的
 * {@code V-2026-0023} 仍是 DRAFT 挂在库里。
 *
 * <p>这与销售侧 2026-07-04 修过的 Bug 3 是<b>同一形状</b>: 凭证在前、终止在后、终止不回收凭证
 * (见 {@link SalesOrderCancelledEvent})。本事件照那条已验证的路子走。
 *
 * <p>用 AFTER_COMMIT 异步 listener 作废 (而非在取消 tx 内直接调 voidVoucher), 避免 voidVoucher 的
 * 内层 @Transactional 抛异常污染取消主事务 (doomed-tx) —— 逐字沿用销售侧的理由。
 */
@Getter
public class TransferTerminatedEvent extends ApplicationEvent {

    private final String factoryId;
    private final String transferId;
    /** CANCELLED 或 REJECTED —— 进作废原因文案, 让财务一眼看出是哪种终止。 */
    private final String terminalStatus;
    private final String reason;
    private final LocalDateTime terminatedAt;

    public TransferTerminatedEvent(Object source, String factoryId, String transferId,
                                   String terminalStatus, String reason) {
        super(source);
        this.factoryId = factoryId;
        this.transferId = transferId;
        this.terminalStatus = terminalStatus;
        this.reason = reason;
        this.terminatedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return String.format("TransferTerminatedEvent[factoryId=%s, transferId=%s, status=%s, reason=%s]",
                factoryId, transferId, terminalStatus, reason);
    }
}
