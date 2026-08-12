package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 卖原料/辅料/包材时, 库存必须从 {@code material_batches} 扣, 不是成品批次。
 *
 * <h2>2026-08-12 Steve 拍板(六膳门张权真机反馈)</h2>
 * 「老问题 销售订单 选择不了原料」——「有啥不能卖的 给钱 我都能卖」;「半成品卖 过分了」。
 *
 * <p>Steve 点破了做法:「我们现在的成品销售也是直接扣库存 然后走财务账单呀 其他的也这样做啊」——
 * 所以不需要把物料复制成商品, 只要发货时换个库存源。
 *
 * <h2>为什么不能靠「镜像成商品」</h2>
 * {@code finished_goods_batches} 按 {@code product_type_id} 建, 销售发货走它;
 * 而原料的货在 {@code material_batches}。实测 LIUSHANMEN: 成品批次 12 / 物料批次 92。
 * 镜像出来的 SKU 在成品批次里一条都不会有 —— 能开单, 发不出货。
 *
 * <p>测试 pattern 沿用 {@link SalesDeliveryHonorBatchAllocationTest}:
 * null ctor + 反射注入 mock + 反射调用 private {@code deductFinishedGoodsInventory}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("🔴 卖物料时从物料批次扣库存")
class SalesMaterialLineShipmentTest {

    private static final String FACTORY = "LIUSHANMEN";
    private static final String MATERIAL_ID = "RMT_LSM_BC001";
    private static final String PRODUCT_ID = "PT_LSM_0001";

    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private MaterialBatchService materialBatchService;

