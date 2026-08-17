package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 闸 —— 半成品不能开销售单，而且这道拦要在<b>后端</b>。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 prod 实测)</h2>
 *
 * 判据：「销售订单要能卖成品、原料、辅材、包材，**半成品不在此列**」。
 *
 * 销售下拉 {@code GET /product-types/sellable} 确实把半成品排除了 —— 实测 141 个可售项里
 * 没有「SOP-20260817-01-黄油鸡-处理后半成品」。<b>但后端不校验</b>：直接 POST 一条
 * productTypeId 指向它的行，返回 <b>200「销售订单创建成功」</b>（SO-20260818-0006，已取消）。
 *
 * <p>形态 B：防呆只在前端下拉里 = 半吊子。前端换版本、AI 工具直调接口、从别处复制订单，
 * 都能把半成品卖出去。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>判定<b>只用</b> {@code ProductCategory.isSellable} —— 它的 javadoc 写着「这里是唯一权威」。
 *       ⛔ 本守卫不重复那个条件，否则就是本仓最高频的「两处口径打架」。</li>
 *   <li>只拦<b>确实查得到且确实是半成品</b>的行。原料/辅料/包材走物料字典、根本不在
 *       {@code product_types} 里 —— 查不到就放行。<b>误拦那四类比漏拦半成品更糟</b>
 *       （Steve 2026-08-12「出了半成品全开」）。</li>
 * </ul>
 */
class SalesOrderSellableGuardTest {

    private static final String F = "F006";
    private static final String SEMI = "17c929e1-9d90-408f-b064-7970b9b7dac0";
    private static final String FG = "eb0aa47b-a5dd-49dc-af20-bf48ce8e1207";
    private static final String RAW_MATERIAL = "RMT_1777441647274";   // 物料字典, 不在 product_types

    private SalesServiceImpl service;
    private ProductTypeRepository repo;

    @BeforeEach
    void setUp() {
        service = mock(SalesServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        repo = mock(ProductTypeRepository.class);
        // 默认: 查不到 (物料字典那三类走这条)
        when(repo.findByIdAndFactoryId(anyString(), eq(F))).thenReturn(Optional.empty());
        when(repo.findByIdAndFactoryId(eq(SEMI), eq(F)))
                .thenReturn(Optional.of(productType(SEMI, "SOP-20260817-01-黄油鸡-处理后半成品", "SEMI_FINISHED")));
        when(repo.findByIdAndFactoryId(eq(FG), eq(F)))
                .thenReturn(Optional.of(productType(FG, "SOP-20260817-01-黄油鸡-成品800g", "FINISHED_PRODUCT")));
        ReflectionTestUtils.setField(service, "productTypeRepository", repo);
    }

    private static ProductType productType(String id, String name, String category) {
        ProductType pt = new ProductType();
        pt.setId(id);
        pt.setName(name);
        pt.setProductCategory(category);
        return pt;
    }

    private static CreateSalesOrderRequest.SalesOrderItemDTO item(String productTypeId) {
        CreateSalesOrderRequest.SalesOrderItemDTO it = new CreateSalesOrderRequest.SalesOrderItemDTO();
        it.setProductTypeId(productTypeId);
        it.setQuantity(BigDecimal.ONE);
        it.setUnitPrice(BigDecimal.TEN);
        return it;
    }

    private void assertSellable(List<CreateSalesOrderRequest.SalesOrderItemDTO> items) {
        ReflectionTestUtils.invokeMethod(service, "assertItemsAreSellable", F, items);
    }

    @Test
    @DisplayName("阳性对照: 成品放行 (否则下面的「该拦」断言可能只是因为它拦了一切)")
    void finishedProductPasses() {
        assertDoesNotThrow(() -> assertSellable(List.of(item(FG))));
    }

    @Test
    @DisplayName("🔴 主断言: 半成品必须被拦, 并说清是哪个东西 + 下一步")
    void semiFinishedIsBlocked() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> assertSellable(List.of(item(SEMI))));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("半成品"), e.getMessage());
        assertTrue(e.getMessage().contains("处理后半成品"), "没说是哪个东西: " + e.getMessage());
        assertTrue(String.valueOf(e.getActionHint()).contains("改选成品"),
                "没给下一步: " + e.getActionHint());
    }

    @Test
    @DisplayName("🔴 阴性对照: 原料/辅料/包材走物料字典, 查不到 productType —— 一律放行, 不许误拦")
    void dictionaryMaterialsAreNotBlocked() {
        // 实测这三类都必须能开单 (Steve 2026-08-12「出了半成品全开」); 误拦它们比漏拦半成品更糟
        assertDoesNotThrow(() -> assertSellable(List.of(item(RAW_MATERIAL))));
    }

    @Test
    @DisplayName("混单: 只要有一行是半成品就整单拒, 且报的是那一行")
    void mixedOrderIsRejectedNamingTheOffendingLine() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> assertSellable(List.of(item(FG), item(RAW_MATERIAL), item(SEMI))));
        assertTrue(e.getMessage().contains("处理后半成品"), e.getMessage());
        assertFalse(e.getMessage().contains("成品800g"), "报错点名了无辜的那一行: " + e.getMessage());
    }

    @Test
    @DisplayName("空/无 productTypeId 的行不炸 —— 守卫不该把没填完的草稿拦死")
    void blankLinesAreSkipped() {
        assertDoesNotThrow(() -> assertSellable(List.of(item(null), item(""))));
        assertDoesNotThrow(() -> assertSellable(List.of()));
    }
}
