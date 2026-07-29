package com.cretas.aims.ai.tool.impl.shipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.service.TraceabilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 批次溯源工具
 *
 * 查询指定批次的基础溯源信息，包括原材料来源、加工记录等。
 *
 * Intent Code: TRACE_BATCH
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class TraceBatchTool extends AbstractBusinessTool {

    @Autowired
    private TraceabilityService traceabilityService;

    @Override
    public String getToolName() {
        return "trace_batch";
    }

    @Override
    public String getDescription() {
        return "查询批次的基础溯源信息。包括原材料来源、生产日期、加工环节等基本追溯数据。" +
                "适用场景：查询产品批次的来源信息、追溯生产过程。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchNumber: 批次号（必需）
        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "要溯源的批次号");
        properties.put("batchNumber", batchNumber);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchNumber"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchNumber");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行批次溯源 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 获取参数
        String batchNumber = getString(params, "batchNumber");

        // 调用溯源服务
        TraceabilityDTO.BatchTraceResponse traceResponse = traceabilityService.getBatchTrace(factoryId, batchNumber);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("batchNumber", batchNumber);
        result.put("traceInfo", traceResponse);

        log.info("批次溯源完成 - 批次号: {}", batchNumber);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "batchNumber", "请问要溯源哪个批次？请提供批次号。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "batchNumber", "批次号"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
