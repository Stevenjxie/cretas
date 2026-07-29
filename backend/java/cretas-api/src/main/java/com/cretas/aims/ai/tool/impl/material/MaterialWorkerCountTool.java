package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 车间实时工人数量查询工具
 *
 * 查询车间实时在岗工人数量和生产概况（活跃批次、今日产出等）。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class MaterialWorkerCountTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "material_worker_count";
    }

    @Override
    public String getDescription() {
        return "查询车间实时在岗工人数量和生产概况。" +
                "返回在岗工人数、进行中批次数、今日产出等实时数据。" +
                "适用场景：了解车间实时人力、查看当前产能、生产调度参考。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("查询车间实时概况: factoryId={}", factoryId);

        Map<String, Object> dashboard = processingService.getDashboardOverview(factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("factoryId", factoryId);

        StringBuilder sb = new StringBuilder();
        sb.append("车间实时概况：");

        if (dashboard.get("activeWorkers") != null) {
            result.put("activeWorkers", dashboard.get("activeWorkers"));
            sb.append("在岗工人: ").append(dashboard.get("activeWorkers")).append("人");
        }
        if (dashboard.get("activeBatches") != null) {
            result.put("activeBatches", dashboard.get("activeBatches"));
            sb.append("，进行中批次: ").append(dashboard.get("activeBatches")).append("个");
        }
        if (dashboard.get("todayOutput") != null) {
            result.put("todayOutput", dashboard.get("todayOutput"));
            sb.append("，今日产出: ").append(dashboard.get("todayOutput"));
        }
        if (dashboard.get("productionLines") != null) {
            result.put("productionLines", dashboard.get("productionLines"));
        }

        result.put("message", sb.toString());

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
