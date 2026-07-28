package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 7 天销售预测 Tool — Sprint 8 P4b 采购员 Workdesk.
 *
 * <p>采购员小赵关心: "下周大概要消耗多少 X 物料?" — 用最近 14 天 SalesOrderItem 历史数据
 * 做最简单的线性预测 (AVG daily × 7 × 1.1 buffer), 不上 ML (Sprint 9 升级 SmartBI LightGBM).
 *
 * <p>预测公式: predicted_7day_demand = (last_14d_total / 14) × 7 × 1.1
 * <ul>
 *   <li>取最近 14 天 SO (status 任意 — 已下单即算需求)</li>
 *   <li>按 productTypeId 聚合 quantity</li>
 *   <li>除以 14 拿 daily AVG</li>
 *   <li>× 7 拿 7-day 预测</li>
 *   <li>× 1.1 = 10% safety buffer (应对周末峰值)</li>
 * </ul>
 *
 * <p>注: 本 Tool 输出是按 <b>productTypeId</b> (产品/菜品 ID), 不是 materialTypeId.
 * 采购员需结合 BOM 反推具体 material 需求. P4b 简化版 — 完整 BOM 反推 Sprint 9 接入.
 *
 * <p>Intent Code: {@code SALES_FORECAST_7DAY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4b)
 */
@Slf4j
@Component
public class SalesForecast7DayTool extends AbstractBusinessTool {

    /** 取最近多少天历史做线性预测. */
    private static final int LOOKBACK_DAYS = 14;
    /** 预测窗口 (7 天). */
    private static final int FORECAST_DAYS = 7;
    /** 安全 buffer 系数 (1.1 = 10% safety stock). */
    private static final BigDecimal BUFFER_MULTIPLIER = new BigDecimal("1.1");
    /** Top N 排序数 (避免长尾噪音). */
    private static final int TOP_N = 20;

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private SalesOrderItemRepository salesOrderItemRepository;

    @Override
    public String getToolName() {
        return "sales_forecast_7day";
    }

