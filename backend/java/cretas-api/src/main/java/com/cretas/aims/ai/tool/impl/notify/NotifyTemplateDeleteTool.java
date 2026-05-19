package com.cretas.aims.ai.tool.impl.notify;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: 删除通知模板 (软删) — Phase 3 Canvas-Notify Step T7 skeleton.
 *
 * <p>对应 REST: DELETE /api/mobile/{factoryId}/notify/templates/{id}
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class NotifyTemplateDeleteTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "notify_template_delete";
    }

    @Override
    public String getDescription() {
        return "软删除通知模板。删除后不会真发推送给该模板的引用方, 但 NotifyLog 历史仍保留供审计。"
                + " 若被工作流节点引用应阻塞删除 (sister 实施时校验 referential integrity)。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("templateCode", Map.of("type", "string", "description", "要删除的模板 code"));
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
                "NotifyTemplateDeleteTool skeleton — Phase 3 sister chat 实施 soft-delete + reference check");
    }
}
