package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.foodsafety.ColdChainEquipment;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.ColdChainEquipmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 冷链温控设备注册 Tool (WRITE + Preview, R4 dedup by equipmentCode) — Sprint 9 P2.D.
 *
 * <p>食品行业 P0: 部分品类 (卤味 / 乳制品 / 冷冻熟食) 强制冷链监控.
 * GB 14881 — 冷藏 ≤ 8.0 ℃, 冷冻 ≤ -18.0 ℃.
 *
 * <p>注册新冷链设备: 录入设备编码 / 类型 / 温控阈值 / 容差时长.
 *
 * <p>防呆 4 位一体 (per .claude/rules/fool-proof-design.md):
 * <ul>
 *   <li>R1: preview 显常见预设 (冷藏 8°C / 冷冻 -18°C) 提示</li>
 *   <li>R2: message 含 equipmentCode + equipmentName + type (上下文)</li>
 *   <li>R3: type enum 校验 (FREEZER/REFRIGERATOR/...)</li>
 *   <li>R4: factory 内 equipment_code 唯一性 → 409 dedup</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"注册 1 号冰柜"</li>
 *   <li>"新增冷藏柜, 编码 FRIDGE-A01, 温度 0-8°C"</li>
 *   <li>"添加冷链车 V-001"</li>
 * </ul>
 *
 * <p>Intent Code: {@code COLD_CHAIN_EQUIPMENT_REGISTER}
 */
@Slf4j
@Component
public class ColdChainEquipmentRegisterTool extends AbstractBusinessTool {

    private static final Set<String> VALID_TYPES = Set.of(
            "FREEZER", "REFRIGERATOR", "COLD_CHAIN_VEHICLE",
            "COLD_DISPLAY", "COLD_STORAGE");

    /** GB 14881 默认温控预设. */
    private static final BigDecimal DEFAULT_FREEZER_MAX = new BigDecimal("-18.0");
    private static final BigDecimal DEFAULT_FRIDGE_MAX = new BigDecimal("8.0");

    @Autowired
    private ColdChainEquipmentRepository equipmentRepository;

    @Override
    public String getToolName() {
        return "cold_chain_equipment_register";
    }

    @Override
    public String getDescription() {
        return "注册冷链温控设备 (冰柜/冷藏柜/冷链车/陈列冷柜/冷库) - GB 14881 食品行业 P0. "
                + "录入设备编码/类型/温控阈值 (冷藏默认 0-8°C, 冷冻默认 -22--18°C) + "
                + "容差时长 (默认 15 min - 短期电压波动/开门不报警). "
                + "LLM 触发: '注册 1 号冰柜' / '新增冷藏柜 FRIDGE-A01 0-8°C' / '添加冷链车'. "
                + "WRITE + Preview + R4 equipmentCode 幂等 dedup.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> equipmentCode = new HashMap<>();
        equipmentCode.put("type", "string");
        equipmentCode.put("description", "设备编码 (必填, factory 内唯一). e.g. FREEZER-A01");
        properties.put("equipmentCode", equipmentCode);

        Map<String, Object> equipmentName = new HashMap<>();
        equipmentName.put("type", "string");
        equipmentName.put("description", "设备名称 (必填). e.g. 1 号冰柜");
        properties.put("equipmentName", equipmentName);

        Map<String, Object> type = new HashMap<>();
        type.put("type", "string");
        type.put("description", "类型 (必填): FREEZER / REFRIGERATOR / COLD_CHAIN_VEHICLE / COLD_DISPLAY / COLD_STORAGE");
        properties.put("type", type);

        Map<String, Object> targetTempMin = new HashMap<>();
        targetTempMin.put("type", "number");
        targetTempMin.put("description", "目标温度下限 ℃ (必填). 冷冻 -22, 冷藏 0");
        properties.put("targetTempMin", targetTempMin);

        Map<String, Object> targetTempMax = new HashMap<>();
        targetTempMax.put("type", "number");
        targetTempMax.put("description", "目标温度上限 ℃ (必填). 冷冻 -18 (GB 14881), 冷藏 8");
        properties.put("targetTempMax", targetTempMax);

        Map<String, Object> toleranceMinutes = new HashMap<>();
        toleranceMinutes.put("type", "integer");
        toleranceMinutes.put("description", "偏离持续时长阈值 min (可选, 默认 15). 超过即报警");
        properties.put("toleranceMinutes", toleranceMinutes);

        Map<String, Object> location = new HashMap<>();
        location.put("type", "string");
        location.put("description", "设备位置 (可选). e.g. 1 楼冷库 A 区");
        properties.put("location", location);

        schema.put("properties", properties);
        schema.put("required", List.of("equipmentCode", "equipmentName", "type",
                "targetTempMin", "targetTempMax"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("equipmentCode", "equipmentName", "type",
                "targetTempMin", "targetTempMax");
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
        String equipmentCode = getString(params, "equipmentCode");
        String equipmentName = getString(params, "equipmentName");
        String type = getString(params, "type");
        BigDecimal tempMin = getBigDecimal(params, "targetTempMin");
        BigDecimal tempMax = getBigDecimal(params, "targetTempMax");
        Integer toleranceMinutes = getInteger(params, "toleranceMinutes", 15);
        String location = getString(params, "location");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PREVIEW");
        result.put("equipmentCode", equipmentCode);
        result.put("equipmentName", equipmentName);
        result.put("type", type);
        result.put("targetTempMin", tempMin);
        result.put("targetTempMax", tempMax);
        result.put("toleranceMinutes", toleranceMinutes);
        result.put("location", location);

        // R3: type enum 校验
        if (!VALID_TYPES.contains(type)) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ type 必须是 %s 之一, 实际 %s", VALID_TYPES, type));
            return result;
        }

        // R3: temp range 校验
        if (tempMin == null || tempMax == null || tempMin.compareTo(tempMax) >= 0) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ targetTempMin (%s) 必须 < targetTempMax (%s)", tempMin, tempMax));
            return result;
        }

        if (toleranceMinutes == null || toleranceMinutes <= 0) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ toleranceMinutes (%s) 必须 > 0", toleranceMinutes));
            return result;
        }

