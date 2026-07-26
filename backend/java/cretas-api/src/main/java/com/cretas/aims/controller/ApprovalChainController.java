package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.approval.CreateApprovalChainConfigRequest;
import com.cretas.aims.dto.approval.UpdateApprovalChainConfigRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.config.ApprovalChainConfig;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.service.ApprovalChainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批链路配置控制器
 *
 * 提供审批链配置的 REST API:
 * - CRUD 操作
 * - 决策类型查询
 * - 权限验证
 * - 配置统计
 *
 * R68-FIX-D (2026-04-29): 重写 — 之前手撸 {success/data/total} Map 绕过 ApiResponse
 * 标准 envelope (R67-BUG-4: 缺 actionHint/severity/hintTarget). 现在改用 ApiResponse<T>
 * 直接返回, 异常由 GlobalExceptionHandler 接管, 业务层 throw typed BusinessException.
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-12-30
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/approval-chains")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "审批链路配置管理", description = "审批链配置的REST API，包括CRUD操作、决策类型查询、权限验证、配置统计等功能")
public class ApprovalChainController {

    private final ApprovalChainService approvalChainService;

    // ==================== CRUD 操作 ====================

    @GetMapping
    @Operation(summary = "获取所有审批链配置")
    public ApiResponse<List<ApprovalChainConfig>> getAllConfigs(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId) {
        log.info("获取所有审批链配置 - factoryId={}", factoryId);
        return ApiResponse.success(approvalChainService.getAllConfigs(factoryId));
    }

    @GetMapping("/by-type/{decisionType}")
    @Operation(summary = "根据决策类型获取配置")
    public ApiResponse<List<ApprovalChainConfig>> getConfigsByType(
            @PathVariable String factoryId,
            @PathVariable @Parameter(description = "决策类型", example = "QUALITY_DISPOSITION") String decisionType) {
        log.info("根据类型获取审批链配置 - factoryId={}, decisionType={}", factoryId, decisionType);
        DecisionType type;
        try {
            type = DecisionType.valueOf(decisionType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "无效的决策类型: " + decisionType +
                    "，有效值: " + Arrays.toString(DecisionType.values()))
                    .withHint("请使用合法的决策类型枚举值")
                    .withHintTarget("decisionType");
        }
        return ApiResponse.success(approvalChainService.getConfigsByDecisionType(factoryId, type));
    }

    @GetMapping("/{configId}")
    @Operation(summary = "获取单个配置详情")
    public ApiResponse<ApprovalChainConfig> getConfig(
            @PathVariable String factoryId,
            @PathVariable @Parameter(description = "配置ID", example = "AC001") String configId) {
        log.info("获取审批链配置详情 - factoryId={}, configId={}", factoryId, configId);
        ApprovalChainConfig config = approvalChainService.getConfig(factoryId, configId)
                .orElseThrow(() -> new ResourceNotFoundException("审批链配置", "id", configId));
        return ApiResponse.success(config);
    }

    @RequirePermission({"system:read_write"})
    @PostMapping
    @Operation(summary = "创建审批链配置")
    public ApiResponse<ApprovalChainConfig> createConfig(
            @PathVariable String factoryId,
            @Valid @RequestBody CreateApprovalChainConfigRequest request) {
        throw legacyMutationRemoved();
    }

    @RequirePermission({"system:read_write"})
    @PutMapping("/{configId}")
    @Operation(summary = "更新审批链配置")
    public ApiResponse<ApprovalChainConfig> updateConfig(
            @PathVariable String factoryId,
            @PathVariable String configId,
            @Valid @RequestBody UpdateApprovalChainConfigRequest request) {
        throw legacyMutationRemoved();
    }

    @RequirePermission({"system:read_write"})
    @DeleteMapping("/{configId}")
    @Operation(summary = "删除审批链配置")
    public ApiResponse<Void> deleteConfig(
            @PathVariable String factoryId,
            @PathVariable String configId) {
        throw legacyMutationRemoved();
    }

    @RequirePermission({"system:read_write"})
    @PatchMapping("/{configId}/toggle")
    @Operation(summary = "切换审批链配置状态")
    public ApiResponse<ApprovalChainConfig> toggleEnabled(
            @PathVariable String factoryId,
            @PathVariable String configId,
            @RequestParam boolean enabled) {
        if (enabled) {
            throw legacyMutationRemoved();
        }
        log.info("停用旧版审批配置 - factoryId={}, configId={}", factoryId, configId);
        ApprovalChainConfig updated = approvalChainService.toggleEnabled(factoryId, configId, enabled);
        return ApiResponse.success("旧版审批配置已停用", updated);
    }

    // ==================== 审批判断 ====================

