package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.report.CashFlowDTO;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CashFlowService.classifyActivity unit tests.
 *
 * <p>现金流量表把每张含现金科目的凭证按其对手科目归入 经营/投资/筹资 三类活动. 历史 bug:
 * 用 22-25 前缀一刀切归 FINANCING, 把流动往来 (应付账款 2202 / 应付票据 / 预收 / 应付职工薪酬 /
 * 应交税费 / 其他应付) 错归筹资; 同时漏了短期借款 (2001, 前缀 20) 应属筹资. 本测试钉死正确分类:
 * 筹资 = 借款/债券/应付利息股利/长期应付 + 权益(4xxx); 经营 = 流动经营往来/收入/成本; 投资 = 14-19.
 */
@ExtendWith(MockitoExtension.class)
class CashFlowServiceTest {

    private static final String FACTORY = "F001";

    @Mock
    private VoucherRepository voucherRepo;
    @Mock
    private VoucherEntryRepository voucherEntryRepo;
    @Mock
    private AccountRepository accountRepo;

    private final CashFlowService service = new CashFlowService(null, null, null);

    @Test
    void tradePayablesAndCurrentItems_areOperating() {
        // 流动经营往来 — 必须 OPERATING (历史 bug 错归 FINANCING)
        assertEquals("OPERATING", service.classifyActivity("2202"), "应付账款");
        assertEquals("OPERATING", service.classifyActivity("2201"), "应付票据");
        assertEquals("OPERATING", service.classifyActivity("2203"), "预收账款");
        assertEquals("OPERATING", service.classifyActivity("2211"), "应付职工薪酬");
        assertEquals("OPERATING", service.classifyActivity("2221"), "应交税费");
        assertEquals("OPERATING", service.classifyActivity("2221.01"), "应交税费-销项 子科目");
        assertEquals("OPERATING", service.classifyActivity("2241"), "其他应付款");
        assertEquals("OPERATING", service.classifyActivity("2401"), "预提费用");
    }

    @Test
    void receivablesRevenueCostCash_areOperating() {
        assertEquals("OPERATING", service.classifyActivity("1122"), "应收账款");
        assertEquals("OPERATING", service.classifyActivity("1002"), "银行存款");
        assertEquals("OPERATING", service.classifyActivity("1405"), "库存商品");
        assertEquals("OPERATING", service.classifyActivity("6001"), "主营业务收入");
        assertEquals("OPERATING", service.classifyActivity("6401"), "主营业务成本");
        assertEquals("OPERATING", service.classifyActivity("6601"), "销售费用");
    }

    @Test
    void borrowingsBondsDividendsLongTermPayable_areFinancing() {
        assertEquals("FINANCING", service.classifyActivity("2001"), "短期借款 (前缀20, 历史漏)");
        assertEquals("FINANCING", service.classifyActivity("2501"), "长期借款");
        assertEquals("FINANCING", service.classifyActivity("2502"), "应付债券");
        assertEquals("FINANCING", service.classifyActivity("2701"), "长期应付款 (前缀27, 历史漏)");
        assertEquals("FINANCING", service.classifyActivity("2231"), "应付利息");
        assertEquals("FINANCING", service.classifyActivity("2232"), "应付股利");
        assertEquals("FINANCING", service.classifyActivity("2501.01"), "长期借款 子科目");
    }

    @Test
    void equityAccounts_areFinancing() {
        assertEquals("FINANCING", service.classifyActivity("4001"), "实收资本");
        assertEquals("FINANCING", service.classifyActivity("4002"), "资本公积");
        assertEquals("FINANCING", service.classifyActivity("4104"), "利润分配");
    }

    @Test
    void fixedAndIntangibleAssets_areInvesting() {
        assertEquals("INVESTING", service.classifyActivity("1601"), "固定资产");
        assertEquals("INVESTING", service.classifyActivity("1701"), "无形资产");
        assertEquals("INVESTING", service.classifyActivity("1501"), "投资性资产");
    }

    @Test
    void nullOrShortCode_defaultsOperating() {
        assertEquals("OPERATING", service.classifyActivity(null));
        assertEquals("OPERATING", service.classifyActivity("1"));
    }

    // -------------------------------------------------------------------------
    // F006 财务审计 Bug 6 follow-up (2026-07-04, #1228 CashFlow sibling): POSTED-only 口径
    // -------------------------------------------------------------------------

    private VoucherEntry entry(String subjectCode, String subjectName, BigDecimal debit, BigDecimal credit) {
        return VoucherEntry.builder()
                .subjectCode(subjectCode)
                .subjectName(subjectName)
                .debit(debit)
                .credit(credit)
                .build();
    }

    private Voucher voucher(VoucherStatus status, VoucherEntry... entries) {
        return Voucher.builder()
                .factoryId(FACTORY)
                .voucherDate(LocalDate.of(2026, 1, 15))
                .status(status)
                .entries(List.of(entries))
                .build();
    }

