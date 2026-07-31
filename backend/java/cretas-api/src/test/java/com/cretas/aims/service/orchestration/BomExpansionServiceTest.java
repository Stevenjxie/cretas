package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomExpansionServiceTest {

    @Mock MaterialBatchRepository materialBatchRepository;
    @Mock ProductionPlanRepository productionPlanRepository;
    @Mock WarehouseResolver warehouseResolver;
    @Mock BomRecipeItemRepository bomRecipeItemRepository;

    private BomExpansionService service;

    @BeforeEach
    void setUp() {
        service = new BomExpansionService(
                materialBatchRepository, productionPlanRepository, warehouseResolver, bomRecipeItemRepository);
    }

    @Test
    void expandsOnlyCurrentActiveRecipeItems() {
        BomRecipeItem item = recipeItem("MAT-PORK", "猪蹄", "200", "80", "g");
        when(bomRecipeItemRepository.findCurrentByProduct("F006", "PT-HOOF"))
                .thenReturn(List.of(item));

        List<MaterialRequirement> requirements = service.expandBOM(
                "F006", "PT-HOOF", new BigDecimal("10"));

        assertEquals(1, requirements.size());
        assertEquals("MAT-PORK", requirements.get(0).getMaterialTypeId());
        assertEquals("猪蹄", requirements.get(0).getMaterialTypeName());
        assertEquals(0, new BigDecimal("2500.000000")
                .compareTo(requirements.get(0).getRequiredQuantity()));
        assertEquals("g", requirements.get(0).getSourceUnit());
        assertEquals(0, new BigDecimal("20").compareTo(requirements.get(0).getWastageRate()));
    }

    @Test
    void usesPersistedActualQuantitySnapshotWhenPresent() {
        BomRecipeItem item = recipeItem("MAT-SALT", "盐", "5", "100", "g");
        item.setActualQuantity(new BigDecimal("6.25"));
        when(bomRecipeItemRepository.findCurrentByProduct("F006", "PT-HOOF"))
                .thenReturn(List.of(item));

        List<MaterialRequirement> requirements = service.expandBOM(
                "F006", "PT-HOOF", new BigDecimal("4"));

        assertEquals(0, new BigDecimal("25.000000")
                .compareTo(requirements.get(0).getRequiredQuantity()));
    }

    @Test
    void rejectsProductionWhenNoActiveRecipeExists() {
        when(bomRecipeItemRepository.findCurrentByProduct("F006", "PT-MISSING"))
                .thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.expandBOM("F006", "PT-MISSING", BigDecimal.ONE));

        assertEquals(409, error.getCode());
        assertEquals("BOM_ACTIVE_RECIPE_REQUIRED", error.getErrorCode());
    }

    /**
     * 🔴 副产行是**产出声明**, 不能展开成领料需求 —— 那等于让人去领一个本该由生产产出的东西。
     *
     * <p>2026-07-31 走前端验收后补: BOM 第四类「副产」上线(V20261029_37 放开了
     * chk_bri_category)后, 这里若不排除, 一条「预计产出 36kg 肥油」会变成 36kg 的领料需求。
     * 同形缺陷在 PurchaseServiceImpl.expandSoItemsInto 也有一份(变成采购需求), 已一并修。</p>
     */
    @Test
    void byproductRowIsNotExpandedIntoAMaterialRequirement() {
        BomRecipeItem input = recipeItem("MAT-PORK", "猪蹄", "200", "80", "g");
        BomRecipeItem byproduct = recipeItem("MAT-FEIYOU", "肥油", "36", "100", "kg");
        byproduct.setMaterialCategory(BomRecipeItem.CATEGORY_BYPRODUCT);
        when(bomRecipeItemRepository.findCurrentByProduct("F006", "PT-HOOF"))
                .thenReturn(List.of(input, byproduct));

        List<MaterialRequirement> requirements = service.expandBOM(
                "F006", "PT-HOOF", new BigDecimal("10"));

        // 阳性对照: 同一次展开里, 真正的投入行照常出现 —— 证明不是整条路径被堵死
        assertEquals(1, requirements.size());
        assertEquals("MAT-PORK", requirements.get(0).getMaterialTypeId());
    }

    private BomRecipeItem recipeItem(
            String materialTypeId, String materialName, String quantity, String yieldRate, String unit) {
        BomRecipeItem item = new BomRecipeItem();
        item.setFactoryId("F006");
        item.setRecipeId("RECIPE-1");
        item.setMaterialTypeId(materialTypeId);
        item.setMaterialName(materialName);
        item.setStandardQuantity(new BigDecimal(quantity));
        item.setYieldRate(new BigDecimal(yieldRate));
        item.setUnit(unit);
        item.setMaterialCategory("RAW");
        item.setSortOrder(0);
        item.setIsOptional(false);
        return item;
    }
}
