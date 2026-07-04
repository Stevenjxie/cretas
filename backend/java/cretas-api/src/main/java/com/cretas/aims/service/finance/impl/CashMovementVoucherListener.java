package com.cretas.aims.service.finance.impl;

import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.event.CashMovementRecordedEvent;
import com.cretas.aims.service.voucher.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 资金段 GL 打通 (finance audit Bug 5) — 收款/付款 现金流水凭证生成监听器。
 *
 * <p>监听 {@link CashMovementRecordedEvent} (由 {@code ArApServiceImpl.recordArPayment} /
 * {@code recordApPayment} 发布), 生成:
 * <ul>
 *   <li><b>收款</b> (CASH_RECEIPT): 借 1002 银行存款 (或 1001 库存现金, 按 paymentMethod)
 *       / 贷 1122 应收账款 [客户辅助核算] = 收款金额。冲减 SALES_RECEIPT 时借的 1122 —— 正确对冲,
 *       非双计。</li>
 *   <li><b>付款</b> (CASH_PAYMENT): 借 2202 应付账款 [供应商辅助核算] / 贷 1002 银行存款
 *       (或 1001 库存现金) = 付款金额。冲减 PURCHASE_PAYMENT 时贷的 2202。</li>
 * </ul>
 *
 * <p>设计 (镜像 {@code SalesFinanceApproveVoucherListener} #1225 范式):
 * <ul>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)}: 收付款事务提交后才生成凭证 —
 *       凭证 insert 异常 (如未预期的 CHECK/期间 gate) 不会 doom 已提交的收付款事务。</li>
 *   <li>{@code @Async}: 不阻塞收付款响应。</li>
 *   <li>fail-soft: 任何异常仅告警不抛出 (AFTER_COMMIT 抛异常无法回滚已提交事务, 反污染异步线程)。
 *       生成失败 → GL 与子账漂移, 由月结 {@code MonthCloseServiceImpl} 的子账↔总账对账 WARNING 暴露。</li>
 *   <li>幂等: {@code createCashMovementVoucher} 按 (sourceType, arApTransactionId) 去重, 重投不重复生成。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CashMovementVoucherListener {

    private final VoucherService voucherService;

    private static final String CASH_BANK_CODE = "1002";
    private static final String CASH_BANK_NAME = "银行存款";
    private static final String CASH_ON_HAND_CODE = "1001";
    private static final String CASH_ON_HAND_NAME = "库存现金";
    private static final String AR_CODE = "1122";
    private static final String AR_NAME = "应收账款";
    private static final String AP_CODE = "2202";
    private static final String AP_NAME = "应付账款";

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCashMovement(CashMovementRecordedEvent event) {
        try {
            BigDecimal amount = event.getAmount();
            if (amount == null || amount.signum() <= 0) {
                log.warn("现金流水凭证跳过 (金额非正, honest-null): txnId={}, amount={}",
                        event.getArApTransactionId(), amount);
                return;
            }
            VoucherType type = event.getVoucherType();
            String cpName = event.getCounterpartyName() != null ? event.getCounterpartyName() : "";
            boolean cashOnHand = event.getPaymentMethod() == PaymentMethod.CASH;
            String cashCode = cashOnHand ? CASH_ON_HAND_CODE : CASH_BANK_CODE;
            String cashName = cashOnHand ? CASH_ON_HAND_NAME : CASH_BANK_NAME;

            List<VoucherEntry> entries = new ArrayList<>(2);
            String description;

            if (type == VoucherType.CASH_RECEIPT) {
                // 借 现金 / 贷 1122 应收账款 (客户辅助核算)
                entries.add(VoucherEntry.builder()
                        .subjectCode(cashCode).subjectName(cashName)
                        .debit(amount).credit(BigDecimal.ZERO)
                        .description("收款-" + cpName)
                        .build());
                entries.add(withCustomer(VoucherEntry.builder()
                        .subjectCode(AR_CODE).subjectName(AR_NAME)
                        .debit(BigDecimal.ZERO).credit(amount)
                        .description("冲减应收-" + cpName)
                        .build(), event.getCounterpartyId()));
                description = "收款 " + cpName;
            } else if (type == VoucherType.CASH_PAYMENT) {
                // 借 2202 应付账款 (供应商辅助核算) / 贷 现金
                entries.add(withSupplier(VoucherEntry.builder()
                        .subjectCode(AP_CODE).subjectName(AP_NAME)
                        .debit(amount).credit(BigDecimal.ZERO)
                        .description("冲减应付-" + cpName)
                        .build(), event.getCounterpartyId()));
                entries.add(VoucherEntry.builder()
                        .subjectCode(cashCode).subjectName(cashName)
                        .debit(BigDecimal.ZERO).credit(amount)
                        .description("付款-" + cpName)
                        .build());
                description = "付款 " + cpName;
            } else {
                log.warn("现金流水凭证跳过 (非 CASH_RECEIPT/CASH_PAYMENT 类型): txnId={}, type={}",
                        event.getArApTransactionId(), type);
                return;
            }

            voucherService.createCashMovementVoucher(
                    event.getFactoryId(), type, event.getVoucherDate(), entries,
                    type.name(), event.getArApTransactionId(), description, event.getOperatedBy());
        } catch (Exception e) {
            // fail-soft: 不抛出 (AFTER_COMMIT 抛异常无法回滚已提交收付款, 且污染 @Async 线程)。
            // GL 漂移由月结子账↔总账对账 WARNING 暴露, 财务可后续手动补凭证。
            log.error("现金流水凭证生成失败 (fail-soft, 不影响收付款; 月结对账会暴露漂移): txnId={}: {}",
                    event.getArApTransactionId(), e.getMessage(), e);
        }
    }

    /** honest-null: counterpartyId 为 null 则不附辅助核算 (不造假)。 */
    private VoucherEntry withCustomer(VoucherEntry entry, String customerId) {
        if (customerId != null) {
            entry.setAuxiliaryType(AuxiliaryType.CUSTOMER);
            entry.setAuxiliaryEntityId(customerId);
        }
        return entry;
    }

    private VoucherEntry withSupplier(VoucherEntry entry, String supplierId) {
        if (supplierId != null) {
            entry.setAuxiliaryType(AuxiliaryType.SUPPLIER);
            entry.setAuxiliaryEntityId(supplierId);
        }
        return entry;
    }
}
