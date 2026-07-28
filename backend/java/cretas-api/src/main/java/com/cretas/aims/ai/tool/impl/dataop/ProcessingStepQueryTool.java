package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.processing.ProcessingStageRecordDTO;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.ProcessingStageRecordService;
import com.cretas.aims.entity.ProductionBatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 加工步骤查询工具
 *
 * 查询生产批次的当前加工步骤和进度。
 * Intent Code: QUERY_PROCESSING_CURRENT_STEP / QUERY_PROCESSING_STEP
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ProcessingStepQueryTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingStageRecordService processingStageRecordService;

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "processing_step_query";
    }

    @Override
    public String getDescription() {
        return "查询生产批次的当前加工步骤和进度。" +
                "适用场景：查看批次当前状态、加工进度、当前工序。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "批次ID或批次号");
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

        try {
            // Get batch details first to get the numeric ID
            ProductionBatch batch = processingService.getBatchById(factoryId, batchId);
            if (batch == null) {
                return buildSimpleResult("未找到批次", Map.of("batchId", batchId, "found", false));
            }

            List<ProcessingStageRecordDTO> stages = processingStageRecordService.getByBatchId(factoryId, batch.getId());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("batchId", batchId);
            result.put("batchNumber", batch.getBatchNumber());
            result.put("currentStatus", batch.getStatus());
            result.put("stages", stages);
            result.put("stageCount", stages != null ? stages.size() : 0);

            return buildSimpleResult("加工步骤查询成功", result);
        } catch (Exception e) {
            log.error("加工步骤查询失败: factoryId={}, batchId={}", factoryId, batchId, e);
            throw e;
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
