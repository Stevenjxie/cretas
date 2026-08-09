package com.cretas.aims.service.production;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.cretas.aims.service.workflow.WorkflowPlanOutputContract;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 建计划的「入场券」装配 —— 2026-08-09 Steve 拍板删掉 LEGACY 老路之后新增。
 *
 * <h2>为什么需要这个东西</h2>
 *
 * <p>老路还在时, 没有画布工艺的产品会回落成 LEGACY 权威, 于是「财审闸 / 多单合并 /
 * 安全库存来源」这类<b>不关心工艺</b>的测试可以只准备自己那点夹具。老路删掉后,
 * 建计划必须拿到完整的工艺 + BOM 权威, 否则 409 —— 这些测试全都被挡在门外。
 *
 * <p>⛔ 不要让每个测试类各造一份: 权威由「工艺契约 + BOM 家族/配方/版本/产出单位」四件
 * 套组成, 各造一份必然版本、hash、SKU 集合对不齐, 而对不齐的表现是 409 而不是断言失败 ——
 * 又变成一堆看不懂的红。集中在这里, 改一处全体生效。
 *
 * <p>本装配<b>只负责让计划建得出来</b>, 不替被测类断言任何工艺行为。
 */
public final class WorkflowAuthorityTestFixture {

    public static final Long WORKFLOW_ID = 7001L;
    public static final Integer WORKFLOW_VERSION = 1;
    public static final Long REVISION_ID = 8001L;
    public static final String REVISION_HASH = "stub-revision-hash";
    public static final String BOM_FAMILY_ID = "BOM-FAMILY-STUB";
    public static final String RECIPE_ID = "BOM-RECIPE-STUB";
    public static final Integer RECIPE_VERSION = 1;

    private WorkflowAuthorityTestFixture() {
    }

    /**
     * 给一个用 mock 装配的 {@link ProductionPlanServiceImpl} 注入「这个产品有可用工艺」的最小事实。
     *
     * @param productTypeId 计划要生产的产品 —— 必须与被测用例里用的一致, 否则 BOM 家族的
     *                      终端集合对不上, 建计划仍会 409。
     */
    public static void install(
            ProductionPlanServiceImpl service, String factoryId, String productTypeId) {
        installResolution(service, productTypeId);
        installBom(service, factoryId, productTypeId);
    }

    private static void installResolution(ProductionPlanServiceImpl service, String productTypeId) {
        ProductWorkflowResolutionService resolution = mock(ProductWorkflowResolutionService.class);
        // outputUnitBySku 不能是空 Map: applyWorkflowBomAuthority 会因此报
        // WORKFLOW_REVISION_AUTHORITY_REQUIRED, 而这条 409 看起来跟"没配工艺"一模一样。
        WorkflowPlanOutputContract contract = new WorkflowPlanOutputContract(
                WORKFLOW_ID, WORKFLOW_VERSION, REVISION_ID, REVISION_HASH,
                Map.of(productTypeId, "kg"), "kg");
        lenient().when(resolution.resolveActivePlanOutputContract(any(), any(), any()))
                .thenReturn(Optional.of(contract));
        lenient().when(resolution.resolvePinnedPlanOutputContract(any(), any(), any(), any(), any()))
                .thenReturn(contract);
        ReflectionTestUtils.setField(service, "workflowResolutionService", resolution);
    }

    private static void installBom(
            ProductionPlanServiceImpl service, String factoryId, String productTypeId) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(RECIPE_ID);
        recipe.setFactoryId(factoryId);
        recipe.setProductTypeId(productTypeId);
        recipe.setVersion(RECIPE_VERSION);
        recipe.setIsCurrent(true);
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setBomFamilyId(BOM_FAMILY_ID);
        recipe.setSharedRecipeId(RECIPE_ID);
        recipe.setTargetTerminalNodeId("terminal:" + productTypeId);
        recipe.setWorkflowId(WORKFLOW_ID);
        recipe.setWorkflowDefinitionVersion(WORKFLOW_VERSION);
        recipe.setWorkflowRevisionId(REVISION_ID);
        recipe.setWorkflowRevisionHash(REVISION_HASH);

        BomRecipeRepository repository = mock(BomRecipeRepository.class);
        lenient().when(repository
                        .findByFactoryIdAndWorkflowRevisionIdAndStatusOrderByProductTypeIdAsc(
                                any(), any(), any()))
                .thenReturn(List.of(recipe));
        ReflectionTestUtils.setField(service, "bomRecipeRepository", repository);
    }
}
