package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.StocktakeBulkImportPreviewDTO;
import com.cretas.aims.dto.factory.StocktakeImportRowDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.dto.material.OpeningInventoryItem;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.service.factory.FactoryStocktakeService;
import com.cretas.aims.service.inventory.OpeningInventoryService;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StocktakeBulkImportServiceImpl 单元测试。
 *
 * <p>覆盖差异计算 + 匹配失败诚实上报路径（不静默丢弃）：
 * <ul>
 *   <li>盘盈 / 盘亏 / 一致 差异分类 + differenceQty 精确</li>
 *   <li>未填实盘（留空）→ skipped，不当作 0</li>
 *   <li>未知批次 / 批次号空 / 文件内重复 / 负数 → errors 上报</li>
 * </ul>
 * 用真实 ExcelUtil 做 write→read 往返（不 mock EasyExcel），逼近真实解析。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("盘点批量导入 — 差异计算 + 匹配失败诚实上报")
class StocktakeBulkImportServiceImplTest {

    private static final String FACTORY_ID = "F006";
    private static final String WAREHOUSE_ID = "WH-1";

    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepo;
    @Mock private FactoryWarehouseRepository warehouseRepo;
    @Mock private FactoryStocktakeService stocktakeService;
    @Mock private OpeningInventoryService openingInventoryService;

    private final ExcelUtil excelUtil = new ExcelUtil(); // 真实实现，做 write/read 往返

