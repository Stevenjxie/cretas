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

    /**
     * 客户 2026-07-31 现场: 「成品报工单位与计划固定 BOM 的产出单位不一致 —— 报工单位 袋, BOM 单位 bag」。
     *
     * 🔴 袋 和 bag **本来就是同一个单位** —— 权威表 {@code UnitContractServiceImpl.systemAliases()}
     * 里写着 {@code alias("bag", "bag", "袋")}。BOM 保存那侧 (BomRecipeServiceImpl) 用的正是权威的
     * {@code areEquivalent()}, 所以这份 BOM 存得进去; 偏偏成品报工这条走了私有的硬编码 switch,
     * 只认 kg/g/盒/箱/片 五组, 两边都落到 default 原样返回 → 字面不等 → 409 BLOCKING。
     *
     * 判据是「按系统自己的别名表, 这两个单位是不是同一个」, 不是「字面一不一样」。
     */
    @Test
    void chineseAndEnglishSpellingsOfTheSameUnitAreNotAMismatch() throws Throwable {
        BomRecipe recipe = recipe("BOM-A", "SKU-A", "bag");   // 库里存的是英文码
        stubPinnedFamily(recipe);
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc("BOM-A"))
                .thenReturn(List.of(packaging("PACK-A", "内袋", "袋", "1")));

        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setOutputs(List.of(output("SKU-A", "袋", "2")));   // 报工填的是中文

        List<ProductionStockAllocationService.AutomaticRequirement> result =
                requirements(request);

        assertThat(result).singleElement().satisfies(requirement -> {
            assertEquals("PACK-A", requirement.materialTypeId());
            assertThat(requirement.quantity()).isEqualByComparingTo("2");
        });
    }

    /** 反过来也一样 —— BOM 存中文、报工报英文码, 同样不该拦。 */
    @Test
    void theSameHoldsWhenTheBomStoresChineseAndReportingSendsTheCode() throws Throwable {
        BomRecipe recipe = recipe("BOM-A", "SKU-A", "袋");
        stubPinnedFamily(recipe);
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc("BOM-A"))
                .thenReturn(List.of(packaging("PACK-A", "内袋", "袋", "1")));

        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setOutputs(List.of(output("SKU-A", "bag", "2")));

        assertThat(requirements(request)).singleElement().satisfies(requirement ->
                assertEquals("PACK-A", requirement.materialTypeId()));
    }

    /**
     * 🔴 同一个私有 switch 还有**反方向**的错, 一并钉住: 它把 片/slice/piece/pcs/个 全映射成
     * "slice", 而权威表里 个→pcs、片→slice 是**两个不同单位** —— 于是「个」报工能冒充「片」的 BOM
     * 混过去。误拦 (上面两条) 和漏拦 (这条) 出自同一个根因: 没用系统自己的别名表。
     */
    @Test
    void genuinelyDifferentUnitsStillFailClosed() {
        BomRecipe recipe = recipe("BOM-A", "SKU-A", "片");
        stubPinnedFamily(recipe);

        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setOutputs(List.of(output("SKU-A", "个", "2")));

        BusinessException error = assertThrows(
                BusinessException.class, () -> requirements(request));

        assertEquals("BOM_OUTPUT_UNIT_MISMATCH", error.getErrorCode());
    }

    /**
     * 单位没填仍然要挡住。
     *
     * <p>⚠️ 这条不是顺手加的: 复用的 {@code configuredUnitsEquivalent} 对「supplied 为空」
     * 是**当等价放行**的 (工序配置那条允许省略单位)。成品报工不能沿用那个宽松语义 ——
     * 没填就是不知道报的是什么, 放行等于让 BOM 用量按一个来历不明的数去折算。
     * 换实现时如果忘了这层守卫, 就会从「误拦」一路滑到「什么都不拦」。</p>
     */
    @Test
    void aMissingReportedUnitStillFailsClosed() {
        BomRecipe recipe = recipe("BOM-A", "SKU-A", "袋");
        stubPinnedFamily(recipe);

        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setOutputs(List.of(output("SKU-A", "  ", "2")));

        BusinessException error = assertThrows(
                BusinessException.class, () -> requirements(request));

        assertEquals("BOM_OUTPUT_UNIT_MISMATCH", error.getErrorCode());
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
