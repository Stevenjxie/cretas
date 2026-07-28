package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * MRP 物料需求计算 Tool
 *
 * 根据库存数据计算物料需求。
 * 对应意图: MRP_CALCULATION
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ReportMrpCalculationTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_mrp_calculation";
    }

    @Override
    public String getDescription() {
        return "MRP物料需求计算，根据库存和生产计划计算物料需求。" +
                "适用场景：物料需求计划、MRP计算、库存与需求匹配。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行MRP物料需求计算 - 工厂ID: {}", factoryId);

        Map<String, Object> inventory = reportService.getInventoryReport(factoryId, LocalDate.now());

        Map<String, Object> result = new HashMap<>();
        result.put("inventoryData", inventory);
        result.put("calculationType", "MRP");
        result.put("period", LocalDate.now() + " 至 " + LocalDate.now().plusDays(7));

        double totalStock = extractDouble(inventory, "totalStock", 0);
        double reservedStock = extractDouble(inventory, "reservedStock", 0);
        double availableStock = totalStock - reservedStock;

        result.put("totalStock", totalStock);
        result.put("reservedStock", reservedStock);
        result.put("availableStock", availableStock);

        StringBuilder sb = new StringBuilder();
        sb.append("MRP物料需求计算结果\n");
        sb.append("计算周期: 未来7天\n\n");
        sb.append("当前库存总量: ").append(String.format("%.0f", totalStock)).append("\n");
        sb.append("已预留: ").append(String.format("%.0f", reservedStock)).append("\n");
        sb.append("可用库存: ").append(String.format("%.0f", availableStock)).append("\n\n");
        sb.append("建议: 请结合生产计划确认物料需求");

        result.put("message", sb.toString());

        return result;
    }

    private double extractDouble(Map<String, Object> map, String key, double defaultVal) {
        if (map == null || !map.containsKey(key)) return defaultVal;
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(String.valueOf(val)); } catch (Exception e) { return defaultVal; }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
