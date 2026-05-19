package com.cretas.aims.ai.tool.impl.notify;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: 更新通知模板 — Phase 3 Canvas-Notify Step T7 skeleton.
 *
 * <p>对应 REST: PUT /api/mobile/{factoryId}/notify/templates/{id}
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class NotifyTemplateUpdateTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "notify_template_update";
    }

    @Override
    public String getDescription() {
        return "更新现有通知模板的标题/正文/渠道。适用场景: 用户说 '把 PO_APPROVAL_PENDING"
                + " 的渠道加上 SMS' 或 '修改正文为 ...'。templateCode 必填, 其他字段任选一个或多个。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("templateCode", Map.of("type", "string", "description", "要更新的模板 code"));
        properties.put("title", Map.of("type", "string", "description", "新标题 (可选)"));
        properties.put("bodyTemplate", Map.of("type", "string", "description", "新正文 (可选)"));
        properties.put("channels", Map.of(
                "type", "array",
                "description", "新渠道列表 (可选)",
                "items", Map.of("type", "string")));
        schema.put("properties", properties);
        schema.put("required", List.of("templateCode"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("templateCode");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "NotifyTemplateUpdateTool skeleton — Phase 3 sister chat 实施 partial update");
    }
}
