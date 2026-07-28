package com.cretas.aims.ai.tool.impl.workflow;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.entity.User;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Sprint 10 Loop 4 — 审批闭环 Tool 1: 查询当前用户待审批列表.
 *
 * <p>包装 {@link WorkflowEngineService#findPendingForRole}, 返回当前 user role 的
 * RUNNING workflow 实例 (跨 module 或 filter 单 module). 用于 Workdesk 触发 path A
 * "当前节点待批 N 项" / path B "我该批什么".
 *
 * <p>Fool-proof Rule 2 兑现: 输出 message 必带 context — 列出每条实例的
 * businessSummary (PO-XXX ¥40000 (供应商甲)) / currentNodeLabel (财务审批) /
 * 发起人 / 发起时间.
 *
 * <p>Phase 1 复用 issue #20 closure (PR #862 / commit 2d681839f) — 后端 service
 * + DTO + controller 全部已 ship 在 main, 此 Tool 仅做 AI-facing wrap layer.
 *
 * <p>factoryId 隔离豁免说明: doExecute() 通过 factoryId 走全部业务调用 —
 * (1) {@code workflowEngine.findPendingForRole(factoryId, ...)} 严格按 factoryId 过滤.
 * (2) {@code userRepository.findById(userId)} 仅取当前登录 user 的 role (User 是全局表,
 * userId 来自当前 session JWT, 不会跨工厂注入).
 * (3) {@code purchaseOrderRepository.findAllById(poIds)} 取 PO businessSummary —
 * poIds 来自 instances (已按 factoryId filter), 不会拉到外厂 PO.
 * Audit 脚本 (tool-factory-isolation-audit.mjs) 见 findById() pattern 默认 HIGH, 此 Tool 实际
 * 安全, 故此处显式标注豁免.
 *
 * @since 2026-05-21 (Sprint 10 Loop 4)
 */
@Slf4j
@Component
public class ApprovalPendingQueryTool extends AbstractBusinessTool {

    @Autowired
    private WorkflowEngineService workflowEngine;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApprovalWorkflowService approvalWorkflowService;

    @Autowired(required = false)
    private PurchaseOrderRepository purchaseOrderRepository;

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public String getToolName() {
        return "approval_pending_query";
    }

    @Override
    public String getDescription() {
        return "查询当前用户待审批的工作流实例列表 (RUNNING + active node role 匹配). "
                + "适用场景: '我该批什么' / '今日待审' / '当前节点待批 N 项' / '等我审批的有哪些'. "
                + "返回每条实例的 businessSummary (e.g. PO-XXX ¥40000 (供应商甲)) + currentNodeLabel + 发起人. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> moduleCode = new HashMap<>();
        moduleCode.put("type", "string");
        moduleCode.put("description",
                "可选业务模块过滤 (PURCHASE_ORDER / SALES_ORDER / PAYMENT / INVOICE / PRICE_ADJUSTMENT). "
                        + "不传则跨模块返全部待审.");
        properties.put("moduleCode", moduleCode);

        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码 (1-based, 默认 1)");
        properties.put("page", page);

        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页数量 (默认 20, 上限 50)");
        properties.put("size", size);

        schema.put("properties", properties);
        schema.put("required", new ArrayList<String>());  // 无必需参数
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return new ArrayList<>();  // 无必需参数 — 默认查全部
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        // 1. 解析当前用户 (context 已有 userId, 需查 role)
        Long userId = getUserId(context);
        String userRole = getUserRole(context);
        if (userRole == null || userRole.isBlank()) {
            // fallback: 从 DB 取
            if (userId != null) {
                Optional<User> userOpt = userRepository.findById(userId);
                if (userOpt.isPresent()) {
                    userRole = userOpt.get().getRoleCode();
                }
            }
        }
        if (userRole == null || userRole.isBlank()) {
            Map<String, Object> data = new HashMap<>();
            data.put("count", 0);
            data.put("items", new ArrayList<>());
            data.put("message", "当前用户无 role 信息, 无法查询待审批列表");
            return buildSimpleResult("当前用户无 role, 待审批为空", data);
        }

        // 2. 参数
        String moduleCode = getString(params, "moduleCode");
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 20);
        if (page < 1) page = 1;
        if (size < 1 || size > 50) size = 20;

        log.info("approval_pending_query - factoryId={}, userRole={}, moduleCode={}, page={}, size={}",
                factoryId, userRole, moduleCode, page, size);

        // 3. 查 pending instances
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<ApprovalWorkflowInstance> instances = workflowEngine.findPendingForRole(
                factoryId, userRole, moduleCode, pageable);

        // 4. 批量 hydrate PO + initiator username + workflow node label
        Set<String> poIds = new HashSet<>();
        Set<Long> initiatorIds = new HashSet<>();
        for (ApprovalWorkflowInstance inst : instances.getContent()) {
            if ("PURCHASE_ORDER".equals(inst.getModuleCode()) && inst.getBusinessEntityId() != null) {
                poIds.add(inst.getBusinessEntityId());
            }
            if (inst.getInitiatedBy() != null) initiatorIds.add(inst.getInitiatedBy());
        }

        Map<String, PurchaseOrder> poById = new HashMap<>();
        if (!poIds.isEmpty() && purchaseOrderRepository != null) {
            try {
                purchaseOrderRepository.findAllById(poIds).forEach(po -> poById.put(po.getId(), po));
            } catch (Exception e) {
                log.warn("批量加载 PO 失败 (businessSummary fallback): {}", e.getMessage());
            }
        }

        Map<Long, String> usernameById = new HashMap<>();
        if (!initiatorIds.isEmpty()) {
            try {
                userRepository.findAllById(initiatorIds).forEach(
                        u -> usernameById.put(u.getId(), u.getUsername()));
            } catch (Exception e) {
                log.warn("批量加载 initiator username 失败: {}", e.getMessage());
            }
        }

        Map<String, Map<String, ApprovalWorkflowNode>> wfNodesCache = new HashMap<>();

        // 5. 构造每行 item
        List<Map<String, Object>> items = new ArrayList<>();
        for (ApprovalWorkflowInstance inst : instances.getContent()) {
            Map<String, Object> item = new HashMap<>();
            item.put("instanceId", inst.getId());
            item.put("moduleCode", inst.getModuleCode());
            item.put("businessEntityId", inst.getBusinessEntityId());
            item.put("businessSummary", buildBusinessSummary(inst, poById));

            // currentNode
            if (inst.getCurrentNodeIds() != null && !inst.getCurrentNodeIds().isEmpty()) {
                String nodeId = inst.getCurrentNodeIds().get(0);
                item.put("currentNodeId", nodeId);
                Map<String, ApprovalWorkflowNode> byId = wfNodesCache.computeIfAbsent(
                        inst.getWorkflowId(),
                        wid -> loadNodes(factoryId, wid));
                ApprovalWorkflowNode node = byId.get(nodeId);
                if (node != null && node.getLabel() != null) {
                    item.put("currentNodeLabel", node.getLabel());
                } else {
                    item.put("currentNodeLabel", nodeId);
                }
            }

            // initiator
            if (inst.getInitiatedBy() != null) {
                String name = usernameById.get(inst.getInitiatedBy());
                item.put("initiatedByUsername", name != null ? name : ("user#" + inst.getInitiatedBy()));
            }

            // initiated_at
            if (inst.getInitiatedAt() != null) {
                item.put("initiatedAt", inst.getInitiatedAt().format(TS_FMT));
            }

            // 跳转 URL — 前端用此跳工作流详情/审批 dialog
            item.put("actionUrl", "/workflow/" + inst.getId());

            items.add(item);
        }

        // 6. 输出
        Map<String, Object> data = new HashMap<>();
        data.put("count", (int) instances.getTotalElements());
        data.put("returned", items.size());
        data.put("page", page);
        data.put("size", size);
        data.put("items", items);
        data.put("userRole", userRole);
        data.put("moduleCodeFilter", moduleCode);

        // 友好 message — fool-proof Rule 2 (context必带)
        String msg;
        if (instances.getTotalElements() == 0) {
            msg = String.format("您 (%s) 当前无待审批工作流%s",
                    userRole, moduleCode != null ? " (模块: " + moduleCode + ")" : "");
        } else {
            msg = String.format("您 (%s) 当前有 %d 项待审批%s — 列表:",
                    userRole, instances.getTotalElements(),
                    moduleCode != null ? " (模块: " + moduleCode + ")" : "");
            for (int i = 0; i < Math.min(5, items.size()); i++) {
                Map<String, Object> it = items.get(i);
                msg += String.format("\n  %d. %s | 当前节点: %s | 发起人: %s",
                        i + 1,
                        it.get("businessSummary"),
                        it.get("currentNodeLabel"),
                        it.getOrDefault("initiatedByUsername", "系统"));
            }
            if (items.size() > 5) {
                msg += String.format("\n  ... 还有 %d 项, 详见前端列表", items.size() - 5);
            }
        }
        data.put("message", msg);

        return buildSimpleResult(msg, data);
    }

    // ==================== helpers ====================

    private String buildBusinessSummary(ApprovalWorkflowInstance inst,
                                        Map<String, PurchaseOrder> poById) {
        String moduleCode = inst.getModuleCode();
        String bizId = inst.getBusinessEntityId();
        if ("PURCHASE_ORDER".equals(moduleCode)) {
            PurchaseOrder po = poById.get(bizId);
            if (po != null) {
                StringBuilder sb = new StringBuilder();
                sb.append(po.getOrderNumber() != null ? po.getOrderNumber() : bizId);
                if (po.getTotalAmount() != null
                        && po.getTotalAmount().compareTo(BigDecimal.ZERO) > 0) {
                    sb.append(" ¥").append(po.getTotalAmount().toPlainString());
                }
                if (po.getSupplierName() != null && !po.getSupplierName().isBlank()) {
                    sb.append(" (").append(po.getSupplierName()).append(")");
                }
                return sb.toString();
            }
            return "采购订单 " + bizId;
        }
        if ("SALES_ORDER".equals(moduleCode)) {
            return "销售订单 " + bizId;
        }
        if ("PAYMENT".equals(moduleCode)) {
            return "付款单 " + bizId;
        }
        if ("INVOICE".equals(moduleCode)) {
            return "开票单 " + bizId;
        }
        if ("PRICE_ADJUSTMENT".equals(moduleCode)) {
            return "调价单 " + bizId;
        }
        return (moduleCode == null ? "业务单据" : moduleCode) + " " + bizId;
    }

    private Map<String, ApprovalWorkflowNode> loadNodes(String factoryId, String workflowId) {
        try {
            Optional<ApprovalWorkflow> wf = approvalWorkflowService.getById(factoryId, workflowId);
            if (wf.isEmpty()) return Map.of();
            List<ApprovalWorkflowNode> nodes = approvalWorkflowService.deserializeNodes(
                    wf.get().getNodesJson());
            Map<String, ApprovalWorkflowNode> byId = new HashMap<>();
            for (ApprovalWorkflowNode n : nodes) byId.put(n.getId(), n);
            return byId;
        } catch (Exception e) {
            log.warn("loadNodes 失败 - workflowId={}, error={}", workflowId, e.getMessage());
            return Map.of();
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
