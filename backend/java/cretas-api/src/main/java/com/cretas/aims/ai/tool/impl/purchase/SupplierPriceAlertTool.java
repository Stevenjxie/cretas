package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.client.GoldFinanceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商价格预警 Tool — 邓总点名痛点 (老板想知道"哪个供应商涨价了").
 *
 * <p>复用已上线的 Wave2 #53 价格异常威慑引擎 (detect_price_anomalies)。#53 的检测内核
 * 用 count 模式 (latest vs trailing-N 次均价); 邓总锁定的基准是<b>自身 90 天移动均价</b>,
 * 所以本 Tool 调用 #53 的 {@code baseline_mode=days, window_days=90} 模式 —
 * <b>不新建 detector</b>, 复用同一 {@code agg_supplier_price} 历史 + ack/累计高风险 infra。
 *
 * <p>本 Tool 的价值: #53 只暴露在 web-admin「价格异常」看板, 没有 AI 问答入口。本 Tool 让
 * 老板/采购在「智能问答」直接问"哪个供应商涨价了 / 供应商涨价预警 / 哪些食材涨价超标",
 * 走 gold detect 端点拿到按风险排序的预警列表。
 *
 * <p><b>RBAC (价格敏感)</b>: 偏离率 deltaPct (率) + 涨跌方向 direction + 风险等级 riskLevel
 * (威慑信号) 始终可见; 绝对价格 (oldPrice/newPrice/trailingAvg) 由 Python 端按角色 strip —
 * 仅 {@code procurement:price:view} 类角色 (PRICE_VIEW_ROLES) 可见, 其余角色为 null。
 * {@link GoldFinanceClient} 转发 X-User-Role 让 Python RBAC 生效。
 *
 * <p><b>防呆 (fool-proof)</b>: 每条预警带 供应商 + 食材 + 涨幅% + 建议动作
 * ("要求供应商解释 / 比价换供应商"), 而非只报数字。
 *
 * <p>ActionType: {@code _alert} 后缀 → NOTIFY (只读预警, 非写操作, 不触发写护栏确认)。
 *
 * <p>Intent Code: {@code SUPPLIER_PRICE_ALERT}
 *
 * @since 2026-06-04 (邓总 供应商价格预警, 复用 #53)
 */
@Slf4j
@Component
public class SupplierPriceAlertTool extends AbstractBusinessTool {

    /** 邓总锁定的移动均价窗口 (天)。 */
    private static final int WINDOW_DAYS = 90;
    /** 默认异常容限 ε (百分比) — 与 #53 detector 默认一致。 */
    private static final double DEFAULT_EPSILON_PCT = 5.0;
    /** 默认最多返回多少条预警 (按风险/涨幅已在 detector 排好序)。 */
    private static final int DEFAULT_LIMIT = 10;

    @Autowired
    private GoldFinanceClient gold;

    @Override
    public String getToolName() {
        return "supplier_price_alert";
    }

    @Override
    public String getDescription() {
        return "供应商价格预警 (邓总痛点): 基于每个食材<b>自身 90 天移动均价</b>, 找出最近一次"
                + "采购单价异常偏离的供应商×食材, 按风险等级排序。LLM 触发场景: "
                + "'哪个供应商涨价了' / '供应商涨价预警' / '哪些食材涨价超标' / '谁的价格不对劲' / "
                + "'最近进价异常'。只读预警 (read-only)。复用价格异常威慑引擎 (#53), "
                + "受价格敏感权限影响: 非 price-view 角色看不到绝对价格, 但能看到涨幅% 和风险等级。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> epsilonPct = new HashMap<>();
        epsilonPct.put("type", "number");
        epsilonPct.put("description", "异常容限 ε (百分比), 偏离超过此值才预警, 默认 5");
        epsilonPct.put("default", DEFAULT_EPSILON_PCT);
        epsilonPct.put("minimum", 0);
        epsilonPct.put("maximum", 100);
        properties.put("epsilonPct", epsilonPct);

        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "最多返回预警条数 (默认 10, 按风险/涨幅排序)");
        limit.put("default", DEFAULT_LIMIT);
        limit.put("minimum", 1);
        limit.put("maximum", 50);
        properties.put("limit", limit);

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

        double epsilonPct = parseEpsilon(params);
        int limit = clamp(getInteger(params, "limit", DEFAULT_LIMIT), 1, 50);

        log.info("supplier_price_alert — factory={} window={}d eps={}% limit={}",
                factoryId, WINDOW_DAYS, epsilonPct, limit);

        List<Map<String, Object>> anomalies;
        try {
            // Reuse #53 detector via its 90-day moving-average mode.
            anomalies = gold.fetchPriceAnomalies(factoryId, "days", WINDOW_DAYS, epsilonPct);
        } catch (Exception ex) {
            log.warn("supplier_price_alert — gold detect failed factory={}: {}",
                    factoryId, ex.getMessage());
            Map<String, Object> err = new HashMap<>();
            err.put("dataAvailable", false);
            err.put("message", "价格预警服务暂时不可用，请稍后重试。");
            return err;
        }

        if (anomalies == null || anomalies.isEmpty()) {
            // Fool-proof Rule 5: no dead-end — explain WHY + next action.
            Map<String, Object> empty = new HashMap<>();
            empty.put("dataAvailable", false);
            empty.put("alertCount", 0);
            empty.put("windowDays", WINDOW_DAYS);
            empty.put("epsilonPct", epsilonPct);
            empty.put("message", String.format(
                    "近 %d 天内未发现供应商进价异常 (偏离 90 天均价超 %.1f%%)。"
                            + "如刚导入新送货单，价格预警会在下次采购确认后更新。",
                    WINDOW_DAYS, epsilonPct));
            empty.put("actionHint", "前往「采购 - 价格异常」看板查看完整进价历史与已确认记录");
            return empty;
        }

        // Trim to the requested limit (detector already sorted: HIGH first, then |delta|).
        List<Map<String, Object>> trimmed = anomalies.size() > limit
                ? new ArrayList<>(anomalies.subList(0, limit))
                : anomalies;

        int highCount = 0;
        List<Map<String, Object>> alerts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("发现 %d 项供应商进价异常 (基准: 各食材近 %d 天移动均价)：\n",
                trimmed.size(), WINDOW_DAYS));

        for (int i = 0; i < trimmed.size(); i++) {
            Map<String, Object> a = trimmed.get(i);
            String ingredient = str(a.get("ingredientName"), str(a.get("normalizedName"), "?"));
            String supplier = str(a.get("supplierName"), "未记录供应商");
            String direction = str(a.get("direction"), "UP");
            String risk = str(a.get("riskLevel"), "MEDIUM");
            Double deltaPct = num(a.get("deltaPct"));
            Object newPrice = a.get("newPrice");   // null when RBAC-stripped
            Object trailingAvg = a.get("trailingAvg");
            Object unit = a.get("unit");
            boolean high = "HIGH".equals(risk);
            if (high) highCount++;

            String dirCn = "DOWN".equals(direction) ? "降" : "涨";
            String absPct = deltaPct != null ? String.format("%.1f%%", Math.abs(deltaPct)) : "?";
            String suggestion = buildSuggestion(direction, high);

            // Build a fool-proof alert row (供应商 + 食材 + 涨幅% + 建议动作).
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("食材", ingredient);
            row.put("供应商", supplier);
            row.put("方向", "UP".equals(direction) ? "涨价" : "降价");
            row.put("偏离率", deltaPct);
            row.put("风险等级", high ? "高风险(连续异常)" : "关注");
            if (newPrice != null) {
                row.put("最新单价", newPrice);
                row.put("90天均价", trailingAvg);
                if (unit != null) row.put("单位", unit);
            } else {
                row.put("价格", "无权限查看 (需采购价格权限)");
            }
            row.put("建议动作", suggestion);
            row.put("anomalyDeliveryDate", a.get("anomalyDeliveryDate"));
            row.put("consecutiveAnomalyCount", a.get("consecutiveAnomalyCount"));
            alerts.add(row);

            sb.append(i + 1).append(". ")
                    .append(high ? "【高风险】" : "")
                    .append(supplier).append(" 的 ").append(ingredient)
                    .append(dirCn).append(absPct);
            if (newPrice != null) {
                sb.append(" (现价 ").append(newPrice);
                if (unit != null) sb.append("/").append(unit);
                sb.append("，90天均价 ").append(trailingAvg).append(")");
            }
            sb.append(" — ").append(suggestion);
            if (i < trimmed.size() - 1) sb.append("\n");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dataAvailable", true);
        data.put("windowDays", WINDOW_DAYS);
        data.put("epsilonPct", epsilonPct);
        data.put("alertCount", trimmed.size());
        data.put("highRiskCount", highCount);
        data.put("alerts", alerts);
        data.put("message", sb.toString());
        return data;
    }

    /** 防呆: 按方向 + 风险给具体下一步动作 (邓总: 威慑非处罚)。 */
    private static String buildSuggestion(String direction, boolean high) {
        if ("DOWN".equals(direction)) {
            return "进价下降，确认是否规格缩水或临期货，必要时核实质量";
        }
        if (high) {
            return "连续涨价已达高风险，要求供应商书面解释并启动比价/换供应商";
        }
        return "要求供应商说明涨价原因（季节性/市场涨价/规格变化），录入解释留痕";
    }

    private double parseEpsilon(Map<String, Object> params) {
        Object v = params.get("epsilonPct");
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d < 0) return 0.0;
            if (d > 100) return 100.0;
            return d;
        }
        if (v != null) {
            try {
                double d = Double.parseDouble(v.toString());
                if (d < 0) return 0.0;
                if (d > 100) return 100.0;
                return d;
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_EPSILON_PCT;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String str(Object o, String dflt) {
        if (o == null) return dflt;
        String s = o.toString();
        return s.isEmpty() ? dflt : s;
    }

    private static Double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : null;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
