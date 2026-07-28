package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 批次员工查询工具
 *
 * 查询指定生产批次分配的员工列表，包含员工信息和工作状态。
 * 用于了解批次人员配置情况。
 *
 * Intent Code: PROCESSING_BATCH_WORKERS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class ProcessingBatchWorkersTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "processing_batch_workers";
    }

    @Override
    public String getDescription() {
        return "查询生产批次分配的员工列表。" +
                "返回员工信息和工作状态（进行中/已完成）。" +
                "适用场景：查看批次人员配置、了解工作分配情况。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 批次ID（必需）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "integer");
        batchId.put("description", "要查询的生产批次ID");
        properties.put("batchId", batchId);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("查询批次员工列表 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 1. 提取必需参数
        Long batchId = getLong(params, "batchId");

        // 参数验证
        if (batchId == null) {
            throw new IllegalArgumentException("批次ID不能为空");
        }

        log.info("查询批次员工: factoryId={}, batchId={}", factoryId, batchId);

        // 2. 调用服务获取员工列表
        List<Map<String, Object>> workers = processingService.getBatchWorkers(factoryId, batchId);

        // 3. 统计信息
        int totalWorkers = workers != null ? workers.size() : 0;
        int workingCount = 0;
        int completedCount = 0;

        if (workers != null) {
            for (Map<String, Object> worker : workers) {
                Object status = worker.get("status");
                if ("WORKING".equals(status) || "IN_PROGRESS".equals(status)) {
                    workingCount++;
                } else if ("COMPLETED".equals(status) || "CHECKED_OUT".equals(status)) {
                    completedCount++;
                }
            }
        }

        // 4. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("totalWorkers", totalWorkers);
        result.put("workingCount", workingCount);
        result.put("completedCount", completedCount);
        result.put("workers", workers != null ? workers : Collections.emptyList());

        // 生成消息
        String message;
        if (totalWorkers == 0) {
            message = String.format("批次 %d 暂无分配员工", batchId);
        } else {
            message = String.format("批次 %d 共有 %d 名员工，其中 %d 人工作中，%d 人已完成",
                    batchId, totalWorkers, workingCount, completedCount);
        }

        return buildSimpleResult(message, result);
    }

    /**
     * 覆盖参数问题提示
     */
    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "batchId", "请问要查询哪个批次的员工列表？请提供批次ID。"
        );
        String question = questions.get(paramName);
        return question != null ? question : super.getParameterQuestion(paramName);
    }

    /**
     * 覆盖参数显示名称
     */
    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "batchId", "批次ID"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
