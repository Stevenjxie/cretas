package com.cretas.aims.listener.inventory;

import com.cretas.aims.event.SalesOrderCancelledEvent;
import com.cretas.aims.service.inventory.FgReservationLedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * SO 取消 → 释放该 SO 的全部成品预留台账 (根治"取消不释放预留 → 永久孤儿")。
 *
 * <p>2026-07-06: 旧 {@code cancelOrder} 只翻订单状态 + 作废凭证, 从不释放
 * {@code finished_goods_batches.reserved_quantity} 里该 SO 占用的预留 → 预留成为孤儿,
 * 永久压低 available (F006 的 76 即此类残留)。本 listener 在取消事务<b>提交后</b>
 * (AFTER_COMMIT) 于<b>独立事务</b> (REQUIRES_NEW) 释放该 SO 的全部 ACTIVE 台账行,
 * 并相应削减各批次 reserved。
 *
 * <p>与 {@code SalesOrderVoucherListener.onSalesOrderCancelled} 同 event、同 AFTER_COMMIT /
 * REQUIRES_NEW 模式, 但独立 bean —— 预留释放失败绝不影响凭证作废, 反之亦然。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesOrderReservationReleaseListener {

    private final FgReservationLedgerService reservationLedgerService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSalesOrderCancelled(SalesOrderCancelledEvent event) {
        try {
            java.math.BigDecimal released =
                    reservationLedgerService.releaseAllForOrder(event.getSalesOrderId());
            if (released != null && released.signum() > 0) {
                log.info("✅ SO {} 取消 → 释放成品预留 {}", event.getSalesOrderId(), released);
            } else {
                log.debug("SO {} 取消, 无 ACTIVE 成品预留需释放", event.getSalesOrderId());
            }
        } catch (Exception e) {
            // AFTER_COMMIT 异步线程, 主取消事务已提交; 释放失败不重抛 (仅告警, 可由对账兜底)。
            log.error("SO {} 取消 → 释放预留失败: {}", event.getSalesOrderId(), e.getMessage(), e);
        }
    }
}
