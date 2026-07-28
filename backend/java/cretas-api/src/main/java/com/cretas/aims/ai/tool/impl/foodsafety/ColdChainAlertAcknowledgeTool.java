package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.foodsafety.ColdChainAlert;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.ColdChainAlertRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 冷链报警确认 Tool (WRITE + Preview) — Sprint 9 P2.D.
 *
 * <p>状态机: OPEN → ACKNOWLEDGED (本 Tool). RESOLVED 走单独 Tool (后续 Sprint).
 *
 * <p>防呆 4 位一体:
 * <ul>
 *   <li>R1: preview 显当前 status + alert 全 context</li>
 *   <li>R2: message 含 alertId + equipmentId + 温度极值</li>
 *   <li>R3: ackReason enum dropdown (TEMPORARY_DOOR_OPEN / POWER_FLUCTUATION /
 *       MAINTENANCE / SENSOR_FAULT / SPOILED / OTHER) + 选 OTHER 必填 ackRemark</li>
 *   <li>R4: 幂等 — 已 ACKNOWLEDGED / RESOLVED 不重写</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"确认 id=42 报警"</li>
 *   <li>"温度报警是开门导致, 确认"</li>
 *   <li>"探头故障导致冷柜误报, 确认 id=15"</li>
 * </ul>
 *
 * <p>Intent Code: {@code COLD_CHAIN_ALERT_ACKNOWLEDGE}
 */
@Slf4j
@Component
public class ColdChainAlertAcknowledgeTool extends AbstractBusinessTool {

    /** R3: 标准 ackReason enum (R3 防呆 dropdown). */
    private static final Set<String> VALID_REASONS = Set.of(
            "TEMPORARY_DOOR_OPEN",  // 开门导致
            "POWER_FLUCTUATION",    // 电压波动
            "MAINTENANCE",          // 设备维修
            "SENSOR_FAULT",         // 探头故障
            "SPOILED",              // 产品已变质
            "OTHER"                 // 其他 (必填 ackRemark)
    );

    @Autowired
    private ColdChainAlertRepository alertRepository;

    @Override
    public String getToolName() {
        return "cold_chain_alert_acknowledge";
    }

