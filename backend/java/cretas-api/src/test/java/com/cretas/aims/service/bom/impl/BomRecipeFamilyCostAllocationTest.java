package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomProcessInjectionConfigRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.product.ProductPackagingSpecRepository;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.uom.MaterialUomConverter;
import com.cretas.aims.service.validation.ProductConfigurationReadinessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomRecipeFamilyCostAllocationTest {

    @Mock private BomRecipeRepository recipeRepo;
    @Mock private BomRecipeItemRepository itemRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private RawMaterialTypeRepository materialTypeRepo;
    @Mock private MaterialUomConverter materialUomConverter;
    @Mock private UnitContractService unitContractService;
    @Mock private ProductPackagingSpecRepository packagingSpecRepository;
    @Mock private ProductConfigurationReadinessService readinessService;
    @Mock private BomWorkflowRevisionService bomWorkflowRevisionService;
    @Mock private BomItemSubstituteService substituteService;
    @Mock private NestedBomCostService nestedBomCostService;
    @Mock private BomSeasoningItemRepository seasoningItemRepo;
    @Mock private BomProcessInjectionConfigRepository processInjectionConfigRepo;

    @InjectMocks
    private BomRecipeServiceImpl service;

    @Test
    void sharedInputsAreAllocatedWhileTargetPackagingRemainsExclusive() {
        BomRecipe sharedRecipe = recipe("RECIPE-SHARED", null, "100");
        BomRecipe outputRecipe = recipe("RECIPE-OUTPUT-B", "RECIPE-SHARED", "25");
        outputRecipe.setTotalLaborCost(new BigDecimal("40"));
        outputRecipe.setTotalOverheadCost(new BigDecimal("20"));
        BomRecipeItem sharedInput = costedItem(
                "RECIPE-SHARED", "SHARED", "RAW_MATERIAL", "10", "10");
        BomRecipeItem exclusivePackaging = costedItem(
                "RECIPE-OUTPUT-B", "OUTPUT_EXCLUSIVE", "PACKAGING", "2", "10");

        when(recipeRepo.findById("RECIPE-SHARED")).thenReturn(Optional.of(sharedRecipe));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-SHARED"))
                .thenReturn(List.of(sharedInput));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-OUTPUT-B"))
                .thenReturn(List.of(exclusivePackaging));
        when(seasoningItemRepo.findByRecipeIdOrderBySeqAsc(anyString())).thenReturn(List.of());
        when(nestedBomCostService.isNestedComponent(any(BomRecipeItem.class))).thenReturn(false);

        ReflectionTestUtils.invokeMethod(service, "recomputeMaterialCost", outputRecipe);

        assertThat(outputRecipe.getTotalMaterialCost()).isEqualByComparingTo("45.0000");
        assertThat(outputRecipe.getTotalCost()).isEqualByComparingTo("60.0000");
    }

    @Test
    void missingSharedPriceKeepsEveryAllocatedOutputCostIncomplete() {
        BomRecipe outputRecipe = recipe("RECIPE-OUTPUT-A", null, "100");
        BomRecipeItem unpricedInput = costedItem(
                "RECIPE-OUTPUT-A", "SHARED", "RAW_MATERIAL", "10", "10");
        unpricedInput.setUnitPrice(null);

        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-OUTPUT-A"))
                .thenReturn(List.of(unpricedInput));
        when(seasoningItemRepo.findByRecipeIdOrderBySeqAsc("RECIPE-OUTPUT-A"))
                .thenReturn(List.of());
        when(nestedBomCostService.isNestedComponent(unpricedInput)).thenReturn(false);

        ReflectionTestUtils.invokeMethod(service, "recomputeMaterialCost", outputRecipe);

        assertThat(outputRecipe.getTotalMaterialCost()).isNull();
        assertThat(outputRecipe.getTotalCost()).isNull();
    }

    @Test
    void activationRejectsFamilyWithoutMainOutput() {
        BomRecipe first = outputContract("RECIPE-A", "FG-A", "TERM-A", BomRecipe.OutputRole.CO_PRODUCT, "50");
        BomRecipe second = outputContract("RECIPE-B", "FG-B", "TERM-B", BomRecipe.OutputRole.CO_PRODUCT, "50");
        when(bomWorkflowRevisionService.resolvePinnedTerminalOutputs("F006", first))
                .thenReturn(List.of(
                        terminal("TERM-A", "FG-A", BomRecipe.OutputRole.CO_PRODUCT, "50"),
                        terminal("TERM-B", "FG-B", BomRecipe.OutputRole.CO_PRODUCT, "50")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        service, "validateFamilyContracts", "F006", List.of(first, second)));

        assertThat(error.getErrorCode()).isEqualTo("BOM_FAMILY_ALLOCATION_INVALID");
    }

    @Test
    void activationRejectsFamilyWithMultipleMainOutputs() {
        BomRecipe first = outputContract("RECIPE-A", "FG-A", "TERM-A", BomRecipe.OutputRole.MAIN, "50");
        BomRecipe second = outputContract("RECIPE-B", "FG-B", "TERM-B", BomRecipe.OutputRole.MAIN, "50");
        when(bomWorkflowRevisionService.resolvePinnedTerminalOutputs("F006", first))
                .thenReturn(List.of(
                        terminal("TERM-A", "FG-A", BomRecipe.OutputRole.MAIN, "50"),
                        terminal("TERM-B", "FG-B", BomRecipe.OutputRole.MAIN, "50")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        service, "validateFamilyContracts", "F006", List.of(first, second)));

        assertThat(error.getErrorCode()).isEqualTo("BOM_FAMILY_ALLOCATION_INVALID");
    }

    @Test
    void activationRejectsFamilyWhoseAllocationDoesNotTotalOneHundredPercent() {
        BomRecipe first = outputContract("RECIPE-A", "FG-A", "TERM-A", BomRecipe.OutputRole.MAIN, "60");
        BomRecipe second = outputContract("RECIPE-B", "FG-B", "TERM-B", BomRecipe.OutputRole.CO_PRODUCT, "30");
        when(bomWorkflowRevisionService.resolvePinnedTerminalOutputs("F006", first))
                .thenReturn(List.of(
                        terminal("TERM-A", "FG-A", BomRecipe.OutputRole.MAIN, "60"),
                        terminal("TERM-B", "FG-B", BomRecipe.OutputRole.CO_PRODUCT, "30")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        service, "validateFamilyContracts", "F006", List.of(first, second)));

        assertThat(error.getErrorCode()).isEqualTo("BOM_FAMILY_ALLOCATION_INVALID");
    }

    @Test
    void activationRejectsByProductUntilCreditAccountingIsExplicitlySupported() {
        BomRecipe main = outputContract(
                "RECIPE-A", "FG-MAIN", "TERM-MAIN", BomRecipe.OutputRole.MAIN, "70");
        BomRecipe byProduct = outputContract(
                "RECIPE-B", "FG-BY", "TERM-BY", BomRecipe.OutputRole.BY_PRODUCT, "30");

        BusinessException error = assertThrows(BusinessException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        service, "validateByProductActivationSupported", List.of(main, byProduct)));

        assertThat(error.getErrorCode()).isEqualTo("BOM_BY_PRODUCT_CREDIT_UNSUPPORTED");
    }

    private BomRecipe recipe(String id, String sharedRecipeId, String allocationRatio) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(id);
        recipe.setFactoryId("F006");
        recipe.setSharedRecipeId(sharedRecipeId);
        recipe.setCostAllocationRatio(new BigDecimal(allocationRatio));
        return recipe;
    }

    private BomRecipe outputContract(
            String id,
            String productTypeId,
            String terminalNodeId,
            BomRecipe.OutputRole role,
            String allocationRatio) {
        BomRecipe recipe = recipe(id, "RECIPE-A", allocationRatio);
        recipe.setProductTypeId(productTypeId);
        recipe.setWorkflowRevisionId(71L);
        recipe.setWorkflowRevisionHash("revision-hash");
        recipe.setTargetTerminalNodeId(terminalNodeId);
        recipe.setOutputRole(role);
        return recipe;
    }

    private BomWorkflowRevisionService.TerminalOutput terminal(
            String terminalNodeId,
            String productTypeId,
            BomRecipe.OutputRole role,
            String allocationRatio) {
        return new BomWorkflowRevisionService.TerminalOutput(
                terminalNodeId,
                productTypeId,
                "split",
                "out-" + terminalNodeId,
                role,
                new BigDecimal(allocationRatio),
                "kg");
    }

    private BomRecipeItem costedItem(
            String recipeId,
            String costScope,
            String category,
            String quantity,
            String unitPrice) {
        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId(recipeId);
        item.setFactoryId("F006");
        item.setCostScope(costScope);
        item.setMaterialCategory(category);
        item.setStandardQuantity(new BigDecimal(quantity));
        item.setUnitPrice(new BigDecimal(unitPrice));
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        return item;
    }
}
