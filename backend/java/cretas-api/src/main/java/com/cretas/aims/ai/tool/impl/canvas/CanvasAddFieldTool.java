package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.config.CanvasDynamicField;
import com.cretas.aims.repository.config.CanvasDynamicFieldRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas 动态字段添加工具
 *
 * 给指定模块添加动态字段（文本/数字/金额/日期/下拉），字段以 PENDING_DDL 状态创建，
 * 等待 DDL 迁移后激活。
 *
 * Intent Code: CANVAS_ADD_FIELD
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-04-09
 */
@Slf4j
@Component
public class CanvasAddFieldTool extends AbstractBusinessTool {

    @Autowired
    private CanvasDynamicFieldRepository fieldRepo;

    private static final Set<String> VALID_FIELD_TYPES = Set.of("TEXT", "NUMBER", "DECIMAL", "SELECT", "DATE");

    @Override
    public String getToolName() {
        return "canvas_add_field";
    }

    @Override
    public String getDescription() {
        return "给指定模块添加动态字段（文本/数字/金额/日期/下拉）。适用场景：在 Canvas 配置界面给某模块扩展自定义字段，如给采购单添加备注字段、给销售单添加税率下拉字段等。";
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
        fieldCode.put("description", "字段代码，唯一标识，如 taxRate、remarkNote 等（建议英文驼峰）");
        properties.put("fieldCode", fieldCode);

        Map<String, Object> fieldType = new HashMap<>();
        fieldType.put("type", "string");
        fieldType.put("description", "字段类型：TEXT（文本）、NUMBER（整数）、DECIMAL（金额/小数）、SELECT（下拉）、DATE（日期）");
        fieldType.put("enum", Arrays.asList("TEXT", "NUMBER", "DECIMAL", "SELECT", "DATE"));
        properties.put("fieldType", fieldType);

        Map<String, Object> label = new HashMap<>();
        label.put("type", "string");
        label.put("description", "字段显示名称，如 税率、备注、交货日期");
        properties.put("label", label);

        Map<String, Object> options = new HashMap<>();
        options.put("type", "array");
        options.put("description", "下拉选项列表（仅 fieldType=SELECT 时需要），如 [{\"value\":\"9\",\"label\":\"9%\"},{\"value\":\"13\",\"label\":\"13%\"}]");
        properties.put("options", options);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("moduleCode", "fieldCode", "fieldType", "label"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("moduleCode", "fieldCode", "fieldType", "label");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String moduleCode = getString(params, "moduleCode");
        String fieldCode = getString(params, "fieldCode");
        String fieldType = getString(params, "fieldType");
        String label = getString(params, "label");
        Object options = params.get("options");

        // Extract userId from context for audit logging (set by CanvasAIController.buildToolContext)
        Object userIdObj = context != null ? context.get("userId") : null;
        String userIdStr = userIdObj != null ? String.valueOf(userIdObj) : "system";
        log.info("Canvas 添加字段 [by user={}] - 工厂ID: {}, 模块: {}, 字段: {}, 类型: {}",
                userIdStr, factoryId, moduleCode, fieldCode, fieldType);

        if (!VALID_FIELD_TYPES.contains(fieldType)) {
            throw new IllegalArgumentException("无效的字段类型: " + fieldType + "。有效值为: TEXT, NUMBER, DECIMAL, SELECT, DATE");
        }

        Map<String, Object> config = new HashMap<>();
        if ("SELECT".equals(fieldType) && options != null) {
            config.put("options", options);
        }

        CanvasDynamicField field = CanvasDynamicField.builder()
                .factoryId(factoryId)
                .moduleCode(moduleCode)
                .fieldCode(fieldCode)
                .fieldType(fieldType)
                .label(label)
                .config(config)
                .status("PENDING_DDL")
                .build();

        CanvasDynamicField saved = fieldRepo.save(field);

        log.info("Canvas 添加字段完成 - ID: {}, 字段: {}.{}", saved.getId(), moduleCode, fieldCode);

        return buildSimpleResult(
                "字段 " + label + "（" + fieldCode + "）已添加到模块 " + moduleCode + "，等待 DDL 迁移激活",
                Map.of(
                        "id", saved.getId(),
                        "factoryId", factoryId,
                        "moduleCode", moduleCode,
                        "fieldCode", fieldCode,
                        "fieldType", fieldType,
                        "label", label,
                        "status", "PENDING_DDL"
                )
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "moduleCode", "请问要给哪个模块添加字段？请提供模块代码（如 PURCHASE、SALES 等）。",
                "fieldCode", "请问新字段的代码是什么？建议英文驼峰，如 taxRate、remarkNote。",
                "fieldType", "请问字段类型是什么？可选：TEXT（文本）、NUMBER（整数）、DECIMAL（金额）、SELECT（下拉）、DATE（日期）。",
                "label", "请问字段的显示名称是什么？如 税率、备注、交货日期。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "moduleCode", "模块代码",
                "fieldCode", "字段代码",
                "fieldType", "字段类型",
                "label", "字段显示名"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
