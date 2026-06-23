package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.PurchaseSuggestionMultiResponse;
import com.cretas.aims.dto.inventory.PurchaseSuggestionResponse;
import com.cretas.aims.entity.bom.BomItem;
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
 * TDD tests for {@link PurchaseServiceImpl#generatePurchaseSuggestionMulti}.
 *
 * <p>转录行3650: 多个销售订单用"加号"逐个追加合并成一张采购单。
 * 验证: 跨 SO 同物料合并数量正确 / 库存只扣一次 / 跨租户某 SO 不属→403 /
 * 无 BOM SO 跳过标记 / 单 SO 退化等于原 generatePurchaseSuggestion。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseServiceImpl - generatePurchaseSuggestionMulti")
class PurchaseServiceImplSuggestionMultiTest {

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

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                null,
                supplierRepository,
                materialTypeRepository,
                materialBatchRepository,
                bomItemRepository,
                null,
                null,
                null);
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

    // ─── helper builders ─────────────────────────────────────────────────────────

    private SalesOrder mockSo(String id, String number, String customer) {
        SalesOrder so = new SalesOrder();
        so.setId(id);
        so.setFactoryId(FACTORY);
        so.setOrderNumber(number);
        so.setCustomerName(customer);
        return so;
    }

    private SalesOrderItem soItem(String soId, String productTypeId, String productName, BigDecimal qty) {
        SalesOrderItem item = new SalesOrderItem();
        item.setSalesOrderId(soId);
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

    // ─── tests ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Empty SO id list → BusinessException")
    void emptyList_throws() {
        assertThatThrownBy(() -> service.generatePurchaseSuggestionMulti(FACTORY, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少");
    }

    @Test
    @DisplayName("Null SO id list → BusinessException")
    void nullList_throws() {
        assertThatThrownBy(() -> service.generatePurchaseSuggestionMulti(FACTORY, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("One SO id missing → ResourceNotFoundException")
    void soNotFound_throws() {
        when(salesOrderRepository.findById("so-1")).thenReturn(Optional.of(mockSo("so-1", "SO-1", "客户A")));
        when(salesOrderItemRepository.findBySalesOrderId("so-1")).thenReturn(List.of());
        when(salesOrderRepository.findById("so-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generatePurchaseSuggestionMulti(FACTORY, List.of("so-1", "so-missing")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Cross-factory: one SO belongs to another factory → 403")
    void crossFactory_throws() {
        when(salesOrderRepository.findById("so-1")).thenReturn(Optional.of(mockSo("so-1", "SO-1", "客户A")));
        when(salesOrderItemRepository.findBySalesOrderId("so-1")).thenReturn(List.of());
        SalesOrder other = mockSo("so-2", "SO-2", "客户B");
        other.setFactoryId("OTHER_FACTORY");
        when(salesOrderRepository.findById("so-2")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.generatePurchaseSuggestionMulti(FACTORY, List.of("so-1", "so-2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("Same material across two SOs aggregated; stock deducted ONCE")
    void sameMaterialAcrossSos_aggregated_stockDeductedOnce() {
        // SO1: 牛腱产品 → rm-niujian, required 50; SO2: 卤味产品 → rm-niujian, required 30
        when(salesOrderRepository.findById("so-1")).thenReturn(Optional.of(mockSo("so-1", "SO-1", "客户A")));
        when(salesOrderRepository.findById("so-2")).thenReturn(Optional.of(mockSo("so-2", "SO-2", "客户B")));
        when(salesOrderItemRepository.findBySalesOrderId("so-1"))
                .thenReturn(List.of(soItem("so-1", "prod-1", "牛腱礼盒", new BigDecimal("50"))));
        when(salesOrderItemRepository.findBySalesOrderId("so-2"))
                .thenReturn(List.of(soItem("so-2", "prod-2", "卤味拼盘", new BigDecimal("30"))));

        // both products consume rm-niujian at 1.0/unit @ 100% yield
        BomItem bom1 = bomItem("prod-1", "rm-niujian", "牛腱", new BigDecimal("1.0"), new BigDecimal("100.0"), "RAW");
        BomItem bom2 = bomItem("prod-2", "rm-niujian", "牛腱", new BigDecimal("1.0"), new BigDecimal("100.0"), "RAW");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-1"))
                .thenReturn(List.of(bom1));
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-2"))
                .thenReturn(List.of(bom2));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY, "rm-niujian"))
                .thenReturn(new BigDecimal("20.0"));

        PurchaseSuggestionMultiResponse result =
                service.generatePurchaseSuggestionMulti(FACTORY, List.of("so-1", "so-2"));

        assertThat(result.isHasBom()).isTrue();
        assertThat(result.getItems()).hasSize(1);
        PurchaseSuggestionMultiResponse.SuggestionItem item = result.getItems().get(0);
        // required = 50 + 30 = 80
        assertThat(item.getRequiredQuantity()).isEqualByComparingTo(new BigDecimal("80.0000"));
        // stock deducted ONCE: net = 80 - 20 = 60 (NOT 80 - 40)
        assertThat(item.getNetRequired()).isEqualByComparingTo(new BigDecimal("60.0000"));
        assertThat(item.isStockSufficient()).isFalse();
        // source SO numbers both recorded, deduped/ordered
        assertThat(item.getSourceSalesOrderNumbers()).containsExactly("SO-1", "SO-2");

        // stock query called exactly once for this material (single deduction)
        verify(materialBatchRepository, times(1))
                .sumAvailableQuantityByMaterialType(FACTORY, "rm-niujian");

        // top-level metadata
        assertThat(result.getSalesOrderIds()).containsExactly("so-1", "so-2");
        assertThat(result.getSalesOrderNumbers()).containsExactly("SO-1", "SO-2");
        assertThat(result.getCustomerNames()).containsExactlyInAnyOrder("客户A", "客户B");
        assertThat(result.getSoSummaries()).hasSize(2);
        assertThat(result.getSoSummaries()).allMatch(PurchaseSuggestionMultiResponse.SoSummary::isHasBom);
    }

    @Test
    @DisplayName("Different materials across SOs → multiple aggregated rows")
    void differentMaterials_multipleRows() {
        when(salesOrderRepository.findById("so-1")).thenReturn(Optional.of(mockSo("so-1", "SO-1", "客户A")));
        when(salesOrderRepository.findById("so-2")).thenReturn(Optional.of(mockSo("so-2", "SO-2", "客户A")));
        when(salesOrderItemRepository.findBySalesOrderId("so-1"))
                .thenReturn(List.of(soItem("so-1", "prod-1", "牛腱", new BigDecimal("10"))));
        when(salesOrderItemRepository.findBySalesOrderId("so-2"))
                .thenReturn(List.of(soItem("so-2", "prod-2", "猪舌", new BigDecimal("10"))));

        BomItem bom1 = bomItem("prod-1", "rm-niujian", "牛腱", new BigDecimal("2.0"), new BigDecimal("100.0"), "RAW");
        BomItem bom2 = bomItem("prod-2", "rm-zhushe", "猪舌", new BigDecimal("3.0"), new BigDecimal("100.0"), "RAW");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-1"))
                .thenReturn(List.of(bom1));
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-2"))
                .thenReturn(List.of(bom2));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY, "rm-niujian"))
                .thenReturn(BigDecimal.ZERO);
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY, "rm-zhushe"))
                .thenReturn(BigDecimal.ZERO);

        PurchaseSuggestionMultiResponse result =
                service.generatePurchaseSuggestionMulti(FACTORY, List.of("so-1", "so-2"));

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems())
                .extracting(PurchaseSuggestionMultiResponse.SuggestionItem::getMaterialTypeId)
                .containsExactlyInAnyOrder("rm-niujian", "rm-zhushe");
        // customer dedup: both SOs are 客户A
        assertThat(result.getCustomerNames()).containsExactly("客户A");
    }

    @Test
    @DisplayName("SO with no BOM → marked hasBom=false in summary, does not block others")
    void noBomSo_markedAndSkipped() {
        when(salesOrderRepository.findById("so-1")).thenReturn(Optional.of(mockSo("so-1", "SO-1", "客户A")));
        when(salesOrderRepository.findById("so-2")).thenReturn(Optional.of(mockSo("so-2", "SO-2", "客户B")));
        // so-1 has a BOM product
        when(salesOrderItemRepository.findBySalesOrderId("so-1"))
                .thenReturn(List.of(soItem("so-1", "prod-1", "牛腱", new BigDecimal("10"))));
        BomItem bom1 = bomItem("prod-1", "rm-niujian", "牛腱", new BigDecimal("1.0"), new BigDecimal("100.0"), "RAW");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-1"))
                .thenReturn(List.of(bom1));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY, "rm-niujian"))
                .thenReturn(BigDecimal.ZERO);
        // so-2 has items but product has no BOM at all
        when(salesOrderItemRepository.findBySalesOrderId("so-2"))
                .thenReturn(List.of(soItem("so-2", "prod-nobom", "无配方品", new BigDecimal("5"))));
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-nobom"))
                .thenReturn(List.of());

        PurchaseSuggestionMultiResponse result =
                service.generatePurchaseSuggestionMulti(FACTORY, List.of("so-1", "so-2"));

        assertThat(result.isHasBom()).isTrue(); // at least one SO had BOM
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getSourceSalesOrderNumbers()).containsExactly("SO-1");

        // summaries honestly expose so-2 had no BOM
        var summaryFor2 = result.getSoSummaries().stream()
                .filter(s -> s.getSalesOrderId().equals("so-2")).findFirst().orElseThrow();
        assertThat(summaryFor2.isHasBom()).isFalse();
        var summaryFor1 = result.getSoSummaries().stream()
                .filter(s -> s.getSalesOrderId().equals("so-1")).findFirst().orElseThrow();
        assertThat(summaryFor1.isHasBom()).isTrue();
    }

    @Test
    @DisplayName("All SOs have no BOM → hasBom=false, items empty, summaries all false")
    void allNoBom_emptyItems() {
        when(salesOrderRepository.findById("so-1")).thenReturn(Optional.of(mockSo("so-1", "SO-1", "客户A")));
        when(salesOrderItemRepository.findBySalesOrderId("so-1")).thenReturn(List.of());

        PurchaseSuggestionMultiResponse result =
                service.generatePurchaseSuggestionMulti(FACTORY, List.of("so-1"));

        assertThat(result.isHasBom()).isFalse();
        assertThat(result.getItems()).isEmpty();
        assertThat(result.getSoSummaries()).hasSize(1);
        assertThat(result.getSoSummaries().get(0).isHasBom()).isFalse();
    }

    @Test
    @DisplayName("Duplicate SO ids deduped → counted once, not double aggregated")
    void duplicateIds_deduped() {
        when(salesOrderRepository.findById("so-1")).thenReturn(Optional.of(mockSo("so-1", "SO-1", "客户A")));
        when(salesOrderItemRepository.findBySalesOrderId("so-1"))
                .thenReturn(List.of(soItem("so-1", "prod-1", "牛腱", new BigDecimal("10"))));
        BomItem bom1 = bomItem("prod-1", "rm-niujian", "牛腱", new BigDecimal("1.0"), new BigDecimal("100.0"), "RAW");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-1"))
                .thenReturn(List.of(bom1));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY, "rm-niujian"))
                .thenReturn(BigDecimal.ZERO);

        // same id passed twice
        PurchaseSuggestionMultiResponse result =
                service.generatePurchaseSuggestionMulti(FACTORY, List.of("so-1", "so-1"));

        assertThat(result.getSalesOrderIds()).containsExactly("so-1");
        // required = 10 (NOT 20) because so-1 only counted once
        assertThat(result.getItems().get(0).getRequiredQuantity())
                .isEqualByComparingTo(new BigDecimal("10.0000"));
    }

    @Test
    @DisplayName("Single SO via multi == single-SO generatePurchaseSuggestion (degenerate parity)")
    void singleSo_degeneratesToSingleSuggestion() {
        SalesOrder so = mockSo("so-1", "SO-1", "客户A");
        when(salesOrderRepository.findById("so-1")).thenReturn(Optional.of(so));
        when(salesOrderItemRepository.findBySalesOrderId("so-1"))
                .thenReturn(List.of(soItem("so-1", "prod-1", "牛腱", new BigDecimal("50"))));
        BomItem bom = bomItem("prod-1", "rm-niujian", "牛腱", new BigDecimal("2.0"), new BigDecimal("100.0"), "RAW");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, "prod-1"))
                .thenReturn(List.of(bom));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY, "rm-niujian"))
                .thenReturn(new BigDecimal("30.0"));

        PurchaseSuggestionResponse single = service.generatePurchaseSuggestion(FACTORY, "so-1");
        PurchaseSuggestionMultiResponse multi =
                service.generatePurchaseSuggestionMulti(FACTORY, List.of("so-1"));

        assertThat(multi.getItems()).hasSize(single.getItems().size());
        PurchaseSuggestionResponse.SuggestionItem s = single.getItems().get(0);
        PurchaseSuggestionMultiResponse.SuggestionItem m = multi.getItems().get(0);
        assertThat(m.getMaterialTypeId()).isEqualTo(s.getMaterialTypeId());
        assertThat(m.getRequiredQuantity()).isEqualByComparingTo(s.getRequiredQuantity());
        assertThat(m.getNetRequired()).isEqualByComparingTo(s.getNetRequired());
        assertThat(m.getCurrentStock()).isEqualByComparingTo(s.getCurrentStock());
        assertThat(multi.isHasBom()).isEqualTo(single.isHasBom());
    }
}
