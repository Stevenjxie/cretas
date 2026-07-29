package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.Label;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.service.LabelService;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 溯源码生成工具
 *
 * 为指定批次生成溯源码/溯源链接。
 * Intent Code: TRACE_GENERATE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class TraceGenerateTool extends AbstractBusinessTool {

    @Autowired
    private LabelService labelService;

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "trace_generate";
    }

    @Override
    public String getDescription() {
        return "为指定批次生成溯源码和溯源链接。" +
                "适用场景：生成溯源码、生成二维码、获取溯源链接。";
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
            // Get the batch to find batchNumber
            ProductionBatch batch = processingService.getBatchById(factoryId, batchId);
            if (batch == null) {
                return buildSimpleResult("未找到批次", Map.of("batchId", batchId, "found", false));
            }

            String batchNumber = batch.getBatchNumber();

            // Generate trace code
            String traceCode = labelService.generateTraceCode(factoryId, batchNumber);

            // Create a label record
            String labelCode = labelService.generateLabelCode(factoryId, "QR_CODE");
            Label label = new Label();
            label.setId(UUID.randomUUID().toString());
            label.setFactoryId(factoryId);
            label.setLabelCode(labelCode);
            label.setLabelType("QR_CODE");
            label.setBatchType("PRODUCTION");
            label.setBatchId(batchId);
            label.setProductionBatchId(batch.getId());
            label.setTraceCode(traceCode);
            label.setStatus("ACTIVE");

            Label created = labelService.createLabel(label);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("batchId", batchId);
            result.put("batchNumber", batchNumber);
            result.put("traceCode", traceCode);
            result.put("labelCode", labelCode);
            result.put("labelId", created.getId());

            return buildSimpleResult("溯源码生成成功", result);
        } catch (Exception e) {
            log.error("溯源码生成失败: factoryId={}, batchId={}", factoryId, batchId, e);
            throw e;
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
