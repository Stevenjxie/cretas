package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 🔴 (2026-07-06): 销售发货必须<b>兑现</b>操作员在分配对话框里选定的成品批次身份。
 *
 * <p>历史 bug: {@code shipDelivery} HARD-GATE 强制批次分配 ({@code isFullyAllocated}), 但
 * {@code deductFinishedGoodsInventory} 却独立 FIFO 重扫 —— 用户选的批次被静默覆盖, 分配记录 ≠
 * 实际发货批次 (追溯失真)。本测试锁死: 有分配记录时扣的<b>就是</b>那些批次 (不走 FIFO 发现),
 * 无分配记录时回落 FIFO (向后兼容)。
 *
 * <p>测试 pattern 沿用 {@link SalesOrderFulfillmentWarehouseTest} —— null ctor + 反射注入 mock +
 * 反射调用 private {@code deductFinishedGoodsInventory}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("🔴 销售发货兑现批次分配 (不被 FIFO 覆盖)")
class SalesDeliveryHonorBatchAllocationTest {

    @Mock
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @Mock
    private WarehouseResolver warehouseResolver;

    @Mock
    private SalesDeliveryBatchAllocationService batchAllocationService;

    private SalesServiceImpl salesService;

    private static final String FACTORY_A = "F001";
    private static final String PRODUCT_TYPE = "PT-001";
    private static final String WH_LOG_ID = "wh-log-f001";
    private static final Long ITEM_ID = 777L;
    private static final String ITEM_ID_STR = "777";

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                null, null, null, finishedGoodsBatchRepository,
                null, null, null, null);
        ReflectionTestUtils.setField(salesService, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(salesService, "batchAllocationService", batchAllocationService);
    }

    /** 三参重载 — 允许测试注入 mock productTypeRepository (跨单位场景)。 */
    private SalesServiceImpl serviceWithProductRepo(ProductTypeRepository ptRepo) {
        SalesServiceImpl svc = new SalesServiceImpl(
                null, null, null, finishedGoodsBatchRepository,
                null, ptRepo, null, null);
        ReflectionTestUtils.setField(svc, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(svc, "batchAllocationService", batchAllocationService);
        return svc;
    }

    private void invokeDeduct(SalesServiceImpl svc, SalesDeliveryItem item) throws Exception {
        Method m = SalesServiceImpl.class.getDeclaredMethod(
                "deductFinishedGoodsInventory", String.class, SalesDeliveryItem.class);
        m.setAccessible(true);
        try {
            m.invoke(svc, FACTORY_A, item);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    private SalesDeliveryItem buildItem(BigDecimal qty, String unit) {
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setId(ITEM_ID);
        item.setProductTypeId(PRODUCT_TYPE);
        item.setDeliveredQuantity(qty);
        item.setUnit(unit);
        return item;
    }

    private FinishedGoodsBatch buildBatch(String id, String batchNumber, String unit,
                                          BigDecimal produced, BigDecimal shipped) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setId(id);
        b.setFactoryId(FACTORY_A);
        b.setBatchNumber(batchNumber);
        b.setProductTypeId(PRODUCT_TYPE);
        b.setWarehouseId(WH_LOG_ID);
        b.setStatus("AVAILABLE");
        b.setUnit(unit);
        b.setProducedQuantity(produced);
        b.setShippedQuantity(shipped);
        b.setReservedQuantity(BigDecimal.ZERO);
        return b;
    }

    private SalesDeliveryItemBatchAllocation alloc(String batchId, String batchNumber,
                                                   BigDecimal qty, String unit) {
        SalesDeliveryItemBatchAllocation a = new SalesDeliveryItemBatchAllocation();
        a.setFactoryId(FACTORY_A);
        a.setDeliveryItemId(ITEM_ID_STR);
        a.setFinishedGoodsBatchId(batchId);
        a.setBatchNumber(batchNumber);
        a.setAllocatedQty(qty);
        a.setUnit(unit);
        return a;
    }

    // ============================================================
    // 核心: 有分配 → 扣分配的批次, 不走 FIFO
    // ============================================================

    @Test
    @DisplayName("🔴 核心: 用户分配批次 B → 发货扣的是 B, 不是 FIFO 会选的 A")
    void ship_honorsAllocatedBatch_notFifo() throws Exception {
        FinishedGoodsBatch batchB = buildBatch("id-B", "B-NEAR-EXPIRY", "件",
                new BigDecimal("100"), BigDecimal.ZERO);
        when(finishedGoodsBatchRepository.findById("id-B")).thenReturn(Optional.of(batchB));
        when(batchAllocationService.listByDeliveryItem(FACTORY_A, ITEM_ID_STR))
                .thenReturn(List.of(alloc("id-B", "B-NEAR-EXPIRY", new BigDecimal("30"), "件")));

        SalesDeliveryItem item = buildItem(new BigDecimal("30"), "件");
        invokeDeduct(salesService, item);

        // 扣的就是分配的批次 B
        assertEquals(0, batchB.getShippedQuantity().compareTo(new BigDecimal("30")));
        verify(finishedGoodsBatchRepository, times(1)).save(batchB);
        // 追溯: item 记录首个扣减批次 = B
        assertEquals("id-B", item.getFinishedGoodsBatchId());
        // 关键: 绝不走 FIFO 发现 (那会另选批次覆盖用户选择)
        verify(finishedGoodsBatchRepository, never())
                .findShippableBatchesAllWarehousesExcluding(anyString(), anyString(), anyString());
        verify(finishedGoodsBatchRepository, never())
                .findShippableBatchesByWarehouse(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("🔴 多批分配: 拆 2 个批次 → 各按分配量精确扣 (近效期清库存场景)")
    void ship_honorsMultiBatchAllocation() throws Exception {
        FinishedGoodsBatch b1 = buildBatch("id-1", "B-001", "件", new BigDecimal("20"), BigDecimal.ZERO);
        FinishedGoodsBatch b2 = buildBatch("id-2", "B-002", "件", new BigDecimal("40"), BigDecimal.ZERO);
        when(finishedGoodsBatchRepository.findById("id-1")).thenReturn(Optional.of(b1));
        when(finishedGoodsBatchRepository.findById("id-2")).thenReturn(Optional.of(b2));
        when(batchAllocationService.listByDeliveryItem(FACTORY_A, ITEM_ID_STR))
                .thenReturn(List.of(
                        alloc("id-1", "B-001", new BigDecimal("20"), "件"),
                        alloc("id-2", "B-002", new BigDecimal("15"), "件")));

        SalesDeliveryItem item = buildItem(new BigDecimal("35"), "件");
        invokeDeduct(salesService, item);

        assertEquals(0, b1.getShippedQuantity().compareTo(new BigDecimal("20")));
        assertEquals(0, b2.getShippedQuantity().compareTo(new BigDecimal("15")));
        assertEquals("DEPLETED", b1.getStatus());   // 20/20 用尽
        assertEquals("AVAILABLE", b2.getStatus());  // 15/40 部分
        verify(finishedGoodsBatchRepository, times(1)).save(b1);
        verify(finishedGoodsBatchRepository, times(1)).save(b2);
    }

    // ============================================================
    // 回落: 无分配记录 → FIFO (向后兼容)
    // ============================================================

    @Test
    @DisplayName("回落: 发货行无分配记录 → 走原 FIFO 发现 (legacy 兼容)")
    void ship_noAllocation_fallsBackToFifo() throws Exception {
        when(batchAllocationService.listByDeliveryItem(FACTORY_A, ITEM_ID_STR))
                .thenReturn(List.of());  // 无分配
        FinishedGoodsBatch fifoBatch = buildBatch("id-fifo", "B-FIFO", "件",
                new BigDecimal("50"), BigDecimal.ZERO);
        when(finishedGoodsBatchRepository.findShippableBatchesAllWarehousesExcluding(
                FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD))
                .thenReturn(List.of(fifoBatch));

        SalesDeliveryItem item = buildItem(new BigDecimal("30"), "件");
        invokeDeduct(salesService, item);

        // FIFO 路径生效
        assertEquals(0, fifoBatch.getShippedQuantity().compareTo(new BigDecimal("30")));
        verify(finishedGoodsBatchRepository, times(1))
                .findShippableBatchesAllWarehousesExcluding(FACTORY_A, PRODUCT_TYPE, WarehouseCodes.WH_RD);
    }

    // ============================================================
    // 并发/库存下降: 分配批次可用不足 → loud-fail (不静默少发)
    // ============================================================

    @Test
    @DisplayName("🔴 分配后批次被其它单占用 (可用下降) → 409 loud-fail 提示重新分配, 不静默少发")
    void ship_allocatedBatchInsufficient_loudFail() throws Exception {
        // produced=100, 但发货前已被其它单发走 95 → 可用=5, 而分配要 30。
        FinishedGoodsBatch batch = buildBatch("id-drop", "B-DROP", "件",
                new BigDecimal("100"), new BigDecimal("95"));
        when(finishedGoodsBatchRepository.findById("id-drop")).thenReturn(Optional.of(batch));
        when(batchAllocationService.listByDeliveryItem(FACTORY_A, ITEM_ID_STR))
                .thenReturn(List.of(alloc("id-drop", "B-DROP", new BigDecimal("30"), "件")));

        SalesDeliveryItem item = buildItem(new BigDecimal("30"), "件");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeDeduct(salesService, item));

        assertNotNull(ex.getMessage());
        assertEquals(true, ex.getMessage().contains("B-DROP"));
        assertEquals(true, ex.getMessage().contains("可用库存不足"));
        // 未误扣: shipped 保持原值 (95), 没有部分写入
        assertEquals(0, batch.getShippedQuantity().compareTo(new BigDecimal("95")));
    }

    @Test
    @DisplayName("🔴 分配批次已删除 → 409 loud-fail (不静默跳过 → 会漏发)")
    void ship_allocatedBatchMissing_loudFail() throws Exception {
        when(finishedGoodsBatchRepository.findById("id-gone")).thenReturn(Optional.empty());
        when(batchAllocationService.listByDeliveryItem(FACTORY_A, ITEM_ID_STR))
                .thenReturn(List.of(alloc("id-gone", "B-GONE", new BigDecimal("10"), "件")));

        SalesDeliveryItem item = buildItem(new BigDecimal("10"), "件");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeDeduct(salesService, item));
        assertEquals(true, ex.getMessage().contains("B-GONE"));
        assertEquals(true, ex.getMessage().contains("不存在"));
    }

    // ============================================================
    // 跨单位 (C1): 发货单位 kg, 批次原生 盒 → 换算后扣减, 不裸减
    // ============================================================

    @Test
    @DisplayName("🔴 C1: 发货行 kg, 分配批次原生单位=盒 → 扣减前换算, 写库为盒")
    void ship_mixedUnitAllocation_converts() throws Exception {
        ProductTypeRepository ptRepo = org.mockito.Mockito.mock(ProductTypeRepository.class);
        ProductType pt = new ProductType();
        pt.setId(PRODUCT_TYPE);
        pt.setGramsPerUnit(new BigDecimal("15"));  // 15g/盒
        when(ptRepo.findById(PRODUCT_TYPE)).thenReturn(Optional.of(pt));
        SalesServiceImpl svc = serviceWithProductRepo(ptRepo);

        // 批次原生=盒, produced=4454.5盒 → 可用换算 kg = 4454.5×15/1000 = 66.8175kg。
        FinishedGoodsBatch boxBatch = buildBatch("id-box", "B-BOX", "盒",
                new BigDecimal("4454.5"), BigDecimal.ZERO);
        when(finishedGoodsBatchRepository.findById("id-box")).thenReturn(Optional.of(boxBatch));
        // 分配记录: allocatedQty 以发货行单位 kg 记录 (allocateBatches setUnit(item.getUnit()))
        when(batchAllocationService.listByDeliveryItem(FACTORY_A, ITEM_ID_STR))
                .thenReturn(List.of(alloc("id-box", "B-BOX", new BigDecimal("10"), "kg")));

        SalesDeliveryItem item = buildItem(new BigDecimal("10"), "kg");  // 发 10kg
        invokeDeduct(svc, item);

        // 10kg → 盒: 10×1000/15 = 666.6666... HALF_UP scale2 → 666.67 盒 写库
        assertEquals(0, new BigDecimal("666.67").compareTo(boxBatch.getShippedQuantity()));
        verify(finishedGoodsBatchRepository, times(1)).save(boxBatch);
    }

    @Test
    @DisplayName("🔴 C1: 单位不同且缺 gramsPerUnit → 诚实 loud-fail, 不裸误扣")
    void ship_mixedUnitAllocation_missingGrams_loudFail() throws Exception {
        ProductTypeRepository ptRepo = org.mockito.Mockito.mock(ProductTypeRepository.class);
        ProductType pt = new ProductType();
        pt.setId(PRODUCT_TYPE);
        pt.setGramsPerUnit(null);  // 缺克重
        when(ptRepo.findById(PRODUCT_TYPE)).thenReturn(Optional.of(pt));
        SalesServiceImpl svc = serviceWithProductRepo(ptRepo);

        FinishedGoodsBatch boxBatch = buildBatch("id-box2", "B-BOX2", "盒",
                new BigDecimal("4454.5"), BigDecimal.ZERO);
        when(finishedGoodsBatchRepository.findById("id-box2")).thenReturn(Optional.of(boxBatch));
        when(batchAllocationService.listByDeliveryItem(FACTORY_A, ITEM_ID_STR))
                .thenReturn(List.of(alloc("id-box2", "B-BOX2", new BigDecimal("10"), "kg")));

        SalesDeliveryItem item = buildItem(new BigDecimal("10"), "kg");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeDeduct(svc, item));
        assertEquals(true, ex.getMessage().contains("不可换算") || ex.getMessage().contains("无法换算"));
        // 未误扣
        assertEquals(0, BigDecimal.ZERO.compareTo(boxBatch.getShippedQuantity()));
    }
}
