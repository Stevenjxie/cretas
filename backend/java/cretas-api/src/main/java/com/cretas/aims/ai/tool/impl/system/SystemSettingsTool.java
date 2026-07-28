package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 系统设置工具
 *
 * 引导用户前往系统设置页面。
 * Intent Code: SYSTEM_SETTINGS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class SystemSettingsTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "system_settings";
    }

    @Override
    public String getDescription() {
        return "引导用户前往系统设置页面，调整语言、主题、数据刷新频率等偏好设置。" +
                "适用场景：用户说'设置'、'偏好设置'、'调整设置'等。";
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
        log.info("系统设置引导 - 工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "请前往【我的】-> 【设置】，可调整语言、主题、数据刷新频率等偏好设置。");

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
