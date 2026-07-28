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
 * AI Tool: 创建通知模板 — Phase 3 Canvas-Notify Step T7.
 *
 * <p>对应 REST: POST /api/mobile/{factoryId}/notify/templates
 *
 * <p>fool-proof Rule 4 (幂等): 同 (factoryId, templateCode) 已存在 → 返 409-style 提示 + existingId.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class NotifyTemplateCreateTool extends AbstractBusinessTool {

    @Autowired
    private NotifyTemplateRepository templateRepository;

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
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("[NotifyTemplateCreateTool] factoryId={}, templateCode={}",
                factoryId, params.get("templateCode"));

        String templateCode = getString(params, "templateCode");
        String title = getString(params, "title");
        String bodyTemplate = getString(params, "bodyTemplate");

        // 解析 channels (List<String> → List<NotifyChannel>)
        Object channelsRaw = params.get("channels");
        if (!(channelsRaw instanceof List<?>)) {
            throw new IllegalArgumentException("channels 必须是 array 类型");
        }
        List<NotifyChannel> channels = new ArrayList<>();
        for (Object ch : (List<Object>) channelsRaw) {
            if (ch == null) continue;
            try {
                channels.add(NotifyChannel.valueOf(ch.toString().trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("无效的渠道名: " + ch + " (可选: WECHAT/DINGTALK/EMAIL/SMS/IN_APP)");
            }
        }
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("channels 不能为空, 至少选 1 个渠道");
        }

        // 幂等检查 — (factoryId, templateCode) 已存在 → 返 DUPLICATE + existingId
        Optional<NotifyTemplate> existing =
                templateRepository.findByFactoryIdAndTemplateCode(factoryId, templateCode);
        if (existing.isPresent()) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "DUPLICATE");
            result.put("message", "通知模板 " + templateCode + " 已存在 (factoryId=" + factoryId + "), 请改用 notify_template_update");
            result.put("existingId", existing.get().getId().toString());
            result.put("templateCode", templateCode);
            result.put("actionHint", "调用 notify_template_update 修改现有模板");
            return result;
        }

        // 创建新模板
        NotifyTemplate template = NotifyTemplate.builder()
                .factoryId(factoryId)
                .templateCode(templateCode)
                .title(title)
                .bodyTemplate(bodyTemplate)
                .channels(channels)
                .variablesSchemaJson(new HashMap<>())
                .build();
        NotifyTemplate saved = templateRepository.save(template);

        log.info("[NotifyTemplateCreateTool] 创建成功: id={}, factoryId={}, templateCode={}",
                saved.getId(), factoryId, templateCode);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "SUCCESS");
        result.put("message", "通知模板 " + templateCode + " 创建成功");
        result.put("id", saved.getId().toString());
        result.put("templateCode", templateCode);
        result.put("title", title);
        result.put("channels", channels);
        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
