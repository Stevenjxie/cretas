package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.material.AbacaQuantityLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * abaca_weight_summary — 查询抄码品批次称重汇总 (W-ABA-1).
 *
 * <p>典型触发: "查 BAT-20260514-001 批次牛肉一共称了多少" / "BAT-... 的总重量"</p>
 *
 * <p>READ 操作.</p>
 */
@Slf4j
@Component
public class AbacaWeightSummaryTool extends AbstractBusinessTool {

    @Autowired
    private AbacaQuantityLogService abacaService;

    @Autowired
    private MaterialBatchRepository materialBatchRepo;

    @Override
    public String getToolName() {
        return "abaca_weight_summary";
    }

    @Override
    public String getDescription() {
        return "查询某抄码品批次的全部称重记录, 含总重量 + 箱数汇总. " +
                "支持按批次号 (batchNumber) 或批次 ID (batchId) 查询.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("batchNumber", Map.of("type", "string",
                "description", "原料批次号 (如 BAT-20260514-001), 与 batchId 二选一"));
        properties.put("batchId", Map.of("type", "string",
                "description", "原料批次 ID (UUID), 与 batchNumber 二选一"));
        schema.put("properties", properties);
        // 二选一, 不能用 required 强制; 改由 doExecute 校验
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();  // batchNumber 或 batchId 二选一, doExecute 内校验
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) {
        String batchNumber = getString(params, "batchNumber");
        String batchId = getString(params, "batchId");

        if ((batchNumber == null || batchNumber.isBlank()) && (batchId == null || batchId.isBlank())) {
            throw new IllegalArgumentException("必须提供 batchNumber 或 batchId 之一");
        }

        if (batchId == null || batchId.isBlank()) {
            MaterialBatch batch = materialBatchRepo.findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                    .orElseThrow(() -> new IllegalArgumentException(
                            String.format("批次 '%s' 不存在 (工厂 %s)", batchNumber, factoryId)));
            batchId = batch.getId();
        }

        Map<String, Object> summary = abacaService.listByBatch(factoryId, batchId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("batchNumber", batchNumber);
        result.putAll(summary);

        Object totalWeight = summary.get("batchTotalWeight");
        Object boxCount = summary.get("batchBoxCount");
        String msg = String.format("批次 %s 已称重 %s 箱, 总重量 %s",
                batchNumber != null ? batchNumber : batchId,
                boxCount, totalWeight);
        log.info("abaca_weight_summary: factory={} batchId={} totalWeight={} boxCount={}",
                factoryId, batchId, totalWeight, boxCount);
        return buildSimpleResult(msg, result);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