    private StocktakeBulkImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StocktakeBulkImportServiceImpl(
                materialBatchRepo, rawMaterialTypeRepo, warehouseRepo,
                stocktakeService, excelUtil, openingInventoryService);
    }

    private MaterialBatch batch(String id, String no, String typeId, String qty, String price) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(FACTORY_ID);
        b.setBatchNumber(no);
        b.setMaterialTypeId(typeId);
        b.setWarehouseId(WAREHOUSE_ID);
        b.setReceiptQuantity(new BigDecimal(qty));
        b.setQuantityUnit("kg");
        if (price != null) b.setUnitPrice(new BigDecimal(price));
        return b;
    }

    private RawMaterialType type(String id, String name) {
        RawMaterialType t = new RawMaterialType();
        t.setId(id);
        t.setName(name);
        return t;
    }

    private StocktakeImportRowDTO row(String materialName, String batchNo, BigDecimal actual) {
        StocktakeImportRowDTO r = new StocktakeImportRowDTO();
        r.setMaterialName(materialName);
        r.setBatchNumber(batchNo);
        r.setWarehouseName("原料仓");
        r.setUnit("kg");
        r.setActualQty(actual);
        return r;
    }

    private ByteArrayInputStream toExcel(List<StocktakeImportRowDTO> rows) {
        byte[] bytes = excelUtil.exportToExcel(rows, StocktakeImportRowDTO.class, "库存盘点");
        return new ByteArrayInputStream(bytes);
    }

    private void stubWarehouseAndBatches() {
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId(WAREHOUSE_ID);
        wh.setFactoryId(FACTORY_ID);
        wh.setName("原料仓");
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WAREHOUSE_ID, FACTORY_ID))
                .thenReturn(Optional.of(wh));

        List<MaterialBatch> batches = List.of(
                batch("id-A", "B001", "T1", "100", "10"),
                batch("id-B", "B002", "T2", "50", "8"),
                batch("id-C", "B003", "T3", "10", "5"),
                batch("id-D", "B004", "T4", "5", "3"),
                batch("id-E", "B005", "T5", "8", "2"));
        when(materialBatchRepo.findByFactoryIdAndWarehouseId(FACTORY_ID, WAREHOUSE_ID))
                .thenReturn(batches);
        lenient().when(rawMaterialTypeRepo.findAllById(any())).thenReturn(List.of(
                type("T1", "猪蹄"), type("T2", "牛肉"), type("T3", "生姜"),
                type("T4", "食盐"), type("T5", "花椒")));
    }

    @Test
    @DisplayName("盘盈/盘亏/一致/未填/未知/空/重复/负数 全路径")
    void preview_allPaths() {
        stubWarehouseAndBatches();

        List<StocktakeImportRowDTO> rows = new ArrayList<>();
        rows.add(row("猪蹄", "B001", new BigDecimal("120")));   // surplus +20
        rows.add(row("牛肉", "B002", new BigDecimal("30")));    // shortage -20
        rows.add(row("生姜", "B003", new BigDecimal("10")));    // match 0
        rows.add(row("食盐", "B004", null));                    // skipped (blank actual)
        rows.add(row("未知料", "BX99", new BigDecimal("5")));   // error: not found
        rows.add(row("空号", "", new BigDecimal("1")));         // error: empty batchNo
        rows.add(row("猪蹄", "B001", new BigDecimal("99")));    // error: duplicate in file
        rows.add(row("花椒", "B005", new BigDecimal("-3")));    // error: negative

        StocktakeBulkImportPreviewDTO dto = service.preview(
                FACTORY_ID, WAREHOUSE_ID, FactoryStocktake.ImportMode.NORMAL, toExcel(rows));

        assertThat(dto.getTotalRows()).isEqualTo(8);
        // matched = A(surplus) + B(shortage) + C(match) + D(skipped) = 4
        assertThat(dto.getMatchedCount()).isEqualTo(4);
        assertThat(dto.getSurplusCount()).isEqualTo(1);
        assertThat(dto.getShortageCount()).isEqualTo(1);
        assertThat(dto.getMatchCount()).isEqualTo(1);
        assertThat(dto.getSkippedCount()).isEqualTo(1);
        // errors = notFound + empty + duplicate + negative = 4 (honest, not dropped)
        assertThat(dto.getErrorCount()).isEqualTo(4);
        assertThat(dto.getErrors()).extracting(StocktakeBulkImportPreviewDTO.RowError::getReason)
                .anySatisfy(r -> assertThat(r).contains("未在仓库"))
                .anySatisfy(r -> assertThat(r).contains("批次号为空"))
                .anySatisfy(r -> assertThat(r).contains("重复"))
                .anySatisfy(r -> assertThat(r).contains("负数"));

        // 差异数量精确
        StocktakeBulkImportPreviewDTO.MatchedLine a = dto.getMatchedLines().stream()
                .filter(l -> l.getBatchNumber().equals("B001")).findFirst().orElseThrow();
        assertThat(a.getDifferenceType()).isEqualTo("SURPLUS");
        assertThat(a.getDifferenceQty()).isEqualByComparingTo("20");
        assertThat(a.getMaterialName()).isEqualTo("猪蹄");

        StocktakeBulkImportPreviewDTO.MatchedLine d = dto.getMatchedLines().stream()
                .filter(l -> l.getBatchNumber().equals("B004")).findFirst().orElseThrow();
        assertThat(d.getActualQty()).isNull();
        assertThat(d.getDifferenceQty()).isNull();
        assertThat(d.getDifferenceType()).isNull();
    }

    @Test
    @DisplayName("(🔒🔒 phantom-variance) NORMAL 预览：消耗过批次 (receipt=100/used=30) 账面=可用量70, 实盘70 → 零差异 (非幻影-30盘亏)")
    void preview_consumedBatch_systemQtyIsCurrentAvailable_notGross() {
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId(WAREHOUSE_ID);
        wh.setFactoryId(FACTORY_ID);
        wh.setName("原料仓");
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WAREHOUSE_ID, FACTORY_ID))
                .thenReturn(Optional.of(wh));

        MaterialBatch consumed = batch("id-C1", "BC01", "T1", "100", "10");
        consumed.setUsedQuantity(new BigDecimal("30"));       // 已领用 30 → 可用量 = 70
        consumed.setReservedQuantity(BigDecimal.ZERO);
        when(materialBatchRepo.findByFactoryIdAndWarehouseId(FACTORY_ID, WAREHOUSE_ID))
                .thenReturn(List.of(consumed));
        lenient().when(rawMaterialTypeRepo.findAllById(any())).thenReturn(List.of(type("T1", "猪蹄")));

        // 仓管照货架实物盘 70 (= 可用量), 应为零差异, 绝不是照 gross 100 盘出 -30 幻影盘亏
        List<StocktakeImportRowDTO> rows = List.of(row("猪蹄", "BC01", new BigDecimal("70")));
        StocktakeBulkImportPreviewDTO dto = service.preview(
                FACTORY_ID, WAREHOUSE_ID, FactoryStocktake.ImportMode.NORMAL, toExcel(rows));

        StocktakeBulkImportPreviewDTO.MatchedLine line = dto.getMatchedLines().stream()
                .filter(l -> "BC01".equals(l.getBatchNumber())).findFirst().orElseThrow();
        assertThat(line.getSystemQty()).isEqualByComparingTo("70");   // 账面 = 当前可用量, 非 gross 100
        assertThat(line.getDifferenceQty()).isEqualByComparingTo("0"); // 零差异
        assertThat(line.getDifferenceType()).isEqualTo("MATCH");
    }

    @Test
    @DisplayName("确认：无任何实盘数量 → 400 拒绝创建空盘点")
    void confirm_noActuals_rejected() {
        stubWarehouseAndBatches();
        List<StocktakeImportRowDTO> rows = new ArrayList<>();
        rows.add(row("食盐", "B004", null)); // 唯一一行且未填实盘

        assertThatThrownBy(() -> service.confirm(FACTORY_ID, WAREHOUSE_ID, "2026-07",
                null, FactoryStocktake.ImportMode.NORMAL, toExcel(rows), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有任何有效的实盘数量");
    }

    @Test
    @DisplayName("未知仓库 → 404")
    void preview_unknownWarehouse_404() {
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());
        List<StocktakeImportRowDTO> rows = List.of(row("x", "B001", new BigDecimal("1")));
        assertThatThrownBy(() -> service.preview(
                FACTORY_ID, "BAD", FactoryStocktake.ImportMode.NORMAL, toExcel(rows)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仓库不存在");
    }

    // =====================================================================
    // OPENING 期初建账 (create-from-zero) — 期初已并入盘点
    // =====================================================================

    private StocktakeImportRowDTO openingRow(String materialName, String batchNo,
            BigDecimal qty, BigDecimal unitPrice) {
        StocktakeImportRowDTO r = row(materialName, batchNo, qty);
        r.setUnitPrice(unitPrice);
        return r;
    }

    private void stubMaterialTypeDictionary() {
        when(rawMaterialTypeRepo.findByFactoryId(FACTORY_ID)).thenReturn(List.of(
                type("T1", "猪蹄"), type("T2", "牛肉"), type("T-NEW", "鸭掌")));
    }

    @Test
    @DisplayName("OPENING 预览：新物料 → 将新建 (willCreate)，既有批次 → 校正，不报错")
    void preview_opening_newMaterial_marksWillCreate() {
        stubWarehouseAndBatches();
        stubMaterialTypeDictionary();

        List<StocktakeImportRowDTO> rows = new ArrayList<>();
        rows.add(openingRow("猪蹄", "B001", new BigDecimal("120"), null)); // 既有 → 盘盈 +20
        rows.add(openingRow("鸭掌", "", new BigDecimal("30"), new BigDecimal("12"))); // 新物料 → 将新建

        StocktakeBulkImportPreviewDTO dto = service.preview(
                FACTORY_ID, WAREHOUSE_ID, FactoryStocktake.ImportMode.OPENING, toExcel(rows));

        assertThat(dto.getErrorCount()).isZero();
        assertThat(dto.getWillCreateCount()).isEqualTo(1);
        assertThat(dto.getMatchedCount()).isEqualTo(2);

        StocktakeBulkImportPreviewDTO.MatchedLine create = dto.getMatchedLines().stream()
                .filter(StocktakeBulkImportPreviewDTO.MatchedLine::isWillCreate).findFirst().orElseThrow();
        assertThat(create.getMaterialName()).isEqualTo("鸭掌");
        assertThat(create.getMaterialTypeId()).isEqualTo("T-NEW");
        assertThat(create.getMaterialBatchId()).isNull(); // 预览阶段还没建壳
        assertThat(create.getSystemQty()).isEqualByComparingTo("0");
        assertThat(create.getActualQty()).isEqualByComparingTo("30");
        assertThat(create.getDifferenceQty()).isEqualByComparingTo("30");
        assertThat(create.getDifferenceType()).isEqualTo("SURPLUS");
        assertThat(create.getUnitPrice()).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("OPENING 预览：名称匹配不到物料字典 → 诚实报错 (不静默新建)")
    void preview_opening_unknownMaterial_errors() {
        stubWarehouseAndBatches();
        stubMaterialTypeDictionary();

        List<StocktakeImportRowDTO> rows = List.of(
                openingRow("不存在的料", "", new BigDecimal("10"), new BigDecimal("5")));

        StocktakeBulkImportPreviewDTO dto = service.preview(
                FACTORY_ID, WAREHOUSE_ID, FactoryStocktake.ImportMode.OPENING, toExcel(rows));

        assertThat(dto.getWillCreateCount()).isZero();
        assertThat(dto.getErrorCount()).isEqualTo(1);
        assertThat(dto.getErrors().get(0).getReason()).contains("未匹配到物料字典");
    }

    @Test
    @DisplayName("NORMAL 预览：未知批次仍报错 (不受 OPENING 改动影响)")
    void preview_normal_unknownBatch_stillErrors() {
        stubWarehouseAndBatches();

        List<StocktakeImportRowDTO> rows = List.of(row("鸭掌", "BX99", new BigDecimal("5")));

        StocktakeBulkImportPreviewDTO dto = service.preview(
                FACTORY_ID, WAREHOUSE_ID, FactoryStocktake.ImportMode.NORMAL, toExcel(rows));

        assertThat(dto.getWillCreateCount()).isZero();
        assertThat(dto.getMatchedCount()).isZero();
        assertThat(dto.getErrorCount()).isEqualTo(1);
        assertThat(dto.getErrors().get(0).getReason()).contains("未在仓库");
    }

    @Test
    @DisplayName("OPENING 确认：新物料建壳(qty=0) + 回填期初数量，纳入 initiate 快照→盘盈")
    void confirm_opening_createsShellAndBackfills() {
        stubWarehouseAndBatches();
        stubMaterialTypeDictionary();

        // 建壳返回 shell 批次 (qty=0)，其 id 进入 initiate 快照。
        MaterialBatch shell = batch("shell-1", "OPEN-AUTO-1", "T-NEW", "0", "12");
        when(openingInventoryService.createOpeningBatchShell(
                eq(FACTORY_ID), any(OpeningInventoryItem.class), anyString(), any()))
                .thenReturn(shell);

        // initiate 快照回一个含新壳批次的明细 (systemQty=0) + 既有批次 B001。
        StocktakeDTO created = new StocktakeDTO();
        created.setId("stk-1");
        created.setStocktakeNo("ST-OPEN-1");
        StocktakeDTO.StocktakeItemDTO itShell = new StocktakeDTO.StocktakeItemDTO();
        itShell.setId("item-shell");
        itShell.setMaterialBatchId("shell-1");
        StocktakeDTO.StocktakeItemDTO itExisting = new StocktakeDTO.StocktakeItemDTO();
        itExisting.setId("item-B001");
        itExisting.setMaterialBatchId("id-A");
        created.setItems(List.of(itShell, itExisting));
        when(stocktakeService.initiate(eq(FACTORY_ID), any(), any(), eq(FactoryStocktake.ImportMode.OPENING)))
                .thenReturn(created);

        List<StocktakeImportRowDTO> rows = new ArrayList<>();
        rows.add(openingRow("猪蹄", "B001", new BigDecimal("100"), null)); // 既有一致(不产生差异但有实盘)
        rows.add(openingRow("鸭掌", "", new BigDecimal("30"), new BigDecimal("12"))); // 新物料建壳

        StocktakeBulkImportPreviewDTO dto = service.confirm(FACTORY_ID, WAREHOUSE_ID, "2026-07",
                null, FactoryStocktake.ImportMode.OPENING, toExcel(rows), 1L);

        // 建壳被调用一次 (仅新物料行)。
        verify(openingInventoryService).createOpeningBatchShell(
                eq(FACTORY_ID), any(OpeningInventoryItem.class), anyString(), any());
        // 回填实盘：两行都有 actualQty → updateItems 收到 2 条 (shell 映射 item-shell, 既有映射 item-B001)。
        verify(stocktakeService).updateItems(eq("stk-1"), eq(FACTORY_ID), any(), eq(1L));
        assertThat(dto.getStocktakeId()).isEqualTo("stk-1");
        assertThat(dto.getWillCreateCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("NORMAL 确认：不建任何期初壳 (openingInventoryService 不被调用)")
    void confirm_normal_neverCreatesShell() {
        stubWarehouseAndBatches();

        StocktakeDTO created = new StocktakeDTO();
        created.setId("stk-n");
        created.setStocktakeNo("ST-N-1");
        StocktakeDTO.StocktakeItemDTO it = new StocktakeDTO.StocktakeItemDTO();
        it.setId("item-A");
        it.setMaterialBatchId("id-A");
        created.setItems(List.of(it));
        when(stocktakeService.initiate(eq(FACTORY_ID), any(), any(), eq(FactoryStocktake.ImportMode.NORMAL)))
                .thenReturn(created);

        List<StocktakeImportRowDTO> rows = List.of(row("猪蹄", "B001", new BigDecimal("120")));

        service.confirm(FACTORY_ID, WAREHOUSE_ID, "2026-07",
                null, FactoryStocktake.ImportMode.NORMAL, toExcel(rows), 1L);

        verify(openingInventoryService, never()).createOpeningBatchShell(anyString(), any(), anyString(), any());
    }
}
