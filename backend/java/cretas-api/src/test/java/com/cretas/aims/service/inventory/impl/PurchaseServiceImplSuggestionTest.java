package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.PurchaseSuggestionResponse;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TDD tests for {@link PurchaseServiceImpl#generatePurchaseSuggestion}.
 *
 * <p>客户原话: "做个弹窗…我直接点开始采购…不然新增有点麻烦，全部手写没意义" (t2b 行1867-1902)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseServiceImpl - generatePurchaseSuggestion")
class PurchaseServiceImplSuggestionTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private BomItemRepository bomItemRepository;
    @Mock private BomRecipeRepository bomRecipeRepository;
    @Mock private BomRecipeItemRepository bomRecipeItemRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;

    private PurchaseServiceImpl service;

    private static final String FACTORY = "F006";
    private static final String SO_ID = "so-001";

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(
                purchaseOrderRepository,         // PurchaseOrderRepository
                purchaseOrderItemRepository,     // PurchaseOrderItemRepository
                null,                            // PurchaseReceiveRecordRepository
                supplierRepository,              // SupplierRepository
                materialTypeRepository,          // RawMaterialTypeRepository
                materialBatchRepository,         // MaterialBatchRepository
                bomItemRepository,               // BomItemRepository
                null,                            // ArApService
                null,                            // ApplicationEventPublisher
                null);                           // MaterialBatchService
        // Field-inject optional repos
        try {
            var soRepoField = PurchaseServiceImpl.class.getDeclaredField("salesOrderRepository");
            soRepoField.setAccessible(true);
            soRepoField.set(service, salesOrderRepository);

            var soItemRepoField = PurchaseServiceImpl.class.getDeclaredField("salesOrderItemRepository");
            soItemRepoField.setAccessible(true);
            soItemRepoField.set(service, salesOrderItemRepository);

            var recipeRepoField = PurchaseServiceImpl.class.getDeclaredField("bomRecipeRepository");
            recipeRepoField.setAccessible(true);
            recipeRepoField.set(service, bomRecipeRepository);

            var recipeItemRepoField = PurchaseServiceImpl.class.getDeclaredField("bomRecipeItemRepository");
            recipeItemRepoField.setAccessible(true);
            recipeItemRepoField.set(service, bomRecipeItemRepository);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── helper builders ───────────────────────────────────────────────────────

    private SalesOrder mockSo() {
        SalesOrder so = new SalesOrder();
        so.setId(SO_ID);
        so.setFactoryId(FACTORY);
        so.setOrderNumber("SO-20260611-001");
        so.setCustomerName("六扇门食品");
        return so;
    }

    private SalesOrderItem soItem(String productTypeId, String productName, BigDecimal qty) {
        SalesOrderItem item = new SalesOrderItem();
        item.setSalesOrderId(SO_ID);
        item.setProductTypeId(productTypeId);
        item.setProductName(productName);
        item.setQuantity(qty);
        return item;
    }

    private BomItem bomItem(String productTypeId, String matId, String matName,
                             BigDecimal stdQty, BigDecimal yieldRate, String category) {
        BomItem b = new BomItem();
        b.setFactoryId(FACTORY);
        b.setProductTypeId(productTypeId);
        b.setMaterialTypeId(matId);
        b.setMaterialName(matName);
        b.setStandardQuantity(stdQty);
        b.setYieldRate(yieldRate);
        b.setMaterialCategory(category);
        b.setUnit("kg");
        return b;
    }

    private BomRecipe recipe(String recipeId, String productTypeId) {
        BomRecipe r = new BomRecipe();
        r.setId(recipeId);
        r.setFactoryId(FACTORY);
        r.setProductTypeId(productTypeId);
        r.setStatus(BomRecipe.Status.ACTIVE);
        r.setIsCurrent(true);
        return r;
    }

    private BomRecipeItem recipeItem(String recipeId, String matId, String matName,
                                     BigDecimal stdQty, BigDecimal yieldRate, String category,
                                     BigDecimal actualQty) {
        return BomRecipeItem.builder()
                .recipeId(recipeId)
                .factoryId(FACTORY)
                .materialTypeId(matId)
                .materialName(matName)
                .standardQuantity(stdQty)
                .yieldRate(yieldRate)
                .actualQuantity(actualQty)
                .materialCategory(category)
                .unit("kg")
                .unitPrice(new BigDecimal("12.50"))
                .build();
    }

    /** Stub the current-ACTIVE recipe lookup + its items for a product. */
    private void stubRecipe(String productTypeId, BomRecipe r, List<BomRecipeItem> items) {
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY, productTypeId, BomRecipe.Status.ACTIVE)).thenReturn(Optional.of(r));
        when(bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(r.getId())).thenReturn(items);
    }

    // ─── tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SO not found → ResourceNotFoundException")
    void soNotFound_throws() {
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.generatePurchaseSuggestion(FACTORY, SO_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Cross-factory access → BusinessException 403")
    void crossFactory_throws() {
        SalesOrder so = mockSo();
        so.setFactoryId("OTHER_FACTORY");
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(so));
        assertThatThrownBy(() -> service.generatePurchaseSuggestion(FACTORY, SO_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName("SO has no items → hasBom=false, items empty")
    void noSoItems_hasBomFalse() {
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(mockSo()));
        when(salesOrderItemRepository.findBySalesOrderId(SO_ID)).thenReturn(List.of());

        PurchaseSuggestionResponse result = service.generatePurchaseSuggestion(FACTORY, SO_ID);

        assertThat(result.isHasBom()).isFalse();
        assertThat(result.getItems()).isEmpty();
        assertThat(result.getSalesOrderNumber()).isEqualTo("SO-20260611-001");
        assertThat(result.getCustomerName()).isEqualTo("六扇门食品");
    }

    @Test
    @DisplayName("SO item has no BOM configured → hasBom=false, items empty")
    void noBomForProduct_itemsEmpty() {
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(mockSo()));
        when(salesOrderItemRepository.findBySalesOrderId(SO_ID))
                .thenReturn(List.of(soItem("prod-001", "猪舌", new BigDecimal("100"))));
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-001"))
                .thenReturn(List.of());

        PurchaseSuggestionResponse result = service.generatePurchaseSuggestion(FACTORY, SO_ID);

        assertThat(result.isHasBom()).isFalse();
        assertThat(result.getItems()).isEmpty();
    }

    @Test
    @DisplayName("BOM expansion: required = (stdQty / yieldRate%) × soQty; net = required - stock")
    void bomExpansion_calculatesNetRequired() {
        // BOM: 猪舌 100盒，每盒需要冷鲜猪舌 1.8kg（stdQty=1.5, yieldRate=83.33 → actual≈1.8）
        // 简化: stdQty=2.0, yieldRate=100.0 → actual=2.0 per unit, SO qty=50 → required=100 kg
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(mockSo()));
        when(salesOrderItemRepository.findBySalesOrderId(SO_ID))
                .thenReturn(List.of(soItem("prod-001", "猪舌", new BigDecimal("50"))));

        BomItem bom = bomItem("prod-001", "rm-001", "冷鲜猪舌",
                new BigDecimal("2.0"), new BigDecimal("100.0"), "RAW");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-001"))
                .thenReturn(List.of(bom));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY, "rm-001"))
                .thenReturn(new BigDecimal("30.0"));

        PurchaseSuggestionResponse result = service.generatePurchaseSuggestion(FACTORY, SO_ID);

        assertThat(result.isHasBom()).isTrue();
        assertThat(result.getItems()).hasSize(1);

        PurchaseSuggestionResponse.SuggestionItem item = result.getItems().get(0);
        assertThat(item.getMaterialTypeId()).isEqualTo("rm-001");
        assertThat(item.getMaterialName()).isEqualTo("冷鲜猪舌");
        assertThat(item.getMaterialCategory()).isEqualTo("RAW");
        assertThat(item.getRequiredQuantity()).isEqualByComparingTo(new BigDecimal("100.0"));
        assertThat(item.getCurrentStock()).isEqualByComparingTo(new BigDecimal("30.0"));
        // net = 100 - 30 = 70
        assertThat(item.getNetRequired()).isEqualByComparingTo(new BigDecimal("70.0000"));
        assertThat(item.isStockSufficient()).isFalse();
    }

    @Test
    @DisplayName("Stock >= required → netRequired=0, stockSufficient=true")
    void stockSufficient_netRequiredZero() {
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(mockSo()));
        when(salesOrderItemRepository.findBySalesOrderId(SO_ID))
                .thenReturn(List.of(soItem("prod-001", "猪舌", new BigDecimal("10"))));

        BomItem bom = bomItem("prod-001", "rm-001", "冷鲜猪舌",
                new BigDecimal("1.0"), new BigDecimal("100.0"), "RAW");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-001"))
                .thenReturn(List.of(bom));
        // stock 50 > required 10
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY, "rm-001"))
                .thenReturn(new BigDecimal("50.0"));

        PurchaseSuggestionResponse result = service.generatePurchaseSuggestion(FACTORY, SO_ID);

        PurchaseSuggestionResponse.SuggestionItem item = result.getItems().get(0);
        assertThat(item.getNetRequired()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(item.isStockSufficient()).isTrue();
    }

    @Test
    @DisplayName("Multiple SO items → same material aggregated across products")
    void multipleProducts_sameMaterialAggregated() {
        // 两个产品都需要同一种原料 rm-001
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(mockSo()));
        when(salesOrderItemRepository.findBySalesOrderId(SO_ID))
                .thenReturn(List.of(
                        soItem("prod-001", "猪舌", new BigDecimal("20")),
                        soItem("prod-002", "牛腱", new BigDecimal("10"))
                ));

        BomItem bom1 = bomItem("prod-001", "rm-001", "盐", new BigDecimal("0.5"), new BigDecimal("100.0"), "AUXILIARY");
        BomItem bom2 = bomItem("prod-002", "rm-001", "盐", new BigDecimal("0.3"), new BigDecimal("100.0"), "AUXILIARY");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-001"))
                .thenReturn(List.of(bom1));
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-002"))
                .thenReturn(List.of(bom2));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY, "rm-001"))
                .thenReturn(BigDecimal.ZERO);

        PurchaseSuggestionResponse result = service.generatePurchaseSuggestion(FACTORY, SO_ID);

        assertThat(result.getItems()).hasSize(1);
        // required = 0.5×20 + 0.3×10 = 10 + 3 = 13
        assertThat(result.getItems().get(0).getRequiredQuantity())
                .isEqualByComparingTo(new BigDecimal("13.0000"));
    }

    @Test
    @DisplayName("BOM item with yield rate < 100 → actualQty > standardQty")
    void yieldRateApplied() {
        // stdQty=1.0, yieldRate=50.0 → actual = 1.0 / (50/100) = 2.0
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(mockSo()));
        when(salesOrderItemRepository.findBySalesOrderId(SO_ID))
                .thenReturn(List.of(soItem("prod-001", "掌中宝", new BigDecimal("1"))));

        BomItem bom = bomItem("prod-001", "rm-001", "鸡掌",
                new BigDecimal("1.0"), new BigDecimal("50.0"), "RAW");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-001"))
                .thenReturn(List.of(bom));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY, "rm-001"))
                .thenReturn(BigDecimal.ZERO);

        PurchaseSuggestionResponse result = service.generatePurchaseSuggestion(FACTORY, SO_ID);

        // actual = 1.0 / 0.5 = 2.0; required = 2.0 × 1 = 2.0
        assertThat(result.getItems().get(0).getRequiredQuantity())
                .isEqualByComparingTo(new BigDecimal("2.0000"));
    }

    // ─── bom_recipe_items source (口径统一: 同财务成本拆分) ──────────────────────

    @Test
    @DisplayName("Recipe source preferred: net required uses BomRecipeItem.actualQuantity")
    void recipeSource_usesActualQuantity() {
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(mockSo()));
        when(salesOrderItemRepository.findBySalesOrderId(SO_ID))
                .thenReturn(List.of(soItem("prod-001", "猪舌", new BigDecimal("100"))));

        // recipe: 猪舌 123g @ 80% yield → actualQuantity persisted = 153.75g (≈recipe 实际用量)
        BomRecipe r = recipe("recipe-001", "prod-001");
        BomRecipeItem ri = recipeItem("recipe-001", "rm-001", "冷鲜猪舌",
                new BigDecimal("123.0"), new BigDecimal("80.0"), "RAW", new BigDecimal("153.75"));
        stubRecipe("prod-001", r, List.of(ri));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY, "rm-001"))
                .thenReturn(new BigDecimal("3000.0"));

        PurchaseSuggestionResponse result = service.generatePurchaseSuggestion(FACTORY, SO_ID);

        assertThat(result.isHasBom()).isTrue();
        assertThat(result.getItems()).hasSize(1);
        PurchaseSuggestionResponse.SuggestionItem item = result.getItems().get(0);
        // required = 153.75 × 100 = 15375
        assertThat(item.getRequiredQuantity()).isEqualByComparingTo(new BigDecimal("15375.0000"));
        // net = 15375 - 3000 = 12375
        assertThat(item.getNetRequired()).isEqualByComparingTo(new BigDecimal("12375.0000"));
        assertThat(item.getReferenceUnitPrice()).isEqualByComparingTo(new BigDecimal("12.50"));
        // legacy bom_items must NOT be consulted when recipe exists
        verify(bomItemRepository, never())
                .findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-001");
    }

    @Test
    @DisplayName("Recipe item with null actualQuantity → falls back to calculateActualQuantity()")
    void recipeSource_nullActualQuantity_computed() {
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(mockSo()));
        when(salesOrderItemRepository.findBySalesOrderId(SO_ID))
                .thenReturn(List.of(soItem("prod-001", "掌中宝", new BigDecimal("1"))));

        BomRecipe r = recipe("recipe-002", "prod-001");
        // actualQuantity null → calculateActualQuantity() = 1.0 / (50/100) = 2.0
        BomRecipeItem ri = recipeItem("recipe-002", "rm-001", "鸡掌",
                new BigDecimal("1.0"), new BigDecimal("50.0"), "RAW", null);
        stubRecipe("prod-001", r, List.of(ri));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY, "rm-001"))
                .thenReturn(BigDecimal.ZERO);

        PurchaseSuggestionResponse result = service.generatePurchaseSuggestion(FACTORY, SO_ID);

        assertThat(result.getItems().get(0).getRequiredQuantity())
                .isEqualByComparingTo(new BigDecimal("2.0000"));
    }

    @Test
    @DisplayName("No recipe → falls back to legacy bom_items (向后兼容)")
    void noRecipe_fallsBackToLegacyBomItems() {
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(mockSo()));
        when(salesOrderItemRepository.findBySalesOrderId(SO_ID))
                .thenReturn(List.of(soItem("prod-009", "老品无配方", new BigDecimal("50"))));

        // recipe lookup returns empty (default Mockito Optional) → legacy path
        BomItem bom = bomItem("prod-009", "rm-009", "老原料",
                new BigDecimal("2.0"), new BigDecimal("100.0"), "RAW");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-009"))
                .thenReturn(List.of(bom));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY, "rm-009"))
                .thenReturn(new BigDecimal("30.0"));

        PurchaseSuggestionResponse result = service.generatePurchaseSuggestion(FACTORY, SO_ID);

        assertThat(result.isHasBom()).isTrue();
        PurchaseSuggestionResponse.SuggestionItem item = result.getItems().get(0);
        assertThat(item.getMaterialName()).isEqualTo("老原料");
        assertThat(item.getRequiredQuantity()).isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(item.getNetRequired()).isEqualByComparingTo(new BigDecimal("70.0000"));
    }

    @Test
    @DisplayName("Recipe exists but has no items → hasBom stays false for that product, no NPE")
    void recipeWithNoItems_noNpe() {
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(mockSo()));
        when(salesOrderItemRepository.findBySalesOrderId(SO_ID))
                .thenReturn(List.of(soItem("prod-001", "空配方", new BigDecimal("10"))));

        BomRecipe r = recipe("recipe-empty", "prod-001");
        stubRecipe("prod-001", r, List.of());

        PurchaseSuggestionResponse result = service.generatePurchaseSuggestion(FACTORY, SO_ID);

        assertThat(result.isHasBom()).isFalse();
        assertThat(result.getItems()).isEmpty();
    }

    @Test
    @DisplayName("Mixed: one product has recipe, another only legacy → both expand, aggregate by matId")
    void mixedRecipeAndLegacy_aggregate() {
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(mockSo()));
        when(salesOrderItemRepository.findBySalesOrderId(SO_ID))
                .thenReturn(List.of(
                        soItem("prod-001", "猪舌(有配方)", new BigDecimal("10")),
                        soItem("prod-002", "牛腱(仅legacy)", new BigDecimal("10"))
                ));

        // prod-001 via recipe: 盐 actualQuantity=0.5 → 5
        BomRecipe r = recipe("recipe-001", "prod-001");
        BomRecipeItem ri = recipeItem("recipe-001", "rm-salt", "盐",
                new BigDecimal("0.5"), new BigDecimal("100.0"), "AUXILIARY", new BigDecimal("0.5"));
        stubRecipe("prod-001", r, List.of(ri));

        // prod-002 no recipe (empty Optional default) → legacy: 盐 actual=0.3 → 3
        BomItem bom = bomItem("prod-002", "rm-salt", "盐",
                new BigDecimal("0.3"), new BigDecimal("100.0"), "AUXILIARY");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-002"))
                .thenReturn(List.of(bom));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY, "rm-salt"))
                .thenReturn(BigDecimal.ZERO);

        PurchaseSuggestionResponse result = service.generatePurchaseSuggestion(FACTORY, SO_ID);

        assertThat(result.getItems()).hasSize(1);
        // required = 0.5×10 + 0.3×10 = 5 + 3 = 8
        assertThat(result.getItems().get(0).getRequiredQuantity())
                .isEqualByComparingTo(new BigDecimal("8.0000"));
    }
}
