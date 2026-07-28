package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.QualityReturnOrder;
import com.cretas.aims.entity.enums.QualityReturnStatus;
import com.cretas.aims.entity.enums.QualityReturnTargetType;
import com.cretas.aims.service.QualityReturnOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Sprint4-H Q-RETURN-1: 查询质检退回单 AI Tool.
 */
@Slf4j
@Component
public class QualityReturnQueryTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private QualityReturnOrderService returnService;

    @Override
    public String getToolName() {
        return "quality_return_query";
    }

    @Override
    public String getDescription() {
        return "查询质检退回单列表 (上游退回, 不含客户销售退货)。支持按状态(DRAFT/CONFIRMED/SHIPPED)、" +
                "目标类型(SUPPLIER/SUBCONTRACT)、关联质检 ID 过滤. 分页返回.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("status", Map.of(
                "type", "string", "description", "状态过滤 (可选)",
                "enum", Arrays.asList("DRAFT", "CONFIRMED", "SHIPPED")));
        properties.put("targetType", Map.of(
                "type", "string", "description", "目标类型过滤 (可选)",
                "enum", Arrays.asList("SUPPLIER", "SUBCONTRACT")));
        properties.put("qualityInspectionId", Map.of(
                "type", "string", "description", "关联质检 ID 过滤 (可选)"));
        properties.put("targetId", Map.of(
                "type", "string", "description", "接收方 ID 过滤 (可选)"));
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
        QualityReturnStatus status = parseEnum(getString(params, "status"), QualityReturnStatus.class);
        QualityReturnTargetType targetType = parseEnum(getString(params, "targetType"), QualityReturnTargetType.class);
        String inspectionId = getString(params, "qualityInspectionId");
        String targetId = getString(params, "targetId");
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 20);

        PageResponse<QualityReturnOrder> pageResult = returnService.list(
                factoryId, status, targetType, inspectionId, targetId,
                null, null, page, size);

        List<Map<String, Object>> rows = pageResult.getContent().stream()
                .map(this::toRow).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("total", pageResult.getTotalElements());
        result.put("page", pageResult.getPage());
        result.put("size", pageResult.getSize());
        result.put("rows", rows);
        result.put("message", String.format("共 %d 个退回单, 当前第 %d 页",
                pageResult.getTotalElements(), pageResult.getPage()));
        return result;
    }

    private Map<String, Object> toRow(QualityReturnOrder o) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", o.getId());
        m.put("returnNumber", o.getReturnNumber());
        m.put("qualityInspectionId", o.getQualityInspectionId());
        m.put("targetType", o.getTargetType() != null ? o.getTargetType().name() : null);
        m.put("targetTypeDisplay", o.getTargetType() != null ? o.getTargetType().getDisplayName() : null);
        m.put("targetId", o.getTargetId());
        m.put("targetName", o.getTargetName());
        m.put("quantity", o.getQuantity());
        m.put("unit", o.getUnit());
        m.put("status", o.getStatus() != null ? o.getStatus().name() : null);
        m.put("statusDisplay", o.getStatus() != null ? o.getStatus().getDisplayName() : null);
        m.put("shippingTrackingNo", o.getShippingTrackingNo());
        m.put("createdAt", o.getCreatedAt());
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
