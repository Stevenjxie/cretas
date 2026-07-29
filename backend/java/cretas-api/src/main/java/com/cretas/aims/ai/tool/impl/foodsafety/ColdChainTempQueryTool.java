package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.foodsafety.ColdChainEquipment;
import com.cretas.aims.entity.foodsafety.ColdChainTempReading;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.ColdChainEquipmentRepository;
import com.cretas.aims.repository.foodsafety.ColdChainTempReadingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 冷链温度曲线查询 Tool (READ + FILTER) — Sprint 9 P2.D.
 *
 * <p>查询设备指定时间窗口内的温度读数 (默认 24h), 返回曲线数据 + 偏离标记
 * + 统计摘要 (min/max/avg/偏离次数).
 *
 * <p>支持 2 种查询模式:
 * <ul>
 *   <li>by equipmentId (Long) — 直接定位</li>
 *   <li>by equipmentCode (String) — 业务友好</li>
 * </ul>
 *
 * <p>防呆 4 位一体:
 * <ul>
 *   <li>R2: message 含 equipmentCode + equipmentName + 时间窗口</li>
 *   <li>R5: 空数据时不 dead-end, 提示 "可前往添加 IoT 设备配置"</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"1 号冰柜 24 小时温度"</li>
 *   <li>"FREEZER-A01 最近温度曲线"</li>
 *   <li>"冷库今天温度怎么样"</li>
 * </ul>
 *
 * <p>Intent Code: {@code COLD_CHAIN_TEMP_QUERY}
 */
@Slf4j
@Component
public class ColdChainTempQueryTool extends AbstractBusinessTool {

    /** 默认查询时间窗口 (h). */
    private static final int DEFAULT_HOURS = 24;

    /** 最大查询时间窗口 (h) — 防止 unbounded scan. */
    private static final int MAX_HOURS = 720;  // 30 d

    @Autowired
    private ColdChainEquipmentRepository equipmentRepository;

    @Autowired
    private ColdChainTempReadingRepository readingRepository;

    @Override
    public String getToolName() {
        return "cold_chain_temp_query";
    }

