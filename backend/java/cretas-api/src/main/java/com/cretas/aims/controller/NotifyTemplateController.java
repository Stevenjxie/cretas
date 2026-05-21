package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.NotifySenderRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Notify 模板 CRUD — Canvas-Notify (Phase 3 → Phase 3 follow-up).
 *
 * <p>5 endpoints:
 * <ul>
 *   <li>GET list — list all factory templates</li>
 *   <li>POST create — create new template (real impl, replaces Phase 3 stub)</li>
 *   <li>PUT update — update existing template (real impl with optimistic lock)</li>
 *   <li>DELETE — soft-delete (real impl)</li>
 *   <li>POST test-send — fan-out via {@link NotifySenderRegistry}</li>
 * </ul>
 *
 * <p><b>RequireRole</b>: factory_super_admin / permission_admin.
 *
 * <p><b>AUD-4 optimistic lock</b>: update uses NotifyTemplate.version (added by V20260626_02).
 * Client submits {@code version} in body; mismatch returns 409 with refresh hint.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 * @since 2026-05-21 (CRUD stubs filled — Phase 3 follow-up issue #41 partial close)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/notify/templates")
@RequiredArgsConstructor
@RequireRole({"factory_super_admin", "permission_admin"})
@Tag(name = "Canvas-Notify Templates", description = "通知模板 CRUD")
public class NotifyTemplateController {

    private final NotifyTemplateRepository templateRepo;
    private final NotifySenderRegistry notifySenderRegistry;

    /**
     * Explicit length cap mirrors PG column width in {@code notify_templates} table
     * (see {@link NotifyTemplate#getTemplateCode()} {@code @Column(length=100)}).
     */
    private static final int TEMPLATE_CODE_MAX_LENGTH = 100;
    private static final int TITLE_MAX_LENGTH = 255;

    @GetMapping
    @Operation(summary = "列出工厂所有通知模板")
    public ApiResponse<List<NotifyTemplate>> list(@PathVariable String factoryId) {
        return ApiResponse.success(templateRepo.findByFactoryId(factoryId));
    }

    @PostMapping
    @Operation(summary = "创建通知模板",
               description = "factoryId + templateCode 必填. UNIQUE 冲突返 409 + actionHint.")
    public ApiResponse<NotifyTemplate> create(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body) {
        log.info("POST /notify/templates factoryId={} bodyKeys={}", factoryId, body.keySet());

        // templateCode 必填 + 长度
        String templateCode = stringField(body, "templateCode");
        if (templateCode == null || templateCode.isBlank()) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "templateCode 必填",
                    "请提供模板 code (如 PO_APPROVAL_PENDING)", "warning");
        }
        if (templateCode.length() > TEMPLATE_CODE_MAX_LENGTH) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "templateCode 最长 " + TEMPLATE_CODE_MAX_LENGTH
                            + " 字符 (当前 " + templateCode.length() + ")",
                    "请使用更短的 templateCode", "warning");
        }

        // title 长度 (可选, 但有上限)
        String title = stringField(body, "title");
        if (title != null && title.length() > TITLE_MAX_LENGTH) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "title 最长 " + TITLE_MAX_LENGTH + " 字符 (当前 " + title.length() + ")",
                    "请使用更短的 title", "warning");
        }

        // UNIQUE 冲突预检
        Optional<NotifyTemplate> dup =
                templateRepo.findByFactoryIdAndTemplateCode(factoryId, templateCode);
        if (dup.isPresent()) {
            return ApiResponse.errorWithCode(409, "DUPLICATE",
                    "模板 code 已存在: " + templateCode + " (id=" + dup.get().getId() + ")",
                    "请换一个唯一的 templateCode, 或编辑现有模板",
                    "warning");
        }

        List<NotifyChannel> channels = parseChannels(body.get("channels"));
        Map<String, Object> variablesSchema = mapField(body, "variablesSchemaJson");

        NotifyTemplate template = NotifyTemplate.builder()
                .factoryId(factoryId)
                .templateCode(templateCode)
                .title(title)
                .bodyTemplate(stringField(body, "bodyTemplate"))
                .channels(channels)
                .variablesSchemaJson(variablesSchema)
                .build();

        NotifyTemplate saved = templateRepo.save(template);
        log.info("Created NotifyTemplate: id={}, factoryId={}, templateCode={}",
                saved.getId(), factoryId, templateCode);
        return ApiResponse.success("通知模板创建成功", saved);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新通知模板",
               description = "支持 AUD-4 optimistic lock — 提交 version 字段, 不匹配返 409.")
    public ApiResponse<NotifyTemplate> update(
            @PathVariable String factoryId,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        log.info("PUT /notify/templates/{} factoryId={} bodyKeys={}", id, factoryId, body.keySet());

        Optional<NotifyTemplate> existingOpt = templateRepo.findById(id);
        if (existingOpt.isEmpty() || !factoryId.equals(existingOpt.get().getFactoryId())) {
            // factoryId 不一致 → 模板属于别工厂, 当 404 处理 (防止 IDOR)
            return ApiResponse.errorWithCode(404, "TEMPLATE_NOT_FOUND",
                    "通知模板不存在: id=" + id + " (factoryId=" + factoryId + ")",
                    "请刷新页面或检查模板 id",
                    "warning");
        }
        NotifyTemplate existing = existingOpt.get();

        // AUD-4 optimistic lock check
        ApiResponse<NotifyTemplate> versionError =
                checkVersion(body.get("version"), existing.getVersion());
        if (versionError != null) {
            return versionError;
        }

        // 字段更新 (允许 partial update — 只更新 body 中显式提供的字段)
        if (body.containsKey("title")) {
            String title = stringField(body, "title");
            if (title != null && title.length() > TITLE_MAX_LENGTH) {
                return ApiResponse.errorWithCode(400, "VALIDATION",
                        "title 最长 " + TITLE_MAX_LENGTH + " 字符 (当前 " + title.length() + ")",
                        "请使用更短的 title", "warning");
            }
            existing.setTitle(title);
        }
        if (body.containsKey("bodyTemplate")) {
            existing.setBodyTemplate(stringField(body, "bodyTemplate"));
        }
        if (body.containsKey("channels")) {
            existing.setChannels(parseChannels(body.get("channels")));
        }
        if (body.containsKey("variablesSchemaJson")) {
            existing.setVariablesSchemaJson(mapField(body, "variablesSchemaJson"));
        }
        // templateCode is immutable (it's the natural key); ignore if present in body

        NotifyTemplate saved = templateRepo.save(existing);
        log.info("Updated NotifyTemplate: id={}, factoryId={}, version={}",
                saved.getId(), factoryId, saved.getVersion());
        return ApiResponse.success("通知模板更新成功", saved);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "软删除通知模板 (set deleted_at)")
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable UUID id) {
        log.info("DELETE /notify/templates/{} factoryId={}", id, factoryId);

        Optional<NotifyTemplate> existingOpt = templateRepo.findById(id);
        if (existingOpt.isEmpty() || !factoryId.equals(existingOpt.get().getFactoryId())) {
            return ApiResponse.errorWithCode(404, "TEMPLATE_NOT_FOUND",
                    "通知模板不存在: id=" + id + " (factoryId=" + factoryId + ")",
                    "请刷新页面或检查模板 id",
                    "warning");
        }
        NotifyTemplate existing = existingOpt.get();
        existing.setDeletedAt(LocalDateTime.now());
        templateRepo.save(existing);
        log.info("Soft-deleted NotifyTemplate: id={}, factoryId={}", id, factoryId);
        return ApiResponse.success("通知模板已删除", null);
    }

    @PostMapping("/test-send")
    @Operation(summary = "测试发送通知模板",
               description = "查 NotifyTemplate by (factoryId, templateCode), 构 NotifyRequest, "
                           + "通过 NotifySenderRegistry fan-out 到 channels, 返回每个 channel 的 NotifyResult.")
    public ApiResponse<Map<String, Object>> testSend(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body) {
        log.info("POST /notify/templates/test-send factoryId={} body keys={}",
                factoryId, body.keySet());

        // 1. templateCode 必填
        Object templateCodeRaw = body.get("templateCode");
        if (templateCodeRaw == null || templateCodeRaw.toString().isBlank()) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "templateCode 必填",
                    "请提供要测试的模板 code (如 PO_APPROVAL_PENDING)", "warning");
        }
        String templateCode = templateCodeRaw.toString();
        if (templateCode.length() > TEMPLATE_CODE_MAX_LENGTH) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "templateCode 最长 " + TEMPLATE_CODE_MAX_LENGTH
                            + " 字符 (当前 " + templateCode.length() + ")",
                    "请使用更短的 templateCode", "warning");
        }

        // 2. 查模板
        Optional<NotifyTemplate> templateOpt =
                templateRepo.findByFactoryIdAndTemplateCode(factoryId, templateCode);
        if (templateOpt.isEmpty()) {
            return ApiResponse.errorWithCode(404, "TEMPLATE_NOT_FOUND",
                    "通知模板不存在: " + templateCode + " (factoryId=" + factoryId + ")",
                    "请先创建该模板, 或检查 templateCode 是否拼写正确", "warning");
        }
        NotifyTemplate template = templateOpt.get();

        // 3. recipientUserIds (可选)
        List<Long> recipientUserIds = parseUserIds(body.get("recipientUserIds"));

        // 4. channels 优先从 body 取, 否则用 template.channels
        List<NotifyChannel> channels = parseChannels(body.get("channels"));
        if (channels.isEmpty()) {
            channels = template.getChannels() != null
                    ? new ArrayList<>(template.getChannels())
                    : new ArrayList<>();
        }
        if (channels.isEmpty()) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "模板未配置 channels, body 也未提供 channels override",
                    "请在模板编辑页配置发送渠道 (WECHAT/DINGTALK/EMAIL/SMS/IN_APP), 或在 body 中显式传 channels", "warning");
        }

        // 5. params
        Map<String, Object> params = mapField(body, "params");

        // 6. 组装 + fan-out
        NotifyRequest request = new NotifyRequest(
                factoryId, recipientUserIds, channels, templateCode, params);

        List<NotifyResult> results;
        try {
            results = notifySenderRegistry.sendAll(request);
        } catch (Exception ex) {
            log.error("[NotifyTemplate.testSend] 调用 NotifySenderRegistry.sendAll 异常: {}",
                    ex.getMessage(), ex);
            throw new BusinessException(500,
                    "测试发送失败: " + ex.getMessage())
                    .withSeverity("warning");
        }

        // 7. 序列化 results + 统计
        List<Map<String, Object>> serialized = new ArrayList<>();
        int sentCount = 0;
        int failedCount = 0;
        for (NotifyResult r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channel", r.channel() != null ? r.channel().name() : null);
            m.put("status", r.status() != null ? r.status().name() : null);
            m.put("errorMsg", r.errorMsg());
            serialized.add(m);
            if (r.status() != null && "SENT".equals(r.status().name())) {
                sentCount++;
            } else {
                failedCount++;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("templateCode", templateCode);
        data.put("templateId", template.getId() != null ? template.getId().toString() : null);
        data.put("totalChannels", channels.size());
        data.put("sentCount", sentCount);
        data.put("failedCount", failedCount);
        data.put("results", serialized);

        String message = failedCount == 0
                ? "测试发送完成 — 全部 " + sentCount + " 个渠道发送成功"
                : "测试发送完成 — 成功 " + sentCount + " 个, 失败 " + failedCount + " 个 (详见 results)";
        return ApiResponse.success(message, data);
    }

    // ==================== helpers ====================

    /**
     * AUD-4 optimistic lock check — mirror CanvasAlertController.checkVersion contract.
     * Returns null when version matches or client opts out by sending null/missing.
     */
    private ApiResponse<NotifyTemplate> checkVersion(Object requestVersionRaw, Long currentVersion) {
        if (requestVersionRaw == null) {
            return null; // lenient: legacy clients without version field
        }
        Long requestVersion;
        if (requestVersionRaw instanceof Number) {
            requestVersion = ((Number) requestVersionRaw).longValue();
        } else {
            try {
                requestVersion = Long.parseLong(requestVersionRaw.toString());
            } catch (NumberFormatException ex) {
                return null; // lenient: malformed version field treated as not-supplied
            }
        }
        if (!requestVersion.equals(currentVersion)) {
            return ApiResponse.errorWithCode(409, "VERSION_CONFLICT",
                    "数据已被其他用户修改 (服务端 v=" + currentVersion
                            + ", 客户端 v=" + requestVersion + ")",
                    "请刷新页面查看最新数据后再编辑",
                    "warning");
        }
        return null;
    }

    private String stringField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        return v.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Long> parseUserIds(Object raw) {
        List<Long> out = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (o == null) continue;
                if (o instanceof Number) {
                    out.add(((Number) o).longValue());
                } else {
                    try {
                        out.add(Long.parseLong(o.toString()));
                    } catch (NumberFormatException ignore) {
                        log.warn("[NotifyTemplateController] 忽略非法 recipientUserId: {}", o);
                    }
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<NotifyChannel> parseChannels(Object raw) {
        List<NotifyChannel> out = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (o == null) continue;
                try {
                    out.add(NotifyChannel.valueOf(o.toString().toUpperCase()));
                } catch (IllegalArgumentException ignore) {
                    log.warn("[NotifyTemplateController] 忽略未知 channel: {}", o);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) v);
        }
        return new HashMap<>();
    }
}
