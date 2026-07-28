package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 通知设置引导工具
 *
 * 引导用户前往通知设置页面管理各类通知提醒。
 * Intent Code: SYSTEM_NOTIFICATION
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class SystemNotificationSettingsTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "system_notification_settings";
    }

    @Override
    public String getDescription() {
        return "引导用户前往通知设置页面，管理告警、审批、生产、库存等各类通知提醒。" +
                "适用场景：用户说'通知设置'、'关闭通知'、'消息管理'等。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("通知设置引导 - 工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "请前往【我的】-> 【消息通知】-> 【通知设置】，可开关各类通知提醒（告警、审批、生产、库存等）。");

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
