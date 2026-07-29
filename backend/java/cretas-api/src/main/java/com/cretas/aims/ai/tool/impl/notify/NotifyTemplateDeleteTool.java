package com.cretas.aims.ai.tool.impl.notify;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI Tool: 删除通知模板 (软删) — Phase 3 Canvas-Notify Step T7.
 *
 * <p>对应 REST: DELETE /api/mobile/{factoryId}/notify/templates/{id}
 *
 * <p>fool-proof: factoryId guard — 只能删除自己 factory 的模板. 软删除 (deleted_at=NOW())
 * 保留历史数据, NotifyLog 中的引用仍可读.
 *
 * <p>Phase 3 follow-up: 工作流引用检查 — 若模板被 active 工作流节点引用应阻塞删除,
 * 当前实现仅删模板, 不检查引用 (sister chat 加 referential integrity).
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class NotifyTemplateDeleteTool extends AbstractBusinessTool {

    @Autowired
    private NotifyTemplateRepository templateRepository;

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
        log.info("[NotifyTemplateDeleteTool] factoryId={}, templateCode={}",
                factoryId, params.get("templateCode"));

        String templateCode = getString(params, "templateCode");
        Optional<NotifyTemplate> existing =
                templateRepository.findByFactoryIdAndTemplateCode(factoryId, templateCode);
        if (existing.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "NOT_FOUND");
            result.put("message", "通知模板 " + templateCode + " 不存在 (factoryId=" + factoryId + "), 无需删除");
            return result;
        }

        NotifyTemplate template = existing.get();
        // 软删除: @SQLDelete 触发 UPDATE deleted_at=NOW(); .delete() 也走软删流程 (per BaseEntity).
        templateRepository.delete(template);

        log.info("[NotifyTemplateDeleteTool] 软删成功: factoryId={}, templateCode={}, id={}",
                factoryId, templateCode, template.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("status", "SUCCESS");
        result.put("message", "通知模板 " + templateCode + " 已软删除 (NotifyLog 历史保留)");
        result.put("id", template.getId().toString());
        result.put("templateCode", templateCode);
        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
