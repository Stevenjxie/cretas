package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.config.FactoryToolConfig;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas AI 工具开关
 *
 * 启用或禁用工厂的某个 AI 工具，通过工厂工具配置表实现工具级权限控制。
 *
 * Intent Code: CANVAS_TOGGLE_TOOL
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-04-09
 */
@Slf4j
@Component
public class CanvasToggleToolTool extends AbstractBusinessTool {

    @Autowired(required = false)
    private FactoryToolConfigRepository factoryToolConfigRepo;

    @Override
    public String getToolName() {
        return "canvas_toggle_tool";
    }

    @Override
    public String getDescription() {
        return "启用或禁用工厂的某个 AI 工具。适用场景：通过 Canvas 配置界面控制特定 AI 工具的可用性，如禁用某些高风险操作工具。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> toolNameParam = new HashMap<>();
        toolNameParam.put("type", "string");
        toolNameParam.put("description", "AI 工具名称，如 equipment_stop、material_batch_create 等");
        properties.put("toolName", toolNameParam);

        Map<String, Object> enabled = new HashMap<>();
        enabled.put("type", "boolean");
        enabled.put("description", "是否启用该工具，true 为启用，false 为禁用");
        properties.put("enabled", enabled);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("toolName", "enabled"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("toolName", "enabled");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String toolName = getString(params, "toolName");
        Boolean enabled = getBoolean(params, "enabled");

        log.info("Canvas AI 工具开关 - 工厂ID: {}, 工具: {}, 启用: {}", factoryId, toolName, enabled);

        Optional<FactoryToolConfig> existing = factoryToolConfigRepo.findByFactoryIdAndToolName(factoryId, toolName);

        FactoryToolConfig config;
        boolean isNew = false;
        if (existing.isPresent()) {
            config = existing.get();
            config.setEnabled(enabled);
        } else {
            config = FactoryToolConfig.builder()
                    .factoryId(factoryId)
                    .toolName(toolName)
                    .enabled(enabled)
                    .build();
            isNew = true;
        }

        factoryToolConfigRepo.save(config);

        String action = Boolean.TRUE.equals(enabled) ? "启用" : "禁用";
        log.info("Canvas AI 工具开关完成 - 工厂ID: {}, 工具: {}, 操作: {}, 新建: {}", factoryId, toolName, action, isNew);

        return buildSimpleResult(
                "AI 工具 " + toolName + " 已" + action,
                Map.of("factoryId", factoryId, "toolName", toolName, "enabled", enabled, "created", isNew)
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "toolName", "请问要操作哪个 AI 工具？请提供工具名称（如 equipment_stop、material_batch_create 等）。",
                "enabled", "请问是要启用还是禁用该 AI 工具？（true 为启用，false 为禁用）"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "toolName", "AI 工具名称",
                "enabled", "是否启用"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
