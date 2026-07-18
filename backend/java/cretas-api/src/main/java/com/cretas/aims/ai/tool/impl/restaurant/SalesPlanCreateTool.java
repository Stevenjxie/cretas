package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.restaurant.SalesPlan;
import com.cretas.aims.service.restaurant.SalesPlanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SalesPlanCreateTool extends AbstractBusinessTool {

    @Autowired
    private SalesPlanService salesPlanService;

    @Override
    public String getToolName() {
        return "restaurant_sales_plan_create";
    }

    @Override
    public String getDescription() {
        return "创建或更新门店月度销售计划 — 设定目标营收/订单数, 支持节假日调整.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "store_id", Map.of("type", "string", "description", "门店ID"),
                "month", Map.of("type", "string", "description", "计划月份 YYYY-MM"),
                "target_revenue", Map.of("type", "number", "description", "目标营收 (元)"),
                "target_order_count", Map.of("type", "integer", "description", "目标订单数"),
                "notes", Map.of("type", "string", "description", "备注")
            ),
            "required", List.of("month", "target_revenue")
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("month", "target_revenue");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String storeId = getString(params, "store_id");
        String monthStr = getString(params, "month");
        LocalDate month = LocalDate.parse(monthStr + "-01", DateTimeFormatter.ISO_LOCAL_DATE);
        BigDecimal target = new BigDecimal(params.get("target_revenue").toString());
        Integer orderCount = params.get("target_order_count") != null
                ? Integer.parseInt(params.get("target_order_count").toString()) : null;
        String notes = getString(params, "notes");

        SalesPlan plan = salesPlanService.createOrUpdate(
                factoryId, storeId, month, target, orderCount, null, null, notes);

        Map<String, Object> result = new HashMap<>();
        result.put("planId", plan.getId());
        result.put("month", monthStr);
        result.put("targetRevenue", target);
        result.put("targetOrderCount", orderCount != null ? orderCount : "未设置");
        return buildSimpleResult("销售计划已设置", result);
    }
}
