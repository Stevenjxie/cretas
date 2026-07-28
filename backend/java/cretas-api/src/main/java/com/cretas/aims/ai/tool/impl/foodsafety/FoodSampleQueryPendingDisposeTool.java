package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.foodsafety.FoodSample;
import com.cretas.aims.repository.foodsafety.FoodSampleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 食品留样 — 待销毁查询 Tool (READ + FILTER) — Sprint 9 P2.A.
 *
 * <p>查询状态 RETAINED 且 expireTime &lt; now 的留样记录, 即"已过 48h 应销毁"的列表.
 * 用于工作台每日巡检 / AI 定时提醒.
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"今天哪些留样要销毁"</li>
 *   <li>"待销毁留样"</li>
 *   <li>"过期的留样"</li>
 *   <li>"48h 到了的留样"</li>
 *   <li>"留样过期列表"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code FOOD_SAMPLE_QUERY_PENDING_DISPOSE}
 */
@Slf4j
@Component
public class FoodSampleQueryPendingDisposeTool extends AbstractBusinessTool {

    @Autowired
    private FoodSampleRepository foodSampleRepository;

    @Override
    public String getToolName() {
        return "food_sample_query_pending_dispose";
    }

    @Override
    public String getDescription() {
        return "查待销毁留样 — status=RETAINED + expire_time < now (已过 48h 应销毁). "
                + "LLM 触发: '今天哪些留样要销毁' / '待销毁留样' / '过期的留样' / "
                + "'48h 到了的留样'. GB 31654-2021 标准化销毁巡检. read-only.";
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
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("food_sample_query_pending_dispose — factory={}", factoryId);

        LocalDateTime now = LocalDateTime.now();
        List<FoodSample> pending = foodSampleRepository
                .findByFactoryIdAndStatusAndExpireTimeBefore(factoryId, "RETAINED", now);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (FoodSample s : pending) {
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
            long overdueHours = ChronoUnit.HOURS.between(s.getExpireTime(), now);
            row.put("overdueHours", overdueHours);
            row.put("actionHint", "/foodsafety/food-samples/" + s.getId());
            rows.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", pending.size());
        data.put("queryTime", now.toString());
        data.put("samples", rows);
        data.put("actionHint", "/foodsafety/food-samples?status=RETAINED&overdue=true");

        String message;
        if (pending.isEmpty()) {
            message = String.format("✅ 工厂 %s 当前无待销毁留样 (所有 RETAINED 留样仍在 48h 内)", factoryId);
        } else {
            message = String.format(
                    "🚨 工厂 %s 共 %d 个留样已过 48h, 需立即销毁. 详见 samples 字段 (含 overdueHours)",
                    factoryId, pending.size());
        }
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