    private SalesServiceImpl salesService;

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                null, null, null, finishedGoodsBatchRepository,
                null, productTypeRepository, null, null);
        ReflectionTestUtils.setField(salesService, "salesRawMaterialTypeRepository", rawMaterialTypeRepository);
        ReflectionTestUtils.setField(salesService, "salesMaterialBatchService", materialBatchService);
    }

    private void invokeDeduct(SalesDeliveryItem item) throws Exception {
        Method m = SalesServiceImpl.class.getDeclaredMethod(
                "deductFinishedGoodsInventory", String.class, String.class, SalesDeliveryItem.class);
        m.setAccessible(true);
        try {
            m.invoke(salesService, FACTORY, (String) null, item);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    private SalesDeliveryItem item(String productTypeId, String qty) {
        SalesDeliveryItem it = new SalesDeliveryItem();
        it.setId(1L);
        it.setProductTypeId(productTypeId);
        it.setProductName("吸塑盒2014-3.5");
        it.setDeliveredQuantity(new BigDecimal(qty));
        it.setUnit("个");
        return it;
    }

    private void materialExists() {
        RawMaterialType m = new RawMaterialType();
        m.setId(MATERIAL_ID);
        m.setFactoryId(FACTORY);
        when(rawMaterialTypeRepository.findByIdAndFactoryId(MATERIAL_ID, FACTORY)).thenReturn(Optional.of(m));
        when(productTypeRepository.findByIdAndFactoryId(MATERIAL_ID, FACTORY)).thenReturn(Optional.empty());
    }

    private MaterialBatchDTO batch(String id, String qty) {
        MaterialBatchDTO b = new MaterialBatchDTO();
        b.setId(id);
        b.setMaterialTypeId(MATERIAL_ID);
        b.setCurrentQuantity(new BigDecimal(qty));
        return b;
    }

    @Test
    @DisplayName("物料行走物料 FIFO —— 一个成品批次都不碰")
    void materialLineDeductsFromMaterialBatches() throws Exception {
        materialExists();
        when(materialBatchService.getFIFOBatches(eq(FACTORY), eq(MATERIAL_ID), any()))
                .thenReturn(List.of(batch("B1", "30")));

        invokeDeduct(item(MATERIAL_ID, "30"));

        verify(materialBatchService).useBatchQuantity(FACTORY, "B1", new BigDecimal("30"));
        // 🔴 关键: 成品那条路一个字都不该执行
        verify(finishedGoodsBatchRepository, never()).findShippableBatchesByWarehouse(any(), any(), any());
    }

    @Test
    @DisplayName("跨批次按 FIFO 依次扣, 扣满为止")
    void spansMultipleBatchesInOrder() throws Exception {
        materialExists();
        when(materialBatchService.getFIFOBatches(eq(FACTORY), eq(MATERIAL_ID), any()))
                .thenReturn(List.of(batch("B1", "12"), batch("B2", "50")));

        invokeDeduct(item(MATERIAL_ID, "30"));

        verify(materialBatchService).useBatchQuantity(FACTORY, "B1", new BigDecimal("12"));
        verify(materialBatchService).useBatchQuantity(FACTORY, "B2", new BigDecimal("18"));
    }

    @Test
    @DisplayName("库存不足要报错 —— 不能少扣了还把货发出去")
    void insufficientStockThrows() {
        materialExists();
        when(materialBatchService.getFIFOBatches(eq(FACTORY), eq(MATERIAL_ID), any()))
                .thenReturn(List.of(batch("B1", "5")));

        assertThatThrownBy(() -> invokeDeduct(item(MATERIAL_ID, "30")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("物料库存不足");
        // 已扣的那部分照旧(事务由调用方回滚), 但绝不能静默放行
        verify(materialBatchService).useBatchQuantity(FACTORY, "B1", new BigDecimal("5"));
    }

    /**
     * ⛔ 依赖没接上时必须炸。静默跳过 = 「发了货但没扣库存」, 比发不出货糟得多,
     * 而且账面看起来一切正常, 要到盘点才发现。
     */
    @Test
    @DisplayName("⛔ MaterialBatchService 没注入时必须炸, 不能静默不扣")
    void missingDependencyThrows() {
        materialExists();
        ReflectionTestUtils.setField(salesService, "salesMaterialBatchService", null);

        assertThatThrownBy(() -> invokeDeduct(item(MATERIAL_ID, "30")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MaterialBatchService is required");
    }

    /**
     * 判据: 启用商品优先。全库实测重叠 3 个 id 且<b>全是停用的画布占位</b>,
     * 「既是启用商品又是物料」为 0 —— 这条用例守住那个前提破裂时的行为(按成品走, 不静默改道)。
     */
    @Test
    @DisplayName("同一 id 既是启用商品又是物料时按【成品】走 —— 不静默改道去扣物料")
    void activeProductWinsOverMaterial() throws Exception {
        RawMaterialType m = new RawMaterialType();
        m.setId(MATERIAL_ID);
        m.setFactoryId(FACTORY);
        when(rawMaterialTypeRepository.findByIdAndFactoryId(MATERIAL_ID, FACTORY)).thenReturn(Optional.of(m));
        ProductType active = new ProductType();
        active.setId(MATERIAL_ID);
        active.setIsActive(true);
        when(productTypeRepository.findByIdAndFactoryId(MATERIAL_ID, FACTORY)).thenReturn(Optional.of(active));

        try {
            invokeDeduct(item(MATERIAL_ID, "30"));
        } catch (RuntimeException ignored) {
            // 成品那条路在本用例的 mock 下会因缺少仓库/批次而抛 —— 与本用例要证明的事无关。
        }
        verify(materialBatchService, never()).useBatchQuantity(any(), any(), any());
    }

    @Test
    @DisplayName("普通成品行完全不受影响 —— 不碰物料仓库")
    void productLineUntouched() {
        when(rawMaterialTypeRepository.findByIdAndFactoryId(PRODUCT_ID, FACTORY)).thenReturn(Optional.empty());

        try {
            invokeDeduct(item(PRODUCT_ID, "10"));
        } catch (Exception ignored) {
            // 同上: 成品路径的其余依赖本用例没搭, 抛出与本用例无关。
        }
        verify(materialBatchService, never()).getFIFOBatches(any(), any(), any());
        verify(materialBatchService, never()).useBatchQuantity(any(), any(), any());
    }
}
