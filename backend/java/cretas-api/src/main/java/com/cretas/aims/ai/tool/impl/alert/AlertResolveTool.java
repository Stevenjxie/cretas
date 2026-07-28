package com.cretas.aims.ai.tool.impl.alert;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.equipment.EquipmentAlertDTO;
import com.cretas.aims.service.EquipmentAlertsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 告警解决工具
 *
 * 用于标记指定的告警为已解决。解决后告警状态变为RESOLVED。
 * 需要提供解决方案说明。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class AlertResolveTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentAlertsService equipmentAlertsService;

    @Override
    public String getToolName() {
        return "alert_resolve";
    }

    @Override
    public String getDescription() {
        return "解决（关闭）指定的告警。需要提供告警ID和解决方案说明。解决后告警状态变为RESOLVED。" +
                "适用场景：标记已修复的问题、关闭已处理的告警、记录解决方案。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // alertId: 告警ID（必需）
        Map<String, Object> alertId = new HashMap<>();
        alertId.put("type", "integer");
        alertId.put("description", "告警ID，要解决的告警的唯一标识");
        properties.put("alertId", alertId);

        // resolution: 解决方案（推荐提供）
        Map<String, Object> resolution = new HashMap<>();
        resolution.put("type", "string");
        resolution.put("description", "解决方案说明，描述如何解决这个问题");
        properties.put("resolution", resolution);

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
        switch (paramName) {
            case "alertId":
                return "请问您要解决哪个告警？请提供告警ID。";
            case "resolution":
                return "请说明解决方案或处理措施。";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "alertId":
                return "告警ID";
            case "resolution":
                return "解决方案";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行告警解决 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析参数
        Integer alertId = getInteger(params, "alertId");
        String resolution = getString(params, "resolution", "已解决");

        // 获取用户信息
        Long userId = getLong(context, "userId");
        String userName = getString(context, "username");

        if (userId == null) {
            userId = 0L;
            userName = "系统";
        }

        // 调用服务解决告警
        EquipmentAlertDTO resolvedAlert = equipmentAlertsService.resolveAlert(
                factoryId, alertId, userId, userName, resolution);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("message", "告警已解决");
        result.put("alertId", alertId);
        result.put("alertInfo", buildAlertInfo(resolvedAlert));
        result.put("resolvedBy", userName);
        result.put("resolvedAt", resolvedAlert.getResolvedAt() != null ?
                resolvedAlert.getResolvedAt().toString() : null);
        result.put("resolution", resolution);

        // 添加处理耗时（如果有触发时间和解决时间）
        if (resolvedAlert.getTriggeredAt() != null && resolvedAlert.getResolvedAt() != null) {
            long durationMinutes = java.time.Duration.between(
                    resolvedAlert.getTriggeredAt(), resolvedAlert.getResolvedAt()).toMinutes();
            result.put("resolutionTimeMinutes", durationMinutes);

            if (durationMinutes < 60) {
                result.put("resolutionTimeDisplay", durationMinutes + " 分钟");
            } else if (durationMinutes < 1440) {
                result.put("resolutionTimeDisplay", (durationMinutes / 60) + " 小时 " + (durationMinutes % 60) + " 分钟");
            } else {
                long days = durationMinutes / 1440;
                long hours = (durationMinutes % 1440) / 60;
                result.put("resolutionTimeDisplay", days + " 天 " + hours + " 小时");
            }
        }

        log.info("告警解决完成 - 告警ID: {}, 解决人: {}", alertId, userName);

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