    @RequirePermission({"system:read_write"})
    @PostMapping("/check-required")
    @Operation(summary = "检查是否需要审批")
    public ApiResponse<Map<String, Object>> checkApprovalRequired(
            @PathVariable String factoryId,
            @RequestParam DecisionType decisionType,
            @RequestBody(required = false) Map<String, Object> context) {
        log.info("检查是否需要审批 - factoryId={}, decisionType={}", factoryId, decisionType);
        boolean required = approvalChainService.requiresApproval(
                factoryId, decisionType, context != null ? context : new HashMap<>());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requiresApproval", required);
        result.put("decisionType", decisionType);
        if (required) {
            approvalChainService.getFirstLevelConfig(factoryId, decisionType)
                    .ifPresent(config -> result.put("firstLevelConfig", config));
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/{configId}/check-permission")
    @Operation(summary = "验证用户审批权限")
    public ApiResponse<Map<String, Object>> checkPermission(
            @PathVariable String factoryId,
            @PathVariable String configId,
            @RequestParam Long userId,
            @RequestParam String userRole) {
        log.info("验证审批权限 - factoryId={}, configId={}, userId={}, userRole={}",
                factoryId, configId, userId, userRole);
        ApprovalChainConfig config = approvalChainService.getConfig(factoryId, configId)
                .orElseThrow(() -> new ResourceNotFoundException("审批链配置", "id", configId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasPermission", approvalChainService.hasApprovalPermission(config, userId, userRole));
        result.put("configId", configId);
        result.put("userId", userId);
        return ApiResponse.success(result);
    }

    // ==================== 统计与验证 ====================

    @GetMapping("/statistics")
    @Operation(summary = "获取配置统计信息")
    public ApiResponse<Map<String, Object>> getStatistics(
            @PathVariable String factoryId) {
        log.info("获取审批链配置统计 - factoryId={}", factoryId);
        Map<DecisionType, Long> stats = approvalChainService.getConfigStatistics(factoryId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", stats);
        result.put("totalTypes", stats.size());
        result.put("totalConfigs", stats.values().stream().mapToLong(Long::longValue).sum());
        return ApiResponse.success(result);
    }

    @RequirePermission({"system:read_write"})
    @PostMapping("/validate")
    @Operation(summary = "验证审批链配置")
    public ApiResponse<Map<String, Object>> validateConfig(
            @PathVariable String factoryId,
            @Valid @RequestBody CreateApprovalChainConfigRequest request) {
        throw legacyMutationRemoved();
    }

    @GetMapping("/decision-types")
    @Operation(summary = "获取所有决策类型")
    public ApiResponse<DecisionType[]> getDecisionTypes(
            @PathVariable String factoryId) {
        log.info("获取所有决策类型 - factoryId={}", factoryId);
        return ApiResponse.success(DecisionType.values());
    }

    private BusinessException legacyMutationRemoved() {
        return new BusinessException(410, "旧版审批配置已停止编辑")
                .withCode("OA_LEGACY_CONFIG_READ_ONLY")
                .withHint("请前往系统设置 → 审批业务，在对应业务的审批画布中配置");
    }

    // ===================================================================
    // Rule 17.1 — wire→entity mappers (Issue #384 batch 6 final).
    // Mapper deliberately does NOT replicate service-owned defaults:
    //   - factoryId from @PathVariable (set by service createConfig)
    //   - id Hibernate UUID2 generated (never accepted from wire)
    //   - requiredApprovers default 1 (service-owned)
    //   - priority default 0 (service-owned)
    //   - enabled default true (service-owned)
    //   - version default 1 (service-owned)
    //   - audit timestamps BaseEntity-managed
    // ===================================================================

    /**
     * Map {@link CreateApprovalChainConfigRequest} → {@link ApprovalChainConfig}.
     *
     * <p>Used for both {@code POST /approval-chains} (create) and
     * {@code POST /approval-chains/validate} (validation-only dry run).
     */
    private static ApprovalChainConfig toApprovalChainConfig(CreateApprovalChainConfigRequest r) {
        ApprovalChainConfig c = new ApprovalChainConfig();
        c.setDecisionType(r.getDecisionType());
        c.setName(r.getName());
        c.setDescription(r.getDescription());
        c.setTriggerCondition(r.getTriggerCondition());
        c.setApprovalLevel(r.getApprovalLevel());
        c.setRequiredApprovers(r.getRequiredApprovers());
        c.setApproverRoles(r.getApproverRoles());
        c.setApproverUserIds(r.getApproverUserIds());
        c.setTimeoutMinutes(r.getTimeoutMinutes());
        c.setEscalationConfigId(r.getEscalationConfigId());
        c.setAutoApproveCondition(r.getAutoApproveCondition());
        c.setAutoRejectCondition(r.getAutoRejectCondition());
        c.setPriority(r.getPriority());
        c.setEnabled(r.getEnabled());
        return c;
    }

    /**
     * Map {@link UpdateApprovalChainConfigRequest} → partial {@link ApprovalChainConfig}.
     *
     * <p>The resulting entity carries only the mutable fields. Service iterates
     * each field with an {@code if (... != null)} guard, so leaving a wire
     * field absent preserves the existing DB value.
     */
    private static ApprovalChainConfig toPartialApprovalChainConfig(
            UpdateApprovalChainConfigRequest r) {
        ApprovalChainConfig c = new ApprovalChainConfig();
        c.setName(r.getName());
        c.setDescription(r.getDescription());
        c.setTriggerCondition(r.getTriggerCondition());
        c.setApprovalLevel(r.getApprovalLevel());
        c.setRequiredApprovers(r.getRequiredApprovers());
        c.setApproverRoles(r.getApproverRoles());
        c.setApproverUserIds(r.getApproverUserIds());
        c.setTimeoutMinutes(r.getTimeoutMinutes());
        c.setEscalationConfigId(r.getEscalationConfigId());
        c.setAutoApproveCondition(r.getAutoApproveCondition());
        c.setAutoRejectCondition(r.getAutoRejectCondition());
        c.setPriority(r.getPriority());
        return c;
    }
}
