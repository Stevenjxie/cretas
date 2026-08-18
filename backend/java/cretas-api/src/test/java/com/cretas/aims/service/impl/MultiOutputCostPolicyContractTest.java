package com.cretas.aims.service.impl;

import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.ProductionSettlementOutputLine;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionSettlementOutputLineRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 坎 4 的契约: <b>「这是不是副产物」全仓只有一条判据</b>。
 *
 * <h3>它在守什么</h3>
 *
 * 2026-08-18 prod 实测: 同一个问题长出了两份判据 ——
 * {@code BomRecipeServiceImpl} 会先问 {@code targetProducedUnderActualIoSemantics}
 * 把 ACTUAL_IO 自动编号出来的占位 {@code BY_PRODUCT} 豁免掉(所以填 NRV 会被
 * {@code BOM_NON_BY_PRODUCT_NRV_FORBIDDEN} 拒绝), 而
 * {@code ProductionPlanServiceImpl#persistWorkflowSettlementOutputs} 只判
 * {@code outputRole == BY_PRODUCT}, 要求 {@code NRV > 0}。
 * 净效果: <b>NRV 被一道闸要求、被另一道闸禁止 ⇒ 多成品计划结不了单。</b>
 *
 * <h3>桩数据为什么长这样(「真实上游真的会给出这个形状吗」)</h3>
 *
 * 全部照 2026-08-18 prod 只读普查的实测形状造, 不是想当然:
 * <ul>
 *   <li>{@code BY_PRODUCT} 行: {@code cost_allocation_ratio = 0.0000}(15/15 行),
 *       {@code byproduct_nrv_unit_price} <b>为 null</b>(全表 51 行 0 个非空 —— 这一列从没被写过)</li>
 *   <li>{@code MAIN} 行: {@code cost_allocation_ratio = 100.0000}(36/36 行)</li>
 *   <li>{@code CO_PRODUCT}: 全库 <b>0 行</b>, 现有授权入口到不了它</li>
 *   <li>ACTUAL_IO: 89/89 个 PROCESS 节点全是它 —— 不是部分采用, 是全部</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiOutputCostPolicyContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "plan-1";
    private static final String MAIN_SKU = "sku-main";
    private static final String SECOND_SKU = "sku-second";

    @Mock private BomRecipeRepository bomRecipeRepository;
    @Mock private ProductionSettlementOutputLineRepository outputLineRepository;
    @Mock private BomWorkflowRevisionService bomWorkflowRevisionService;

    // ------------------------------------------------------------------
    // 1. 权威出口本身: 三态, 且 ACTUAL_IO 那一态既不要 NRV 也不要 ratio
    // ------------------------------------------------------------------

    @Test
    @DisplayName("行级判据三态 —— ACTUAL_IO 占位行不是可抵扣副产品, 也不是可分摊主产出")
    void settlementLinePolicyIsThreeStateNotBoolean() {
        // 阳性对照: 用户真标的副产品(有正 NRV)确实被认成 BY_PRODUCT_NRV
        assertEquals(BomWorkflowRevisionService.OutputCostPolicy.BY_PRODUCT_NRV,
                BomWorkflowRevisionService.settlementLineCostPolicy(
                        BomRecipe.OutputRole.BY_PRODUCT, new BigDecimal("12.5")),
                "阳性对照失败: 真副产品应按 NRV 计价, 后面的阴性断言就没有意义了");

        // prod 实测形状: BY_PRODUCT + NRV 为 null(全表 0 个非空) ⇒ 占位, 不是真副产品
        assertEquals(BomWorkflowRevisionService.OutputCostPolicy.ACTUAL_IO_PLACEHOLDER,
                BomWorkflowRevisionService.settlementLineCostPolicy(
                        BomRecipe.OutputRole.BY_PRODUCT, null));
        assertEquals(BomWorkflowRevisionService.OutputCostPolicy.ACTUAL_IO_PLACEHOLDER,
                BomWorkflowRevisionService.settlementLineCostPolicy(
                        BomRecipe.OutputRole.BY_PRODUCT, BigDecimal.ZERO));

        assertEquals(BomWorkflowRevisionService.OutputCostPolicy.ALLOCATION_RATIO,
                BomWorkflowRevisionService.settlementLineCostPolicy(
                        BomRecipe.OutputRole.MAIN, null));
        assertEquals(BomWorkflowRevisionService.OutputCostPolicy.ALLOCATION_RATIO,
                BomWorkflowRevisionService.settlementLineCostPolicy(
                        BomRecipe.OutputRole.CO_PRODUCT, null));
    }

    // ------------------------------------------------------------------
    // 2. 真实入口 A: persistWorkflowSettlementOutputs (结单落产出行)
    //    ⛔ 不是测 helper —— 这是 settleProduction 真正调的那个方法, 真实例 + 真实体,
    //       只桩掉 repository(外部 IO)。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("真实入口: ACTUAL_IO 占位产出不再被结单闸要 NRV —— 坎 4 解开")
    void settlementAcceptsActualIoPlaceholderOutputWithoutNrv() {
        BomRecipe main = recipe("bom-main", MAIN_SKU, BomRecipe.OutputRole.MAIN,
                new BigDecimal("100"), null);
        BomRecipe second = recipe("bom-second", SECOND_SKU, BomRecipe.OutputRole.BY_PRODUCT,
                BigDecimal.ZERO, null);
        // 权威出口说: 这两个都是 ACTUAL_IO 自动编号出来的占位角色
        stubActualIoFamily();

        ProductionPlanServiceImpl service = serviceWith(main, second);

        // 不抛 = 结单闸放行
        invokePersist(service, plan(), settlement(), request());

        @SuppressWarnings("unchecked")
        java.util.List<ProductionSettlementOutputLine> saved = captureSaved();
        assertEquals(2, saved.size(), "两个终端产出都应该落行");
        assertTrue(saved.stream().anyMatch(line -> SECOND_SKU.equals(line.getProductTypeId())),
                "第二个产出必须落行 —— 它正是原来被 BYPRODUCT_NRV_REQUIRED 卡住的那一个");
        assertNull(saved.stream()
                        .filter(line -> SECOND_SKU.equals(line.getProductTypeId()))
                        .findFirst().orElseThrow().getByproductNrvUnitPrice(),
                "占位产出不该被凭空塞一个 NRV —— 那是编数字");
    }

    @Test
    @DisplayName("阳性对照(fail-closed 没被削弱): 用户【真标】的副产品缺 NRV 仍然拦, 且给中文和下一步")
    void settlementStillRejectsAuthoredByProductWithoutNrv() {
        BomRecipe main = recipe("bom-main", MAIN_SKU, BomRecipe.OutputRole.MAIN,
                new BigDecimal("100"), null);
        BomRecipe second = recipe("bom-second", SECOND_SKU, BomRecipe.OutputRole.BY_PRODUCT,
                BigDecimal.ZERO, null);
        when(bomWorkflowRevisionService.resolveOutputCostPolicy(eq(FACTORY_ID), any()))
                .thenAnswer(call -> {
                    BomRecipe recipe = call.getArgument(1);
                    return recipe.getOutputRole() == BomRecipe.OutputRole.BY_PRODUCT
                            ? BomWorkflowRevisionService.OutputCostPolicy.BY_PRODUCT_NRV
                            : BomWorkflowRevisionService.OutputCostPolicy.ALLOCATION_RATIO;
                });

        ProductionPlanServiceImpl service = serviceWith(main, second);

        BusinessException error = assertThrows(BusinessException.class,
                () -> invokePersist(service, plan(), settlement(), request()));

        assertEquals("BYPRODUCT_NRV_REQUIRED", error.getErrorCode());
        assertEquals(409, error.getCode());
        assertTrue(containsChinese(error.getMessage()),
                "错误正文必须是中文, 实际: " + error.getMessage());
        assertNotNull(error.getActionHint(), "拦住人的地方必须说下一步");
        assertTrue(error.getActionHint().contains("BOM成本管理"),
                "下一步要说清去哪配, 实际: " + error.getActionHint());
        verify(outputLineRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("⛔ 拒答不许挪到下一道闸: 占位产出的 ratio 是 0, 不能改撞 ALLOCATION_RATIO_REQUIRED")
    void placeholderOutputDoesNotFallThroughToTheRatioGate() {
        // 这一条是本次修复最容易做半吊子的地方 —— 只豁免 NRV 那一支, 占位产出(ratio=0)
        // 下一步就会撞上 OUTPUT_COST_ALLOCATION_RATIO_REQUIRED, 用户看到的仍然是结不了单。
        BomRecipe main = recipe("bom-main", MAIN_SKU, BomRecipe.OutputRole.MAIN,
                new BigDecimal("100"), null);
        BomRecipe second = recipe("bom-second", SECOND_SKU, BomRecipe.OutputRole.BY_PRODUCT,
                BigDecimal.ZERO, null);
        stubActualIoFamily();

        ProductionPlanServiceImpl service = serviceWith(main, second);
        invokePersist(service, plan(), settlement(), request());

        assertEquals(2, captureSaved().size(),
                "占位产出的 ratio=0, 若第二道闸没跟着豁免, 这里会抛 OUTPUT_COST_ALLOCATION_RATIO_REQUIRED");
    }

    @Test
    @DisplayName("阳性对照: 真正没配比例的主产出仍然被拦(不是把整道闸删了)")
    void authoredMainOutputWithoutRatioIsStillRejected() {
        BomRecipe main = recipe("bom-main", MAIN_SKU, BomRecipe.OutputRole.MAIN, null, null);
        BomRecipe second = recipe("bom-second", SECOND_SKU, BomRecipe.OutputRole.MAIN,
                new BigDecimal("100"), null);
        when(bomWorkflowRevisionService.resolveOutputCostPolicy(eq(FACTORY_ID), any()))
                .thenReturn(BomWorkflowRevisionService.OutputCostPolicy.ALLOCATION_RATIO);

        ProductionPlanServiceImpl service = serviceWith(main, second);

        BusinessException error = assertThrows(BusinessException.class,
                () -> invokePersist(service, plan(), settlement(), request()));
        assertEquals("OUTPUT_COST_ALLOCATION_RATIO_REQUIRED", error.getErrorCode());
        assertTrue(containsChinese(error.getMessage()), error.getMessage());
        assertNotNull(error.getActionHint());
    }

    @Test
    @DisplayName("接线断言: 结单闸必须【真的去问】权威出口, 不许自己判 outputRole")
    void settlementActuallyConsultsTheAuthoritativeOutlet() {
        // 这一条守的不是「答案对不对」, 而是「线接上没有」——
        // 本仓的账: 零件对了、线没接上, 而闸是绿的。
        BomRecipe main = recipe("bom-main", MAIN_SKU, BomRecipe.OutputRole.MAIN,
                new BigDecimal("100"), null);
        BomRecipe second = recipe("bom-second", SECOND_SKU, BomRecipe.OutputRole.BY_PRODUCT,
                BigDecimal.ZERO, null);
        stubActualIoFamily();

        ProductionPlanServiceImpl service = serviceWith(main, second);
        invokePersist(service, plan(), settlement(), request());

        verify(bomWorkflowRevisionService).resolveOutputCostPolicy(FACTORY_ID, main);
        verify(bomWorkflowRevisionService).resolveOutputCostPolicy(FACTORY_ID, second);
    }

    @Test
    @DisplayName("权威出口没装配就炸, ⛔ 不许退回一个默认判据 —— 那正是坎 4 的成因")
    void missingAuthorityFailsLoudlyInsteadOfGuessing() {
        BomRecipe main = recipe("bom-main", MAIN_SKU, BomRecipe.OutputRole.MAIN,
                new BigDecimal("100"), null);
        BomRecipe second = recipe("bom-second", SECOND_SKU, BomRecipe.OutputRole.BY_PRODUCT,
                BigDecimal.ZERO, null);
        ProductionPlanServiceImpl service = serviceWith(main, second);
        ReflectionTestUtils.setField(service, "bomWorkflowRevisionService", null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> invokePersist(service, plan(), settlement(), request()));
        assertEquals("WORKFLOW_OUTPUT_SETTLEMENT_UNAVAILABLE", error.getErrorCode());
        assertEquals(500, error.getCode());
    }

    // ------------------------------------------------------------------
    // 3. 真实入口 B: allocateWorkflowOutputCostsFromTotal (入库分摊成本)
    //    修复必须按【最不显眼的那个】验收 —— 结单放行了, 成本分摊那一步原来会 NPE。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("真实入口: 占位产出行进入成本分摊器, 给显式 409 而不是 NullPointerException")
    void costAllocatorRefusesPlaceholderLinesExplicitlyInsteadOfThrowingNpe() {
        ProductionSettlementOutputLine mainLine =
                outputLine(MAIN_SKU, BomRecipe.OutputRole.MAIN, "100", null, "60");
        // prod 实测形状: BY_PRODUCT + ratio 0 + NRV null
        ProductionSettlementOutputLine placeholder =
                outputLine(SECOND_SKU, BomRecipe.OutputRole.BY_PRODUCT, "0", null, "40");

        BusinessException error = assertThrows(BusinessException.class, () ->
                ProductionPlanServiceImpl.allocateWorkflowOutputCostsFromTotal(
                        new BigDecimal("1000"), List.of(mainLine, placeholder)));

        assertEquals("OUTPUT_COST_ALLOCATION_POLICY_UNAUTHORED", error.getErrorCode());
        assertEquals(409, error.getCode());
        assertTrue(containsChinese(error.getMessage()), error.getMessage());
        assertNotNull(error.getActionHint());
        assertNull(placeholder.getAllocatedCost(),
                "⛔ 算不出不许写成 ¥0 —— 那会让后续 COGS 按零成本结转");
        assertNull(mainLine.getAllocatedCost(),
                "拒绝时不许留下半份分摊结果");
    }

    @Test
    @DisplayName("阳性对照: 真副产品(有 NRV)照常按 NRV 抵扣, 分摊器没被改坏")
    void costAllocatorStillCreditsAuthoredByProductsByNrv() {
        ProductionSettlementOutputLine mainLine =
                outputLine(MAIN_SKU, BomRecipe.OutputRole.MAIN, "100", null, "60");
        ProductionSettlementOutputLine byProduct =
                outputLine(SECOND_SKU, BomRecipe.OutputRole.BY_PRODUCT, "0", "2", "40");

        ProductionPlanServiceImpl.allocateWorkflowOutputCostsFromTotal(
                new BigDecimal("1000"), List.of(mainLine, byProduct));

        // 副产品 NRV 2 × 40 = 80; 剩余 920 全给唯一的主产出
        assertEquals(0, new BigDecimal("80").compareTo(byProduct.getAllocatedCost()),
                "阳性对照失败: NRV 抵扣路径没跑起来, 上面的阴性断言就没有意义");
        assertEquals(0, new BigDecimal("920").compareTo(mainLine.getAllocatedCost()));
    }

    // ------------------------------------------------------------------
    // 4. 两个形态必须给同一个答案 (形态 D: 同一个东西有两份, 它一定会漂)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("配方级与行级判据对【结单能落库的每一种形状】给出同一答案")
    void recipeLevelAndLineLevelPoliciesAgreeOnEveryPersistableShape() {
        record Shape(BomRecipe.OutputRole role, BigDecimal nrv,
                     BomWorkflowRevisionService.OutputCostPolicy recipePolicy) { }
        List<Shape> shapes = List.of(
                // 结单闸放行 BY_PRODUCT_NRV 时必然 nrv > 0
                new Shape(BomRecipe.OutputRole.BY_PRODUCT, new BigDecimal("3.5"),
                        BomWorkflowRevisionService.OutputCostPolicy.BY_PRODUCT_NRV),
                // BOM 侧会把非计价产出的 NRV 主动置 null 并禁止填
                new Shape(BomRecipe.OutputRole.BY_PRODUCT, null,
                        BomWorkflowRevisionService.OutputCostPolicy.ACTUAL_IO_PLACEHOLDER),
                new Shape(BomRecipe.OutputRole.MAIN, null,
                        BomWorkflowRevisionService.OutputCostPolicy.ALLOCATION_RATIO),
                new Shape(BomRecipe.OutputRole.CO_PRODUCT, null,
                        BomWorkflowRevisionService.OutputCostPolicy.ALLOCATION_RATIO));

        for (Shape shape : shapes) {
            assertEquals(shape.recipePolicy(),
                    BomWorkflowRevisionService.settlementLineCostPolicy(shape.role(), shape.nrv()),
                    "行级与配方级判据漂了: role=" + shape.role() + " nrv=" + shape.nrv());
        }
        // 被参数化的那一维真的不同 —— 否则「4 条全绿」可能只是同一个样本跑了 4 遍
        assertEquals(4, shapes.size());
        assertEquals(3, shapes.stream().map(Shape::recipePolicy).distinct().count(),
                "三态必须都被覆盖到, 否则这条断言没有判别力");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * ACTUAL_IO 多产出家族的<b>真实</b>口径分布 —— ⛔ 不要把 MAIN 也桩成 PLACEHOLDER。
     *
     * <p>权威出口先用便宜的 outputRole 判: MAIN 走 ALLOCATION_RATIO(它恒拿 ratio=100,
     * prod 36/36 全是 100.0000, 所以照样放行), 只有 BY_PRODUCT 才去问 ACTUAL_IO。
     * 桩成「两个都是 PLACEHOLDER」是真实上游产生不出来的形状。</p>
     */
    private void stubActualIoFamily() {
        when(bomWorkflowRevisionService.resolveOutputCostPolicy(eq(FACTORY_ID), any()))
                .thenAnswer(call -> {
                    BomRecipe recipe = call.getArgument(1);
                    return recipe.getOutputRole() == BomRecipe.OutputRole.BY_PRODUCT
                            ? BomWorkflowRevisionService.OutputCostPolicy.ACTUAL_IO_PLACEHOLDER
                            : BomWorkflowRevisionService.OutputCostPolicy.ALLOCATION_RATIO;
                });
    }

    private ProductionPlanServiceImpl serviceWith(BomRecipe... recipes) {
        ProductionPlanServiceImpl service = new ProductionPlanServiceImpl(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "bomRecipeRepository", bomRecipeRepository);
        ReflectionTestUtils.setField(service, "productionSettlementOutputLineRepository",
                outputLineRepository);
        ReflectionTestUtils.setField(service, "bomWorkflowRevisionService", bomWorkflowRevisionService);
        for (BomRecipe recipe : recipes) {
            when(bomRecipeRepository.findById(recipe.getId())).thenReturn(Optional.of(recipe));
        }
        return service;
    }

    @SuppressWarnings("unchecked")
    private List<ProductionSettlementOutputLine> captureSaved() {
        org.mockito.ArgumentCaptor<List<ProductionSettlementOutputLine>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(outputLineRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    /**
     * 反射进入 {@code persistWorkflowSettlementOutputs} —— 它是 private, 但这是
     * {@code settleProduction} 真正调用的那个产品方法(见 ProductionPlanServiceImpl:2834),
     * 用的是真实例与真实体, 只桩掉了 repository。⛔ 不是另建一个 helper 来测。
     */
    private void invokePersist(ProductionPlanServiceImpl service, ProductionPlan plan,
                               ProductionSettlement settlement, ProductionSettlementRequest request) {
        try {
            Method method = ProductionPlanServiceImpl.class.getDeclaredMethod(
                    "persistWorkflowSettlementOutputs", String.class, ProductionPlan.class,
                    ProductionSettlement.class, ProductionSettlementRequest.class);
            method.setAccessible(true);
            method.invoke(service, FACTORY_ID, plan, settlement, request);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private ProductionPlan plan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setWorkflowSelectionMode(ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        plan.setSelectedBomFamilyId("family-1");
        plan.setSelectedWorkflowRevisionId(173L);
        plan.setSelectedWorkflowRevisionHash("hash-1");
        Map<String, String> recipeIds = new LinkedHashMap<>();
        recipeIds.put(MAIN_SKU, "bom-main");
        recipeIds.put(SECOND_SKU, "bom-second");
        plan.setSelectedBomRecipeIdsByProduct(recipeIds);
        Map<String, Integer> versions = new LinkedHashMap<>();
        versions.put(MAIN_SKU, 1);
        versions.put(SECOND_SKU, 1);
        plan.setSelectedBomVersionsByProduct(versions);
        Map<String, String> units = new LinkedHashMap<>();
        units.put(MAIN_SKU, "盒");
        units.put(SECOND_SKU, "kg");
        plan.setWorkflowOutputUnitsByProduct(units);
        return plan;
    }

    private ProductionSettlement settlement() {
        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId("settlement-1");
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(PLAN_ID);
        return settlement;
    }

    private ProductionSettlementRequest request() {
        ProductionSettlementRequest request = new ProductionSettlementRequest();
        List<ProductionSettlementRequest.OutputLine> outputs = new ArrayList<>();
        outputs.add(outputRequest(MAIN_SKU, "盒", "60", "terminal-main"));
        outputs.add(outputRequest(SECOND_SKU, "kg", "40", "terminal-second"));
        request.setTerminalOutputs(outputs);
        return request;
    }

    private ProductionSettlementRequest.OutputLine outputRequest(
            String sku, String unit, String quantity, String terminalNodeId) {
        ProductionSettlementRequest.OutputLine line = new ProductionSettlementRequest.OutputLine();
        line.setProductTypeId(sku);
        line.setBatchNumber("B-" + sku);
        line.setQuantity(new BigDecimal(quantity));
        line.setUnit(unit);
        line.setMaterialNodeId(terminalNodeId);
        return line;
    }

    private BomRecipe recipe(String id, String productTypeId, BomRecipe.OutputRole role,
                             BigDecimal ratio, BigDecimal nrv) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(id);
        recipe.setFactoryId(FACTORY_ID);
        recipe.setProductTypeId(productTypeId);
        recipe.setProductName(MAIN_SKU.equals(productTypeId) ? "成品甲" : "成品乙");
        recipe.setBomFamilyId("family-1");
        recipe.setWorkflowRevisionId(173L);
        recipe.setWorkflowRevisionHash("hash-1");
        recipe.setVersion(1);
        recipe.setTargetTerminalNodeId(MAIN_SKU.equals(productTypeId)
                ? "terminal-main" : "terminal-second");
        recipe.setOutputRole(role);
        recipe.setCostAllocationRatio(ratio);
        recipe.setByproductNrvUnitPrice(nrv);
        recipe.setOutputUnit(MAIN_SKU.equals(productTypeId) ? "盒" : "kg");
        return recipe;
    }

    private ProductionSettlementOutputLine outputLine(
            String sku, BomRecipe.OutputRole role, String ratio, String nrv, String quantity) {
        ProductionSettlementOutputLine line = ProductionSettlementOutputLine.create();
        line.setProductTypeId(sku);
        line.setOutputRole(role);
        line.setCostAllocationRatio(ratio == null ? null : new BigDecimal(ratio));
        line.setByproductNrvUnitPrice(nrv == null ? null : new BigDecimal(nrv));
        line.setReceivedQuantity(new BigDecimal(quantity));
        line.setQuantityUnit("kg");
        return line;
    }

    private boolean containsChinese(String text) {
        return text != null && text.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
    }
}
