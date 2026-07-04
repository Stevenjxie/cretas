package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * 退货单驳回事件 (2026-07-04, Bug 9 修复) — 由 ReturnOrderServiceImpl 的 rejectReturnOrder /
 * financeRejectReturnOrder 发布。
 *
 * <p><b>为什么新增</b>: RETURN 凭证在退货单 DRAFT 创建时即生成, 但驳回 (审批前/财审驳回) 旧实现
 * 只翻状态到 REJECTED, 从不作废凭证 → 被驳回退货的 RETURN 凭证 (反向冲销原销售) 成为幽灵。
 * 本事件驱动 {@code ReturnOrderVoucherListener} 在退货单驳回 (tx 提交) 后作废对应凭证。</p>
 *
 * <p>用 AFTER_COMMIT 异步 listener 作废, 避免 voidVoucher 内层事务污染驳回主事务 (doomed-tx)。</p>
 */
@Getter
public class ReturnOrderRejectedEvent extends ApplicationEvent {

    private final String factoryId;
    private final String returnOrderId;
    private final LocalDateTime rejectedAt;

    public ReturnOrderRejectedEvent(Object source, String factoryId, String returnOrderId) {
        super(source);
        this.factoryId = factoryId;
        this.returnOrderId = returnOrderId;
        this.rejectedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return String.format("ReturnOrderRejectedEvent[factoryId=%s, returnOrderId=%s]",
                factoryId, returnOrderId);
    }
}
