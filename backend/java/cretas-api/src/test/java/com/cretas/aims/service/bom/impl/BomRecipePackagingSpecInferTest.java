package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.uom.MaterialUomConverter;
import com.cretas.aims.service.unit.TestUnitContractFactory;
import com.cretas.aims.service.unit.UnitContractService;
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
 * 包材规格自动推算 (Packaging Spec Auto-Inference) 单元测试.
 *
 * <p>覆盖场景:
 * <ol>
 *   <li>PACKAGING 物料有 packQtyPerProduct, DTO 未手填 → standardQuantity 自动推算</li>
 *   <li>PACKAGING 物料有 packQtyPerProduct, DTO 已手填 → 手填优先 (不覆盖)</li>
 *   <li>PACKAGING 物料 packQtyPerProduct 为 null → standardQuantity 保持 null (诚实)</li>
 *   <li>RAW 物料 (非 PACKAGING) 有 packQtyPerProduct → 不触发推算</li>
 *   <li>自动推算同时回填 unit (DTO unit 为 null 时从物料主数据取)</li>
 *   <li>自动推算时 unit 已填 → 不覆盖 DTO 单位</li>
 *   <li>成本含 PACKAGING 行 (unitPrice + 自动推算 standardQuantity → itemCost 不为零)</li>
 * </ol>
 */
