package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 查询生产批次工序详情工具
 *
 * 根据批次ID查询该批次的完整工序信息，包括批次基本信息和所有工序步骤。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class MaterialProcessingStepTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "material_processing_step";
    }

    @Override
    public String getDescription() {
        return "查询生产批次的完整工序详情。" +
                "返回批次基本信息和所有工序步骤的时间线。" +
                "适用场景：查看批次全部加工工序、了解工序流转记录。";
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

        log.info("查询批次工序详情: factoryId={}, batchId={}", factoryId, batchId);

        ProductionBatch batch = processingService.getBatchById(factoryId, batchId);
        List<Map<String, Object>> timeline = processingService.getBatchTimeline(factoryId, batchId);

        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("batch", batch);
        result.put("timeline", timeline);
        result.put("totalSteps", timeline.size());
        result.put("message", String.format("批次 %s 工序详情 (共%d步)", batchId, timeline.size()));

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("batchId".equals(paramName)) {
            return "请问您要查询哪个批次的工序详情？请提供批次ID。";
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
