package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.approval.CreateApprovalWorkflowRequest;
import com.cretas.aims.dto.approval.UpdateApprovalWorkflowRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.workflow.DecisionTypeMetadata;
import com.cretas.aims.service.workflow.DecisionTypeMetadataRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Graph-native 审批工作流控制器.
 *
 * <p>Sprint 3 Track-I (C-APPROVAL-EDITOR-1) 引入. 跟 {@link ApprovalChainController}
 * 互补 — Chain 管 flat list (legacy), Workflow 管 graph (new).
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-16
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/approval-workflows")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "审批工作流配置 (graph)",
     description = "Graph-native 审批工作流配置: nodes+edges JSONB, 支持 sequential/parallel/conditional/会签 N-of-M.")
public class ApprovalWorkflowController {

    private final ApprovalWorkflowService approvalWorkflowService;
    private final DecisionTypeMetadataRegistry decisionTypeMetadataRegistry;

    // ==================== CRUD ====================

    @GetMapping
    @Operation(summary = "获取工厂所有审批工作流")
    public ApiResponse<List<ApprovalWorkflow>> getAll(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId) {
        log.info("获取所有审批工作流 - factoryId={}", factoryId);
        return ApiResponse.success(approvalWorkflowService.getAllByFactory(factoryId));
    }

    @GetMapping("/by-type/{decisionType}")
    @Operation(summary = "根据决策类型获取工作流")
    public ApiResponse<List<ApprovalWorkflow>> getByDecisionType(
            @PathVariable String factoryId,
            @PathVariable String decisionType) {
        log.info("根据决策类型获取审批工作流 - factoryId={}, decisionType={}", factoryId, decisionType);
        DecisionType type;
        try {
            type = DecisionType.valueOf(decisionType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "无效的决策类型: " + decisionType +
                    "，有效值: " + Arrays.toString(DecisionType.values()))
                    .withHint("请使用合法的决策类型枚举值")
                    .withHintTarget("decisionType");
        }
        return ApiResponse.success(approvalWorkflowService.getByDecisionType(factoryId, type));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取单个工作流详情")
    public ApiResponse<ApprovalWorkflow> getById(
            @PathVariable String factoryId,
            @PathVariable String id) {
        log.info("获取审批工作流详情 - factoryId={}, id={}", factoryId, id);
        ApprovalWorkflow workflow = approvalWorkflowService.getById(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("审批工作流", "id", id));
        return ApiResponse.success(workflow);
    }

    @RequirePermission({"system:read_write"})
    @PostMapping
    @Operation(summary = "创建审批工作流")
    public ApiResponse<ApprovalWorkflow> create(
            @PathVariable String factoryId,
            @Valid @RequestBody CreateApprovalWorkflowRequest request) {
        log.info("创建审批工作流 - factoryId={}, name={}", factoryId, request.getName());
        ApprovalWorkflow workflow = toEntity(request);
        ApprovalWorkflow created = approvalWorkflowService.create(factoryId, workflow);
        return ApiResponse.success("审批工作流创建成功", created);
    }

    @RequirePermission({"system:read_write"})
    @PutMapping("/{id}")
    @Operation(summary = "更新审批工作流")
    public ApiResponse<ApprovalWorkflow> update(
            @PathVariable String factoryId,
            @PathVariable String id,
            @Valid @RequestBody UpdateApprovalWorkflowRequest request) {
        log.info("更新审批工作流 - factoryId={}, id={}", factoryId, id);
        ApprovalWorkflow partial = toPartialEntity(request);
        ApprovalWorkflow updated = approvalWorkflowService.update(factoryId, id, partial);
        return ApiResponse.success("审批工作流更新成功", updated);
    }

    @RequirePermission({"system:read_write"})
    @DeleteMapping("/{id}")
    @Operation(summary = "删除审批工作流 (软删除)")
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable String id) {
        log.info("删除审批工作流 - factoryId={}, id={}", factoryId, id);
        approvalWorkflowService.delete(factoryId, id);
        return ApiResponse.successMessage("审批工作流已删除");
    }

    // ==================== Lifecycle ====================

    @RequirePermission({"system:read_write"})
    @PostMapping("/{id}/clone-draft")
    @Operation(summary = "从已发布或历史版本克隆独立草稿")
    public ApiResponse<ApprovalWorkflow> cloneDraft(
            @PathVariable String factoryId,
            @PathVariable String id) {
        log.info("审批工作流克隆草稿 - factoryId={}, sourceId={}", factoryId, id);
        return ApiResponse.success(
                "已克隆为新草稿",
                approvalWorkflowService.cloneAsDraft(factoryId, id));
    }

    @RequirePermission({"system:read_write"})
    @PatchMapping("/{id}/publish")
    @Operation(summary = "发布草稿 (draft → published)")
    public ApiResponse<ApprovalWorkflow> publish(
            @PathVariable String factoryId,
            @PathVariable String id) {
        log.info("发布审批工作流 - factoryId={}, id={}", factoryId, id);
        return ApiResponse.success("审批工作流已发布", approvalWorkflowService.publishDraft(factoryId, id));
    }

