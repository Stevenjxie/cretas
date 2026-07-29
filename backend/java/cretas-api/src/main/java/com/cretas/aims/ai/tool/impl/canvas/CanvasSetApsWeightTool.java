package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.aps.StrategyWeightAdaptationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas APS 策略权重调整工具 (Round 4 Fix P2-26)
 *
 * 连接 Canvas 配置层与 APS 排产策略引擎。从 TriggerChain 或 Scheduler 调用，
 * 可以在定时任务中动态调整 APS 权重（如中秋/春节临时把"加急优先级"调高）。
 *
 * 使用场景:
 *   - 蓝海水产中秋备货: 定时 9/1 把 urgency_first 权重调到 0.8
 *   - 节假日: 把 earliest_deadline 调低避免过度赶工
 *   - 批次堆积: 动态提升 fifo 权重
 *
 * 参数:
 *   weights: Map<String, Double> — 策略名到权重值的映射
 *     如 {"urgency_first": 0.8, "earliest_deadline": 0.1, "fifo": 0.1}
 *   reason: String — 调整原因 (用于审计)
 *
 * 权重值之和不必为 1，但建议归一化以保证策略对比有意义。
 */
@Slf4j
@Component
public class CanvasSetApsWeightTool extends AbstractBusinessTool {

    @Autowired(required = false)
    private StrategyWeightAdaptationService strategyWeightService;

    @Override
    public String getToolName() {
        return "canvas_set_aps_weight";
    }

    @Override
    public String getDescription() {
        return "调整 APS 排产策略权重。可在 TriggerChain 或 Scheduler 中调用，实现节假日/季节性优先级自动切换。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("weights", Map.of("type", "object",
            "description", "策略名到权重值映射, 如 {urgency_first: 0.8, earliest_deadline: 0.1}"));
        properties.put("reason", Map.of("type", "string",
            "description", "调整原因, 用于审计日志"));
        return Map.of("type", "object", "properties", properties, "required", List.of("weights"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("weights");
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        if (strategyWeightService == null) {
            throw new IllegalStateException("StrategyWeightAdaptationService 未就绪 (APS 模块未启用)");
        }

        Map<String, Object> weightsRaw = (Map<String, Object>) params.get("weights");
        if (weightsRaw == null || weightsRaw.isEmpty()) {
            throw new IllegalArgumentException("weights 不能为空");
        }

        // Convert to Double
        Map<String, Double> weights = new HashMap<>();
        for (Map.Entry<String, Object> e : weightsRaw.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Number n) {
                weights.put(e.getKey(), n.doubleValue());
            } else if (v instanceof CharSequence s) {
                try { weights.put(e.getKey(), Double.parseDouble(s.toString())); }
                catch (NumberFormatException ignored) {}
            }
        }

        if (weights.isEmpty()) {
            throw new IllegalArgumentException("weights 解析后为空, 请检查类型");
        }

        String reason = params.containsKey("reason") ? String.valueOf(params.get("reason"))
            : "Canvas TriggerChain 调用";

        log.info("Canvas set APS weights [factory={}] {} (reason: {})", factoryId, weights, reason);

        var result = strategyWeightService.setWeights(factoryId, weights, reason);

        Map<String, Object> resp = new HashMap<>();
        resp.put("factoryId", factoryId);
        resp.put("weightsApplied", weights);
        resp.put("reason", reason);
        resp.put("adjustmentId", result != null ? result.toString() : null);
        return buildSimpleResult("APS 策略权重已调整", resp);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
