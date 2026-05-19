package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.repository.notify.NotifyLogRepository;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.NotifySenderRegistry;
import com.cretas.aims.service.notify.TemplateEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Notify 模板 CRUD — Phase 3 Canvas-Notify Step T6 implementation.
 *
 * <p>5 endpoints: GET list / POST create / PUT update / DELETE soft-delete / POST test-send.
 *
 * <p>RequireRole: factory_super_admin / permission_admin.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 * @since 2026-05-19 (Phase 3 CRUD + test-send 实现)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/notify/templates")
@RequiredArgsConstructor
@RequireRole({"factory_super_admin", "permission_admin"})
@Tag(name = "Canvas-Notify Templates", description = "通知模板 CRUD")
public class NotifyTemplateController {

    private final NotifyTemplateRepository templateRepo;
    private final NotifyLogRepository logRepo;
    private final TemplateEngine templateEngine;
    /**
     * Optional — when NotifySender impls are not wired (test profile), fan-out
     * cannot happen; test-send returns FAILED per channel with explanatory msg.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NotifySenderRegistry notifySenderRegistry;

    @GetMapping
    @Operation(summary = "列出工厂所有通知模板")
    public ApiResponse<List<NotifyTemplate>> list(@PathVariable String factoryId) {
        return ApiResponse.success(templateRepo.findByFactoryId(factoryId));
    }

    @PostMapping
    @Operation(summary = "创建通知模板. (factoryId, templateCode) UNIQUE — 重复返 409 actionHint")
    @Transactional
    public ApiResponse<NotifyTemplate> create(
            @PathVariable String factoryId,
            @RequestBody NotifyTemplate body) {
        if (body.getTemplateCode() == null || body.getTemplateCode().isBlank()) {
            return ApiResponse.error(400, "templateCode 必填");
        }
        // fool-proof Rule 4 idempotency: check existing first, give actionable 409.
        Optional<NotifyTemplate> existing =
                templateRepo.findByFactoryIdAndTemplateCode(factoryId, body.getTemplateCode());
        if (existing.isPresent()) {
            return ApiResponse.errorWithHint(409,
                    "通知模板编码 " + body.getTemplateCode() + " 已存在",
                    "请改用 PUT /api/mobile/" + factoryId + "/notify/templates/"
                            + existing.get().getId() + " 更新该模板",
                    "WARNING",
                    null);
        }
        body.setId(null);
        body.setFactoryId(factoryId);
        if (body.getChannels() == null) {
            body.setChannels(new ArrayList<>());
        }
        if (body.getVariablesSchemaJson() == null) {
            body.setVariablesSchemaJson(new HashMap<>());
        }
        try {
            NotifyTemplate saved = templateRepo.save(body);
            return ApiResponse.success("通知模板创建成功", saved);
        } catch (DataIntegrityViolationException e) {
            // Race: another concurrent create won. Re-check.
            Optional<NotifyTemplate> raceWinner =
                    templateRepo.findByFactoryIdAndTemplateCode(factoryId, body.getTemplateCode());
            return ApiResponse.errorWithHint(409,
                    "通知模板编码 " + body.getTemplateCode() + " 已被并发创建",
                    raceWinner.isPresent()
                            ? "请打开已存在的模板 " + raceWinner.get().getId() + " 进行编辑"
                            : "请刷新列表后重试",
                    "WARNING",
                    null);
        }
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新通知模板")
    @Transactional
    public ApiResponse<NotifyTemplate> update(
            @PathVariable String factoryId,
            @PathVariable UUID id,
            @RequestBody NotifyTemplate body) {
        NotifyTemplate existing = templateRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("通知模板不存在: " + id));
        if (!factoryId.equals(existing.getFactoryId())) {
            return ApiResponse.error(403, "无权修改其他工厂的通知模板");
        }
        if (body.getTitle() != null) {
            existing.setTitle(body.getTitle());
        }
        if (body.getBodyTemplate() != null) {
            existing.setBodyTemplate(body.getBodyTemplate());
        }
        if (body.getChannels() != null) {
            existing.setChannels(body.getChannels());
        }
        if (body.getVariablesSchemaJson() != null) {
            existing.setVariablesSchemaJson(body.getVariablesSchemaJson());
        }
        // template_code 不允许修改 (会破坏 Phase 1 workflow notify 节点的 templateCode 绑定).
        NotifyTemplate saved = templateRepo.save(existing);
        return ApiResponse.success("通知模板更新成功", saved);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "软删除通知模板")
    @Transactional
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable UUID id) {
        NotifyTemplate existing = templateRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("通知模板不存在: " + id));
        if (!factoryId.equals(existing.getFactoryId())) {
            return ApiResponse.error(403, "无权删除其他工厂的通知模板");
        }
        existing.setDeletedAt(LocalDateTime.now());
        templateRepo.save(existing);
        return ApiResponse.success("通知模板已删除", null);
    }

    /**
     * Test-send body schema:
     * <pre>{
     *   "templateCode": "PO_APPROVAL_PENDING",
     *   "channels": ["WECHAT", "EMAIL"],     // optional, defaults to template.channels
     *   "recipientUserIds": [101, 102],       // optional, [0] used as dummy if empty
     *   "params": { "amount": 1000, ... }
     * }</pre>
     * Returns rendered title/body + per-channel send results.
     */
    @PostMapping("/test-send")
    @Operation(summary = "测试发送 — 渲染模板 + 5 渠道 fan-out + 写 NotifyLog")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> testSend(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body) {
        String templateCode = (String) body.get("templateCode");
        if (templateCode == null || templateCode.isBlank()) {
            return ApiResponse.error(400, "templateCode 必填");
        }
        NotifyTemplate template = templateRepo
                .findByFactoryIdAndTemplateCode(factoryId, templateCode)
                .orElse(null);
        if (template == null) {
            return ApiResponse.errorWithHint(404,
                    "通知模板 " + templateCode + " 不存在",
                    "请先在 Canvas → 通知模板 创建该模板",
                    "WARNING",
                    null);
        }

        // Params + channels + recipients fallback.
        Map<String, Object> params = (Map<String, Object>) body.getOrDefault("params", Map.of());
        List<String> channelStrs = (List<String>) body.getOrDefault("channels",
                template.getChannels() != null
                        ? template.getChannels().stream().map(Enum::name).toList()
                        : List.of());
        List<NotifyChannel> channels = new ArrayList<>();
        for (String c : channelStrs) {
            try {
                channels.add(NotifyChannel.valueOf(c));
            } catch (IllegalArgumentException ignored) {
                log.warn("[testSend] 未知 channel: {}", c);
            }
        }
        if (channels.isEmpty()) {
            return ApiResponse.error(400, "无有效 channel 可发送 (检查 channels 入参 + 模板 channels 字段)");
        }

        List<Number> recipientNums = (List<Number>) body.getOrDefault("recipientUserIds", List.of());
        List<Long> recipientUserIds = new ArrayList<>();
        for (Number n : recipientNums) {
            recipientUserIds.add(n.longValue());
        }
        if (recipientUserIds.isEmpty()) {
            // sentinel: -1L means "test-send, no actual recipient" — sender impl should
            // log "would have sent to ...".
            recipientUserIds.add(-1L);
        }

        // Render title + body for the response preview (fool-proof Rule 1 — 显式抛 if 缺变量).
        String renderedTitle;
        String renderedBody;
        try {
            renderedTitle = templateEngine.render(template.getTitle(), params);
            renderedBody = templateEngine.render(template.getBodyTemplate(), params);
        } catch (IllegalArgumentException e) {
            return ApiResponse.errorWithHint(400,
                    "模板渲染失败: " + e.getMessage(),
                    "请检查 params 是否覆盖模板中所有 {{var}} 占位符",
                    "WARNING",
                    null);
        }

        // Fan-out to all senders, collect results.
        List<NotifyResult> results;
        if (notifySenderRegistry != null) {
            NotifyRequest req = new NotifyRequest(
                    factoryId, recipientUserIds, channels, templateCode, params);
            results = notifySenderRegistry.sendAll(req);
        } else {
            results = new ArrayList<>();
            for (NotifyChannel ch : channels) {
                results.add(new NotifyResult(ch, NotifyStatus.FAILED,
                        "NotifySenderRegistry 未启用 (生产环境配置缺失)"));
            }
        }

        // Persist NotifyLog rows (audit). 1 log per (recipient × channel) — for test-send
        // we usually only have 1 recipient so this equals channels.size() rows.
        for (Long recipientId : recipientUserIds) {
            for (NotifyResult result : results) {
                NotifyLog logRow = NotifyLog.builder()
                        .factoryId(factoryId)
                        .templateCode(templateCode)
                        .recipientUserId(recipientId)
                        .channel(result.channel())
                        .status(result.status())
                        .errorMsg(result.errorMsg())
                        .sentAt(LocalDateTime.now())
                        .build();
                logRepo.save(logRow);
            }
        }

        // Response: rendered preview + per-channel results.
        Map<String, Object> resp = new HashMap<>();
        resp.put("templateCode", templateCode);
        resp.put("renderedTitle", renderedTitle);
        resp.put("renderedBody", renderedBody);
        resp.put("channels", channels.stream().map(Enum::name).toList());
        resp.put("recipientUserIds", recipientUserIds);
        resp.put("results", results.stream().map(r -> Map.of(
                "channel", r.channel().name(),
                "status", r.status().name(),
                "errorMsg", r.errorMsg() == null ? "" : r.errorMsg()
        )).toList());
        return ApiResponse.success("测试发送已完成 — 共 " + results.size() + " 渠道", resp);
    }
}
