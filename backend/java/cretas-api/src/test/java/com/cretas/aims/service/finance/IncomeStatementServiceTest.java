package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.report.IncomeStatementDTO;
import com.cretas.aims.entity.enums.AccountBalanceType;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Sprint 7 T3 IncomeStatementService unit tests.
 *
 * <p>覆盖:
 * <ul>
 *   <li>正常 case: 收入 - 成本 - 费用 - 所得税 = 净利润</li>
 *   <li>毛利 / 营业利润 / 净利润 4 层正确计算</li>
 *   <li>所得税识别 (name 含 "所得税")</li>
 *   <li>资产/负债/权益科目应被排除</li>
 *   <li>Legacy entry: 5/6xxx prefix fallback REVENUE/COST</li>
 *   <li>空数据: 全部 0, 净利润 0</li>
 *   <li>负净利润 (亏损)</li>
 *   <li>非法输入 (month 越界 / startDate > endDate)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class IncomeStatementServiceTest {

    @Mock
    private VoucherEntryRepository voucherEntryRepo;

    @Mock
    private VoucherRepository voucherRepo;

    @Mock
    private AccountRepository accountRepo;

    @InjectMocks
    private IncomeStatementService service;

    private static final String FACTORY = "F006";
    private static final int START_YEAR = 2026;
    private static final int START_MONTH = 1;
    private static final int END_YEAR = 2026;
    private static final int END_MONTH = 5;

    private Account revenueAccount;
    private Account costAccount;
    private Account expenseAccount;
    private Account incomeTaxAccount;
    private Account cashAccount;  // 应被排除

    @BeforeEach
    void setUp() {
        revenueAccount = Account.builder()
                .id("acc-5001").code("5001").name("主营业务收入")
                .category(AccountCategory.REVENUE)
                .balanceType(AccountBalanceType.CREDIT_NORMAL)
                .build();
        costAccount = Account.builder()
                .id("acc-5401").code("5401").name("主营业务成本")
                .category(AccountCategory.COST)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        expenseAccount = Account.builder()
                .id("acc-6601").code("6601").name("管理费用")
                .category(AccountCategory.EXPENSE)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        incomeTaxAccount = Account.builder()
                .id("acc-6801").code("6801").name("所得税费用")
                .category(AccountCategory.EXPENSE)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        cashAccount = Account.builder()
                .id("acc-1001").code("1001").name("库存现金")
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
    }

    @Test
    void generate_normalProfitable_returnsCorrectFourLevels() {
        // 收入 100,000 / 成本 60,000 / 费用 10,000 / 所得税 8,000
        // 毛利 = 100k - 60k = 40k
        // 营业利润 = 40k - 18k = 22k (注: totalExpense 含 income tax)
        // 净利润 = 22k - 8k = 14k
        // 注意: totalExpense 包含所得税 (因为所得税 category=EXPENSE), 但所得税也单独拆出
        List<SubjectAggregateRow> aggregates = Arrays.asList(
                row("5001", "主营业务收入", "0.00", "100000.00"),  // credit 100k
                row("5401", "主营业务成本", "60000.00", "0.00"),    // debit 60k
                row("6601", "管理费用", "10000.00", "0.00"),         // debit 10k
                row("6801", "所得税费用", "8000.00", "0.00")          // debit 8k (含在 totalExpense 内)
        );
        when(voucherEntryRepo.aggregateBySubjectExcludingDraftAndClosing(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(aggregates);
        when(accountRepo.findVisibleToFactory(FACTORY))
                .thenReturn(Arrays.asList(revenueAccount, costAccount, expenseAccount, incomeTaxAccount));

        IncomeStatementDTO dto = service.generate(FACTORY, START_YEAR, START_MONTH, END_YEAR, END_MONTH);

        assertEquals(new BigDecimal("100000.00"), dto.getTotalRevenue());
        assertEquals(new BigDecimal("60000.00"), dto.getTotalCost());
        assertEquals(new BigDecimal("40000.00"), dto.getGrossProfit());

        assertEquals(new BigDecimal("18000.00"), dto.getTotalExpense(),
                "totalExpense = 管理费用 10000 + 所得税 8000 = 18000");
        assertEquals(new BigDecimal("22000.00"), dto.getOperatingProfit(),
                "毛利 40000 - 总费用 18000 = 22000");

        assertEquals(new BigDecimal("8000.00"), dto.getIncomeTax());
        assertEquals(new BigDecimal("14000.00"), dto.getNetProfit(),
                "营业利润 22000 - 所得税 8000 = 14000");

        assertEquals(1, dto.getRevenues().size());
        assertEquals(1, dto.getCosts().size());
        assertEquals(2, dto.getExpenses().size(), "管理费用 + 所得税");
    }

    @Test
    void generate_mixedDraftAndPosted_usesPostedOnlyExcludingClosingAndReportsPendingCount() {
        // F006 财务审计 Bug 6 (2026-07-04): 利润表必须只用
        // aggregateBySubjectExcludingDraftAndClosing (POSTED-only + 排除结转产物噪音),
        // 不再用 DRAFT-inclusive 的 aggregateBySubject. 同时须透出 pendingDraftVoucherCount.
        List<SubjectAggregateRow> postedOnlyAggregates = Arrays.asList(
                row("5001", "主营业务收入", "0.00", "50000.00"),
                row("5401", "主营业务成本", "20000.00", "0.00")
        );
        when(voucherEntryRepo.aggregateBySubjectExcludingDraftAndClosing(
                eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(postedOnlyAggregates);
        when(accountRepo.findVisibleToFactory(FACTORY))
                .thenReturn(Arrays.asList(revenueAccount, costAccount));
        when(voucherRepo.countByFactoryIdAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
                eq(FACTORY), eq(VoucherStatus.DRAFT), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(2L);

        IncomeStatementDTO dto = service.generate(FACTORY, START_YEAR, START_MONTH, END_YEAR, END_MONTH);

        assertEquals(new BigDecimal("50000.00"), dto.getTotalRevenue());
        assertEquals(new BigDecimal("20000.00"), dto.getTotalCost());
        assertEquals(new BigDecimal("30000.00"), dto.getGrossProfit());
        assertTrue(dto.isCostDataAvailable());
        assertEquals(IncomeStatementDTO.GrossMarginStatus.CALCULABLE, dto.getGrossMarginStatus());
        assertEquals(2L, dto.getPendingDraftVoucherCount());

        org.mockito.Mockito.verify(voucherEntryRepo, org.mockito.Mockito.never())
                .aggregateBySubject(org.mockito.ArgumentMatchers.any(), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    void generate_balanceAccountsExcluded() {
        // 现金账户存在但应被排除 — 利润表只看 5/6xxx
        List<SubjectAggregateRow> aggregates = Arrays.asList(
                row("1001", "库存现金", "5000.00", "0.00"),     // ASSET, 应排除
                row("5001", "主营业务收入", "0.00", "10000.00")  // REVENUE, 应保留
        );
        when(voucherEntryRepo.aggregateBySubjectExcludingDraftAndClosing(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(aggregates);
        when(accountRepo.findVisibleToFactory(FACTORY))
                .thenReturn(Arrays.asList(cashAccount, revenueAccount));

        IncomeStatementDTO dto = service.generate(FACTORY, START_YEAR, START_MONTH, END_YEAR, END_MONTH);

        assertEquals(new BigDecimal("10000.00"), dto.getTotalRevenue());
        assertEquals(BigDecimal.ZERO.setScale(2), dto.getTotalCost());
        assertEquals(new BigDecimal("10000.00"), dto.getGrossProfit());
        assertEquals(0, dto.getCosts().size(), "cash account excluded");
        assertFalse(dto.isCostDataAvailable());
        assertEquals(IncomeStatementDTO.GrossMarginStatus.MISSING_COST_DATA, dto.getGrossMarginStatus());
        assertTrue(dto.getGrossMarginStatusMessage().contains("不能把缺失成本当作 0"));
    }

    @Test
    void generate_lossScenario_returnsNegativeNetProfit() {
        // 收入 1000 - 成本 2000 = -1000 (毛亏)
        // 毛亏 -1000 - 费用 500 = -1500 (营业亏损)
        // 净亏 -1500 - 所得税 0 = -1500
        List<SubjectAggregateRow> aggregates = Arrays.asList(
                row("5001", "主营业务收入", "0.00", "1000.00"),
                row("5401", "主营业务成本", "2000.00", "0.00"),
                row("6601", "管理费用", "500.00", "0.00")
        );
        when(voucherEntryRepo.aggregateBySubjectExcludingDraftAndClosing(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(aggregates);
        when(accountRepo.findVisibleToFactory(FACTORY))
                .thenReturn(Arrays.asList(revenueAccount, costAccount, expenseAccount));

        IncomeStatementDTO dto = service.generate(FACTORY, START_YEAR, START_MONTH, END_YEAR, END_MONTH);

        assertEquals(new BigDecimal("-1000.00"), dto.getGrossProfit());
        assertEquals(new BigDecimal("-1500.00"), dto.getOperatingProfit());
        assertEquals(new BigDecimal("-1500.00"), dto.getNetProfit(), "亏损");
        assertTrue(dto.getNetProfit().signum() < 0);
    }

    @Test
    void generate_legacyEntryFallsBackByCodePrefix() {
        // 5xxx 不绑 Account, 按 prefix '5' fallback REVENUE
        // 6xxx 不绑 Account, 按 prefix '6' fallback COST
        List<SubjectAggregateRow> aggregates = Arrays.asList(
                row("5999", "自定义收入", "0.00", "3000.00"),
                row("6999", "自定义成本", "1000.00", "0.00")
        );
        when(voucherEntryRepo.aggregateBySubjectExcludingDraftAndClosing(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(aggregates);
        when(accountRepo.findVisibleToFactory(FACTORY))
                .thenReturn(Collections.emptyList());  // 没注册 Account

        IncomeStatementDTO dto = service.generate(FACTORY, START_YEAR, START_MONTH, END_YEAR, END_MONTH);

        assertEquals(new BigDecimal("3000.00"), dto.getTotalRevenue());
        assertEquals(new BigDecimal("1000.00"), dto.getTotalCost());
        assertEquals(new BigDecimal("2000.00"), dto.getGrossProfit());
    }

    @Test
    void generate_emptyData_returnsAllZeros() {
        when(voucherEntryRepo.aggregateBySubjectExcludingDraftAndClosing(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(new ArrayList<>());
        when(accountRepo.findVisibleToFactory(FACTORY))
                .thenReturn(Collections.emptyList());

        IncomeStatementDTO dto = service.generate(FACTORY, START_YEAR, START_MONTH, END_YEAR, END_MONTH);

        assertEquals(BigDecimal.ZERO.setScale(2), dto.getTotalRevenue());
        assertEquals(BigDecimal.ZERO.setScale(2), dto.getNetProfit());
        assertEquals(IncomeStatementDTO.GrossMarginStatus.NO_REVENUE, dto.getGrossMarginStatus());
        assertEquals(0, dto.getRevenues().size());
        assertEquals(0, dto.getCosts().size());
        assertEquals(0, dto.getExpenses().size());
    }

    @Test
    void generate_dateRangeReverse_throws() {
        // start 5 月 → end 1 月 (倒着)
        assertThrows(IllegalArgumentException.class, () ->
                service.generate(FACTORY, 2026, 5, 2026, 1));
    }

    @Test
    void generate_invalidMonth_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                service.generate(FACTORY, 2026, 13, 2026, 5));
        assertThrows(IllegalArgumentException.class, () ->
                service.generate(FACTORY, 2026, 1, 2026, 0));
    }

    @Test
    void generate_nullFactoryId_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                service.generate(null, 2026, 1, 2026, 5));
        assertThrows(IllegalArgumentException.class, () ->
                service.generate("", 2026, 1, 2026, 5));
    }

    private SubjectAggregateRow row(String code, String name, String debit, String credit) {
        return SubjectAggregateRow.builder()
                .subjectCode(code)
                .subjectName(name)
                .totalDebit(new BigDecimal(debit))
                .totalCredit(new BigDecimal(credit))
                .entryCount(1L)
                .build();
    }
}
