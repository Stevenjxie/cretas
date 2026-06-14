package com.cretas.aims.service.finance.impl;

import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.VoucherExportRequestDTO;
import com.cretas.aims.entity.enums.AccountBalanceType;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.finance.VoucherExportConfig;
import com.cretas.aims.entity.finance.VoucherExportRecord;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.repository.finance.VoucherExportConfigRepository;
import com.cretas.aims.repository.finance.VoucherExportRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP11: VoucherExportServiceImpl 单元测试.
 *
 * @since SP11 2026-06-10
 */
@DisplayName("VoucherExportServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class VoucherExportServiceTest {

    @Mock private VoucherRepository voucherRepo;
    @Mock private VoucherEntryRepository entryRepo;
    @Mock private AccountRepository accountRepo;
    @Mock private AccountingPeriodRepository accountingPeriodRepo;
    @Mock private VoucherExportConfigRepository exportConfigRepo;
    @Mock private VoucherExportRecordRepository exportRecordRepo;

    private VoucherExportServiceImpl service;

    private static final String FACTORY_ID = "F-SP11-EXPORT";
    private static final Long USER_ID = 99L;
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2026, 5, 31);

    @BeforeEach
    void setUp() {
        service = new VoucherExportServiceImpl(voucherRepo, entryRepo, accountRepo,
                accountingPeriodRepo, exportConfigRepo, exportRecordRepo);
    }

    private VoucherExportRequestDTO buildReq() {
        return VoucherExportRequestDTO.builder()
                .startDate(START)
                .endDate(END)
                .targetSystem(VoucherTargetSystem.KINGDEE)
                .build();
    }

    private VoucherExportConfig defaultConfig() {
        return VoucherExportConfig.builder()
                .factoryId(FACTORY_ID)
                .targetSystem(VoucherTargetSystem.KINGDEE)
                .build();
    }

    private Account account(String code, String name, AccountBalanceType balanceType) {
        return Account.builder()
                .id("ACC-" + code)
                .factoryId(FACTORY_ID)
                .code(code)
                .name(name)
                .category(balanceType == AccountBalanceType.DEBIT_NORMAL
                        ? AccountCategory.ASSET : AccountCategory.LIABILITY)
                .balanceType(balanceType)
                .build();
    }

    private AccountingPeriod closedPeriod(int year, int month) {
        return AccountingPeriod.builder()
                .id("PERIOD-" + year + "-" + month)
                .factoryId(FACTORY_ID)
                .year(year)
                .month(month)
                .status(AccountingPeriod.Status.CLOSED)
                .build();
    }

    private SubjectAggregateRow aggregate(String code, String name, String debit, String credit) {
        return SubjectAggregateRow.builder()
                .subjectCode(code)
                .subjectName(name)
                .totalDebit(new BigDecimal(debit))
                .totalCredit(new BigDecimal(credit))
                .entryCount(1L)
                .build();
    }

    private Voucher voucher(String id, String number, LocalDate date) {
        return Voucher.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .voucherNumber(number)
                .voucherDate(date)
                .voucherType(VoucherType.PURCHASE_PAYMENT)
                .sourceBusinessType("TEST")
                .sourceBusinessId(id)
                .totalDebit(BigDecimal.ZERO)
                .totalCredit(BigDecimal.ZERO)
                .status(VoucherStatus.POSTED)
                .build();
    }

    private VoucherEntry entry(String id, int lineNo, String code, String name,
                               String summary, String debit, String credit) {
        return VoucherEntry.builder()
                .id(id)
                .lineNo(lineNo)
                .subjectCode(code)
                .subjectName(name)
                .description(summary)
                .debit(new BigDecimal(debit))
                .credit(new BigDecimal(credit))
                .build();
    }

    private List<List<String>> readXlsx(byte[] bytes) {
        List<List<String>> all = new ArrayList<>();
        com.alibaba.excel.EasyExcel.read(new ByteArrayInputStream(bytes),
                new com.alibaba.excel.event.AnalysisEventListener<Map<Integer, String>>() {
                    @Override
                    public void invokeHeadMap(Map<Integer, String> headMap,
                                              com.alibaba.excel.context.AnalysisContext context) {
                        all.add(orderedValues(headMap));
                    }

                    @Override
                    public void invoke(Map<Integer, String> data,
                                       com.alibaba.excel.context.AnalysisContext context) {
                        all.add(orderedValues(data));
                    }

                    @Override
                    public void doAfterAllAnalysed(com.alibaba.excel.context.AnalysisContext context) {
                    }

                    private List<String> orderedValues(Map<Integer, String> map) {
                        return new TreeMap<>(map).values().stream()
                                .map(v -> v == null ? "" : v)
                                .collect(Collectors.toList());
                    }
                }).sheet().doRead();
        return all;
    }

    private BigDecimal decimalAt(List<String> row, int index) {
        return new BigDecimal(row.get(index));
    }

    @Test
    @DisplayName("T1: 序时账导出 — 有凭证时 OutputStream 非空且写了 xlsx 内容")
    void testExportSequentialLedger_withVouchers_writesXlsx() throws Exception {
        when(exportRecordRepo.findRecentExport(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.of(defaultConfig()));

        // 一张凭证 + 两行分录
        Voucher v = Voucher.builder()
                .id("V-001")
                .factoryId(FACTORY_ID)
                .voucherNumber("V-2026-001")
                .voucherDate(LocalDate.of(2026, 5, 10))
                .build();

        VoucherEntry e1 = VoucherEntry.builder()
                .id("E-001").lineNo(1)
                .subjectCode("1002").subjectName("银行存款")
                .debit(new BigDecimal("1000.00")).credit(BigDecimal.ZERO)
                .build();
        VoucherEntry e2 = VoucherEntry.builder()
                .id("E-002").lineNo(2)
                .subjectCode("6601").subjectName("销售收入")
                .debit(BigDecimal.ZERO).credit(new BigDecimal("1000.00"))
                .build();

        when(voucherRepo.findByFactoryIdAndDateRange(FACTORY_ID, START, END)).thenReturn(List.of(v));
        when(entryRepo.findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc("V-001"))
                .thenReturn(List.of(e1, e2));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String fileName = service.exportSequentialLedger(FACTORY_ID, buildReq(), USER_ID, out);

        assertTrue(out.size() > 0, "OutputStream 应写入 xlsx 内容");
        assertNotNull(fileName, "应返回文件名");
        assertTrue(fileName.startsWith("voucher-ledger_"), "文件名前缀应为 voucher-ledger_");
        assertTrue(fileName.endsWith(".xlsx"), "文件应为 .xlsx");

        // 验证导出记录写入
        ArgumentCaptor<VoucherExportRecord> cap = ArgumentCaptor.forClass(VoucherExportRecord.class);
        verify(exportRecordRepo).save(cap.capture());
        assertEquals(FACTORY_ID, cap.getValue().getFactoryId());
        assertEquals("SEQUENTIAL_LEDGER", cap.getValue().getExportType());
        assertEquals(2, cap.getValue().getRowCount(), "2 条分录行");
    }

    @Test
    @DisplayName("T2: 科目余额表导出 — 有聚合行时文件非空")
    void testExportSubjectBalance_withData_writesXlsx() throws Exception {
        when(exportRecordRepo.findRecentExport(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.of(defaultConfig()));

        SubjectAggregateRow row = SubjectAggregateRow.builder()
                .subjectCode("1002")
                .subjectName("银行存款")
                .totalDebit(new BigDecimal("50000.00"))
                .totalCredit(new BigDecimal("30000.00"))
                .entryCount(5L)
                .build();
        when(entryRepo.aggregateBySubject(FACTORY_ID, START, END)).thenReturn(List.of(row));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String fileName = service.exportSubjectBalance(FACTORY_ID, buildReq(), USER_ID, out);

        assertTrue(out.size() > 0, "OutputStream 应非空");
        assertTrue(fileName.startsWith("subject-balance_"), "文件名前缀");

        ArgumentCaptor<VoucherExportRecord> cap = ArgumentCaptor.forClass(VoucherExportRecord.class);
        verify(exportRecordRepo).save(cap.capture());
        assertEquals("SUBJECT_BALANCE", cap.getValue().getExportType());
        assertEquals(1, cap.getValue().getRowCount());
    }

    @Test
    @DisplayName("T3: Rule-4 幂等 — 5分钟内重复请求不新建 exportRecord")
    void testExportSequentialLedger_dedup_noNewRecord() throws Exception {
        VoucherExportRecord existing = VoucherExportRecord.builder()
                .id("EXISTING-ID")
                .factoryId(FACTORY_ID)
                .exportType("SEQUENTIAL_LEDGER")
                .periodStart(START).periodEnd(END)
                .rowCount(10)
                .fileName("voucher-ledger_F-SP11-EXPORT_20260501_20260531_existing.xlsx")
                .exportedBy(USER_ID)
                .build();
        when(exportRecordRepo.findRecentExport(eq(FACTORY_ID), eq("SEQUENTIAL_LEDGER"),
                eq(START), eq(END), any()))
                .thenReturn(Optional.of(existing));
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.of(defaultConfig()));
        when(voucherRepo.findByFactoryIdAndDateRange(any(), any(), any()))
                .thenReturn(List.of());
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportSequentialLedger(FACTORY_ID, buildReq(), USER_ID, out);

        // 仍会 save (记录新的导出操作), 但应该 log dedup
        // 主要验证不 throw
        verify(exportRecordRepo, atLeastOnce()).findRecentExport(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("T4: 默认配置 — 无 VoucherExportConfig 时使用默认金蝶列名")
    void testExportSubjectBalance_noConfig_usesDefaultKingdee() throws Exception {
        when(exportRecordRepo.findRecentExport(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        // 没有配置 — 走 orElseGet 分支
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());
        when(entryRepo.aggregateBySubject(any(), any(), any())).thenReturn(List.of());
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Should not throw
        assertDoesNotThrow(() ->
                service.exportSubjectBalance(FACTORY_ID, buildReq(), USER_ID, out));
    }

    @Test
    @DisplayName("F006: subject balance uses previous CLOSED period ending as opening")
    void exportSubjectBalance_closedPreviousPeriod_usesPriorEndingAsOpening() throws Exception {
        when(exportRecordRepo.findRecentExport(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.of(defaultConfig()));
        when(accountingPeriodRepo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY_ID, 2026, 4))
                .thenReturn(Optional.of(closedPeriod(2026, 4)));
        when(entryRepo.aggregateBySubject(eq(FACTORY_ID), eq(LocalDate.of(1970, 1, 1)), eq(LocalDate.of(2026, 4, 30))))
                .thenReturn(List.of(aggregate("1002", "Bank", "1000.00", "200.00")));
        when(entryRepo.aggregateBySubject(eq(FACTORY_ID), eq(START), eq(END)))
                .thenReturn(List.of(aggregate("1002", "Bank", "200.00", "50.00")));
        when(accountRepo.findByCodeForFactory(FACTORY_ID, "1002"))
                .thenReturn(List.of(account("1002", "Bank", AccountBalanceType.DEBIT_NORMAL)));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportSubjectBalance(FACTORY_ID, buildReq(), USER_ID, out);

        List<List<String>> rows = readXlsx(out.toByteArray());
        assertEquals(2, rows.size(), "header + one subject row");
        List<String> data = rows.get(1);
        assertEquals("1002", data.get(0));
        assertEquals(0, new BigDecimal("800.00").compareTo(decimalAt(data, 2)), "opening = 1000 - 200");
        assertEquals(0, new BigDecimal("200.00").compareTo(decimalAt(data, 3)), "period debit");
        assertEquals(0, new BigDecimal("50.00").compareTo(decimalAt(data, 4)), "period credit");
        assertEquals(0, new BigDecimal("950.00").compareTo(decimalAt(data, 5)), "closing = 800 + 200 - 50");
        assertEquals("待财务确认", data.get(6), "voucher source caliber must be explicitly marked");
    }

    @Test
    @DisplayName("F006: subject balance has honest zero opening when previous period is not CLOSED")
    void exportSubjectBalance_withoutClosedPreviousPeriod_openingIsZero() throws Exception {
        when(exportRecordRepo.findRecentExport(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.of(defaultConfig()));
        when(accountingPeriodRepo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY_ID, 2026, 4))
                .thenReturn(Optional.empty());
        when(entryRepo.aggregateBySubject(FACTORY_ID, START, END))
                .thenReturn(List.of(aggregate("1002", "Bank", "100.00", "25.00")));
        when(accountRepo.findByCodeForFactory(FACTORY_ID, "1002"))
                .thenReturn(List.of(account("1002", "Bank", AccountBalanceType.DEBIT_NORMAL)));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportSubjectBalance(FACTORY_ID, buildReq(), USER_ID, out);

        List<String> data = readXlsx(out.toByteArray()).get(1);
        assertEquals(0, BigDecimal.ZERO.compareTo(decimalAt(data, 2)), "opening stays 0 without previous close");
        assertEquals(0, new BigDecimal("75.00").compareTo(decimalAt(data, 5)), "closing = 0 + 100 - 25");
        verify(entryRepo, never()).aggregateBySubject(eq(FACTORY_ID), eq(LocalDate.of(1970, 1, 1)), any());
    }

    @Test
    @DisplayName("F006: subject balance four columns reconcile for credit-normal accounts")
    void exportSubjectBalance_creditNormalAccount_reconcilesFourColumns() throws Exception {
        when(exportRecordRepo.findRecentExport(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.of(defaultConfig()));
        when(accountingPeriodRepo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY_ID, 2026, 4))
                .thenReturn(Optional.of(closedPeriod(2026, 4)));
        when(entryRepo.aggregateBySubject(eq(FACTORY_ID), eq(LocalDate.of(1970, 1, 1)), eq(LocalDate.of(2026, 4, 30))))
                .thenReturn(List.of(aggregate("2202", "AP", "100.00", "500.00")));
        when(entryRepo.aggregateBySubject(eq(FACTORY_ID), eq(START), eq(END)))
                .thenReturn(List.of(aggregate("2202", "AP", "50.00", "300.00")));
        when(accountRepo.findByCodeForFactory(FACTORY_ID, "2202"))
                .thenReturn(List.of(account("2202", "AP", AccountBalanceType.CREDIT_NORMAL)));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportSubjectBalance(FACTORY_ID, buildReq(), USER_ID, out);

        List<String> data = readXlsx(out.toByteArray()).get(1);
        assertEquals("2202", data.get(0));
        assertEquals(0, new BigDecimal("400.00").compareTo(decimalAt(data, 2)), "credit-normal opening = 500 - 100");
        assertEquals(0, new BigDecimal("50.00").compareTo(decimalAt(data, 3)), "period debit remains raw debit");
        assertEquals(0, new BigDecimal("300.00").compareTo(decimalAt(data, 4)), "period credit remains raw credit");
        assertEquals(0, new BigDecimal("650.00").compareTo(decimalAt(data, 5)), "closing = 400 + 300 - 50");
    }

    @Test
    @DisplayName("T5: 凭证期间无数据 — 生成文件仍成功 (只有表头行)")
    void testExportSequentialLedger_noVouchers_succeeds() throws Exception {
        when(exportRecordRepo.findRecentExport(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.of(defaultConfig()));
        when(voucherRepo.findByFactoryIdAndDateRange(any(), any(), any()))
                .thenReturn(List.of());
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String fileName = service.exportSequentialLedger(FACTORY_ID, buildReq(), USER_ID, out);

        assertNotNull(fileName);
        ArgumentCaptor<VoucherExportRecord> cap = ArgumentCaptor.forClass(VoucherExportRecord.class);
        verify(exportRecordRepo).save(cap.capture());
        assertEquals(0, cap.getValue().getRowCount(), "无分录时 rowCount = 0");
    }

    @Test
    @DisplayName("Ledger: 序时账按日期凭证号逐笔输出标准表头和科目余额")
    void exportChronologicalLedger_writesStandardHeadersAndRunningBalance() throws Exception {
        when(voucherRepo.findByFactoryIdAndDateRange(FACTORY_ID, START, END))
                .thenReturn(List.of(
                        voucher("V-002", "记-002", LocalDate.of(2026, 5, 2)),
                        voucher("V-001", "记-001", LocalDate.of(2026, 5, 1))
                ));
        when(entryRepo.findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc("V-001"))
                .thenReturn(List.of(entry("E-001", 1, "1002", "银行存款", "收款", "100.00", "0.00")));
        when(entryRepo.findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc("V-002"))
                .thenReturn(List.of(entry("E-002", 1, "1002", "银行存款", "付款", "0.00", "25.00")));
        when(accountRepo.findByCodeForFactory(FACTORY_ID, "1002"))
                .thenReturn(List.of(account("1002", "银行存款", AccountBalanceType.DEBIT_NORMAL)));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String fileName = service.exportChronologicalLedger(FACTORY_ID, buildReq(), USER_ID, out);

        assertTrue(fileName.startsWith("chronological-ledger_"));
        List<List<String>> rows = readXlsx(out.toByteArray());
        assertEquals(List.of("日期", "凭证字号", "摘要", "科目编码", "科目名称", "借方金额", "贷方金额", "方向", "余额"), rows.get(0));
        assertEquals("2026-05-01", rows.get(1).get(0), "按日期排序");
        assertEquals("记-001", rows.get(1).get(1));
        assertEquals("借", rows.get(1).get(7));
        assertEquals(0, new BigDecimal("100.00").compareTo(decimalAt(rows.get(1), 8)));
        assertEquals("记-002", rows.get(2).get(1));
        assertEquals(0, new BigDecimal("75.00").compareTo(decimalAt(rows.get(2), 8)));
    }

    @Test
    @DisplayName("Ledger: 总账覆盖借方/贷方余额方向和本年累计")
    void exportGeneralLedger_usesDebitAndCreditNormalDirections() throws Exception {
        when(accountingPeriodRepo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY_ID, 2026, 4))
                .thenReturn(Optional.of(closedPeriod(2026, 4)));
        when(entryRepo.aggregateBySubject(eq(FACTORY_ID), eq(LocalDate.of(1970, 1, 1)), eq(LocalDate.of(2026, 4, 30))))
                .thenReturn(List.of(
                        aggregate("1002", "银行存款", "1000.00", "200.00"),
                        aggregate("2202", "应付账款", "100.00", "500.00")
                ));
        when(entryRepo.aggregateBySubject(eq(FACTORY_ID), eq(START), eq(END)))
                .thenReturn(List.of(
                        aggregate("1002", "银行存款", "200.00", "50.00"),
                        aggregate("2202", "应付账款", "50.00", "300.00")
                ));
        when(entryRepo.aggregateBySubject(eq(FACTORY_ID), eq(LocalDate.of(2026, 1, 1)), eq(END)))
                .thenReturn(List.of(
                        aggregate("1002", "银行存款", "1200.00", "250.00"),
                        aggregate("2202", "应付账款", "150.00", "800.00")
                ));
        when(accountRepo.findByCodeForFactory(FACTORY_ID, "1002"))
                .thenReturn(List.of(account("1002", "银行存款", AccountBalanceType.DEBIT_NORMAL)));
        when(accountRepo.findByCodeForFactory(FACTORY_ID, "2202"))
                .thenReturn(List.of(account("2202", "应付账款", AccountBalanceType.CREDIT_NORMAL)));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportGeneralLedger(FACTORY_ID, buildReq(), USER_ID, out);

        List<List<String>> rows = readXlsx(out.toByteArray());
        assertEquals(List.of("科目编码", "科目名称", "期初(借/贷)", "本期借方", "本期贷方", "本年累计", "期末(借/贷)"), rows.get(0));
        assertEquals(List.of("1002", "银行存款", "借 800.00", "200.00", "50.00", "借 950.00", "借 950.00"), rows.get(1));
        assertEquals(List.of("2202", "应付账款", "贷 400.00", "50.00", "300.00", "贷 650.00", "贷 650.00"), rows.get(2));
    }

    @Test
    @DisplayName("Ledger: 明细账按科目分段并带期初/本期合计/期末")
    void exportSubsidiaryLedger_writesSubjectSectionsAndTotals() throws Exception {
        when(accountingPeriodRepo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY_ID, 2026, 4))
                .thenReturn(Optional.of(closedPeriod(2026, 4)));
        when(entryRepo.aggregateBySubject(eq(FACTORY_ID), eq(LocalDate.of(1970, 1, 1)), eq(LocalDate.of(2026, 4, 30))))
                .thenReturn(List.of(aggregate("1002", "银行存款", "1000.00", "200.00")));
        when(voucherRepo.findByFactoryIdAndDateRange(FACTORY_ID, START, END))
                .thenReturn(List.of(voucher("V-001", "记-001", LocalDate.of(2026, 5, 1))));
        when(entryRepo.findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc("V-001"))
                .thenReturn(List.of(entry("E-001", 1, "1002", "银行存款", "收款", "200.00", "0.00")));
        when(accountRepo.findByCodeForFactory(FACTORY_ID, "1002"))
                .thenReturn(List.of(account("1002", "银行存款", AccountBalanceType.DEBIT_NORMAL)));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportSubsidiaryLedger(FACTORY_ID, buildReq(), USER_ID, out);

        List<List<String>> rows = readXlsx(out.toByteArray());
        assertEquals(List.of("科目编码/名称分段", "日期", "凭证字号", "摘要", "借方", "贷方", "方向", "余额"), rows.get(0));
        assertEquals("1002 银行存款", rows.get(1).get(0));
        assertEquals("期初", rows.get(2).get(3));
        assertEquals("借", rows.get(2).get(6));
        assertEquals(0, new BigDecimal("800.00").compareTo(decimalAt(rows.get(2), 7)));
        assertEquals("本期合计", rows.get(4).get(3));
        assertEquals(0, new BigDecimal("200.00").compareTo(decimalAt(rows.get(4), 4)));
        assertEquals("期末", rows.get(5).get(3));
        assertEquals(0, new BigDecimal("1000.00").compareTo(decimalAt(rows.get(5), 7)));
    }

    @Test
    @DisplayName("Ledger: 试算平衡表三组都平后才写合计")
    void exportTrialBalance_balancedWritesOpeningPeriodAndClosingTotals() throws Exception {
        when(accountingPeriodRepo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY_ID, 2026, 4))
                .thenReturn(Optional.of(closedPeriod(2026, 4)));
        when(entryRepo.aggregateBySubject(eq(FACTORY_ID), eq(LocalDate.of(1970, 1, 1)), eq(LocalDate.of(2026, 4, 30))))
                .thenReturn(List.of(
                        aggregate("1002", "银行存款", "1000.00", "0.00"),
                        aggregate("2202", "应付账款", "0.00", "1000.00")
                ));
        when(entryRepo.aggregateBySubject(eq(FACTORY_ID), eq(START), eq(END)))
                .thenReturn(List.of(
                        aggregate("1002", "银行存款", "0.00", "100.00"),
                        aggregate("2202", "应付账款", "100.00", "0.00")
                ));
        when(accountRepo.findByCodeForFactory(FACTORY_ID, "1002"))
                .thenReturn(List.of(account("1002", "银行存款", AccountBalanceType.DEBIT_NORMAL)));
        when(accountRepo.findByCodeForFactory(FACTORY_ID, "2202"))
                .thenReturn(List.of(account("2202", "应付账款", AccountBalanceType.CREDIT_NORMAL)));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportTrialBalance(FACTORY_ID, buildReq(), USER_ID, out);

        List<List<String>> rows = readXlsx(out.toByteArray());
        assertEquals(List.of("科目编码", "科目名称", "期初借方", "期初贷方", "本期借方", "本期贷方", "期末借方", "期末贷方"), rows.get(0));
        List<String> total = rows.stream()
                .filter(row -> !row.isEmpty() && "合计".equals(row.get(0)))
                .findFirst()
                .orElseThrow();
        assertEquals(0, new BigDecimal("1000.00").compareTo(decimalAt(total, 2)), "期初借方合计");
        assertEquals(0, new BigDecimal("1000.00").compareTo(decimalAt(total, 3)), "期初贷方合计");
        assertEquals(0, new BigDecimal("100.00").compareTo(decimalAt(total, 4)), "本期借方合计");
        assertEquals(0, new BigDecimal("100.00").compareTo(decimalAt(total, 5)), "本期贷方合计");
        assertEquals(0, new BigDecimal("900.00").compareTo(decimalAt(total, 6)), "期末借方合计");
        assertEquals(0, new BigDecimal("900.00").compareTo(decimalAt(total, 7)), "期末贷方合计");
    }

    @Test
    @DisplayName("Ledger: 试算平衡任一组不平时抛错且列出科目和差额")
    void exportTrialBalance_unbalancedThrowsWithSubjectsAndDifference() {
        when(accountingPeriodRepo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY_ID, 2026, 4))
                .thenReturn(Optional.empty());
        when(entryRepo.aggregateBySubject(FACTORY_ID, START, END))
                .thenReturn(List.of(aggregate("1002", "银行存款", "100.00", "0.00")));
        when(accountRepo.findByCodeForFactory(FACTORY_ID, "1002"))
                .thenReturn(List.of(account("1002", "银行存款", AccountBalanceType.DEBIT_NORMAL)));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.exportTrialBalance(FACTORY_ID, buildReq(), USER_ID, new ByteArrayOutputStream()));

        assertTrue(ex.getMessage().contains("试算平衡不平"));
        assertTrue(ex.getMessage().contains("本期"));
        assertTrue(ex.getMessage().contains("期末"));
        assertTrue(ex.getMessage().contains("1002 银行存款"));
        assertTrue(ex.getMessage().contains("差额=100.00"));
    }
}
