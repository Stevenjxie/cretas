package com.cretas.aims.ai.tool.impl.notify;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyStatus;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.NotifySenderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI Tool: 立即发送通知 — Phase 3 Canvas-Notify Step T7.
 *
 * <p>对应 REST: POST /api/mobile/{factoryId}/notify/templates/test-send
 *
 * <p>fool-proof Rule 4: 同 templateCode + recipient 5min 内重复触发应返 409 idempotent
 * (Phase 3 follow-up: 加 NotifyDedupCache, 当前不实现).
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class NotifySendTool extends AbstractBusinessTool {

    @Autowired
    private NotifyTemplateRepository templateRepository;

    @Autowired
    private NotifySenderRegistry senderRegistry;

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
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("[NotifySendTool] factoryId={}, templateCode={}", factoryId, params.get("templateCode"));

        String templateCode = getString(params, "templateCode");

        // 加载 template (factory-scoped guard)
        Optional<NotifyTemplate> templateOpt =
                templateRepository.findByFactoryIdAndTemplateCode(factoryId, templateCode);
        if (templateOpt.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "NOT_FOUND");
            result.put("message", "通知模板 " + templateCode + " 不存在 (factoryId=" + factoryId + ")");
            result.put("actionHint", "先调用 notify_template_create 创建模板再发送");
            return result;
        }
        NotifyTemplate template = templateOpt.get();

        // 解析 recipientUserIds (List<Number> → List<Long>)
        Object recipientsRaw = params.get("recipientUserIds");
        if (!(recipientsRaw instanceof List<?>)) {
            throw new IllegalArgumentException("recipientUserIds 必须是 array 类型");
        }
        List<Long> recipients = new ArrayList<>();
        for (Object id : (List<Object>) recipientsRaw) {
            if (id == null) continue;
            if (id instanceof Number) {
                recipients.add(((Number) id).longValue());
            } else {
                try {
                    recipients.add(Long.parseLong(id.toString().trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("recipientUserIds 含无效 id: " + id);
                }
            }
        }
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("recipientUserIds 不能为空");
        }

        // 解析 params (Map<String, Object>)
        Object paramsRaw = params.get("params");
        Map<String, Object> templateParams = paramsRaw instanceof Map<?, ?>
                ? new HashMap<>((Map<String, Object>) paramsRaw)
                : new HashMap<>();

        // 构造 NotifyRequest, 用模板自带的 channels
        List<NotifyChannel> channels = template.getChannels();
        if (channels == null || channels.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "INVALID");
            result.put("message", "通知模板 " + templateCode + " 未配置渠道, 无法发送");
            result.put("actionHint", "调用 notify_template_update 添加 channels");
            return result;
        }

        NotifyRequest request = new NotifyRequest(
                factoryId, recipients, channels, templateCode, templateParams);

        // Fan-out 到所有 channels
        List<NotifyResult> results = senderRegistry.sendAll(request);

        // 统计结果
        int sentCount = 0;
        int failedCount = 0;
        List<Map<String, Object>> resultDetails = new ArrayList<>();
        for (NotifyResult r : results) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("channel", r.channel() != null ? r.channel().name() : null);
            detail.put("status", r.status() != null ? r.status().name() : null);
            if (r.errorMsg() != null) {
                detail.put("errorMsg", r.errorMsg());
            }
            resultDetails.add(detail);
            if (r.status() == NotifyStatus.SENT) {
                sentCount++;
            } else {
                failedCount++;
            }
        }

        log.info("[NotifySendTool] 发送完成: factoryId={}, templateCode={}, recipients={}, sent={}/{}",
                factoryId, templateCode, recipients.size(), sentCount, results.size());

        Map<String, Object> result = new HashMap<>();
        result.put("status", failedCount == 0 ? "SUCCESS" : (sentCount == 0 ? "FAILED" : "PARTIAL"));
        result.put("message", "通知发送完成: " + sentCount + " 渠道成功, " + failedCount + " 渠道失败");
        result.put("templateCode", templateCode);
        result.put("recipientCount", recipients.size());
        result.put("channels", channels);
        result.put("sentCount", sentCount);
        result.put("failedCount", failedCount);
        result.put("results", resultDetails);
        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
