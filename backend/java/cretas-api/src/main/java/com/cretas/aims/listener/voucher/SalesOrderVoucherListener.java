package com.cretas.aims.listener.voucher;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.event.SalesOrderCancelledEvent;
import com.cretas.aims.event.SalesOrderConfirmedEvent;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.voucher.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * SalesOrder 审批 confirmed → 自动生成 SALES_RECEIPT 凭证.
 * AFTER_COMMIT 保证业务 tx 已提交后再触发, 避免读到未提交数据.
 * @Async 保证不阻塞业务 tx 主线.
 *
 * <p>Bug 3 修复 (2026-07-04): 新增 {@link #onSalesOrderCancelled} — 订单取消时作废对应凭证,
 * 消除幽灵营收/应收。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesOrderVoucherListener {

    private final SalesOrderRepository salesOrderRepo;
    private final VoucherService voucherService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onSalesOrderConfirmed(SalesOrderConfirmedEvent event) {
        try {
            handleVoucherGeneration(event.getFactoryId(), event.getSalesOrderId());
        } catch (Exception e) {
            log.error("SalesOrder voucher hook failed: orderId={}", event.getSalesOrderId(), e);
        }
    }

    /**
     * Bug 3 修复: 销售订单取消 → 作废其 SALES_RECEIPT 凭证 (若存在)。
     *
     * <p>幂等 + fail-soft: 无凭证 / 已 VOID / 已 REVERSED → 静默跳过; voidVoucher 异常仅日志告警,
     * 不重抛 (AFTER_COMMIT 异步线程, 主取消事务已提交)。DRAFT 凭证 → 直接 VOID; 万一已 POSTED
     * (防御, 正常取消白名单不含 FINANCE_APPROVED+ 故凭证应仍 DRAFT) → voidVoucher 内部转红字冲销。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onSalesOrderCancelled(SalesOrderCancelledEvent event) {
        try {
            Optional<Voucher> voucherOpt = voucherService.findBySourceBusiness("SALES_ORDER", event.getSalesOrderId());
            if (voucherOpt.isEmpty()) {
                log.debug("SO {} cancelled, 无 SALES_RECEIPT 凭证需作废", event.getSalesOrderId());
                return;
            }
            Voucher voucher = voucherOpt.get();
            if (voucher.getStatus() == VoucherStatus.VOID || voucher.getStatus() == VoucherStatus.REVERSED) {
                log.debug("SO {} 凭证 {} 已是终态 {}, 跳过作废",
                        event.getSalesOrderId(), voucher.getVoucherNumber(), voucher.getStatus());
                return;
            }
            String reason = "销售订单取消自动作废"
                    + (event.getReason() == null || event.getReason().isBlank() ? "" : " (" + event.getReason() + ")");
            voucherService.voidVoucher(event.getFactoryId(), voucher.getId(), reason, null);
            log.info("✅ SO {} 取消 → 凭证 {} 已作废", event.getSalesOrderId(), voucher.getVoucherNumber());
        } catch (Exception e) {
            log.error("SO {} 取消作废凭证失败 (不影响取消主流程, 财务可手动作废): {}",
                    event.getSalesOrderId(), e.getMessage(), e);
        }
    }

    private void handleVoucherGeneration(String factoryId, String salesOrderId) {
        SalesOrder so = salesOrderRepo.findById(salesOrderId).orElse(null);
        if (so == null) {
            log.warn("SO not found after confirmed event: {}", salesOrderId);
            return;
        }
        if (so.getVflag() != VoucherFlag.UNCREATED) {
            log.debug("SO {} vflag={}, skip voucher generation", salesOrderId, so.getVflag());
            return;
        }
        so.setVflag(VoucherFlag.PENDING);
        salesOrderRepo.save(so);
        try {
            voucherService.createFromBusiness(factoryId, "SALES_ORDER", salesOrderId);
            so.setVflag(VoucherFlag.CREATED);
        } catch (Exception e) {
            so.setVflag(VoucherFlag.FAILED);
            log.error("Voucher generation failed for SO {}", salesOrderId, e);
        }
        salesOrderRepo.save(so);
    }
}
