package com.cretas.aims.ai.tool.impl.notify;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI Tool: 更新通知模板 — Phase 3 Canvas-Notify Step T7.
 *
 * <p>对应 REST: PUT /api/mobile/{factoryId}/notify/templates/{id}
 *
 * <p>fool-proof: factoryId guard — 只能更新自己 factory 的模板.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class NotifyTemplateUpdateTool extends AbstractBusinessTool {

    @Autowired
    private NotifyTemplateRepository templateRepository;

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
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("[NotifyTemplateUpdateTool] factoryId={}, templateCode={}",
                factoryId, params.get("templateCode"));

        String templateCode = getString(params, "templateCode");
        Optional<NotifyTemplate> existing =
                templateRepository.findByFactoryIdAndTemplateCode(factoryId, templateCode);
        if (existing.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "NOT_FOUND");
            result.put("message", "通知模板 " + templateCode + " 不存在 (factoryId=" + factoryId + ")");
            result.put("actionHint", "先调用 notify_template_create 创建模板");
            return result;
        }

        NotifyTemplate template = existing.get();
        List<String> changedFields = new ArrayList<>();

        String newTitle = getString(params, "title");
        if (newTitle != null && !newTitle.equals(template.getTitle())) {
            template.setTitle(newTitle);
            changedFields.add("title");
        }

        String newBody = getString(params, "bodyTemplate");
        if (newBody != null && !newBody.equals(template.getBodyTemplate())) {
            template.setBodyTemplate(newBody);
            changedFields.add("bodyTemplate");
        }

        Object channelsRaw = params.get("channels");
        if (channelsRaw instanceof List<?>) {
            List<NotifyChannel> newChannels = new ArrayList<>();
            for (Object ch : (List<Object>) channelsRaw) {
                if (ch == null) continue;
                try {
                    newChannels.add(NotifyChannel.valueOf(ch.toString().trim().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("无效的渠道名: " + ch);
                }
            }
            if (!newChannels.isEmpty()) {
                template.setChannels(newChannels);
                changedFields.add("channels");
            }
        }

        if (changedFields.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "NO_CHANGE");
            result.put("message", "未提供任何要更新的字段");
            result.put("templateCode", templateCode);
            return result;
        }

        NotifyTemplate saved = templateRepository.save(template);
        log.info("[NotifyTemplateUpdateTool] 更新成功: factoryId={}, templateCode={}, changed={}",
                factoryId, templateCode, changedFields);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "SUCCESS");
        result.put("message", "通知模板 " + templateCode + " 已更新 (" + String.join("/", changedFields) + ")");
        result.put("id", saved.getId().toString());
        result.put("templateCode", templateCode);
        result.put("changedFields", changedFields);
        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