    @Test
    void generate_excludesDraftVouchersFromCashFlow() {
        // DRAFT (未财审) 凭证: 现金借 1000 / 对手贷 1000 (收入) — 必须被排除, 不计入经营活动流入
        Voucher draft = voucher(VoucherStatus.DRAFT,
                entry("1002", "银行存款", new BigDecimal("1000.00"), null),
                entry("6001", "主营业务收入", null, new BigDecimal("1000.00")));
        // POSTED 凭证: 现金借 500 / 对手贷 500 (收入) — 应计入
        Voucher posted = voucher(VoucherStatus.POSTED,
                entry("1002", "银行存款", new BigDecimal("500.00"), null),
                entry("6001", "主营业务收入", null, new BigDecimal("500.00")));

        when(voucherRepo.findByFactoryIdAndDateRange(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(draft, posted));
        when(accountRepo.findVisibleToFactory(FACTORY)).thenReturn(Collections.emptyList());
        when(voucherRepo.countByFactoryIdAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
                eq(FACTORY), eq(VoucherStatus.DRAFT), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(1L);

        CashFlowService svc = new CashFlowService(voucherRepo, voucherEntryRepo, accountRepo);
        CashFlowDTO dto = svc.generate(FACTORY, 2026, 1, 2026, 1);

        // 只有 POSTED 的 500 计入 — 若 DRAFT 未被排除, 会变成 1500
        assertEquals(new BigDecimal("500.00"), dto.getOperatingNetCashFlow(),
                "DRAFT 凭证不应计入现金流量表 (人工审核制, 未财审草稿不算已发生现金流动)");
        assertEquals(1L, dto.getPendingDraftVoucherCount(), "待过账凭证数须透出告知财务人员");
    }

    @Test
    void generate_keepsReversedVouchersSoRedLetterOffsetIsCorrect() {
        // 已过账原凭证(REVERSED, 金蝶红字冲销规范): 现金贷 200 / 对手借 200 — 财务效应必须保留计入
        Voucher reversedOriginal = voucher(VoucherStatus.REVERSED,
                entry("1002", "银行存款", null, new BigDecimal("200.00")),
                entry("6001", "主营业务收入", new BigDecimal("200.00"), null));
        // 红字冲销镜像凭证 (POSTED, 借贷互换): 现金借 200 / 对手贷 200 — 二者相加应完全抵消归零
        Voucher reversalMirror = voucher(VoucherStatus.POSTED,
                entry("1002", "银行存款", new BigDecimal("200.00"), null),
                entry("6001", "主营业务收入", null, new BigDecimal("200.00")));

        when(voucherRepo.findByFactoryIdAndDateRange(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(reversedOriginal, reversalMirror));
        when(accountRepo.findVisibleToFactory(FACTORY)).thenReturn(Collections.emptyList());
        when(voucherRepo.countByFactoryIdAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
                eq(FACTORY), eq(VoucherStatus.DRAFT), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(0L);

        CashFlowService svc = new CashFlowService(voucherRepo, voucherEntryRepo, accountRepo);
        CashFlowDTO dto = svc.generate(FACTORY, 2026, 1, 2026, 1);

        // REVERSED 若被误排除(像 aggregateBySubjectPosted 那样), 净额会变成 "倍数冲销"
        // (只剩镜像凭证的 +200 流入 而不是原凭证的 -200 流出被抵消); 保留 REVERSED 应精确归零.
        assertEquals(new BigDecimal("0.00"), dto.getOperatingNetCashFlow(),
                "REVERSED 原凭证必须保留计入, 配合红字冲销镜像才能正确抵消归零");
        assertEquals(0L, dto.getPendingDraftVoucherCount());
    }

    @Test
    void generate_excludesVoidVouchers() {
        Voucher voided = voucher(VoucherStatus.VOID,
                entry("1002", "银行存款", new BigDecimal("999.00"), null),
                entry("6001", "主营业务收入", null, new BigDecimal("999.00")));

        when(voucherRepo.findByFactoryIdAndDateRange(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(voided));
        when(accountRepo.findVisibleToFactory(FACTORY)).thenReturn(Collections.emptyList());
        when(voucherRepo.countByFactoryIdAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
                eq(FACTORY), eq(VoucherStatus.DRAFT), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(0L);

        CashFlowService svc = new CashFlowService(voucherRepo, voucherEntryRepo, accountRepo);
        CashFlowDTO dto = svc.generate(FACTORY, 2026, 1, 2026, 1);

        assertEquals(new BigDecimal("0.00"), dto.getOperatingNetCashFlow(), "VOID 凭证不计入 (既有行为, 回归测试)");
    }

    // -------------------------------------------------------------------------
    // beginningCash / endingCash — 修复历史 bug (曾硬编码 0, 任何非起点区间低报期末余额)
    // -------------------------------------------------------------------------

    private SubjectAggregateRow aggregateRow(String code, String name, BigDecimal debit, BigDecimal credit) {
        return new SubjectAggregateRow(code, name, debit, credit, 1L);
    }

    @Test
    void generate_nonInceptionRange_beginningCashReflectsPriorCumulativeBalance() {
        // 2026-06 区间: 期初现金余额 = 现金科目在 [EPOCH_START, 2026-05-31] 的累计
        // debit-credit. 现金科目分散在 1001/1002/1012 三个 code, 且聚合结果里混入非现金科目
        // (6001) 必须被过滤掉, 不能污染期初余额.
        LocalDate priorCutoff = LocalDate.of(2026, 5, 31);
        when(voucherEntryRepo.aggregateBySubjectExcludingDraft(FACTORY, LocalDate.of(1970, 1, 1), priorCutoff))
                .thenReturn(List.of(
                        aggregateRow("1001", "库存现金", new BigDecimal("500.00"), new BigDecimal("100.00")),
                        aggregateRow("1002", "银行存款", new BigDecimal("5000.00"), new BigDecimal("1000.00")),
                        aggregateRow("1012", "其他货币资金", new BigDecimal("200.00"), new BigDecimal("0.00")),
                        aggregateRow("6001", "主营业务收入", new BigDecimal("0.00"), new BigDecimal("999999.00"))));

        // 本期 (2026-06) 一张 POSTED 凭证: 现金借 300 (银行存款) / 对手贷 300 (收入) — 净增 300
        Voucher june = voucher(VoucherStatus.POSTED,
                entry("1002", "银行存款", new BigDecimal("300.00"), null),
                entry("6001", "主营业务收入", null, new BigDecimal("300.00")));
        when(voucherRepo.findByFactoryIdAndDateRange(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(june));
        when(accountRepo.findVisibleToFactory(FACTORY)).thenReturn(Collections.emptyList());
        when(voucherRepo.countByFactoryIdAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
                eq(FACTORY), eq(VoucherStatus.DRAFT), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(0L);

        CashFlowService svc = new CashFlowService(voucherRepo, voucherEntryRepo, accountRepo);
        CashFlowDTO dto = svc.generate(FACTORY, 2026, 6, 2026, 6);

        // beginningCash = (500-100) + (5000-1000) + (200-0) = 400 + 4000 + 200 = 4600 (6001 excluded)
        assertEquals(new BigDecimal("4600.00"), dto.getBeginningCash(),
                "期初现金余额须为现金科目 (1001/1002/1012) 累计余额, 非现金科目 (6001) 必须被过滤");
        assertEquals(new BigDecimal("300.00"), dto.getNetIncreaseInCash());
        // endingCash = beginningCash + netIncrease = 4600 + 300 = 4900 — 精确等于资产负债表口径
        assertEquals(new BigDecimal("4900.00"), dto.getEndingCash(),
                "期末现金余额 = 期初 + 本期净额, 必须等于资产负债表现金科目区间末余额");
    }

    @Test
    void generate_startDateAtEpoch_beginningCashIsZeroWithoutQuerying() {
        // 起始月正好是 EPOCH_START 所在月 (1970-01) — startDate.minusDays(1) 早于 EPOCH_START,
        // 应直接返回 0, 不应发起聚合查询 (无意义的空区间查询).
        Voucher jan1970 = voucher(VoucherStatus.POSTED,
                entry("1002", "银行存款", new BigDecimal("100.00"), null),
                entry("6001", "主营业务收入", null, new BigDecimal("100.00")));
        when(voucherRepo.findByFactoryIdAndDateRange(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(jan1970));
        when(accountRepo.findVisibleToFactory(FACTORY)).thenReturn(Collections.emptyList());
        when(voucherRepo.countByFactoryIdAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
                eq(FACTORY), eq(VoucherStatus.DRAFT), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(0L);

        CashFlowService svc = new CashFlowService(voucherRepo, voucherEntryRepo, accountRepo);
        CashFlowDTO dto = svc.generate(FACTORY, 1970, 1, 1970, 1);

        assertEquals(new BigDecimal("0.00"), dto.getBeginningCash());
        assertEquals(new BigDecimal("100.00"), dto.getEndingCash());
        verify(voucherEntryRepo, never()).aggregateBySubjectExcludingDraft(any(), any(), any());
    }

    @Test
    void generate_sinceInceptionDefault_beginningCashStillZero() {
        // 从数据起点开始查询 (无更早历史) — aggregateBySubjectExcludingDraft 返回空列表,
        // beginningCash 应为 0 (既有 UI 默认"年初至当月"在 F006 上凑巧 = since-inception 的行为不变).
        when(voucherEntryRepo.aggregateBySubjectExcludingDraft(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        Voucher v = voucher(VoucherStatus.POSTED,
                entry("1002", "银行存款", new BigDecimal("1000.00"), null),
                entry("6001", "主营业务收入", null, new BigDecimal("1000.00")));
        when(voucherRepo.findByFactoryIdAndDateRange(eq(FACTORY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(v));
        when(accountRepo.findVisibleToFactory(FACTORY)).thenReturn(Collections.emptyList());
        when(voucherRepo.countByFactoryIdAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
                eq(FACTORY), eq(VoucherStatus.DRAFT), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(0L);

        CashFlowService svc = new CashFlowService(voucherRepo, voucherEntryRepo, accountRepo);
        CashFlowDTO dto = svc.generate(FACTORY, 2026, 1, 2026, 1);

        assertEquals(new BigDecimal("0.00"), dto.getBeginningCash());
        assertEquals(new BigDecimal("1000.00"), dto.getEndingCash());
    }
}
