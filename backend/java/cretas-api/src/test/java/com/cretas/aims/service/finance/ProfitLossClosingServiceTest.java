package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.enums.AccountBalanceType;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.service.finance.impl.ProfitLossClosingServiceImpl;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProfitLossClosingServiceTest {

    @Mock VoucherService voucherService;
    @Mock VoucherEntryRepository voucherEntryRepo;
    @Mock AccountRepository accountRepo;
    @Mock com.cretas.aims.repository.VoucherRepository voucherRepo;
    @InjectMocks ProfitLossClosingServiceImpl service;

    @Captor ArgumentCaptor<List<VoucherEntrySpec>> entriesCap;

    private SubjectAggregateRow row(String code, String name, String debit, String credit) {
        return SubjectAggregateRow.builder().subjectCode(code).subjectName(name)
                .totalDebit(new BigDecimal(debit)).totalCredit(new BigDecimal(credit)).entryCount(1L).build();
    }
    private Account acc(String code, String name, AccountCategory cat) {
        return Account.builder().code(code).name(name).category(cat)
                .balanceType(cat == AccountCategory.REVENUE ? AccountBalanceType.CREDIT_NORMAL : AccountBalanceType.DEBIT_NORMAL)
                .build();
    }

    @Test
    void closePeriod_profit_movesNetToRetainedEarnings() {
        // 收入1000(贷) - 成本600(借) - 费用200(借) = 净利200 → 4103 贷200
        when(voucherEntryRepo.aggregateBySubjectPosted(eq("F006"), any(), any())).thenReturn(List.of(
                row("6001", "主营业务收入", "0.00", "1000.00"),
                row("6401", "主营业务成本", "600.00", "0.00"),
                row("6601", "销售费用", "200.00", "0.00")));
        when(accountRepo.findVisibleToFactory("F006")).thenReturn(List.of(
                acc("6001", "主营业务收入", AccountCategory.REVENUE),
                acc("6401", "主营业务成本", AccountCategory.COST),
                acc("6601", "销售费用", AccountCategory.EXPENSE)));
        when(voucherService.createManual(any(), any(), any(), anyList(), any(), any(), any(), any()))
                .thenReturn(Voucher.builder().id("v1").build());

        service.closePeriod("F006", 2026, 5, 1309L);

        verify(voucherService).createManual(eq("F006"), eq(VoucherType.PL_CLOSING),
                eq(LocalDate.of(2026, 5, 31)), entriesCap.capture(),
                eq("PL_CLOSING"), contains("closing-F006-2026-5"), any(), eq(1309L));
        List<VoucherEntrySpec> es = entriesCap.getValue();
        BigDecimal r4103Debit = es.stream().filter(e -> e.subjectCode().equals("4103"))
                .map(e -> e.debit() == null ? BigDecimal.ZERO : e.debit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal r4103Credit = es.stream().filter(e -> e.subjectCode().equals("4103"))
                .map(e -> e.credit() == null ? BigDecimal.ZERO : e.credit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("200.00").compareTo(r4103Credit.subtract(r4103Debit)), "4103 净贷=净利200");
        BigDecimal d = es.stream().map(e -> e.debit()==null?BigDecimal.ZERO:e.debit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal c = es.stream().map(e -> e.credit()==null?BigDecimal.ZERO:e.credit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, d.compareTo(c), "借贷平");
    }

    @Test
    void closePeriod_doesNotClose5xxxWip() {
        when(voucherEntryRepo.aggregateBySubjectPosted(eq("F006"), any(), any())).thenReturn(List.of(
                row("6001", "主营业务收入", "0.00", "1000.00"),
                row("5001", "生产成本", "300.00", "0.00")));
        when(accountRepo.findVisibleToFactory("F006")).thenReturn(List.of(
                acc("6001", "主营业务收入", AccountCategory.REVENUE),
                acc("5001", "生产成本", AccountCategory.COST)));
        when(voucherService.createManual(any(), any(), any(), anyList(), any(), any(), any(), any()))
                .thenReturn(Voucher.builder().id("v1").build());

        service.closePeriod("F006", 2026, 5, 1309L);

        verify(voucherService).createManual(any(), any(), any(), entriesCap.capture(), any(), any(), any(), any());
        boolean has5001 = entriesCap.getValue().stream().anyMatch(e -> e.subjectCode().startsWith("5"));
        assertFalse(has5001, "5xxx WIP 不结转");
    }

    @Test
    void closePeriod_lossMonth_4103Debit() {
        when(voucherEntryRepo.aggregateBySubjectPosted(eq("F006"), any(), any())).thenReturn(List.of(
                row("6001", "主营业务收入", "0.00", "500.00"),
                row("6401", "主营业务成本", "800.00", "0.00")));
        when(accountRepo.findVisibleToFactory("F006")).thenReturn(List.of(
                acc("6001", "主营业务收入", AccountCategory.REVENUE),
                acc("6401", "主营业务成本", AccountCategory.COST)));
        when(voucherService.createManual(any(), any(), any(), anyList(), any(), any(), any(), any()))
                .thenReturn(Voucher.builder().id("v1").build());

        service.closePeriod("F006", 2026, 5, 1309L);

        verify(voucherService).createManual(any(), any(), any(), entriesCap.capture(), any(), any(), any(), any());
        List<VoucherEntrySpec> es = entriesCap.getValue();
        BigDecimal r4103Debit = es.stream().filter(e -> e.subjectCode().equals("4103"))
                .map(e -> e.debit()==null?BigDecimal.ZERO:e.debit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal r4103Credit = es.stream().filter(e -> e.subjectCode().equals("4103"))
                .map(e -> e.credit()==null?BigDecimal.ZERO:e.credit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("300.00").compareTo(r4103Debit.subtract(r4103Credit)), "4103 净借=亏损300");
    }

    @Test
    void closePeriod_noPnl_noOp() {
        when(voucherEntryRepo.aggregateBySubjectPosted(eq("F006"), any(), any())).thenReturn(List.of());
        when(accountRepo.findVisibleToFactory("F006")).thenReturn(List.of());
        service.closePeriod("F006", 2026, 5, 1309L);
        verify(voucherService, never()).createManual(any(), any(), any(), anyList(), any(), any(), any(), any());
    }

    @Test
    void reversePeriodClosing_redReversesActiveBatch() {
        Voucher active = Voucher.builder().id("vc1").factoryId("F006").build();
        when(voucherRepo.findActiveClosingVouchers(eq("F006"), contains("closing-F006-2026-5-monthly-r")))
                .thenReturn(List.of(active));

        service.reversePeriodClosing("F006", 2026, 5, 1309L);

        // voidVoucher 对 POSTED 凭证执行红字冲销
        verify(voucherService).voidVoucher("F006", "vc1", "反结账自动红冲", 1309L);
    }

    @Test
    void reversePeriodClosing_noActive_noOp() {
        when(voucherRepo.findActiveClosingVouchers(eq("F006"), anyString())).thenReturn(List.of());
        service.reversePeriodClosing("F006", 2026, 5, 1309L);
        verifyNoInteractions(voucherService);
    }
}
