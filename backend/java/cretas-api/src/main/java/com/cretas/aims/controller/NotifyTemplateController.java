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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Notify 模板 CRUD — Phase 3 Canvas-Notify Step T6.
 *
 * <p>5 endpoints: GET list / POST create / PUT update / DELETE soft-delete / POST test-send.
 * Implementation status:
 * - GET list — implemented (returns all factory templates).
 * - POST create / PUT update / DELETE — sister chat 实施 (CRUD ops still 501 skeleton).
 * - POST test-send — Phase 4a fill: lookup template via {@code templateCode} in body, build
 *   {@link NotifyRequest}, fan-out via {@link NotifySenderRegistry#sendAll}, return per-channel results.
 *
 * <p>RequireRole: factory_super_admin / permission_admin.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/notify/templates")
@RequiredArgsConstructor
@RequireRole({"factory_super_admin", "permission_admin"})
@Tag(name = "Canvas-Notify Templates", description = "通知模板 CRUD (Phase 3 skeleton)")
public class NotifyTemplateController {

    private final NotifyTemplateRepository templateRepo;
    private final NotifySenderRegistry notifySenderRegistry;

    /**
     * AUD-5 B-A3 sister sweep: explicit length cap mirrors PG column width in
     * {@code notify_templates} table (see {@link NotifyTemplate#getTemplateCode()}
     * {@code @Column(length=100)}).
     *
     * <p>Note: CRUD endpoints (create / update / delete) below currently return 501
     * stubs as their FIRST statement, so any pre-check here would be unreachable code
     * — those endpoints will gain length validation when Phase 3 sister chat replaces
     * the stubs with real persistence. The active path needing pre-check now is
     * {@link #testSend} which actually looks up by {@code templateCode}.
     */
    private static final int TEMPLATE_CODE_MAX_LENGTH = 100;

    @GetMapping
    @Operation(summary = "列出工厂所有通知模板")
    public ApiResponse<List<NotifyTemplate>> list(@PathVariable String factoryId) {
        return ApiResponse.success(templateRepo.findByFactoryId(factoryId));
    }

    @PostMapping
    @Operation(summary = "创建通知模板 (skeleton — sister 实施时加 UNIQUE 冲突 409 actionHint)")
    public ApiResponse<NotifyTemplate> create(
            @PathVariable String factoryId,
            @RequestBody NotifyTemplate body) {
        return ApiResponse.error(501,
                "NotifyTemplateController.create skeleton — Phase 3 sister chat 实施");
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新通知模板 (skeleton)")
    public ApiResponse<NotifyTemplate> update(
            @PathVariable String factoryId,
            @PathVariable UUID id,
            @RequestBody NotifyTemplate body) {
        // AUD-4 wiring (PR #94 follow-up): forward-compat optimistic-lock check.
        //
        // The endpoint currently returns 501 (Phase 3 sister chat will replace with
        // real persistence). This guard documents the pattern Phase 3 must adopt:
        //   1. findById(id) → load existing entity
        //   2. checkVersion(body.getVersion(), existing.getVersion()) → fail 409 on stale
        //   3. setFields(existing, body)
        //   4. save(existing)
        //
        // PR #94 already added @Version Long version on NotifyTemplate entity + Flyway DDL
        // so the column is in place. The check itself can't fire today because no row is
        // ever loaded (501 short-circuits) — but the helper definition + this preserved
        // comment ensure Phase 3 sister chat sees the contract before writing the real PUT.
        // If Phase 3 ships without honoring this pattern, AUD-4 stays open on NotifyTemplate.
        return ApiResponse.error(501,
                "NotifyTemplateController.update skeleton — Phase 3 sister chat 实施 (must honor AUD-4 version check; see PR #94 + this PR for pattern)");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "软删除通知模板 (skeleton)")
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable UUID id) {
        return ApiResponse.error(501,
                "NotifyTemplateController.delete skeleton — Phase 3 sister chat 实施");
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
        // AUD-5 B-A3 sister sweep: explicit length pre-check. Without this, an
        // over-length templateCode would silently miss in the repo lookup (returning
        // 404 "通知模板不存在") which masks the real issue (input violates the
        // VARCHAR(100) contract). Surface as specific 400 instead.
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

        // 3. recipientUserIds (可选, 默认空 — sister 后续可加 default 当前用户)
        List<Long> recipientUserIds = parseUserIds(body.get("recipientUserIds"));

        // 4. channels 优先从 body 取 (允许 caller override), 否则用 template.channels
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

        // 5. params: 用于 {{var}} 替换的实际参数
        Map<String, Object> params = mapField(body, "params");

        // 6. 组装 NotifyRequest + fan-out
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
            // count "SENT" as success, anything else as failure
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
                        log.warn("[testSend] 忽略非法 recipientUserId: {}", o);
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
                    log.warn("[testSend] 忽略未知 channel: {}", o);
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
        return new LinkedHashMap<>();
    }
}
