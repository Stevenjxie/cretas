package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.config.FactorySkillConfig;
import com.cretas.aims.repository.config.FactorySkillConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas AI 技能开关
 *
 * 启用或禁用工厂的某个 AI 技能（Skill），通过工厂技能配置表实现技能级权限控制。
 *
 * Intent Code: CANVAS_TOGGLE_SKILL
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-04-09
 */
@Slf4j
@Component
public class CanvasToggleSkillTool extends AbstractBusinessTool {

    @Autowired(required = false)
    private FactorySkillConfigRepository factorySkillConfigRepo;

    @Override
    public String getToolName() {
        return "canvas_toggle_skill";
    }

    @Override
    public String getDescription() {
        return "启用或禁用工厂的某个 AI 技能。适用场景：通过 Canvas 配置界面控制特定 Skill 的可用性，如启用 inventory-analysis、禁用 restaurant-operations 等。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> skillNameParam = new HashMap<>();
        skillNameParam.put("type", "string");
        skillNameParam.put("description", "AI 技能名称，如 inventory-analysis、production-tracking、quality-inspection 等");
        properties.put("skillName", skillNameParam);

        Map<String, Object> enabled = new HashMap<>();
        enabled.put("type", "boolean");
        enabled.put("description", "是否启用该技能，true 为启用，false 为禁用");
        properties.put("enabled", enabled);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("skillName", "enabled"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("skillName", "enabled");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String skillName = getString(params, "skillName");
        Boolean enabled = getBoolean(params, "enabled");

        log.info("Canvas AI 技能开关 - 工厂ID: {}, 技能: {}, 启用: {}", factoryId, skillName, enabled);

        Optional<FactorySkillConfig> existing = factorySkillConfigRepo.findByFactoryIdAndSkillName(factoryId, skillName);

        FactorySkillConfig config;
        boolean isNew = false;
        if (existing.isPresent()) {
            config = existing.get();
            config.setEnabled(enabled);
        } else {
            config = FactorySkillConfig.builder()
                    .factoryId(factoryId)
                    .skillName(skillName)
                    .enabled(enabled)
                    .build();
            isNew = true;
        }

        factorySkillConfigRepo.save(config);

        String action = Boolean.TRUE.equals(enabled) ? "启用" : "禁用";
        log.info("Canvas AI 技能开关完成 - 工厂ID: {}, 技能: {}, 操作: {}, 新建: {}", factoryId, skillName, action, isNew);

        return buildSimpleResult(
                "AI 技能 " + skillName + " 已" + action,
                Map.of("factoryId", factoryId, "skillName", skillName, "enabled", enabled, "created", isNew)
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "skillName", "请问要操作哪个 AI 技能？请提供技能名称（如 inventory-analysis、production-tracking 等）。",
                "enabled", "请问是要启用还是禁用该 AI 技能？（true 为启用，false 为禁用）"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "skillName", "AI 技能名称",
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
