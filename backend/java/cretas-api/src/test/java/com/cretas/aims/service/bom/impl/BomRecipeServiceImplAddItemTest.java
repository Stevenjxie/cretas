package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.uom.MaterialUomConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Regression test: BomRecipeServiceImpl.addItem / updateItem / deleteItem must NOT replace
 * the Hibernate-managed BomRecipe.items collection reference.
 *
 * <p>Root cause: {@code BomRecipe.items} is annotated with
 * {@code cascade = CascadeType.ALL, orphanRemoval = true}. Calling
 * {@code recipe.setItems(newList)} after a Hibernate session load replaces the
 * PersistentBag with a plain ArrayList, causing:
 * {@code HibernateException: A collection with cascade="all-delete-orphan" was no longer
 * referenced by the owning entity instance}.
 *
 * <p>Fix: {@link BomRecipeServiceImpl#refreshItemsInPlace(BomRecipe)} uses
 * {@code recipe.getItems().clear(); recipe.getItems().addAll(fresh)} to keep the same
 * collection instance. This test asserts that the original list instance is preserved
 * (i.e., {@code setItems} is never called with a different object reference after load).
 */
@DisplayName("BomRecipeServiceImpl.addItem — orphanRemoval collection identity preserved")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BomRecipeServiceImplAddItemTest {

    @Mock
    BomRecipeRepository recipeRepo;

    @Mock
    BomRecipeItemRepository itemRepo;

    @Mock
    RawMaterialTypeRepository materialTypeRepo;

    @Mock
    MaterialUomConverter materialUomConverter;

    /** SP1: NestedBomCostService must be mocked so @InjectMocks can inject it. */
    @Mock
    NestedBomCostService nestedBomCostService;

    @InjectMocks
    BomRecipeServiceImpl service;

    private BomRecipe recipe;
    private BomRecipeItem existingItem;
    /** The Hibernate-managed list instance that must NOT be replaced. */
    private List<BomRecipeItem> managedList;

    @BeforeEach
    void setUp() {
        recipe = new BomRecipe();
        recipe.setId("RECIPE-ORPHAN-TEST");
        recipe.setFactoryId("F006");
        recipe.setStatus(BomRecipe.Status.DRAFT);

        existingItem = new BomRecipeItem();
        existingItem.setId(1L);
        existingItem.setRecipeId("RECIPE-ORPHAN-TEST");
        existingItem.setFactoryId("F006");
        existingItem.setMaterialTypeId("MT-001");
        existingItem.setMaterialName("猪舌原料");
        existingItem.setUnit("kg");
        existingItem.setStandardQuantity(BigDecimal.valueOf(800));

        // Simulate Hibernate-managed PersistentBag by using a specific ArrayList instance
        managedList = new ArrayList<>();
        managedList.add(existingItem);
        recipe.setItems(managedList);

        // Mock repo lookups
        when(recipeRepo.findById("RECIPE-ORPHAN-TEST")).thenReturn(Optional.of(recipe));
        when(recipeRepo.save(any(BomRecipe.class))).thenAnswer(inv -> inv.getArgument(0));

        RawMaterialType mt = new RawMaterialType();
        mt.setId("MT-001");
        mt.setName("猪舌原料");
        mt.setUnit("kg");
        mt.setFactoryId("F006");
        mt.setPrimaryCode("001");
        mt.setTaxRate(TaxRate.TAX_9);
        mt.setUnitPrice(new BigDecimal("10.0000"));
        mt.setMovingAvgPrice(new BigDecimal("12.0000"));
        when(materialTypeRepo.findById("MT-001")).thenReturn(Optional.of(mt));
        when(materialTypeRepo.findById("MT-002")).thenReturn(Optional.of(newMt("MT-002", "包材A", "g", "002")));

        // uom converter: allow write unit (service calls isWriteUnitCompatible)
        when(materialUomConverter.isWriteUnitCompatible(anyString(), anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("激活新版本时旧 ACTIVE 版本必须同时归档并取消 current")
    void activateArchivesEveryCompetingActiveVersion() {
        recipe.setProductTypeId("SKU-001");
        recipe.setIsCurrent(false);

        BomRecipe oldCurrent = new BomRecipe();
        oldCurrent.setId("RECIPE-V2");
        oldCurrent.setFactoryId("F006");
        oldCurrent.setProductTypeId("SKU-001");
        oldCurrent.setStatus(BomRecipe.Status.ACTIVE);
        oldCurrent.setIsCurrent(true);

        BomRecipe staleActive = new BomRecipe();
        staleActive.setId("RECIPE-V1");
        staleActive.setFactoryId("F006");
        staleActive.setProductTypeId("SKU-001");
        staleActive.setStatus(BomRecipe.Status.ACTIVE);
        staleActive.setIsCurrent(false);

        when(recipeRepo.findById(recipe.getId())).thenReturn(Optional.of(recipe));
        when(recipeRepo.findCompetingVersionsForActivation(
                "F006", "SKU-001", recipe.getId(), BomRecipe.Status.ACTIVE))
                .thenReturn(List.of(oldCurrent, staleActive));
        when(recipeRepo.save(any(BomRecipe.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BomRecipe activated = service.activateRecipe("F006", recipe.getId(), 7L);

        assertThat(activated.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
        assertThat(activated.getIsCurrent()).isTrue();
        assertThat(oldCurrent.getStatus()).isEqualTo(BomRecipe.Status.ARCHIVED);
        assertThat(oldCurrent.getIsCurrent()).isFalse();
        assertThat(staleActive.getStatus()).isEqualTo(BomRecipe.Status.ARCHIVED);
        assertThat(staleActive.getIsCurrent()).isFalse();
        verify(recipeRepo).flush();
    }

    @Test
    @DisplayName("addItem: collection reference stays the same instance after call (no setItems replacement)")
    void addItem_keepsOriginalCollectionInstance() {
        BomRecipeItem newItem = new BomRecipeItem();
        newItem.setId(2L);
        newItem.setRecipeId("RECIPE-ORPHAN-TEST");
        newItem.setFactoryId("F006");
        newItem.setMaterialTypeId("MT-002");
        newItem.setMaterialName("包材A");
        newItem.setUnit("g");
        newItem.setStandardQuantity(BigDecimal.valueOf(50));

        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(newItem);
        // findByRecipeIdOrderBySortOrderAsc returns the updated list (including new item)
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of(existingItem, newItem));

        BomRecipeItemDTO dto = buildDto("MT-002", BigDecimal.valueOf(50), "g", "PACKAGING");
        service.addItem("F006", "RECIPE-ORPHAN-TEST", dto);

        // The collection instance on the entity must be the SAME object as managedList
        // (not a brand-new ArrayList). If setItems() was called with a new list, this fails.
        assertThat(recipe.getItems())
                .as("Hibernate-managed collection instance must be preserved after addItem")
                .isSameAs(managedList);

        // Content should be updated in-place
        assertThat(recipe.getItems()).hasSize(2);
        assertThat(recipe.getItems()).contains(existingItem, newItem);
    }

    // ── F6: taxRate 未传 → 保持 null (不默认 0%), 成本侧诚实标缺 ──────────────
    @Test
    @DisplayName("addItem 未传价格税率时从物料档案继承")
    void addItem_pricingNotProvided_inheritsMaterialMaster() {
        org.mockito.ArgumentCaptor<BomRecipeItem> captor =
                org.mockito.ArgumentCaptor.forClass(BomRecipeItem.class);
        when(itemRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of(existingItem));

        // buildDto 不设 taxRate → dto.getTaxRate() == null
        BomRecipeItemDTO dto = buildDto("MT-002", BigDecimal.valueOf(50), "g", "PACKAGING");
        service.addItem("F006", "RECIPE-ORPHAN-TEST", dto);

        assertThat(captor.getValue().getTaxRate()).isEqualByComparingTo("9.00");
        assertThat(captor.getValue().getUnitPrice()).isEqualByComparingTo("12.0000");
    }

    @Test
    @DisplayName("addItem 忽略页面价格税率，以物料档案为真值")
    void addItem_payloadPricingIgnored_materialMasterWins() {
        org.mockito.ArgumentCaptor<BomRecipeItem> captor =
                org.mockito.ArgumentCaptor.forClass(BomRecipeItem.class);
        when(itemRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of(existingItem));

        BomRecipeItemDTO dto = buildDto("MT-002", BigDecimal.valueOf(50), "g", "PACKAGING");
        ReflectionTestUtils.setField(dto, "taxRate", new BigDecimal("13.00"));
        ReflectionTestUtils.setField(dto, "unitPrice", new BigDecimal("999.00"));
        ReflectionTestUtils.setField(dto, "yieldRate", new BigDecimal("55.00"));
        service.addItem("F006", "RECIPE-ORPHAN-TEST", dto);

        assertThat(captor.getValue().getTaxRate()).isEqualByComparingTo("9.00");
        assertThat(captor.getValue().getUnitPrice()).isEqualByComparingTo("12.0000");
        assertThat(captor.getValue().getYieldRate()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("RAW BOM 可只建立物料关联，不填每成品用量")
    void addRawItem_standardQuantityMayBeNull() {
        when(itemRepo.save(any(BomRecipeItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of(existingItem));

        BomRecipeItemDTO dto = buildDto("MT-001", null, "kg", "RAW");
        BomRecipeItem saved = service.addItem("F006", "RECIPE-ORPHAN-TEST", dto);

        assertThat(saved.getStandardQuantity()).isNull();
        assertThat(saved.getActualQuantity()).isNull();
        assertThat(saved.getItemCost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("包材 BOM 仍必须填每成品用量")
    void addPackagingItem_standardQuantityRequired() {
        BomRecipeItemDTO dto = buildDto("MT-002", null, "g", "PACKAGING");

        assertThatThrownBy(() -> service.addItem("F006", "RECIPE-ORPHAN-TEST", dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("每成品用量");
    }

    @Test
    @DisplayName("SP8: addItem 未传 primaryCode 时从物料主数据回填")
    void addItem_primaryCodeBackfilledFromMaterial() {
        org.mockito.ArgumentCaptor<BomRecipeItem> captor =
                org.mockito.ArgumentCaptor.forClass(BomRecipeItem.class);
        when(itemRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of(existingItem));

        BomRecipeItemDTO dto = buildDto("MT-002", BigDecimal.valueOf(50), "g", "PACKAGING");
        service.addItem("F006", "RECIPE-ORPHAN-TEST", dto);

        assertThat(captor.getValue().getPrimaryCode()).isEqualTo("002");
    }

    @Test
    @DisplayName("SP8: addItem 传入 primaryCode 与物料主编码不一致 -> 400")
    void addItem_primaryCodeMismatchRejected() {
        BomRecipeItemDTO dto = buildDto("MT-002", BigDecimal.valueOf(50), "g", "PACKAGING");
        ReflectionTestUtils.setField(dto, "primaryCode", "001");

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> service.addItem("F006", "RECIPE-ORPHAN-TEST", dto));

        assertThat(ex.getCode()).isEqualTo(400);
        assertThat(ex.getMessage()).contains("MT-002").contains("001").contains("002");
        verify(itemRepo, never()).save(any(BomRecipeItem.class));
    }

    @Test
    @DisplayName("addItem: AUXILIARY item accepted — B-41 AUXILIARY category supported")
    void addItem_auxiliaryCategory_accepted() {
        BomRecipeItem auxItem = new BomRecipeItem();
        auxItem.setId(3L);
        auxItem.setRecipeId("RECIPE-ORPHAN-TEST");
        auxItem.setFactoryId("F006");
        auxItem.setMaterialTypeId("MT-001");
        auxItem.setMaterialName("辅料A");
        auxItem.setUnit("g");

        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(auxItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of(existingItem, auxItem));

        BomRecipeItemDTO dto = buildDto("MT-001", BigDecimal.valueOf(10), "g", "AUXILIARY");
        BomRecipeItem result = service.addItem("F006", "RECIPE-ORPHAN-TEST", dto);

        assertThat(result).isEqualTo(auxItem);
    }

    @Test
    @DisplayName("deleteItem: collection reference stays the same instance after soft-delete")
    void deleteItem_keepsOriginalCollectionInstance() {
        when(itemRepo.findById(1L)).thenReturn(Optional.of(existingItem));
        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(existingItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-ORPHAN-TEST"))
                .thenReturn(List.of());  // after soft-delete, returns empty

        service.deleteItem("F006", 1L);

        assertThat(recipe.getItems())
                .as("Hibernate-managed collection instance must be preserved after deleteItem")
                .isSameAs(managedList);
        assertThat(recipe.getItems()).isEmpty();
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private BomRecipeItemDTO buildDto(String materialTypeId, BigDecimal qty, String unit, String category) {
        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        ReflectionTestUtils.setField(dto, "materialTypeId", materialTypeId);
        ReflectionTestUtils.setField(dto, "standardQuantity", qty);
        ReflectionTestUtils.setField(dto, "unit", unit);
        ReflectionTestUtils.setField(dto, "materialCategory", category);
        return dto;
    }

    private RawMaterialType newMt(String id, String name, String unit, String primaryCode) {
        RawMaterialType mt = new RawMaterialType();
        mt.setId(id);
        mt.setName(name);
        mt.setUnit(unit);
        mt.setFactoryId("F006");
        mt.setPrimaryCode(primaryCode);
        mt.setTaxRate(TaxRate.TAX_9);
        mt.setUnitPrice(new BigDecimal("10.0000"));
        mt.setMovingAvgPrice(new BigDecimal("12.0000"));
        return mt;
    }
}
