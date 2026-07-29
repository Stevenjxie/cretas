package com.cretas.aims.ai.tool.impl.alert;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.equipment.EquipmentAlertDTO;
import com.cretas.aims.service.EquipmentAlertsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 告警确认工具
 *
 * 用于确认（知晓）指定的告警。确认后告警状态变为ACKNOWLEDGED。
 * 表示用户已经知道这个告警，但尚未解决。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class AlertAcknowledgeTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentAlertsService equipmentAlertsService;

    @Override
    public String getToolName() {
        return "alert_acknowledge";
    }

    @Override
    public String getDescription() {
        return "确认（知晓）指定的告警。需要提供告警ID。确认后告警状态变为ACKNOWLEDGED，" +
                "表示已知晓该告警但尚未解决。适用场景：确认已收到告警、标记正在处理中的告警。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // alertId: 告警ID（必需）
        Map<String, Object> alertId = new HashMap<>();
        alertId.put("type", "integer");
        alertId.put("description", "告警ID，要确认的告警的唯一标识");
        properties.put("alertId", alertId);

        // note: 备注（可选）
        Map<String, Object> note = new HashMap<>();
        note.put("type", "string");
        note.put("description", "确认备注，说明确认原因或处理计划");
        properties.put("note", note);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("alertId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("alertId");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("alertId".equals(paramName)) {
            return "请问您要确认哪个告警？请提供告警ID。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("alertId".equals(paramName)) {
            return "告警ID";
        }
        if ("note".equals(paramName)) {
            return "备注";
        }
        return super.getParameterDisplayName(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行告警确认 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析参数
        Integer alertId = getInteger(params, "alertId");
        String note = getString(params, "note");

        // 获取用户信息
        Long userId = getLong(context, "userId");
        String userName = getString(context, "username");

        if (userId == null) {
            userId = 0L;
            userName = "系统";
        }

        // 调用服务确认告警
        EquipmentAlertDTO acknowledgedAlert = equipmentAlertsService.acknowledgeAlert(
                factoryId, alertId, userId, userName);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("message", "告警已确认");
        result.put("alertId", alertId);
        result.put("alertInfo", buildAlertInfo(acknowledgedAlert));
        result.put("acknowledgedBy", userName);
        result.put("acknowledgedAt", acknowledgedAlert.getAcknowledgedAt() != null ?
                acknowledgedAlert.getAcknowledgedAt().toString() : null);

        if (note != null) {
            result.put("note", note);
        }

        // 添加后续操作建议
        result.put("nextSteps", Arrays.asList(
                "查看告警详情以了解问题根源",
                "制定解决方案",
                "解决后使用alert_resolve标记为已解决"
        ));

        log.info("告警确认完成 - 告警ID: {}, 确认人: {}", alertId, userName);

        return result;
    }

    /**
     * 构建告警信息摘要
     */
    private Map<String, Object> buildAlertInfo(EquipmentAlertDTO alert) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", alert.getId());
        info.put("equipmentName", alert.getEquipmentName());
        info.put("alertType", alert.getAlertType());
        info.put("level", alert.getLevel() != null ? alert.getLevel().name() : null);
        info.put("status", alert.getStatus() != null ? alert.getStatus().name() : null);
        info.put("message", alert.getMessage());
        return info;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
