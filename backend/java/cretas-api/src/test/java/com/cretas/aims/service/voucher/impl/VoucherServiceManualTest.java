package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.repository.PayrollRecordRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import com.cretas.aims.service.LinkArrayService;
import com.cretas.aims.service.finance.AccountingPeriodService;
import com.cretas.aims.service.voucher.VoucherGeneratorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoucherServiceManualTest {

    @Mock VoucherRepository voucherRepo;
    @Mock VoucherGeneratorRegistry registry;
    @Mock LinkArrayService linkArrayService;
    @Mock SalesOrderRepository salesOrderRepo;
    @Mock PurchaseOrderRepository purchaseOrderRepo;
    @Mock ReturnOrderRepository returnOrderRepo;
    @Mock InternalTransferRepository internalTransferRepo;
    @Mock WastageRecordRepository wastageRecordRepo;
    @Mock PayrollRecordRepository payrollRecordRepo;
    @Mock ProductionPlanRepository productionPlanRepo;
    @Mock AccountingPeriodService accountingPeriodService;

    @InjectMocks VoucherServiceImpl service;

    @Test
    void createManual_buildsBalancedPostedVoucher_bypassesPeriodGate() {
        when(voucherRepo.countByFactoryIdAndYear("F006", "2026")).thenReturn(0L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(i -> i.getArgument(0));

        List<VoucherEntrySpec> entries = List.of(
                new VoucherEntrySpec("6001", "主营业务收入", new BigDecimal("1000.00"), null, "结转收入"),
                new VoucherEntrySpec("4103", "本年利润", null, new BigDecimal("1000.00"), "转入本年利润"));

        Voucher v = service.createManual("F006", VoucherType.PL_CLOSING,
                LocalDate.of(2026, 5, 31), entries,
                "PL_CLOSING", "closing-F006-2026-5-monthly-r0", "5月结转损益", 1309L);

        assertEquals(VoucherStatus.POSTED, v.getStatus());
        assertEquals(0, new BigDecimal("1000.00").compareTo(v.getTotalDebit()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(v.getTotalCredit()));
        assertEquals(2, v.getEntries().size());
        assertEquals(Long.valueOf(1309L), v.getCreatedBy());
        assertEquals("V-2026-0001", v.getVoucherNumber());
        v.validateBalanced();
        verify(accountingPeriodService, never()).assertOpen(any(), any(), any());
    }

    @Test
    void createManual_unbalanced_throws() {
        when(voucherRepo.countByFactoryIdAndYear("F006", "2026")).thenReturn(0L);
        List<VoucherEntrySpec> entries = List.of(
                new VoucherEntrySpec("6001", "主营业务收入", new BigDecimal("1000.00"), null, null),
                new VoucherEntrySpec("4103", "本年利润", null, new BigDecimal("900.00"), null));
        assertThrows(RuntimeException.class, () -> service.createManual("F006", VoucherType.PL_CLOSING,
                LocalDate.of(2026, 5, 31), entries, "PL_CLOSING", "x", "d", 1L));
    }
}
