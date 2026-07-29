package com.cretas.aims.ai.tool.impl.workflow;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.workflow.ApprovalHistory;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sprint 10 Loop 4 — 审批闭环 Tool 2: 执行审批 (APPROVE / REJECT).
 *
 * <p>包装 {@link WorkflowEngineService#transitionNode}, 推进或驳回 workflow 实例.
 * 触发条件: 用户在 Workdesk path A/B 看到待审清单后, 选择具体 instance 做 APPROVE/REJECT.
 *
 * <p><b>幂等性</b> (fool-proof Rule 4): instance 的 @Version 乐观锁天然防重复 — 同一 instance 已
 * 转换 (status != RUNNING) 再调返 409. 同一并发 APPROVE 第二个失败. 这是基础设施级幂等,
 * 业务层无需再加 dedup window.
 *
 * <p><b>context_json 加标记</b> (Loop 4 复用 existing column, NO new column):
 * 转换前把 {@code testRun=true, source="sprint-10-loop-4"} merge 进 contextJson
 * (新 key, 不破坏现有 business context). 用于 E2E spec SQL verify.
 *
 * <p><b>用户身份</b>: 从 context.userId / context.userRole 取. role 缺失从 DB 查.
 *
 * <p><b>下一审批人提示</b> (fool-proof Rule 2): 转换完毕后, 找 currentNodeIds 第一个 approval node
 * 的 approverRoles 作为下一审批人 hint, 加进 message.
 *
 * <p>factoryId 隔离豁免说明: doExecute() 使用 fetch-then-verify pattern —
 * (1) {@code instanceRepository.findById(instanceId)} 后紧跟 {@code factoryId equals 校验}
 * (instance.getFactoryId() != factoryId → 403); UUID 全局唯一不会跨工厂误命中.
 * (2) {@code userRepository.findById(actorId)} 取 actor role — User 是全局表 (factory_super_admin
 * 可跨工厂, 但当前调用上下文已经过 JWT factoryId 校验, 不会跨工厂注入).
 * (3) {@code workflowEngine.transitionNode} 内部按 instance.factoryId 走 — 不会跨工厂修改.
 * Audit 脚本 (tool-factory-isolation-audit.mjs) 见 findById() pattern 默认 HIGH, 此 Tool 实际
 * 安全 (fetch-then-verify), 故此处显式标注豁免.
 *
 * @since 2026-05-21 (Sprint 10 Loop 4)
 */
@Slf4j
@Component
public class ApprovalActionExecuteTool extends AbstractBusinessTool {

    @Autowired
    private WorkflowEngineService workflowEngine;

    @Autowired
    private ApprovalWorkflowInstanceRepository instanceRepository;

    @Autowired
    private ApprovalWorkflowService approvalWorkflowService;

    @Autowired
    private UserRepository userRepository;

    @Override
    public String getToolName() {
        return "approval_action_execute";
    }

    @Override
    public String getDescription() {
        return "执行审批操作 (批准 / 拒绝). 适用场景: 用户对待审工作流实例做决定 — 批准 / 通过 / 同意 / 驳回 / 拒绝. "
                + "支持 5 个流程: 付款 / 开票 / 调价 / 采购 / 销售订单. 幂等: 重复调返 409. 转换完返下一审批人或终态.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> instanceId = new HashMap<>();
        instanceId.put("type", "string");
        instanceId.put("description",
                "工作流实例 ID (UUID, 36 chars). 可从 approval_pending_query 输出的 items[i].instanceId 取.");
        properties.put("instanceId", instanceId);

        Map<String, Object> action = new HashMap<>();
        action.put("type", "string");
        action.put("description", "审批动作: 'APPROVE' (批准/通过/同意) 或 'REJECT' (拒绝/驳回)");
        action.put("enum", List.of("APPROVE", "REJECT"));
        properties.put("action", action);

        Map<String, Object> notes = new HashMap<>();
        notes.put("type", "string");
        notes.put("description", "审批意见 / 拒绝原因 (R3 dropdown enum or free text)");
        properties.put("notes", notes);

        schema.put("properties", properties);
        schema.put("required", List.of("instanceId", "action"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("instanceId", "action");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "instanceId" -> "请提供工作流实例 ID (可在 '我待审' 列表中查到).";
            case "action" -> "请选择审批动作: APPROVE (批准) 或 REJECT (拒绝).";
            case "notes" -> "请填写审批意见 (拒绝时建议说明原因).";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    @Transactional
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        // 1. 解析参数
        String instanceId = getString(params, "instanceId");
        String actionStr = getString(params, "action");
        String notes = getString(params, "notes");

        if (instanceId == null || instanceId.isBlank()) {
            throw new BusinessException(400, "instanceId 不能为空");
        }
        if (actionStr == null || actionStr.isBlank()) {
            throw new BusinessException(400, "action 不能为空 (APPROVE 或 REJECT)");
        }

        ApprovalHistory.HistoryAction action;
        try {
            action = ApprovalHistory.HistoryAction.valueOf(actionStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "action 必须是 APPROVE 或 REJECT, 实际收到: " + actionStr);
        }
        if (action != ApprovalHistory.HistoryAction.APPROVE
                && action != ApprovalHistory.HistoryAction.REJECT) {
            throw new BusinessException(400, "action 只支持 APPROVE / REJECT, 当前: " + action);
        }

        // 2. 解析当前用户
        Long actorId = getUserId(context);
        String actorRole = getUserRole(context);
        if (actorRole == null || actorRole.isBlank()) {
            if (actorId != null) {
                Optional<User> userOpt = userRepository.findById(actorId);
                if (userOpt.isPresent()) {
                    actorRole = userOpt.get().getRoleCode();
                }
            }
        }
        if (actorId == null) {
            throw new BusinessException(401, "无 userId 上下文, 无法记录审批人");
        }
        if (actorRole == null || actorRole.isBlank()) {
            throw new BusinessException(403, "无 user role, 无法验证审批权限");
        }

        log.info("approval_action_execute - factoryId={}, instanceId={}, actorId={}, actorRole={}, action={}",
                factoryId, instanceId, actorId, actorRole, action);

        // 3. Pre-check + tag context (Loop 4 复用 existing context_json, NO new column)
        //    顺序: 先 load, 加 testRun tag, save, 再 transition. transitionNode 会再 load 看到最新.
        ApprovalWorkflowInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BusinessException(404, "工作流实例不存在: " + instanceId));

        if (!factoryId.equals(instance.getFactoryId())) {
            throw new BusinessException(403, "工作流实例不属于当前工厂");
        }

        if (instance.getStatus() != ApprovalWorkflowInstance.InstanceStatus.RUNNING) {
            // 幂等: 已 ended → 409 actionHint
            String hint = String.format(
                    "工作流实例已结束 (状态: %s), 不可再审批. instanceId=%s",
                    instance.getStatus(), instanceId);
            throw new BusinessException(409, hint);
        }

        // 4. 标记 ai_invocation_metadata 进 contextJson — Loop 4 复用 existing column
        Map<String, Object> ctx = new HashMap<>(instance.getContextJson() == null
                ? Map.of() : instance.getContextJson());
        Map<String, Object> aiMeta = new HashMap<>();
        // 保留已有 aiInvocationMetadata (若 Loop 3 写过)
        Object existingMeta = ctx.get("aiInvocationMetadata");
        if (existingMeta instanceof Map<?, ?> existingMap) {
            for (Map.Entry<?, ?> entry : existingMap.entrySet()) {
                aiMeta.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        aiMeta.put("testRun", true);
        aiMeta.put("source", "sprint-10-loop-4");
        aiMeta.put("actorId", actorId);
        aiMeta.put("action", action.name());
        aiMeta.put("ts", LocalDateTime.now().toString());
        ctx.put("aiInvocationMetadata", aiMeta);
        // 同步顶层 testRun / source key 兼容 SQL `context_json @>` containment 查询
        ctx.put("testRun", true);
        ctx.put("source", "sprint-10-loop-4");

        instance.setContextJson(ctx);
        // 把 context 更新 flush 出去, transitionNode 拿到 fresh state.
        instanceRepository.save(instance);

        // 5. 转换 — 让 transitionNode 处理乐观锁 / state machine / history / Redis
        ApprovalWorkflowInstance updated;
        try {
            updated = workflowEngine.transitionNode(instanceId, actorId, actorRole, action, notes);
        } catch (BusinessException be) {
            // 409 等业务异常直接透传
            throw be;
        } catch (Exception e) {
            log.error("transitionNode 失败 - instanceId={}, error={}", instanceId, e.getMessage(), e);
            throw new BusinessException(500, "审批转换失败: " + e.getMessage());
        }

        // 6. 找下一审批人 / 终态状态 (fool-proof Rule 2 — context 必带)
        Map<String, Object> result = new HashMap<>();
        result.put("instanceId", updated.getId());
        result.put("moduleCode", updated.getModuleCode());
        result.put("businessEntityId", updated.getBusinessEntityId());
        result.put("priorStatus", "RUNNING");
        result.put("currentStatus", updated.getStatus().name());
        result.put("action", action.name());
        result.put("actorId", actorId);
        result.put("actorRole", actorRole);
        result.put("workflowUrl", "/workflow/" + updated.getId());

        String message;
        if (updated.getStatus() == ApprovalWorkflowInstance.InstanceStatus.RUNNING) {
            // 还有下一节点
            String nextNodeLabel = "下一节点";
            List<String> nextApproverRoles = new ArrayList<>();
            if (updated.getCurrentNodeIds() != null && !updated.getCurrentNodeIds().isEmpty()) {
                String nextNodeId = updated.getCurrentNodeIds().get(0);
                result.put("nextNodeId", nextNodeId);
                ApprovalWorkflowNode node = loadNode(factoryId, updated.getWorkflowId(), nextNodeId);
                if (node != null) {
                    if (node.getLabel() != null) {
                        nextNodeLabel = node.getLabel();
                        result.put("nextNodeLabel", nextNodeLabel);
                    }
                    if (node.getConfig() != null) {
                        Object roles = node.getConfig().get("approverRoles");
                        if (roles instanceof List<?> roleList) {
                            for (Object r : roleList) {
                                if (r != null) nextApproverRoles.add(String.valueOf(r));
                            }
                        }
                    }
                }
            }
            result.put("nextApproverRoles", nextApproverRoles);
            message = String.format(
                    "已%s — instance %s (%s/%s). 流转到下一节点: %s%s. 工作流详情: /workflow/%s",
                    action == ApprovalHistory.HistoryAction.APPROVE ? "批准" : "拒绝",
                    updated.getId().substring(0, Math.min(8, updated.getId().length())),
                    updated.getModuleCode(),
                    updated.getBusinessEntityId() != null ? updated.getBusinessEntityId() : "—",
                    nextNodeLabel,
                    nextApproverRoles.isEmpty() ? "" : (" (待 " + String.join("/", nextApproverRoles) + ")"),
                    updated.getId());
        } else {
            message = String.format(
                    "已%s — instance %s (%s/%s) 终态: %s. 工作流详情: /workflow/%s",
                    action == ApprovalHistory.HistoryAction.APPROVE ? "批准" : "拒绝",
                    updated.getId().substring(0, Math.min(8, updated.getId().length())),
                    updated.getModuleCode(),
                    updated.getBusinessEntityId() != null ? updated.getBusinessEntityId() : "—",
                    updated.getStatus().name(),
                    updated.getId());
        }
        result.put("message", message);

        return buildSimpleResult(message, result);
    }

    // ==================== helpers ====================

    private ApprovalWorkflowNode loadNode(String factoryId, String workflowId, String nodeId) {
        try {
            Optional<ApprovalWorkflow> wf = approvalWorkflowService.getById(factoryId, workflowId);
            if (wf.isEmpty()) return null;
            List<ApprovalWorkflowNode> nodes = approvalWorkflowService.deserializeNodes(
                    wf.get().getNodesJson());
            for (ApprovalWorkflowNode n : nodes) {
                if (nodeId.equals(n.getId())) return n;
            }
            return null;
        } catch (Exception e) {
            log.warn("loadNode 失败 - workflowId={}, nodeId={}, err={}", workflowId, nodeId, e.getMessage());
            return null;
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
