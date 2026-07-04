package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D5: SalesOrder 发货 → 仅从总仓 (WH-LOG) 出货 (PR #316, 2026-05-11).
 *
 * <p>验证 {@link SalesServiceImpl#deductFinishedGoodsInventory} (FIFO 扣减)
 * 通过 {@link WarehouseResolver#resolveLogisticsId} 解析 WH-LOG 仓库 id,
 * 仅扣减该仓库的批次。WH-WKS (鲜棉仓) 批次不参与销售扣减。
 *
 * <p>测试 pattern：参考 {@code SalesServiceImplSalespersonTest} — 用 null ctor
 * 构造 service, 反射设置需要的字段 (warehouseResolver, finishedGoodsBatchRepository).
 *
 * @since 2026-05-11 PR #316 D5 (sales from WH-LOG)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("D5: SalesOrder fulfillment WH-LOG 过滤")
class SalesOrderFulfillmentWarehouseTest {

    @Mock
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @Mock
    private WarehouseResolver warehouseResolver;

    private SalesServiceImpl salesService;

    private static final String FACTORY_A = "F001";
    private static final String PRODUCT_TYPE = "PT-001";
    private static final String WH_LOG_ID = "wh-log-f001";

    @BeforeEach
    void setUp() {
        // Mirror SalesServiceImplSalespersonTest pattern: 8-arg ctor with null + reflective injection.
        salesService = new SalesServiceImpl(
                null,    // salesOrderRepository
                null,    // salesOrderItemRepository
                null,    // deliveryRecordRepository
                finishedGoodsBatchRepository,
                null,    // customerRepository
                null,    // productTypeRepository
                null,    // arApService
                null);   // applicationEventPublisher
        ReflectionTestUtils.setField(salesService, "warehouseResolver", warehouseResolver);
        when(warehouseResolver.resolveLogisticsId(FACTORY_A)).thenReturn(WH_LOG_ID);
        // getAvailableBatches (read path) blank source 走 resolveSalesOutboundWh (2026-07-02 用途拆分),
        // fallback WH-LOG = 现状. LENIENT: 未用到的测试忽略。
        when(warehouseResolver.resolveSalesOutboundWh(FACTORY_A)).thenReturn(WH_LOG_ID);
    }

    /** Invoke private deductFinishedGoodsInventory via reflection. */
    private void invokeDeduct(SalesDeliveryItem item) throws Exception {
        Method m = SalesServiceImpl.class.getDeclaredMethod(
                "deductFinishedGoodsInventory", String.class, SalesDeliveryItem.class);
        m.setAccessible(true);
        try {
            m.invoke(salesService, FACTORY_A, item);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Unwrap so callers can catch BusinessException directly.
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw e;
        }
    }

    private SalesDeliveryItem buildDeliveryItem(BigDecimal quantity) {
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setProductTypeId(PRODUCT_TYPE);
        item.setDeliveredQuantity(quantity);
        return item;
    }

    private FinishedGoodsBatch buildAvailableBatch(String batchNumber, String warehouseId, BigDecimal produced) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setFactoryId(FACTORY_A);
        b.setBatchNumber(batchNumber);
        b.setProductTypeId(PRODUCT_TYPE);
        b.setWarehouseId(warehouseId);
        b.setStatus("AVAILABLE");
        b.setProducedQuantity(produced);
        b.setShippedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        return b;
    }

    // ============================================================
    // Standard path — WH-LOG batches only
    // ============================================================

    @Test
    @DisplayName("🔴 G1: blank source → 跨全部可售仓库 (除 WH-RD) 扣减, 不再单仓 WH-LOG")
    void deduct_queriesAllShippableWarehouses() throws Exception {
        FinishedGoodsBatch whLogBatch = buildAvailableBatch("B-LOG-001", WH_LOG_ID, new BigDecimal("50"));
        // 🔴 G1: blank source 走 findShippableBatchesAllWarehousesExcluding (跨仓, 排 WH-RD)
        when(finishedGoodsBatchRepository.findShippableBatchesAllWarehousesExcluding(
                FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD))
                .thenReturn(List.of(whLogBatch));

        SalesDeliveryItem item = buildDeliveryItem(new BigDecimal("30"));
        invokeDeduct(item);

        // blank 不再解析单一物流仓, 也不走单仓 shippable 查询
        verify(warehouseResolver, never()).resolveLogisticsId(FACTORY_A);
        verify(finishedGoodsBatchRepository, times(1))
                .findShippableBatchesAllWarehousesExcluding(FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD);
        verify(finishedGoodsBatchRepository, never())
                .findShippableBatchesByWarehouse(anyString(), anyString(), anyString());

        // 扣减 30 / 50
        assertEquals(0, whLogBatch.getShippedQuantity().compareTo(new BigDecimal("30")));
        assertEquals("B-LOG-001", whLogBatch.getBatchNumber());
    }

    @Test
    @DisplayName("标准: FEFO 跨多个批次扣减 (blank source, 跨仓候选)")
    void deduct_consumesAcrossBatchesFefo() throws Exception {
        FinishedGoodsBatch oldBatch = buildAvailableBatch("B-LOG-OLD", WH_LOG_ID, new BigDecimal("20"));
        FinishedGoodsBatch newBatch = buildAvailableBatch("B-LOG-NEW", WH_LOG_ID, new BigDecimal("40"));
        // Repository returns FEFO ordered (oldest first)
        when(finishedGoodsBatchRepository.findShippableBatchesAllWarehousesExcluding(
                FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD))
                .thenReturn(List.of(oldBatch, newBatch));

        SalesDeliveryItem item = buildDeliveryItem(new BigDecimal("35"));
        invokeDeduct(item);

        // 第一批 20/20 全用, 第二批 15/40 部分
        assertEquals(0, oldBatch.getShippedQuantity().compareTo(new BigDecimal("20")));
        assertEquals(0, newBatch.getShippedQuantity().compareTo(new BigDecimal("15")));
        // 第一批 depleted
        assertEquals("DEPLETED", oldBatch.getStatus());
        // 第二批仍 AVAILABLE
        assertEquals("AVAILABLE", newBatch.getStatus());

        verify(finishedGoodsBatchRepository, times(1)).save(eq(oldBatch));
        verify(finishedGoodsBatchRepository, times(1)).save(eq(newBatch));
    }

    // ============================================================
    // 🔴 G1 bug repro — FG only in WH-WKS, blank source → NOW ships (was insufficient)
    // ============================================================

    @Test
    @DisplayName("🔴 G1 repro: 成品仅在 WH-WKS + blank source → 现在能发货 (旧逻辑误报库存不足)")
    void deduct_onlyWhWksAvailable_blankSource_nowShips() throws Exception {
        final String WH_WKS_ID = "wh-wks-f001";
        FinishedGoodsBatch wksBatch = buildAvailableBatch("B-WKS-001", WH_WKS_ID, new BigDecimal("50"));
        // 跨仓候选集包含 WH-WKS 批次 (生产落点)
        when(finishedGoodsBatchRepository.findShippableBatchesAllWarehousesExcluding(
                FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD))
                .thenReturn(List.of(wksBatch));

        SalesDeliveryItem item = buildDeliveryItem(new BigDecimal("10"));
        invokeDeduct(item);  // 不再抛"库存不足"

        assertEquals(0, wksBatch.getShippedQuantity().compareTo(new BigDecimal("10")));
        verify(finishedGoodsBatchRepository, times(1)).save(eq(wksBatch));
    }

    @Test
    @DisplayName("负: blank source 全厂无可发货成品 → 抛 BusinessException + message 提示可售仓库")
    void deduct_noStockAnywhere_blankSource_throwsInsufficient() throws Exception {
        when(finishedGoodsBatchRepository.findShippableBatchesAllWarehousesExcluding(
                FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD))
                .thenReturn(Collections.emptyList());

        SalesDeliveryItem item = buildDeliveryItem(new BigDecimal("10"));
        BusinessException ex = assertThrows(BusinessException.class, () -> invokeDeduct(item));

        assertNotNull(ex.getMessage());
        assertEquals(true, ex.getMessage().contains("成品库存不足"));
        assertEquals(true, ex.getMessage().contains(PRODUCT_TYPE));
    }

    // ============================================================
    // getAvailableBatches — public read path
    // ============================================================

    @Test
    @DisplayName("🔴 getAvailableBatches: blank source → 跨全部可售仓 (除 WH-RD), 与 recommend-fifo (G1) 同口径")
    void getAvailableBatches_returnsAllSellableWarehouses() {
        // 2026-07-04: blank source 不再单仓 WH-LOG (成品落生产仓 WH-WKS 时库存查询假报无货) —
        //   跨全部可售仓 (findAvailableBatchesFefoAllWarehousesExcluding, 排 WH-RD), 与发货推荐/扣减 G1 一致。
        FinishedGoodsBatch wksBatch = buildAvailableBatch("B-WKS-001", "wh-wks-f001", new BigDecimal("100"));
        when(finishedGoodsBatchRepository.findAvailableBatchesFefoAllWarehousesExcluding(
                FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD))
                .thenReturn(List.of(wksBatch));

        List<FinishedGoodsBatch> batches = salesService.getAvailableBatches(FACTORY_A, PRODUCT_TYPE);

        assertEquals(1, batches.size());
        assertEquals("wh-wks-f001", batches.get(0).getWarehouseId());
        // blank source 不解析单仓, 直接跨全可售仓查询。
        verify(warehouseResolver, never()).resolveSalesOutboundWh(anyString());
        verify(finishedGoodsBatchRepository, times(1))
                .findAvailableBatchesFefoAllWarehousesExcluding(FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD);
    }

    @Test
    @DisplayName("WH-LOG warehouseCode 常量稳定 (D5 spec 锁定)")
    void warehouseCodeConstantIsStable() {
        // Defensive: 若未来重命名 WH-LOG → 这个 test 立即 fail, 提醒 grep 所有 site
        assertEquals("WH-LOG", WarehouseCodes.WH_LOG);
    }

    // ============================================================
    // T4-D5 #572: per-row sourceWarehouseCode honored (PR #547/#564 chain)
    // ============================================================

    @Test
    @DisplayName("T4-D5 #572: sourceWarehouseCode=WH-WKS → 查 WH-WKS 仓库批次, 不走 WH-LOG 默认")
    void deduct_honorsSourceWarehouseCode_whWks() throws Exception {
        final String WH_WKS_ID = "wh-wks-f001";
        when(warehouseResolver.resolveId(FACTORY_A, WarehouseCodes.WH_WKS)).thenReturn(WH_WKS_ID);

        FinishedGoodsBatch wksBatch = buildAvailableBatch("B-WKS-001", WH_WKS_ID, new BigDecimal("50"));
        when(finishedGoodsBatchRepository.findShippableBatchesByWarehouse(FACTORY_A, PRODUCT_TYPE, WH_WKS_ID))
                .thenReturn(List.of(wksBatch));

        SalesDeliveryItem item = buildDeliveryItem(new BigDecimal("30"));
        item.setSourceWarehouseCode(WarehouseCodes.WH_WKS);
        invokeDeduct(item);

        // 解析走 resolveId(code), 不走 resolveLogisticsId 默认
        verify(warehouseResolver, times(1)).resolveId(FACTORY_A, WarehouseCodes.WH_WKS);
        verify(warehouseResolver, never()).resolveLogisticsId(anyString());
        verify(finishedGoodsBatchRepository, times(1))
                .findShippableBatchesByWarehouse(FACTORY_A, PRODUCT_TYPE, WH_WKS_ID);

        // 扣减 30 / 50
        assertEquals(0, wksBatch.getShippedQuantity().compareTo(new BigDecimal("30")));
    }

    @Test
    @DisplayName("🔴 G1: sourceWarehouseCode=null → 跨全部可售仓库 (除 WH-RD), 不再单仓 WH-LOG")
    void deduct_nullSourceWarehouseCode_searchesAllWarehouses() throws Exception {
        FinishedGoodsBatch whLogBatch = buildAvailableBatch("B-LOG-001", WH_LOG_ID, new BigDecimal("50"));
        when(finishedGoodsBatchRepository.findShippableBatchesAllWarehousesExcluding(
                FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD))
                .thenReturn(List.of(whLogBatch));

        SalesDeliveryItem item = buildDeliveryItem(new BigDecimal("30"));
        // sourceWarehouseCode 留 null
        invokeDeduct(item);

        // 不再解析单仓, 也不调 resolveId(code)
        verify(warehouseResolver, never()).resolveLogisticsId(FACTORY_A);
        verify(warehouseResolver, never()).resolveId(anyString(), anyString());
        verify(finishedGoodsBatchRepository, times(1))
                .findShippableBatchesAllWarehousesExcluding(FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD);

        assertEquals(0, whLogBatch.getShippedQuantity().compareTo(new BigDecimal("30")));
    }

    @Test
    @DisplayName("🔴 G1: sourceWarehouseCode=空字符串 → 也走跨仓发现 (blank-safe)")
    void deduct_blankSourceWarehouseCode_searchesAllWarehouses() throws Exception {
        FinishedGoodsBatch whLogBatch = buildAvailableBatch("B-LOG-001", WH_LOG_ID, new BigDecimal("50"));
        when(finishedGoodsBatchRepository.findShippableBatchesAllWarehousesExcluding(
                FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD))
                .thenReturn(List.of(whLogBatch));

        SalesDeliveryItem item = buildDeliveryItem(new BigDecimal("30"));
        item.setSourceWarehouseCode("   ");
        invokeDeduct(item);

        verify(warehouseResolver, never()).resolveLogisticsId(FACTORY_A);
        verify(warehouseResolver, never()).resolveId(anyString(), anyString());
        verify(finishedGoodsBatchRepository, times(1))
                .findShippableBatchesAllWarehousesExcluding(FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD);
    }

    @Test
    @DisplayName("T4-D5 #572: 错误信息含选中仓库 code (UX — 用户看得到自己选哪个仓)")
    void deduct_insufficientStock_errorMessageIncludesWarehouse() throws Exception {
        final String WH_WKS_ID = "wh-wks-f001";
        when(warehouseResolver.resolveId(FACTORY_A, WarehouseCodes.WH_WKS)).thenReturn(WH_WKS_ID);
        when(finishedGoodsBatchRepository.findShippableBatchesByWarehouse(FACTORY_A, PRODUCT_TYPE, WH_WKS_ID))
                .thenReturn(Collections.emptyList());

        SalesDeliveryItem item = buildDeliveryItem(new BigDecimal("10"));
        item.setSourceWarehouseCode(WarehouseCodes.WH_WKS);

        BusinessException ex = assertThrows(BusinessException.class, () -> invokeDeduct(item));
        assertNotNull(ex.getMessage());
        // 用户看到 message 里显式提到 WH-WKS — 知道是选错仓的问题, 不是没生产
        assertEquals(true, ex.getMessage().contains("WH-WKS"));
        assertEquals(true, ex.getMessage().contains("成品库存不足"));
    }

    // ============================================================
    // T4-D5 #572 Phase B-1: getAvailableBatches 三参重载 sourceWarehouseCode 过滤
    // ============================================================

    @Test
    @DisplayName("Phase B-1: getAvailableBatches(sourceWarehouseCode=WH-WKS) → 仅返回 WH-WKS 批次")
    void getAvailableBatches_withSourceWarehouseCode_filtersToWks() {
        final String WH_WKS_ID = "wh-wks-f001";
        when(warehouseResolver.resolveId(FACTORY_A, WarehouseCodes.WH_WKS)).thenReturn(WH_WKS_ID);
        FinishedGoodsBatch wksBatch = buildAvailableBatch("B-WKS-001", WH_WKS_ID, new BigDecimal("100"));
        when(finishedGoodsBatchRepository.findAvailableBatchesByWarehouse(FACTORY_A, PRODUCT_TYPE, WH_WKS_ID))
                .thenReturn(List.of(wksBatch));

        List<FinishedGoodsBatch> batches = salesService.getAvailableBatches(
                FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_WKS);

        assertEquals(1, batches.size());
        assertEquals(WH_WKS_ID, batches.get(0).getWarehouseId());
        verify(warehouseResolver, times(1)).resolveId(FACTORY_A, WarehouseCodes.WH_WKS);
        verify(warehouseResolver, never()).resolveLogisticsId(anyString());
    }

    @Test
    @DisplayName("getAvailableBatches(sourceWarehouseCode=null) → 跨全部可售仓 (除 WH-RD)")
    void getAvailableBatches_nullSourceWarehouseCode_allSellable() {
        FinishedGoodsBatch logBatch = buildAvailableBatch("B-LOG-001", WH_LOG_ID, new BigDecimal("100"));
        when(finishedGoodsBatchRepository.findAvailableBatchesFefoAllWarehousesExcluding(
                FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD))
                .thenReturn(List.of(logBatch));

        List<FinishedGoodsBatch> batches = salesService.getAvailableBatches(FACTORY_A, PRODUCT_TYPE, null);

        assertEquals(1, batches.size());
        assertEquals(WH_LOG_ID, batches.get(0).getWarehouseId());
        verify(warehouseResolver, never()).resolveSalesOutboundWh(anyString());
        verify(warehouseResolver, never()).resolveId(anyString(), anyString());
    }

    @Test
    @DisplayName("getAvailableBatches(sourceWarehouseCode=空字符串) → blank-safe 跨全部可售仓")
    void getAvailableBatches_blankSourceWarehouseCode_allSellable() {
        when(finishedGoodsBatchRepository.findAvailableBatchesFefoAllWarehousesExcluding(
                FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD))
                .thenReturn(Collections.emptyList());

        salesService.getAvailableBatches(FACTORY_A, PRODUCT_TYPE, "   ");

        verify(finishedGoodsBatchRepository, times(1))
                .findAvailableBatchesFefoAllWarehousesExcluding(FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD);
        verify(warehouseResolver, never()).resolveId(anyString(), anyString());
    }

    // ============================================================
    // R6 #6: 发货释放预留 reservedQuantity (避免 available 双扣低估)
    // ============================================================

    @Test
    @DisplayName("R6 #6: 部分预留批次发货 → shipped 增 + reserved 释放对应量, available 不双扣")
    void deduct_releasesReservedOnShip() throws Exception {
        // produced=100, reserved=50 (本 SO 预留), available=50. 发货 50。
        FinishedGoodsBatch batch = buildAvailableBatch("B-LOG-RSV", WH_LOG_ID, new BigDecimal("100"));
        batch.setReservedQuantity(new BigDecimal("50"));
        when(finishedGoodsBatchRepository.findShippableBatchesAllWarehousesExcluding(FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD))
                .thenReturn(List.of(batch));

        SalesDeliveryItem item = buildDeliveryItem(new BigDecimal("50"));
        invokeDeduct(item);

        // Pass 1 先扣 available(50) — shipped=50. 不动 reserved (扣的是未预留部分)。
        assertEquals(0, batch.getShippedQuantity().compareTo(new BigDecimal("50")));
        // 修复后 available = 100 - 50 - 50 = 0 (仍正确: 这 50 是预留给别的/还没发)。
        // 关键: reserved 没被"发货+预留"双占。本场景 Pass1 已满足, reserved 不变 (=50)。
        assertEquals(0, batch.getReservedQuantity().compareTo(new BigDecimal("50")));
        assertEquals(0, batch.getAvailableQuantity().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("R6 #6: 批次被预留耗尽 (available=0) 仍可发货 — 动用预留物理库存并释放")
    void deduct_fullyReservedBatch_shipsAndReleasesReserved() throws Exception {
        // produced=50, reserved=50, available=0. 本 SO 预留了全部 50, 现在发货 50。
        // 旧逻辑: available=0 → 报"库存不足"无法发货 (幻影预留 bug)。
        // 新逻辑: findShippableBatchesByWarehouse 返回该批 (produced-shipped=50>0), Pass2 动用预留发货。
        FinishedGoodsBatch batch = buildAvailableBatch("B-LOG-FULLRSV", WH_LOG_ID, new BigDecimal("50"));
        batch.setReservedQuantity(new BigDecimal("50"));
        when(finishedGoodsBatchRepository.findShippableBatchesAllWarehousesExcluding(FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD))
                .thenReturn(List.of(batch));

        SalesDeliveryItem item = buildDeliveryItem(new BigDecimal("50"));
        invokeDeduct(item);  // 不应抛"库存不足"

        // shipped=50, reserved 释放 50 → reserved=0, available=50-50-0=0
        assertEquals(0, batch.getShippedQuantity().compareTo(new BigDecimal("50")));
        assertEquals(0, batch.getReservedQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, batch.getAvailableQuantity().compareTo(BigDecimal.ZERO));
        assertEquals("DEPLETED", batch.getStatus());  // produced - shipped = 0 → depleted
    }

    @Test
    @DisplayName("R6 #6: 发超过 available 但有预留 → 先扣 available 再动用预留, 释放对应预留量")
    void deduct_partlyFromAvailableThenReserved() throws Exception {
        // produced=100, reserved=70, available=30. 发货 50 (> available 30)。
        // Pass1 扣 available 30; Pass2 从预留再发 20, 释放预留 20。
        FinishedGoodsBatch batch = buildAvailableBatch("B-LOG-MIX", WH_LOG_ID, new BigDecimal("100"));
        batch.setReservedQuantity(new BigDecimal("70"));
        when(finishedGoodsBatchRepository.findShippableBatchesAllWarehousesExcluding(FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD))
                .thenReturn(List.of(batch));

        SalesDeliveryItem item = buildDeliveryItem(new BigDecimal("50"));
        invokeDeduct(item);

        // shipped=50, reserved=70-20=50, available=100-50-50=0
        assertEquals(0, batch.getShippedQuantity().compareTo(new BigDecimal("50")));
        assertEquals(0, batch.getReservedQuantity().compareTo(new BigDecimal("50")));
        assertEquals(0, batch.getAvailableQuantity().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("getAvailableBatches 二参旧路径 → 委托走 null(blank) → 跨全部可售仓 (向后兼容)")
    void getAvailableBatches_legacyTwoArg_delegatesToAllSellable() {
        // 覆盖旧签名委托新签名的逻辑: 未传 code → blank → 跨全可售仓查询 (非单仓 WH-LOG)。
        when(finishedGoodsBatchRepository.findAvailableBatchesFefoAllWarehousesExcluding(
                FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD))
                .thenReturn(Collections.emptyList());

        salesService.getAvailableBatches(FACTORY_A, PRODUCT_TYPE);

        verify(finishedGoodsBatchRepository, times(1))
                .findAvailableBatchesFefoAllWarehousesExcluding(FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD);
        verify(warehouseResolver, never()).resolveId(anyString(), anyString());
    }
}
