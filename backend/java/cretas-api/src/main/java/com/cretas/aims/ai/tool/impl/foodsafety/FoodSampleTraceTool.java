package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.foodsafety.FoodSample;
import com.cretas.aims.repository.foodsafety.FoodSampleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 食品留样 — 按批次追溯 Tool (READ) — Sprint 9 P2.A.
 *
 * <p>客户投诉场景下回溯: 给定 batchNumber, 列出该批次所有留样记录 + 当前 status.
 * 决定是否可用于送检 (RETAINED) 或已被使用/销毁 (DISPOSED / USED_FOR_INSPECTION).
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"B-X 留样还在吗"</li>
 *   <li>"查 B-X 的留样"</li>
 *   <li>"客户投诉, 找 B-X 留样去检测"</li>
 *   <li>"批次 B-X 留样追溯"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code FOOD_SAMPLE_TRACE}
 */
@Slf4j
@Component
public class FoodSampleTraceTool extends AbstractBusinessTool {

    @Autowired
    private FoodSampleRepository foodSampleRepository;

    @Override
    public String getToolName() {
        return "food_sample_trace";
    }

    @Override
    public String getDescription() {
        return "按批次号追溯食品留样 (客户投诉场景). 列出该批次的全部留样记录 + status + "
                + "是否可用于送检. LLM 触发: 'B-X 留样还在吗' / '查 B-X 的留样' / "
                + "'客户投诉, 找 B-X 留样去检测' / '批次 B-X 留样追溯'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号 (必填). e.g. B-20260521-A01");
        properties.put("batchNumber", batchNumber);

        schema.put("properties", properties);
        schema.put("required", List.of("batchNumber"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("batchNumber");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        log.info("food_sample_trace — factory={} batch={}", factoryId, batchNumber);

        List<FoodSample> samples = foodSampleRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);

        List<Map<String, Object>> rows = new ArrayList<>();
        int retainedCount = 0;
        int disposedCount = 0;
        int inspectionCount = 0;

        for (FoodSample s : samples) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("batchNumber", s.getBatchNumber());
            row.put("materialBatchId", s.getMaterialBatchId());
            row.put("productName", s.getProductName());
            row.put("sampleQuantity", s.getSampleQuantity());
            row.put("unit", s.getUnit());
            row.put("sampleTime", s.getSampleTime().toString());
            row.put("expireTime", s.getExpireTime().toString());
            row.put("storageLocation", s.getStorageLocation());
            row.put("status", s.getStatus());
            row.put("disposedAt", s.getDisposedAt() != null ? s.getDisposedAt().toString() : null);
            row.put("disposeReason", s.getDisposeReason());
            row.put("retainedByUserId", s.getRetainedByUserId());
            row.put("disposedByUserId", s.getDisposedByUserId());
            row.put("actionHint", "/foodsafety/food-samples/" + s.getId());
            rows.add(row);

            switch (s.getStatus()) {
                case "RETAINED": retainedCount++; break;
                case "DISPOSED": disposedCount++; break;
                case "USED_FOR_INSPECTION": inspectionCount++; break;
                default: break;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchNumber", batchNumber);
        data.put("totalCount", samples.size());
        data.put("retainedCount", retainedCount);
        data.put("disposedCount", disposedCount);
        data.put("inspectionCount", inspectionCount);
        data.put("availableForInspection", retainedCount > 0);
        data.put("samples", rows);
        data.put("actionHint", "/foodsafety/food-samples?batchNumber=" + batchNumber);

        String message;
        if (samples.isEmpty()) {
            message = String.format(
                    "⚠️ 批次 %s 无留样记录, 客户投诉无法送检追溯 — 违反 GB 31654-2021",
                    batchNumber);
        } else if (retainedCount > 0) {
            message = String.format(
                    "✅ 批次 %s 共 %d 份留样, 其中 %d 份 RETAINED 可送检 (其余: %d DISPOSED, %d INSPECTION)",
                    batchNumber, samples.size(), retainedCount, disposedCount, inspectionCount);
        } else {
            message = String.format(
                    "ℹ️ 批次 %s 共 %d 份留样, 全部已 DISPOSED/INSPECTION, 无可送检留样",
                    batchNumber, samples.size());
        }
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
