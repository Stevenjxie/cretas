package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.foodsafety.ColdChainAlert;
import com.cretas.aims.entity.foodsafety.ColdChainEquipment;
import com.cretas.aims.repository.foodsafety.ColdChainAlertRepository;
import com.cretas.aims.repository.foodsafety.ColdChainEquipmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 冷链活跃报警查询 Tool (READ) — Sprint 9 P2.D.
 *
 * <p>列出当前 status IN ('OPEN', 'ACKNOWLEDGED') 的报警, 按 alertTime DESC.
 * 工作台每日巡检 / AI 主动告警必用.
 *
 * <p>统计字段:
 * <ul>
 *   <li>{@code openCount} 待处理报警数</li>
 *   <li>{@code criticalCount} CRITICAL 严重等级数</li>
 *   <li>{@code alerts} 报警列表 (含设备信息 join)</li>
 * </ul>
 *
 * <p>防呆 R5: 空数据时返 "✅ 目前无活跃报警", 不 dead-end.
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"冷链有报警吗"</li>
 *   <li>"待处理冷链报警"</li>
 *   <li>"今天哪些冷柜出问题"</li>
 *   <li>"温度报警列表"</li>
 * </ul>
 *
 * <p>Intent Code: {@code COLD_CHAIN_ALERT_ACTIVE}
 */
@Slf4j
@Component
public class ColdChainAlertActiveTool extends AbstractBusinessTool {

    @Autowired
    private ColdChainAlertRepository alertRepository;

    @Autowired
    private ColdChainEquipmentRepository equipmentRepository;

    @Override
    public String getToolName() {
        return "cold_chain_alert_active";
    }

    @Override
    public String getDescription() {
        return "列出当前活跃 (OPEN / ACKNOWLEDGED) 的冷链温度偏离报警. "
                + "按 alertTime DESC, 含设备 join 信息. "
                + "LLM 触发: '冷链有报警吗' / '待处理冷链报警' / '今天哪些冷柜出问题' / "
                + "'温度报警列表'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.READ;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.LOW;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("cold_chain_alert_active — factory={}", factoryId);

        List<ColdChainAlert> alerts = alertRepository
                .findByFactoryIdAndStatusInOrderByAlertTimeDesc(
                        factoryId, List.of("OPEN", "ACKNOWLEDGED"));

        int openCount = 0;
        int acknowledgedCount = 0;
        int criticalCount = 0;
        List<Map<String, Object>> rows = new ArrayList<>(alerts.size());

        for (ColdChainAlert a : alerts) {
            if ("OPEN".equals(a.getStatus())) openCount++;
            else if ("ACKNOWLEDGED".equals(a.getStatus())) acknowledgedCount++;
            if ("CRITICAL".equals(a.getSeverity())) criticalCount++;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("equipmentId", a.getEquipmentId());

            // Join equipment info (best-effort, 设备删除则降级显示)
            Optional<ColdChainEquipment> eqOpt = equipmentRepository.findById(a.getEquipmentId());
            eqOpt.ifPresent(eq -> {
                row.put("equipmentCode", eq.getEquipmentCode());
                row.put("equipmentName", eq.getEquipmentName());
                row.put("equipmentType", eq.getType());
                row.put("targetTempMin", eq.getTargetTempMin());
                row.put("targetTempMax", eq.getTargetTempMax());
            });
            if (eqOpt.isEmpty()) {
                row.put("equipmentCode", "(设备已删)");
            }

            row.put("alertTime", a.getAlertTime().toString());
            row.put("severity", a.getSeverity());
            row.put("minTemp", a.getMinTemp());
            row.put("maxTemp", a.getMaxTemp());
            row.put("durationMinutes", a.getDurationMinutes());
            row.put("status", a.getStatus());
            if (a.getAcknowledgedAt() != null) {
                row.put("acknowledgedAt", a.getAcknowledgedAt().toString());
            }
            if (a.getAckReason() != null) {
                row.put("ackReason", a.getAckReason());
            }
            row.put("actionHint", "/foodsafety/cold-chain-alerts/" + a.getId());
            rows.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalCount", alerts.size());
        data.put("openCount", openCount);
        data.put("acknowledgedCount", acknowledgedCount);
        data.put("criticalCount", criticalCount);
        data.put("alerts", rows);

        String message;
        if (alerts.isEmpty()) {
            message = "✅ 工厂 " + factoryId + " 目前无活跃冷链报警";
        } else {
            message = String.format(
                    "🚨 工厂 %s 当前 %d 个活跃报警: %d OPEN, %d ACKNOWLEDGED, %d CRITICAL.%s",
                    factoryId, alerts.size(), openCount, acknowledgedCount, criticalCount,
                    criticalCount > 0 ? " ⛔ 含严重等级, 请立即处理" : "");
        }
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
