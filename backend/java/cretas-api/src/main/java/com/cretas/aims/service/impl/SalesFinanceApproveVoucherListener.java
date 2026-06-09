package com.cretas.aims.service.impl;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * SP5: 财务审核通过 → 更新凭证标志 (vflag) 为 PENDING.
 *
 * <p>监听 {@link SalesOrderFinanceApprovedEvent}，将 SalesOrder.vflag 从 UNCREATED 更新为 PENDING，
 * 表示等待开票流程处理。
 *
 * <p>设计选择:
 * <ul>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)}: 财务审核事务提交后才触发，
 *       避免在父事务 rollback 时写入无效凭证状态。</li>
 *   <li>{@code @Async}: 凭证标志更新不阻塞财审主流程。</li>
 *   <li>幂等: 已是 CREATED 或 FAILED 状态时不重复更新。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesFinanceApproveVoucherListener {

    private final SalesOrderRepository salesOrderRepository;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFinanceApproved(SalesOrderFinanceApprovedEvent event) {
        String orderId = event.getSalesOrderId();
        Optional<SalesOrder> orderOpt = salesOrderRepository.findById(orderId);
        if (orderOpt.isEmpty()) {
            log.warn("SP5 SalesFinanceApproveVoucherListener: order {} not found, skipped", orderId);
            return;
        }

        SalesOrder order = orderOpt.get();

        // 幂等: 已经是 CREATED / FAILED 状态，不重复更新
        VoucherFlag currentFlag = order.getVflag();
        if (currentFlag == VoucherFlag.CREATED || currentFlag == VoucherFlag.FAILED) {
            log.debug("SP5 VoucherListener: order {} already in terminal state {}, skipped",
                    orderId, currentFlag);
            return;
        }

        order.setVflag(VoucherFlag.PENDING);
        salesOrderRepository.save(order);
        log.info("SP5 VoucherListener: order {} vflag set to PENDING (finance approved, factoryId={})",
                orderId, event.getFactoryId());
    }
}
