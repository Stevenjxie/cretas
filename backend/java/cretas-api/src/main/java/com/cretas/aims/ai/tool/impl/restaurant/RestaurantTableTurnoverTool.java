package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 翻台率估算工具
 *
 * 当日订单数 / 座位数 = 翻台率。
 * 对应意图: RESTAURANT_TABLE_TURNOVER
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class RestaurantTableTurnoverTool extends AbstractBusinessTool {

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Override
    public String getToolName() {
        return "restaurant_table_turnover";
    }

    @Override
    public String getDescription() {
        return "翻台率估算，基于每日订单数和座位数计算。默认40座位、近7天。" +
                "适用场景：经营效率评估、座位利用率分析。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> seatCount = new HashMap<>();
        seatCount.put("type", "integer");
        seatCount.put("description", "餐厅座位数，默认40");
        seatCount.put("default", 40);
        seatCount.put("minimum", 1);
        properties.put("seatCount", seatCount);

        Map<String, Object> days = new HashMap<>();
        days.put("type", "integer");
        days.put("description", "统计天数，默认7天");
        days.put("default", 7);
        days.put("minimum", 1);
        properties.put("days", days);

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
        log.info("执行翻台率估算 - 工厂ID: {}, 参数: {}", factoryId, params);

        int seatCount = getInteger(params, "seatCount", 40);
        int days = getInteger(params, "days", 7);

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1);

        List<Map<String, Object>> dailyList = new ArrayList<>();
        double totalTurnover = 0;
        int validDays = 0;

        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            long dayOrders = salesOrderRepository.countByFactoryIdAndDate(factoryId, d);
            double turnover = seatCount > 0 ? (double) dayOrders / seatCount : 0;
            totalTurnover += turnover;
            if (dayOrders > 0) validDays++;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("日期", d.toString());
            row.put("订单数", dayOrders);
            row.put("翻台率", String.format("%.2f", turnover));
            dailyList.add(row);
        }

        // 用 validDays (有销售的营业日) 做分母, 不用 days (整窗口) — 否则歇业/无销售日稀释平均翻台率。
        double avgTurnover = validDays > 0 ? totalTurnover / validDays : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        // R27: 数据源为工厂 ERP 销售单 — POS 餐饮租户此表为空, 曾返回无 message
        // 的空壳被兜底成"操作已完成。"。全零窗口诚实拒答并给可用替代;
        // 有数据时 message 直给结论, 不再依赖调用方拼装。
        if (validDays == 0) {
            String noData = String.format(
                "近%d天（%s 至 %s）没有可用于计算翻台率的订单数据，无法给出翻台率，"
                + "也不会用其他指标替代。可以先问「晚上生意怎么样」看时段人效，"
                + "或「昨天卖了几单」看订单量。",
                days, startDate, endDate);
            result.put("message", noData);
            result.put("dataAvailable", false);
            return result;
        }
        result.put("message", String.format(
                "近%d天（%s 至 %s）按 %d 座估算，平均翻台率 %.2f 次/天"
                + "（有销售的营业日 %d 天）。行业参考：快餐 4-6、正餐 2-3、火锅 2-4 次/天。"
                + "翻台率 = 当日订单数 / 座位数；如座位数不准确请提供实际座位数。",
                days, startDate, endDate, seatCount, avgTurnover, validDays));
        result.put("统计周期", String.format("近%d天（%s 至 %s）", days, startDate, endDate));
        result.put("座位数", seatCount);
        result.put("平均翻台率", String.format("%.2f 次/天", avgTurnover));
        result.put("每日详情", dailyList);
        result.put("行业参考", "快餐 4-6 次/天，正餐 2-3 次/天，火锅 2-4 次/天");
        result.put("说明", "翻台率 = 当日订单数 / 座位数。如座位数不准确，请在参数中提供实际座位数。");

        log.info("翻台率估算完成 - 平均翻台率: {} 次/天", String.format("%.2f", avgTurnover));
        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "seatCount", "请问餐厅有多少个座位？",
            "days", "请问要查看多少天的翻台率？"
        );
        return questions.get(paramName);
    }
}
