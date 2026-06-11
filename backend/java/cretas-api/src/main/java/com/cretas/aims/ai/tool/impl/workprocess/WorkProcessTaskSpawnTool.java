package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工序任务 spawn Tool (Track D2 — M-WP-1/2).
 *
 * <p>触发: "给批次 BAT-001 生成工序任务" / "为生产批次 12 spawn 工序"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkProcessTaskSpawnTool extends AbstractBusinessTool {

    private final WorkProcessTaskService workProcessTaskService;

    @Override
    public String getToolName() {
        return "work_process_task_spawn";
    }

    @Override
    public String getDescription() {
        return "为生产批次从 product_work_processes 模板生成工序任务实例。需要 productionBatchId 和 productTypeId。"
                + "每个绑定的工序生成一个 PENDING 状态的任务。适用场景: 启动批次后初始化工序流程。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("productionBatchId", Map.of(
                "type", "integer",
                "description", "生产批次ID (Long)"));
        properties.put("productTypeId", Map.of(
                "type", "string",
                "description", "产品类型ID (该批次绑定的产品)"));

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("productionBatchId", "productTypeId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("productionBatchId", "productTypeId");
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        Long productionBatchId = getLong(params, "productionBatchId");
        String productTypeId = getString(params, "productTypeId");
        // Fable 审计修复 (问题2): 走计划模式感知的 spawn — 与计划转批次主路径一致。
        // skip=true 计划经 AI 补 spawn 时生成两点哨兵任务 (而非旧的恒逐道), 不与计划模式矛盾。
        List<WorkProcessTaskDTO> spawned = workProcessTaskService.spawnTasksForBatch(
                factoryId, productionBatchId, productTypeId);
        log.info("AI 工具 spawn 工序: batch={}, productType={}, count={}",
                productionBatchId, productTypeId, spawned.size());
        return buildSimpleResult(
                String.format("已为批次 %d 生成 %d 道工序任务", productionBatchId, spawned.size()),
                spawned);
    }
}
