package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowOutputResolutionDTO;

import java.util.List;
import java.util.Optional;

/**
 * 生产计划「选择成品 → 解析并固定 Workflow 路线」。
 *
 * <p>反向索引 = 运行时扫描已启用 activation 的原料图 nodesJson 终端成品 (不建映射表, 天然一致)。
 */
public interface ProductWorkflowResolutionService {

    /**
     * 给定一组成品 productTypeId, 解析最高优先层的已启用 workflow 候选 (只读, 空候选不报错)。
     * <ul>
     *   <li>单选只接受单产出精确图。</li>
     *   <li>多选优先终端集合精确图；无精确图时只返回额外产出最少的同层超集。</li>
     *   <li>同层可返回 1/N 条，N 条必须由调用方显式选择。</li>
     *   <li>0 候选 → NONE (空列表)。</li>
     * </ul>
     */
    WorkflowOutputResolutionDTO resolveForOutputs(String factoryId, List<String> finishedGoodProductTypeIds);

    /**
     * Resolve the unique active workflow and return only the ancestor process path for one finished SKU.
     * An empty result means that no active workflow exists and callers may use the legacy linear chain.
     * Ambiguous or invalid active workflows fail closed and must never fall back silently.
     */
    Optional<WorkflowProcessPath> resolveProcessPath(String factoryId, String finishedGoodProductTypeId);

    /**
     * 写路径守卫: 断言 ownerProductTypeId 当前 enabled activation 指向的 workflow 终端覆盖 targets,
     * 否则抛 409 WORKFLOW_RESOLUTION_NOT_COVERED。用于建计划/转批次防绕过 + 防 activation 窗口期漂移。
     */
    void assertActiveWorkflowCoversOutputs(String factoryId, String ownerProductTypeId,
                                           List<String> targetFinishedGoodIds);

    /** Validate the exact workflow/version pinned by an existing production plan. */
    void assertPinnedWorkflowCoversOutputs(String factoryId, Long workflowId, Integer definitionVersion,
                                           List<String> targetFinishedGoodIds);

    /** Resolve and pin the active workflow's terminal reporting-unit contract for a plan. */
    Optional<WorkflowPlanOutputContract> resolveActivePlanOutputContract(
            String factoryId, String ownerProductTypeId, List<String> targetFinishedGoodIds);

    /**
     * Revalidate the exact workflow/version explicitly selected in the plan UI and return its
     * reporting-unit contract. The selection must still be the enabled activation for the owner.
     */
    WorkflowPlanOutputContract resolvePinnedPlanOutputContract(
            String factoryId, String ownerProductTypeId, Long workflowId, Integer definitionVersion,
            List<String> targetFinishedGoodIds);
}
