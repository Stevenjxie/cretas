package com.cretas.aims.listener.voucher;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.event.ReturnOrderCreatedEvent;
import com.cretas.aims.event.ReturnOrderRejectedEvent;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.voucher.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * ReturnOrder 创建时 → 自动生成 RETURN 凭证 (反向冲销原销售).
 *
 * <p>Bug 9 修复 (2026-07-04): 新增 {@link #onReturnOrderRejected} — 退货单驳回时作废对应凭证,
 * 消除被驳回退货的幽灵冲销凭证。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnOrderVoucherListener {

    private final ReturnOrderRepository returnOrderRepo;
    private final VoucherService voucherService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onReturnOrderCreated(ReturnOrderCreatedEvent event) {
        try {
            handleVoucherGeneration(event.getFactoryId(), event.getReturnOrderId());
        } catch (Exception e) {
            log.error("ReturnOrder voucher hook failed: orderId={}", event.getReturnOrderId(), e);
        }
    }

    /**
     * Bug 9 修复: 退货单驳回 (rejectReturnOrder / financeRejectReturnOrder) → 作废其 RETURN 凭证。
     *
     * <p>幂等 + fail-soft: 无凭证 / 已 VOID / 已 REVERSED → 静默跳过; voidVoucher 异常仅日志告警不重抛。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onReturnOrderRejected(ReturnOrderRejectedEvent event) {
        try {
            Optional<Voucher> voucherOpt = voucherService.findBySourceBusiness("RETURN_ORDER", event.getReturnOrderId());
            if (voucherOpt.isEmpty()) {
                log.debug("RO {} 驳回, 无 RETURN 凭证需作废", event.getReturnOrderId());
                return;
            }
            Voucher voucher = voucherOpt.get();
            if (voucher.getStatus() == VoucherStatus.VOID || voucher.getStatus() == VoucherStatus.REVERSED) {
                log.debug("RO {} 凭证 {} 已是终态 {}, 跳过作废",
                        event.getReturnOrderId(), voucher.getVoucherNumber(), voucher.getStatus());
                return;
            }
            voucherService.voidVoucher(event.getFactoryId(), voucher.getId(), "退货单驳回自动作废", null);
            log.info("✅ RO {} 驳回 → 凭证 {} 已作废", event.getReturnOrderId(), voucher.getVoucherNumber());
        } catch (Exception e) {
            log.error("RO {} 驳回作废凭证失败 (不影响驳回主流程, 财务可手动作废): {}",
                    event.getReturnOrderId(), e.getMessage(), e);
        }
    }

    private void handleVoucherGeneration(String factoryId, String returnOrderId) {
        ReturnOrder r = returnOrderRepo.findById(returnOrderId).orElse(null);
        if (r == null) {
            log.warn("ReturnOrder not found after created event: {}", returnOrderId);
            return;
        }
        if (r.getVflag() != VoucherFlag.UNCREATED) {
            log.debug("RO {} vflag={}, skip", returnOrderId, r.getVflag());
            return;
        }
        r.setVflag(VoucherFlag.PENDING);
        returnOrderRepo.save(r);
        try {
            voucherService.createFromBusiness(factoryId, "RETURN_ORDER", returnOrderId);
            r.setVflag(VoucherFlag.CREATED);
        } catch (Exception e) {
            r.setVflag(VoucherFlag.FAILED);
            log.error("Voucher generation failed for RO {}", returnOrderId, e);
        }
        returnOrderRepo.save(r);
    }
}
