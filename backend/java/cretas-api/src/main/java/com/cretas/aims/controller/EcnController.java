package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.bom.EcnApprovalRequest;
import com.cretas.aims.dto.bom.EcnCreateRequest;
import com.cretas.aims.dto.bom.EcnImpactCalculateRequest;
import com.cretas.aims.dto.bom.EcnImpactReport;
import com.cretas.aims.dto.bom.EcnRejectRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.bom.EngineeringChangeNotice;
import com.cretas.aims.entity.bom.EngineeringChangeNotice.EcnReason;
import com.cretas.aims.entity.bom.EngineeringChangeNotice.EcnStatus;
import com.cretas.aims.service.bom.ECNService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * ECN REST Controller (Sprint 3 Track-H / M-BOM-VER-1).
 *
 * <p>路径前缀: {@code /api/mobile/{factoryId}/bom/ecns}.
 *
 * <p>5 reason 类: CUSTOMER_REQUEST / MATERIAL_DISCONTINUED / COST_OPTIMIZATION /
 * QUALITY_DEFECT / PROCESS_IMPROVEMENT.
 *
 * <p>RBAC: Write 方法 (POST) 沿用 {@link BomController} / {@link BomRecipeController} 模式 —
 * {@code @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})}.
 * SCHEMA spec 提议的 {@code bom:write} 新权限码暂未引入 role binding, 故复用 production:* 系列.
 * Read 方法 (GET) 不加 @RequirePermission, 由 @RequireModule("bom") 控制模块启用即可.
 *
 * <p>Issue #710 fix (2026-05-16): 补全 7 个 write endpoint 缺失的 RBAC, 防止低权限 F006 用户
 * (viewer/operator/quality_insp/...) 直接调 approve/reject 绕过审批 (P0 RBAC bypass).
 *
 * @author Cretas Team / Sprint 3 Track-H
 * @since 2026-05-16
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/bom/ecns")
@RequiredArgsConstructor
@Tag(name = "BOM 工程变更通知 (ECN, M-BOM-VER-1)",
     description = "5 reason ECN 状态机 + 审批 + 通知 + 影响范围分析")
@RequireModule("bom")
public class EcnController {

    private final ECNService ecnService;

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping
    @Operation(summary = "创建 ECN DRAFT (auto generateEcnNumber ECN-YYYY-NNNN)")
    public ApiResponse<EngineeringChangeNotice> create(
            @PathVariable String factoryId,
            @Valid @RequestBody EcnCreateRequest req) {
        // factoryId from path takes precedence over body (multi-tenant安全).
        req.setFactoryId(factoryId);
        return ApiResponse.success(ecnService.create(req));
    }

    @GetMapping("/{ecnId}")
    @Operation(summary = "ECN 详情")
    public ApiResponse<EngineeringChangeNotice> getById(
            @PathVariable String factoryId,
            @PathVariable String ecnId) {
        return ApiResponse.success(ecnService.getById(factoryId, ecnId));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/{ecnId}/submit")
    @Operation(summary = "提交审批 (DRAFT → SUBMITTED) — 触发 notifyImpactedRoles")
    public ApiResponse<EngineeringChangeNotice> submitForApproval(
            @PathVariable String factoryId,
            @PathVariable String ecnId) {
        return ApiResponse.success(ecnService.submitForApproval(factoryId, ecnId));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/{ecnId}/approve")
    @Operation(summary = "审批通过 (SUBMITTED → APPROVED) — cascade approve BomVersion DRAFT")
    public ApiResponse<EngineeringChangeNotice> approve(
            @PathVariable String factoryId,
            @PathVariable String ecnId,
            @Valid @RequestBody EcnApprovalRequest req) {
        return ApiResponse.success(ecnService.approve(factoryId, ecnId, req.getApproverId()));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/{ecnId}/reject")
    @Operation(summary = "拒绝 (SUBMITTED → REJECTED, terminal) — cascade reject BomVersion DRAFT")
    public ApiResponse<EngineeringChangeNotice> reject(
            @PathVariable String factoryId,
            @PathVariable String ecnId,
            @Valid @RequestBody EcnRejectRequest req) {
        return ApiResponse.success(ecnService.reject(factoryId, ecnId, req.getApproverId(),
                req.getRejectionReason()));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/{ecnId}/notify")
    @Operation(summary = "重发通知给 notify_roles (幂等)")
    public ApiResponse<Void> notifyImpactedRoles(
            @PathVariable String factoryId,
            @PathVariable String ecnId) {
        ecnService.notifyImpactedRoles(factoryId, ecnId);
        return ApiResponse.success(null);
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/calculate-impact")
    @Operation(summary = "计算 ECN 影响范围 (read-only, 不创建 ECN)")
    public ApiResponse<EcnImpactReport> calculateImpact(
            @PathVariable String factoryId,
            @Valid @RequestBody EcnImpactCalculateRequest req) {
        return ApiResponse.success(ecnService.calculateImpact(factoryId, req.getBomRecipeId(),
                req.getChangeContext()));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/activate-due")
    @Operation(summary = "Housekeeping: APPROVED + effective_date ≤ today → EFFECTIVE")
    public ApiResponse<Integer> activateDueApprovedEcns(
            @PathVariable String factoryId) {
        int count = ecnService.activateDueApprovedEcns(factoryId);
        return ApiResponse.success(count);
    }

    /**
     * Sprint 6 W4-C — Paginated ECN list with optional filters.
     * 防呆 Rule 5: 默认 page=1 size=20, 前端可不传分页参数. status / reason / bomRecipeId 都可选.
     */
    @GetMapping
    @Operation(summary = "ECN paginated list (Sprint 6 W4-C)",
            description = "分页查询 ECN, 支持 status / reason / bomRecipeId 过滤. 默认 size=20.")
    public ApiResponse<PageResponse<EngineeringChangeNotice>> listEcns(
            @PathVariable String factoryId,
            @Valid PageRequest pageRequest,
            @Parameter(description = "可选 status 过滤")
            @RequestParam(required = false) EcnStatus status,
            @Parameter(description = "可选 reason 过滤")
            @RequestParam(required = false) EcnReason reason,
            @Parameter(description = "可选 BOM Recipe ID 过滤 (查某 BOM 的全部 ECN)")
            @RequestParam(required = false) String bomRecipeId) {
        return ApiResponse.success(ecnService.listEcns(factoryId, pageRequest, status, reason, bomRecipeId));
    }
}
