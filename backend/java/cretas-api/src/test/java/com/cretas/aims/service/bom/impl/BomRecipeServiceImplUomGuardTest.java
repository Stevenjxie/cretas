package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.entity.RawMaterialType;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T159-B-anchor: BomRecipeServiceImpl UoM guard on write.
 *
 * <p>Tests the hard-block (R3) at BOM write time:
 * <ul>
 *   <li>Cross-dimension unit (个 vs kg-canonical) → throws BusinessException with 防呆 message</li>
 *   <li>Same-dimension unit (g vs kg-canonical) → allowed</li>
 *   <li>Same unit (kg vs kg-canonical) → allowed</li>
 *   <li>Same count dimension (pcs vs 件-canonical) → allowed</li>
 *   <li>Abaca material → allowed (ABACA_SKIP fail-open)</li>
 *   <li>Null/blank unit in DTO → no block (backwards compat)</li>
 * </ul>
 */
@DisplayName("T159-B R3: BomRecipeServiceImpl — UoM guard at BOM write time")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BomRecipeServiceImplUomGuardTest {

    @Mock
    private BomRecipeRepository recipeRepo;
    @Mock
    private BomRecipeItemRepository itemRepo;
    @Mock
    private RawMaterialTypeRepository materialTypeRepo;
    /** Placeholder mock so @InjectMocks constructor can inject; replaced in setUp with real converter. */
    @Mock
    private MaterialUomConverter mockUomConverter;

    /** SP1: NestedBomCostService must be mocked so @InjectMocks can inject it. */
    @Mock
    private NestedBomCostService nestedBomCostService;

    private MaterialUomConverter realUomConverter;

    @InjectMocks
    private BomRecipeServiceImpl service;

    private static final String FACTORY = "F001";
    private static final String RECIPE_ID = "recipe-001";
    private static final String MAT_PORK = "mt-pork";     // canonical=kg, not abaca
    private static final String MAT_BOX = "mt-box";       // canonical=件, not abaca
    private static final String MAT_ABACA = "mt-abaca";   // abaca=true

    @BeforeEach
    void setUp() {
        // Use real UomConverter wired with real UnitConversionService
        // (no DB calls needed for write-compat check — just dimension logic)
        com.cretas.aims.repository.MaterialPackagingHierarchyRepository packagingRepo =
                mock(com.cretas.aims.repository.MaterialPackagingHierarchyRepository.class);
        when(packagingRepo.findByMaterialTypeId(anyString())).thenReturn(Optional.empty());
        realUomConverter = new MaterialUomConverter(packagingRepo, materialTypeRepo,
                com.cretas.aims.service.unit.TestUnitContractFactory.legacyFacade());
        // Replace @InjectMocks placeholder mock with real converter
        ReflectionTestUtils.setField(service, "materialUomConverter", realUomConverter);

        // BOM recipe exists in DRAFT status
        BomRecipe recipe = new BomRecipe();
        recipe.setId(RECIPE_ID);
        recipe.setFactoryId(FACTORY);
        recipe.setStatus(BomRecipe.Status.DRAFT);
        when(recipeRepo.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));

        // Default material repo: not found
        when(materialTypeRepo.findById(anyString())).thenReturn(Optional.empty());

        // Pork material: canonical=kg
        RawMaterialType pork = new RawMaterialType();
        pork.setId(MAT_PORK);
        pork.setName("冷冻猪舌");
        pork.setUnit("kg");
        pork.setFactoryId(FACTORY);
        pork.setIsAbacaPackaging(false);
        when(materialTypeRepo.findById(MAT_PORK)).thenReturn(Optional.of(pork));

        // Box material: canonical=件
        RawMaterialType box = new RawMaterialType();
        box.setId(MAT_BOX);
        box.setName("吸塑盒2014-3.5");
        box.setUnit("件");
        box.setFactoryId(FACTORY);
        box.setIsAbacaPackaging(false);
        when(materialTypeRepo.findById(MAT_BOX)).thenReturn(Optional.of(box));

        // Abaca material: canonical=箱, abaca=true
        RawMaterialType abaca = new RawMaterialType();
        abaca.setId(MAT_ABACA);
        abaca.setName("牛肉");
        abaca.setUnit("箱");
        abaca.setFactoryId(FACTORY);
        abaca.setIsAbacaPackaging(true);
        when(materialTypeRepo.findById(MAT_ABACA)).thenReturn(Optional.of(abaca));

        // itemRepo default behavior
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc(anyString())).thenReturn(Collections.emptyList());
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ============================================================
    // (c) Cross-dimension BLOCKED cases
    // ============================================================

    @Test
    @DisplayName("(c) addItem: 个 BOM unit vs kg-canonical material → BusinessException with 防呆 message")
    void addItem_crossDimension_个vsKg_blocked() {
        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, "个", new BigDecimal("100"));

        assertThatThrownBy(() -> service.addItem(FACTORY, RECIPE_ID, dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getMessage())
                            .contains("冷冻猪舌")    // real material name
                            .contains("个")          // BOM unit
                            .contains("kg")          // canonical unit
                            .contains("BOM单位");
                    assertThat(be.getCode()).isIn(409, 400);
                    // 4-位一体: must have actionHint
                    assertThat(be.getActionHint()).isNotBlank();
                });
    }

    @Test
    @DisplayName("(c) addItem: pcs BOM unit vs kg-canonical material → BlockedException")
    void addItem_crossDimension_pcsVsKg_blocked() {
        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, "pcs", new BigDecimal("100"));
        assertThatThrownBy(() -> service.addItem(FACTORY, RECIPE_ID, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("冷冻猪舌");
    }

    @Test
    @DisplayName("(c) addItem: kg BOM unit vs 件-canonical material → BlockedException")
    void addItem_crossDimension_kgVsJian_blocked() {
        BomRecipeItemDTO dto = bomItemDto(MAT_BOX, "kg", new BigDecimal("50"));
        assertThatThrownBy(() -> service.addItem(FACTORY, RECIPE_ID, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("吸塑盒");
    }

    // ============================================================
    // (a) Same-dimension ALLOWED cases
    // ============================================================

    @Test
    @DisplayName("(a) addItem: g BOM unit vs kg-canonical material → ALLOWED (g↔kg same dimension)")
    void addItem_sameDimension_gVsKg_allowed() {
        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, "g", new BigDecimal("500"));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc(RECIPE_ID)).thenReturn(Collections.emptyList());
        // Should not throw
        BomRecipeItem item = service.addItem(FACTORY, RECIPE_ID, dto);
        assertThat(item.getUnit()).isEqualTo("g");
    }

    @Test
    @DisplayName("(a) addItem: kg BOM unit vs kg-canonical material → ALLOWED (same unit)")
    void addItem_sameUnit_kgVsKg_allowed() {
        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, "kg", new BigDecimal("2"));
        BomRecipeItem item = service.addItem(FACTORY, RECIPE_ID, dto);
        assertThat(item.getUnit()).isEqualTo("kg");
    }

    // ============================================================
    // (b) Same count dimension ALLOWED
    // ============================================================

    @Test
    @DisplayName("(b) addItem: 个 BOM unit vs 件-canonical material → ALLOWED (同计数维度)")
    void addItem_countDimension_geVsJian_allowed() {
        BomRecipeItemDTO dto = bomItemDto(MAT_BOX, "个", new BigDecimal("100"));
        BomRecipeItem item = service.addItem(FACTORY, RECIPE_ID, dto);
        assertThat(item.getUnit()).isEqualTo("个");
    }

    @Test
    @DisplayName("(b) addItem: pcs BOM unit vs 件-canonical material → ALLOWED")
    void addItem_countDimension_pcsVsJian_allowed() {
        BomRecipeItemDTO dto = bomItemDto(MAT_BOX, "pcs", new BigDecimal("200"));
        BomRecipeItem item = service.addItem(FACTORY, RECIPE_ID, dto);
        assertThat(item.getUnit()).isEqualTo("pcs");
    }

    // ============================================================
    // (d) Abaca material → ALLOWED
    // ============================================================

    @Test
    @DisplayName("(d) addItem: any unit vs abaca material → ALLOWED (ABACA_SKIP fail-open)")
    void addItem_abaca_anyUnit_allowed() {
        BomRecipeItemDTO dto = bomItemDto(MAT_ABACA, "kg", new BigDecimal("100"));
        BomRecipeItem item = service.addItem(FACTORY, RECIPE_ID, dto);
        assertThat(item.getMaterialTypeId()).isEqualTo(MAT_ABACA);
    }

    // ============================================================
    // Null/blank unit → no block
    // ============================================================

    @Test
    @DisplayName("null BOM unit → no unit guard (backwards compat, unit may be set later)")
    void addItem_nullUnit_noBlock() {
        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, null, new BigDecimal("500"));
        // Should not throw
        BomRecipeItem item = service.addItem(FACTORY, RECIPE_ID, dto);
        assertThat(item).isNotNull();
    }

    @Test
    @DisplayName("blank BOM unit → no unit guard")
    void addItem_blankUnit_noBlock() {
        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, "  ", new BigDecimal("500"));
        BomRecipeItem item = service.addItem(FACTORY, RECIPE_ID, dto);
        assertThat(item).isNotNull();
    }

    // ============================================================
    // updateItem path also guarded
    // ============================================================

    @Test
    @DisplayName("updateItem: cross-dimension 个 vs kg-canonical → BlockedException")
    void updateItem_crossDimension_blocked() {
        BomRecipeItem existingItem = buildExistingItem(1L, MAT_PORK);
        when(itemRepo.findById(1L)).thenReturn(Optional.of(existingItem));

        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, "个", new BigDecimal("100"));
        assertThatThrownBy(() -> service.updateItem(FACTORY, 1L, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("冷冻猪舌");
    }

    @Test
    @DisplayName("updateItem: same dimension g vs kg-canonical → ALLOWED")
    void updateItem_sameDimension_allowed() {
        BomRecipeItem existingItem = buildExistingItem(2L, MAT_PORK);
        when(itemRepo.findById(2L)).thenReturn(Optional.of(existingItem));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BomRecipeItemDTO dto = bomItemDto(MAT_PORK, "g", new BigDecimal("500"));
        BomRecipeItem updated = service.updateItem(FACTORY, 2L, dto);
        assertThat(updated.getUnit()).isEqualTo("g");
    }

    // ============================================================
    // Helper
    // ============================================================

    private BomRecipeItemDTO bomItemDto(String materialTypeId, String unit, BigDecimal qty) {
        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        dto.setMaterialTypeId(materialTypeId);
        dto.setUnit(unit);
        dto.setStandardQuantity(qty);
        dto.setYieldRate(new BigDecimal("100.00"));
        return dto;
    }

    private BomRecipeItem buildExistingItem(Long id, String materialTypeId) {
        BomRecipeItem item = new BomRecipeItem();
        item.setId(id);
        item.setFactoryId(FACTORY);
        item.setRecipeId(RECIPE_ID);   // must match the mocked recipe in setUp
        item.setMaterialTypeId(materialTypeId);
        item.setStandardQuantity(new BigDecimal("100"));
        return item;
    }
}
