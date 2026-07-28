package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.QualityDefect;
import com.cretas.aims.entity.enums.DefectStatus;
import com.cretas.aims.entity.enums.DefectType;
import com.cretas.aims.service.QualityDefectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Sprint4-H Q-PROCESS-1: 查询质检不良 AI Tool.
 */
@Slf4j
@Component
public class QualityDefectQueryTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private QualityDefectService qualityDefectService;

    @Override
    public String getToolName() {
        return "quality_defect_query";
    }

    @Override
    public String getDescription() {
        return "查询工序质检不良记录列表。支持按状态(OPEN/IN_PROGRESS/CLOSED)、缺陷类型、" +
                "关联质检ID过滤，分页返回。适用场景：查看待处理不良、统计不良趋势、" +
                "按类型筛选历史不良。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("status", Map.of(
                "type", "string", "description", "状态过滤 (可选)",
                "enum", Arrays.asList("OPEN", "IN_PROGRESS", "CLOSED")));
        properties.put("defectType", Map.of(
                "type", "string", "description", "缺陷类型过滤 (可选)",
                "enum", Arrays.asList("APPEARANCE", "WEIGHT", "COMPOSITION", "MICROBIAL",
                        "PACKAGING", "FOREIGN_OBJECT", "SENSORY", "SHELF_LIFE", "OTHER")));
        properties.put("qualityInspectionId", Map.of(
                "type", "string", "description", "关联质检记录 ID 过滤 (可选)"));
        properties.put("materialId", Map.of(
                "type", "string", "description", "物料 ID 过滤 (可选)"));
        properties.put("page", Map.of("type", "integer", "description", "页码, 默认 1"));
        properties.put("size", Map.of("type", "integer", "description", "每页, 默认 20, 最大 200"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        DefectStatus status = parseEnum(getString(params, "status"), DefectStatus.class);
        DefectType defectType = parseEnum(getString(params, "defectType"), DefectType.class);
        String inspectionId = getString(params, "qualityInspectionId");
        String materialId = getString(params, "materialId");
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 20);

        PageResponse<QualityDefect> pageResult = qualityDefectService.listDefects(
                factoryId, status, defectType, inspectionId, materialId,
                null, null, page, size);

        List<Map<String, Object>> rows = pageResult.getContent().stream()
                .map(this::toRow).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("total", pageResult.getTotalElements());
        result.put("page", pageResult.getPage());
        result.put("size", pageResult.getSize());
        result.put("rows", rows);
        result.put("message", String.format("共 %d 条不良记录, 当前第 %d 页",
                pageResult.getTotalElements(), pageResult.getPage()));
        return result;
    }

    private Map<String, Object> toRow(QualityDefect d) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", d.getId());
        m.put("qualityInspectionId", d.getQualityInspectionId());
        m.put("materialId", d.getMaterialId());
        m.put("defectType", d.getDefectType() != null ? d.getDefectType().name() : null);
        m.put("defectTypeDisplay", d.getDefectType() != null ? d.getDefectType().getDisplayName() : null);
        m.put("quantity", d.getQuantity());
        m.put("status", d.getStatus() != null ? d.getStatus().name() : null);
        m.put("statusDisplay", d.getStatus() != null ? d.getStatus().getDisplayName() : null);
        m.put("cause", d.getCause());
        m.put("handlingAction", d.getHandlingAction());
        m.put("closedAt", d.getClosedAt());
        m.put("createdAt", d.getCreatedAt());
        return m;
    }

    private <E extends Enum<E>> E parseEnum(String s, Class<E> cls) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return Enum.valueOf(cls, s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return userRole != null && !userRole.isEmpty();
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
