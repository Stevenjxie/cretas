package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * 价格历史查询 Tool — Sprint 8 P4b 采购员 Workdesk.
 *
 * <p>采购员小赵关心: "X 物料历史采购价多少, 这次报价合理吗?" — 输出最近 N 个月每月
 * 平均单价 + 同比趋势, 让采购员对比当前供应商报价是否在合理区间.
 *
 * <p>注: 受 {@code @PriceSensitive} 影响, 仓管员 (warehouse_manager) 角色看到的
 * unitPrice 会被 strip 为 null, 本 Tool 会显示"无权限查看". 采购员 (procurement) /
 * 财务 (finance) 角色不受影响.
 *
 * <p>Intent Code: {@code PRICE_HISTORY_QUERY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4b)
 */
@Slf4j
@Component
public class PriceHistoryQueryTool extends AbstractBusinessTool {

    private static final int DEFAULT_MONTHS_BACK = 3;
    private static final int MAX_MONTHS_BACK = 24;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Override
    public String getToolName() {
        return "price_history_query";
    }

    @Override
    public String getDescription() {
        return "价格历史查询 — 按 materialTypeId 输出最近 N 个月每月加权平均采购价 + 同比趋势. "
                + "LLM 触发场景: 采购员问 'X 物料历史价格' / '这次报价 Y 元/kg 贵吗?' / "
                + "'X 物料涨价了多少' / '价格曲线' / '采购价对比'. read-only. "
                + "默认 monthsBack=3 (上限 24). 受 PriceSensitive 影响, "
                + "仓管员角色看不到 unitPrice (返 null + 提示).";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> materialTypeId = new HashMap<>();
        materialTypeId.put("type", "string");
        materialTypeId.put("description", "材料类型 ID (必填)");
        properties.put("materialTypeId", materialTypeId);

        Map<String, Object> monthsBack = new HashMap<>();
        monthsBack.put("type", "integer");
        monthsBack.put("description", "回看月数 (1-24, 默认 3)");
        monthsBack.put("default", DEFAULT_MONTHS_BACK);
        monthsBack.put("minimum", 1);
        monthsBack.put("maximum", MAX_MONTHS_BACK);
        properties.put("monthsBack", monthsBack);

        schema.put("properties", properties);
        schema.put("required", List.of("materialTypeId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("materialTypeId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String materialTypeId = getString(params, "materialTypeId");
        int monthsBack = clamp(getInteger(params, "monthsBack", DEFAULT_MONTHS_BACK),
                1, MAX_MONTHS_BACK);

        LocalDate today = LocalDate.now();
        YearMonth currentMonth = YearMonth.from(today);
        YearMonth fromMonth = currentMonth.minusMonths(monthsBack - 1L);
        LocalDate windowStart = fromMonth.atDay(1);
        LocalDate windowEnd = today;

        log.info("price_history_query — factory={} material={} monthsBack={}",
                factoryId, materialTypeId, monthsBack);

        // 拉该物料所有历史 items
        List<PurchaseOrderItem> allItems = purchaseOrderItemRepository
                .findByMaterialTypeId(materialTypeId);
        if (allItems.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("materialTypeId", materialTypeId);
            empty.put("monthsBack", monthsBack);
            empty.put("monthlyPrices", Collections.emptyList());
            return buildSimpleResult(
                    String.format("物料 %s 无任何历史采购记录, 无法对比价格", materialTypeId),
                    empty);
        }

        // 按 PO 维度过滤工厂 + 时间窗口
        String materialName = null;
        String unit = null;
        Map<YearMonth, MonthAgg> aggByMonth = new TreeMap<>();
        int totalItemsInWindow = 0;
        int itemsWithoutPrice = 0;

        for (PurchaseOrderItem item : allItems) {
            Optional<PurchaseOrder> poOpt = purchaseOrderRepository.findById(item.getPurchaseOrderId());
            if (poOpt.isEmpty()) continue;
            PurchaseOrder po = poOpt.get();
            if (!factoryId.equals(po.getFactoryId())) continue;
            if (po.getOrderDate() == null) continue;
            if (po.getOrderDate().isBefore(windowStart) || po.getOrderDate().isAfter(windowEnd)) continue;

            totalItemsInWindow++;
            if (materialName == null) materialName = item.getMaterialName();
            if (unit == null) unit = item.getUnit();

            BigDecimal price = item.getUnitPrice();
            BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            if (price == null) {
                itemsWithoutPrice++;
                continue;
            }
            YearMonth ym = YearMonth.from(po.getOrderDate());
            aggByMonth.computeIfAbsent(ym, k -> new MonthAgg()).add(price, qty);
        }

        // 构建月数据 + 同比 (MoM)
        List<Map<String, Object>> monthlyPrices = new ArrayList<>();
        BigDecimal prevAvg = null;
        for (YearMonth ym : aggByMonth.keySet()) {
            MonthAgg agg = aggByMonth.get(ym);
            BigDecimal wAvg = agg.weightedAvg();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("yearMonth", ym.toString());
            row.put("orderCount", agg.count);
            row.put("totalQty", agg.totalQty);
            row.put("weightedAvgPrice", wAvg);
            row.put("minPrice", agg.minPrice);
            row.put("maxPrice", agg.maxPrice);
            if (prevAvg != null && prevAvg.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal changePct = wAvg.subtract(prevAvg)
                        .multiply(new BigDecimal("100"))
                        .divide(prevAvg, 2, RoundingMode.HALF_UP);
                row.put("momChangePct", changePct);
                String trendIcon = changePct.compareTo(BigDecimal.ZERO) > 0 ? "📈"
                        : changePct.compareTo(BigDecimal.ZERO) < 0 ? "📉" : "➖";
                row.put("trend", trendIcon);
            } else {
                row.put("momChangePct", null);
                row.put("trend", "➖");
            }
            prevAvg = wAvg;
            monthlyPrices.add(row);
        }

        // 整体均价 + 整体趋势
        BigDecimal overallAvg = null;
        if (!monthlyPrices.isEmpty()) {
            BigDecimal sumW = BigDecimal.ZERO;
            BigDecimal sumQ = BigDecimal.ZERO;
            for (MonthAgg agg : aggByMonth.values()) {
                sumW = sumW.add(agg.totalAmount);
                sumQ = sumQ.add(agg.totalQty);
            }
            if (sumQ.compareTo(BigDecimal.ZERO) > 0) {
                overallAvg = sumW.divide(sumQ, 4, RoundingMode.HALF_UP);
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("materialTypeId", materialTypeId);
        data.put("materialName", materialName);
        data.put("unit", unit);
        data.put("monthsBack", monthsBack);
        data.put("windowStart", windowStart.toString());
        data.put("windowEnd", windowEnd.toString());
        data.put("totalItemsInWindow", totalItemsInWindow);
        data.put("itemsWithoutPriceVisibility", itemsWithoutPrice);
        data.put("overallWeightedAvgPrice", overallAvg);
        data.put("monthlyPrices", monthlyPrices);

        String message;
        if (monthlyPrices.isEmpty()) {
            if (itemsWithoutPrice > 0) {
                message = String.format(
                        "⚠️ 物料 %s 最近 %d 个月有 %d 条采购记录, 但当前角色无权限查看单价 (PriceSensitive)",
                        materialName != null ? materialName : materialTypeId,
                        monthsBack, itemsWithoutPrice);
            } else {
                message = String.format("物料 %s 最近 %d 个月无采购记录",
                        materialName != null ? materialName : materialTypeId, monthsBack);
            }
        } else {
            message = String.format(
                    "%s 最近 %d 个月价格历史: %d 月数据, 加权均价 %s%s%s",
                    materialName != null ? materialName : materialTypeId,
                    monthsBack, monthlyPrices.size(),
                    overallAvg != null ? overallAvg.toPlainString() : "?",
                    unit != null ? "/" + unit : "",
                    itemsWithoutPrice > 0
                            ? String.format(" (%d 行无价格 — PriceSensitive)", itemsWithoutPrice)
                            : "");
        }
        return buildSimpleResult(message, data);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 月度聚合: 加权 AVG, min, max. */
    private static class MonthAgg {
        BigDecimal totalAmount = BigDecimal.ZERO; // sum(price × qty)
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal minPrice = null;
        BigDecimal maxPrice = null;
        int count = 0;

        void add(BigDecimal price, BigDecimal qty) {
            count++;
            BigDecimal q = qty != null ? qty : BigDecimal.ZERO;
            totalAmount = totalAmount.add(price.multiply(q));
            totalQty = totalQty.add(q);
            if (minPrice == null || price.compareTo(minPrice) < 0) minPrice = price;
            if (maxPrice == null || price.compareTo(maxPrice) > 0) maxPrice = price;
        }

        BigDecimal weightedAvg() {
            if (totalQty.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
            return totalAmount.divide(totalQty, 4, RoundingMode.HALF_UP);
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
