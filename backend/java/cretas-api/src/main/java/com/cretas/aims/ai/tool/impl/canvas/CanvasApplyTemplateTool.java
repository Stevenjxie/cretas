package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.config.FactoryConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas 行业模板应用工具
 *
 * 为工厂应用行业模板，一键配置所有相关模块的字段、流程和权限设置。
 *
 * Intent Code: CANVAS_APPLY_TEMPLATE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-04-09
 */
@Slf4j
@Component
public class CanvasApplyTemplateTool extends AbstractBusinessTool {

    @Autowired
    @Qualifier("canvasFactoryConfigService")
    private FactoryConfigService configService;

    @Override
    public String getToolName() {
        return "canvas_apply_template";
    }

    @Override
    public String getDescription() {
        return "为工厂应用行业模板，一键配置所有模块。适用场景：新工厂初始化、行业切换或重置配置，如应用食品加工模板、餐饮模板等。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> templateCode = new HashMap<>();
        templateCode.put("type", "string");
        templateCode.put("description", "行业模板代码，如 FOOD_PROCESSING、RESTAURANT、COLD_CHAIN、GENERAL_MANUFACTURING 等");
        properties.put("templateCode", templateCode);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("templateCode"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("templateCode");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String templateCode = getString(params, "templateCode");

        log.info("Canvas 应用行业模板 - 工厂ID: {}, 模板: {}", factoryId, templateCode);

        configService.applyTemplate(factoryId, templateCode, 0L);

        log.info("Canvas 行业模板应用完成 - 工厂ID: {}, 模板: {}", factoryId, templateCode);

        return buildSimpleResult(
                "行业模板 " + templateCode + " 已成功应用到工厂 " + factoryId,
                Map.of("factoryId", factoryId, "templateCode", templateCode)
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "templateCode", "请问要应用哪个行业模板？请提供模板代码（如 FOOD_PROCESSING、RESTAURANT、COLD_CHAIN 等）。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "templateCode", "行业模板代码"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
