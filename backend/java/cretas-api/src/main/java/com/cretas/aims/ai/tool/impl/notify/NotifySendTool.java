package com.cretas.aims.ai.tool.impl.notify;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: 立即发送通知 — Phase 3 Canvas-Notify Step T7 skeleton.
 *
 * <p>对应 REST: POST /api/mobile/{factoryId}/notify/templates/test-send
 *
 * <p>fool-proof Rule 4: 同 templateCode + recipient 5min 内重复触发应返 409 idempotent
 * (sister 实施时加 NotifyDedupCache).
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class NotifySendTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "notify_send";
    }

    @Override
    public String getDescription() {
        return "立即发送通知给指定用户。适用场景: '通知张三采购单 PO-001 已经审批通过' 等 ad-hoc 推送。"
                + " 参数: templateCode (要发送的模板), recipientUserIds (用户 id 列表),"
                + " params (用于 {{var}} 替换的实际参数, 如 {amount: 1000, poNumber: 'PO-001'})。"
                + " 5min 同 templateCode + recipient 重复触发会返 409 防误点。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("templateCode", Map.of("type", "string", "description", "要发送的模板 code"));
        properties.put("recipientUserIds", Map.of(
                "type", "array",
                "description", "收件用户 id 列表",
                "items", Map.of("type", "integer")));
        properties.put("params", Map.of(
                "type", "object",
                "description", "用于 {{var}} 替换的参数 (key 必须 cover 模板所有占位符)"));
        schema.put("properties", properties);
        schema.put("required", List.of("templateCode", "recipientUserIds", "params"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("templateCode", "recipientUserIds", "params");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "NotifySendTool skeleton — Phase 3 sister chat 注入 NotifyDispatcher + 调用 dispatch");
    }
}
