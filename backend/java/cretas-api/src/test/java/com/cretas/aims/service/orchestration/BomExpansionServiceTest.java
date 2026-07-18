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
