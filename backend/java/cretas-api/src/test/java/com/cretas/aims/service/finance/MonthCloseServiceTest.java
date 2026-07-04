package com.cretas.aims.service.finance;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.finance.MonthCloseReconciliationDTO;
import com.cretas.aims.dto.finance.MonthCloseResultDTO;
import com.cretas.aims.dto.finance.report.IncomeStatementDTO;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.service.finance.impl.MonthCloseServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Wave2 月结自动闭环 MonthCloseService 单元测试.
 *
 * <p>覆盖:
 * <ul>
 *   <li>previewClose: 无待审批调整 → PASS / 有 → WARNING / 已 CLOSED → canClose=false (BLOCKING)</li>
 *   <li>executeClose: 正常闭环 (CLOSED + 20天窗口 + P&L 快照) / 已 CLOSED → 409 / 对账 BLOCKING → 400</li>
 *   <li>调整窗口 = closed_at + 20 天</li>
 *   <li>快照写入: totalRevenueSnapshot / netProfitSnapshot / incomeStatementSnapshot</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MonthCloseServiceTest {

    @Mock private AccountingPeriodRepository periodRepo;
    @Mock private AccountingPeriodService periodService;
    @Mock private ArApService arApService;
    @Mock private IncomeStatementService incomeStatementService;
    @Mock private com.cretas.aims.repository.VoucherEntryRepository voucherEntryRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MonthCloseServiceImpl service;

    private static final String FACTORY = "F006";
    private static final Integer Y = 2026;
    private static final Integer M = 5;

    private IncomeStatementDTO sampleIncomeStatement;

    @BeforeEach
    void setUp() {
        // @InjectMocks can't inject the real ObjectMapper (not a mock), build manually
        service = new MonthCloseServiceImpl(periodRepo, periodService, arApService,
                incomeStatementService, objectMapper, voucherEntryRepository);

        // 资金段子账↔总账对账 (finance audit Bug 5): 默认零漂移 stub (lenient — 非每个 test 都到 previewClose)。
        lenient().when(arApService.getFinanceOverview(FACTORY)).thenReturn(java.util.Map.of(
                "totalReceivable", BigDecimal.ZERO, "totalPayable", BigDecimal.ZERO));
        lenient().when(voucherEntryRepository.sumNetDebitBySubjectPrefix(eq(FACTORY), anyString()))
                .thenReturn(BigDecimal.ZERO);

        sampleIncomeStatement = IncomeStatementDTO.builder()
                .factoryId(FACTORY)
                .startYear(Y).startMonth(M).endYear(Y).endMonth(M)
                .totalRevenue(new BigDecimal("100000.00"))
                .totalCost(new BigDecimal("60000.00"))
                .grossProfit(new BigDecimal("40000.00"))
                .totalExpense(new BigDecimal("10000.00"))
                .operatingProfit(new BigDecimal("30000.00"))
                .incomeTax(new BigDecimal("5000.00"))
                .netProfit(new BigDecimal("25000.00"))
                .revenues(Collections.emptyList())
                .costs(Collections.emptyList())
                .expenses(Collections.emptyList())
                .generatedAt(LocalDateTime.now().toString())
                .build();
    }

    private void stubNoPendingAdjustments() {
        when(arApService.getPendingAdjustments(eq(FACTORY), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(Collections.<ArApTransaction>emptyList(), 1, 1, 0L));
    }

    private void stubPendingAdjustments(long count) {
        when(arApService.getPendingAdjustments(eq(FACTORY), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(Collections.<ArApTransaction>emptyList(), 1, 1, count));
    }

    // ==================== previewClose ====================

    @Test
    void previewClose_openPeriod_noPending_returnsPassCanClose() {
        when(periodService.findPeriod(FACTORY, Y, M)).thenReturn(Optional.empty());
        stubNoPendingAdjustments();
        when(incomeStatementService.generate(FACTORY, Y, M, Y, M)).thenReturn(sampleIncomeStatement);

        MonthCloseReconciliationDTO result = service.previewClose(FACTORY, Y, M);

        assertTrue(result.isCanClose(), "OPEN 期间无阻塞项 → canClose");
        assertEquals("PASS", result.getReconciliationStatus());
        assertEquals(4, result.getChecks().size());  // +1: 子账↔总账对账 (finance audit Bug 5)
        assertNotNull(result.getSummary());
    }

    @Test
    void previewClose_pendingAdjustments_returnsWarningButStillCanClose() {
        when(periodService.findPeriod(FACTORY, Y, M)).thenReturn(Optional.empty());
        stubPendingAdjustments(3);
        when(incomeStatementService.generate(FACTORY, Y, M, Y, M)).thenReturn(sampleIncomeStatement);

        MonthCloseReconciliationDTO result = service.previewClose(FACTORY, Y, M);

        assertTrue(result.isCanClose(), "待审批调整是 WARNING (非阻塞), 仍可结账");
        assertEquals("WARNING", result.getReconciliationStatus());
        // 找到待审批调整 check
        MonthCloseReconciliationDTO.CheckItem adjCheck = result.getChecks().stream()
                .filter(c -> c.getName().contains("调整"))
                .findFirst().orElseThrow();
        assertFalse(adjCheck.isPassed());
        assertEquals(3L, adjCheck.getValue());
    }

    @Test
    void previewClose_subledgerGlDrift_surfacesWarningButStillCanClose() {
        // finance audit Bug 5: AR 子账 ¥1000 但 1122 GL 只有 ¥600 (漂移 ¥400) → WARNING, 不阻塞。
        when(periodService.findPeriod(FACTORY, Y, M)).thenReturn(Optional.empty());
        stubNoPendingAdjustments();
        when(incomeStatementService.generate(FACTORY, Y, M, Y, M)).thenReturn(sampleIncomeStatement);
        when(arApService.getFinanceOverview(FACTORY)).thenReturn(java.util.Map.of(
                "totalReceivable", new BigDecimal("1000.00"),
                "totalPayable", new BigDecimal("500.00")));
        when(voucherEntryRepository.sumNetDebitBySubjectPrefix(FACTORY, "1122%"))
                .thenReturn(new BigDecimal("600.00"));   // 应收 GL 漂移 400
        when(voucherEntryRepository.sumNetDebitBySubjectPrefix(FACTORY, "2202%"))
                .thenReturn(new BigDecimal("-500.00"));   // 应付 GL 净贷 500 → 取负 = 500, 与子账一致

        MonthCloseReconciliationDTO result = service.previewClose(FACTORY, Y, M);

        assertTrue(result.isCanClose(), "对账漂移是 WARNING (非阻塞), 仍可结账");
        assertEquals("WARNING", result.getReconciliationStatus());
        MonthCloseReconciliationDTO.CheckItem reconcile = result.getChecks().stream()
                .filter(c -> c.getName().contains("对账"))
                .findFirst().orElseThrow();
        assertFalse(reconcile.isPassed(), "AR 子账 1000 vs GL 600 差 400 > 容差 → 未通过");
        assertEquals("WARNING", reconcile.getSeverity());
        assertTrue(reconcile.getDetail().contains("1000") && reconcile.getDetail().contains("600"),
                "detail 必须列出两套账数字供财务核查");
    }

    @Test
    void previewClose_subledgerGlWithinTolerance_reconcilePasses() {
        // 子账与 GL 一致 (差 ≤ ¥1) → 对账 check passed=true, 不产生 WARNING。
        when(periodService.findPeriod(FACTORY, Y, M)).thenReturn(Optional.empty());
        stubNoPendingAdjustments();
        when(incomeStatementService.generate(FACTORY, Y, M, Y, M)).thenReturn(sampleIncomeStatement);
        when(arApService.getFinanceOverview(FACTORY)).thenReturn(java.util.Map.of(
                "totalReceivable", new BigDecimal("1000.00"),
                "totalPayable", new BigDecimal("500.00")));
        when(voucherEntryRepository.sumNetDebitBySubjectPrefix(FACTORY, "1122%"))
                .thenReturn(new BigDecimal("1000.00"));
        when(voucherEntryRepository.sumNetDebitBySubjectPrefix(FACTORY, "2202%"))
                .thenReturn(new BigDecimal("-500.00"));

        MonthCloseReconciliationDTO result = service.previewClose(FACTORY, Y, M);

        assertEquals("PASS", result.getReconciliationStatus());
        MonthCloseReconciliationDTO.CheckItem reconcile = result.getChecks().stream()
                .filter(c -> c.getName().contains("对账"))
                .findFirst().orElseThrow();
        assertTrue(reconcile.isPassed(), "子账==GL → 对账通过");
    }

    @Test
    void previewClose_alreadyClosed_canCloseFalse() {
        AccountingPeriod closed = AccountingPeriod.builder()
                .factoryId(FACTORY).year(Y).month(M)
                .status(AccountingPeriod.Status.CLOSED).build();
        when(periodService.findPeriod(FACTORY, Y, M)).thenReturn(Optional.of(closed));
        stubNoPendingAdjustments();
        when(incomeStatementService.generate(FACTORY, Y, M, Y, M)).thenReturn(sampleIncomeStatement);

        MonthCloseReconciliationDTO result = service.previewClose(FACTORY, Y, M);

        assertFalse(result.isCanClose(), "已 CLOSED 期间 BLOCKING → canClose=false");
        MonthCloseReconciliationDTO.CheckItem statusCheck = result.getChecks().stream()
                .filter(c -> c.getName().equals("期间状态"))
                .findFirst().orElseThrow();
        assertFalse(statusCheck.isPassed());
        assertEquals("BLOCKING", statusCheck.getSeverity());
    }

    // ==================== executeClose ====================

    @Test
    void executeClose_openPeriod_closesWithSnapshotAndAdjustWindow() {
        AccountingPeriod open = AccountingPeriod.builder()
                .id("p-1").factoryId(FACTORY).year(Y).month(M)
                .status(AccountingPeriod.Status.OPEN).build();
        when(periodRepo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(open));
        // previewClose internals
        when(periodService.findPeriod(FACTORY, Y, M)).thenReturn(Optional.of(open));
        stubNoPendingAdjustments();
        when(incomeStatementService.generate(FACTORY, Y, M, Y, M)).thenReturn(sampleIncomeStatement);
        when(periodRepo.save(any(AccountingPeriod.class))).thenAnswer(inv -> inv.getArgument(0));

        MonthCloseResultDTO result = service.executeClose(FACTORY, Y, M, 300L);

        AccountingPeriod p = result.getPeriod();
        assertEquals(AccountingPeriod.Status.CLOSED, p.getStatus());
        assertEquals(300L, p.getClosedBy());
        assertNotNull(p.getClosedAt());
        // 调整窗口 = closed_at + 20 天
        assertNotNull(p.getAdjustDeadline());
        long days = java.time.Duration.between(p.getClosedAt(), p.getAdjustDeadline()).toDays();
        assertEquals(20, days, "调整窗口必须是 20 天 (邓总要求)");
        // 快照
        assertEquals(new BigDecimal("100000.00"), p.getTotalRevenueSnapshot());
        assertEquals(new BigDecimal("25000.00"), p.getNetProfitSnapshot());
        assertNotNull(p.getIncomeStatementSnapshot(), "P&L JSON 快照必须写入");
        assertTrue(p.getIncomeStatementSnapshot().contains("25000"), "快照含净利润");
        assertNotNull(p.getReportReadyAt(), "报表生成时间必须设 (邓总1-3号出报表)");
        // 结果
        assertNotNull(result.getIncomeStatement());
        assertEquals(new BigDecimal("25000.00"), result.getIncomeStatement().getNetProfit());
        verify(periodRepo).save(any(AccountingPeriod.class));
    }

    @Test
    void executeClose_alreadyClosed_throws409() {
        AccountingPeriod closed = AccountingPeriod.builder()
                .id("p-2").factoryId(FACTORY).year(Y).month(M)
                .status(AccountingPeriod.Status.CLOSED).build();
        when(periodRepo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(closed));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.executeClose(FACTORY, Y, M, 300L));
        assertEquals(Integer.valueOf(409), ex.getCode(), "重复结账 → 409 (防呆 R4)");
        assertEquals("PERIOD_ALREADY_CLOSED", ex.getErrorCode());
        assertNotNull(ex.getActionHint());
        verify(periodRepo, never()).save(any());
    }

    @Test
    void executeClose_pendingClosePeriod_canStillClose() {
        // PENDING_CLOSE → executeClose 应能推进到 CLOSED (不是 BLOCKING)
        AccountingPeriod pending = AccountingPeriod.builder()
                .id("p-3").factoryId(FACTORY).year(Y).month(M)
                .status(AccountingPeriod.Status.PENDING_CLOSE).build();
        when(periodRepo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(pending));
        when(periodService.findPeriod(FACTORY, Y, M)).thenReturn(Optional.of(pending));
        stubNoPendingAdjustments();
        when(incomeStatementService.generate(FACTORY, Y, M, Y, M)).thenReturn(sampleIncomeStatement);
        when(periodRepo.save(any(AccountingPeriod.class))).thenAnswer(inv -> inv.getArgument(0));

        MonthCloseResultDTO result = service.executeClose(FACTORY, Y, M, 300L);
        assertEquals(AccountingPeriod.Status.CLOSED, result.getPeriod().getStatus());
    }

    @Test
    void executeClose_noPeriodRow_autoOpensAndCloses() {
        // 无 row → openPeriod 自动创建 → 结账
        when(periodRepo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.empty());
        AccountingPeriod open = AccountingPeriod.builder()
                .id("p-auto").factoryId(FACTORY).year(Y).month(M)
                .status(AccountingPeriod.Status.OPEN).build();
        when(periodService.openPeriod(FACTORY, Y, M, 300L)).thenReturn(open);
        when(periodService.findPeriod(FACTORY, Y, M)).thenReturn(Optional.of(open));
        stubNoPendingAdjustments();
        when(incomeStatementService.generate(FACTORY, Y, M, Y, M)).thenReturn(sampleIncomeStatement);
        when(periodRepo.save(any(AccountingPeriod.class))).thenAnswer(inv -> inv.getArgument(0));

        MonthCloseResultDTO result = service.executeClose(FACTORY, Y, M, 300L);
        assertEquals(AccountingPeriod.Status.CLOSED, result.getPeriod().getStatus());
        verify(periodService).openPeriod(FACTORY, Y, M, 300L);
    }

    // ==================== input validation ====================

    @Test
    void previewClose_invalidMonth_throws() {
        assertThrows(BusinessException.class, () -> service.previewClose(FACTORY, Y, 13));
    }

    @Test
    void executeClose_nullFactory_throws() {
        assertThrows(BusinessException.class, () -> service.executeClose(null, Y, M, 1L));
    }
}
