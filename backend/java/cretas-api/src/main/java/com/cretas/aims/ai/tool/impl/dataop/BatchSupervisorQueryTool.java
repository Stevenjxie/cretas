package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 批次负责人查询工具
 *
 * 查询生产批次的负责人/主管信息。
 * Intent Code: QUERY_PROCESSING_BATCH_SUPERVISOR
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class BatchSupervisorQueryTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "batch_supervisor_query";
    }

    @Override
    public String getDescription() {
        return "查询生产批次的负责人/主管信息。" +
                "适用场景：查看谁在负责某个批次、批次主管是谁。";
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
            ProductionBatch batch = processingService.getBatchById(factoryId, batchId);
            if (batch == null) {
                return buildSimpleResult("未找到批次", Map.of("batchId", batchId, "found", false));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("batchId", batchId);
            result.put("batchNumber", batch.getBatchNumber());
            result.put("supervisorId", batch.getSupervisorId());
            result.put("status", batch.getStatus());
            result.put("productTypeId", batch.getProductTypeId());

            return buildSimpleResult("批次负责人查询成功", result);
        } catch (Exception e) {
            log.error("批次负责人查询失败: factoryId={}, batchId={}", factoryId, batchId, e);
            throw e;
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