    @Override
    public String getDescription() {
        return "查询冷链设备温度曲线 + 偏离标记 + 统计摘要 (min/max/avg/偏离次数). "
                + "默认 24h 窗口, 最大 30 天. 支持 by equipmentId 或 equipmentCode. "
                + "LLM 触发: '1 号冰柜 24 小时温度' / 'FREEZER-A01 最近温度曲线' / "
                + "'冷库今天温度怎么样'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> equipmentId = new HashMap<>();
        equipmentId.put("type", "integer");
        equipmentId.put("description", "设备 ID (Long, 优先用此, 跟 equipmentCode 二选一)");
        properties.put("equipmentId", equipmentId);

        Map<String, Object> equipmentCode = new HashMap<>();
        equipmentCode.put("type", "string");
        equipmentCode.put("description", "设备编码 (跟 equipmentId 二选一). e.g. FREEZER-A01");
        properties.put("equipmentCode", equipmentCode);

        Map<String, Object> hours = new HashMap<>();
        hours.put("type", "integer");
        hours.put("description", "查询时间窗口 h (可选, 默认 24, 最大 720)");
        properties.put("hours", hours);

        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // 实际 doExecute 验证 equipmentId XOR equipmentCode 至少一个
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
        Long equipmentId = getLong(params, "equipmentId");
        String equipmentCode = getString(params, "equipmentCode");
        Integer hours = getInteger(params, "hours", DEFAULT_HOURS);

        if (equipmentId == null && (equipmentCode == null || equipmentCode.isBlank())) {
            throw new BusinessException(400,
                    "请提供 equipmentId 或 equipmentCode (二选一)")
                    .withSeverity("BLOCKING");
        }

        if (hours == null || hours <= 0) hours = DEFAULT_HOURS;
        if (hours > MAX_HOURS) hours = MAX_HOURS;

        log.info("cold_chain_temp_query — factory={} equipmentId={} code={} hours={}",
                factoryId, equipmentId, equipmentCode, hours);

        // 定位设备
        ColdChainEquipment equipment;
        if (equipmentId != null) {
            Optional<ColdChainEquipment> opt = equipmentRepository.findById(equipmentId);
            if (opt.isEmpty()) {
                throw new BusinessException(404, String.format(
                        "冷链设备 id=%d 不存在", equipmentId))
                        .withSeverity("BLOCKING");
            }
            equipment = opt.get();
            if (!factoryId.equals(equipment.getFactoryId())) {
                throw new BusinessException(403, String.format(
                        "设备 id=%d 不属于当前工厂 (factory=%s), 无权查询",
                        equipmentId, factoryId))
                        .withSeverity("BLOCKING");
            }
        } else {
            Optional<ColdChainEquipment> opt = equipmentRepository
                    .findByFactoryIdAndEquipmentCode(factoryId, equipmentCode);
            if (opt.isEmpty()) {
                throw new BusinessException(404, String.format(
                        "冷链设备 code=%s 在工厂 %s 不存在", equipmentCode, factoryId))
                        .withSeverity("BLOCKING");
            }
            equipment = opt.get();
        }

        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        List<ColdChainTempReading> readings = readingRepository
                .findByEquipmentIdAndRecordedAtAfterOrderByRecordedAtAsc(
                        equipment.getId(), since);

        // 统计摘要
        BigDecimal tempMin = equipment.getTargetTempMin();
        BigDecimal tempMax = equipment.getTargetTempMax();
        BigDecimal observedMin = null;
        BigDecimal observedMax = null;
        BigDecimal sum = BigDecimal.ZERO;
        int deviationCount = 0;

        List<Map<String, Object>> curve = new ArrayList<>(readings.size());
        for (ColdChainTempReading r : readings) {
            BigDecimal t = r.getTemperature();
            if (observedMin == null || t.compareTo(observedMin) < 0) observedMin = t;
            if (observedMax == null || t.compareTo(observedMax) > 0) observedMax = t;
            sum = sum.add(t);
            if (r.isDeviation()) deviationCount++;

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("recordedAt", r.getRecordedAt().toString());
            point.put("temperature", t);
            point.put("isDeviation", r.isDeviation());
            curve.add(point);
        }

        BigDecimal avg = readings.isEmpty()
                ? null
                : sum.divide(new BigDecimal(readings.size()), 2, java.math.RoundingMode.HALF_UP);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("equipmentId", equipment.getId());
        data.put("equipmentCode", equipment.getEquipmentCode());
        data.put("equipmentName", equipment.getEquipmentName());
        data.put("type", equipment.getType());
        data.put("targetTempMin", tempMin);
        data.put("targetTempMax", tempMax);
        data.put("unit", equipment.getUnit());
        data.put("toleranceMinutes", equipment.getToleranceMinutes());
        data.put("hours", hours);
        data.put("readingCount", readings.size());
        data.put("observedMin", observedMin);
        data.put("observedMax", observedMax);
        data.put("avg", avg);
        data.put("deviationCount", deviationCount);
        data.put("curve", curve);
        data.put("actionHint", "/foodsafety/cold-chain-equipment/" + equipment.getId());

        String message;
        if (readings.isEmpty()) {
            // R5: 空数据 dead-end 改导航
            message = String.format(
                    "ℹ️ %s (%s) 最近 %d h 暂无温度读数. " +
                    "请确认 IoT 设备已连接或前去配置数据采集",
                    equipment.getEquipmentCode(), equipment.getEquipmentName(), hours);
        } else {
            message = String.format(
                    "%s (%s, %s) 最近 %d h 共 %d 条读数: min=%s°C, max=%s°C, " +
                    "avg=%s°C. 目标 %s ~ %s°C. 偏离 %d 次%s",
                    equipment.getEquipmentCode(), equipment.getEquipmentName(),
                    equipment.getType(), hours, readings.size(),
                    observedMin, observedMax, avg, tempMin, tempMax, deviationCount,
                    deviationCount > 0 ? " ⚠️" : " ✅");
        }
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
