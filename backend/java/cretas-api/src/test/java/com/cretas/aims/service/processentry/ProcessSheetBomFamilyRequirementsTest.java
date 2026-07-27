package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessSheetBomFamilyRequirementsTest {

    private final ProductionPlanRepository planRepository = mock(ProductionPlanRepository.class);
    private final BomRecipeRepository recipeRepository = mock(BomRecipeRepository.class);
    private final BomRecipeItemRepository itemRepository = mock(BomRecipeItemRepository.class);
    private final BomSeasoningItemRepository seasoningRepository =
            mock(BomSeasoningItemRepository.class);
    private final WorkProcessRepository processRepository = mock(WorkProcessRepository.class);
    private final ProductWorkProcessRepository productWorkProcessRepository =
            mock(ProductWorkProcessRepository.class);
    private final ProcessSheetServiceImpl service = service();

    private ProcessSheetServiceImpl service() {
        ProcessSheetServiceImpl result = new ProcessSheetServiceImpl(
                null, null, null, null, null, null, planRepository, null,
                null, null, null, processRepository, productWorkProcessRepository,
                null, null, null, null);
        ReflectionTestUtils.setField(result, "bomRecipeRepository", recipeRepository);
        ReflectionTestUtils.setField(result, "bomRecipeItemRepository", itemRepository);
        ReflectionTestUtils.setField(result, "bomSeasoningItemRepository", seasoningRepository);
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<ProductionStockAllocationService.AutomaticRequirement> requirements(
            ProcessSheetRowRequest request) throws Throwable {
        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "buildAutomaticBomRequirements",
                String.class, String.class, ProcessSheetRowRequest.class, boolean.class);
        method.setAccessible(true);
        try {
            return (List<ProductionStockAllocationService.AutomaticRequirement>) method.invoke(
                    service, "F006", "PLAN-1", request, true);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    @Test
    void eachReportedOutputUsesItsOwnPinnedFamilyBomPackaging() throws Throwable {
        BomRecipe recipeA = recipe("BOM-A", "SKU-A", "袋");
        BomRecipe recipeB = recipe("BOM-B", "SKU-B", "盒");
        stubPinnedFamily(recipeA, recipeB);
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc("BOM-A"))
                .thenReturn(List.of(packaging("PACK-A", "内袋", "袋", "1")));
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc("BOM-B"))
                .thenReturn(List.of(packaging("PACK-B", "包装盒", "盒", "2")));

        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setOutputs(List.of(
                output("SKU-A", "袋", "2"),
                output("SKU-B", "盒", "3")));

        List<ProductionStockAllocationService.AutomaticRequirement> result =
                requirements(request);

        assertThat(result).extracting(
                        ProductionStockAllocationService.AutomaticRequirement::materialTypeId,
                        ProductionStockAllocationService.AutomaticRequirement::quantity,
                        ProductionStockAllocationService.AutomaticRequirement::unit)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PACK-A", new BigDecimal("2"), "袋"),
                        org.assertj.core.groups.Tuple.tuple("PACK-B", new BigDecimal("6"), "盒"));
    }

    @Test
    void reportingOnlyTheSiblingOutputStillUsesThatSiblingBom() throws Throwable {
        BomRecipe recipeA = recipe("BOM-A", "SKU-A", "袋");
        BomRecipe recipeB = recipe("BOM-B", "SKU-B", "盒");
        stubPinnedFamily(recipeA, recipeB);
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc("BOM-B"))
                .thenReturn(List.of(packaging("PACK-B", "包装盒", "盒", "2")));

        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setOutputs(List.of(output("SKU-B", "盒", "4")));

        List<ProductionStockAllocationService.AutomaticRequirement> result =
                requirements(request);

        assertThat(result).singleElement().satisfies(requirement -> {
            assertEquals("PACK-B", requirement.materialTypeId());
            assertThat(requirement.quantity()).isEqualByComparingTo("8");
        });
    }

    @Test
    void missingOutputBomFailsClosedBeforeInventoryAllocation() {
        BomRecipe recipeA = recipe("BOM-A", "SKU-A", "袋");
        stubPinnedFamily(recipeA);
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setOutputs(List.of(output("SKU-B", "盒", "1")));

        BusinessException error = assertThrows(
                BusinessException.class, () -> requirements(request));

        assertEquals("PINNED_BOM_OUTPUT_RECIPE_MISSING", error.getErrorCode());
    }

    @Test
    void sharedSeasoningIsCountedOnceAndSiblingExclusiveSeasoningFollowsReportedOutput()
            throws Throwable {
        BomRecipe recipeA = recipe("BOM-A", "SKU-A", "袋");
        BomRecipe recipeB = recipe("BOM-B", "SKU-B", "箱");
        stubPinnedFamily(recipeA, recipeB);
        stubLegacyProcess("SKU-B");

        BomSeasoningItem shared = seasoning(
                101L, "BOM-A", "SALT", "食盐", "10", "SHARED");
        BomSeasoningItem siblingExclusive = seasoning(
                102L, "BOM-B", "SPICE", "香辛料", "20", "OUTPUT_EXCLUSIVE");
        when(seasoningRepository.findByRecipeIdAndWorkProcessIdOrderBySeqAsc(
                "BOM-A", "WP-1")).thenReturn(List.of(shared));
        when(seasoningRepository.findByRecipeIdAndWorkProcessIdOrderBySeqAsc(
                "BOM-B", "WP-1")).thenReturn(List.of(shared, siblingExclusive));

        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setProductTypeId("SKU-B");
        request.setProcessOrder(1);
        request.setInputQuantity(new BigDecimal("10"));
        request.setInputUnit("kg");
        request.setOutputs(List.of(output("SKU-B", "箱", "1")));

        List<ProductionStockAllocationService.AutomaticRequirement> result =
                requirements(request);

        assertThat(result).filteredOn(requirement ->
                        "SEASONING".equals(requirement.sourceType()))
                .extracting(
                        ProductionStockAllocationService.AutomaticRequirement::materialTypeId,
                        ProductionStockAllocationService.AutomaticRequirement::quantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SALT", new BigDecimal("0.1")),
                        org.assertj.core.groups.Tuple.tuple("SPICE", new BigDecimal("0.2")));
    }

    private void stubPinnedFamily(BomRecipe... recipes) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-1");
        plan.setFactoryId("F006");
        plan.setSelectedBomRecipeId("BOM-A");
        when(planRepository.findByIdAndFactoryId("PLAN-1", "F006"))
                .thenReturn(Optional.of(plan));
        when(recipeRepository.findById("BOM-A"))
                .thenReturn(Optional.of(recipes[0]));
        when(recipeRepository.findByFactoryIdAndBomFamilyIdOrderByProductTypeIdAscVersionDesc(
                "F006", "FAMILY-1")).thenReturn(List.of(recipes));
    }

    private BomRecipe recipe(String id, String productTypeId, String unit) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(id);
        recipe.setFactoryId("F006");
        recipe.setBomFamilyId("FAMILY-1");
        recipe.setWorkflowRevisionId(9L);
        recipe.setProductTypeId(productTypeId);
        recipe.setOutputUnit(unit);
        recipe.setOutputQuantityPerUnit(BigDecimal.ONE);
        return recipe;
    }

    private BomRecipeItem packaging(
            String materialTypeId, String name, String unit, String quantity) {
        BomRecipeItem item = new BomRecipeItem();
        item.setMaterialTypeId(materialTypeId);
        item.setMaterialName(name);
        item.setMaterialCategory("PACKAGING");
        item.setUnit(unit);
        item.setStandardQuantity(new BigDecimal(quantity));
        item.setIsOptional(false);
        return item;
    }

    private void stubLegacyProcess(String productTypeId) {
        ProductWorkProcess binding = new ProductWorkProcess();
        binding.setFactoryId("F006");
        binding.setProductTypeId(productTypeId);
        binding.setWorkProcessId("WP-1");
        binding.setProcessOrder(1);
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc("F006", productTypeId))
                .thenReturn(List.of(binding));
        WorkProcess process = new WorkProcess();
        process.setId("WP-1");
        process.setFactoryId("F006");
        process.setProcessName("包装");
        when(processRepository.findById("WP-1")).thenReturn(Optional.of(process));
    }

    private BomSeasoningItem seasoning(
            Long id,
            String recipeId,
            String materialTypeId,
            String name,
            String dosagePerKgG,
            String costScope) {
        BomSeasoningItem item = new BomSeasoningItem();
        item.setId(id);
        item.setRecipeId(recipeId);
        item.setFactoryId("F006");
        item.setMaterialTypeId(materialTypeId);
        item.setName(name);
        item.setSection(BomSeasoningItem.SECTION_INJECTION);
        item.setDosagePerKgG(new BigDecimal(dosagePerKgG));
        item.setCountInSeasoning(true);
        item.setWorkProcessId("WP-1");
        item.setCostScope(costScope);
        return item;
    }

    private ProcessSheetRowRequest.OutputLine output(
            String productTypeId, String unit, String quantity) {
        ProcessSheetRowRequest.OutputLine output = new ProcessSheetRowRequest.OutputLine();
        output.setProductTypeId(productTypeId);
        output.setUnit(unit);
        output.setQuantity(new BigDecimal(quantity));
        output.setFinished(true);
        return output;
    }
}