    @Override
    public String getDescription() {
        return "7 天销售预测 — 基于最近 14 天 SO 历史, AVG daily × 7 × 1.1 buffer 简单线性预测. "
                + "LLM 触发场景: 采购员问 '下周大概卖多少' / '7 天销售预测' / '下周销量' / "
                + "'下周哪些产品好卖' / '未来一周需求'. read-only. 可选按 productTypeId 过滤. "
                + "防呆 R1: 告诉采购员每个产品预测销量, 不让他自己算 AVG.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> productTypeId = new HashMap<>();
        productTypeId.put("type", "string");
        productTypeId.put("description",
                "可选 — 指定 productTypeId 仅返该产品预测. 不填则返 Top 20 产品.");
        properties.put("productTypeId", productTypeId);

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
        String filterProductTypeId = getString(params, "productTypeId");

        LocalDate today = LocalDate.now();
        LocalDate windowStart = today.minusDays(LOOKBACK_DAYS);
        LocalDate windowEnd = today.minusDays(1); // 不含今天 (今天数据未稳定)

        log.info("sales_forecast_7day — factory={} window={}->{} productTypeId={}",
                factoryId, windowStart, windowEnd, filterProductTypeId);

        // 1. 拉最近 14 天 SO
        List<SalesOrder> sosInWindow = salesOrderRepository.findByFactoryIdAndDateRange(
                factoryId, windowStart, windowEnd);

        if (sosInWindow.isEmpty()) {
            Map<String, Object> emptyData = new HashMap<>();
            emptyData.put("lookbackDays", LOOKBACK_DAYS);
            emptyData.put("forecastDays", FORECAST_DAYS);
            emptyData.put("bufferMultiplier", BUFFER_MULTIPLIER);
            emptyData.put("windowStart", windowStart.toString());
            emptyData.put("windowEnd", windowEnd.toString());
            emptyData.put("forecasts", Collections.emptyList());
            return buildSimpleResult(
                    String.format("最近 %d 天无销售订单, 无法预测. 请确认 SO 录入正常.",
                            LOOKBACK_DAYS),
                    emptyData);
        }

        // 2. 拉所有 SO 的 items
        Set<String> soIds = sosInWindow.stream().map(SalesOrder::getId).collect(Collectors.toSet());
        Map<String, BigDecimal> totalByProduct = new HashMap<>();
        Map<String, String> nameByProduct = new HashMap<>();
        Map<String, String> unitByProduct = new HashMap<>();

        for (String soId : soIds) {
            List<SalesOrderItem> items = salesOrderItemRepository.findBySalesOrderId(soId);
            for (SalesOrderItem item : items) {
                String pid = item.getProductTypeId();
                if (pid == null) continue;
                if (filterProductTypeId != null && !filterProductTypeId.isBlank()
                        && !filterProductTypeId.equals(pid)) {
                    continue;
                }
                BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
                totalByProduct.merge(pid, qty, BigDecimal::add);
                nameByProduct.putIfAbsent(pid, item.getProductName());
                unitByProduct.putIfAbsent(pid, item.getUnit());
            }
        }

        if (totalByProduct.isEmpty()) {
            Map<String, Object> emptyData = new HashMap<>();
            emptyData.put("lookbackDays", LOOKBACK_DAYS);
            emptyData.put("forecastDays", FORECAST_DAYS);
            emptyData.put("forecasts", Collections.emptyList());
            return buildSimpleResult(
                    filterProductTypeId != null
                            ? String.format("产品 %s 最近 %d 天无销售记录", filterProductTypeId, LOOKBACK_DAYS)
                            : "最近 14 天 SO 无 line items, 无法预测",
                    emptyData);
        }

        // 3. 计算预测: AVG daily × 7 × 1.1 buffer
        List<Map<String, Object>> forecasts = new ArrayList<>();
        BigDecimal divisor = new BigDecimal(LOOKBACK_DAYS);
        BigDecimal forecastFactor = new BigDecimal(FORECAST_DAYS).multiply(BUFFER_MULTIPLIER);

        for (Map.Entry<String, BigDecimal> e : totalByProduct.entrySet()) {
            String pid = e.getKey();
            BigDecimal total = e.getValue();
            BigDecimal dailyAvg = total.divide(divisor, 4, RoundingMode.HALF_UP);
            BigDecimal predicted7Day = dailyAvg.multiply(forecastFactor)
                    .setScale(2, RoundingMode.HALF_UP);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productTypeId", pid);
            row.put("productName", nameByProduct.get(pid));
            row.put("unit", unitByProduct.get(pid));
            row.put("last14dTotal", total);
            row.put("dailyAvg", dailyAvg);
            row.put("predicted7DayDemand", predicted7Day);
            row.put("bufferApplied", BUFFER_MULTIPLIER);
            row.put("displayHint", String.format(
                    "%s — 过去 14 天卖 %s, 日均 %s, 预测下周 7 天 %s%s (含 10%% buffer)",
                    nameByProduct.get(pid),
                    stripZeros(total),
                    stripZeros(dailyAvg),
                    stripZeros(predicted7Day),
                    nullToEmpty(unitByProduct.get(pid))));
            forecasts.add(row);
        }

        // 排 predicted desc, Top N
        forecasts.sort((a, b) -> {
            BigDecimal ba = (BigDecimal) b.get("predicted7DayDemand");
            BigDecimal ab = (BigDecimal) a.get("predicted7DayDemand");
            return ba.compareTo(ab);
        });
        if (filterProductTypeId == null || filterProductTypeId.isBlank()) {
            if (forecasts.size() > TOP_N) {
                forecasts = forecasts.subList(0, TOP_N);
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("lookbackDays", LOOKBACK_DAYS);
        data.put("forecastDays", FORECAST_DAYS);
        data.put("bufferMultiplier", BUFFER_MULTIPLIER);
        data.put("windowStart", windowStart.toString());
        data.put("windowEnd", windowEnd.toString());
        data.put("totalSOs", sosInWindow.size());
        data.put("totalProducts", totalByProduct.size());
        data.put("forecasts", forecasts);

        String message = String.format(
                "📈 基于 %d 天历史 (%d 个 SO), 预测下周 7 天 Top %d 产品需求, 已附日均 + 10%% buffer",
                LOOKBACK_DAYS, sosInWindow.size(), forecasts.size());
        return buildSimpleResult(message, data);
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static String stripZeros(BigDecimal v) {
        if (v == null) return "0";
        try {
            return v.stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return v.toPlainString();
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
