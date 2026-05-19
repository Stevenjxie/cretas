package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Canvas-Rules REST API (Phase 4a — pure auto business rule engine).
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §5
 *
 * NOTE — Name disambiguation: there is also a {@code BusinessRuleController} under the
 * same package which handles Canvas V2 validation rules / default values / formulas /
 * scheduler (older). This Canvas-Rules Phase 4a controller is named {@code CanvasRuleController}
 * to avoid bean name collision and class name confusion.
 *
 * ⚠️ SKELETON: endpoints throw UnsupportedOperationException. Sister chat to wire to
 * BusinessRuleService + RuleEngine + audit logging.
 *
 * Role gate: factory_super_admin / permission_admin only (config-tier change).
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas-rules")
@RequiredArgsConstructor
@Tag(name = "Canvas-Rules", description = "Canvas 业务规则引擎 (Phase 4a)")
public class CanvasRuleController {

    @Operation(summary = "列出所有业务规则", description = "可按 scope 过滤")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping
    public ApiResponse<Object> listRules(@PathVariable String factoryId,
                                         @RequestParam(required = false) String scope) {
        throw new UnsupportedOperationException(
                "CanvasRuleController.listRules not yet implemented (Phase 4a skeleton)");
    }

    @Operation(summary = "新建业务规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @PostMapping
    public ApiResponse<Object> createRule(@PathVariable String factoryId,
                                          @RequestBody Map<String, Object> body) {
        throw new UnsupportedOperationException(
                "CanvasRuleController.createRule not yet implemented (Phase 4a skeleton)");
    }

    @Operation(summary = "更新业务规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @PutMapping("/{id}")
    public ApiResponse<Object> updateRule(@PathVariable String factoryId,
                                          @PathVariable UUID id,
                                          @RequestBody Map<String, Object> body) {
        throw new UnsupportedOperationException(
                "CanvasRuleController.updateRule not yet implemented (Phase 4a skeleton)");
    }

    @Operation(summary = "切换规则启用/禁用")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @PostMapping("/{id}/toggle")
    public ApiResponse<Object> toggleRule(@PathVariable String factoryId,
                                          @PathVariable UUID id) {
        throw new UnsupportedOperationException(
                "CanvasRuleController.toggleRule not yet implemented (Phase 4a skeleton)");
    }

    @Operation(summary = "删除业务规则 (软删除)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @DeleteMapping("/{id}")
    public ApiResponse<Object> deleteRule(@PathVariable String factoryId,
                                          @PathVariable UUID id) {
        throw new UnsupportedOperationException(
                "CanvasRuleController.deleteRule not yet implemented (Phase 4a skeleton)");
    }

    @Operation(summary = "测试评估单条规则 (dry-run)",
               description = "对样本 input 跑一次 condition, 不写日志, 不真实修改")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @PostMapping("/{id}/test-evaluate")
    public ApiResponse<Object> testEvaluate(@PathVariable String factoryId,
                                            @PathVariable UUID id,
                                            @RequestBody Map<String, Object> sampleInput) {
        throw new UnsupportedOperationException(
                "CanvasRuleController.testEvaluate not yet implemented (Phase 4a skeleton)");
    }

    @Operation(summary = "查询规则执行历史", description = "分页, 倒序")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping("/{id}/logs")
    public ApiResponse<Object> listLogs(@PathVariable String factoryId,
                                        @PathVariable UUID id,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        throw new UnsupportedOperationException(
                "CanvasRuleController.listLogs not yet implemented (Phase 4a skeleton)");
    }
}
