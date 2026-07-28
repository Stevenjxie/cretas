package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 微信通知发送工具
 *
 * 发送微信通知给指定接收人或全体成员。
 * Intent Code: NOTIFICATION_SEND_WECHAT / SEND_WECHAT_MESSAGE / NOTIFICATION_WECHAT_SEND
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * factoryId 隔离豁免说明 (Whitelist — Audit 2026-04-07)
 * ───────────────────────────────────────────────────────────────────────────
 * 本 Tool 当前为 stub — 企业微信集成尚未实现，doExecute 直接返回
 * SERVICE_NOT_AVAILABLE 错误，不访问任何业务数据，不存在跨工厂泄露风险。
 * 一旦实现企业微信集成，须同步加 factoryId 过滤（接收人必须属于 factoryId
 * 所在工厂，或通过显式白名单允许跨工厂通知）。
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Component
public class SendWechatNotificationTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "send_wechat_notification";
    }

    @Override
    public String getDescription() {
        return "发送微信通知给指定接收人或全体成员。支持指定接收人和消息内容。" +
                "适用场景：发送微信通知、群发消息、通知特定人员。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> recipient = new HashMap<>();
        recipient.put("type", "string");
        recipient.put("description", "接收人，如姓名或'全体成员'");
        properties.put("recipient", recipient);

        Map<String, Object> message = new HashMap<>();
        message.put("type", "string");
        message.put("description", "通知消息内容");
        properties.put("message", message);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 企业微信集成尚未实现 — 保持 SERVICE_NOT_AVAILABLE 以明确告知用户
        return buildSimpleResult("企业微信集成尚未实现", Map.of(
                "success", false,
                "error", "企业微信集成尚未实现，发送微信通知功能暂不可用",
                "code", "SERVICE_NOT_AVAILABLE"));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
