package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.FactorySettingsDTO;
import com.cretas.aims.service.FactorySettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 系统配置重置工具
 *
 * 恢复系统默认配置，包括排产设置、功能开关、通知设置。
 * 此为敏感操作，需要确认。
 * Intent Code: CONFIG_RESET
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ConfigResetTool extends AbstractBusinessTool {

    @Autowired
    private FactorySettingsService factorySettingsService;

    @Override
    public String getToolName() {
        return "config_reset";
    }

    @Override
    public String getDescription() {
        return "恢复系统默认配置。将重置排产设置(手动确认模式)、功能开关(全部启用)、通知设置(默认渠道)。" +
                "此为敏感操作，执行前会要求确认。适用场景：恢复出厂设置、重置配置。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> confirmed = new HashMap<>();
        confirmed.put("type", "boolean");
        confirmed.put("description", "是否已确认执行重置操作");
        properties.put("confirmed", confirmed);

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
        Boolean confirmed = getBoolean(params, "confirmed");
        if (confirmed == null || !confirmed) {
            return buildSimpleResult("请确认重置操作", Map.of(
                    "requiresConfirmation", true,
                    "message", "此操作将重置所有工厂配置为默认值，请确认后继续。设置 confirmed=true 以执行重置。"));
        }

        try {
            log.info("执行配置重置 - 工厂ID: {}", factoryId);
            FactorySettingsDTO result = factorySettingsService.resetToDefaults(factoryId);
            return buildSimpleResult("系统配置已重置为默认值", Map.of(
                    "factoryId", factoryId,
                    "settings", result));
        } catch (Exception e) {
            log.error("配置重置失败 - 工厂ID: {}", factoryId, e);
            throw e;
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