    @RequirePermission({"system:read_write"})
    @PatchMapping("/{id}/archive")
    @Operation(summary = "归档 (published → archived)")
    public ApiResponse<ApprovalWorkflow> archive(
            @PathVariable String factoryId,
            @PathVariable String id) {
        log.info("归档审批工作流 - factoryId={}, id={}", factoryId, id);
        return ApiResponse.success("审批工作流已归档", approvalWorkflowService.archive(factoryId, id));
    }

    @RequirePermission({"system:read_write"})
    @PatchMapping("/{id}/toggle")
    @Operation(summary = "切换启用/禁用")
    public ApiResponse<ApprovalWorkflow> toggle(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestParam boolean enabled) {
        log.info("切换审批工作流状态 - factoryId={}, id={}, enabled={}", factoryId, id, enabled);
        return ApiResponse.success(enabled ? "工作流已启用" : "工作流已禁用",
                approvalWorkflowService.toggleEnabled(factoryId, id, enabled));
    }

    // ==================== Validation ====================

    @RequirePermission({"system:read_write"})
    @PostMapping("/validate")
    @Operation(summary = "校验工作流结构 (不入库)")
    public ApiResponse<Map<String, Object>> validate(
            @PathVariable String factoryId,
            @Valid @RequestBody CreateApprovalWorkflowRequest request) {
        log.info("校验审批工作流 - factoryId={}", factoryId);
        ApprovalWorkflow workflow = toEntity(request);
        workflow.setFactoryId(factoryId);
        return ApiResponse.success(approvalWorkflowService.validateGraph(workflow));
    }

    @GetMapping("/statistics")
    @Operation(summary = "获取工作流统计信息")
    public ApiResponse<Map<String, Object>> statistics(
            @PathVariable String factoryId) {
        log.info("获取审批工作流统计 - factoryId={}", factoryId);
        Map<DecisionType, Long> stats = approvalWorkflowService.getConfigStatistics(factoryId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", stats);
        result.put("totalTypes", stats.size());
        result.put("totalWorkflows", stats.values().stream().mapToLong(Long::longValue).sum());
        return ApiResponse.success(result);
    }

    @GetMapping("/decision-types")
    @Operation(summary = "获取所有决策类型 (复用 ApprovalChainConfig.DecisionType enum)")
    public ApiResponse<DecisionType[]> getDecisionTypes(@PathVariable String factoryId) {
        return ApiResponse.success(DecisionType.values());
    }

    /**
     * Sprint 6 W3-B (2026-05-19): 返回 32 个 DecisionType 的完整元数据 — 中文名/分类/
     * 默认审批角色/moduleCode/wired flag. Admin UI dropdown 直接渲染中文标签 +
     * 显示"已接入业务"badge.
     *
     * <p>取代原 {@link #getDecisionTypes} 仅返 enum string 的方式 (admin UI 早期
     * hardcode 12 个中文 label, Sprint 5 PR #55 H 扩到 32 后 admin UI 漏 20 个).
     */
    @GetMapping("/decision-types-meta")
    @Operation(summary = "获取所有决策类型完整元数据 (Sprint 6 W3-B — 中文名/分类/默认角色)")
    public ApiResponse<List<DecisionTypeMetadata>> getDecisionTypesMetadata(@PathVariable String factoryId) {
        log.debug("获取 DecisionType 元数据 - factoryId={}, count={}, wired={}",
                factoryId, decisionTypeMetadataRegistry.size(), decisionTypeMetadataRegistry.getWiredCount());
        // 按 enum declaration order 输出 (EnumMap 保 enum order), admin UI 分类 group-by category
        return ApiResponse.success(List.copyOf(decisionTypeMetadataRegistry.getAll().values()));
    }

    // ==================== Wire → entity mappers (Rule 17.1 pattern) ====================

    private ApprovalWorkflow toEntity(CreateApprovalWorkflowRequest r) {
        ApprovalWorkflow w = new ApprovalWorkflow();
        w.setDecisionType(r.getDecisionType());
        w.setName(r.getName());
        w.setDescription(r.getDescription());
        w.setNodesJson(approvalWorkflowService.serializeNodes(r.getNodes()));
        w.setEdgesJson(approvalWorkflowService.serializeEdges(r.getEdges()));
        w.setStartNodeId(r.getStartNodeId());
        w.setPriority(r.getPriority());
        w.setEnabled(r.getEnabled());
        return w;
    }

    private ApprovalWorkflow toPartialEntity(UpdateApprovalWorkflowRequest r) {
        ApprovalWorkflow w = new ApprovalWorkflow();
        w.setName(r.getName());
        w.setDescription(r.getDescription());
        // nodes/edges 只在非 null 时 serialize (PATCH 语义)
        if (r.getNodes() != null) {
            w.setNodesJson(approvalWorkflowService.serializeNodes(r.getNodes()));
        }
        if (r.getEdges() != null) {
            w.setEdgesJson(approvalWorkflowService.serializeEdges(r.getEdges()));
        }
        w.setStartNodeId(r.getStartNodeId());
        w.setPriority(r.getPriority());
        w.setEnabled(r.getEnabled());
        return w;
    }
}
