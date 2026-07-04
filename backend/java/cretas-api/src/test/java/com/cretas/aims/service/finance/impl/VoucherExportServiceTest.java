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
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.SemiFinishedInventory;
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
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private MaterialConsumptionRepository materialConsumptionRepo;
    @Mock private SemiFinishedInventoryRepository semiFinishedInventoryRepo;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepo;
    @Mock private SalesDeliveryItemRepository salesDeliveryItemRepo;
    @Mock private CustomerRepository customerRepo;
    @Mock private SupplierRepository supplierRepo;
    @Mock private DepartmentRepository departmentRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private UserRepository userRepo;

    private VoucherExportServiceImpl service;

    private static final String FACTORY_ID = "F-SP11-EXPORT";
    private static final Long USER_ID = 99L;
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2026, 5, 31);

    @BeforeEach
    void setUp() {
        service = new VoucherExportServiceImpl(voucherRepo, entryRepo, accountRepo,
                accountingPeriodRepo, exportConfigRepo, exportRecordRepo,
                materialBatchRepo, materialConsumptionRepo, semiFinishedInventoryRepo, finishedGoodsBatchRepo,
                salesDeliveryItemRepo,
                customerRepo, supplierRepo, departmentRepo, productTypeRepo, userRepo);
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

    private Account account(String code, String name, AccountCategory category, AccountBalanceType balanceType) {
        return Account.builder()
                .id("ACC-" + code)
                .factoryId(FACTORY_ID)
                .code(code)
                .name(name)
                .category(category)
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

    private MaterialBatch materialBatch(String id, String typeId, String batchNo,
                                        String receiptDate, String quantity, String unitPrice) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY_ID);
        batch.setMaterialTypeId(typeId);
        batch.setBatchNumber(batchNo);
        batch.setReceiptDate(LocalDate.parse(receiptDate));
        batch.setReceiptQuantity(new BigDecimal(quantity));
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setQuantityUnit("kg");
        batch.setUnitPrice(new BigDecimal(unitPrice));
        batch.setCreatedBy(USER_ID);
        return batch;
    }

    private MaterialConsumption consumption(String batchId, String typeId, String time,
                                            String quantity, String unitPrice, String totalCost) {
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setFactoryId(FACTORY_ID);
        consumption.setBatchId(batchId);
        consumption.setMaterialTypeId(typeId);
        consumption.setConsumptionTime(LocalDateTime.parse(time));
        consumption.setQuantity(new BigDecimal(quantity));
        consumption.setUnitPrice(new BigDecimal(unitPrice));
        consumption.setTotalCost(new BigDecimal(totalCost));
        consumption.setNotes("生产领用");
        consumption.setRecordedBy(USER_ID);
        return consumption;
    }

    private SemiFinishedInventory semiFinished(String batchNo, String productTypeId,
                                               String createdAt, String produced, String consumed, String unitCost) {
        return SemiFinishedInventory.builder()
                .factoryId(FACTORY_ID)
                .intermediateBatchNo(batchNo)
                .productTypeId(productTypeId)
                .producedQuantity(new BigDecimal(produced))
                .consumedQuantity(new BigDecimal(consumed))
                .availableQuantity(new BigDecimal(produced).subtract(new BigDecimal(consumed)))
                .unit("kg")
                .unitCost(new BigDecimal(unitCost))
                .createdAt(LocalDateTime.parse(createdAt))
                .build();
    }

    private FinishedGoodsBatch finishedGoods(String id, String batchNo, String productTypeId,
                                             String productionDate, String produced, String shipped, String unitPrice) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber(batchNo);
        batch.setProductTypeId(productTypeId);
        batch.setProductName("成品-" + productTypeId);
        batch.setProductionDate(LocalDate.parse(productionDate));
        batch.setProducedQuantity(new BigDecimal(produced));
        batch.setShippedQuantity(new BigDecimal(shipped));
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setUnit("kg");
        batch.setUnitPrice(new BigDecimal(unitPrice));
        batch.setWarehouseId("WH-LOG");
        batch.setCreatedBy(USER_ID);
        return batch;
    }

    /** FG batch with an explicit propagated unitCost (Bug 7: ledger must value at cost, not price). */
    private FinishedGoodsBatch finishedGoodsWithCost(String id, String batchNo, String productTypeId,
                                                     String productionDate, String produced,
                                                     String unitPrice, String unitCost) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber(batchNo);
        batch.setProductTypeId(productTypeId);
        batch.setProductName("成品-" + productTypeId);
        batch.setProductionDate(LocalDate.parse(productionDate));
        batch.setProducedQuantity(new BigDecimal(produced));
        batch.setShippedQuantity(BigDecimal.ZERO); // 发出流水现来自 SalesDeliveryItem, 不再读此累计列
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setUnit("kg");
        batch.setUnitPrice(new BigDecimal(unitPrice));
        if (unitCost != null) {
            batch.setUnitCost(new BigDecimal(unitCost));
        }
        batch.setWarehouseId("WH-LOG");
        batch.setCreatedBy(USER_ID);
        return batch;
    }

    /** One row shaped exactly like SalesDeliveryItemRepository.findShippedMovementsForLedger returns. */
    private Object[] shipmentRow(String deliveryDate, String deliveredQty, String unitCost,
                                 String productTypeId, String batchNumber, String deliveryNumber) {
        return new Object[]{
                LocalDate.parse(deliveryDate),
                new BigDecimal(deliveredQty),
                unitCost == null ? null : new BigDecimal(unitCost),
                productTypeId,
                batchNumber,
                "成品-" + productTypeId,
                deliveryNumber
        };
    }

    @Test
    @DisplayName("Bug 7: 成品收发存按成本(unitCost)计价、发出按逐笔发货真实日期 — 非售价/非累计盖生产日期")
    void exportQuantityAmountLedger_finishedGoods_costBasisAndPerShipmentDate() throws Exception {
        // 生产 05-05 产 10 (成本 18, 售价 30 应被忽略); 发货 05-20 发 4 (成本 18)。
        when(materialBatchRepo.findByFactoryId(eq(FACTORY_ID), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(finishedGoodsBatchRepo.findByFactoryIdOrderByCreatedAtDesc(eq(FACTORY_ID), any()))
                .thenReturn(new PageImpl<>(List.of(finishedGoodsWithCost("FG-001", "FG20260505001", "FG-SAUCE",
                        "2026-05-05", "10.000", "30.00", "18.00"))));
        when(salesDeliveryItemRepo.findShippedMovementsForLedger(eq(FACTORY_ID), any(), any()))
                .thenReturn(java.util.Collections.singletonList(shipmentRow("2026-05-20", "4.000", "18.00",
                        "FG-SAUCE", "FG20260505001", "DLV-20260520-001")));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportQuantityAmountLedger(FACTORY_ID, buildReq(), USER_ID, out);
        List<List<String>> rows = readXlsx(out.toByteArray());

        // rows: [header, FG入库(05-05), FG发出(05-20), footer]
        List<String> in = rows.get(1);
        assertEquals("2026-05-05", in.get(0));
        assertEquals(0, new BigDecimal("10.000").compareTo(decimalAt(in, 3)), "收入数量=产量");
        assertEquals(0, new BigDecimal("18.00").compareTo(decimalAt(in, 4)), "收入单价=成本(非售价30)");
        assertEquals(0, new BigDecimal("180.00").compareTo(decimalAt(in, 5)), "收入金额=10×18(非10×30)");
        assertEquals(0, new BigDecimal("10.000").compareTo(decimalAt(in, 9)), "结存数量");
        assertEquals(0, new BigDecimal("180.00").compareTo(decimalAt(in, 11)), "结存金额=成本口径");

        List<String> outRow = rows.get(2);
        assertEquals("2026-05-20", outRow.get(0), "发出日期=逐笔发货真实日期(非生产日05-05)");
        assertEquals(0, new BigDecimal("4.000").compareTo(decimalAt(outRow, 6)), "发出数量=本笔发货量");
        assertEquals(0, new BigDecimal("18.00").compareTo(decimalAt(outRow, 7)), "发出单价=成本");
        assertEquals(0, new BigDecimal("72.00").compareTo(decimalAt(outRow, 8)), "发出金额=4×18");
        // 期初(0)+入(180)-出(72)=期末(108); 数量 10-4=6
        assertEquals(0, new BigDecimal("6.000").compareTo(decimalAt(outRow, 9)), "结存数量=10-4");
        assertEquals(0, new BigDecimal("108.00").compareTo(decimalAt(outRow, 11)), "结存金额=180-72");
    }

    @Test
    @DisplayName("Bug 7: 成品成本未知 → 诚实 null(金额/结存显示 —), 绝不按售价或 ¥0 伪造")
    void exportQuantityAmountLedger_finishedGoods_honestNullWhenCostMissing() throws Exception {
        when(materialBatchRepo.findByFactoryId(eq(FACTORY_ID), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(finishedGoodsBatchRepo.findByFactoryIdOrderByCreatedAtDesc(eq(FACTORY_ID), any()))
                .thenReturn(new PageImpl<>(List.of(finishedGoodsWithCost("FG-002", "FG20260506001", "FG-NOCOST",
                        "2026-05-06", "8.000", "25.00", null)))); // unitCost 缺失
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportQuantityAmountLedger(FACTORY_ID, buildReq(), USER_ID, out);
        List<List<String>> rows = readXlsx(out.toByteArray());

        List<String> in = rows.get(1);
        assertEquals("2026-05-06", in.get(0));
        assertEquals(0, new BigDecimal("8.000").compareTo(decimalAt(in, 3)), "数量仍真实呈现");
        assertEquals("—", in.get(4), "收入单价诚实 null(非售价25)");
        assertEquals("—", in.get(5), "收入金额诚实 null(非¥0)");
        assertEquals("—", in.get(11), "结存金额一旦有未知成本即诚实 null");
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
        when(entryRepo.aggregateBySubjectExcludingDraft(FACTORY_ID, START, END)).thenReturn(List.of(row));
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

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportSequentialLedger(FACTORY_ID, buildReq(), USER_ID, out);

        // 防呆 R4 (2026-06-18 修): dedup 命中时仍正常导出文件, 但 NOT 新建 exportRecord
        // (修复前 dedup 命中只 log 不 return, 仍 save → 重复审计记录的 no-op)。
        verify(exportRecordRepo, atLeastOnce()).findRecentExport(any(), any(), any(), any(), any());
        verify(exportRecordRepo, never()).save(any());
    }

    @Test
    @DisplayName("T4: 默认配置 — 无 VoucherExportConfig 时使用默认金蝶列名")
    void testExportSubjectBalance_noConfig_usesDefaultKingdee() throws Exception {
        when(exportRecordRepo.findRecentExport(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        // 没有配置 — 走 orElseGet 分支
        when(exportConfigRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());
        when(entryRepo.aggregateBySubjectExcludingDraft(any(), any(), any())).thenReturn(List.of());
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
        when(entryRepo.aggregateBySubjectExcludingDraft(eq(FACTORY_ID), eq(LocalDate.of(1970, 1, 1)), eq(LocalDate.of(2026, 4, 30))))
                .thenReturn(List.of(aggregate("1002", "Bank", "1000.00", "200.00")));
        when(entryRepo.aggregateBySubjectExcludingDraft(eq(FACTORY_ID), eq(START), eq(END)))
                .thenReturn(List.of(aggregate("1002", "Bank", "200.00", "50.00")));
        when(accountRepo.findByCodeForFactory(FACTORY_ID, "1002"))
                .thenReturn(List.of(account("1002", "Bank", AccountBalanceType.DEBIT_NORMAL)));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportSubjectBalance(FACTORY_ID, buildReq(), USER_ID, out);

        List<List<String>> rows = readXlsx(out.toByteArray());
        // header + one subject row + F006 财务审计 Bug 6 (2026-07-04) 新增的待过账提示行
        assertEquals(3, rows.size(), "header + one subject row + pending-draft footer");
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
        when(entryRepo.aggregateBySubjectExcludingDraft(FACTORY_ID, START, END))
                .thenReturn(List.of(aggregate("1002", "Bank", "100.00", "25.00")));
        when(accountRepo.findByCodeForFactory(FACTORY_ID, "1002"))
                .thenReturn(List.of(account("1002", "Bank", AccountBalanceType.DEBIT_NORMAL)));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportSubjectBalance(FACTORY_ID, buildReq(), USER_ID, out);

        List<String> data = readXlsx(out.toByteArray()).get(1);
        assertEquals(0, BigDecimal.ZERO.compareTo(decimalAt(data, 2)), "opening stays 0 without previous close");
        assertEquals(0, new BigDecimal("75.00").compareTo(decimalAt(data, 5)), "closing = 0 + 100 - 25");
        verify(entryRepo, never()).aggregateBySubjectExcludingDraft(eq(FACTORY_ID), eq(LocalDate.of(1970, 1, 1)), any());
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
        when(entryRepo.aggregateBySubjectExcludingDraft(eq(FACTORY_ID), eq(LocalDate.of(1970, 1, 1)), eq(LocalDate.of(2026, 4, 30))))
                .thenReturn(List.of(aggregate("2202", "AP", "100.00", "500.00")));
        when(entryRepo.aggregateBySubjectExcludingDraft(eq(FACTORY_ID), eq(START), eq(END)))
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
        when(entryRepo.aggregateBySubjectExcludingDraft(eq(FACTORY_ID), eq(LocalDate.of(1970, 1, 1)), eq(LocalDate.of(2026, 4, 30))))
                .thenReturn(List.of(
                        aggregate("1002", "银行存款", "1000.00", "200.00"),
                        aggregate("2202", "应付账款", "100.00", "500.00")
                ));
        when(entryRepo.aggregateBySubjectExcludingDraft(eq(FACTORY_ID), eq(START), eq(END)))
                .thenReturn(List.of(
                        aggregate("1002", "银行存款", "200.00", "50.00"),
                        aggregate("2202", "应付账款", "50.00", "300.00")
                ));
        when(entryRepo.aggregateBySubjectExcludingDraft(eq(FACTORY_ID), eq(LocalDate.of(2026, 1, 1)), eq(END)))
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
        when(entryRepo.aggregateBySubjectExcludingDraft(eq(FACTORY_ID), eq(LocalDate.of(1970, 1, 1)), eq(LocalDate.of(2026, 4, 30))))
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
        when(entryRepo.aggregateBySubjectExcludingDraft(eq(FACTORY_ID), eq(LocalDate.of(1970, 1, 1)), eq(LocalDate.of(2026, 4, 30))))
                .thenReturn(List.of(
                        aggregate("1002", "银行存款", "1000.00", "0.00"),
                        aggregate("2202", "应付账款", "0.00", "1000.00")
                ));
        when(entryRepo.aggregateBySubjectExcludingDraft(eq(FACTORY_ID), eq(START), eq(END)))
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
        when(entryRepo.aggregateBySubjectExcludingDraft(FACTORY_ID, START, END))
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

    @Test
    @DisplayName("Income statement export writes period formulas and year-to-date totals")
    void exportIncomeStatement_writesPeriodAndYearToDateFormulaRows() throws Exception {
        when(entryRepo.aggregateBySubjectExcludingDraftAndClosing(eq(FACTORY_ID), eq(START), eq(END)))
                .thenReturn(List.of(
                        aggregate("6001", "主营业务收入", "0.00", "1000.00"),
                        aggregate("6401", "主营业务成本", "400.00", "0.00"),
                        aggregate("6601", "销售费用", "50.00", "0.00")
                ));
        when(entryRepo.aggregateBySubjectExcludingDraftAndClosing(eq(FACTORY_ID), eq(LocalDate.of(2026, 1, 1)), eq(END)))
                .thenReturn(List.of(
                        aggregate("6001", "主营业务收入", "0.00", "3000.00"),
                        aggregate("6401", "主营业务成本", "1200.00", "0.00"),
                        aggregate("6601", "销售费用", "150.00", "0.00")
                ));
        when(accountRepo.findVisibleToFactory(FACTORY_ID)).thenReturn(List.of(
                account("6001", "主营业务收入", AccountCategory.REVENUE, AccountBalanceType.CREDIT_NORMAL),
                account("6401", "主营业务成本", AccountCategory.COST, AccountBalanceType.DEBIT_NORMAL),
                account("6601", "销售费用", AccountCategory.EXPENSE, AccountBalanceType.DEBIT_NORMAL)
        ));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String fileName = service.exportIncomeStatement(FACTORY_ID, buildReq(), USER_ID, out);

        assertTrue(fileName.startsWith("income-statement_"));
        List<List<String>> rows = readXlsx(out.toByteArray());
        assertEquals(List.of("项目", "本期金额", "本年累计金额"), rows.get(0));
        Map<String, List<String>> byItem = rows.stream()
                .skip(1)
                .filter(row -> row.size() >= 3)
                .collect(Collectors.toMap(row -> row.get(0), row -> row, (a, b) -> a));
        assertEquals(0, new BigDecimal("1000.00").compareTo(decimalAt(byItem.get("一、营业收入"), 1)));
        assertEquals(0, new BigDecimal("400.00").compareTo(decimalAt(byItem.get("减：营业成本"), 1)));
        assertEquals(0, new BigDecimal("600.00").compareTo(decimalAt(byItem.get("二、毛利"), 1)));
        assertEquals(0, new BigDecimal("1800.00").compareTo(decimalAt(byItem.get("二、毛利"), 2)));
        assertEquals(0, new BigDecimal("550.00").compareTo(decimalAt(byItem.get("三、营业利润"), 1)));
        assertEquals(0, new BigDecimal("1650.00").compareTo(decimalAt(byItem.get("三、营业利润"), 2)));
    }

    @Test
    @DisplayName("Quantity amount ledger export balances inbound outbound and running stock")
    void exportQuantityAmountLedger_writesInboundOutboundAndRunningBalance() throws Exception {
        MaterialBatch rawBatch = materialBatch("MB-001", "RAW-PEPPER", "MB20260501001",
                "2026-05-01", "100.000", "8.00");
        when(materialBatchRepo.findByFactoryId(eq(FACTORY_ID), any()))
                .thenReturn(new PageImpl<>(List.of(rawBatch)));
        when(materialConsumptionRepo.findByTimeRange(eq(FACTORY_ID), any(), any()))
                .thenReturn(List.of(consumption("MB-001", "RAW-PEPPER", "2026-05-03T10:00:00",
                        "30.000", "8.00", "240.00")));
        when(semiFinishedInventoryRepo.findByFactoryIdForWeightView(FACTORY_ID))
                .thenReturn(List.of(semiFinished("WIP-001", "SEMI-PASTE", "2026-05-04T09:00:00",
                        "20.000", "5.000", "12.00")));
        when(finishedGoodsBatchRepo.findByFactoryIdOrderByCreatedAtDesc(eq(FACTORY_ID), any()))
                .thenReturn(new PageImpl<>(List.of(finishedGoods("FG-001", "FG20260505001", "FG-SAUCE",
                        "2026-05-05", "10.000", "2.000", "30.00"))));
        when(exportRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String fileName = service.exportQuantityAmountLedger(FACTORY_ID, buildReq(), USER_ID, out);

        assertTrue(fileName.startsWith("quantity-amount-ledger_"));
        List<List<String>> rows = readXlsx(out.toByteArray());
        assertEquals(List.of("日期", "凭证字号", "摘要", "收入数量", "收入单价", "收入金额",
                "发出数量", "发出单价", "发出金额", "结存数量", "结存单价", "结存金额"), rows.get(0));
        List<String> rawInbound = rows.get(1);
        assertEquals("2026-05-01", rawInbound.get(0));
        assertEquals(0, new BigDecimal("100.000").compareTo(decimalAt(rawInbound, 3)));
        assertEquals(0, new BigDecimal("100.000").compareTo(decimalAt(rawInbound, 9)));
        assertEquals(0, new BigDecimal("800.00").compareTo(decimalAt(rawInbound, 11)));

        List<String> rawOutbound = rows.get(2);
        assertEquals("2026-05-03", rawOutbound.get(0));
        assertEquals(0, new BigDecimal("30.000").compareTo(decimalAt(rawOutbound, 6)));
        assertEquals(0, new BigDecimal("70.000").compareTo(decimalAt(rawOutbound, 9)));
        assertEquals(0, new BigDecimal("560.00").compareTo(decimalAt(rawOutbound, 11)));

        List<String> footer = rows.get(rows.size() - 1);
        assertEquals("凭证来源口径=待财务确认", footer.get(0));
    }
}
