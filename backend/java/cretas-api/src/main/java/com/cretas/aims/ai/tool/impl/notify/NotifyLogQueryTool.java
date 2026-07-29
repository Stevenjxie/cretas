package com.cretas.aims.ai.tool.impl.notify;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import com.cretas.aims.repository.notify.NotifyLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: 查询通知发送日志 — Phase 3 Canvas-Notify Step T7.
 *
 * <p>对应 REST: GET /api/mobile/{factoryId}/notify/logs
 *
 * <p>用于 "为什么我没收到 X 通知" 排查 — 用户问 AI, AI 查 NotifyLog 看 status/errorMsg。
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class NotifyLogQueryTool extends AbstractBusinessTool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    @Autowired
    private NotifyLogRepository logRepository;

    @Override
    public String getToolName() {
        return "notify_log_query";
    }

    @Override
    public String getDescription() {
        return "查询通知发送日志, 排查 '为什么我没收到 X 通知' 类场景。"
                + " 可按 channel / status / recipientUserId / 时间范围过滤。"
                + " 返回最近 N 条 NotifyLog (含 templateCode, channel, status, errorMsg, sentAt)。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("channel", Map.of(
                "type", "string",
                "description", "过滤渠道 (可选): WECHAT/DINGTALK/EMAIL/SMS/IN_APP",
                "enum", List.of("WECHAT", "DINGTALK", "EMAIL", "SMS", "IN_APP")));
        properties.put("status", Map.of(
                "type", "string",
                "description", "过滤状态 (可选): SENT 或 FAILED",
                "enum", List.of("SENT", "FAILED")));
        properties.put("recipientUserId", Map.of(
                "type", "integer",
                "description", "过滤收件用户 id (可选)"));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "最多返回多少条 (默认 50, 最大 200)"));
        schema.put("properties", properties);
        // No required params — all filters optional, defaults to recent 50 logs for factory
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("[NotifyLogQueryTool] factoryId={}, filters={}", factoryId, params);

        // 解析可选参数
        NotifyChannel channel = parseChannel(getString(params, "channel"));
        NotifyStatus status = parseStatus(getString(params, "status"));
        Long recipientUserId = getLong(params, "recipientUserId");

        Integer limit = getInteger(params, "limit", DEFAULT_LIMIT);
        if (limit == null || limit <= 0) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;

        Pageable pageable = PageRequest.of(0, limit);
        Page<NotifyLog> page = logRepository.findWithFilters(
                factoryId, channel, status, recipientUserId, pageable);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (NotifyLog row : page.getContent()) {
            Map<String, Object> r = new HashMap<>();
            r.put("id", row.getId() != null ? row.getId().toString() : null);
            r.put("templateCode", row.getTemplateCode());
            r.put("recipientUserId", row.getRecipientUserId());
            r.put("channel", row.getChannel() != null ? row.getChannel().name() : null);
            r.put("status", row.getStatus() != null ? row.getStatus().name() : null);
            r.put("errorMsg", row.getErrorMsg());
            r.put("sentAt", row.getSentAt() != null ? row.getSentAt().toString() : null);
            rows.add(r);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "SUCCESS");
        result.put("message", "查询到 " + page.getTotalElements() + " 条通知日志 (factoryId=" + factoryId + ")");
        result.put("totalElements", page.getTotalElements());
        result.put("returnedCount", rows.size());
        result.put("logs", rows);
        return result;
    }

    private NotifyChannel parseChannel(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return NotifyChannel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;  // 容错: 无效 channel 名当未过滤
        }
    }

    private NotifyStatus parseStatus(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return NotifyStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
