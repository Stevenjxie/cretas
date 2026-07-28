package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 查询批次工人信息工具
 *
 * 根据批次ID查询分配到该批次的工人列表及其工作状态。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class MaterialBatchWorkersTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "material_batch_workers";
    }

    @Override
    public String getDescription() {
        return "查询生产批次分配的工人列表。" +
                "返回工人姓名、状态等详细信息。" +
                "适用场景：查看批次人员配置、了解工人分配情况。";
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

        log.info("查询批次工人: factoryId={}, batchId={}", factoryId, batchId);

        List<Map<String, Object>> workers = processingService.getBatchWorkers(factoryId, Long.parseLong(batchId));

        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("workers", workers);
        result.put("total", workers.size());

        StringBuilder sb = new StringBuilder();
        sb.append("批次 ").append(batchId).append(" 工人信息：");
        if (workers.isEmpty()) {
            sb.append("暂无分配工人");
        } else {
            sb.append("共 ").append(workers.size()).append(" 名工人");
        }
        result.put("message", sb.toString());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("batchId".equals(paramName)) {
            return "请问您要查询哪个批次的工人信息？请提供批次ID。";
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
