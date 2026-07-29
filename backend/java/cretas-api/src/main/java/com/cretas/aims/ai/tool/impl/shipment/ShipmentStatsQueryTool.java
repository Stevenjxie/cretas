package com.cretas.aims.ai.tool.impl.shipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.service.ShipmentRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 出货统计查询工具
 *
 * 查询出货统计数据，支持按时间周期或客户维度统计。
 * 返回总数、各状态数量、时间段内出货情况等统计信息。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ShipmentStatsQueryTool extends AbstractBusinessTool {

    @Autowired
    private ShipmentRecordService shipmentRecordService;

    @Override
    public String getToolName() {
        return "shipment_stats_query";
    }

    @Override
    public String getDescription() {
        return "查询出货统计数据。" +
                "支持按时间周期（今日/本周/本月）或按客户维度统计。" +
                "返回总数、各状态数量（待发货、已发货、已送达、已退回）等信息。" +
                "适用场景：查看出货概况、业务报表、绩效统计。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // period: 时间周期（可选）
        Map<String, Object> period = new HashMap<>();
        period.put("type", "string");
        period.put("description", "统计时间周期");
        period.put("enum", Arrays.asList("today", "week", "month", "all"));
        period.put("default", "all");
        properties.put("period", period);

        // customerId: 客户ID（可选）
        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "按客户ID筛选统计，可选");
        properties.put("customerId", customerId);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 1. 获取参数
        String period = getString(params, "period", "all");
        String customerId = getString(params, "customerId");

        log.info("查询出货统计: factoryId={}, period={}, customerId={}",
                factoryId, period, customerId);

        // 2. 计算日期范围
        LocalDate startDate = null;
        LocalDate endDate = LocalDate.now();

        switch (period.toLowerCase()) {
            case "today":
                startDate = LocalDate.now();
                break;
            case "week":
                startDate = LocalDate.now().minusDays(7);
                break;
            case "month":
                startDate = LocalDate.now().minusDays(30);
                break;
            case "all":
            default:
                // 不限制日期
                break;
        }

        // 3. 获取统计数据
        long total;
        long pending;
        long shipped;
        long delivered;
        long returned;

        if (customerId != null && !customerId.trim().isEmpty()) {
            // 按客户统计
            List<ShipmentRecord> records = shipmentRecordService.getByCustomer(factoryId, customerId);

            // 如果有日期限制，过滤记录
            if (startDate != null) {
                final LocalDate finalStartDate = startDate;
                records = filterByDateRange(records, finalStartDate, endDate);
            }

            total = records.size();
            pending = countByStatus(records, "pending");
            shipped = countByStatus(records, "shipped");
            delivered = countByStatus(records, "delivered");
            returned = countByStatus(records, "returned");
        } else if (startDate != null) {
            // 按日期范围统计
            List<ShipmentRecord> records = shipmentRecordService.getByDateRange(factoryId, startDate, endDate);

            total = records.size();
            pending = countByStatus(records, "pending");
            shipped = countByStatus(records, "shipped");
            delivered = countByStatus(records, "delivered");
            returned = countByStatus(records, "returned");
        } else {
            // 全量统计
            total = shipmentRecordService.countByFactoryId(factoryId);
            pending = shipmentRecordService.countByStatus(factoryId, "pending");
            shipped = shipmentRecordService.countByStatus(factoryId, "shipped");
            delivered = shipmentRecordService.countByStatus(factoryId, "delivered");
            returned = shipmentRecordService.countByStatus(factoryId, "returned");
        }

        // 4. 计算完成率
        double completionRate = total > 0 ? (double) delivered / total * 100 : 0;
        double inProgressRate = total > 0 ? (double) (pending + shipped) / total * 100 : 0;

        // 5. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("period", period);
        if (customerId != null) {
            result.put("customerId", customerId);
        }

        // 统计数据
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("pending", pending);
        stats.put("shipped", shipped);
        stats.put("delivered", delivered);
        stats.put("returned", returned);
        stats.put("completionRate", String.format("%.1f%%", completionRate));
        stats.put("inProgressRate", String.format("%.1f%%", inProgressRate));
        result.put("stats", stats);

        // 生成摘要信息
        String summary = String.format(
                "出货统计（%s）：总计%d条，待发货%d，已发货%d，已送达%d，已退回%d，完成率%.1f%%",
                getPeriodDescription(period), total, pending, shipped, delivered, returned, completionRate
        );
        result.put("summary", summary);
        result.put("message", summary);

        log.info("出货统计查询完成: {}", summary);

        return result;
    }

    /**
     * 按日期范围过滤记录
     */
    private List<ShipmentRecord> filterByDateRange(List<ShipmentRecord> records, LocalDate startDate, LocalDate endDate) {
        List<ShipmentRecord> filtered = new ArrayList<>();
        for (ShipmentRecord record : records) {
            if (record.getShipmentDate() != null) {
                LocalDate shipDate = record.getShipmentDate();
                if (!shipDate.isBefore(startDate) && !shipDate.isAfter(endDate)) {
                    filtered.add(record);
                }
            }
        }
        return filtered;
    }

    /**
     * 按状态统计数量
     */
    private long countByStatus(List<ShipmentRecord> records, String status) {
        return records.stream()
                .filter(r -> status.equals(r.getStatus()))
                .count();
    }

    /**
     * 获取周期描述
     */
    private String getPeriodDescription(String period) {
        return switch (period.toLowerCase()) {
            case "today" -> "今日";
            case "week" -> "近7天";
            case "month" -> "近30天";
            default -> "全部";
        };
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = new HashMap<>();
        questions.put("period", "请问您要查询哪个时间段的统计？可选：today（今日）、week（本周）、month（本月）、all（全部）");
        questions.put("customerId", "请问您要按哪个客户筛选统计？请提供客户ID。");

        String question = questions.get(paramName);
        return question != null ? question : super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = new HashMap<>();
        displayNames.put("period", "统计周期");
        displayNames.put("customerId", "客户ID");

        String name = displayNames.get(paramName);
        return name != null ? name : super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
