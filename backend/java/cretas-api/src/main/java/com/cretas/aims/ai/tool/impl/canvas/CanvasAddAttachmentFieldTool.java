package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.config.CanvasDynamicField;
import com.cretas.aims.repository.config.CanvasDynamicFieldRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas 附件上传字段添加工具
 *
 * 给指定模块添加附件上传字段，支持配置允许的文件类型、最大文件大小和最大文件数量。
 *
 * Intent Code: CANVAS_ADD_ATTACHMENT_FIELD
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-04-09
 */
@Slf4j
@Component
public class CanvasAddAttachmentFieldTool extends AbstractBusinessTool {

    @Autowired
    private CanvasDynamicFieldRepository fieldRepo;

    private static final String DEFAULT_ACCEPT = ".pdf,.jpg,.png,.doc,.docx";
    private static final int DEFAULT_MAX_SIZE_MB = 10;
    private static final int DEFAULT_MAX_COUNT = 1;

    @Override
    public String getToolName() {
        return "canvas_add_attachment_field";
    }

    @Override
    public String getDescription() {
        return "给指定模块添加附件上传字段（PDF/图片/文档）。适用场景：在 Canvas 配置界面为某模块扩展文件上传能力，如给采购单添加合同附件、给质检记录添加检测报告。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> moduleCode = new HashMap<>();
        moduleCode.put("type", "string");
        moduleCode.put("description", "模块代码，如 PURCHASE、QUALITY、SALES 等");
        properties.put("moduleCode", moduleCode);

        Map<String, Object> fieldCode = new HashMap<>();
        fieldCode.put("type", "string");
        fieldCode.put("description", "字段代码，唯一标识，如 contractFile、inspectionReport 等（建议英文驼峰）");
        properties.put("fieldCode", fieldCode);

        Map<String, Object> label = new HashMap<>();
        label.put("type", "string");
        label.put("description", "字段显示名称，如 合同附件、检测报告、产品图片");
        properties.put("label", label);

        Map<String, Object> accept = new HashMap<>();
        accept.put("type", "string");
        accept.put("description", "（可选）允许的文件类型，逗号分隔，默认 .pdf,.jpg,.png,.doc,.docx。如仅允许图片: .jpg,.jpeg,.png");
        properties.put("accept", accept);

        Map<String, Object> maxSize = new HashMap<>();
        maxSize.put("type", "integer");
        maxSize.put("description", "（可选）单文件最大大小（MB），默认 10MB");
        properties.put("maxSize", maxSize);

        Map<String, Object> maxCount = new HashMap<>();
        maxCount.put("type", "integer");
        maxCount.put("description", "（可选）最多上传文件数量，默认 1 个");
        properties.put("maxCount", maxCount);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("moduleCode", "fieldCode", "label"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("moduleCode", "fieldCode", "label");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String moduleCode = getString(params, "moduleCode");
        String fieldCode = getString(params, "fieldCode");
        String label = getString(params, "label");

        String accept = params.containsKey("accept") && params.get("accept") != null
                ? getString(params, "accept")
                : DEFAULT_ACCEPT;

        int maxSize = DEFAULT_MAX_SIZE_MB;
        if (params.containsKey("maxSize") && params.get("maxSize") != null) {
            Object maxSizeObj = params.get("maxSize");
            if (maxSizeObj instanceof Number) {
                maxSize = ((Number) maxSizeObj).intValue();
            } else {
                try {
                    maxSize = Integer.parseInt(maxSizeObj.toString());
                } catch (NumberFormatException e) {
                    log.warn("Canvas 附件字段 - maxSize 格式无效: {}, 使用默认值 {}MB", maxSizeObj, DEFAULT_MAX_SIZE_MB);
                }
            }
        }

        int maxCount = DEFAULT_MAX_COUNT;
        if (params.containsKey("maxCount") && params.get("maxCount") != null) {
            Object maxCountObj = params.get("maxCount");
            if (maxCountObj instanceof Number) {
                maxCount = ((Number) maxCountObj).intValue();
            } else {
                try {
                    maxCount = Integer.parseInt(maxCountObj.toString());
                } catch (NumberFormatException e) {
                    log.warn("Canvas 附件字段 - maxCount 格式无效: {}, 使用默认值 {}", maxCountObj, DEFAULT_MAX_COUNT);
                }
            }
        }

        log.info("Canvas 添加附件字段 - 工厂ID: {}, 模块: {}, 字段: {}, 类型: {}, 大小限制: {}MB, 数量: {}",
                factoryId, moduleCode, fieldCode, accept, maxSize, maxCount);

        Map<String, Object> config = new HashMap<>();
        config.put("accept", accept);
        config.put("maxSize", maxSize);
        config.put("maxCount", maxCount);

        CanvasDynamicField field = CanvasDynamicField.builder()
                .factoryId(factoryId)
                .moduleCode(moduleCode)
                .fieldCode(fieldCode)
                .fieldType("ATTACHMENT")
                .label(label)
                .config(config)
                .status("PENDING_DDL")
                .build();

        CanvasDynamicField saved = fieldRepo.save(field);

        log.info("Canvas 添加附件字段完成 - ID: {}, 字段: {}.{}", saved.getId(), moduleCode, fieldCode);

        return buildSimpleResult(
                "附件字段 " + label + "（" + fieldCode + "）已添加到模块 " + moduleCode + "，等待 DDL 迁移激活",
                Map.of(
                        "id", saved.getId(),
                        "factoryId", factoryId,
                        "moduleCode", moduleCode,
                        "fieldCode", fieldCode,
                        "fieldType", "ATTACHMENT",
                        "label", label,
                        "accept", accept,
                        "maxSize", maxSize,
                        "maxCount", maxCount,
                        "status", "PENDING_DDL"
                )
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "moduleCode", "请问要给哪个模块添加附件字段？请提供模块代码（如 PURCHASE、QUALITY 等）。",
                "fieldCode", "请问附件字段的代码是什么？建议英文驼峰，如 contractFile、inspectionReport。",
                "label", "请问附件字段的显示名称是什么？如 合同附件、检测报告。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "moduleCode", "模块代码",
                "fieldCode", "字段代码",
                "label", "字段显示名",
                "accept", "允许文件类型",
                "maxSize", "最大文件大小(MB)",
                "maxCount", "最多文件数量"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