        // R4 幂等: factory 内 equipmentCode 唯一性
        if (equipmentRepository.existsByFactoryIdAndEquipmentCode(factoryId, equipmentCode)) {
            Optional<ColdChainEquipment> existing = equipmentRepository
                    .findByFactoryIdAndEquipmentCode(factoryId, equipmentCode);
            result.put("canDo", false);
            result.put("status", "DUPLICATE");
            existing.ifPresent(e -> {
                result.put("existingId", e.getId());
                result.put("actionHint", "/foodsafety/cold-chain-equipment/" + e.getId());
            });
            result.put("message", String.format(
                    "ℹ️ 设备编码 %s 在工厂 %s 已存在 (id=%s), 不可重复注册. " +
                    "如需修改请用 update Tool",
                    equipmentCode, factoryId, existing.map(e -> e.getId().toString()).orElse("?")));
            return result;
        }

        // GB 14881 合规性 advisory
        String complianceHint = "";
        if ("FREEZER".equals(type) && tempMax.compareTo(DEFAULT_FREEZER_MAX) > 0) {
            complianceHint = String.format(" ⚠️ 冷冻设备上限 %s°C 高于 GB 14881 (%.1f°C)",
                    tempMax, DEFAULT_FREEZER_MAX);
        } else if (("REFRIGERATOR".equals(type) || "COLD_DISPLAY".equals(type))
                && tempMax.compareTo(DEFAULT_FRIDGE_MAX) > 0) {
            complianceHint = String.format(" ⚠️ 冷藏设备上限 %s°C 高于 GB 14881 (%.1f°C)",
                    tempMax, DEFAULT_FRIDGE_MAX);
        }

        result.put("canDo", true);
        result.put("message", String.format(
                "📋 注册冷链设备预览 — %s (%s, %s) 温控 %s ~ %s°C, " +
                "容差 %s min, 位置 %s.%s",
                equipmentCode, equipmentName, type, tempMin, tempMax,
                toleranceMinutes, location != null ? location : "-", complianceHint));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String equipmentCode = getString(params, "equipmentCode");
        String equipmentName = getString(params, "equipmentName");
        String type = getString(params, "type");
        BigDecimal tempMin = getBigDecimal(params, "targetTempMin");
        BigDecimal tempMax = getBigDecimal(params, "targetTempMax");
        Integer toleranceMinutes = getInteger(params, "toleranceMinutes", 15);
        String location = getString(params, "location");
        Long userId = getUserId(context);

        log.info("cold_chain_equipment_register — factory={} code={} name={} type={} userId={}",
                factoryId, equipmentCode, equipmentName, type, userId);

        // R3 enum 校验
        if (!VALID_TYPES.contains(type)) {
            throw new BusinessException(400, String.format(
                    "type 必须是 %s 之一, 实际 %s", VALID_TYPES, type))
                    .withSeverity("BLOCKING");
        }

        // R3 temp range 校验
        if (tempMin == null || tempMax == null || tempMin.compareTo(tempMax) >= 0) {
            throw new BusinessException(400, String.format(
                    "targetTempMin (%s) 必须 < targetTempMax (%s)", tempMin, tempMax))
                    .withSeverity("BLOCKING");
        }

        if (toleranceMinutes == null || toleranceMinutes <= 0) {
            throw new BusinessException(400, String.format(
                    "toleranceMinutes (%s) 必须 > 0", toleranceMinutes))
                    .withSeverity("BLOCKING");
        }

        // R4 幂等: equipmentCode 已存在 → 返 existingId 不创建
        Optional<ColdChainEquipment> existing = equipmentRepository
                .findByFactoryIdAndEquipmentCode(factoryId, equipmentCode);
        if (existing.isPresent()) {
            ColdChainEquipment e = existing.get();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "DUPLICATE");
            data.put("existingId", e.getId());
            data.put("equipmentCode", equipmentCode);
            data.put("count", 0);
            data.put("actionHint", "/foodsafety/cold-chain-equipment/" + e.getId());
            return buildSimpleResult(String.format(
                    "ℹ️ 设备编码 %s 在工厂 %s 已存在 (id=%d), 跳过重复注册",
                    equipmentCode, factoryId, e.getId()), data);
        }

        ColdChainEquipment equipment = ColdChainEquipment.builder()
                .factoryId(factoryId)
                .equipmentCode(equipmentCode)
                .equipmentName(equipmentName)
                .type(type)
                .targetTempMin(tempMin)
                .targetTempMax(tempMax)
                .unit("℃")
                .toleranceMinutes(toleranceMinutes)
                .location(location)
                .active(true)
                .build();

        equipment = equipmentRepository.save(equipment);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", equipment.getId());
        data.put("equipmentCode", equipmentCode);
        data.put("equipmentName", equipmentName);
        data.put("type", type);
        data.put("targetTempMin", tempMin);
        data.put("targetTempMax", tempMax);
        data.put("unit", "℃");
        data.put("toleranceMinutes", toleranceMinutes);
        data.put("location", location);
        data.put("active", true);
        data.put("actionHint", "/foodsafety/cold-chain-equipment/" + equipment.getId());

        String message = String.format(
                "✅ 已注册冷链设备 — %s (%s, %s) 温控 %s ~ %s°C, 容差 %s min",
                equipmentCode, equipmentName, type, tempMin, tempMax, toleranceMinutes);
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
