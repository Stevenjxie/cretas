package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.config.FieldConfigDTO;
import com.cretas.aims.service.config.FactoryConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas 字段配置更新工具
 *
 * 修改工厂模块的字段配置，支持可见性、必填性、标签和默认值的单属性更新。
 *
 * Intent Code: CANVAS_UPDATE_FIELD
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-04-09
 */
@Slf4j
@Component
public class CanvasUpdateFieldTool extends AbstractBusinessTool {

    @Autowired
    @Qualifier("canvasFactoryConfigService")
    private FactoryConfigService configService;

    private static final Set<String> VALID_PROPERTIES = Set.of("visible", "required", "label", "defaultValue");

    @Override
    public String getToolName() {
        return "canvas_update_field";
    }

    @Override
    public String getDescription() {
        return "修改工厂模块的字段配置，支持可见性、必填性、标签和默认值。适用场景：通过 Canvas 配置界面调整某字段是否显示、是否必填、显示名称或默认值。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> moduleCode = new HashMap<>();
        moduleCode.put("type", "string");
        moduleCode.put("description", "模块代码，如 PURCHASE、SALES、EQUIPMENT 等");
        properties.put("moduleCode", moduleCode);

        Map<String, Object> fieldCode = new HashMap<>();
        fieldCode.put("type", "string");
        fieldCode.put("description", "字段代码，如 batchNumber、supplierName、quantity 等");
        properties.put("fieldCode", fieldCode);

        Map<String, Object> property = new HashMap<>();
        property.put("type", "string");
        property.put("description", "要修改的属性名：visible（可见性）、required（必填）、label（标签名）、defaultValue（默认值）");
        property.put("enum", Arrays.asList("visible", "required", "label", "defaultValue"));
        properties.put("property", property);

        Map<String, Object> value = new HashMap<>();
        value.put("description", "属性的新值。visible/required 为 boolean，label 为 string，defaultValue 为任意类型");
        properties.put("value", value);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("moduleCode", "fieldCode", "property", "value"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("moduleCode", "fieldCode", "property", "value");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String moduleCode = getString(params, "moduleCode");
        String fieldCode = getString(params, "fieldCode");
        String property = getString(params, "property");
        Object value = params.get("value");

        log.info("Canvas 字段配置更新 - 工厂ID: {}, 模块: {}, 字段: {}, 属性: {}, 值: {}",
                factoryId, moduleCode, fieldCode, property, value);

        if (!VALID_PROPERTIES.contains(property)) {
            throw new IllegalArgumentException("无效的属性名: " + property + "。有效值为: " + String.join(", ", VALID_PROPERTIES));
        }

        FieldConfigDTO dto = buildFieldConfigDTO(property, value);
        configService.updateFieldConfig(factoryId, moduleCode, fieldCode, dto, 0L);

        log.info("Canvas 字段配置更新完成 - 工厂ID: {}, 模块: {}, 字段: {}", factoryId, moduleCode, fieldCode);

        return buildSimpleResult(
                "字段 " + fieldCode + " 的 " + property + " 已更新为: " + value,
                Map.of(
                        "factoryId", factoryId,
                        "moduleCode", moduleCode,
                        "fieldCode", fieldCode,
                        "property", property,
                        "newValue", value
                )
        );
    }

    /**
     * 根据属性名和值构建 FieldConfigDTO
     */
    private FieldConfigDTO buildFieldConfigDTO(String property, Object value) {
        FieldConfigDTO.FieldConfigDTOBuilder builder = FieldConfigDTO.builder();
        switch (property) {
            case "visible":
                builder.visible(parseBoolean(value));
                break;
            case "required":
                builder.required(parseBoolean(value));
                break;
            case "label":
                builder.label(value != null ? value.toString() : null);
                break;
            case "defaultValue":
                builder.defaultValue(value);
                break;
            default:
                throw new IllegalArgumentException("未知属性: " + property);
        }
        return builder.build();
    }

    private Boolean parseBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "moduleCode", "请问要配置哪个模块的字段？请提供模块代码（如 PURCHASE、SALES 等）。",
                "fieldCode", "请问要配置哪个字段？请提供字段代码（如 batchNumber、quantity 等）。",
                "property", "请问要修改字段的哪个属性？可选：visible（可见性）、required（必填）、label（标签名）、defaultValue（默认值）。",
                "value", "请问该属性的新值是什么？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "moduleCode", "模块代码",
                "fieldCode", "字段代码",
                "property", "配置属性名",
                "value", "属性新值"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
