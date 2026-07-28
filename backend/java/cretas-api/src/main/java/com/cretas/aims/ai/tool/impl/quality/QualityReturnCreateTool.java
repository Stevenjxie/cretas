package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.QualityReturnOrder;
import com.cretas.aims.entity.enums.QualityReturnTargetType;
import com.cretas.aims.service.QualityReturnOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Sprint4-H Q-RETURN-1: 创建质检退回单 AI Tool.
 *
 * <p>区分 T-RTA 客户销售退货 — 此 Tool 是上游退回 (供应商/委外加工厂).
 */
@Slf4j
@Component
public class QualityReturnCreateTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private QualityReturnOrderService returnService;

    @Override
    public String getToolName() {
        return "quality_return_create";
    }

    @Override
    public String getDescription() {
        return "创建质检退回单 (退回供应商或委外加工厂)。需要关联质检记录、退回目标类型、" +
                "接收方ID、数量。注意：此 Tool 处理上游退回，不含客户销售退货 (后者请用 sales 相关 Tool)。" +
                "状态机: DRAFT → CONFIRMED → SHIPPED, 创建后默认 DRAFT。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("qualityInspectionId", Map.of(
                "type", "string", "description", "关联质检记录 ID"));
        properties.put("targetType", Map.of(
                "type", "string", "description", "退回目标类型: SUPPLIER (供应商) / SUBCONTRACT (委外)",
                "enum", Arrays.asList("SUPPLIER", "SUBCONTRACT")));
        properties.put("targetId", Map.of(
                "type", "string", "description", "接收方 ID (供应商 ID 或 委外加工厂 ID)"));
        properties.put("targetName", Map.of(
                "type", "string", "description", "接收方名称快照 (可选)"));
        properties.put("materialId", Map.of(
                "type", "string", "description", "退回物料 ID (可选)"));
        properties.put("quantity", Map.of(
                "type", "number", "description", "退回数量 (必须 > 0)"));
        properties.put("unit", Map.of(
                "type", "string", "description", "单位 (kg / 件 / 箱, 可选)"));
        properties.put("reason", Map.of(
                "type", "string", "description", "退回原因 (可选)", "maxLength", 500));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("qualityInspectionId", "targetType", "targetId", "quantity"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("qualityInspectionId", "targetType", "targetId", "quantity");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        String inspectionId = getString(params, "qualityInspectionId");
        String targetTypeStr = getString(params, "targetType");
        String targetId = getString(params, "targetId");
        String targetName = getString(params, "targetName");
        String materialId = getString(params, "materialId");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String unit = getString(params, "unit");
        String reason = getString(params, "reason");

        QualityReturnTargetType targetType;
        try {
            targetType = QualityReturnTargetType.valueOf(targetTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的退回目标类型: " + targetTypeStr +
                    ". 可选: SUPPLIER (退回供应商) / SUBCONTRACT (退回委外)");
        }

        Long userId = extractUserIdFromContext(context);

        QualityReturnOrder saved = returnService.createDraft(
                factoryId, inspectionId, targetType, targetId, targetName,
                materialId, quantity, unit, reason, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("id", saved.getId());
        result.put("returnNumber", saved.getReturnNumber());
        result.put("targetType", saved.getTargetType().name());
        result.put("targetTypeDisplay", saved.getTargetType().getDisplayName());
        result.put("targetId", saved.getTargetId());
        result.put("quantity", saved.getQuantity());
        result.put("status", saved.getStatus().name());
        result.put("message", String.format("已创建退回单 %s (%s, %s 数量 %s), 状态 DRAFT, 待确认",
                saved.getReturnNumber(), saved.getTargetType().getDisplayName(),
                targetName != null ? targetName : targetId, saved.getQuantity()));
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
            case "targetType" -> "请问退回类型是？退回供应商(SUPPLIER) 还是 退回委外加工厂(SUBCONTRACT)？";
            case "targetId" -> "请问接收方 ID 是？(供应商 ID 或 委外厂 ID)";
            case "quantity" -> "请问退回数量是多少？";
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
                || "quality_manager".equals(userRole)
                || "quality_inspector".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
