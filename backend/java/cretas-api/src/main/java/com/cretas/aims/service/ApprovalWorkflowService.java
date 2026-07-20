package com.cretas.aims.service;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowEdge;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Graph-native 审批工作流服务.
 *
 * <p>Sprint 3 Track-I (C-APPROVAL-EDITOR-1) 引入 — 跟 {@link ApprovalChainService}
 * 互补:
 * <ul>
 *   <li>ApprovalChainService manages flat-list ApprovalChainConfig (legacy)</li>
 *   <li>ApprovalWorkflowService manages graph ApprovalWorkflow (new)</li>
 * </ul>
 *
 * <p>ApprovalChainService.requiresApproval() dual-source 读取: 先查
 * {@link #getActiveByDecisionType}, 找到则走 graph executor; 否则 fallback
 * 到 ApprovalChainConfig flat-list 逻辑.
 *
 * @since 2026-05-16
 */
public interface ApprovalWorkflowService {

    // ==================== CRUD ====================

    ApprovalWorkflow create(String factoryId, ApprovalWorkflow workflow);

    ApprovalWorkflow update(String factoryId, String id, ApprovalWorkflow partial);

    void delete(String factoryId, String id);

    Optional<ApprovalWorkflow> getById(String factoryId, String id);

    /** Exact pessimistic lock for validated-definition instance creation. */
    Optional<ApprovalWorkflow> getByIdForUpdate(String factoryId, String id);

    List<ApprovalWorkflow> getAllByFactory(String factoryId);

    List<ApprovalWorkflow> getByDecisionType(String factoryId, DecisionType decisionType);

    // ==================== Lookup (executor 用) ====================

    /**
     * 找当前 active workflow.
     *
     * <p>Filter: {@code publishStatus='published'} + {@code enabled=true}.
     * <p>Ordering: priority DESC, version DESC. 取第一个.
     *
     * @return Optional.empty() 表示没有 active workflow, caller 应 fallback 到 flat-list
     */
    Optional<ApprovalWorkflow> getActiveByDecisionType(String factoryId, DecisionType decisionType);

    // ==================== Lifecycle ====================

    /** Draft → Published (transactional). */
    ApprovalWorkflow publishDraft(String factoryId, String id);

    /** Published → Archived (transactional). */
    ApprovalWorkflow archive(String factoryId, String id);

    /** Toggle enabled flag (PATCH semantics). */
    ApprovalWorkflow toggleEnabled(String factoryId, String id, boolean enabled);

    // ==================== JSONB 序列化 helpers ====================

    /** Deserialize {@code nodesJson} String → List. */
    List<ApprovalWorkflowNode> deserializeNodes(String nodesJson);

    /** Deserialize {@code edgesJson} String → List. */
    List<ApprovalWorkflowEdge> deserializeEdges(String edgesJson);

    /** Serialize List → JSON String (for setNodesJson). */
    String serializeNodes(List<ApprovalWorkflowNode> nodes);

    /** Serialize List → JSON String (for setEdgesJson). */
    String serializeEdges(List<ApprovalWorkflowEdge> edges);

    // ==================== Validation ====================

    /**
     * 校验 graph 结构 + SpEL syntax.
     *
     * <p>Checks:
     * <ul>
     *   <li>≥ 1 节点, ≥ 1 type=start 节点 且 startNodeId 匹配</li>
     *   <li>≥ 1 type=end 节点</li>
     *   <li>所有 edge source/target 引用合法 nodeId</li>
     *   <li>无孤立节点 (除 start)</li>
     *   <li>无环 (DAG topology)</li>
     *   <li>join 节点 mode 字段合法 (ALL/N_OF_M/ANY)</li>
     *   <li>condition SpEL 语法基础合法 (轻量 parse)</li>
     * </ul>
     *
     * @return {@code {valid: boolean, errors: List<String>, warnings: List<String>}}
     */
    Map<String, Object> validateGraph(ApprovalWorkflow workflow);

    // ==================== 统计 ====================

    Map<DecisionType, Long> getConfigStatistics(String factoryId);
}
