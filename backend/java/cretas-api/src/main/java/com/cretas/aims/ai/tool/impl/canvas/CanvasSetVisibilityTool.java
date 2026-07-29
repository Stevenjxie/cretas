package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.config.CanvasDynamicField;
import com.cretas.aims.repository.config.CanvasDynamicFieldRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas 字段条件显隐规则工具
 *
 * 设置字段的条件显隐规则（visibleWhen）和条件计算规则（computedWhen），
 * 如"出库后才显示实际金额"、"金额>1000时高亮"等。
 *
 * Intent Code: CANVAS_SET_VISIBILITY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-04-09
 */
@Slf4j
@Component
public class CanvasSetVisibilityTool extends AbstractBusinessTool {

    @Autowired
    private CanvasDynamicFieldRepository fieldRepo;

    @Override
    public String getToolName() {
        return "canvas_set_visibility";
    }

    @Override
    public String getDescription() {
        return "设置字段条件显隐规则（如出库后才显示实际金额）。支持设置 visibleWhen（显示条件）和 computedWhen（计算条件）表达式。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> moduleCode = new HashMap<>();
        moduleCode.put("type", "string");
        moduleCode.put("description", "模块代码，如 PURCHASE、SALES、SHIPMENT 等");
        properties.put("moduleCode", moduleCode);

        Map<String, Object> fieldCode = new HashMap<>();
        fieldCode.put("type", "string");
        fieldCode.put("description", "字段代码，如 actualAmount、finalPrice 等");
        properties.put("fieldCode", fieldCode);

        Map<String, Object> visibleWhen = new HashMap<>();
        visibleWhen.put("type", "string");
        visibleWhen.put("description", "显示条件表达式，如 status == 'SHIPPED' 或 amount > 1000。字段仅在条件为 true 时显示。");
        properties.put("visibleWhen", visibleWhen);

        Map<String, Object> computedWhen = new HashMap<>();
        computedWhen.put("type", "string");
        computedWhen.put("description", "（可选）计算触发条件表达式，满足时执行字段的关联计算逻辑");
        properties.put("computedWhen", computedWhen);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("moduleCode", "fieldCode", "visibleWhen"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("moduleCode", "fieldCode", "visibleWhen");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String moduleCode = getString(params, "moduleCode");
        String fieldCode = getString(params, "fieldCode");
        String visibleWhen = getString(params, "visibleWhen");
        String computedWhen = params.containsKey("computedWhen") ? getString(params, "computedWhen") : null;

        log.info("Canvas 设置显隐规则 - 工厂ID: {}, 模块: {}, 字段: {}, 条件: {}", factoryId, moduleCode, fieldCode, visibleWhen);

        Optional<CanvasDynamicField> existing = fieldRepo.findByFactoryIdAndModuleCodeAndFieldCode(factoryId, moduleCode, fieldCode);

        if (existing.isPresent()) {
            CanvasDynamicField field = existing.get();
            field.setVisibleWhen(visibleWhen);
            if (computedWhen != null) {
                field.setComputedWhen(computedWhen);
            }
            fieldRepo.save(field);

            log.info("Canvas 设置显隐规则完成 - 更新动态字段 ID: {}", field.getId());

            return buildSimpleResult(
                    "字段 " + fieldCode + " 的显隐规则已设置",
                    Map.of(
                            "id", field.getId(),
                            "factoryId", factoryId,
                            "moduleCode", moduleCode,
                            "fieldCode", fieldCode,
                            "visibleWhen", visibleWhen,
                            "computedWhen", computedWhen != null ? computedWhen : ""
                    )
            );
        } else {
            // 字段不是动态字段，记录日志并返回提示
            log.warn("Canvas 设置显隐规则 - 未找到动态字段 {}.{} (factoryId={})，该字段可能是 JPA 内置字段，暂不支持条件显隐配置",
                    moduleCode, fieldCode, factoryId);

            return buildSimpleResult(
                    "未找到动态字段 " + fieldCode + "（模块: " + moduleCode + "）。如需为内置 JPA 字段设置显隐，请先通过 canvas_add_field 创建该字段的动态覆盖配置。",
                    Map.of(
                            "factoryId", factoryId,
                            "moduleCode", moduleCode,
                            "fieldCode", fieldCode,
                            "visibleWhen", visibleWhen,
                            "found", false
                    )
            );
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "moduleCode", "请问要配置哪个模块的字段显隐？请提供模块代码（如 PURCHASE、SHIPMENT 等）。",
                "fieldCode", "请问要设置显隐规则的字段代码是什么？",
                "visibleWhen", "请问字段的显示条件是什么？如 status == 'SHIPPED' 或 amount > 1000。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "moduleCode", "模块代码",
                "fieldCode", "字段代码",
                "visibleWhen", "显示条件",
                "computedWhen", "计算触发条件"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
