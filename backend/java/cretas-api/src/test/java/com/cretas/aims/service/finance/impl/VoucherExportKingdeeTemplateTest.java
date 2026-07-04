package com.cretas.aims.service.finance.impl;

import com.cretas.aims.dto.finance.VoucherExportRequestDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherExportFileFormat;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.finance.VoucherExportConfig;
import com.cretas.aims.entity.finance.VoucherExportRecord;
import com.cretas.aims.repository.AccountRepository;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.DepartmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.UserRepository;
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
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @Mock private CustomerRepository customerRepo;
    @Mock private SupplierRepository supplierRepo;
    @Mock private DepartmentRepository departmentRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private UserRepository userRepo;

    private VoucherExportServiceImpl service;

    private static final String FACTORY_ID = "F-KINGDEE-TEMPLATE";
    private static final Long USER_ID = 99L;
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2026, 5, 31);

    @BeforeEach
    void setUp() {
        service = new VoucherExportServiceImpl(voucherRepo, entryRepo, accountRepo,
                accountingPeriodRepo, exportConfigRepo, exportRecordRepo,
                materialBatchRepo, materialConsumptionRepo, semiFinishedInventoryRepo, finishedGoodsBatchRepo,
                customerRepo, supplierRepo, departmentRepo, productTypeRepo, userRepo);
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
    @DisplayName("🔒🔒 outflow gate: 金蝶 import template carries POSTED only — DRAFT + VOID excluded")
    void exportKingdeeImportTemplate_postedOnly_excludesDraftAndVoid() throws Exception {
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(FACTORY_ID, VoucherTargetSystem.KINGDEE_YXSKY))
                .thenReturn(Optional.empty());
        Voucher posted = voucherWithStatus("V-POSTED", "1001", LocalDate.of(2026, 5, 10), VoucherStatus.POSTED);
        Voucher draft = voucherWithStatus("V-DRAFT", "1002", LocalDate.of(2026, 5, 11), VoucherStatus.DRAFT);
        Voucher voided = voucherWithStatus("V-VOID", "1003", LocalDate.of(2026, 5, 12), VoucherStatus.VOID);
        when(voucherRepo.findByFactoryIdAndDateRange(FACTORY_ID, START, END))
                .thenReturn(List.of(posted, draft, voided));
        // Only the POSTED voucher's entries are ever loaded (filter runs before entry fetch).
        when(entryRepo.findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc("V-POSTED"))
                .thenReturn(List.of(entry("E-P", 1, "1002", "银行存款", "已过账凭证", "100.00", "0.00", "")));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportKingdeeImportTemplate(FACTORY_ID, buildReq(), USER_ID, out);

        Sheet sheet = readFirstSheet(out.toByteArray());
        // Header (row 0) + exactly one data row (the POSTED voucher's single entry).
        assertEquals(1, sheet.getLastRowNum(), "only the POSTED voucher's row must be exported");
        assertEquals("1001", cellAsString(sheet.getRow(1).getCell(1)), "exported row is the POSTED voucher (1001)");

        ArgumentCaptor<VoucherExportRecord> record = ArgumentCaptor.forClass(VoucherExportRecord.class);
        verify(exportRecordRepo).save(record.capture());
        assertEquals(1, record.getValue().getRowCount(),
                "DRAFT (1002) and VOID (1003) must NOT leak into the customer's real 金蝶 books");
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

    @Test
    @DisplayName("KIS/K3 profile writes 18-column standard-format-voucher layout distinct from 云星空")
    void exportKingdeeImportTemplate_kis_writesStandardFormatVoucherColumns() throws Exception {
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(FACTORY_ID, VoucherTargetSystem.KINGDEE_KIS))
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
        String fileName = service.exportKingdeeImportTemplate(FACTORY_ID, buildReq(VoucherTargetSystem.KINGDEE_KIS), USER_ID, out);

        assertTrue(fileName.startsWith("kingdee-kis-import-template_"));
        Sheet sheet = readFirstSheet(out.toByteArray());
        assertEquals("凭证导入", sheet.getSheetName());
        assertRow(sheet.getRow(0),
                "日期", "凭证字", "凭证号", "附单据数", "摘要", "科目编码", "科目名称",
                "借方金额", "贷方金额", "币别", "汇率", "原币金额", "数量", "单价",
                "核算类别", "核算编码", "核算名称", "制单人");

        Row debit = sheet.getRow(1);
        assertEquals("2026-05-10", cellAsString(debit.getCell(0)));
        assertEquals("记", cellAsString(debit.getCell(1)));
        assertEquals("1001", cellAsString(debit.getCell(2)));
        assertEquals("", cellAsString(debit.getCell(3)), "附单据数 honest-blank, not fabricated");
        assertEquals("收款", cellAsString(debit.getCell(4)));
        assertEquals("1002", cellAsString(debit.getCell(5)));
        assertEquals("银行存款", cellAsString(debit.getCell(6)));
        assertEquals("12.35", cellAsString(debit.getCell(7)), "HALF_UP rounds 12.345 to 12.35");
        assertEquals("", cellAsString(debit.getCell(8)), "debit row credit amount must be blank, not 0.00");
        assertEquals("人民币", cellAsString(debit.getCell(9)));
        assertEquals("1", cellAsString(debit.getCell(10)));
        assertEquals("", cellAsString(debit.getCell(11)), "原币金额 honest-blank (single-currency system)");
        assertEquals("", cellAsString(debit.getCell(12)), "数量 honest-blank (no qty dimension tracked)");
        assertEquals("", cellAsString(debit.getCell(13)), "单价 honest-blank");
        assertEquals("", cellAsString(debit.getCell(14)), "核算类别 honest-blank");
        assertEquals("", cellAsString(debit.getCell(15)), "核算编码 honest-blank");
        assertEquals("客户:张三", cellAsString(debit.getCell(16)), "核算名称 reuses same auxiliaryEntityId as 云星空 辅助核算");
        assertEquals("", cellAsString(debit.getCell(17)), "制单人 honest-blank (no username resolution wired)");

        ArgumentCaptor<VoucherExportRecord> record = ArgumentCaptor.forClass(VoucherExportRecord.class);
        verify(exportRecordRepo).save(record.capture());
        assertEquals("KINGDEE_IMPORT_TEMPLATE", record.getValue().getExportType());
        assertEquals(VoucherTargetSystem.KINGDEE_KIS, record.getValue().getTargetSystem());
        assertEquals(2, record.getValue().getRowCount());
    }

    @Test
    @DisplayName("KIS/K3 config override changes reusable column names, keeps KIS-only literal columns")
    void exportKingdeeImportTemplate_kisConfigOverride_changesReusableHeaders() throws Exception {
        VoucherExportConfig config = VoucherExportConfig.builder()
                .factoryId(FACTORY_ID)
                .targetSystem(VoucherTargetSystem.KINGDEE_KIS)
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
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(FACTORY_ID, VoucherTargetSystem.KINGDEE_KIS))
                .thenReturn(Optional.of(config));
        when(voucherRepo.findByFactoryIdAndDateRange(FACTORY_ID, START, END)).thenReturn(java.util.List.of());
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportKingdeeImportTemplate(FACTORY_ID, buildReq(VoucherTargetSystem.KINGDEE_KIS), USER_ID, out);

        assertRow(readFirstSheet(out.toByteArray()).getRow(0),
                "业务日期", "凭证字", "凭证编号", "附单据数", "分录摘要", "会计科目编码", "会计科目名称",
                "借方", "贷方", "币种", "汇率", "原币金额", "数量", "单价",
                "核算类别", "核算编码", "核算维度", "制单人");
    }

    @Test
    @DisplayName("云星空 auxiliary UUID resolves to 类别:名称, not raw UUID")
    void exportKingdeeImportTemplate_yxsky_resolvesAuxiliaryUuidToNameCategory() throws Exception {
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(FACTORY_ID, VoucherTargetSystem.KINGDEE_YXSKY))
                .thenReturn(Optional.empty());
        String customerUuid = "11111111-2222-3333-4444-555555555555";
        Voucher voucher = voucher("V-002", "1002", LocalDate.of(2026, 5, 12));
        when(voucherRepo.findByFactoryIdAndDateRange(FACTORY_ID, START, END)).thenReturn(List.of(voucher));
        when(entryRepo.findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc("V-002"))
                .thenReturn(List.of(
                        entryAux("E-1", 1, "1122", "应收账款", "销售", "100.00", "0.00",
                                AuxiliaryType.CUSTOMER, customerUuid),
                        entry("E-2", 2, "6001", "主营业务收入", "销售", "0.00", "100.00", "")
                ));
        when(customerRepo.findAllById(any())).thenReturn(List.of(customer(customerUuid, "张三海鲜", "C001")));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportKingdeeImportTemplate(FACTORY_ID, buildReq(VoucherTargetSystem.KINGDEE_YXSKY), USER_ID, out);

        Sheet sheet = readFirstSheet(out.toByteArray());
        String aux = cellAsString(sheet.getRow(1).getCell(10)); // 辅助核算 column
        assertEquals("客户:张三海鲜", aux, "UUID must be resolved to 类别:名称");
        assertFalse(aux.contains(customerUuid), "must never emit the raw UUID");
    }

    @Test
    @DisplayName("KIS auxiliary resolves to 核算类别 + 核算编码 + 核算名称 (not UUID)")
    void exportKingdeeImportTemplate_kis_resolvesAuxiliaryCategoryCodeName() throws Exception {
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(FACTORY_ID, VoucherTargetSystem.KINGDEE_KIS))
                .thenReturn(Optional.empty());
        String customerUuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        Voucher voucher = voucher("V-003", "1003", LocalDate.of(2026, 5, 13));
        when(voucherRepo.findByFactoryIdAndDateRange(FACTORY_ID, START, END)).thenReturn(List.of(voucher));
        when(entryRepo.findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc("V-003"))
                .thenReturn(List.of(
                        entryAux("E-1", 1, "1122", "应收账款", "销售", "200.00", "0.00",
                                AuxiliaryType.CUSTOMER, customerUuid)
                ));
        when(customerRepo.findAllById(any())).thenReturn(List.of(customer(customerUuid, "李四餐饮", "C009")));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportKingdeeImportTemplate(FACTORY_ID, buildReq(VoucherTargetSystem.KINGDEE_KIS), USER_ID, out);

        Row row = readFirstSheet(out.toByteArray()).getRow(1);
        assertEquals("客户", cellAsString(row.getCell(14)), "核算类别 = 客户");
        assertEquals("C009", cellAsString(row.getCell(15)), "核算编码 = 实体 code");
        assertEquals("李四餐饮", cellAsString(row.getCell(16)), "核算名称 = 实体名, not UUID");
        assertFalse(cellAsString(row.getCell(16)).contains(customerUuid));
    }

    @Test
    @DisplayName("unresolvable auxiliary entity leaves name blank, never emits UUID")
    void exportKingdeeImportTemplate_unresolvableAuxiliary_blankNotUuid() throws Exception {
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(FACTORY_ID, VoucherTargetSystem.KINGDEE_KIS))
                .thenReturn(Optional.empty());
        String deletedUuid = "dddddddd-0000-0000-0000-000000000000";
        Voucher voucher = voucher("V-004", "1004", LocalDate.of(2026, 5, 14));
        when(voucherRepo.findByFactoryIdAndDateRange(FACTORY_ID, START, END)).thenReturn(List.of(voucher));
        when(entryRepo.findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc("V-004"))
                .thenReturn(List.of(
                        entryAux("E-1", 1, "1122", "应收账款", "销售", "50.00", "0.00",
                                AuxiliaryType.CUSTOMER, deletedUuid)
                ));
        when(customerRepo.findAllById(any())).thenReturn(List.of()); // entity gone
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportKingdeeImportTemplate(FACTORY_ID, buildReq(VoucherTargetSystem.KINGDEE_KIS), USER_ID, out);

        Row row = readFirstSheet(out.toByteArray()).getRow(1);
        assertEquals("客户", cellAsString(row.getCell(14)), "核算类别 still known from type");
        assertEquals("", cellAsString(row.getCell(15)), "核算编码 blank when unresolved");
        assertEquals("", cellAsString(row.getCell(16)), "核算名称 blank (NOT the UUID)");
    }

    @Test
    @DisplayName("DBF format writes a valid dBASE file with KIS field schema and a parseable record")
    void exportKingdeeImportTemplate_dbf_writesValidDbaseFile() throws Exception {
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());
        String customerUuid = "12341234-1234-1234-1234-123412341234";
        Voucher voucher = voucher("V-005", "1005", LocalDate.of(2026, 5, 15));
        when(voucherRepo.findByFactoryIdAndDateRange(FACTORY_ID, START, END)).thenReturn(List.of(voucher));
        when(entryRepo.findByVoucherIdAndDeletedAtIsNullOrderByLineNoAsc("V-005"))
                .thenReturn(List.of(
                        entryAux("E-1", 1, "1122", "应收账款", "销售收款", "12.35", "0.00",
                                AuxiliaryType.CUSTOMER, customerUuid),
                        entry("E-2", 2, "6001", "主营业务收入", "销售收款", "0.00", "12.35", "")
                ));
        when(customerRepo.findAllById(any())).thenReturn(List.of(customer(customerUuid, "王五商贸", "C077")));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VoucherExportRequestDTO req = VoucherExportRequestDTO.builder()
                .startDate(START).endDate(END)
                .targetSystem(VoucherTargetSystem.KINGDEE_KIS)
                .exportFormat(VoucherExportFileFormat.DBF)
                .build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String fileName = service.exportKingdeeImportTemplate(FACTORY_ID, req, USER_ID, out);
        assertTrue(fileName.endsWith(".dbf"), "DBF filename extension");
        assertTrue(fileName.startsWith("kingdee-kis-voucher_"));

        ParsedDbf dbf = parseDbf(out.toByteArray());
        // field schema present
        assertTrue(dbf.fieldNames.containsAll(List.of(
                "FDATE", "FTRANSDATE", "FPERIOD", "FGROUP", "FNUM", "FENTRYID", "FEXP", "FACCTID",
                "FCLSNAME1", "FOBJID1", "FOBJNAME1", "FDC", "FCYID", "FEXCHRATE",
                "FFCYAMT", "FDEBIT", "FCREDIT", "FAMOUNT", "FQUANTITY", "FPRICE", "FPREPARE")),
                "all KIS DBF fields present; got " + dbf.fieldNames);
        assertEquals(2, dbf.records.size(), "two entries → two DBF records");

        Map<String, String> debit = dbf.records.get(0);
        assertEquals("20260515", debit.get("FDATE"));
        assertEquals("5", debit.get("FPERIOD"), "FPERIOD = voucher month");
        assertEquals("记", debit.get("FGROUP"));
        assertEquals("1005", debit.get("FNUM"));
        assertEquals("0", debit.get("FENTRYID"), "分录号 0-based");
        assertEquals("销售收款", debit.get("FEXP"));
        assertEquals("1122", debit.get("FACCTID"));
        assertEquals("客户", debit.get("FCLSNAME1"), "核算类别名 resolved");
        assertEquals("C077", debit.get("FOBJID1"), "核算项目代码 resolved");
        assertEquals("王五商贸", debit.get("FOBJNAME1"), "核算项目名 resolved, not UUID");
        assertFalse(debit.get("FOBJNAME1").contains(customerUuid));
        assertEquals("1", debit.get("FDC"), "debit → FDC=1");
        assertEquals("RMB", debit.get("FCYID"));
        assertEquals("12.35", debit.get("FDEBIT"), "HALF_UP 12.345→12.35");
        assertEquals("0.00", debit.get("FCREDIT"));
        assertEquals("12.35", debit.get("FAMOUNT"));

        Map<String, String> credit = dbf.records.get(1);
        assertEquals("1", credit.get("FENTRYID"), "second entry in same voucher → 1");
        assertEquals("0", credit.get("FDC"), "credit → FDC=0");
        assertEquals("12.35", credit.get("FCREDIT"));
    }

    @Test
    @DisplayName("XLS format writes legacy BIFF workbook (.xls) for KIS专业版")
    void exportKingdeeImportTemplate_xls_writesBiff() throws Exception {
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(FACTORY_ID, VoucherTargetSystem.KINGDEE_KIS))
                .thenReturn(Optional.empty());
        when(voucherRepo.findByFactoryIdAndDateRange(FACTORY_ID, START, END)).thenReturn(List.of());
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VoucherExportRequestDTO req = VoucherExportRequestDTO.builder()
                .startDate(START).endDate(END)
                .targetSystem(VoucherTargetSystem.KINGDEE_KIS)
                .exportFormat(VoucherExportFileFormat.XLS)
                .build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String fileName = service.exportKingdeeImportTemplate(FACTORY_ID, req, USER_ID, out);
        assertTrue(fileName.endsWith(".xls"), "legacy BIFF extension");
        // BIFF (HSSF) magic: D0 CF 11 E0 (OLE2 compound file)
        byte[] bytes = out.toByteArray();
        assertEquals((byte) 0xD0, bytes[0]);
        assertEquals((byte) 0xCF, bytes[1]);
        try (org.apache.poi.hssf.usermodel.HSSFWorkbook wb =
                     new org.apache.poi.hssf.usermodel.HSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertRow(sheet.getRow(0),
                    "日期", "凭证字", "凭证号", "附单据数", "摘要", "科目编码", "科目名称",
                    "借方金额", "贷方金额", "币别", "汇率", "原币金额", "数量", "单价",
                    "核算类别", "核算编码", "核算名称", "制单人");
        }
    }

    private Customer customer(String id, String name, String code) {
        Customer c = new Customer();
        c.setId(id);
        c.setFactoryId(FACTORY_ID);
        c.setName(name);
        c.setCode(code);
        return c;
    }

    private VoucherEntry entryAux(String id, int lineNo, String code, String name,
                                  String summary, String debit, String credit,
                                  AuxiliaryType auxType, String auxId) {
        VoucherEntry e = entry(id, lineNo, code, name, summary, debit, credit, auxId);
        e.setAuxiliaryType(auxType);
        return e;
    }

    // ---- Minimal dBASE III (.dbf) parser for test verification ----
    private record ParsedDbf(List<String> fieldNames, List<Map<String, String>> records) {
    }

    private static ParsedDbf parseDbf(byte[] bytes) {
        Charset gbk = Charset.isSupported("GBK") ? Charset.forName("GBK") : Charset.defaultCharset();
        int recordCount = u32(bytes, 4);
        int headerLength = u16(bytes, 8);
        int numFields = (headerLength - 32 - 1) / 32;

        List<String> names = new ArrayList<>();
        int[] lengths = new int[numFields];
        for (int i = 0; i < numFields; i++) {
            int off = 32 + i * 32;
            StringBuilder nm = new StringBuilder();
            for (int j = 0; j < 11 && bytes[off + j] != 0; j++) {
                nm.append((char) (bytes[off + j] & 0xFF));
            }
            names.add(nm.toString());
            lengths[i] = bytes[off + 16] & 0xFF;
        }

        List<Map<String, String>> records = new ArrayList<>();
        int pos = headerLength;
        for (int r = 0; r < recordCount; r++) {
            pos++; // deletion flag
            Map<String, String> rec = new LinkedHashMap<>();
            for (int i = 0; i < numFields; i++) {
                String raw = new String(bytes, pos, lengths[i], gbk).trim();
                rec.put(names.get(i), raw);
                pos += lengths[i];
            }
            records.add(rec);
        }
        return new ParsedDbf(names, records);
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private VoucherExportRequestDTO buildReq() {
        return buildReq(VoucherTargetSystem.KINGDEE);
    }

    private VoucherExportRequestDTO buildReq(VoucherTargetSystem targetSystem) {
        return VoucherExportRequestDTO.builder()
                .startDate(START)
                .endDate(END)
                .targetSystem(targetSystem)
                .build();
    }

    private Voucher voucher(String id, String number, LocalDate date) {
        return voucherWithStatus(id, number, date, VoucherStatus.POSTED);
    }

    private Voucher voucherWithStatus(String id, String number, LocalDate date, VoucherStatus status) {
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
                .status(status)
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
