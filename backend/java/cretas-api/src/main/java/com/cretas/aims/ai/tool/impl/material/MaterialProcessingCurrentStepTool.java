package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 查询生产批次当前工序进度工具
 *
 * 根据批次ID查询该批次当前所处的加工工序节点，返回时间线和当前步骤信息。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class MaterialProcessingCurrentStepTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "material_processing_current_step";
    }

    @Override
    public String getDescription() {
        return "查询生产批次当前所处的加工工序进度。" +
                "返回批次的完整时间线和当前步骤信息。" +
                "适用场景：了解批次加工进展、查看当前处于哪个工序节点。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "生产批次ID");
        properties.put("batchId", batchId);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("batchId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("batchId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchId = getString(params, "batchId");

        log.info("查询批次当前工序: factoryId={}, batchId={}", factoryId, batchId);

        List<Map<String, Object>> timeline = processingService.getBatchTimeline(factoryId, batchId);

        Map<String, Object> currentStep = timeline.isEmpty()
                ? Collections.singletonMap("stepName", "无记录")
                : timeline.get(timeline.size() - 1);

        String currentStepName = timeline.isEmpty() ? "无"
                : String.valueOf(currentStep.getOrDefault("stepName", "进行中"));

        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("timeline", timeline);
        result.put("currentStep", currentStep);
        result.put("totalSteps", timeline.size());
        result.put("message", String.format("批次 %s 当前进度: %s (共%d个节点)",
                batchId, currentStepName, timeline.size()));

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("batchId".equals(paramName)) {
            return "请问您要查询哪个批次的当前工序？请提供批次ID。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("batchId".equals(paramName)) {
            return "批次ID";
        }
        return super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
