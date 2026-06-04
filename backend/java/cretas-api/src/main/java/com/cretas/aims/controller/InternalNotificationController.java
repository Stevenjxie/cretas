package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * #56 价值可视化回馈回路 — Python → Java 内部通知端点。
 *
 * <p>供 Python {@code value_notifier_client.notify_role_via_java} 调用, 创建角色
 * 定向站内通知 (复用 {@link NotificationService#notifyRole}, INFO 类型 — D5 RN 零改动)。
 *
 * <p>认证: {@code /api/internal/**} 由 {@code JwtAuthInterceptor} 统一以
 * {@code X-Internal-Key} 对比 {@code INTERNAL_API_SECRET} 环境变量校验 (与 Python
 * auth_middleware 同一密钥) — 到达本 controller 即已通过认证, 故此处不再重复校验
 * (避免与 onboarding 的独立 internal.api.key 双源密钥不一致导致误 403)。
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/notifications")
@RequiredArgsConstructor
public class InternalNotificationController {

    private final NotificationService notificationService;

    /**
     * 创建角色定向通知。
     *
     * <p>请求体: {@code {factoryId, role, title, body, actionUrl?, source?}}。
     * actionUrl/source 当前 NotificationService.notifyRole 不持久化 (D5 zero-change
     * 复用 INFO + source=SYSTEM); 透传字段保留供未来扩展, 不影响通知创建。
     */
    @PostMapping("/role")
    public ApiResponse<Void> notifyRole(@RequestBody Map<String, Object> request) {

        String factoryId = strOf(request.get("factoryId"));
        String role = strOf(request.get("role"));
        String title = strOf(request.get("title"));
        String body = strOf(request.get("body"));

        if (factoryId == null || factoryId.isBlank()
                || role == null || role.isBlank()
                || title == null || title.isBlank()) {
            return ApiResponse.error(400, "factoryId / role / title 必填");
        }

        notificationService.notifyRole(factoryId, role, title, body == null ? "" : body);
        log.info("[InternalNotification] role notification created factory={} role={} title={}",
                factoryId, role, title);
        return ApiResponse.successMessage("通知已创建");
    }

    private static String strOf(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
