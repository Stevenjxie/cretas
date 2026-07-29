package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.QualityDefect;
import com.cretas.aims.entity.enums.DefectType;
import com.cretas.aims.service.QualityDefectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Sprint4-H Q-PROCESS-1: 登记质检不良 AI Tool.
 *
 * <p>当 LLM 识别用户意图为"登记质检不良 / 记录不良"时调用. 必填:
 * qualityInspectionId, defectType, quantity. 可选: materialId, cause.
 */
@Slf4j
@Component
public class QualityDefectRecordTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private QualityDefectService qualityDefectService;

    @Override
    public String getToolName() {
        return "quality_defect_record";
    }

    @Override
    public String getDescription() {
        return "登记工序质检不良记录。需要指定关联的质检记录ID、缺陷类型、数量，可选指定物料和原因。" +
                "适用场景：质检员发现不合格产品后, 逐条登记不良详情用于后续闭环处理。" +
                "不良类型: APPEARANCE(外观)/WEIGHT(重量)/COMPOSITION(成分)/MICROBIAL(微生物)/" +
                "PACKAGING(包装)/FOREIGN_OBJECT(异物)/SENSORY(感官)/SHELF_LIFE(保质期)/OTHER。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("qualityInspectionId", Map.of(
                "type", "string",
                "description", "关联质检记录ID (quality_inspections.id, UUID)"));
        properties.put("defectType", Map.of(
                "type", "string",
                "description", "缺陷类型",
                "enum", Arrays.asList(
                        "APPEARANCE", "WEIGHT", "COMPOSITION", "MICROBIAL",
                        "PACKAGING", "FOREIGN_OBJECT", "SENSORY", "SHELF_LIFE", "OTHER")));
        properties.put("quantity", Map.of(
                "type", "number",
                "description", "不良数量 (必须 > 0)"));
        properties.put("materialId", Map.of(
                "type", "string",
                "description", "关联物料 ID (可选, raw_material_types.id)"));
        properties.put("cause", Map.of(
                "type", "string",
                "description", "缺陷原因描述 (可选, 最长 500 字)",
                "maxLength", 500));

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("qualityInspectionId", "defectType", "quantity"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("qualityInspectionId", "defectType", "quantity");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        String inspectionId = getString(params, "qualityInspectionId");
        String defectTypeStr = getString(params, "defectType");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String materialId = getString(params, "materialId");
        String cause = getString(params, "cause");

        DefectType defectType;
        try {
            defectType = DefectType.valueOf(defectTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的缺陷类型: " + defectTypeStr +
                    ". 可选: APPEARANCE/WEIGHT/COMPOSITION/MICROBIAL/PACKAGING/FOREIGN_OBJECT/SENSORY/SHELF_LIFE/OTHER");
        }

        Long userId = extractUserIdFromContext(context);

        QualityDefect saved = qualityDefectService.recordDefect(
                factoryId, inspectionId, materialId, defectType, quantity, cause, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("defectId", saved.getId());
        result.put("qualityInspectionId", saved.getQualityInspectionId());
        result.put("defectType", saved.getDefectType().name());
        result.put("defectTypeDisplay", saved.getDefectType().getDisplayName());
        result.put("quantity", saved.getQuantity());
        result.put("status", saved.getStatus().name());
        result.put("message", String.format("已登记不良: %s (%s) 数量 %s, 待分派处理",
                saved.getDefectType().getDisplayName(), saved.getId(), saved.getQuantity()));
        return result;
    }

    private Long extractUserIdFromContext(Map<String, Object> context) {
        Object userIdObj = context.get("userId");
        if (userIdObj == null) return null;
        if (userIdObj instanceof Long l) return l;
        try {
            return Long.parseLong(userIdObj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "qualityInspectionId" -> "请问关联的质检记录 ID 是？";
            case "defectType" -> "请问缺陷类型是？(外观/重量/成分/微生物/包装/异物/感官/保质期/其他)";
            case "quantity" -> "请问不良数量是多少？";
            case "materialId" -> "请问关联的物料 ID 是？(可选)";
            case "cause" -> "请描述缺陷原因 (可选)";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole)
                || "factory_super_admin".equals(userRole)
                || "platform_admin".equals(userRole)
                || "factory_admin".equals(userRole)
                || "quality_inspector".equals(userRole)
                || "quality_manager".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
