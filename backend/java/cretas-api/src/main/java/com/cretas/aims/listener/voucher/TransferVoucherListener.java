package com.cretas.aims.listener.voucher;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.event.TransferCreatedEvent;
import com.cretas.aims.event.TransferTerminatedEvent;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.voucher.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * InternalTransfer 创建时 → 自动生成 INVENTORY_TRANSFER 凭证.
 *
 * <p>2026-08-09 修复 (六膳门 TRF-20260809-1790 善后): 新增 {@link #onTransferTerminated} ——
 * 调拨单取消/驳回时作废对应凭证。此前凭证在<b>创建</b>时就生成 (草稿阶段, 库存一分没动), 而
 * cancelTransfer/rejectTransfer 只翻状态不回收凭证 → 账上留着对应不到实物流的内部调拨凭证。
 * 与销售侧 2026-07-04 Bug 3 同形状, 修法照抄 {@code SalesOrderVoucherListener}。
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

    /**
     * 调拨单取消 / 驳回 → 作废其 INVENTORY_TRANSFER 凭证 (若存在)。
     *
     * <p>幂等 + fail-soft: 无凭证 / 已 VOID / 已 REVERSED → 静默跳过; voidVoucher 异常仅日志告警,
     * 不重抛 (AFTER_COMMIT 异步线程, 终止事务已提交, 重抛也回滚不了)。凭证生成在创建阶段, 正常
     * 终止时应仍是 DRAFT → 直接 VOID; 万一已 POSTED → voidVoucher 内部转红字冲销。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onTransferTerminated(TransferTerminatedEvent event) {
        try {
            Optional<Voucher> voucherOpt = voucherService.findBySourceBusiness(
                    "INTERNAL_TRANSFER", event.getTransferId());
            if (voucherOpt.isEmpty()) {
                log.debug("调拨单 {} 已{}, 无 INVENTORY_TRANSFER 凭证需作废",
                        event.getTransferId(), event.getTerminalStatus());
                return;
            }
            Voucher voucher = voucherOpt.get();
            if (voucher.getStatus() == VoucherStatus.VOID || voucher.getStatus() == VoucherStatus.REVERSED) {
                log.debug("调拨单 {} 的凭证 {} 已是终态 {}, 跳过作废",
                        event.getTransferId(), voucher.getVoucherNumber(), voucher.getStatus());
                return;
            }
            String label = "REJECTED".equals(event.getTerminalStatus()) ? "驳回" : "取消";
            String reason = "调拨单" + label + "自动作废"
                    + (event.getReason() == null || event.getReason().isBlank()
                        ? "" : " (" + event.getReason() + ")");
            voucherService.voidVoucher(event.getFactoryId(), voucher.getId(), reason, null);
            log.info("✅ 调拨单 {} {} → 凭证 {} 已作废",
                    event.getTransferId(), label, voucher.getVoucherNumber());
        } catch (Exception e) {
            log.error("调拨单 {} 终止后作废凭证失败 (不影响终止主流程, 财务可手动作废): {}",
                    event.getTransferId(), e.getMessage(), e);
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
