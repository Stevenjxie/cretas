package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 查询生产批次负责人/工人工具
 *
 * 根据批次ID查询该批次分配的工作人员信息。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class MaterialBatchSupervisorTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "material_batch_supervisor";
    }

    @Override
    public String getDescription() {
        return "查询生产批次的负责人和工作人员信息。" +
                "返回批次分配的所有工作人员列表及其状态。" +
                "适用场景：查看批次由谁负责、了解人员分配情况。";
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

        log.info("查询批次负责人: factoryId={}, batchId={}", factoryId, batchId);

        List<Map<String, Object>> workers = processingService.getBatchWorkers(factoryId, Long.parseLong(batchId));

        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("workers", workers);
        result.put("total", workers.size());

        if (workers.isEmpty()) {
            result.put("message", "批次 " + batchId + " 暂未分配负责人");
        } else {
            result.put("message", "批次 " + batchId + " 共 " + workers.size() + " 名工作人员");
        }

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("batchId".equals(paramName)) {
            return "请问您要查询哪个批次的负责人？请提供批次ID。";
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
