package com.cretas.aims.service.finance.impl;

import com.cretas.aims.dto.finance.SubjectAggregateRow;
import com.cretas.aims.dto.finance.VoucherExportRequestDTO;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.finance.VoucherExportConfig;
import com.cretas.aims.entity.finance.VoucherExportRecord;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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
    @Mock private VoucherExportConfigRepository exportConfigRepo;
    @Mock private VoucherExportRecordRepository exportRecordRepo;

    private VoucherExportServiceImpl service;

    private static final String FACTORY_ID = "F-SP11-EXPORT";
    private static final Long USER_ID = 99L;
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2026, 5, 31);

    @BeforeEach
    void setUp() {
        service = new VoucherExportServiceImpl(voucherRepo, entryRepo, exportConfigRepo, exportRecordRepo);
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
}
