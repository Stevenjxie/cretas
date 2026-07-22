package com.cretas.aims.service.impl;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowEdge;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.config.ApprovalWorkflowRepository;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * Graph-native 审批工作流服务实现.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-16
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApprovalWorkflowServiceImpl implements ApprovalWorkflowService {

    private final ApprovalWorkflowRepository workflowRepository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private ApprovalWorkflowInstanceRepository workflowInstanceRepository;

    private static final Set<String> VALID_NODE_TYPES = Set.of(
            "start", "approval", "condition", "parallel", "join", "notify", "end");

    private static final Set<String> VALID_JOIN_MODES = Set.of("ALL", "N_OF_M", "ANY");

    // ==================== CRUD ====================

    @Override
    @Transactional
    public ApprovalWorkflow create(String factoryId, ApprovalWorkflow workflow) {
        log.info("创建审批工作流 - factoryId={}, decisionType={}, name={}",
                factoryId, workflow.getDecisionType(), workflow.getName());

        workflow.setFactoryId(factoryId);

        // 唯一性校验
        if (workflowRepository.existsByFactoryIdAndDecisionTypeAndName(
                factoryId, workflow.getDecisionType(), workflow.getName())) {
            throw new BusinessException(409, "同决策类型下工作流名称已存在: " + workflow.getName())
                    .withHint("请使用其他名称, 或编辑现有工作流")
                    .withHintTarget("name");
        }

        // 结构校验
        Map<String, Object> validation = validateGraph(workflow);
        if (!(Boolean) validation.get("valid")) {
            throw new BusinessException(400, "工作流校验失败: " + validation.get("errors"))
                    .withHint("请检查节点与边的引用完整性, 以及 join 节点 mode 字段")
                    .withHintTarget("nodes");
        }

        // 默认值 (Builder.Default 在 @NoArgsConstructor 路径下不生效, Service 显式 fallback)
        if (workflow.getEnabled() == null) workflow.setEnabled(true);
        if (workflow.getVersion() == null) workflow.setVersion(1);
        if (workflow.getPriority() == null) workflow.setPriority(0);
        if (!StringUtils.hasText(workflow.getPublishStatus())) workflow.setPublishStatus("draft");

        ApprovalWorkflow saved = workflowRepository.save(workflow);
        log.info("审批工作流创建成功 - id={}, publishStatus={}", saved.getId(), saved.getPublishStatus());
        return saved;
    }

    @Override
    @Transactional
    public ApprovalWorkflow update(String factoryId, String id, ApprovalWorkflow partial) {
        log.info("更新审批工作流 - factoryId={}, id={}", factoryId, id);

        ApprovalWorkflow existing = workflowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ApprovalWorkflow", id));

        if (!existing.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权修改其他工厂的工作流")
                    .withHint("请切换到该工作流所属的工厂后再操作");
        }
        assertNoRunningInstances(factoryId, id);

        // PATCH 语义: null 字段不动
        if (partial.getName() != null) existing.setName(partial.getName());
        if (partial.getDescription() != null) existing.setDescription(partial.getDescription());
        if (partial.getNodesJson() != null) existing.setNodesJson(partial.getNodesJson());
        if (partial.getEdgesJson() != null) existing.setEdgesJson(partial.getEdgesJson());
        if (partial.getStartNodeId() != null) existing.setStartNodeId(partial.getStartNodeId());
        if (partial.getPriority() != null) existing.setPriority(partial.getPriority());
        if (partial.getEnabled() != null) existing.setEnabled(partial.getEnabled());

        // 结构校验 (在合并后)
        Map<String, Object> validation = validateGraph(existing);
        if (!(Boolean) validation.get("valid")) {
            throw new BusinessException(400, "工作流校验失败: " + validation.get("errors"))
                    .withHint("更新后的 graph 校验未通过")
                    .withHintTarget("nodes");
        }

        // 改动 published workflow → 自动 revert 回 draft (避免线上立即生效)
        if ("published".equals(existing.getPublishStatus())) {
            existing.setPublishStatus("draft");
            existing.setVersion(existing.getVersion() + 1);
            log.info("已发布工作流被修改, 自动 revert 到 draft - id={}, newVersion={}",
                    id, existing.getVersion());
        }

        return workflowRepository.save(existing);
    }

    @Override
    @Transactional
    public void delete(String factoryId, String id) {
        log.info("删除审批工作流 - factoryId={}, id={}", factoryId, id);

        ApprovalWorkflow existing = workflowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ApprovalWorkflow", id));

        if (!existing.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权删除其他工厂的工作流");
        }
        assertNoRunningInstances(factoryId, id);

        // @SQLDelete 在 BaseEntity 触发软删除 (设 deleted_at = NOW())
        workflowRepository.delete(existing);
        log.info("审批工作流软删除 - id={}", id);
    }

    @Override
    public Optional<ApprovalWorkflow> getById(String factoryId, String id) {
        return workflowRepository.findById(id)
                .filter(w -> w.getFactoryId().equals(factoryId));
    }

    @Override
    @Transactional
    public Optional<ApprovalWorkflow> getByIdForUpdate(String factoryId, String id) {
        return workflowRepository.findByFactoryIdAndIdForUpdate(factoryId, id);
    }

    @Override
    public List<ApprovalWorkflow> getAllByFactory(String factoryId) {
        return workflowRepository.findByFactoryIdOrderByDecisionTypeAscPriorityDesc(factoryId);
    }

    @Override
    public List<ApprovalWorkflow> getByDecisionType(String factoryId, DecisionType decisionType) {
        return workflowRepository.findByFactoryIdAndDecisionTypeOrderByPriorityDesc(factoryId, decisionType);
    }

    // ==================== Executor lookup ====================

    @Override
    public Optional<ApprovalWorkflow> getActiveByDecisionType(String factoryId, DecisionType decisionType) {
        List<ApprovalWorkflow> candidates = workflowRepository.findActiveByDecisionType(factoryId, decisionType);
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    // ==================== Lifecycle ====================

    @Override
    @Transactional
    public ApprovalWorkflow publishDraft(String factoryId, String id) {
        log.info("发布审批工作流 - factoryId={}, id={}", factoryId, id);

        ApprovalWorkflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ApprovalWorkflow", id));

        if (!workflow.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权发布其他工厂的工作流");
        }

        if (!"draft".equals(workflow.getPublishStatus())) {
            throw new BusinessException(400, "仅 draft 状态可发布, 当前状态: " + workflow.getPublishStatus())
                    .withHint("如需重新发布, 先 archive 当前版本再创建新 draft");
        }

        // 发布前再次校验
        Map<String, Object> validation = validateGraph(workflow);
        if (!(Boolean) validation.get("valid")) {
            throw new BusinessException(400, "发布前校验失败: " + validation.get("errors"))
                    .withHint("请先修复 graph 错误再发布");
        }

        workflow.setPublishStatus("published");
        return workflowRepository.save(workflow);
    }

    @Override
    @Transactional
    public ApprovalWorkflow archive(String factoryId, String id) {
        log.info("归档审批工作流 - factoryId={}, id={}", factoryId, id);

        ApprovalWorkflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ApprovalWorkflow", id));

        if (!workflow.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权归档其他工厂的工作流");
        }
        assertNoRunningInstances(factoryId, id);

        workflow.setPublishStatus("archived");
        return workflowRepository.save(workflow);
    }

    @Override
    @Transactional
    public ApprovalWorkflow toggleEnabled(String factoryId, String id, boolean enabled) {
        log.info("切换审批工作流状态 - factoryId={}, id={}, enabled={}", factoryId, id, enabled);

        ApprovalWorkflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ApprovalWorkflow", id));

        if (!workflow.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权修改其他工厂的工作流");
        }
        assertNoRunningInstances(factoryId, id);

        workflow.setEnabled(enabled);
        return workflowRepository.save(workflow);
    }

    private void assertNoRunningInstances(String factoryId, String workflowId) {
        if (workflowInstanceRepository != null
                && !workflowInstanceRepository.findByFactoryIdAndWorkflowIdAndStatus(
                        factoryId, workflowId,
                        com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus.RUNNING)
                .isEmpty()) {
            throw new BusinessException(409,
                    "该审批流程仍有进行中的实例，暂不能修改")
                    .withCode("OA_WORKFLOW_RUNNING_INSTANCE_EXISTS")
                    .withHint("请等待所有进行中的审批完成后再修改流程");
        }
    }

    // ==================== JSONB serde ====================

    @Override
    public List<ApprovalWorkflowNode> deserializeNodes(String nodesJson) {
        if (!StringUtils.hasText(nodesJson)) return List.of();
        try {
            return objectMapper.readValue(nodesJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.error("反序列化 nodesJson 失败: {}", e.getMessage());
            throw new BusinessException(400, "nodes JSON 格式错误: " + e.getOriginalMessage())
                    .withHint("nodes_json 必须是 JSON array of ApprovalWorkflowNode")
                    .withHintTarget("nodes");
        }
    }

    @Override
    public List<ApprovalWorkflowEdge> deserializeEdges(String edgesJson) {
        if (!StringUtils.hasText(edgesJson)) return List.of();
        try {
            return objectMapper.readValue(edgesJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.error("反序列化 edgesJson 失败: {}", e.getMessage());
            throw new BusinessException(400, "edges JSON 格式错误: " + e.getOriginalMessage())
                    .withHint("edges_json 必须是 JSON array of ApprovalWorkflowEdge")
                    .withHintTarget("edges");
        }
    }

    @Override
    public String serializeNodes(List<ApprovalWorkflowNode> nodes) {
        try {
            return objectMapper.writeValueAsString(nodes == null ? List.of() : nodes);
        } catch (JsonProcessingException e) {
            log.error("序列化 nodes 失败: {}", e.getMessage());
            throw new BusinessException(500, "序列化 nodes 失败: " + e.getOriginalMessage());
        }
    }

    @Override
    public String serializeEdges(List<ApprovalWorkflowEdge> edges) {
        try {
            return objectMapper.writeValueAsString(edges == null ? List.of() : edges);
        } catch (JsonProcessingException e) {
            log.error("序列化 edges 失败: {}", e.getMessage());
            throw new BusinessException(500, "序列化 edges 失败: " + e.getOriginalMessage());
        }
    }

    // ==================== Validation ====================

    @Override
    public Map<String, Object> validateGraph(ApprovalWorkflow workflow) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (workflow == null) {
            errors.add("workflow 为 null");
            return result(false, errors, warnings);
        }

        if (!StringUtils.hasText(workflow.getNodesJson())) {
            errors.add("nodesJson 不能为空");
            return result(false, errors, warnings);
        }
        if (!StringUtils.hasText(workflow.getEdgesJson())) {
            errors.add("edgesJson 不能为空 (允许 [], 但不能 null)");
            return result(false, errors, warnings);
        }

        List<ApprovalWorkflowNode> nodes;
        List<ApprovalWorkflowEdge> edges;
        try {
            nodes = deserializeNodes(workflow.getNodesJson());
            edges = deserializeEdges(workflow.getEdgesJson());
        } catch (BusinessException e) {
            errors.add(e.getMessage());
            return result(false, errors, warnings);
        }

        // 节点列表非空
        if (nodes.isEmpty()) {
            errors.add("节点列表不能为空");
            return result(false, errors, warnings);
        }

        // 收集 nodeId, 检查唯一 + 收集 type 分布
        Map<String, ApprovalWorkflowNode> nodesById = new HashMap<>();
        int startCount = 0, endCount = 0;
        for (ApprovalWorkflowNode n : nodes) {
            if (!StringUtils.hasText(n.getId())) {
                errors.add("节点 ID 不能为空");
                continue;
            }
            if (nodesById.containsKey(n.getId())) {
                errors.add("节点 ID 重复: " + n.getId());
                continue;
            }
            nodesById.put(n.getId(), n);
            if (!VALID_NODE_TYPES.contains(n.getType())) {
                errors.add("节点 " + n.getId() + " 类型非法: " + n.getType() +
                        " (合法: " + VALID_NODE_TYPES + ")");
            }
            if ("start".equals(n.getType())) startCount++;
            if ("end".equals(n.getType())) endCount++;
            // join 节点 mode 字段校验
            if ("join".equals(n.getType())) {
                Object mode = n.getConfig() == null ? null : n.getConfig().get("mode");
                if (mode == null || !VALID_JOIN_MODES.contains(mode.toString())) {
                    errors.add("join 节点 " + n.getId() + " mode 字段非法: " + mode +
                            " (合法: " + VALID_JOIN_MODES + ")");
                }
                if ("N_OF_M".equals(String.valueOf(mode))
                        && !(n.getConfig().get("n") instanceof Number)) {
                    errors.add("join 节点 " + n.getId() + " mode=N_OF_M 需要 config.n (integer)");
                }
            }
        }

        if (startCount == 0) errors.add("必须有至少一个 start 节点");
        if (startCount > 1) errors.add("只能有一个 start 节点, 当前: " + startCount);
        if (endCount == 0) errors.add("必须有至少一个 end 节点");

        // startNodeId 必须存在
        if (!StringUtils.hasText(workflow.getStartNodeId())) {
            errors.add("startNodeId 不能为空");
        } else if (!nodesById.containsKey(workflow.getStartNodeId())) {
            errors.add("startNodeId 引用的节点不存在: " + workflow.getStartNodeId());
        } else if (!"start".equals(nodesById.get(workflow.getStartNodeId()).getType())) {
            errors.add("startNodeId 指向的节点不是 start 类型");
        }

        // edge 引用合法 + 收集 incoming 用于孤立节点检测
        Set<String> hasIncoming = new HashSet<>();
        Set<String> hasOutgoing = new HashSet<>();
        for (ApprovalWorkflowEdge e : edges) {
            if (!nodesById.containsKey(e.getSource())) {
                errors.add("边 " + e.getId() + " source 引用不存在的节点: " + e.getSource());
            } else {
                hasOutgoing.add(e.getSource());
            }
            if (!nodesById.containsKey(e.getTarget())) {
                errors.add("边 " + e.getId() + " target 引用不存在的节点: " + e.getTarget());
            } else {
                hasIncoming.add(e.getTarget());
            }
        }

        // 孤立节点: 除 start 外, 必须有 incoming
        for (ApprovalWorkflowNode n : nodes) {
            if ("start".equals(n.getType())) continue;
            if (!hasIncoming.contains(n.getId())) {
                warnings.add("节点 " + n.getId() + " 没有入边, 无法到达");
            }
        }
        // end 节点不应有 outgoing
        for (ApprovalWorkflowNode n : nodes) {
            if ("end".equals(n.getType()) && hasOutgoing.contains(n.getId())) {
                errors.add("end 节点 " + n.getId() + " 不应有出边");
            }
        }

        // 环检测 (DFS, Day 3 简易版 — 完整 SCC 留 Day 4)
        if (errors.isEmpty() && hasCycle(nodes, edges)) {
            errors.add("graph 存在环 (DAG topology 要求)");
        }

        return result(errors.isEmpty(), errors, warnings);
    }

    /** DFS 环检测 — 检查从 start 可达的子图. */
    private boolean hasCycle(List<ApprovalWorkflowNode> nodes, List<ApprovalWorkflowEdge> edges) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (ApprovalWorkflowEdge e : edges) {
            adjacency.computeIfAbsent(e.getSource(), k -> new ArrayList<>()).add(e.getTarget());
        }
        Set<String> visited = new HashSet<>();
        Set<String> recStack = new HashSet<>();
        for (ApprovalWorkflowNode n : nodes) {
            if (dfsHasCycle(n.getId(), adjacency, visited, recStack)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfsHasCycle(String nodeId, Map<String, List<String>> adjacency,
                                Set<String> visited, Set<String> recStack) {
        if (recStack.contains(nodeId)) return true;
        if (visited.contains(nodeId)) return false;
        visited.add(nodeId);
        recStack.add(nodeId);
        for (String next : adjacency.getOrDefault(nodeId, List.of())) {
            if (dfsHasCycle(next, adjacency, visited, recStack)) return true;
        }
        recStack.remove(nodeId);
        return false;
    }

    private Map<String, Object> result(boolean valid, List<String> errors, List<String> warnings) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", valid);
        m.put("errors", errors);
        m.put("warnings", warnings);
        return m;
    }

    // ==================== 统计 ====================

    @Override
    public Map<DecisionType, Long> getConfigStatistics(String factoryId) {
        List<Object[]> raw = workflowRepository.countByFactoryIdGroupByDecisionType(factoryId);
        Map<DecisionType, Long> result = new EnumMap<>(DecisionType.class);
        for (Object[] row : raw) {
            result.put((DecisionType) row[0], (Long) row[1]);
        }
        return result;
    }
}
