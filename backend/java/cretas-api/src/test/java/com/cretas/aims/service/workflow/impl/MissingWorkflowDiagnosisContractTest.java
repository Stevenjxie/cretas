package com.cretas.aims.service.workflow.impl;

import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator;
import com.cretas.aims.service.workflow.impl.ProductWorkflowResolutionServiceImpl.MissingWorkflowCause;
import com.cretas.aims.service.workflow.impl.ProductWorkflowResolutionServiceImpl.WorkflowCoverage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 「找不到工艺」这句提示<b>不许把人送去建一个已经存在的东西</b>。
 *
 * <p>原文案是一句断言 ——「未找到覆盖该产品的工序 Workflow，请前往 Workflow 配置」——
 * 而它有四个互不相同的成因(工艺存在且可用只是要整组选 / 启用了但当前版本不可用 /
 * 存在但没启用 / 真的没有), 其中<b>三个</b>的下一步都不是「去建一张」。
 * 本仓的账: <b>错误码是分类, 不是定位</b>。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MissingWorkflowDiagnosisContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String TARGET_SKU = "sku-A";
    private static final String SIBLING_SKU = "sku-B";
    private static final String RAW_SKU = "raw-1";

    @Mock private ProductProcessWorkflowActivationRepository activationRepository;
    @Mock private ProductProcessWorkflowRepository workflowRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private UnitContractService unitContractService;
    @Mock private ProductProcessWorkflowUnitValidator unitValidator;

    // ------------------------------------------------------------------
    // 1. 诊断本身: 四个成因必须分得开
    // ------------------------------------------------------------------

    @Test
    @DisplayName("四个成因分得开 —— 且被参数化的那一维真的不同")
    void diagnosisSeparatesAllFourCauses() {
        Set<String> requested = Set.of(TARGET_SKU);
        Set<String> covering = Set.of(TARGET_SKU, SIBLING_SKU);

        MissingWorkflowCause enabledUsable = ProductWorkflowResolutionServiceImpl
                .diagnoseMissingWorkflow(requested,
                        List.of(new WorkflowCoverage(covering, true, true)));
        MissingWorkflowCause enabledUnusable = ProductWorkflowResolutionServiceImpl
                .diagnoseMissingWorkflow(requested,
                        List.of(new WorkflowCoverage(covering, true, false)));
        MissingWorkflowCause notEnabled = ProductWorkflowResolutionServiceImpl
                .diagnoseMissingWorkflow(requested,
                        List.of(new WorkflowCoverage(covering, false, false)));
        MissingWorkflowCause absent = ProductWorkflowResolutionServiceImpl
                .diagnoseMissingWorkflow(requested,
                        List.of(new WorkflowCoverage(Set.of("sku-unrelated"), true, true)));

        assertEquals(MissingWorkflowCause.NEEDS_SIBLING_OUTPUTS, enabledUsable);
        assertEquals(MissingWorkflowCause.ENABLED_BUT_UNUSABLE, enabledUnusable);
        assertEquals(MissingWorkflowCause.NOT_ENABLED, notEnabled);
        assertEquals(MissingWorkflowCause.TRULY_ABSENT, absent);

        // ⛔ 「4 条全绿」必须先证明这 4 个读数真的互不相同, 否则可能只是同一个样本跑了 4 遍
        assertEquals(4, List.of(enabledUsable, enabledUnusable, notEnabled, absent)
                .stream().distinct().count());
    }

    @Test
    @DisplayName("没有任何工艺时是 TRULY_ABSENT —— 只有这一种才该说「请前往 Workflow 配置」")
    void emptyCoverageIsTrulyAbsent() {
        assertEquals(MissingWorkflowCause.TRULY_ABSENT,
                ProductWorkflowResolutionServiceImpl.diagnoseMissingWorkflow(
                        Set.of(TARGET_SKU), List.of()));
    }

    // ------------------------------------------------------------------
    // 2. 文案: 三个「工艺已存在」的成因不许叫人去新建
    // ------------------------------------------------------------------

    @Test
    @DisplayName("判据挂在成因上(结构), 不是靠文案里有没有某个词(文本)")
    void hintsForExistingWorkflowNeverPrescribeCreatingOne() {
        // ⬛ 上一版这条断言写成 hint.contains("新建工艺") == false, 当场被自己拓红 ——
        //    因为正确的文案说的是「不需要新建工艺」, 它包含那个子串。
        //    改成断言「那句祈使句在不在」 + 成因自带的结构标志。
        for (MissingWorkflowCause cause : MissingWorkflowCause.values()) {
            String hint = ProductWorkflowResolutionServiceImpl.missingWorkflowHint(cause, List.of());
            assertNotNull(hint);
            assertEquals(!cause.workflowAlreadyExists(),
                    hint.contains(ProductWorkflowResolutionServiceImpl.CREATE_WORKFLOW_INSTRUCTION),
                    cause + " 的下一步与「工艺是不是已经存在」对不上: " + hint);
        }
        // 阳性对照: 确实有一种成因该说那句话, 否则上面那条可能是恒真式
        assertTrue(ProductWorkflowResolutionServiceImpl.missingWorkflowHint(
                        MissingWorkflowCause.TRULY_ABSENT, List.of())
                .contains(ProductWorkflowResolutionServiceImpl.CREATE_WORKFLOW_INSTRUCTION),
                "阳性对照失败: TRULY_ABSENT 才是该叫人新建的那一种");
        assertEquals(1, java.util.Arrays.stream(MissingWorkflowCause.values())
                        .filter(cause -> !cause.workflowAlreadyExists()).count(),
                "只有一种成因该叫人新建 —— 否则上面那条等价断言没有判别力");
    }

    @Test
    @DisplayName("能算出缺哪些同胞产出时, 直接把名字说出来")
    void siblingHintNamesTheMissingOutputs() {
        String hint = ProductWorkflowResolutionServiceImpl.missingWorkflowHint(
                MissingWorkflowCause.NEEDS_SIBLING_OUTPUTS, List.of(SIBLING_SKU));
        assertTrue(hint.contains(SIBLING_SKU), hint);
        // 算不出来时不许编 —— 退回一句不点名但仍然正确的话
        String vague = ProductWorkflowResolutionServiceImpl.missingWorkflowHint(
                MissingWorkflowCause.NEEDS_SIBLING_OUTPUTS, List.of());
        assertFalse(vague.contains(SIBLING_SKU));
        assertTrue(vague.contains("整组一起选"), vague);
    }

    // ------------------------------------------------------------------
    // 3. 接线断言: 真实公开入口上, 诊断结论真的到达了用户
    // ------------------------------------------------------------------

    @Test
    @DisplayName("真实入口: 工艺存在但没启用时, 抛出来的 hint 说的是「去启用」不是「去新建」")
    void realEntryPointReportsNotEnabledInsteadOfTellingUserToCreateAWorkflow() {
        ProductWorkflowResolutionServiceImpl service = service();
        // 锚点上没有启用记录 ⇒ requireResolutionForAnchor 走 noMatchingWorkflow
        when(activationRepository.findByFactoryIdAndProductTypeId(FACTORY_ID, RAW_SKU))
                .thenReturn(Optional.empty());
        when(activationRepository.findByFactoryIdAndEnabledTrue(FACTORY_ID))
                .thenReturn(List.of());
        // 但工厂里确实有一张覆盖该成品的工艺(只是没启用)
        when(workflowRepository.findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc(FACTORY_ID))
                .thenReturn(List.of(workflow(1L, ProductProcessWorkflow.Status.PUBLISHED)));

        BusinessException error = assertThrows(BusinessException.class, () ->
                service.assertActiveWorkflowCoversOutputs(FACTORY_ID, RAW_SKU, List.of(TARGET_SKU)));

        assertEquals("WORKFLOW_SINGLE_OUTPUT_NOT_FOUND", error.getErrorCode());
        assertNotNull(error.getActionHint(), "拦住人的地方必须说下一步");
        assertTrue(error.getActionHint().contains("已存在"),
                "应当明说工艺已存在, 实际: " + error.getActionHint());
        assertFalse(error.getActionHint().contains(
                        ProductWorkflowResolutionServiceImpl.CREATE_WORKFLOW_INSTRUCTION),
                "⛔ 又把人送去建一个已经存在的东西: " + error.getActionHint());
    }

    @Test
    @DisplayName("阳性对照: 工厂里真的没有覆盖该成品的工艺时, 仍然叫人去新建")
    void realEntryPointStillTellsUserToCreateWhenNothingCoversTheSku() {
        ProductWorkflowResolutionServiceImpl service = service();
        when(activationRepository.findByFactoryIdAndProductTypeId(FACTORY_ID, RAW_SKU))
                .thenReturn(Optional.empty());
        when(activationRepository.findByFactoryIdAndEnabledTrue(FACTORY_ID))
                .thenReturn(List.of());
        when(workflowRepository.findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc(FACTORY_ID))
                .thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class, () ->
                service.assertActiveWorkflowCoversOutputs(FACTORY_ID, RAW_SKU, List.of(TARGET_SKU)));

        assertTrue(error.getActionHint().contains(
                        ProductWorkflowResolutionServiceImpl.CREATE_WORKFLOW_INSTRUCTION),
                "阳性对照失败: 真的没有工艺时那句指引必须还在, 否则上面那条阴性断言可能恒真。实际: "
                        + error.getActionHint());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ProductWorkflowResolutionServiceImpl service() {
        when(unitValidator.validate(any(), any())).thenThrow(
                new IllegalStateException("本用例不该走到单位校验"));
        return new ProductWorkflowResolutionServiceImpl(
                activationRepository, workflowRepository, productTypeRepository,
                rawMaterialTypeRepository, new ObjectMapper(), unitContractService, unitValidator);
    }

    /** 最小可分类图: 原料根 → 工序 → 终端成品。terminals = {TARGET_SKU}。 */
    private ProductProcessWorkflow workflow(Long id, ProductProcessWorkflow.Status status) {
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(id);
        workflow.setFactoryId(FACTORY_ID);
        workflow.setProductTypeId(RAW_SKU);
        workflow.setStatus(status);
        workflow.setDefinitionVersion(1);
        workflow.setNodesJson("["
                + "{\"id\":\"n-raw\",\"kind\":\"RAW_MATERIAL\",\"data\":{\"skuId\":\"" + RAW_SKU + "\"}},"
                + "{\"id\":\"n-proc\",\"kind\":\"PROCESS\",\"data\":{\"workProcessId\":\"wp-1\"}},"
                + "{\"id\":\"n-fg\",\"kind\":\"FINISHED_GOOD\",\"data\":{\"skuId\":\"" + TARGET_SKU + "\"}}"
                + "]");
        workflow.setEdgesJson("["
                + "{\"id\":\"e1\",\"source\":\"n-raw\",\"target\":\"n-proc\"},"
                + "{\"id\":\"e2\",\"source\":\"n-proc\",\"target\":\"n-fg\"}"
                + "]");
        return workflow;
    }

    @SuppressWarnings("unused")
    private ProductProcessWorkflowActivation activation(Long workflowId, boolean enabled) {
        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId(FACTORY_ID);
        activation.setProductTypeId(RAW_SKU);
        activation.setActiveWorkflowId(workflowId);
        activation.setActiveDefinitionVersion(1);
        activation.setEnabled(enabled);
        return activation;
    }
}
