package com.cretas.aims.controller.workflow;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.workflow.WorkflowInstancePendingDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工作流实例查询 controller — issue #20 (Phase 1 final closure for ADR-001 AC-3).
 *
 * <p>提供 "我待审" widget 数据源 — 给 finance_manager / quality_manager / 等
 * 审批角色登录 admin.cretaceousfuture.com 后, Dashboard 顶部 banner 列出他们
 * 待审的 RUNNING workflow 实例.
 *
 * <p>Wave 1 #13 修了 finance_mgr 调 {@code /approve} 不再 403, 但**没 UI 知道有
 * PO 待审** — 需 admin 通知去找 PO ID. Issue #20 关上这个 UX gap.
 *
 * <p>Base path: {@code /api/mobile/{factoryId}/workflow/instances}
 *
 * @since 2026-05-18 (issue #20)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/workflow/instances")
@RequiredArgsConstructor
@Validated
@Tag(name = "工作流实例查询",
     description = "查 RUNNING workflow 实例 — Dashboard '我待审' widget (issue #20).")
public class WorkflowInstanceController {

    private final WorkflowEngineService workflowEngine;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final UserRepository userRepository;
    private final MobileService mobileService;
    private final PurchaseService purchaseService;

    /** Optional — 用于 hydrate PURCHASE_ORDER businessSummary. */
    @Autowired(required = false)
    private PurchaseOrderRepository purchaseOrderRepository;

    /**
     * 查当前用户角色的"我待审"列表.
     *
     * <p>过滤规则 (mirror {@link WorkflowEngineService#findPendingForRole}):
     * <ul>
     *   <li>status=RUNNING</li>
     *   <li>factory_id = pathvar factoryId</li>
     *   <li>moduleCode 可选过滤</li>
     *   <li>active approval node 的 approverRoles 含 user.roleCode</li>
     *   <li>factory_super_admin 看全部 (兜底)</li>
     * </ul>
     *
     * <p>businessSummary hydrate:
     * <ul>
     *   <li>PURCHASE_ORDER → 批量 fetch PO → "PO-20260518-0123 ¥40000 (供应商甲)"</li>
     *   <li>SALES_ORDER → "SO-{businessEntityId}" fallback (Phase 2 加)</li>
     *   <li>其他 → moduleCode + businessEntityId</li>
     * </ul>
     *
     * @param factoryId path 上的工厂 id (跟 JWT 中 factoryId 应匹配, 由 jwtAuthInterceptor 校验)
     * @param authorization Bearer JWT
     * @param moduleCode 可选过滤 (e.g. "PURCHASE_ORDER")
     * @param page 1-based (跟项目其他 API 一致)
     * @param size 默认 20, 上限 100
     */
    @GetMapping("/pending")
    @Operation(summary = "我待审列表 — Dashboard widget 数据源 (issue #20)")
    public ApiResponse<PageResponse<WorkflowInstancePendingDTO>> getPendingForUser(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @RequestParam(required = false) String moduleCode,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        // 1. 解析 user
        User user = resolveCurrentUser(authorization);
        if (user == null) {
            throw new BusinessException(401, "未授权 — JWT 解析失败");
        }
        String userRole = user.getRoleCode();
        if (userRole == null || userRole.isBlank()) {
            // 无角色 — 返空 page (而非 403, 因为该 endpoint 应该对所有登录用户友好)
            log.debug("getPendingForUser: user {} 无 roleCode, 返空", user.getId());
            return ApiResponse.success(PageResponse.of(List.of(), page, size, 0L));
        }

        // 2. 查 pending instances (Spring Data 0-based pageable, 但暴露给前端用 1-based)
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<ApprovalWorkflowInstance> instances = workflowEngine.findPendingForRole(
                factoryId, userRole, moduleCode, pageable);

        // 3. 批量 hydrate businessSummary (避免 1+N)
        Map<String, WorkflowInstancePendingDTO> partial = new HashMap<>();
        Set<String> poIds = new HashSet<>();
        for (ApprovalWorkflowInstance inst : instances.getContent()) {
            WorkflowInstancePendingDTO dto = WorkflowInstancePendingDTO.builder()
                    .instanceId(inst.getId())
                    .moduleCode(inst.getModuleCode())
                    .businessEntityId(inst.getBusinessEntityId())
                    .initiatedAt(inst.getInitiatedAt())
                    .build();
            partial.put(inst.getId(), dto);
            if ("PURCHASE_ORDER".equals(inst.getModuleCode()) && inst.getBusinessEntityId() != null) {
                poIds.add(inst.getBusinessEntityId());
            }
        }

        // 3a. PURCHASE_ORDER 批量 fetch
        Map<String, PurchaseOrder> poById = new HashMap<>();
        if (!poIds.isEmpty() && purchaseOrderRepository != null) {
            try {
                purchaseOrderRepository.findAllById(poIds).forEach(po -> poById.put(po.getId(), po));
            } catch (Exception e) {
                log.warn("批量加载 PO 失败 (businessSummary 退化为 fallback): {}", e.getMessage());
            }
        }

        // 3b. 用户名 hydrate (initiatedBy)
        Map<Long, String> usernameById = new HashMap<>();
        Set<Long> initiatorIds = new HashSet<>();
        for (ApprovalWorkflowInstance inst : instances.getContent()) {
            if (inst.getInitiatedBy() != null) initiatorIds.add(inst.getInitiatedBy());
        }
        if (!initiatorIds.isEmpty()) {
            try {
                userRepository.findAllById(initiatorIds).forEach(
                        u -> usernameById.put(u.getId(), u.getUsername()));
            } catch (Exception e) {
                log.warn("批量加载 initiator username 失败: {}", e.getMessage());
            }
        }

        // 3c. workflow nodes hydrate (currentNodeLabel) — 缓存按 workflowId
        Map<String, Map<String, ApprovalWorkflowNode>> wfNodesCache = new HashMap<>();

        // 4. 构造 final DTO list
        List<WorkflowInstancePendingDTO> dtos = new ArrayList<>();
        for (ApprovalWorkflowInstance inst : instances.getContent()) {
            WorkflowInstancePendingDTO dto = partial.get(inst.getId());

            // businessSummary
            dto.setBusinessSummary(buildBusinessSummary(inst, poById));

            // currentNodeId + label
            if (inst.getCurrentNodeIds() != null && !inst.getCurrentNodeIds().isEmpty()) {
                String nodeId = inst.getCurrentNodeIds().get(0);
                dto.setCurrentNodeId(nodeId);

                Map<String, ApprovalWorkflowNode> byId = wfNodesCache.computeIfAbsent(
                        inst.getWorkflowId(),
                        wid -> loadNodes(factoryId, wid));
                ApprovalWorkflowNode node = byId.get(nodeId);
                if (node != null) {
                    dto.setCurrentNodeLabel(node.getLabel() != null
                            ? node.getLabel() : node.getId());
                }
            }

            // initiated by
            if (inst.getInitiatedBy() != null) {
                dto.setInitiatedByUsername(usernameById.get(inst.getInitiatedBy()));
            }

            dtos.add(dto);
        }

        PageResponse<WorkflowInstancePendingDTO> response = PageResponse.of(
                dtos, page, size, instances.getTotalElements());
        return ApiResponse.success(response);
    }

    /**
     * Unified OA action boundary.  Business detail pages are read-only; they link here
     * instead of maintaining a second approve/reject state machine.
     */
    @PostMapping("/{instanceId}/actions")
    @Operation(summary = "在 OA 审批中心处理当前节点")
    public ApiResponse<Map<String, Object>> executeAction(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String instanceId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody WorkflowActionRequest request) {
        User user = resolveCurrentUser(authorization);
        if (user == null) throw new BusinessException(401, "未授权 — JWT 解析失败");
        ApprovalWorkflowInstance instance = workflowEngine.getInstance(factoryId, instanceId)
                .orElseThrow(() -> new BusinessException(404, "OA 审批实例不存在"));
        if (request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new BusinessException(400, "OA 审批动作缺少幂等键")
                    .withCode("OA_IDEMPOTENCY_KEY_REQUIRED").withHintTarget("idempotencyKey");
        }
        if (request.expectedNodeId() == null || request.expectedNodeId().isBlank()) {
            throw new BusinessException(400, "OA 审批动作缺少预期节点")
                    .withCode("OA_EXPECTED_NODE_REQUIRED").withHintTarget("expectedNodeId");
        }
        if (instance.getCurrentNodeIds() == null
                || !instance.getCurrentNodeIds().contains(request.expectedNodeId())) {
            throw new BusinessException(409, "审批节点已变化，请刷新待办后重试")
                    .withCode("OA_TASK_NODE_CHANGED");
        }
        if (instance.getStatus() == ApprovalWorkflowInstance.InstanceStatus.RUNNING
                && !workflowEngine.canTransition(instance, user)) {
            throw new BusinessException(403, "您不属于当前审批节点的处理角色")
                    .withCode("OA_TASK_FORBIDDEN");
        }
        HistoryAction action;
        try {
            action = HistoryAction.valueOf(request.action().trim().toUpperCase());
        } catch (Exception invalid) {
            throw new BusinessException(400, "不支持的审批动作").withHintTarget("action");
        }
        if (action != HistoryAction.APPROVE && action != HistoryAction.REJECT) {
            throw new BusinessException(400, "当前入口只支持审批通过或驳回")
                    .withHintTarget("action");
        }
        if (!"PURCHASE_ORDER".equals(instance.getModuleCode())) {
            throw new BusinessException(422, "该业务域尚未迁移到统一 OA 动作适配器")
                    .withCode("OA_DOMAIN_ADAPTER_REQUIRED");
        }
        PurchaseOrder order = purchaseService.applyWorkflowAction(
                factoryId,
                instance.getBusinessEntityId(),
                instanceId,
                user.getId(),
                user.getRoleCode(),
                action,
                request.notes());
        ApprovalWorkflowInstance updated = workflowEngine.getLatestInstance(
                factoryId, instance.getModuleCode(), instance.getBusinessEntityId())
                .orElse(instance);
        return ApiResponse.success(Map.of(
                "instanceId", updated.getId(),
                "workflowStatus", updated.getStatus().name(),
                "businessEntityId", order.getId(),
                "businessStatus", order.getStatus().name()));
    }

    public record WorkflowActionRequest(
            String action,
            String notes,
            String idempotencyKey,
            String expectedNodeId) {}

    /**
     * Sprint 5 Track A — "我创建的工作流" 列表 (personal view).
     *
     * <p>HJ ERP 工作流 6 sub-menu 之一. 列出当前 user 作为 initiator 的 workflow
     * 实例 (跨 status 跨 module). 用户可看自己起的 RUNNING / APPROVED / REJECTED /
     * CANCELLED 全状态历史. UI 按 status 显示不同 tag.
     *
     * <p>差异 vs {@code /pending}: pending 按 role 过滤 (审批人视角); my-created 按
     * initiator 过滤 (发起人视角). 同一 user 可能同时是 initiator + approver.
     *
     * @param factoryId 工厂 id
     * @param authorization Bearer JWT
     * @param page 1-based 页码
     * @param size 默认 20, 上限 100
     * @return Page of WorkflowInstancePendingDTO (复用 DTO, status/completedAt 后续 phase 扩)
     * @since 2026-05-19 (Sprint 5 Track A — C-MENU-PERSONAL-VIEW)
     */
    @GetMapping("/my-created")
    @Operation(summary = "我创建的工作流 — Sprint 5 Track A personal view")
    public ApiResponse<PageResponse<WorkflowInstancePendingDTO>> getMyCreated(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        User user = resolveCurrentUser(authorization);
        if (user == null) {
            throw new BusinessException(401, "未授权 — JWT 解析失败");
        }

        Pageable pageable = PageRequest.of(page - 1, size);
        Page<ApprovalWorkflowInstance> instances = workflowEngine.findCreatedBy(
                factoryId, user.getId(), pageable);

        List<WorkflowInstancePendingDTO> dtos = hydrateInstances(factoryId, instances.getContent());

        PageResponse<WorkflowInstancePendingDTO> response = PageResponse.of(
                dtos, page, size, instances.getTotalElements());
        return ApiResponse.success(response);
    }

    /**
     * Sprint 5 Track A — "我参与的工作流" 列表 (personal view).
     *
     * <p>HJ ERP 工作流 6 sub-menu 之一. 列出当前 user 发起或作为 actor 处理过的 workflow
     * 实例 (通过 ApprovalHistory). distinct 因同一 user 可能多次操作同一实例.
     *
     * <p>差异 vs {@code /my-created}: my-created 只按 initiator 过滤; my-participated
     * 同时包含我发起及我作为 actor 处理过的实例 (含已完成).
     *
     * @param factoryId 工厂 id
     * @param authorization Bearer JWT
     * @param page 1-based 页码
     * @param size 默认 20, 上限 100
     * @return Page of WorkflowInstancePendingDTO
     * @since 2026-05-19 (Sprint 5 Track A — C-MENU-PERSONAL-VIEW)
     */
    @GetMapping("/my-participated")
    @Operation(summary = "我参与的工作流 — Sprint 5 Track A personal view")
    public ApiResponse<PageResponse<WorkflowInstancePendingDTO>> getMyParticipated(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        User user = resolveCurrentUser(authorization);
        if (user == null) {
            throw new BusinessException(401, "未授权 — JWT 解析失败");
        }

        Pageable pageable = PageRequest.of(page - 1, size);
        Page<ApprovalWorkflowInstance> instances = workflowEngine.findParticipatedBy(
                factoryId, user.getId(), pageable);

        List<WorkflowInstancePendingDTO> dtos = hydrateInstances(factoryId, instances.getContent());

        PageResponse<WorkflowInstancePendingDTO> response = PageResponse.of(
                dtos, page, size, instances.getTotalElements());
        return ApiResponse.success(response);
    }

    /**
     * Sprint 6 W1-B — "工作流处理" admin view (super-admin only).
     *
     * <p>HJ ERP 工作流 6 sub-menu 之一. 列出当前 factory 下所有 RUNNING workflow
     * 实例 — 跨 module 跨 initiator 跨 role. 给 super-admin / platform_admin 看全局
     * 视角, 用于排查卡住的流程、跨部门协调、bulk admin 操作 (W4+ 加 force-cancel /
     * delegate).
     *
     * <p>差异 vs {@code /pending} (按 role 过滤) / {@code /my-created} (按 initiator
     * 过滤) / {@code /my-participated} (按 actor 过滤): 此 endpoint <b>无过滤</b>,
     * 返 factory 下全部 RUNNING. 因此必须用 {@code @PreAuthorize} 守门.
     *
     * <p>RBAC: 仅 {@code factory_super_admin} 和 {@code platform_admin} 可访问.
     * 普通审批角色 (finance_manager / quality_manager / ...) 应去 {@code /pending}.
     *
     * @param factoryId 工厂 id
     * @param authorization Bearer JWT (用于 hydrateInstances, 不做 user-level 过滤)
     * @param page 1-based 页码
     * @param size 默认 20, 上限 100
     * @return Page of RUNNING WorkflowInstancePendingDTO
     * @since 2026-05-19 (Sprint 6 W1-B)
     */
    @GetMapping("/admin-running")
    // @PreAuthorize 在本项目是 NO-OP (Spring Security method security 未启用) → 用 @RequireRole 真正守门。
    @RequireRole({"factory_super_admin", "platform_admin"})
    @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "工作流处理 admin 全局视图 — Sprint 6 W1-B (super-admin only)")
    public ApiResponse<PageResponse<WorkflowInstancePendingDTO>> getAdminRunning(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        // resolve user 仅用于审计日志, 不做过滤 (RBAC 在 @PreAuthorize 拦了非 admin)
        User user = resolveCurrentUser(authorization);
        if (user == null) {
            throw new BusinessException(401, "未授权 — JWT 解析失败");
        }
        log.info("admin-running 查询: factoryId={}, admin user={}, page={}, size={}",
                factoryId, user.getUsername(), page, size);

        Pageable pageable = PageRequest.of(page - 1, size);
        Page<ApprovalWorkflowInstance> instances = workflowEngine.listAllRunning(
                factoryId, pageable);

        List<WorkflowInstancePendingDTO> dtos = hydrateInstances(factoryId, instances.getContent());

        PageResponse<WorkflowInstancePendingDTO> response = PageResponse.of(
                dtos, page, size, instances.getTotalElements());
        return ApiResponse.success(response);
    }

    // ==================== internal helpers ====================

    /**
     * 批量 hydrate instance → DTO. 抽取自 {@link #getPendingForUser} 给 Sprint 5
     * Track A personal view endpoints 复用 (DRY — 避免 PO/user/node label 重复加载逻辑).
     */
    private List<WorkflowInstancePendingDTO> hydrateInstances(
            String factoryId, List<ApprovalWorkflowInstance> instances) {
        if (instances.isEmpty()) return List.of();

        // 1. 收 PO ids + initiator ids
        Set<String> poIds = new HashSet<>();
        Set<Long> initiatorIds = new HashSet<>();
        for (ApprovalWorkflowInstance inst : instances) {
            if ("PURCHASE_ORDER".equals(inst.getModuleCode()) && inst.getBusinessEntityId() != null) {
                poIds.add(inst.getBusinessEntityId());
            }
            if (inst.getInitiatedBy() != null) initiatorIds.add(inst.getInitiatedBy());
        }

        // 2. 批量 fetch PO
        Map<String, PurchaseOrder> poById = new HashMap<>();
        if (!poIds.isEmpty() && purchaseOrderRepository != null) {
            try {
                purchaseOrderRepository.findAllById(poIds).forEach(po -> poById.put(po.getId(), po));
            } catch (Exception e) {
                log.warn("批量加载 PO 失败 (businessSummary 退化为 fallback): {}", e.getMessage());
            }
        }

        // 3. 批量 fetch usernames
        Map<Long, String> usernameById = new HashMap<>();
        if (!initiatorIds.isEmpty()) {
            try {
                userRepository.findAllById(initiatorIds).forEach(
                        u -> usernameById.put(u.getId(), u.getUsername()));
            } catch (Exception e) {
                log.warn("批量加载 initiator username 失败: {}", e.getMessage());
            }
        }

        // 4. workflow nodes hydrate (currentNodeLabel) — 缓存按 workflowId
        Map<String, Map<String, ApprovalWorkflowNode>> wfNodesCache = new HashMap<>();

        // 5. 构造 DTO list
        List<WorkflowInstancePendingDTO> dtos = new ArrayList<>();
        for (ApprovalWorkflowInstance inst : instances) {
            WorkflowInstancePendingDTO dto = WorkflowInstancePendingDTO.builder()
                    .instanceId(inst.getId())
                    .moduleCode(inst.getModuleCode())
                    .businessEntityId(inst.getBusinessEntityId())
                    .initiatedAt(inst.getInitiatedAt())
                    .businessSummary(buildBusinessSummary(inst, poById))
                    .status(inst.getStatus().name())
                    .completedAt(inst.getCompletedAt())
                    .build();

            // currentNodeId + label
            if (inst.getCurrentNodeIds() != null && !inst.getCurrentNodeIds().isEmpty()) {
                String nodeId = inst.getCurrentNodeIds().get(0);
                dto.setCurrentNodeId(nodeId);
                Map<String, ApprovalWorkflowNode> byId = wfNodesCache.computeIfAbsent(
                        inst.getWorkflowId(),
                        wid -> loadNodes(factoryId, wid));
                ApprovalWorkflowNode node = byId.get(nodeId);
                if (node != null) {
                    dto.setCurrentNodeLabel(node.getLabel() != null
                            ? node.getLabel() : node.getId());
                    Object configuredRoles = node.getConfig() == null
                            ? null : node.getConfig().get("approverRoles");
                    if (configuredRoles instanceof Iterable<?> iterable) {
                        List<String> approverRoles = new ArrayList<>();
                        iterable.forEach(value -> approverRoles.add(String.valueOf(value)));
                        dto.setApproverRoles(approverRoles);
                    }
                }
            }

            // initiator username
            if (inst.getInitiatedBy() != null) {
                dto.setInitiatedByUsername(usernameById.get(inst.getInitiatedBy()));
            }

            dtos.add(dto);
        }

        return dtos;
    }

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
            // Phase 2 加销售订单 hydrate. 目前 fallback.
            return "销售订单 " + bizId;
        }
        // generic fallback
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
            log.warn("loadNodes 失败 (currentNodeLabel 退化为 nodeId) — workflowId={}, error={}",
                    workflowId, e.getMessage());
            return Map.of();
        }
    }

    private Long extractUserId(String authorization) {
        String token = TokenUtils.extractToken(authorization);
        return mobileService.getUserFromToken(token).getId();
    }

    private User resolveCurrentUser(String authorization) {
        try {
            Long userId = extractUserId(authorization);
            if (userId == null) return null;
            return userRepository.findById(userId).orElse(null);
        } catch (Exception e) {
            log.debug("resolveCurrentUser failed: {}", e.getMessage());
            return null;
        }
    }
}
