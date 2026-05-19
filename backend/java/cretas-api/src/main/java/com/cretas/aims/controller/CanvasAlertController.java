package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 告警规则 / 事件 REST 接口 — Phase 2 Canvas-Alerts skeleton.
 *
 * <p>注: 类名 {@code CanvasAlertController} 区分老的 {@link AlertController}
 * (生产告警 GET /api/mobile/{f}/alerts/summary 等). 本 Controller 服务
 * Phase 2 Canvas-Alerts 可配置告警规则 + 事件管理, 路径前缀 {@code /rules} 和
 * {@code /events} 跟老 controller 不冲突.
 *
 * <p>所有 endpoint 当前返 {@code ApiResponse.error(501, ...)}.
 * Sister chat 在 Phase 2 B-2 替换为真实业务逻辑.
 *
 * <p><b>RBAC</b>: factory_super_admin / permission_admin 才能 CRUD 规则 +
 * 查询事件. 普通用户通过 dashboard / push notification 收事件.
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/alerts")
@Tag(name = "Canvas-告警规则", description = "Phase 2 Canvas-Alerts — 预警规则配置 + 事件管理")
@RequiredArgsConstructor
public class CanvasAlertController {

    private static final String NOT_IMPL_MSG = "Phase 2 sister chat impl pending";
    private static final String NOT_IMPL_CODE = "NOT_IMPLEMENTED";

    @GetMapping("/rules")
    @Operation(summary = "列出告警规则", description = "返回工厂全部告警规则 (含 disabled)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Object> listRules(@PathVariable String factoryId) {
        log.info("[skeleton] GET /alerts/rules factoryId={}", factoryId);
        return ApiResponse.error(501, NOT_IMPL_MSG + " (" + NOT_IMPL_CODE + ")");
    }

    @PostMapping("/rules")
    @Operation(summary = "创建告警规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Object> createRule(@PathVariable String factoryId,
                                          @RequestBody Map<String, Object> body) {
        log.info("[skeleton] POST /alerts/rules factoryId={} body={}", factoryId, body);
        return ApiResponse.error(501, NOT_IMPL_MSG + " (" + NOT_IMPL_CODE + ")");
    }

    @PutMapping("/rules/{id}")
    @Operation(summary = "更新告警规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Object> updateRule(@PathVariable String factoryId,
                                          @PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        log.info("[skeleton] PUT /alerts/rules/{} factoryId={} body={}", id, factoryId, body);
        return ApiResponse.error(501, NOT_IMPL_MSG + " (" + NOT_IMPL_CODE + ")");
    }

    @PostMapping("/rules/{id}/toggle")
    @Operation(summary = "启用/禁用 告警规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Object> toggleRule(@PathVariable String factoryId,
                                          @PathVariable String id) {
        log.info("[skeleton] POST /alerts/rules/{}/toggle factoryId={}", id, factoryId);
        return ApiResponse.error(501, NOT_IMPL_MSG + " (" + NOT_IMPL_CODE + ")");
    }

    @DeleteMapping("/rules/{id}")
    @Operation(summary = "删除告警规则 (软删)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Object> deleteRule(@PathVariable String factoryId,
                                          @PathVariable String id) {
        log.info("[skeleton] DELETE /alerts/rules/{} factoryId={}", id, factoryId);
        return ApiResponse.error(501, NOT_IMPL_MSG + " (" + NOT_IMPL_CODE + ")");
    }

    @GetMapping("/events")
    @Operation(summary = "查询告警事件 (分页)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Object> listEvents(@PathVariable String factoryId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        log.info("[skeleton] GET /alerts/events factoryId={} status={} page={} size={}",
                factoryId, status, page, size);
        return ApiResponse.error(501, NOT_IMPL_MSG + " (" + NOT_IMPL_CODE + ")");
    }
}
