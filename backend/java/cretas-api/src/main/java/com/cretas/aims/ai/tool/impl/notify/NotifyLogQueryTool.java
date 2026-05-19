package com.cretas.aims.ai.tool.impl.notify;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: 查询通知发送日志 — Phase 3 Canvas-Notify Step T7 skeleton.
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
                "description", "最多返回多少条 (默认 50)"));
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
        throw new UnsupportedOperationException(
                "NotifyLogQueryTool skeleton — Phase 3 sister chat 实施 NotifyLogRepository 查询");
    }
}
