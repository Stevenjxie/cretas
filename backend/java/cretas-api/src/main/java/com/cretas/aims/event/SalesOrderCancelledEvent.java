package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * 销售订单取消事件 (2026-07-04, Bug 3 修复) — 由 SalesServiceImpl.cancelOrder 发布。
 *
 * <p><b>为什么新增</b>: SALES_RECEIPT 凭证在 confirmOrder (确认) 时即生成, 但 cancelOrder 旧实现
 * 只翻订单状态, 从不作废已生成的凭证 → 幽灵营收 + 应收挂账。本事件驱动
 * {@code SalesOrderVoucherListener} 在订单真正取消 (tx 提交) 后作废对应凭证。</p>
 *
 * <p>用 AFTER_COMMIT 异步 listener 作废 (而非在 cancelOrder tx 内直接调 voidVoucher), 避免
 * voidVoucher 的内层 @Transactional 抛异常污染 cancelOrder 主事务 (doomed-tx)。</p>
 */
@Getter
public class SalesOrderCancelledEvent extends ApplicationEvent {

    private final String factoryId;
    private final String salesOrderId;
    private final String reason;
    private final LocalDateTime cancelledAt;

    public SalesOrderCancelledEvent(Object source, String factoryId, String salesOrderId, String reason) {
        super(source);
        this.factoryId = factoryId;
        this.salesOrderId = salesOrderId;
        this.reason = reason;
        this.cancelledAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return String.format("SalesOrderCancelledEvent[factoryId=%s, salesOrderId=%s, reason=%s]",
                factoryId, salesOrderId, reason);
    }
}
