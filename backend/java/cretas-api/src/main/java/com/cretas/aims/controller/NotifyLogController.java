package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import com.cretas.aims.repository.notify.NotifyLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

/**
 * Notify 发送日志查询 — Phase 3 Canvas-Notify Step T6 implementation.
 *
 * <p>Read-only. RequireRole: factory_super_admin / permission_admin
 * (审计权限, 不让普通用户看到别人收到什么).
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 * @since 2026-05-19 (Phase 3 paginated query)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/notify/logs")
@RequiredArgsConstructor
@RequireRole({"factory_super_admin", "permission_admin"})
@Tag(name = "Canvas-Notify Logs", description = "通知发送日志查询")
public class NotifyLogController {

    private final NotifyLogRepository logRepo;

    @GetMapping
    @Operation(summary = "查询通知日志 (分页, channel / status 过滤)")
    public ApiResponse<Page<NotifyLog>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long recipientUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // Cap page size to avoid abuse.
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);

        NotifyChannel channelEnum = null;
        if (channel != null && !channel.isBlank()) {
            try {
                channelEnum = NotifyChannel.valueOf(channel);
            } catch (IllegalArgumentException e) {
                return ApiResponse.error(400,
                        "未知 channel: " + channel + " (合法值: WECHAT/DINGTALK/EMAIL/SMS/IN_APP)");
            }
        }
        NotifyStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = NotifyStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                return ApiResponse.error(400,
                        "未知 status: " + status + " (合法值: SENT/FAILED)");
            }
        }

        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<NotifyLog> result = logRepo.findWithFilters(
                factoryId, channelEnum, statusEnum, recipientUserId, pageable);
        return ApiResponse.success(result);
    }
}