    @Override
    public String getDescription() {
        return "确认冷链报警 (OPEN → ACKNOWLEDGED). "
                + "ackReason 必须是 enum 之一: TEMPORARY_DOOR_OPEN (开门导致) / "
                + "POWER_FLUCTUATION (电压波动) / MAINTENANCE (设备维修) / "
                + "SENSOR_FAULT (探头故障) / SPOILED (产品已变质) / OTHER (其他, 必填 ackRemark). "
                + "LLM 触发: '确认 id=42 报警' / '温度报警是开门导致, 确认' / "
                + "'探头故障误报, 确认 id=15'. WRITE + Preview + R4 幂等.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> id = new HashMap<>();
        id.put("type", "integer");
        id.put("description", "报警 ID (必填). e.g. 42");
        properties.put("id", id);

        Map<String, Object> ackReason = new HashMap<>();
        ackReason.put("type", "string");
        ackReason.put("description", "确认原因 enum (必填): TEMPORARY_DOOR_OPEN / POWER_FLUCTUATION / "
                + "MAINTENANCE / SENSOR_FAULT / SPOILED / OTHER");
        properties.put("ackReason", ackReason);

        Map<String, Object> ackRemark = new HashMap<>();
        ackRemark.put("type", "string");
        ackRemark.put("description", "确认备注 (选 OTHER 时必填; 其他 enum 可选)");
        properties.put("ackRemark", ackRemark);

        schema.put("properties", properties);
        schema.put("required", List.of("id", "ackReason"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("id", "ackReason");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.WRITE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.MEDIUM;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Long id = getLong(params, "id");
        String ackReason = getString(params, "ackReason");
        String ackRemark = getString(params, "ackRemark");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PREVIEW");
        result.put("id", id);
        result.put("ackReason", ackReason);

        // R3: reason enum 校验
        if (!VALID_REASONS.contains(ackReason)) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ ackReason 必须是 %s 之一, 实际 %s", VALID_REASONS, ackReason));
            return result;
        }

        // R3: OTHER 必填 ackRemark
        if ("OTHER".equals(ackReason) && (ackRemark == null || ackRemark.isBlank())) {
            result.put("canDo", false);
            result.put("message", "⚠️ ackReason=OTHER 时, ackRemark 必填");
            return result;
        }

        Optional<ColdChainAlert> opt = alertRepository.findById(id);
        if (opt.isEmpty()) {
            result.put("canDo", false);
            result.put("message", String.format("⚠️ 报警 id=%d 不存在", id));
            return result;
        }

        ColdChainAlert a = opt.get();
        if (!factoryId.equals(a.getFactoryId())) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ 报警 id=%d 不属于当前工厂 (factory=%s), 无权操作", id, factoryId));
            return result;
        }

        result.put("equipmentId", a.getEquipmentId());
        result.put("alertTime", a.getAlertTime().toString());
        result.put("severity", a.getSeverity());
        result.put("minTemp", a.getMinTemp());
        result.put("maxTemp", a.getMaxTemp());
        result.put("durationMinutes", a.getDurationMinutes());
        result.put("currentStatus", a.getStatus());

        // R4 幂等: 已 ACKNOWLEDGED / RESOLVED → 不重写
        if (!"OPEN".equals(a.getStatus())) {
            result.put("canDo", false);
            result.put("status", "ALREADY_ACKNOWLEDGED");
            result.put("message", String.format(
                    "ℹ️ 报警 id=%d (equipment=%d) 已是 %s 状态 (ack_at=%s, prev_reason=%s), " +
                    "无需重复确认",
                    id, a.getEquipmentId(), a.getStatus(),
                    a.getAcknowledgedAt() != null ? a.getAcknowledgedAt().toString() : "-",
                    a.getAckReason() != null ? a.getAckReason() : "-"));
            return result;
        }

        result.put("newStatus", "ACKNOWLEDGED");
        result.put("canDo", true);
        result.put("message", String.format(
                "📋 确认报警预览 — id=%d, equipment=%d, %s/%s, 持续 %s min " +
                "(温度 %s~%s°C). 原因: %s%s. OPEN → ACKNOWLEDGED",
                id, a.getEquipmentId(), a.getAlertTime(), a.getSeverity(),
                a.getDurationMinutes(), a.getMinTemp(), a.getMaxTemp(),
                ackReason, ackRemark != null && !ackRemark.isBlank()
                        ? " (" + ackRemark + ")" : ""));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Long id = getLong(params, "id");
        String ackReason = getString(params, "ackReason");
        String ackRemark = getString(params, "ackRemark");
        Long userId = getUserId(context);

        log.info("cold_chain_alert_acknowledge — factory={} id={} reason={} userId={}",
                factoryId, id, ackReason, userId);

        // R3 enum 校验
        if (!VALID_REASONS.contains(ackReason)) {
            throw new BusinessException(400, String.format(
                    "ackReason 必须是 %s 之一, 实际 %s", VALID_REASONS, ackReason))
                    .withSeverity("BLOCKING");
        }

        if ("OTHER".equals(ackReason) && (ackRemark == null || ackRemark.isBlank())) {
            throw new BusinessException(400,
                    "ackReason=OTHER 时, ackRemark 必填")
                    .withSeverity("BLOCKING");
        }

        Optional<ColdChainAlert> opt = alertRepository.findById(id);
        if (opt.isEmpty()) {
            throw new BusinessException(404, String.format(
                    "报警 id=%d 不存在", id))
                    .withSeverity("BLOCKING");
        }

        ColdChainAlert a = opt.get();
        if (!factoryId.equals(a.getFactoryId())) {
            throw new BusinessException(403, String.format(
                    "报警 id=%d 不属于当前工厂 (factory=%s), 无权操作", id, factoryId))
                    .withSeverity("BLOCKING");
        }

        String prevStatus = a.getStatus();

        // R4 幂等: 已 ACKNOWLEDGED / RESOLVED 不重写
        if (!"OPEN".equals(prevStatus)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "ALREADY_ACKNOWLEDGED");
            data.put("id", id);
            data.put("currentStatus", prevStatus);
            data.put("acknowledgedAt", a.getAcknowledgedAt() != null
                    ? a.getAcknowledgedAt().toString() : null);
            data.put("count", 0);
            data.put("actionHint", "/foodsafety/cold-chain-alerts/" + id);
            return buildSimpleResult(String.format(
                    "ℹ️ 报警 id=%d (equipment=%d) 已是 %s 状态, 跳过重复确认",
                    id, a.getEquipmentId(), prevStatus), data);
        }

        LocalDateTime now = LocalDateTime.now();
        a.setStatus("ACKNOWLEDGED");
        a.setAcknowledgedAt(now);
        a.setAcknowledgedByUserId(userId);
        a.setAckReason(ackReason);
        if (ackRemark != null && !ackRemark.isBlank()) {
            a.setAckRemark(ackRemark);
        }
        alertRepository.save(a);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("equipmentId", a.getEquipmentId());
        data.put("alertTime", a.getAlertTime().toString());
        data.put("severity", a.getSeverity());
        data.put("previousStatus", prevStatus);
        data.put("currentStatus", "ACKNOWLEDGED");
        data.put("acknowledgedAt", now.toString());
        data.put("acknowledgedByUserId", userId);
        data.put("ackReason", ackReason);
        data.put("ackRemark", ackRemark);
        data.put("actionHint", "/foodsafety/cold-chain-alerts/" + id);

        String message = String.format(
                "✅ 已确认报警 id=%d (equipment=%d, %s, 持续 %s min). 原因: %s%s. " +
                "%s → ACKNOWLEDGED",
                id, a.getEquipmentId(), a.getSeverity(), a.getDurationMinutes(),
                ackReason, ackRemark != null && !ackRemark.isBlank()
                        ? " (" + ackRemark + ")" : "",
                prevStatus);
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
