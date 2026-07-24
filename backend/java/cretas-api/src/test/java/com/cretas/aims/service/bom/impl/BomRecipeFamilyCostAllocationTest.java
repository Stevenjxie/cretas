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
        BomRecipe sharedRecipe = familyRecipe(
                "RECIPE-SHARED", "FG-A", "TERM-A", BomRecipe.OutputRole.MAIN, "75");
        BomRecipe outputRecipe = familyRecipe(
                "RECIPE-OUTPUT-B", "FG-B", "TERM-B", BomRecipe.OutputRole.CO_PRODUCT, "25");
        outputRecipe.setTotalLaborCost(new BigDecimal("40"));
        outputRecipe.setTotalOverheadCost(new BigDecimal("20"));
        BomRecipeItem sharedInput = costedItem(
                "RECIPE-SHARED", "SHARED", "RAW_MATERIAL", "10", "10");
        BomRecipeItem exclusivePackaging = costedItem(
                "RECIPE-OUTPUT-B", "OUTPUT_EXCLUSIVE", "PACKAGING", "2", "10");

        mockFamily(List.of(sharedRecipe, outputRecipe));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-SHARED"))
                .thenReturn(List.of(sharedInput));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-OUTPUT-B"))
                .thenReturn(List.of(exclusivePackaging));
        when(seasoningItemRepo.findByRecipeIdOrderBySeqAsc(anyString())).thenReturn(List.of());
        when(nestedBomCostService.isNestedComponent(any(BomRecipeItem.class))).thenReturn(false);

        ReflectionTestUtils.invokeMethod(service, "recomputeFamilyCosts", outputRecipe);

        assertThat(outputRecipe.getTotalMaterialCost()).isEqualByComparingTo("45.0000");
        assertThat(outputRecipe.getTotalCost()).isEqualByComparingTo("60.0000");
        assertThat(sharedRecipe.getTotalMaterialCost()).isEqualByComparingTo("75.0000");
    }

    @Test
    void missingSharedPriceKeepsEveryAllocatedOutputCostIncomplete() {
        BomRecipe outputRecipe = familyRecipe(
                "RECIPE-OUTPUT-A", "FG-A", "TERM-A", BomRecipe.OutputRole.MAIN, "100");
        BomRecipeItem unpricedInput = costedItem(
                "RECIPE-OUTPUT-A", "SHARED", "RAW_MATERIAL", "10", "10");
        unpricedInput.setUnitPrice(null);

        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-OUTPUT-A"))
                .thenReturn(List.of(unpricedInput));
        when(nestedBomCostService.isNestedComponent(unpricedInput)).thenReturn(false);
        mockFamily(List.of(outputRecipe));

        ReflectionTestUtils.invokeMethod(service, "recomputeFamilyCosts", outputRecipe);

        assertThat(outputRecipe.getTotalMaterialCost()).isNull();
        assertThat(outputRecipe.getTotalCost()).isNull();
    }

    @Test
    void partiallySharedPoolIsAllocatedOnlyAcrossItsActualOutputs() {
        BomRecipe first = familyRecipe(
                "RECIPE-A", "FG-A", "TERM-A", BomRecipe.OutputRole.MAIN, "50");
        BomRecipe second = familyRecipe(
                "RECIPE-B", "FG-B", "TERM-B", BomRecipe.OutputRole.CO_PRODUCT, "30");
        BomRecipe third = familyRecipe(
                "RECIPE-C", "FG-C", "TERM-C", BomRecipe.OutputRole.CO_PRODUCT, "20");
        BomRecipeItem groupInput = costedItem(
                "RECIPE-A", "OUTPUT_GROUP", "RAW_MATERIAL", "8", "10");
        groupInput.setCostScopeKey("TERM-A,TERM-B");
        BomRecipeItem thirdExclusive = costedItem(
                "RECIPE-C", "OUTPUT_EXCLUSIVE", "PACKAGING", "2", "10");
        thirdExclusive.setCostScopeKey("TERM-C");

        mockFamily(List.of(first, second, third));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-A"))
                .thenReturn(List.of(groupInput));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-B")).thenReturn(List.of());
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-C"))
                .thenReturn(List.of(thirdExclusive));
        when(seasoningItemRepo.findByRecipeIdOrderBySeqAsc(anyString())).thenReturn(List.of());
        when(nestedBomCostService.isNestedComponent(any(BomRecipeItem.class))).thenReturn(false);

        ReflectionTestUtils.invokeMethod(service, "recomputeFamilyCosts", first);

        assertThat(first.getTotalMaterialCost()).isEqualByComparingTo("50.0000");
        assertThat(second.getTotalMaterialCost()).isEqualByComparingTo("30.0000");
        assertThat(third.getTotalMaterialCost()).isEqualByComparingTo("20.0000");
    }

    @Test
    void byProductNrvCreditsOnlyItsSharedPathAndPreservesTotalInputCost() {
        BomRecipe main = familyRecipe(
                "RECIPE-A", "FG-MAIN", "TERM-MAIN", BomRecipe.OutputRole.MAIN, "100");
        BomRecipe byProduct = familyRecipe(
                "RECIPE-B", "FG-BY", "TERM-BY", BomRecipe.OutputRole.BY_PRODUCT, "0");
        byProduct.setByproductNrvUnitPrice(new BigDecimal("10"));
        byProduct.setOutputQuantityPerUnit(new BigDecimal("2"));
        BomRecipeItem shared = costedItem(
                "RECIPE-A", "SHARED", "RAW_MATERIAL", "10", "10");
        shared.setCostScopeKey("TERM-BY,TERM-MAIN");
        BomRecipeItem byProductDirect = costedItem(
                "RECIPE-B", "OUTPUT_EXCLUSIVE", "PACKAGING", "1", "5");
        byProductDirect.setCostScopeKey("TERM-BY");

        mockFamily(List.of(main, byProduct));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-A")).thenReturn(List.of(shared));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-B"))
                .thenReturn(List.of(byProductDirect));
        when(seasoningItemRepo.findByRecipeIdOrderBySeqAsc(anyString())).thenReturn(List.of());
        when(nestedBomCostService.isNestedComponent(any(BomRecipeItem.class))).thenReturn(false);

        ReflectionTestUtils.invokeMethod(service, "recomputeFamilyCosts", main);

        assertThat(main.getTotalMaterialCost()).isEqualByComparingTo("85.0000");
        assertThat(byProduct.getTotalMaterialCost()).isEqualByComparingTo("20.0000");
        assertThat(main.getTotalMaterialCost().add(byProduct.getTotalMaterialCost()))
                .isEqualByComparingTo("105.0000");
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
    void activationRejectsByProductWithoutNrv() {
        BomRecipe main = outputContract(
                "RECIPE-A", "FG-MAIN", "TERM-MAIN", BomRecipe.OutputRole.MAIN, "100");
        BomRecipe byProduct = outputContract(
                "RECIPE-B", "FG-BY", "TERM-BY", BomRecipe.OutputRole.BY_PRODUCT, "0");
        byProduct.setOutputQuantityPerUnit(BigDecimal.ONE);

        BusinessException error = assertThrows(BusinessException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        service, "validateByProductCreditRules", List.of(main, byProduct)));

        assertThat(error.getErrorCode()).isEqualTo("BOM_BY_PRODUCT_NRV_REQUIRED");
    }

    @Test
    void excessiveByProductCreditCannotCreateNegativeSharedCost() {
        BomRecipe main = familyRecipe(
                "RECIPE-A", "FG-MAIN", "TERM-MAIN", BomRecipe.OutputRole.MAIN, "100");
        BomRecipe byProduct = familyRecipe(
                "RECIPE-B", "FG-BY", "TERM-BY", BomRecipe.OutputRole.BY_PRODUCT, "0");
        byProduct.setByproductNrvUnitPrice(new BigDecimal("20"));
        byProduct.setOutputQuantityPerUnit(BigDecimal.ONE);
        BomRecipeItem shared = costedItem(
                "RECIPE-A", "SHARED", "RAW_MATERIAL", "1", "10");
        shared.setCostScopeKey("TERM-BY,TERM-MAIN");

        mockFamily(List.of(main, byProduct));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-A")).thenReturn(List.of(shared));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("RECIPE-B")).thenReturn(List.of());
        when(seasoningItemRepo.findByRecipeIdOrderBySeqAsc(anyString())).thenReturn(List.of());
        when(nestedBomCostService.isNestedComponent(shared)).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "recomputeFamilyCosts", main));

        assertThat(error.getErrorCode())
                .isEqualTo("BOM_BY_PRODUCT_CREDIT_EXCEEDS_SHARED_COST");
    }

    private BomRecipe recipe(String id, String sharedRecipeId, String allocationRatio) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(id);
        recipe.setFactoryId("F006");
        recipe.setSharedRecipeId(sharedRecipeId);
        recipe.setCostAllocationRatio(new BigDecimal(allocationRatio));
        return recipe;
    }

    private BomRecipe familyRecipe(
            String id,
            String productTypeId,
            String terminalNodeId,
            BomRecipe.OutputRole role,
            String allocationRatio) {
        BomRecipe recipe = outputContract(
                id, productTypeId, terminalNodeId, role, allocationRatio);
        recipe.setBomFamilyId("FAMILY-1");
        recipe.setStatus(BomRecipe.Status.DRAFT);
        recipe.setSharedRecipeId("RECIPE-A");
        recipe.setOutputQuantityPerUnit(BigDecimal.ONE);
        recipe.setOutputUnit("kg");
        return recipe;
    }

    private void mockFamily(List<BomRecipe> family) {
        when(recipeRepo.findByFactoryIdAndBomFamilyIdOrderByProductTypeIdAscVersionDesc(
                "F006", "FAMILY-1")).thenReturn(family);
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
