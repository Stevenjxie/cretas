package com.cretas.aims.service.finance.impl;

import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.event.CashMovementRecordedEvent;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 资金段现金流水凭证监听器测试 (finance audit Bug 5).
 *
 * <p>验证: 收款 (借 1002/贷 1122 + 客户辅助核算) / 付款 (借 2202/贷 1002 + 供应商辅助核算) /
 * 现金方式 → 1001 库存现金 / 幂等键 (sourceType=类型名, sourceId=txnId) / fail-soft。
 */
@ExtendWith(MockitoExtension.class)
class CashMovementVoucherListenerTest {

    @Mock private VoucherService voucherService;

    @InjectMocks private CashMovementVoucherListener listener;

    @SuppressWarnings("unchecked")
    private List<VoucherEntry> captureEntries() {
        ArgumentCaptor<List<VoucherEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(voucherService).createCashMovementVoucher(anyString(), any(), any(),
                captor.capture(), anyString(), anyString(), anyString(), any());
        return captor.getValue();
    }

    private VoucherEntry entryByCode(List<VoucherEntry> entries, String code) {
        return entries.stream().filter(e -> code.equals(e.getSubjectCode())).findFirst().orElseThrow();
    }

    @Test
    void receipt_bankTransfer_debit1002_credit1122_customerAux() {
        CashMovementRecordedEvent event = new CashMovementRecordedEvent(this, "F001", "txn-1",
                VoucherType.CASH_RECEIPT, "cust-1", "客户A", new BigDecimal("120.00"),
                PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 5, 16), 300L);

        listener.onCashMovement(event);

        List<VoucherEntry> entries = captureEntries();
        assertEquals(2, entries.size());
        VoucherEntry cash = entryByCode(entries, "1002");
        assertEquals(new BigDecimal("120.00"), cash.getDebit());
        assertEquals(BigDecimal.ZERO, cash.getCredit());
        VoucherEntry ar = entryByCode(entries, "1122");
        assertEquals(new BigDecimal("120.00"), ar.getCredit());
        assertEquals(BigDecimal.ZERO, ar.getDebit());
        assertEquals(AuxiliaryType.CUSTOMER, ar.getAuxiliaryType());
        assertEquals("cust-1", ar.getAuxiliaryEntityId());
        // 幂等键 + 类型
        verify(voucherService).createCashMovementVoucher(eq("F001"), eq(VoucherType.CASH_RECEIPT),
                eq(LocalDate.of(2026, 5, 16)), anyList(), eq("CASH_RECEIPT"), eq("txn-1"), anyString(), eq(300L));
    }

    @Test
    void receipt_cashMethod_usesCode1001() {
        CashMovementRecordedEvent event = new CashMovementRecordedEvent(this, "F001", "txn-1",
                VoucherType.CASH_RECEIPT, "cust-1", "客户A", new BigDecimal("80.00"),
                PaymentMethod.CASH, LocalDate.of(2026, 5, 16), 300L);

        listener.onCashMovement(event);

        List<VoucherEntry> entries = captureEntries();
        assertNotNull(entryByCode(entries, "1001"), "现金方式应记 1001 库存现金");
        assertEquals(new BigDecimal("80.00"), entryByCode(entries, "1001").getDebit());
    }

    @Test
    void payment_debit2202_credit1002_supplierAux() {
        CashMovementRecordedEvent event = new CashMovementRecordedEvent(this, "F001", "txn-2",
                VoucherType.CASH_PAYMENT, "sup-1", "供应商B", new BigDecimal("300.00"),
                PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 5, 16), 300L);

        listener.onCashMovement(event);

        List<VoucherEntry> entries = captureEntries();
        VoucherEntry ap = entryByCode(entries, "2202");
        assertEquals(new BigDecimal("300.00"), ap.getDebit());
        assertEquals(AuxiliaryType.SUPPLIER, ap.getAuxiliaryType());
        assertEquals("sup-1", ap.getAuxiliaryEntityId());
        VoucherEntry cash = entryByCode(entries, "1002");
        assertEquals(new BigDecimal("300.00"), cash.getCredit());
        verify(voucherService).createCashMovementVoucher(eq("F001"), eq(VoucherType.CASH_PAYMENT),
                any(), anyList(), eq("CASH_PAYMENT"), eq("txn-2"), anyString(), any());
    }

    @Test
    void nonPositiveAmount_skipsWithoutVoucher() {
        CashMovementRecordedEvent event = new CashMovementRecordedEvent(this, "F001", "txn-3",
                VoucherType.CASH_RECEIPT, "cust-1", "客户A", BigDecimal.ZERO,
                PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 5, 16), 300L);

        listener.onCashMovement(event);

        verifyNoInteractions(voucherService);
    }

    @Test
    void voucherServiceThrows_failSoftNoPropagation() {
        when(voucherService.createCashMovementVoucher(anyString(), any(), any(), anyList(),
                anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("CHECK violation simulate"));
        CashMovementRecordedEvent event = new CashMovementRecordedEvent(this, "F001", "txn-4",
                VoucherType.CASH_RECEIPT, "cust-1", "客户A", new BigDecimal("50.00"),
                PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 5, 16), 300L);

        // fail-soft: 不抛出 (AFTER_COMMIT 抛异常无法回滚已提交收付款)
        assertDoesNotThrow(() -> listener.onCashMovement(event));
    }
}
