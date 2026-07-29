package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 客户收入趋势工具 (Sprint 8 P1 — 卤味老板 Workdesk V1, AGGREGATE).
 *
 * <p>聚合 SalesOrder 按月统计销售额, 默认看近 2 个月. 用于"X 客户本月 vs 上月" /
 * "客户收入趋势" 场景.
 *
 * <p>读路径无副作用. 防呆 R2 — 含 customer name + 每月汇总 + 同比/环比.
 *
 * <p>Intent Code: {@code CUSTOMER_REVENUE_TREND}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P1)
 */
@Slf4j
@Component
public class CustomerRevenueTrendTool extends AbstractBusinessTool {

    private static final int DEFAULT_PERIOD_MONTHS = 2;
    private static final int MAX_PERIOD_MONTHS = 24;

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Override
    public String getToolName() {
        return "customer_revenue_trend";
    }

    @Override
    public String getDescription() {
        return "聚合客户销售订单, 按月输出销售总额趋势 (默认近 2 个月). "
                + "LLM 触发场景: 用户问 '鲜湘缘本月 vs 上月销售额' / '客户收入趋势' / "
                + "'X 客户最近几个月订单情况' / '客户购买金额变化'. "
                + "可选 customerId 限单客户, 否则跨全 factory 汇总. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "可选 — 仅查单客户 UUID. 留空 = 全 factory 汇总");
        properties.put("customerId", customerId);

        Map<String, Object> periodMonths = new HashMap<>();
        periodMonths.put("type", "integer");
        periodMonths.put("description", "查最近多少个月 (默认 2, 上限 24)");
        periodMonths.put("default", DEFAULT_PERIOD_MONTHS);
        periodMonths.put("minimum", 1);
        periodMonths.put("maximum", MAX_PERIOD_MONTHS);
        properties.put("periodMonths", periodMonths);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        int periodMonths = clamp(getInteger(params, "periodMonths", DEFAULT_PERIOD_MONTHS),
                1, MAX_PERIOD_MONTHS);
        String customerId = getString(params, "customerId", null);

        log.info("customer_revenue_trend — factory={} customer={} months={}",
                factoryId, customerId, periodMonths);

        LocalDate now = LocalDate.now();
        LocalDate startDate = now.minusMonths(periodMonths).withDayOfMonth(1);

        // Aggregate by YearMonth
        Map<YearMonth, BigDecimal> monthlyTotal = new TreeMap<>();
        Map<YearMonth, Integer> monthlyCount = new TreeMap<>();

        // Initialize all months with 0
        YearMonth ym = YearMonth.from(startDate);
        YearMonth ymEnd = YearMonth.from(now);
        while (!ym.isAfter(ymEnd)) {
            monthlyTotal.put(ym, BigDecimal.ZERO);
            monthlyCount.put(ym, 0);
            ym = ym.plusMonths(1);
        }

        // Query orders
        List<SalesOrder> orders;
        if (customerId != null && !customerId.isBlank()) {
            orders = salesOrderRepository.findByFactoryIdAndCustomerId(factoryId, customerId);
        } else {
            orders = salesOrderRepository.findByFactoryIdAndDateRange(factoryId, startDate, now);
        }

        for (SalesOrder so : orders) {
            if (so.getOrderDate() == null) continue;
            if (so.getOrderDate().isBefore(startDate) || so.getOrderDate().isAfter(now)) continue;
            YearMonth k = YearMonth.from(so.getOrderDate());
            BigDecimal amount = so.getTotalAmount() != null ? so.getTotalAmount() : BigDecimal.ZERO;
            monthlyTotal.merge(k, amount, BigDecimal::add);
            monthlyCount.merge(k, 1, Integer::sum);
        }

        List<Map<String, Object>> series = new ArrayList<>(monthlyTotal.size());
        BigDecimal prevTotal = null;
        for (Map.Entry<YearMonth, BigDecimal> e : monthlyTotal.entrySet()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("month", e.getKey().toString());  // e.g. "2026-05"
            p.put("totalAmount", e.getValue());
            p.put("orderCount", monthlyCount.get(e.getKey()));
            if (prevTotal != null && prevTotal.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal change = e.getValue().subtract(prevTotal);
                BigDecimal pctChange = change
                        .multiply(new BigDecimal("100"))
                        .divide(prevTotal, 2, java.math.RoundingMode.HALF_UP);
                p.put("changeFromPrevMonth", change);
                p.put("pctChangeFromPrevMonth", pctChange);
            } else {
                p.put("changeFromPrevMonth", null);
                p.put("pctChangeFromPrevMonth", null);
            }
            series.add(p);
            prevTotal = e.getValue();
        }

        String customerName = customerId != null
                ? resolveCustomerName(factoryId, customerId) : null;

        Map<String, Object> data = new HashMap<>();
        data.put("customerId", customerId);
        data.put("customerName", customerName);
        data.put("periodMonths", periodMonths);
        data.put("series", series);

        String scope = customerId != null
                ? String.format("%s 客户", nameForDisplay(customerName))
                : "全 factory 客户";
        String message = String.format("%s 近 %d 个月销售趋势 (共 %d 个月数据点)",
                scope, periodMonths, series.size());
        return buildSimpleResult(message, data);
    }

    private String resolveCustomerName(String factoryId, String customerId) {
        if (customerId == null) return null;
        try {
            Optional<Customer> opt = customerRepository.findByIdAndFactoryId(customerId, factoryId);
            return opt.map(Customer::getName).orElse(null);
        } catch (Exception ex) {
            log.warn("resolveCustomerName failed customer={} factory={}: {}",
                    customerId, factoryId, ex.getMessage());
            return null;
        }
    }

    private String nameForDisplay(String name) {
        return (name != null && !name.isBlank()) ? name : "该客户";
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
