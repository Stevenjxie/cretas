package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.FinanceCostBreakdown;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * B2 fix: getOrderCostBreakdown must not report 0 for bomStandardUnitCost /
 * actualLineCost when the underlying data is "not configured" (null or zero
 * stored due to missing prices / migrations).
 *
 * <p>Covers the two zero-masking scenarios fixed in B2:
 * <ol>
 *   <li>BomRecipe.totalCost == 0 (empty-items path in recomputeMaterialCost):
 *       must return null, not 0.</li>
 *   <li>SalesOrderItem.costUnitPrice == 0 (migration default or unset):
 *       must be treated as missing (anyActualMissing=true), not as "cost = 0".</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("B2 Fix: cost breakdown does not report fake zeros")
class SalesServiceImplCostBreakdownB2Test {

    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private BomRecipeService bomRecipeService;
    @Mock
    private com.cretas.aims.repository.inventory.SalesOrderItemRepository salesOrderItemRepository;

    private SalesServiceImpl salesService;

    private static final String FACTORY_ID = "F001";
    private static final String ORDER_ID = "SO-001";
    private static final String PRODUCT_ID = "PT-001";

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                salesOrderRepository,
                salesOrderItemRepository,
                null, null, null, null, null, null);
        // Inject optional BomRecipeService via reflection (field is @Autowired optional)
        ReflectionTestUtils.setField(salesService, "bomRecipeService", bomRecipeService);
    }

    /** Build a minimal SalesOrder with one item. */
    private SalesOrder makeOrder(BigDecimal sellPrice, BigDecimal costPrice) {
        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId(PRODUCT_ID);
        item.setProductName("测试产品");
        item.setQuantity(BigDecimal.TEN);
        item.setUnitPrice(sellPrice);
        item.setCostUnitPrice(costPrice);

        SalesOrder order = new SalesOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY_ID);
        order.setStatus(SalesOrderStatus.PENDING_FINANCE_REVIEW);
        order.setTotalAmount(sellPrice != null ? sellPrice.multiply(BigDecimal.TEN) : null);
        order.setItems(new java.util.ArrayList<>(List.of(item)));
        return order;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BOM totalCost == 0 → bomStandardUnitCost must be null (not 0)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BomRecipe.totalCost == 0 → bomStandardUnitCost is null (not 0)")
    void bomTotalCostZero_returnsNullNotZero() {
        SalesOrder order = makeOrder(new BigDecimal("9.00"), null);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        BomRecipe recipe = new BomRecipe();
        recipe.setTotalCost(BigDecimal.ZERO); // zero — prices not configured
        when(bomRecipeService.getCurrentRecipe(FACTORY_ID, PRODUCT_ID))
                .thenReturn(Optional.of(recipe));

        FinanceCostBreakdown result = salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID);

        assertNull(result.getBomStandardCost(),
                "bomStandardCost must be null when totalCost=0 (prices not configured)");
        assertNull(result.getLines().get(0).getBomStandardUnitCost(),
                "bomStandardUnitCost must be null when totalCost=0");
        assertNull(result.getLines().get(0).getBomStandardLineCost(),
                "bomStandardLineCost must be null when totalCost=0");
        assertNotNull(result.getDataSourceHint(),
                "dataSourceHint must explain the missing BOM price");
    }

    @Test
    @DisplayName("BomRecipe.totalCost == null → bomStandardUnitCost is null (not 0)")
    void bomTotalCostNull_returnsNull() {
        SalesOrder order = makeOrder(new BigDecimal("9.00"), null);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        BomRecipe recipe = new BomRecipe();
        recipe.setTotalCost(null); // null — prices not configured
        when(bomRecipeService.getCurrentRecipe(FACTORY_ID, PRODUCT_ID))
                .thenReturn(Optional.of(recipe));

        FinanceCostBreakdown result = salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID);

        assertNull(result.getBomStandardCost());
        assertNull(result.getLines().get(0).getBomStandardUnitCost());
    }

    @Test
    @DisplayName("BomRecipe.totalCost > 0 → bomStandardUnitCost is populated correctly")
    void bomTotalCostPositive_populatesCorrectly() {
        SalesOrder order = makeOrder(new BigDecimal("9.00"), null);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        BomRecipe recipe = new BomRecipe();
        recipe.setTotalCost(new BigDecimal("6.5000")); // real cost configured
        when(bomRecipeService.getCurrentRecipe(FACTORY_ID, PRODUCT_ID))
                .thenReturn(Optional.of(recipe));

        FinanceCostBreakdown result = salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID);

        assertEquals(new BigDecimal("6.5000"), result.getLines().get(0).getBomStandardUnitCost());
        // bomStandardLineCost = 10 * 6.5 = 65.00
        assertEquals(new BigDecimal("65.00"), result.getLines().get(0).getBomStandardLineCost());
        assertNotNull(result.getBomStandardCost());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // costUnitPrice == 0 → actualLineCost must be null (treated as missing)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("costUnitPrice == 0 → actualLineCost is null (not 0)")
    void costUnitPriceZero_treatedAsMissing() {
        // costUnitPrice=0 means the field was never properly set (migration default or unset)
        SalesOrder order = makeOrder(new BigDecimal("9.00"), BigDecimal.ZERO);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(bomRecipeService.getCurrentRecipe(FACTORY_ID, PRODUCT_ID))
                .thenReturn(Optional.empty());

        FinanceCostBreakdown result = salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID);

        assertNull(result.getLines().get(0).getActualLineCost(),
                "actualLineCost must be null when costUnitPrice=0 (not configured)");
        assertNull(result.getActualCost(),
                "actualCost must be null when any line has zero costUnitPrice");
        assertNotNull(result.getDataSourceHint(),
                "hint must explain missing actual cost");
    }

    @Test
    @DisplayName("costUnitPrice == null → actualLineCost is null (standard missing path)")
    void costUnitPriceNull_treatedAsMissing() {
        SalesOrder order = makeOrder(new BigDecimal("9.00"), null);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(bomRecipeService.getCurrentRecipe(FACTORY_ID, PRODUCT_ID))
                .thenReturn(Optional.empty());

        FinanceCostBreakdown result = salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID);

        assertNull(result.getLines().get(0).getActualLineCost());
        assertNull(result.getActualCost());
    }

    @Test
    @DisplayName("costUnitPrice > 0 → actualLineCost is computed correctly")
    void costUnitPricePositive_computedCorrectly() {
        SalesOrder order = makeOrder(new BigDecimal("9.00"), new BigDecimal("5.50"));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(bomRecipeService.getCurrentRecipe(FACTORY_ID, PRODUCT_ID))
                .thenReturn(Optional.empty());

        FinanceCostBreakdown result = salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID);

        // actualLineCost = 10 * 5.50 = 55.00
        assertEquals(new BigDecimal("55.00"), result.getLines().get(0).getActualLineCost());
        assertEquals(new BigDecimal("55.00"), result.getActualCost());
    }
}
