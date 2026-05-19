package com.cretas.aims.ai.tool.impl.notify;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: 创建通知模板 — Phase 3 Canvas-Notify Step T7 skeleton.
 *
 * <p>对应 REST: POST /api/mobile/{factoryId}/notify/templates
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class NotifyTemplateCreateTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "notify_template_create";
    }

    @Override
    public String getDescription() {
        return "创建通知模板。用户用自然语言描述通知场景 (如 '采购单待审时给采购总监发邮件 + 微信'),"
                + " 该工具创建模板并返回 templateCode 供后续 workflow / 业务规则引用。"
                + " 参数: templateCode (业务 key), title (标题), bodyTemplate (正文支持 {{var}}),"
                + " channels (渠道列表: WECHAT/DINGTALK/EMAIL/SMS/IN_APP)。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("templateCode", Map.of("type", "string", "description", "业务 key, 如 PO_APPROVAL_PENDING"));
        properties.put("title", Map.of("type", "string", "description", "通知标题, 可含 {{var}}"));
        properties.put("bodyTemplate", Map.of("type", "string", "description", "正文模板, 支持 {{var}} 占位符"));
        properties.put("channels", Map.of(
                "type", "array",
                "description", "渠道列表, 如 [WECHAT, EMAIL]",
                "items", Map.of("type", "string", "enum",
                        List.of("WECHAT", "DINGTALK", "EMAIL", "SMS", "IN_APP"))));
        schema.put("properties", properties);
        schema.put("required", List.of("templateCode", "title", "bodyTemplate", "channels"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("templateCode", "title", "bodyTemplate", "channels");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "NotifyTemplateCreateTool skeleton — Phase 3 sister chat 实施 NotifyTemplateRepository.save");
    }
}
