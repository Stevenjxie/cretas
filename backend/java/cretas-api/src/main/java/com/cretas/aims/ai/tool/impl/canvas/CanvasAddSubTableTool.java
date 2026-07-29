package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.config.CanvasDynamicField;
import com.cretas.aims.repository.config.CanvasDynamicFieldRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas 子表添加工具
 *
 * 给指定模块添加子表（一对多关系），如付款记录、追踪记录、明细行等。
 * 子表字段以 fieldType=SUB_TABLE 存储，columns 配置在 config.columns 中。
 *
 * Intent Code: CANVAS_ADD_SUB_TABLE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-04-09
 */
@Slf4j
@Component
public class CanvasAddSubTableTool extends AbstractBusinessTool {

    @Autowired
    private CanvasDynamicFieldRepository fieldRepo;

    @Override
    public String getToolName() {
        return "canvas_add_sub_table";
    }

    @Override
    public String getDescription() {
        return "给指定模块添加子表（一对多关系，如付款记录、追踪记录、明细行）。适用场景：在 Canvas 配置界面扩展某模块的子表，支持定义子表列结构。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> moduleCode = new HashMap<>();
        moduleCode.put("type", "string");
        moduleCode.put("description", "父模块代码，如 PURCHASE、SALES、EQUIPMENT 等");
        properties.put("moduleCode", moduleCode);

        Map<String, Object> fieldCode = new HashMap<>();
        fieldCode.put("type", "string");
        fieldCode.put("description", "子表字段代码，唯一标识，如 paymentRecords、trackingHistory 等");
        properties.put("fieldCode", fieldCode);

        Map<String, Object> label = new HashMap<>();
        label.put("type", "string");
        label.put("description", "子表显示名称，如 付款记录、追踪历史、费用明细");
        properties.put("label", label);

        Map<String, Object> columns = new HashMap<>();
        columns.put("type", "array");
        columns.put("description", "子表列定义，每列包含 {code, label, type}，type 可选 TEXT/NUMBER/DECIMAL/DATE");
        properties.put("columns", columns);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("moduleCode", "fieldCode", "label", "columns"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("moduleCode", "fieldCode", "label", "columns");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String moduleCode = getString(params, "moduleCode");
        String fieldCode = getString(params, "fieldCode");
        String label = getString(params, "label");
        Object columns = params.get("columns");

        log.info("Canvas 添加子表 - 工厂ID: {}, 模块: {}, 子表: {}", factoryId, moduleCode, fieldCode);

        if (columns == null) {
            throw new IllegalArgumentException("子表必须定义 columns（列结构）");
        }

        Map<String, Object> config = new HashMap<>();
        config.put("columns", columns);

        CanvasDynamicField field = CanvasDynamicField.builder()
                .factoryId(factoryId)
                .moduleCode(moduleCode)
                .fieldCode(fieldCode)
                .fieldType("SUB_TABLE")
                .label(label)
                .config(config)
                .status("PENDING_DDL")
                .build();

        CanvasDynamicField saved = fieldRepo.save(field);

        log.info("Canvas 添加子表完成 - ID: {}, 子表: {}.{}", saved.getId(), moduleCode, fieldCode);

        return buildSimpleResult(
                "子表 " + label + "（" + fieldCode + "）已添加到模块 " + moduleCode + "，等待 DDL 迁移激活",
                Map.of(
                        "id", saved.getId(),
                        "factoryId", factoryId,
                        "moduleCode", moduleCode,
                        "fieldCode", fieldCode,
                        "fieldType", "SUB_TABLE",
                        "label", label,
                        "columns", columns,
                        "status", "PENDING_DDL"
                )
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "moduleCode", "请问要给哪个模块添加子表？请提供模块代码（如 PURCHASE、SALES 等）。",
                "fieldCode", "请问子表的字段代码是什么？建议英文驼峰，如 paymentRecords、trackingHistory。",
                "label", "请问子表的显示名称是什么？如 付款记录、追踪历史。",
                "columns", "请问子表包含哪些列？请以 JSON 数组提供，每列含 code、label、type（TEXT/NUMBER/DECIMAL/DATE）。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "moduleCode", "模块代码",
                "fieldCode", "子表字段代码",
                "label", "子表显示名",
                "columns", "列定义"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
