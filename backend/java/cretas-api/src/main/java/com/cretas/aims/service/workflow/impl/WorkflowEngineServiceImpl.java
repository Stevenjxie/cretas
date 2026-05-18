package com.cretas.aims.service.workflow.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowEdge;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.workflow.ApprovalHistory;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.workflow.ApprovalHistoryRepository;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator.SpelEvaluationFailure;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Workflow engine 实现 — Phase 1 B.4 完整 DAG executor.
 *
 * <p><b>B.4 实现内容</b>:
 * <ul>
 *   <li>{@link #transitionNode} — 完整 DAG advance (APPROVE/REJECT/SKIP/DELEGATE/TIMEOUT)
 *       带 condition/parallel/join/notify 评估</li>
 *   <li>{@link #cancel} — set CANCELLED + completedAt + history + Redis TTL</li>
 *   <li>{@link #walkToFirstHumanNode} — 修正为评估 SpEL condition (跟 autoAdvance 共用 logic)</li>
 *   <li>Redis TTL split — RUNNING persistent / 终态 24h</li>
 *   <li>moduleCode → DecisionType 静态 map (Phase B 扩展更友好)</li>
 * </ul>
 *
 * <p><b>不在 B.4 scope</b> (留给后续 phase):
 * <ul>
 *   <li>超时 escalation — Phase 2</li>
 *   <li>Delegation 实际转移 — B.5 加字段, B.4 仅持久化</li>
 *   <li>真实推送 — Phase 3 (NOTIFY 节点只写 history)</li>
 * </ul>
 *
 * <p><b>Redis 策略</b>: {@code RedisTemplate<String, Object>} ({@code redisObjectTemplate}
 * bean in CacheConfig) 配置 GenericJackson2JsonRedisSerializer, 可直接序列化
 * {@link ApprovalWorkflowInstance}. Redis fail-open: 操作失败仅 log warning,
 * PG 是 source of truth.
 *
 * @since 2026-05-18 (B.3 skeleton) / 2026-05-18 (B.4 DAG executor)
 */
@Service
@Slf4j
public class WorkflowEngineServiceImpl implements WorkflowEngineService {

    /** Redis key 前缀 — {@code aw:instance:{instanceId}}. */
    private static final String REDIS_KEY_PREFIX = "aw:instance:";

    /** Redis TTL — 终态后 24h 自动清理 (PG 保留作历史). */
    private static final Duration REDIS_TERMINAL_TTL = Duration.ofHours(24);

    /**
     * moduleCode → DecisionType registry.
     *
     * <p>Phase 1: PURCHASE_ORDER / SALES_ORDER. Phase B+ 扩展时直接加 entry,
     * 不必触碰 {@link #lookupDecisionType} 方法 body.
     */
    private static final Map<String, DecisionType> MODULE_TO_DECISION = Map.of(
            "PURCHASE_ORDER", DecisionType.PURCHASE_ORDER_APPROVAL,
            "SALES_ORDER", DecisionType.SALES_ORDER_APPROVAL
    );

    /** Walk hop 上限 — 防御性 (graph validation 已禁环, 此处兜底). */
    private static final int MAX_WALK_HOPS = 200;

    private final ApprovalWorkflowInstanceRepository instanceRepository;
    private final ApprovalHistoryRepository historyRepository;
    private final ApprovalWorkflowService workflowService;
    private final SandboxedSpelEvaluator spelEvaluator;

    /**
     * Optional Redis — 当 RedisAutoConfiguration excluded (e.g. local pg profile)
     * 时为 NULL. 所有 Redis 操作 null-guard, fail-open.
     */
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    public WorkflowEngineServiceImpl(
            ApprovalWorkflowInstanceRepository instanceRepository,
            ApprovalHistoryRepository historyRepository,
            ApprovalWorkflowService workflowService,
            SandboxedSpelEvaluator spelEvaluator,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RedisTemplate<String, Object> redisTemplate) {
        this.instanceRepository = instanceRepository;
        this.historyRepository = historyRepository;
        this.workflowService = workflowService;
        this.spelEvaluator = spelEvaluator;
        this.redisTemplate = redisTemplate;
    }

    // ==================== Public API ====================

    @Override
    @Transactional
    public ApprovalWorkflowInstance startWorkflow(String factoryId,
                                                  String moduleCode,
                                                  String businessEntityId,
                                                  Map<String, Object> contextJson,
                                                  Long initiatorUserId) {
        Objects.requireNonNull(factoryId, "factoryId must not be null");
        Objects.requireNonNull(moduleCode, "moduleCode must not be null");
        Objects.requireNonNull(businessEntityId, "businessEntityId must not be null");

        // 1. moduleCode → DecisionType
        DecisionType decisionType = lookupDecisionType(moduleCode);

        // 2. 取 active workflow
        ApprovalWorkflow workflow = workflowService
                .getActiveByDecisionType(factoryId, decisionType)
                .orElseThrow(() -> new IllegalArgumentException(String.format(
                        "无 active 审批工作流 — factoryId=%s, moduleCode=%s, decisionType=%s. " +
                        "请在 Canvas 审批 Tab 中创建并发布 workflow.",
                        factoryId, moduleCode, decisionType)));

        // 3. 解析 graph
        List<ApprovalWorkflowNode> nodes = workflowService.deserializeNodes(workflow.getNodesJson());
        List<ApprovalWorkflowEdge> edges = workflowService.deserializeEdges(workflow.getEdgesJson());

        // 4. 创建实例 — UUID id
        String instanceId = UUID.randomUUID().toString();
        Map<String, Object> safeContext = contextJson == null
                ? new HashMap<>()
                : new HashMap<>(contextJson);

        ApprovalWorkflowInstance instance = ApprovalWorkflowInstance.builder()
                .id(instanceId)
                .factoryId(factoryId)
                .workflowId(workflow.getId())
                .moduleCode(moduleCode)
                .businessEntityId(businessEntityId)
                .status(InstanceStatus.RUNNING)
                .currentNodeIds(new ArrayList<>())
                .contextJson(safeContext)
                .initiatedBy(initiatorUserId)
                .initiatedAt(LocalDateTime.now())
                .build();

        // 5. Walk from startNode → 第一个 approval/end 节点 (评估 condition).
        //    walkToFirstHumanNode 内部通过 autoAdvance 副作用更新 instance.currentNodeIds
        //    + 在 end 节点时设 status. 此处不需要再 add — 直接让副作用 settle.
        GraphIndex graph = indexGraph(nodes, edges);
        String firstActiveNodeId = walkToFirstHumanNode(workflow.getStartNodeId(), graph, instance);

        // 6. 持久化 PG — 捕获 partial-unique-index 冲突 (issue #11):
        //    同一 (factory, module, business_entity) 已有 RUNNING 实例时, partial
        //    unique index uq_aw_instances_active 会拒绝 INSERT, 抛 DataIntegrityViolationException.
        //    Caller 应先 getCurrentInstance(...) 检查; 此处兜底防 race.
        ApprovalWorkflowInstance saved;
        try {
            saved = instanceRepository.save(instance);
        } catch (DataIntegrityViolationException e) {
            log.warn("startWorkflow 唯一约束冲突 - factoryId={}, moduleCode={}, " +
                            "businessEntityId={}, error={}",
                    factoryId, moduleCode, businessEntityId, e.getMessage());
            throw new BusinessException(409,
                    "审批流程已存在, 不能重复启动")
                    .withHint("该业务单据已有进行中的审批实例, 请先在审批列表中查看或取消现有流程");
        }

        // 7. 写入 Redis (fail-open)
        writeRedis(saved);

        // 8. 写 START history
        writeHistory(saved.getFactoryId(), saved.getId(),
                workflow.getStartNodeId() == null ? "start" : workflow.getStartNodeId(),
                HistoryAction.START, null, null,
                "workflow 启动 — businessEntityId=" + businessEntityId, null);

        log.info("启动 workflow 实例 - instanceId={}, factoryId={}, moduleCode={}, " +
                        "businessEntityId={}, workflowId={}, firstActive={}, status={}",
                saved.getId(), factoryId, moduleCode, businessEntityId,
                workflow.getId(), firstActiveNodeId, saved.getStatus());

        return saved;
    }

    @Override
    @Transactional
    public ApprovalWorkflowInstance transitionNode(String instanceId,
                                                   Long actorId,
                                                   String actorRole,
                                                   HistoryAction action,
                                                   String notes) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(action, "action must not be null");

        // 1. Load instance — Redis miss → PG
        ApprovalWorkflowInstance instance = loadInstanceOrFail(instanceId);

        if (instance.getStatus() != InstanceStatus.RUNNING) {
            throw new BusinessException(409,
                    "工作流实例已结束, 不可再 transition. 当前 status=" + instance.getStatus());
        }

        // 2. Identify the "from" node — 当前 active 节点中能匹配 action 的第一个 approval 节点.
        //    Phase 1 简化: 默认取 currentNodeIds[0] 作为 fromNodeId (caller 应只在 active node 调 transition).
        //    并行场景 caller 应明确 fromNodeId (B.5 frontend 携带). 暂以 head 作 fallback.
        if (instance.getCurrentNodeIds().isEmpty()) {
            throw new BusinessException(409,
                    "工作流实例无 active 节点, 不能 transition. instanceId=" + instanceId);
        }
        String fromNodeId = instance.getCurrentNodeIds().get(0);

        // 3. 取 workflow + 索引 graph
        ApprovalWorkflow workflow = workflowService.getById(instance.getFactoryId(), instance.getWorkflowId())
                .orElseThrow(() -> new BusinessException(404,
                        "工作流配置不存在 — workflowId=" + instance.getWorkflowId()));
        List<ApprovalWorkflowNode> nodes = workflowService.deserializeNodes(workflow.getNodesJson());
        List<ApprovalWorkflowEdge> edges = workflowService.deserializeEdges(workflow.getEdgesJson());
        GraphIndex graph = indexGraph(nodes, edges);

        ApprovalWorkflowNode fromNode = graph.nodesById.get(fromNodeId);
        if (fromNode == null) {
            throw new BusinessException(500,
                    "current active node 找不到对应 workflow node — nodeId=" + fromNodeId);
        }

        // 4. 写 transition history (含 durationSeconds 从前一 history 推算)
        Integer durationSeconds = computeDurationFromPrevious(
                instance.getFactoryId(), instanceId, instance.getInitiatedAt());
        writeHistory(instance.getFactoryId(), instanceId, fromNodeId,
                action, actorId, actorRole, notes, durationSeconds);

        // 5. 根据 action 处理 transition
        switch (action) {
            case REJECT:
                handleReject(instance, graph, fromNodeId);
                break;
            case APPROVE:
            case SKIP:
            case DELEGATE:
            case TIMEOUT:
            case AUTO_TRANSITION:
                // 这些都视为 "可继续" — DELEGATE 在 B.4 持久化 history 即可, 不实际转移人.
                // SKIP / TIMEOUT 同 APPROVE 走 outgoing.
                handleApproveLike(instance, graph, fromNodeId);
                break;
            case CANCEL:
            case START:
                throw new BusinessException(400,
                        "不支持的 transition action — " + action + " (CANCEL 走 cancel(), START 仅启动时)");
            default:
                throw new BusinessException(400, "未知 action — " + action);
        }

        // 6. 保存 PG + Redis — 捕获并发审批冲突 (issue #12)
        ApprovalWorkflowInstance saved;
        try {
            saved = instanceRepository.save(instance);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("并发 transition 冲突 - instanceId={}, actor={}, action={}, error={}",
                    instanceId, actorId, action, e.getMessage());
            throw new BusinessException(409, "并发审批冲突, 请刷新后重试")
                    .withHint("另一审批人已操作此实例, 请刷新页面查看最新状态后再继续");
        }
        writeRedis(saved);

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveWorkflow(String factoryId, String moduleCode) {
        // Phase 1 hotfix 2026-05-18: 让 caller (PurchaseServiceImpl 等) 预检 active workflow
        // 存在性, 避免触发 startWorkflow 的 orElseThrow 路径. Spring 在 @Transactional 方法内
        // 抛 unchecked exception 时即使 caller catch 也会 mark outer 事务 rollback-only,
        // 导致 PG commit 时 UnexpectedRollbackException. 预检模式避开此陷阱.
        try {
            DecisionType dt = lookupDecisionType(moduleCode);
            return workflowService.getActiveByDecisionType(factoryId, dt).isPresent();
        } catch (IllegalArgumentException unmappedModuleCode) {
            // moduleCode 未在 MODULE_TO_DECISION registry 里 — 视为无 workflow, caller 走 legacy.
            log.debug("hasActiveWorkflow: moduleCode={} 未映射, 视为无 workflow", moduleCode);
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ApprovalWorkflowInstance> getCurrentInstance(String factoryId,
                                                                 String moduleCode,
                                                                 String businessEntityId) {
        return instanceRepository.findByFactoryIdAndModuleCodeAndBusinessEntityId(
                factoryId, moduleCode, businessEntityId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalHistory> getHistory(String factoryId, String instanceId) {
        return historyRepository.findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(
                factoryId, instanceId);
    }

    @Override
    public boolean evaluateCondition(String spelExpression, Map<String, Object> context) {
        if (spelExpression == null || spelExpression.isBlank()) {
            // 空 condition = 总是走 (per ApprovalWorkflowEdge javadoc).
            return true;
        }
        try {
            // SpEL #context.xxx — variables map 单 key 'context' 绑定整 map.
            // 同时把 context 的顶层 entries 也作为 #key 暴露, 兼容 #amount 直写.
            Map<String, Object> variables = new HashMap<>();
            Map<String, Object> safeCtx = context == null ? Map.of() : context;
            variables.put("context", safeCtx);
            for (Map.Entry<String, Object> entry : safeCtx.entrySet()) {
                // 顶层 spread — caller 可写 #amount 或 #context.amount, 都 work.
                variables.putIfAbsent(entry.getKey(), entry.getValue());
            }
            return spelEvaluator.evaluateBoolean(spelExpression, variables);
        } catch (SpelEvaluationFailure e) {
            log.warn("SpEL 评估失败, 视为 false - spel={}, error={}",
                    spelExpression, e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional
    public ApprovalWorkflowInstance cancel(String instanceId,
                                           Long cancellerUserId,
                                           String reason) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");

        ApprovalWorkflowInstance instance = loadInstanceOrFail(instanceId);

        if (instance.getStatus() != InstanceStatus.RUNNING) {
            throw new BusinessException(409,
                    "工作流实例已结束, 不可取消. 当前 status=" + instance.getStatus());
        }

        // Write CANCEL history before mutating instance, capture fromNode for audit.
        String nodeIdForHistory = instance.getCurrentNodeIds().isEmpty()
                ? "cancel"
                : instance.getCurrentNodeIds().get(0);
        Integer durationSeconds = computeDurationFromPrevious(
                instance.getFactoryId(), instanceId, instance.getInitiatedAt());
        writeHistory(instance.getFactoryId(), instanceId, nodeIdForHistory,
                HistoryAction.CANCEL, cancellerUserId, null, reason, durationSeconds);

        // Mutate
        instance.setStatus(InstanceStatus.CANCELLED);
        instance.setCompletedAt(LocalDateTime.now());
        instance.setCurrentNodeIds(new ArrayList<>());

        // Save PG — 捕获并发冲突 (issue #12)
        ApprovalWorkflowInstance saved;
        try {
            saved = instanceRepository.save(instance);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("并发 cancel 冲突 - instanceId={}, canceller={}, error={}",
                    instanceId, cancellerUserId, e.getMessage());
            throw new BusinessException(409, "并发审批冲突, 请刷新后重试")
                    .withHint("另一审批人已操作此实例, 请刷新页面查看最新状态后再继续");
        }
        writeRedis(saved);

        log.info("取消 workflow 实例 - instanceId={}, canceller={}, reason={}",
                instanceId, cancellerUserId, reason);
        return saved;
    }

    /**
     * 启动 hook — 从 PG 拉所有 RUNNING 实例重建 Redis state.
     */
    @Override
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildRedisFromPg() {
        if (redisTemplate == null) {
            log.info("Redis 未配置, 跳过 workflow 实例重建");
            return;
        }

        try {
            List<ApprovalWorkflowInstance> runningInstances =
                    instanceRepository.findByStatus(InstanceStatus.RUNNING);

            int restored = 0;
            for (ApprovalWorkflowInstance instance : runningInstances) {
                try {
                    writeRedis(instance);
                    restored++;
                } catch (Exception inner) {
                    log.warn("重建单个实例失败 (跳过) - instanceId={}, error={}",
                            instance.getId(), inner.getMessage());
                }
            }
            log.info("启动重建 Redis workflow 实例 - 总数={}, 成功={}",
                    runningInstances.size(), restored);
        } catch (Exception e) {
            // Fail-open — 重建失败不阻塞 Spring 启动.
            log.error("Redis workflow 实例重建失败, 业务降级到 PG 直读 - error={}",
                    e.getMessage(), e);
        }
    }

    // ==================== B.4 — DAG advance logic ====================

    /**
     * 处理 APPROVE-like action (APPROVE/SKIP/DELEGATE/TIMEOUT/AUTO_TRANSITION):
     * 从 fromNodeId outgoing edges 评估 SpEL 选目标, 然后 autoAdvance 跳过自动节点.
     */
    private void handleApproveLike(ApprovalWorkflowInstance instance, GraphIndex graph, String fromNodeId) {
        // 1. 评估 fromNode 的 outgoing edges
        List<ApprovalWorkflowEdge> outgoing = graph.outgoingByNode.getOrDefault(fromNodeId, List.of());
        List<String> matchedTargets = evaluateOutgoingEdges(outgoing, instance, "APPROVE", fromNodeId);

        if (matchedTargets.isEmpty()) {
            throw new IllegalStateException(
                    "workflow misconfigured: 节点 " + fromNodeId + " 无匹配 outgoing edge " +
                    "(共 " + outgoing.size() + " 条 outgoing, 全部 condition 为 false 且无 DEFAULT)");
        }

        // 2. Remove fromNode from currentNodeIds
        instance.getCurrentNodeIds().remove(fromNodeId);

        // 3. 对每个目标 autoAdvance (parallel fan-out 自然处理)
        for (String target : matchedTargets) {
            autoAdvance(instance, graph, target);
        }

        // 4. 如果 currentNodeIds 全空且 status 仍 RUNNING → 不应发生 (autoAdvance 会到 end).
        //    防御性: 终态化为 APPROVED.
        if (instance.getStatus() == InstanceStatus.RUNNING && instance.getCurrentNodeIds().isEmpty()) {
            log.warn("transition 后 currentNodeIds 空但 status=RUNNING, 视为 APPROVED 终态 - instanceId={}",
                    instance.getId());
            instance.setStatus(InstanceStatus.APPROVED);
            instance.setCompletedAt(LocalDateTime.now());
        }
    }

    /**
     * 处理 REJECT action: 找 REJECTED 分支 (edge label=REJECTED 或 condition=#decision=='REJECT'),
     * 若无 → 终态 REJECTED.
     */
    private void handleReject(ApprovalWorkflowInstance instance, GraphIndex graph, String fromNodeId) {
        List<ApprovalWorkflowEdge> outgoing = graph.outgoingByNode.getOrDefault(fromNodeId, List.of());
        // 先找显式 REJECTED label 的 edge
        ApprovalWorkflowEdge rejectEdge = null;
        for (ApprovalWorkflowEdge e : outgoing) {
            if ("REJECTED".equalsIgnoreCase(e.getLabel())) {
                rejectEdge = e;
                break;
            }
        }
        // 否则评估 condition 时把 #decision='REJECT' 放进 context
        if (rejectEdge == null) {
            for (ApprovalWorkflowEdge e : outgoing) {
                if (e.getCondition() != null && !e.getCondition().isBlank()) {
                    Map<String, Object> rejCtx = new HashMap<>(instance.getContextJson());
                    rejCtx.put("decision", "REJECT");
                    if (evaluateCondition(e.getCondition(), rejCtx)) {
                        rejectEdge = e;
                        break;
                    }
                }
            }
        }

        instance.getCurrentNodeIds().remove(fromNodeId);

        if (rejectEdge == null) {
            // No reject branch → terminate REJECTED
            instance.setStatus(InstanceStatus.REJECTED);
            instance.setCompletedAt(LocalDateTime.now());
            instance.setCurrentNodeIds(new ArrayList<>());
            log.info("REJECT 无显式分支 → 终态 REJECTED - instanceId={}, fromNodeId={}",
                    instance.getId(), fromNodeId);
            return;
        }

        // Walk reject branch via autoAdvance
        autoAdvance(instance, graph, rejectEdge.getTarget());
    }

    /**
     * Auto-advance from {@code nodeId} skipping non-human nodes.
     *
     * <p>Walks the graph: at each non-approval / non-end node, evaluates outgoing
     * edges and recurses. Stops at approval (added to currentNodeIds) or end (terminates).
     */
    private void autoAdvance(ApprovalWorkflowInstance instance, GraphIndex graph, String nodeId) {
        autoAdvance(instance, graph, nodeId, 0);
    }

    private void autoAdvance(ApprovalWorkflowInstance instance, GraphIndex graph,
                             String nodeId, int hop) {
        if (instance.getStatus() != InstanceStatus.RUNNING) return;
        if (hop > MAX_WALK_HOPS) {
            log.error("autoAdvance hop 超限 (>{}), 停止 - instanceId={}, lastNode={}",
                    MAX_WALK_HOPS, instance.getId(), nodeId);
            return;
        }
        ApprovalWorkflowNode node = graph.nodesById.get(nodeId);
        if (node == null) {
            log.error("autoAdvance 遇未知 nodeId - instanceId={}, nodeId={}",
                    instance.getId(), nodeId);
            return;
        }
        String type = node.getType() == null ? "" : node.getType();

        switch (type) {
            case "approval":
                // Pause for human action.
                if (!instance.getCurrentNodeIds().contains(nodeId)) {
                    instance.getCurrentNodeIds().add(nodeId);
                }
                log.info("autoAdvance 停在 approval 节点 - instanceId={}, nodeId={}",
                        instance.getId(), nodeId);
                return;

            case "end":
                terminateAtEnd(instance, node);
                return;

            case "start": {
                // 单 outgoing, no condition.
                List<ApprovalWorkflowEdge> outs = graph.outgoingByNode.getOrDefault(nodeId, List.of());
                if (outs.isEmpty()) {
                    log.warn("start 节点无 outgoing - instanceId={}", instance.getId());
                    return;
                }
                autoAdvance(instance, graph, outs.get(0).getTarget(), hop + 1);
                return;
            }

            case "condition": {
                List<ApprovalWorkflowEdge> outs = graph.outgoingByNode.getOrDefault(nodeId, List.of());
                List<String> matched = evaluateOutgoingEdges(outs, instance, null, nodeId);
                if (matched.isEmpty()) {
                    throw new IllegalStateException(
                            "workflow misconfigured: condition 节点 " + nodeId +
                            " 所有 outgoing 均不匹配且无 DEFAULT");
                }
                // condition 只取第一个 (按 priority sorted)
                autoAdvance(instance, graph, matched.get(0), hop + 1);
                return;
            }

            case "parallel": {
                List<ApprovalWorkflowEdge> outs = graph.outgoingByNode.getOrDefault(nodeId, List.of());
                if (outs.isEmpty()) {
                    log.warn("parallel 节点无 outgoing - instanceId={}, nodeId={}",
                            instance.getId(), nodeId);
                    return;
                }
                // Fan-out 全部分支, 不评估 condition.
                for (ApprovalWorkflowEdge e : outs) {
                    autoAdvance(instance, graph, e.getTarget(), hop + 1);
                }
                return;
            }

            case "join":
                handleJoinArrival(instance, graph, node, hop);
                return;

            case "notify": {
                // 写 NOTIFY history (action=AUTO_TRANSITION, notes 描述 channels+recipients).
                String notifyDesc = describeNotify(node);
                writeHistory(instance.getFactoryId(), instance.getId(), nodeId,
                        HistoryAction.AUTO_TRANSITION, null, null,
                        "notify: " + notifyDesc, null);
                List<ApprovalWorkflowEdge> outs = graph.outgoingByNode.getOrDefault(nodeId, List.of());
                if (outs.isEmpty()) {
                    log.warn("notify 节点无 outgoing - instanceId={}, nodeId={}",
                            instance.getId(), nodeId);
                    return;
                }
                autoAdvance(instance, graph, outs.get(0).getTarget(), hop + 1);
                return;
            }

            default:
                log.error("autoAdvance 未知节点 type - instanceId={}, nodeId={}, type={}",
                        instance.getId(), nodeId, type);
        }
    }

    /**
     * Join 节点处理: 检查上游完成情况.
     *
     * <ul>
     *   <li>ALL — 全部上游 source 都已有 terminal history (APPROVE/REJECT/AUTO_TRANSITION)</li>
     *   <li>ANY — 至少 1 个上游已完成</li>
     *   <li>N_OF_M — config.requiredCount (或 ceil(size/2)) 个上游已完成</li>
     * </ul>
     *
     * <p>满足 → 上游 nodeIds 从 currentNodeIds 移除 (它们汇合到 join 了), 推进 join 的 outgoing.
     * 不满足 → join 不加入 currentNodeIds (waiting), 静默返回.
     */
    private void handleJoinArrival(ApprovalWorkflowInstance instance, GraphIndex graph,
                                   ApprovalWorkflowNode joinNode, int hop) {
        String joinId = joinNode.getId();
        Map<String, Object> config = joinNode.getConfig() == null ? Map.of() : joinNode.getConfig();
        String mode = String.valueOf(config.getOrDefault("mode", "ALL")).toUpperCase(Locale.ROOT);

        List<ApprovalWorkflowEdge> incoming = graph.incomingByNode.getOrDefault(joinId, List.of());
        Set<String> upstreamNodeIds = new LinkedHashSet<>();
        for (ApprovalWorkflowEdge e : incoming) {
            upstreamNodeIds.add(e.getSource());
        }

        // 查询 history: 哪些 upstream node 已完成 (有终态 action — APPROVE/REJECT/AUTO_TRANSITION/SKIP/TIMEOUT).
        List<ApprovalHistory> historyRows = historyRepository
                .findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(
                        instance.getFactoryId(), instance.getId());
        Set<String> completedUpstream = new HashSet<>();
        for (ApprovalHistory h : historyRows) {
            if (!upstreamNodeIds.contains(h.getNodeId())) continue;
            HistoryAction a = h.getAction();
            if (a == HistoryAction.APPROVE || a == HistoryAction.REJECT
                    || a == HistoryAction.AUTO_TRANSITION || a == HistoryAction.SKIP
                    || a == HistoryAction.TIMEOUT) {
                completedUpstream.add(h.getNodeId());
            }
        }

        int completedCount = completedUpstream.size();
        int totalUpstream = upstreamNodeIds.size();
        boolean satisfied;
        switch (mode) {
            case "ANY":
                satisfied = completedCount >= 1;
                break;
            case "N_OF_M": {
                int requiredCount;
                Object raw = config.get("requiredCount");
                if (raw instanceof Number n) {
                    requiredCount = n.intValue();
                } else if (raw instanceof String s) {
                    try { requiredCount = Integer.parseInt(s); }
                    catch (NumberFormatException ex) {
                        requiredCount = (int) Math.ceil(totalUpstream / 2.0);
                    }
                } else {
                    requiredCount = (int) Math.ceil(totalUpstream / 2.0);
                }
                satisfied = completedCount >= requiredCount;
                break;
            }
            case "ALL":
            default:
                satisfied = completedCount >= totalUpstream;
                break;
        }

        if (!satisfied) {
            log.info("Join 等待更多上游分支 - instanceId={}, joinId={}, mode={}, completed={}/{}",
                    instance.getId(), joinId, mode, completedCount, totalUpstream);
            return;
        }

        // Satisfied — remove all upstream from currentNodeIds (parallel branches converged).
        instance.getCurrentNodeIds().removeAll(upstreamNodeIds);

        // Write AUTO_TRANSITION history for join.
        writeHistory(instance.getFactoryId(), instance.getId(), joinId,
                HistoryAction.AUTO_TRANSITION, null, null,
                "join: mode=" + mode + ", completed=" + completedCount + "/" + totalUpstream, null);

        // Recurse to outgoing.
        List<ApprovalWorkflowEdge> outs = graph.outgoingByNode.getOrDefault(joinId, List.of());
        if (outs.isEmpty()) {
            log.warn("join 节点无 outgoing - instanceId={}, joinId={}", instance.getId(), joinId);
            return;
        }
        // join 一般单 outgoing, 取第一个 (按 priority).
        autoAdvance(instance, graph, outs.get(0).getTarget(), hop + 1);
    }

    /**
     * 评估 outgoing edges: 按 priority ASC, 取第一个 condition=true 的 (空 condition = true).
     *
     * <p>label="DEFAULT" 的 edge 保留作 fallback.
     *
     * @param decisionOverride 若非 null, 将 #decision 注入 context (REJECT 路径专用)
     * @return matched target nodeIds. 多个 = parallel fan-out (parallel 节点 caller 不通过此 path).
     *         空 list = misconfig.
     */
    private List<String> evaluateOutgoingEdges(List<ApprovalWorkflowEdge> outgoing,
                                               ApprovalWorkflowInstance instance,
                                               String decisionOverride,
                                               String fromNodeId) {
        if (outgoing.isEmpty()) {
            return List.of();
        }
        List<ApprovalWorkflowEdge> sorted = new ArrayList<>(outgoing);
        sorted.sort(Comparator.comparingInt(e ->
                e.getPriority() == null ? Integer.MAX_VALUE : e.getPriority()));

        Map<String, Object> ctx = new HashMap<>(instance.getContextJson() == null
                ? Map.of() : instance.getContextJson());
        if (decisionOverride != null) {
            ctx.put("decision", decisionOverride);
        }

        ApprovalWorkflowEdge defaultEdge = null;
        for (ApprovalWorkflowEdge e : sorted) {
            if ("DEFAULT".equalsIgnoreCase(e.getLabel())) {
                defaultEdge = e;
                continue;
            }
            String cond = e.getCondition();
            if (cond == null || cond.isBlank()) {
                // 空 condition = always true.
                return List.of(e.getTarget());
            }
            if (evaluateCondition(cond, ctx)) {
                return List.of(e.getTarget());
            }
        }
        if (defaultEdge != null) {
            return List.of(defaultEdge.getTarget());
        }
        log.warn("outgoing edges 全 false 且无 DEFAULT - fromNodeId={}, instanceId={}",
                fromNodeId, instance.getId());
        return List.of();
    }

    /**
     * Terminate at end node: 读 outcome 设 status + completedAt + 清 currentNodeIds.
     */
    private void terminateAtEnd(ApprovalWorkflowInstance instance, ApprovalWorkflowNode endNode) {
        Map<String, Object> config = endNode.getConfig() == null ? Map.of() : endNode.getConfig();
        String outcome = String.valueOf(config.getOrDefault("outcome", "APPROVED"))
                .toUpperCase(Locale.ROOT);
        InstanceStatus terminal;
        try {
            terminal = InstanceStatus.valueOf(outcome);
            // Disallow CANCELLED via end-node config (CANCEL is caller-driven).
            if (terminal == InstanceStatus.RUNNING) {
                terminal = InstanceStatus.APPROVED;
            }
        } catch (IllegalArgumentException ex) {
            terminal = InstanceStatus.APPROVED;
        }
        // REJECTED 优先 (per Sprint 3 executor semantic).
        if (instance.getStatus() == InstanceStatus.REJECTED && terminal == InstanceStatus.APPROVED) {
            log.info("已 REJECTED 不被 APPROVED 覆盖 - instanceId={}", instance.getId());
            return;
        }
        instance.setStatus(terminal);
        instance.setCompletedAt(LocalDateTime.now());
        instance.setCurrentNodeIds(new ArrayList<>());

        // Write AUTO_TRANSITION history for end node.
        writeHistory(instance.getFactoryId(), instance.getId(), endNode.getId(),
                HistoryAction.AUTO_TRANSITION, null, null,
                "end: outcome=" + terminal, null);

        log.info("workflow 实例终态 - instanceId={}, status={}, outcome={}",
                instance.getId(), terminal, outcome);
    }

    // ==================== Workflow-scoped RBAC (Phase 2 issue #13) ====================

    /**
     * 检查用户是否有权限推进 instance 当前 active 节点 (Phase 2 hotfix for issue #13).
     *
     * <p>替代 module 静态 permission (e.g. {@code procurement:read_write}), 改读
     * workflow node {@code config.approverRoles}:
     * <ol>
     *   <li>{@code factory_super_admin} — 总是放行 (兜底管理员推工作流)</li>
     *   <li>instance 非 RUNNING / 无 active node — 拒绝 (无可推进节点)</li>
     *   <li>active node type != "approval" — 拒绝 (notify/condition/parallel 不接受人工 transition)</li>
     *   <li>user.roleCode in node.config.approverRoles → 放行</li>
     *   <li>otherwise → 拒绝</li>
     * </ol>
     *
     * <p>Phase 1 简化: 默认取 {@code currentNodeIds[0]} 作为 active node (mirror
     * {@link #transitionNode} 行为). Parallel 场景 caller 应明确 fromNodeId
     * (B.5 frontend 携带, Phase 2 Sprint 4 增加 endpoint 参数).
     *
     * <p>Fail-closed: 任何异常 (workflow config missing / node deser fail) → false.
     *
     * @param instance RUNNING workflow 实例
     * @param user 当前调用方
     * @return true 当且仅当 user 角色在 active approval 节点的 approverRoles 中, 或是 super_admin
     * @since 2026-05-18 (Phase 2 issue #13)
     */
    @SuppressWarnings("unchecked")
    public boolean canTransition(ApprovalWorkflowInstance instance, User user) {
        if (instance == null || user == null) {
            return false;
        }
        // factory_super_admin 总是放行 — 兜底, 跟其他模块 RBAC 一致.
        FactoryUserRole roleEnum = user.getRoleEnum();
        if (roleEnum == FactoryUserRole.factory_super_admin) {
            return true;
        }

        // 仅 RUNNING 实例可推进.
        if (instance.getStatus() != InstanceStatus.RUNNING) {
            return false;
        }
        List<String> currentNodes = instance.getCurrentNodeIds();
        if (currentNodes == null || currentNodes.isEmpty()) {
            return false;
        }

        String userRole = user.getRoleCode();
        if (userRole == null || userRole.isBlank()) {
            return false;
        }

        try {
            // 取 active node 的 approverRoles 配置.
            ApprovalWorkflow workflow = workflowService
                    .getById(instance.getFactoryId(), instance.getWorkflowId())
                    .orElse(null);
            if (workflow == null) {
                log.warn("canTransition: workflow config 不存在 — instanceId={}, workflowId={}",
                        instance.getId(), instance.getWorkflowId());
                return false;
            }
            List<ApprovalWorkflowNode> nodes = workflowService
                    .deserializeNodes(workflow.getNodesJson());
            Map<String, ApprovalWorkflowNode> byId = new HashMap<>();
            for (ApprovalWorkflowNode n : nodes) byId.put(n.getId(), n);

            // 检查任一 active approval 节点的 approverRoles 包含 user.roleCode.
            // 一般 currentNodeIds 只有 1 个 (mirror transitionNode 简化), 但 parallel 场景
            // 可能有多个 — 任一节点放行即可 (caller 之后只 advance 自己那个).
            for (String nodeId : currentNodes) {
                ApprovalWorkflowNode node = byId.get(nodeId);
                if (node == null || !"approval".equals(node.getType())) {
                    continue;
                }
                Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
                Object roles = config.get("approverRoles");
                if (!(roles instanceof List<?> roleList)) continue;
                for (Object roleRaw : roleList) {
                    if (roleRaw == null) continue;
                    if (userRole.equals(String.valueOf(roleRaw))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            // Fail-closed — config/deser 错误等同 deny.
            log.warn("canTransition 评估失败 (fail-closed): instanceId={}, userId={}, error={}",
                    instance.getId(), user.getId(), e.getMessage());
            return false;
        }
    }

    // ==================== Internal helpers ====================

    /**
     * moduleCode → DecisionType — registry lookup, Phase B+ 加 entry 不必触碰逻辑.
     */
    private DecisionType lookupDecisionType(String moduleCode) {
        DecisionType type = MODULE_TO_DECISION.get(moduleCode);
        if (type == null) {
            throw new IllegalArgumentException(
                    "未支持的 moduleCode: " + moduleCode +
                    " (已注册: " + MODULE_TO_DECISION.keySet() + ")");
        }
        return type;
    }

    /**
     * Load instance — Redis hit → 直接返回; miss → PG fallback. 找不到抛 BusinessException 404.
     */
    private ApprovalWorkflowInstance loadInstanceOrFail(String instanceId) {
        // Redis hot path
        if (redisTemplate != null) {
            try {
                Object cached = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + instanceId);
                if (cached instanceof ApprovalWorkflowInstance inst) {
                    return inst;
                }
            } catch (Exception e) {
                log.warn("Redis 读取失败, fallback PG - instanceId={}, error={}",
                        instanceId, e.getMessage());
            }
        }
        // PG fallback
        return instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BusinessException(404,
                        "workflow 实例不存在 — instanceId=" + instanceId));
    }

    /**
     * 计算 durationSeconds: 找本 instance 最近一条 history, 算其 createdAt 到 now 的秒数.
     * 若无 history (首次 transition), 用 initiatedAt → now.
     */
    private Integer computeDurationFromPrevious(String factoryId, String instanceId,
                                                LocalDateTime initiatedAt) {
        List<ApprovalHistory> existing = historyRepository
                .findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(factoryId, instanceId);
        LocalDateTime ref;
        if (existing.isEmpty()) {
            ref = initiatedAt == null ? LocalDateTime.now() : initiatedAt;
        } else {
            ref = existing.get(existing.size() - 1).getCreatedAt();
            if (ref == null) ref = initiatedAt;
        }
        if (ref == null) return 0;
        long delta = java.time.Duration.between(ref, LocalDateTime.now()).getSeconds();
        // Clamp to non-negative.
        return (int) Math.max(0L, delta);
    }

    /**
     * 描述 notify 节点 — 提取 channels / recipients 用于 history notes.
     */
    @SuppressWarnings("unchecked")
    private String describeNotify(ApprovalWorkflowNode node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Object channels = config.get("channels");
        Object recipients = config.get("recipients");
        if (channels == null && recipients == null) {
            return node.getLabel() == null ? node.getId() : node.getLabel();
        }
        return String.format("channels=%s, recipients=%s",
                channels == null ? "[]" : channels,
                recipients == null ? "[]" : recipients);
    }

    /**
     * Walk from startNodeId → first human/end node. Evaluates SpEL condition on edges.
     *
     * <p>区别于 B.3 skeleton (取首个 outgoing 不评估 condition), 本实现复用 autoAdvance
     * 逻辑跑到第一个停止点. 启动期间 instance.currentNodeIds / status 会被 autoAdvance
     * 实时更新. 返回的 id 用于 log + early exit; caller 已通过 instance 副作用拿到结果.
     *
     * @return first landing node id (approval, or end), or null if unreachable
     */
    String walkToFirstHumanNode(String startNodeId, GraphIndex graph,
                                ApprovalWorkflowInstance instance) {
        if (startNodeId == null) {
            log.warn("workflow startNodeId 为空, 实例 currentNodeIds 留空");
            return null;
        }
        ApprovalWorkflowNode startNode = graph.nodesById.get(startNodeId);
        if (startNode == null) {
            log.warn("startNodeId 在 graph 中找不到 - {}", startNodeId);
            return null;
        }
        // autoAdvance 副作用 — 走到 approval (added to currentNodeIds) 或 end (status set).
        autoAdvance(instance, graph, startNodeId, 0);
        // 返回第一个 approval (若有), 否则 end (status=terminal).
        if (!instance.getCurrentNodeIds().isEmpty()) {
            return instance.getCurrentNodeIds().get(0);
        }
        // Walk completed terminally.
        return startNodeId;
    }

    /**
     * Build graph indexes for fast lookup.
     */
    GraphIndex indexGraph(List<ApprovalWorkflowNode> nodes, List<ApprovalWorkflowEdge> edges) {
        Map<String, ApprovalWorkflowNode> nodesById = new HashMap<>();
        for (ApprovalWorkflowNode n : nodes) nodesById.put(n.getId(), n);

        Map<String, List<ApprovalWorkflowEdge>> outgoing = new HashMap<>();
        Map<String, List<ApprovalWorkflowEdge>> incoming = new HashMap<>();
        for (ApprovalWorkflowEdge e : edges) {
            outgoing.computeIfAbsent(e.getSource(), k -> new ArrayList<>()).add(e);
            incoming.computeIfAbsent(e.getTarget(), k -> new ArrayList<>()).add(e);
        }
        // Pre-sort outgoing by priority ASC for deterministic eval.
        for (List<ApprovalWorkflowEdge> list : outgoing.values()) {
            list.sort(Comparator.comparingInt(e ->
                    e.getPriority() == null ? Integer.MAX_VALUE : e.getPriority()));
        }
        return new GraphIndex(nodesById, outgoing, incoming);
    }

    /**
     * 写入 Redis hot cache. Fail-open — 失败仅 log warn.
     *
     * <p>TTL split:
     * <ul>
     *   <li>RUNNING — 无 TTL (持久), 每次 transition 重置</li>
     *   <li>终态 (APPROVED/REJECTED/CANCELLED/TIMEOUT) — 24h TTL</li>
     * </ul>
     */
    private void writeRedis(ApprovalWorkflowInstance instance) {
        if (redisTemplate == null) {
            return;
        }
        try {
            String key = REDIS_KEY_PREFIX + instance.getId();
            Duration ttl = ttlFor(instance.getStatus());
            if (ttl == null) {
                // RUNNING — no TTL (persistent until terminal).
                redisTemplate.opsForValue().set(key, instance);
            } else {
                redisTemplate.opsForValue().set(key, instance, ttl);
            }
        } catch (Exception e) {
            log.warn("Redis 写入失败 (PG 已落地, 业务继续) - instanceId={}, error={}",
                    instance.getId(), e.getMessage());
        }
    }

    /**
     * TTL 策略 — RUNNING 持久, 终态 24h.
     */
    private Duration ttlFor(InstanceStatus status) {
        if (status == null || status == InstanceStatus.RUNNING) {
            return null;
        }
        return REDIS_TERMINAL_TTL;
    }

    /**
     * 写 history 记录 — append-only.
     */
    private void writeHistory(String factoryId, String instanceId, String nodeId,
                              HistoryAction action, Long actorId, String actorRole,
                              String notes, Integer durationSeconds) {
        ApprovalHistory history = ApprovalHistory.builder()
                .factoryId(factoryId)
                .instanceId(instanceId)
                .nodeId(nodeId)
                .action(action)
                .actorId(actorId)
                .actorRole(actorRole)
                .notes(notes)
                .durationSeconds(durationSeconds)
                .createdAt(LocalDateTime.now())
                .build();
        historyRepository.save(history);
    }

    /**
     * Graph 索引 — per call, 不缓存 (workflow config 改动后即时生效).
     */
    static class GraphIndex {
        final Map<String, ApprovalWorkflowNode> nodesById;
        final Map<String, List<ApprovalWorkflowEdge>> outgoingByNode;
        final Map<String, List<ApprovalWorkflowEdge>> incomingByNode;

        GraphIndex(Map<String, ApprovalWorkflowNode> nodesById,
                   Map<String, List<ApprovalWorkflowEdge>> outgoingByNode,
                   Map<String, List<ApprovalWorkflowEdge>> incomingByNode) {
            this.nodesById = nodesById;
            this.outgoingByNode = outgoingByNode;
            this.incomingByNode = incomingByNode;
        }
    }

}
