package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.material.CreateAbacaQuantityLogRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.warehouse.AbacaQuantityLog;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.material.AbacaQuantityLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * abaca_weight_log — 记录抄码品单箱实际称重 (W-ABA-1).
 *
 * <p>典型触发: "录入 BAT-20260514-001 批次第 3 箱牛肉, 实际称重 12.5kg"</p>
 */
@Slf4j
@Component
public class AbacaWeightLogTool extends AbstractBusinessTool {

    @Autowired
    private AbacaQuantityLogService abacaService;

    @Autowired
    private MaterialBatchRepository materialBatchRepo;

    @Override
    public String getToolName() {
        return "abaca_weight_log";
    }

    @Override
    public String getDescription() {
        return "记录抄码品入库时单箱实际称重. 适用场景: 卤制品工厂仓管员入库 (牛肉/猪肉/鸭肉等每箱重量不一的原料), " +
                "由扫码或语音触发, 逐箱录入实际重量.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("batchNumber", Map.of("type", "string",
                "description", "原料批次号 (如 BAT-20260514-001)"));
        properties.put("boxIndex", Map.of("type", "integer",
                "description", "第几箱 (1, 2, 3...). 不填则自动分配下一个箱号"));
        properties.put("actualWeight", Map.of("type", "number",
                "description", "实际称重 (单位由 unit 决定, 默认 kg)"));
        properties.put("unit", Map.of("type", "string",
                "description", "计量单位 (kg / g)", "default", "kg"));
        properties.put("weighingMethod", Map.of("type", "string",
                "description", "称重方式: SCALE / MANUAL / IMPORTED", "default", "SCALE"));
        properties.put("scaleDeviceId", Map.of("type", "string",
                "description", "电子秤设备 ID (如有对接)"));
        properties.put("notes", Map.of("type", "string", "description", "备注"));
        schema.put("properties", properties);
        schema.put("required", List.of("batchNumber", "actualWeight"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("batchNumber", "actualWeight");
    }

    @Override
    public ActionType getActionType() {
        return ActionType.WRITE;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) {
        String batchNumber = getString(params, "batchNumber");
        BigDecimal actualWeight = getBigDecimal(params, "actualWeight");
        Integer boxIndex = getInteger(params, "boxIndex");
        String unit = getString(params, "unit", "kg");
        String weighingMethod = getString(params, "weighingMethod", "SCALE");
        String scaleDeviceId = getString(params, "scaleDeviceId");
        String notes = getString(params, "notes");

        if (actualWeight == null || actualWeight.signum() <= 0) {
            throw new IllegalArgumentException("actualWeight 必须大于 0");
        }

        MaterialBatch batch = materialBatchRepo.findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        String.format("批次 '%s' 不存在 (工厂 %s)", batchNumber, factoryId)));

        CreateAbacaQuantityLogRequest req = new CreateAbacaQuantityLogRequest();
        req.setMaterialBatchId(batch.getId());
        req.setRawMaterialTypeId(batch.getMaterialTypeId());
        req.setBoxIndex(boxIndex);
        req.setActualWeight(actualWeight);
        req.setUnit(unit);
        req.setWeighingMethod(weighingMethod);
        req.setScaleDeviceId(scaleDeviceId);
        req.setNotes(notes);

        Long userId = getUserId(context);
        if (userId == null) {
            throw new IllegalArgumentException("context 缺少 userId, 无法识别称重员");
        }

        Map<String, Object> result = abacaService.create(factoryId, userId, req);
        Object savedLog = result.get("log");
        Object actualBoxIdx = savedLog instanceof AbacaQuantityLog
                ? ((AbacaQuantityLog) savedLog).getBoxIndex()
                : boxIndex;
        log.info("abaca_weight_log: factory={} batch={} box={} weight={}{}",
                factoryId, batchNumber, actualBoxIdx, actualWeight, unit);
        return buildSimpleResult(
                String.format("批次 %s 第 %s 箱称重已记录: %s%s",
                        batchNumber, actualBoxIdx, actualWeight, unit),
                result);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
