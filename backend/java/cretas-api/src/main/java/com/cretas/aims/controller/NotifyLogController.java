package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.repository.notify.NotifyLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * Notify 发送日志查询 — Phase 3 Canvas-Notify Step T6 skeleton.
 *
 * <p>Read-only. RequireRole: factory_super_admin / permission_admin
 * (审计权限, 不让普通用户看到别人收到什么).
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/notify/logs")
@RequiredArgsConstructor
@RequireRole({"factory_super_admin", "permission_admin"})
@Tag(name = "Canvas-Notify Logs", description = "通知发送日志查询 (Phase 3 skeleton)")
public class NotifyLogController {

    private final NotifyLogRepository logRepo;

    @GetMapping
    @Operation(summary = "查询通知日志 (skeleton — sister 实施时加分页 / channel / status filter)")
    public ApiResponse<Page<NotifyLog>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.error(501,
                "NotifyLogController.list skeleton — Phase 3 sister chat: "
                        + "查 NotifyLogRepository 加 channel/status 过滤 + Pageable");
    }
}
