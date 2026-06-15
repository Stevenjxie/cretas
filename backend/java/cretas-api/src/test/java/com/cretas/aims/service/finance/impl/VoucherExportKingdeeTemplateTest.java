package com.cretas.aims.service.finance.impl;

import com.cretas.aims.dto.finance.VoucherExportRequestDTO;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.finance.VoucherExportConfig;
import com.cretas.aims.entity.finance.VoucherExportRecord;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.repository.finance.VoucherExportConfigRepository;
import com.cretas.aims.repository.finance.VoucherExportRecordRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Kingdee Cloud voucher import template export")
@ExtendWith(MockitoExtension.class)
class VoucherExportKingdeeTemplateTest {

    @Mock private VoucherRepository voucherRepo;
    @Mock private VoucherEntryRepository entryRepo;
    @Mock private AccountRepository accountRepo;
    @Mock private AccountingPeriodRepository accountingPeriodRepo;
    @Mock private VoucherExportConfigRepository exportConfigRepo;
    @Mock private VoucherExportRecordRepository exportRecordRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private MaterialConsumptionRepository materialConsumptionRepo;
    @Mock private SemiFinishedInventoryRepository semiFinishedInventoryRepo;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepo;

    private VoucherExportServiceImpl service;

    private static final String FACTORY_ID = "F-KINGDEE-TEMPLATE";
    private static final Long USER_ID = 99L;
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2026, 5, 31);

    @BeforeEach
    void setUp() {
        service = new VoucherExportServiceImpl(voucherRepo, entryRepo, accountRepo,
                accountingPeriodRepo, exportConfigRepo, exportRecordRepo,
                materialBatchRepo, materialConsumptionRepo, semiFinishedInventoryRepo, finishedGoodsBatchRepo);
    }

    @Test
    @DisplayName("writes Kingdee Cloud rows with blank opposite amount columns and required constants")
    void exportKingdeeImportTemplate_writesBlankOppositeAmountColumnsAndConstants() throws Exception {
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(FACTORY_ID, VoucherTargetSystem.KINGDEE_YXSKY))
                .thenReturn(Optional.empty());
        Voucher voucher = voucher("V-001", "1001", LocalDate.of(2026, 5, 10));
        when(voucherRepo.findByFactoryIdAndDateRange(FACTORY_ID, START, END)).thenReturn(java.util.List.of(voucher));
        when(entryRepo.findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc("V-001"))
                .thenReturn(java.util.List.of(
                        entry("E-001", 1, "1002", "银行存款", "收款", "12.345", "0.00", "客户:张三"),
                        entry("E-002", 2, "6001", "主营业务收入", "收款", "0.00", "12.345", "")
                ));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String fileName = service.exportKingdeeImportTemplate(FACTORY_ID, buildReq(), USER_ID, out);

        assertTrue(fileName.startsWith("kingdee-yxsky-import-template_"));
        Sheet sheet = readFirstSheet(out.toByteArray());
        assertEquals("凭证导入", sheet.getSheetName());
        assertRow(sheet.getRow(0), "凭证字", "凭证号", "日期", "摘要", "科目编码", "科目名称", "借方金额", "贷方金额", "币别", "汇率", "辅助核算");

        Row debit = sheet.getRow(1);
        assertEquals("记", cellAsString(debit.getCell(0)));
        assertEquals("1001", cellAsString(debit.getCell(1)));
        assertEquals("2026-05-10", cellAsString(debit.getCell(2)));
        assertEquals("收款", cellAsString(debit.getCell(3)));
        assertEquals("1002", cellAsString(debit.getCell(4)));
        assertEquals("银行存款", cellAsString(debit.getCell(5)));
        assertEquals("12.35", cellAsString(debit.getCell(6)), "HALF_UP rounds 12.345 to 12.35");
        assertEquals("", cellAsString(debit.getCell(7)), "debit row credit amount must be blank, not 0.00");
        assertEquals("人民币", cellAsString(debit.getCell(8)));
        assertEquals("1", cellAsString(debit.getCell(9)));
        assertEquals("客户:张三", cellAsString(debit.getCell(10)));

        Row credit = sheet.getRow(2);
        assertEquals("", cellAsString(credit.getCell(6)), "credit row debit amount must be blank, not 0.00");
        assertEquals("12.35", cellAsString(credit.getCell(7)), "HALF_UP rounds 12.345 to 12.35");
        assertEquals("人民币", cellAsString(credit.getCell(8)));
        assertEquals("1", cellAsString(credit.getCell(9)));

        ArgumentCaptor<VoucherExportRecord> record = ArgumentCaptor.forClass(VoucherExportRecord.class);
        verify(exportRecordRepo).save(record.capture());
        assertEquals("KINGDEE_IMPORT_TEMPLATE", record.getValue().getExportType());
        assertEquals(VoucherTargetSystem.KINGDEE_YXSKY, record.getValue().getTargetSystem());
        assertEquals(2, record.getValue().getRowCount());
    }

    @Test
    @DisplayName("empty period returns header only and no synthetic rows")
    void exportKingdeeImportTemplate_emptyPeriod_headerOnly() throws Exception {
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(FACTORY_ID, VoucherTargetSystem.KINGDEE_YXSKY))
                .thenReturn(Optional.empty());
        when(voucherRepo.findByFactoryIdAndDateRange(FACTORY_ID, START, END)).thenReturn(java.util.List.of());
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportKingdeeImportTemplate(FACTORY_ID, buildReq(), USER_ID, out);

        Sheet sheet = readFirstSheet(out.toByteArray());
        assertEquals(0, sheet.getLastRowNum(), "only header row should exist");
        assertNotNull(sheet.getRow(0));

        ArgumentCaptor<VoucherExportRecord> record = ArgumentCaptor.forClass(VoucherExportRecord.class);
        verify(exportRecordRepo).save(record.capture());
        assertEquals(0, record.getValue().getRowCount());
    }

    @Test
    @DisplayName("KINGDEE_YXSKY config overrides reusable column names")
    void exportKingdeeImportTemplate_configOverride_changesReusableHeaders() throws Exception {
        VoucherExportConfig config = VoucherExportConfig.builder()
                .factoryId(FACTORY_ID)
                .targetSystem(VoucherTargetSystem.KINGDEE_YXSKY)
                .colVoucherNo("凭证编号")
                .colDate("业务日期")
                .colSummary("分录摘要")
                .colSubjectCode("会计科目编码")
                .colSubjectName("会计科目名称")
                .colDebit("借方")
                .colCredit("贷方")
                .colCurrency("币种")
                .colAuxiliary("核算维度")
                .build();
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(FACTORY_ID, VoucherTargetSystem.KINGDEE_YXSKY))
                .thenReturn(Optional.of(config));
        when(voucherRepo.findByFactoryIdAndDateRange(FACTORY_ID, START, END)).thenReturn(java.util.List.of());
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportKingdeeImportTemplate(FACTORY_ID, buildReq(), USER_ID, out);

        assertRow(readFirstSheet(out.toByteArray()).getRow(0),
                "凭证字", "凭证编号", "业务日期", "分录摘要", "会计科目编码", "会计科目名称",
                "借方", "贷方", "币种", "汇率", "核算维度");
    }

    private VoucherExportRequestDTO buildReq() {
        return VoucherExportRequestDTO.builder()
                .startDate(START)
                .endDate(END)
                .targetSystem(VoucherTargetSystem.KINGDEE)
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
                               String summary, String debit, String credit, String auxiliary) {
        return VoucherEntry.builder()
                .id(id)
                .lineNo(lineNo)
                .subjectCode(code)
                .subjectName(name)
                .description(summary)
                .debit(new BigDecimal(debit))
                .credit(new BigDecimal(credit))
                .auxiliaryEntityId(auxiliary)
                .build();
    }

    private static Sheet readFirstSheet(byte[] xlsxBytes) throws Exception {
        Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes));
        return workbook.getSheetAt(0);
    }

    private static void assertRow(Row row, String... expected) {
        assertNotNull(row);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], cellAsString(row.getCell(i)), "column " + i);
        }
    }

    private static String cellAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    yield String.valueOf((long) d);
                }
                yield BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }
}
