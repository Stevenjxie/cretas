package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowOutputResolutionDTO;

import java.util.List;
import java.util.Optional;

/**
 * raw-centric 多成品 (2026-07-13): 生产计划「多选成品 → 解析共用 raw workflow」。
 *
 * <p>反向索引 = 运行时扫描已启用 activation 的原料图 nodesJson 终端成品 (不建映射表, 天然一致)。
 */
public interface ProductWorkflowResolutionService {

    /**
     * 给定一组成品 productTypeId, 解析可覆盖它们的已启用 workflow 候选 (只读, 空候选不报错)。
     * <ul>
     *   <li>单选且命中成品自有图 (owner 非原料) → SELF_WORKFLOW, 短路优先。</li>
     *   <li>否则匹配 owner=原料、终端成品 ⊇ 所选 的图 → RAW_OWNED (0/1/N 候选)。</li>
     *   <li>0 候选 → NONE (空列表)。</li>
     * </ul>
     */
    WorkflowOutputResolutionDTO resolveForOutputs(String factoryId, List<String> finishedGoodProductTypeIds);

    /**
     * 写路径守卫: 断言 ownerProductTypeId 当前 enabled activation 指向的 workflow 终端覆盖 targets,
     * 否则抛 409 WORKFLOW_RESOLUTION_NOT_COVERED。用于建计划/转批次防绕过 + 防 activation 窗口期漂移。
     */
    void assertActiveWorkflowCoversOutputs(String factoryId, String ownerProductTypeId,
                                           List<String> targetFinishedGoodIds);

    /** Resolve and pin the active workflow's terminal reporting-unit contract for a plan. */
    Optional<WorkflowPlanOutputContract> resolveActivePlanOutputContract(
            String factoryId, String ownerProductTypeId, List<String> targetFinishedGoodIds);
}
