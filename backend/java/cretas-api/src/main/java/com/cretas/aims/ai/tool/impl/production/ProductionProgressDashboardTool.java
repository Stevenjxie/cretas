package com.cretas.aims.ai.tool.impl.production;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.production.ProductionProgressDashboardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B8 — 查询当天生产进度数字看板.
 */
@Slf4j
@Component
public class ProductionProgressDashboardTool extends AbstractBusinessTool {

    @Autowired
    private ProductionProgressDashboardService dashboardService;

    @Override
    public String getToolName() {
        return "production_progress_dashboard";
    }

    @Override
    public String getDescription() {
        return "查询当天生产进度数字看板 — 展示所有计划的工序进度百分比, 适合大屏展示。" +
               "当用户说'生产进度'/'打屏看板'/'今天生产情况'时调用";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "date", Map.of("type", "string", "description", "yyyy-MM-dd, 为空则当天")
            ),
            "required", List.of()
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("production", "dashboard");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String dateStr = getString(params, "date");
        LocalDate date = (dateStr != null && !dateStr.isBlank()) ? LocalDate.parse(dateStr) : null;
        Map<String, Object> data = dashboardService.getDashboard(factoryId, date);
        Object summary = data.get("summary");
        return buildSimpleResult("生产进度看板 " + data.get("date"), data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
