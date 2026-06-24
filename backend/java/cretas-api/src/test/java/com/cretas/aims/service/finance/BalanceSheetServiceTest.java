package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.report.BalanceSheetDTO;
import com.cretas.aims.entity.enums.AccountBalanceType;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
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
 * Sprint 7 T3 BalanceSheetService unit tests.
 *
 * <p>覆盖:
 * <ul>
 *   <li>正常 case: 1 资产 (1001 现金) + 1 负债 (2202 应付账款) + 1 权益 (4001 实收资本), 平衡</li>
 *   <li>balanceCheck false: 故意制造不平衡数据</li>
 *   <li>科目分类: REVENUE/COST/EXPENSE 不应纳入资产负债表</li>
 *   <li>Legacy entry 无 Account (subjectCode 自由文本): 按 code 前缀 fallback</li>
 *   <li>periodStatus 透传 from AccountingPeriodService</li>
 *   <li>空数据返 totalAssets=0, balanceCheck=true (0 == 0)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BalanceSheetServiceTest {

    @Mock
    private VoucherEntryRepository voucherEntryRepo;

    @Mock
    private AccountRepository accountRepo;

    @Mock
    private AccountingPeriodService accountingPeriodService;

    @InjectMocks
    private BalanceSheetService service;

    private static final String FACTORY = "F006";
    private static final Integer YEAR = 2026;
    private static final Integer MONTH = 5;

    private Account cashAccount;       // 1001 库存现金 ASSET DEBIT_NORMAL
    private Account payableAccount;    // 2202 应付账款 LIABILITY CREDIT_NORMAL
    private Account capitalAccount;    // 4001 实收资本 EQUITY CREDIT_NORMAL
    private Account revenueAccount;    // 5001 主营业务收入 REVENUE CREDIT_NORMAL (应被 skip)

    @BeforeEach
    void setUp() {
        cashAccount = Account.builder()
                .id("acc-1001").code("1001").name("库存现金")
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        payableAccount = Account.builder()
                .id("acc-2202").code("2202").name("应付账款")
                .category(AccountCategory.LIABILITY)
                .balanceType(AccountBalanceType.CREDIT_NORMAL)
                .build();
        capitalAccount = Account.builder()
                .id("acc-4001").code("4001").name("实收资本")
                .category(AccountCategory.EQUITY)
                .balanceType(AccountBalanceType.CREDIT_NORMAL)
                .build();
        revenueAccount = Account.builder()
                .id("acc-5001").code("5001").name("主营业务收入")
                .category(AccountCategory.REVENUE)
                .balanceType(AccountBalanceType.CREDIT_NORMAL)
                .build();
    }

    @Test
    void generate_balancedScenario_returnsCheckTrue() {
        // 1001 现金 debit 100000 credit 30000 → net asset 70000
        // 2202 应付 credit 20000 debit 0 → net liability 20000
        // 4001 实收资本 credit 50000 debit 0 → net equity 50000
        // Balance: assets 70000 == liabilities 20000 + equity 50000 ✓
        List<SubjectAggregateRow> aggregates = Arrays.asList(
                row("1001", "库存现金", "100000.00", "30000.00"),
                row("2202", "应付账款", "0.00", "20000.00"),
                row("4001", "实收资本", "0.00", "50000.00")
        );
        when(voucherEntryRepo.aggregateBySubject(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(aggregates);
        when(accountRepo.findVisibleToFactory(FACTORY))
                .thenReturn(Arrays.asList(cashAccount, payableAccount, capitalAccount));
        when(accountingPeriodService.getStatus(FACTORY, YEAR, MONTH))
                .thenReturn(AccountingPeriod.Status.CLOSED);

        BalanceSheetDTO dto = service.generate(FACTORY, YEAR, MONTH);

        assertNotNull(dto);
        assertEquals(FACTORY, dto.getFactoryId());
        assertEquals(YEAR, dto.getYear());
        assertEquals(MONTH, dto.getMonth());
        assertEquals(AccountingPeriod.Status.CLOSED, dto.getPeriodStatus());

        assertEquals(1, dto.getAssets().size(), "1 asset line");
        assertEquals("1001", dto.getAssets().get(0).getAccountCode());
        assertEquals(new BigDecimal("70000.00"), dto.getAssets().get(0).getAmount());

        assertEquals(1, dto.getLiabilities().size(), "1 liability line");
        assertEquals(new BigDecimal("20000.00"), dto.getLiabilities().get(0).getAmount());

        assertEquals(1, dto.getEquity().size(), "1 equity line");
        assertEquals(new BigDecimal("50000.00"), dto.getEquity().get(0).getAmount());

        assertEquals(new BigDecimal("70000.00"), dto.getTotalAssets());
        assertEquals(new BigDecimal("20000.00"), dto.getTotalLiabilities());
        assertEquals(new BigDecimal("50000.00"), dto.getTotalEquity());
        assertEquals(new BigDecimal("70000.00"), dto.getTotalLiabilitiesAndEquity());

        assertTrue(dto.getBalanceCheck(), "balanced (70000 == 70000)");
    }

    @Test
    void generate_unbalancedData_returnsCheckFalse() {
        // Asset 100 vs Liability 30 + Equity 50 = 80 → diff 20 > 0.01
        List<SubjectAggregateRow> aggregates = Arrays.asList(
                row("1001", "库存现金", "100.00", "0.00"),
                row("2202", "应付账款", "0.00", "30.00"),
                row("4001", "实收资本", "0.00", "50.00")
        );
        when(voucherEntryRepo.aggregateBySubject(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(aggregates);
        when(accountRepo.findVisibleToFactory(FACTORY))
                .thenReturn(Arrays.asList(cashAccount, payableAccount, capitalAccount));
        when(accountingPeriodService.getStatus(FACTORY, YEAR, MONTH))
                .thenReturn(AccountingPeriod.Status.OPEN);

        BalanceSheetDTO dto = service.generate(FACTORY, YEAR, MONTH);

        assertFalse(dto.getBalanceCheck(), "100 ≠ 80, should fail");
        assertEquals(AccountingPeriod.Status.OPEN, dto.getPeriodStatus());
    }

    @Test
    void generate_revenueAndExpenseAccounts_notListedAsBookedAccounts() {
        // 5001 收入 不作为资产/负债/已记账权益科目列出, 但其净额滚入"未分配利润"权益合成行.
        // 此处用的是 NON-balanced 人工数据 (借 1000 ≠ 贷 5000), 所以即便滚入仍不平衡 —
        // 验证 service 不把 revenue 当资产, 同时确认 revenue 进了 retained-earnings 而非凭空消失.
        List<SubjectAggregateRow> aggregates = Arrays.asList(
                row("1001", "库存现金", "1000.00", "0.00"),
                row("5001", "主营业务收入", "0.00", "5000.00")
        );
        when(voucherEntryRepo.aggregateBySubject(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(aggregates);
        when(accountRepo.findVisibleToFactory(FACTORY))
                .thenReturn(Arrays.asList(cashAccount, revenueAccount));
        when(accountingPeriodService.getStatus(FACTORY, YEAR, MONTH))
                .thenReturn(AccountingPeriod.Status.OPEN);

        BalanceSheetDTO dto = service.generate(FACTORY, YEAR, MONTH);

        assertEquals(1, dto.getAssets().size(), "only cash, not revenue");
        assertEquals(0, dto.getLiabilities().size());
        // revenue 5000 滚入"未分配利润"合成权益行 (而非作为 5001 收入科目列出)
        assertEquals(1, dto.getEquity().size(), "retained-earnings synthetic line");
        assertTrue(dto.getEquity().get(0).getAccountName().contains("未分配利润"));
        assertEquals(new BigDecimal("5000.00"), dto.getEquity().get(0).getAmount());
        // 资产 1000 vs 负债+权益 5000 → 仍不平衡 (人工非配平数据, 非真实凭证)
        assertFalse(dto.getBalanceCheck());
    }

    @Test
    void generate_revenueWithoutClosing_rolledIntoRetainedEarnings() {
        // 真实场景 (F006): 配平销售凭证 借 现金 9,710,036.42 / 贷 主营业务收入 9,710,036.42.
        // 无任何已记账权益科目, 也未做结转损益 (期间未结账).
        // 修复前: 资产 9.7M vs 权益 0 → balanceCheck=false (利润未滚入权益).
        // 修复后: 未结转 P&L 净额作为"未分配利润"合成权益行 → 平衡.
        List<SubjectAggregateRow> aggregates = Arrays.asList(
                row("1001", "库存现金", "9710036.42", "0.00"),
                row("5001", "主营业务收入", "0.00", "9710036.42")
        );
        when(voucherEntryRepo.aggregateBySubject(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(aggregates);
        when(accountRepo.findVisibleToFactory(FACTORY))
                .thenReturn(Arrays.asList(cashAccount, revenueAccount));
        when(accountingPeriodService.getStatus(FACTORY, YEAR, MONTH))
                .thenReturn(AccountingPeriod.Status.OPEN);

        BalanceSheetDTO dto = service.generate(FACTORY, YEAR, MONTH);

        assertEquals(new BigDecimal("9710036.42"), dto.getTotalAssets());
        assertEquals(BigDecimal.ZERO.setScale(2), dto.getTotalLiabilities());
        assertEquals(new BigDecimal("9710036.42"), dto.getTotalEquity(), "未分配利润 = 净利润");
        assertEquals(1, dto.getEquity().size(), "single retained-earnings line");
        assertTrue(dto.getEquity().get(0).getAccountName().contains("未分配利润"));
        assertEquals(new BigDecimal("9710036.42"), dto.getEquity().get(0).getAmount());
        assertTrue(dto.getBalanceCheck(), "资产 ≡ 负债 + (已记账权益 + 未分配利润)");
    }

    @Test
    void generate_costAndExpenseReduceRetainedEarnings() {
        // 配平凭证: 借 现金 700 + 借 主营业务成本 200 + 借 管理费用 100 / 贷 收入 1000
        //   资产(现金) = 700
        //   未分配利润 = 收入1000 - 成本200 - 费用100 = 700
        //   资产 700 ≡ 权益 700 → 平衡
        Account costAccount = Account.builder()
                .id("acc-6401").code("6401").name("主营业务成本")
                .category(AccountCategory.COST).balanceType(AccountBalanceType.DEBIT_NORMAL).build();
        Account expenseAccount = Account.builder()
                .id("acc-6602").code("6602").name("管理费用")
                .category(AccountCategory.EXPENSE).balanceType(AccountBalanceType.DEBIT_NORMAL).build();
        List<SubjectAggregateRow> aggregates = Arrays.asList(
                row("1001", "库存现金", "700.00", "0.00"),
                row("6401", "主营业务成本", "200.00", "0.00"),
                row("6602", "管理费用", "100.00", "0.00"),
                row("5001", "主营业务收入", "0.00", "1000.00")
        );
        when(voucherEntryRepo.aggregateBySubject(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(aggregates);
        when(accountRepo.findVisibleToFactory(FACTORY))
                .thenReturn(Arrays.asList(cashAccount, revenueAccount, costAccount, expenseAccount));
        when(accountingPeriodService.getStatus(FACTORY, YEAR, MONTH))
                .thenReturn(AccountingPeriod.Status.OPEN);

        BalanceSheetDTO dto = service.generate(FACTORY, YEAR, MONTH);

        assertEquals(new BigDecimal("700.00"), dto.getTotalAssets());
        assertEquals(new BigDecimal("700.00"), dto.getTotalEquity(), "1000 - 200 - 100");
        assertTrue(dto.getBalanceCheck(), "成本/费用降低未分配利润后仍平衡");
    }

    @Test
    void generate_legacyEntryWithoutAccount_fallsBackByCodePrefix() {
        // subjectCode = "1xxx-custom" 没绑 Account, 按 prefix '1' fallback ASSET
        List<SubjectAggregateRow> aggregates = Arrays.asList(
                row("1999", "自定义流动资产", "5000.00", "0.00"),
                row("2999", "自定义流动负债", "0.00", "5000.00")
        );
        when(voucherEntryRepo.aggregateBySubject(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(aggregates);
        when(accountRepo.findVisibleToFactory(FACTORY))
                .thenReturn(Collections.emptyList());  // 没有任何 Account 注册
        when(accountingPeriodService.getStatus(FACTORY, YEAR, MONTH))
                .thenReturn(AccountingPeriod.Status.OPEN);

        BalanceSheetDTO dto = service.generate(FACTORY, YEAR, MONTH);

        assertEquals(1, dto.getAssets().size(), "1xxx fallback to ASSET");
        assertEquals(new BigDecimal("5000.00"), dto.getAssets().get(0).getAmount());
        assertEquals(1, dto.getLiabilities().size(), "2xxx fallback to LIABILITY");
        assertEquals(new BigDecimal("5000.00"), dto.getLiabilities().get(0).getAmount());
        assertTrue(dto.getBalanceCheck(), "balanced even with legacy entries");
    }

    @Test
    void generate_emptyData_returnsZeroAndBalanced() {
        when(voucherEntryRepo.aggregateBySubject(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(new ArrayList<>());
        when(accountRepo.findVisibleToFactory(FACTORY))
                .thenReturn(Collections.emptyList());
        when(accountingPeriodService.getStatus(FACTORY, YEAR, MONTH))
                .thenReturn(AccountingPeriod.Status.OPEN);

        BalanceSheetDTO dto = service.generate(FACTORY, YEAR, MONTH);

        assertEquals(0, dto.getAssets().size());
        assertEquals(0, dto.getLiabilities().size());
        assertEquals(0, dto.getEquity().size());
        assertEquals(BigDecimal.ZERO.setScale(2), dto.getTotalAssets());
        assertEquals(BigDecimal.ZERO.setScale(2), dto.getTotalLiabilitiesAndEquity());
        assertTrue(dto.getBalanceCheck(), "0 == 0 should be balanced");
    }

    @Test
    void generate_factoryCustomAccountOverridesSystemLevel() {
        // 系统级 1001 (factoryId=null) + factory 自定义 1001 (factoryId=F006) — 自定义优先
        Account systemCash = Account.builder()
                .id("sys-1001").code("1001").name("系统-现金")
                .category(AccountCategory.ASSET).balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();
        Account factoryCash = Account.builder()
                .id("f-1001").factoryId(FACTORY).code("1001").name("F006-现金特殊")
                .category(AccountCategory.ASSET).balanceType(AccountBalanceType.DEBIT_NORMAL)
                .build();

        List<SubjectAggregateRow> aggregates = Collections.singletonList(
                row("1001", "现金", "1000.00", "0.00")
        );
        when(voucherEntryRepo.aggregateBySubject(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(aggregates);
        when(accountRepo.findVisibleToFactory(FACTORY))
                .thenReturn(Arrays.asList(systemCash, factoryCash));  // 顺序无所谓
        when(accountingPeriodService.getStatus(FACTORY, YEAR, MONTH))
                .thenReturn(AccountingPeriod.Status.OPEN);

        BalanceSheetDTO dto = service.generate(FACTORY, YEAR, MONTH);

        assertEquals(1, dto.getAssets().size());
        assertEquals("F006-现金特殊", dto.getAssets().get(0).getAccountName(),
                "factory 自定义优先于系统级");
    }

    @Test
    void generate_invalidFactoryId_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.generate(null, YEAR, MONTH));
        assertThrows(IllegalArgumentException.class, () -> service.generate("", YEAR, MONTH));
    }

    @Test
    void generate_invalidMonth_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.generate(FACTORY, YEAR, 13));
        assertThrows(IllegalArgumentException.class, () -> service.generate(FACTORY, YEAR, 0));
        assertThrows(IllegalArgumentException.class, () -> service.generate(FACTORY, YEAR, null));
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
