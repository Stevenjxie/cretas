package com.cretas.aims.listener.voucher;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.event.TransferCreatedEvent;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.voucher.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * InternalTransfer 创建时 → 自动生成 INVENTORY_TRANSFER 凭证.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferVoucherListener {

    private final InternalTransferRepository internalTransferRepo;
    private final VoucherService voucherService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onTransferCreated(TransferCreatedEvent event) {
        try {
            handleVoucherGeneration(event.getSourceFactoryId(), event.getTransferId());
        } catch (Exception e) {
            log.error("Transfer voucher hook failed: transferId={}", event.getTransferId(), e);
        }
    }

    private void handleVoucherGeneration(String factoryId, String transferId) {
        InternalTransfer t = internalTransferRepo.findById(transferId).orElse(null);
        if (t == null) {
            log.warn("Transfer not found after created event: {}", transferId);
            return;
        }
        if (t.getVflag() != VoucherFlag.UNCREATED) {
            log.debug("Transfer {} vflag={}, skip", transferId, t.getVflag());
            return;
        }
        // 修 (2026-06-12, Codex gold 标): 0 值调拨 (totalAmount null/0) 无会计影响, 跳过凭证生成.
        // 否则 InventoryTransferVoucherGenerator.buildEntries 造 debit=0/credit=0 的 1405 双行 →
        // 违反 voucher_entries chk_ve_single_side (要求恰好一边非零) → 整凭证插入 batch abort →
        // vflag=FAILED + 错误日志噪音 (调入0值物料/无成本批次调拨时复现, AFTER_COMMIT 隔离不阻断调拨主流程).
        if (t.getTotalAmount() == null || t.getTotalAmount().signum() <= 0) {
            log.debug("Transfer {} totalAmount={} <=0, skip voucher (0 值调拨无会计影响)",
                    transferId, t.getTotalAmount());
            return;  // 保持 vflag UNCREATED — 无需凭证, 不重试
        }
        t.setVflag(VoucherFlag.PENDING);
        internalTransferRepo.save(t);
        try {
            voucherService.createFromBusiness(factoryId, "INTERNAL_TRANSFER", transferId);
            t.setVflag(VoucherFlag.CREATED);
        } catch (Exception e) {
            t.setVflag(VoucherFlag.FAILED);
            log.error("Voucher generation failed for Transfer {}", transferId, e);
        }
        internalTransferRepo.save(t);
    }
}
