package com.cretas.aims.service.alerts.restaurant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spec §3.1 卡 C1 — "本次无法判定" 前缀豁免语义.
 *
 * <p>这是 standing-alert 链路上<b>最脆</b>的一环: 诊断的"缺席"默认被
 * {@code sweepFactory} 判成"已恢复" → auto-resolve。若某项检测本次是"查不到"
 * 而非"已恢复", 事件被误清后下一轮成功 sweep 会重建 → flap 重复推送。
 *
 * <p>2026-07-29 由单一布尔 {@code supplierAnomalyUnavailable} 泛化为前缀列表,
 * 因为 plan-alert 的失败隔离粒度是<b>单条规则</b>。
 */
@DisplayName("RestaurantHealthAlertBridgeService — unavailable 前缀豁免")
class RestaurantHealthAlertBridgeServiceUnavailableTest {

    private static Set<String> prefixes(String... values) {
        return new LinkedHashSet<>(java.util.Arrays.asList(values));
    }

    @Test
    @DisplayName("空前缀集 → 一律不豁免 (缺席即恢复, 保持原有 auto-resolve 行为)")
    void emptyPrefixes_neverExempt() {
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                "food_cost_ratio", prefixes())).isFalse();
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                "plan_alert:weekly", null)).isFalse();
    }

    @Test
    @DisplayName("F1 原语义保持: supplier_price_anomaly:* 前缀匹配")
    void supplierPrefix_stillWorks() {
        Set<String> p = prefixes("supplier_price_anomaly:");
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                "supplier_price_anomaly:牛肉:SUP001", p)).isTrue();
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                "food_cost_ratio", p)).isFalse();
    }

    @Test
    @DisplayName("逐规则粒度: 只豁免出问题的那条规则, 其它规则照常 auto-resolve")
    void perRuleIsolation() {
        Set<String> p = prefixes("plan_alert:broken_rule");
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                "plan_alert:broken_rule", p)).isTrue();
        // 另一条健康规则本次没触发 = 真的恢复了, 必须允许被 auto-resolve
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                "plan_alert:healthy_rule", p)).isFalse();
    }

    @Test
    @DisplayName("整族豁免: 规则表读不到时 plan_alert: 覆盖所有规则")
    void familyWideExemption() {
        Set<String> p = prefixes("plan_alert:");
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                "plan_alert:rule_a", p)).isTrue();
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                "plan_alert:rule_b", p)).isTrue();
        // 但不能误伤 DiagnosticsEngine 的指标
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                "discount_rate", p)).isFalse();
    }

    @Test
    @DisplayName("多前缀并存 (F1 + plan-alert 同时不可用)")
    void multiplePrefixes() {
        Set<String> p = prefixes("supplier_price_anomaly:", "plan_alert:rule_a");
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                "supplier_price_anomaly:牛肉:S1", p)).isTrue();
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                "plan_alert:rule_a", p)).isTrue();
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                "plan_alert:rule_b", p)).isFalse();
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                "ingredient_waste_rate", p)).isFalse();
    }

    @Test
    @DisplayName("null / 空白前缀不匹配任何东西 (防空串 startsWith 全命中)")
    void nullAndBlankAreIgnored() {
        Set<String> p = prefixes("", "   ");
        p.add(null);
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                "food_cost_ratio", p)).isFalse();
    }

    @Test
    @DisplayName("null businessEntityId 不 NPE")
    void nullEntityId_isFalse() {
        assertThat(RestaurantHealthAlertBridgeService.isUnavailable(
                null, prefixes("plan_alert:"))).isFalse();
    }
}
