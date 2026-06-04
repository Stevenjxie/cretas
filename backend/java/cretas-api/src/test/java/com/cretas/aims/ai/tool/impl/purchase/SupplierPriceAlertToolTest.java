package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.client.GoldFinanceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SupplierPriceAlertTool} (邓总 供应商价格预警, 复用 #53).
 *
 * <p>Validates: it calls the #53 detector via its 90-day moving-average mode,
 * shapes anomalies into fool-proof alert rows (供应商+食材+涨幅%+建议动作), respects
 * Python-side RBAC strip (null absolute prices → "无权限查看"), handles empty/error,
 * and that the ActionType derives to NOTIFY (read-only — no write-guard confirm).
 */
@ExtendWith(MockitoExtension.class)
class SupplierPriceAlertToolTest {

    private static final String FACTORY_ID = "F006";

    @Mock
    private GoldFinanceClient gold;

    private SupplierPriceAlertTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new SupplierPriceAlertTool();
        Field f = SupplierPriceAlertTool.class.getDeclaredField("gold");
        f.setAccessible(true);
        f.set(tool, gold);
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UT-SPA-01: metadata — toolName / description / no required params")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("supplier_price_alert");
        assertThat(tool.getDescription()).contains("供应商价格预警").contains("90");
        assertThat(tool.getParametersSchema()).containsKey("properties");
        assertThat(tool.getRequiredParameters()).isEmpty();
    }

    @Test
    @DisplayName("UT-SPA-02: actionType is NOTIFY (read-only, not a write op)")
    void actionType_isNotify() {
        // _alert suffix → NOTIFY → not gated by the write-confirm guard.
        assertThat(tool.getActionType().name()).isEqualTo("NOTIFY");
    }

    // -------------------------------------------------------------------------
    // doExecute — reuse #53 detector via 90-day mode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UT-SPA-03: calls #53 detector with baseline_mode=days window=90")
    void doExecute_callsDetectorWith90DayMode() throws Exception {
        when(gold.fetchPriceAnomalies(eq(FACTORY_ID), eq("days"), eq(90), anyDouble()))
                .thenReturn(List.of(anomaly("洗洁精", "鑫农", "UP", 32.4, 150.0, 113.33, "MEDIUM", 1)));

        Map<String, Object> result = tool.doExecute(FACTORY_ID, new HashMap<>(), new HashMap<>());

        assertThat(result).containsEntry("dataAvailable", true);
        assertThat(result).containsEntry("windowDays", 90);
        assertThat(result).containsEntry("alertCount", 1);
    }

    @Test
    @DisplayName("UT-SPA-04: alert row carries 供应商+食材+涨幅%+建议动作 (防呆)")
    void doExecute_alertRowFoolProof() throws Exception {
        when(gold.fetchPriceAnomalies(eq(FACTORY_ID), eq("days"), eq(90), anyDouble()))
                .thenReturn(List.of(anomaly("洗洁精", "鑫农", "UP", 32.4, 150.0, 113.33, "MEDIUM", 1)));

        Map<String, Object> result = tool.doExecute(FACTORY_ID, new HashMap<>(), new HashMap<>());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alerts = (List<Map<String, Object>>) result.get("alerts");
        assertThat(alerts).hasSize(1);
        Map<String, Object> row = alerts.get(0);
        assertThat(row).containsEntry("食材", "洗洁精");
        assertThat(row).containsEntry("供应商", "鑫农");
        assertThat(row).containsEntry("方向", "涨价");
        assertThat(row.get("偏离率")).isEqualTo(32.4);
        assertThat(row).containsKey("建议动作");
        assertThat(row.get("建议动作").toString()).contains("解释");
        // absolute prices visible (mock simulates a price-view role response)
        assertThat(row).containsEntry("最新单价", 150.0);
        assertThat(row).containsEntry("90天均价", 113.33);
        // message carries supplier + ingredient + pct + suggestion
        String msg = result.get("message").toString();
        assertThat(msg).contains("鑫农").contains("洗洁精").contains("32.4%");
    }

    @Test
    @DisplayName("UT-SPA-05: HIGH risk → 高风险 escalation + 比价/换供应商 suggestion")
    void doExecute_highRiskEscalation() throws Exception {
        when(gold.fetchPriceAnomalies(eq(FACTORY_ID), eq("days"), eq(90), anyDouble()))
                .thenReturn(List.of(anomaly("洗洁精", "鑫农", "UP", 36.4, 150.0, 110.0, "HIGH", 3)));

        Map<String, Object> result = tool.doExecute(FACTORY_ID, new HashMap<>(), new HashMap<>());

        assertThat(result).containsEntry("highRiskCount", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alerts = (List<Map<String, Object>>) result.get("alerts");
        assertThat(alerts.get(0).get("风险等级").toString()).contains("高风险");
        assertThat(alerts.get(0).get("建议动作").toString()).contains("比价");
        assertThat(result.get("message").toString()).contains("【高风险】");
    }

    @Test
    @DisplayName("UT-SPA-06: RBAC-stripped prices (null) → 无权限查看, but deltaPct/risk visible")
    void doExecute_rbacStrippedPrices() throws Exception {
        // Python nulled oldPrice/newPrice/trailingAvg for a non-price-view role,
        // but kept deltaPct (rate) + direction + riskLevel (deterrence signal).
        Map<String, Object> stripped = anomaly("洗洁精", "鑫农", "UP", 32.4, null, null, "MEDIUM", 1);
        when(gold.fetchPriceAnomalies(eq(FACTORY_ID), eq("days"), eq(90), anyDouble()))
                .thenReturn(List.of(stripped));

        Map<String, Object> result = tool.doExecute(FACTORY_ID, new HashMap<>(), new HashMap<>());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alerts = (List<Map<String, Object>>) result.get("alerts");
        Map<String, Object> row = alerts.get(0);
        assertThat(row).doesNotContainKey("最新单价");
        assertThat(row.get("价格").toString()).contains("无权限查看");
        // deterrence signal still visible
        assertThat(row.get("偏离率")).isEqualTo(32.4);
        assertThat(row).containsKey("风险等级");
    }

    @Test
    @DisplayName("UT-SPA-07: empty result → fool-proof message + next-action hint, no dead-end")
    void doExecute_emptyFoolProof() throws Exception {
        when(gold.fetchPriceAnomalies(eq(FACTORY_ID), eq("days"), eq(90), anyDouble()))
                .thenReturn(List.of());

        Map<String, Object> result = tool.doExecute(FACTORY_ID, new HashMap<>(), new HashMap<>());

        assertThat(result).containsEntry("dataAvailable", false);
        assertThat(result).containsEntry("alertCount", 0);
        assertThat(result).containsKey("actionHint");
        assertThat(result.get("message").toString()).contains("90");
    }

    @Test
    @DisplayName("UT-SPA-08: gold transport failure → graceful 暂时不可用, not an exception")
    void doExecute_goldFailureGraceful() throws Exception {
        when(gold.fetchPriceAnomalies(eq(FACTORY_ID), eq("days"), eq(90), anyDouble()))
                .thenThrow(new IOException("detect HTTP 500"));

        Map<String, Object> result = tool.doExecute(FACTORY_ID, new HashMap<>(), new HashMap<>());

        assertThat(result).containsEntry("dataAvailable", false);
        assertThat(result.get("message").toString()).contains("暂时不可用");
    }

    @Test
    @DisplayName("UT-SPA-09: limit trims the alert list (detector already sorted)")
    void doExecute_limitTrims() throws Exception {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            many.add(anomaly("食材" + i, "供应商" + i, "UP", 10.0 + i, 100.0, 90.0, "MEDIUM", 1));
        }
        when(gold.fetchPriceAnomalies(eq(FACTORY_ID), eq("days"), eq(90), anyDouble()))
                .thenReturn(many);

        Map<String, Object> params = new HashMap<>();
        params.put("limit", 3);
        Map<String, Object> result = tool.doExecute(FACTORY_ID, params, new HashMap<>());

        assertThat(result).containsEntry("alertCount", 3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alerts = (List<Map<String, Object>>) result.get("alerts");
        assertThat(alerts).hasSize(3);
    }

    @Test
    @DisplayName("UT-SPA-10: DOWN direction → 降价 + 规格缩水/临期 suggestion")
    void doExecute_downDirection() throws Exception {
        when(gold.fetchPriceAnomalies(eq(FACTORY_ID), eq("days"), eq(90), anyDouble()))
                .thenReturn(List.of(anomaly("青菜", "王记", "DOWN", -20.0, 2.0, 2.5, "MEDIUM", 1)));

        Map<String, Object> result = tool.doExecute(FACTORY_ID, new HashMap<>(), new HashMap<>());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alerts = (List<Map<String, Object>>) result.get("alerts");
        Map<String, Object> row = alerts.get(0);
        assertThat(row).containsEntry("方向", "降价");
        assertThat(row.get("建议动作").toString()).contains("规格");
        assertThat(result.get("message").toString()).contains("降20.0%");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build a detector anomaly row mirroring the Python detect output (camelCase). */
    private static Map<String, Object> anomaly(
            String ingredient, String supplier, String direction, double deltaPct,
            Object newPrice, Object trailingAvg, String risk, int consecutive) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("normalizedName", ingredient);
        m.put("ingredientName", ingredient);
        m.put("supplierId", "sup-" + supplier);
        m.put("supplierName", supplier);
        m.put("unit", "瓶");
        m.put("anomalyDeliveryDate", "2026-06-03");
        m.put("oldPrice", trailingAvg);
        m.put("newPrice", newPrice);
        m.put("trailingAvg", trailingAvg);
        m.put("deltaPct", deltaPct);
        m.put("direction", direction);
        m.put("consecutiveAnomalyCount", consecutive);
        m.put("riskLevel", risk);
        return m;
    }
}