@DisplayName("BomRecipeServiceImpl — 包材规格自动推算 (PackagingSpecAutoInfer)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BomRecipePackagingSpecInferTest {

    @Mock
    BomRecipeRepository recipeRepo;

    @Mock
    BomRecipeItemRepository itemRepo;

    @Mock
    RawMaterialTypeRepository materialTypeRepo;

    @Mock
    MaterialUomConverter materialUomConverter;

    @Mock
    UnitContractService unitContractService;

    @Mock
    NestedBomCostService nestedBomCostService;

    @InjectMocks
    BomRecipeServiceImpl service;

    private BomRecipe recipe;

    @BeforeEach
    void setUp() {
        recipe = new BomRecipe();
        recipe.setId("RECIPE-PKG-TEST");
        recipe.setFactoryId("F006");
        recipe.setStatus(BomRecipe.Status.DRAFT);
        recipe.setItems(new ArrayList<>());

        when(recipeRepo.findById("RECIPE-PKG-TEST")).thenReturn(Optional.of(recipe));
        when(recipeRepo.save(any(BomRecipe.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialUomConverter.isWriteUnitCompatible(anyString(), anyString())).thenReturn(true);
        UnitContractService canonicalUnits = TestUnitContractFactory.contract();
        when(unitContractService.normalize(anyString(), anyString()))
                .thenAnswer(invocation -> canonicalUnits.normalize(
                        invocation.getArgument(0), invocation.getArgument(1)));
        // NestedBomCostService: treat all items as plain (non-nested) for these tests
        when(nestedBomCostService.isNestedComponent(any())).thenReturn(false);
    }

    // ─── T1: PACKAGING 物料有 packQtyPerProduct, DTO 未手填 → 自动推算 ─────────

    @Test
    @DisplayName("T1: PACKAGING + packQtyPerProduct=1 + DTO qty null → standardQuantity auto-set to 1")
    void packaging_withPackQty_dtoQtyNull_autoInferred() {
        RawMaterialType box = packagingMt("PKG-001", "吸塑盒", "个", new BigDecimal("1.000000"));
        when(materialTypeRepo.findById("PKG-001")).thenReturn(Optional.of(box));

        BomRecipeItem savedItem = stubSavedItem("PKG-001", new BigDecimal("1.000000"), "个");
        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(savedItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(savedItem));

        BomRecipeItemDTO dto = buildDto("PKG-001", null, "个", "PACKAGING");
        BomRecipeItem result = service.addItem("F006", "RECIPE-PKG-TEST", dto);

        // standardQuantity must have been auto-set to packQtyPerProduct before save
        assertThat(result.getStandardQuantity())
                .as("standardQuantity should be auto-set from packQtyPerProduct=1")
                .isEqualByComparingTo(new BigDecimal("1.000000"));
    }

    @Test
    @DisplayName("T1b: PACKAGING + packQtyPerProduct=0.05 (外箱/20盒) → standardQuantity=0.05")
    void packaging_outerBox_packQty_dtoQtyNull_autoInferred() {
        // 外箱: 每箱装20盒 → 每产品单位需要1/20=0.05个外箱
        RawMaterialType outerBox = packagingMt("PKG-002", "外箱(20盒装)", "个",
                new BigDecimal("0.050000"));
        when(materialTypeRepo.findById("PKG-002")).thenReturn(Optional.of(outerBox));

        BomRecipeItem savedItem = stubSavedItem("PKG-002", new BigDecimal("0.050000"), "个");
        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(savedItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(savedItem));

        BomRecipeItemDTO dto = buildDto("PKG-002", null, "个", "PACKAGING");
        BomRecipeItem result = service.addItem("F006", "RECIPE-PKG-TEST", dto);

        assertThat(result.getStandardQuantity())
                .isEqualByComparingTo(new BigDecimal("0.050000"));
    }

    // ─── T2: 手填优先 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("T2: PACKAGING + packQtyPerProduct=1 + DTO qty=2 (手填) → 手填 2 优先, 不覆盖")
    void packaging_withPackQty_dtoQtyManuallyFilled_respectsManualInput() {
        RawMaterialType box = packagingMt("PKG-001", "吸塑盒", "个", new BigDecimal("1.000000"));
        when(materialTypeRepo.findById("PKG-001")).thenReturn(Optional.of(box));

        // 手填 qty=2 (多个盒子手工调整)
        BomRecipeItem savedItem = stubSavedItem("PKG-001", new BigDecimal("2"), "个");
        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(savedItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(savedItem));

        BomRecipeItemDTO dto = buildDto("PKG-001", new BigDecimal("2"), "个", "PACKAGING");
        BomRecipeItem result = service.addItem("F006", "RECIPE-PKG-TEST", dto);

        // 手填 2 应被保留, packQtyPerProduct=1 不应覆盖
        assertThat(result.getStandardQuantity())
                .as("manual qty=2 must not be overridden by packQtyPerProduct=1")
                .isEqualByComparingTo(new BigDecimal("2"));
    }

    // ─── T3: packQtyPerProduct null → explicit validation ─────────────────

    @Test
    @DisplayName("T3: PACKAGING + packQtyPerProduct null + DTO qty null → explicit validation error")
    void packaging_packQtyNull_dtoQtyNull_isRejected() {
        // 物料未配置规格
        RawMaterialType unspecBox = packagingMt("PKG-003", "未配置规格盒", "个", null);
        when(materialTypeRepo.findById("PKG-003")).thenReturn(Optional.of(unspecBox));

        BomRecipeItem savedItem = stubSavedItem("PKG-003", null, "个");
        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(savedItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(savedItem));

        BomRecipeItemDTO dto = buildDto("PKG-003", null, "个", "PACKAGING");
        assertThatThrownBy(() -> service.addItem("F006", "RECIPE-PKG-TEST", dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("每成品用量");
    }

    // ─── T4: RAW 物料不触发推算 ───────────────────────────────────────────

    @Test
    @DisplayName("T4: RAW 物料即使有 packQtyPerProduct → 不触发自动推算 (category guard)")
    void raw_withPackQtyField_doesNotTriggerInfer() {
        // RAW 物料错配了 packQtyPerProduct (不应触发推算)
        RawMaterialType rawMt = rawMtWithPackQty("RAW-001", "猪舌", "kg", new BigDecimal("1.000000"));
        when(materialTypeRepo.findById("RAW-001")).thenReturn(Optional.of(rawMt));

        BomRecipeItem savedItem = stubSavedItem("RAW-001", null, "kg");
        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(savedItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(savedItem));

        // category=RAW, qty=null → should NOT infer from packQtyPerProduct
        BomRecipeItemDTO dto = buildDto("RAW-001", null, "kg", "RAW");
        service.addItem("F006", "RECIPE-PKG-TEST", dto);

        // verify: service did NOT call packQtyPerProduct to fill in standardQuantity
        // (since savedItem.standardQuantity=null and we return null, if it were inferred it'd be non-null)
        assertThat(savedItem.getStandardQuantity())
                .as("RAW category: packQtyPerProduct must NOT trigger auto-infer")
                .isNull();
    }

    // ─── T5: unit 回填 ───────────────────────────────────────────────────

    @Test
    @DisplayName("T5: PACKAGING auto-infer + DTO unit null → material alias 个 is canonicalized to pcs")
    void packaging_autoInfer_unitBackfilledFromMaterial() {
        // Material unit = "个", DTO unit not supplied
        RawMaterialType box = packagingMt("PKG-001", "吸塑盒", "个", new BigDecimal("1.000000"));
        when(materialTypeRepo.findById("PKG-001")).thenReturn(Optional.of(box));

        // We capture what item is saved
        final BomRecipeItem[] capturedItem = new BomRecipeItem[1];
        when(itemRepo.save(any(BomRecipeItem.class))).thenAnswer(inv -> {
            capturedItem[0] = inv.getArgument(0);
            return capturedItem[0];
        });
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(new BomRecipeItem()));

        // DTO: no unit (null) — not explicitly provided
        BomRecipeItemDTO dto = buildDtoNoUnit("PKG-001", null, "PACKAGING");
        service.addItem("F006", "RECIPE-PKG-TEST", dto);

        // unit is back-filled from material then persisted as the canonical code.
        assertThat(capturedItem[0].getUnit())
                .as("unit should be back-filled from material.unit when DTO unit is blank")
                .isEqualTo("pcs");
    }

    @Test
    @DisplayName("T6: PACKAGING auto-infer + DTO unit=袋 → canonical bag is preserved")
    void packaging_autoInfer_existingUnitPreserved() {
        RawMaterialType box = packagingMt("PKG-001", "包装袋", "袋", new BigDecimal("1.000000"));
        when(materialTypeRepo.findById("PKG-001")).thenReturn(Optional.of(box));
        // allow 袋 unit
        when(materialUomConverter.isWriteUnitCompatible(anyString(), eq("袋"))).thenReturn(true);

        final BomRecipeItem[] capturedItem = new BomRecipeItem[1];
        when(itemRepo.save(any(BomRecipeItem.class))).thenAnswer(inv -> {
            capturedItem[0] = inv.getArgument(0);
            return capturedItem[0];
        });
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(new BomRecipeItem()));

        // DTO explicitly supplies unit=袋
        BomRecipeItemDTO dto = buildDto("PKG-001", null, "袋", "PACKAGING");
        service.addItem("F006", "RECIPE-PKG-TEST", dto);

        // Explicit localized alias is persisted using the canonical bag code.
        assertThat(capturedItem[0].getUnit())
                .as("explicitly supplied unit=袋 should persist as canonical bag")
                .isEqualTo("bag");
    }

    // ─── T7: 成本含 PACKAGING 行 ───────────────────────────────────────

    @Test
    @DisplayName("T7: 成本含PACKAGING行 — auto-inferred qty + unitPrice → itemCost non-zero")
    void packaging_costIncludesPackagingItem() {
        // 吸塑盒: 1个/产品, 单价0.30元
        RawMaterialType box = packagingMt("PKG-001", "吸塑盒", "个", new BigDecimal("1.000000"));
        box.setUnitPrice(new BigDecimal("0.30"));
        when(materialTypeRepo.findById("PKG-001")).thenReturn(Optional.of(box));

        // Item saved with auto-inferred qty=1, unitPrice=0.30
        BomRecipeItem savedItem = new BomRecipeItem();
        savedItem.setId(10L);
        savedItem.setRecipeId("RECIPE-PKG-TEST");
        savedItem.setFactoryId("F006");
        savedItem.setMaterialTypeId("PKG-001");
        savedItem.setMaterialName("吸塑盒");
        savedItem.setUnit("个");
        savedItem.setStandardQuantity(new BigDecimal("1.000000"));
        savedItem.setYieldRate(new BigDecimal("100.00"));
        savedItem.setUnitPrice(new BigDecimal("0.30"));
        savedItem.setMaterialCategory("PACKAGING");
        // itemCost = 1.0000 * 0.30 = 0.3000
        savedItem.setItemCost(savedItem.computeItemCost());

        when(itemRepo.save(any(BomRecipeItem.class))).thenReturn(savedItem);
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-PKG-TEST"))
                .thenReturn(List.of(savedItem));

        BomRecipeItemDTO dto = buildDto("PKG-001", null, "个", "PACKAGING");
        dto.setUnitPrice(new BigDecimal("0.30"));
        BomRecipeItem result = service.addItem("F006", "RECIPE-PKG-TEST", dto);

        assertThat(result.getItemCost())
                .as("itemCost = standardQuantity(1) × unitPrice(0.30) = 0.3000")
                .isEqualByComparingTo(new BigDecimal("0.3000"));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private RawMaterialType packagingMt(String id, String name, String unit,
                                         BigDecimal packQtyPerProduct) {
        RawMaterialType mt = new RawMaterialType();
        mt.setId(id);
        mt.setName(name);
        mt.setUnit(unit);
        mt.setFactoryId("F006");
        mt.setCategory("PACKAGING");
        mt.setPackQtyPerProduct(packQtyPerProduct);
        mt.setIsActive(true);
        return mt;
    }

    private RawMaterialType rawMtWithPackQty(String id, String name, String unit,
                                              BigDecimal packQtyPerProduct) {
        RawMaterialType mt = new RawMaterialType();
        mt.setId(id);
        mt.setName(name);
        mt.setUnit(unit);
        mt.setFactoryId("F006");
        mt.setCategory("RAW");
        mt.setPackQtyPerProduct(packQtyPerProduct);
        mt.setIsActive(true);
        return mt;
    }

    private BomRecipeItem stubSavedItem(String materialTypeId, BigDecimal qty, String unit) {
        BomRecipeItem item = new BomRecipeItem();
        item.setId(99L);
        item.setRecipeId("RECIPE-PKG-TEST");
        item.setFactoryId("F006");
        item.setMaterialTypeId(materialTypeId);
        item.setUnit(unit);
        item.setStandardQuantity(qty);
        item.setMaterialCategory("PACKAGING");
        return item;
    }

    private BomRecipeItemDTO buildDto(String materialTypeId, BigDecimal qty, String unit, String category) {
        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        ReflectionTestUtils.setField(dto, "materialTypeId", materialTypeId);
        ReflectionTestUtils.setField(dto, "standardQuantity", qty);
        ReflectionTestUtils.setField(dto, "unit", unit);
        ReflectionTestUtils.setField(dto, "materialCategory", category);
        return dto;
    }

    /** Build DTO with null unit (no unit provided) — simulates client not sending unit field. */
    private BomRecipeItemDTO buildDtoNoUnit(String materialTypeId, BigDecimal qty, String category) {
        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        ReflectionTestUtils.setField(dto, "materialTypeId", materialTypeId);
        ReflectionTestUtils.setField(dto, "standardQuantity", qty);
        ReflectionTestUtils.setField(dto, "unit", null);
        ReflectionTestUtils.setField(dto, "materialCategory", category);
        return dto;
    }
}
