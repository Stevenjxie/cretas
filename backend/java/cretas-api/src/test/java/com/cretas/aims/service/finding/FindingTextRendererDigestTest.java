package com.cretas.aims.service.finding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link FindingTextRenderer#renderDigestLines} —— 驾驶舱卡片出口。 */
class FindingTextRendererDigestTest {

    private final FindingTextRenderer renderer = new FindingTextRenderer();

    private static Finding typeConcentration() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("cost", 267672.34);
        facts.put("share", 37.0);
        facts.put("windowDays", 7);
        facts.put("totalCost", 722721.34);
        return new Finding("WASTAGE_TYPE_CONCENTRATION", "restaurant",
                Finding.Severity.INFO, 70, "变质", "变质", facts);
    }

    @Test
    @DisplayName("UT-DIG-01: 🔴 金额来自服务端渲染 —— 前端不再自己拼, 也就不会被 Map key 脱敏打空")
    void moneyComesFromServerRendering() {
        FindingService.Result r = new FindingService.Result(
                List.of(typeConcentration()), List.of("损耗类型集中度"), 1,
                Map.of("WASTAGE_TYPE_CONCENTRATION", 1), List.of(), List.of());

        List<String> lines = renderer.renderDigestLines(r);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("267672.34"), lines.get(0));
        assertTrue(lines.get(0).contains("37.0"), lines.get(0));
        assertFalse(lines.get(0).contains("顺带"),
                "驾驶舱卡片不是对话里的「顺带」, 不该带那个语气: " + lines.get(0));
    }

    @Test
    @DisplayName("UT-DIG-02: 三态各一行，且各说各话")
    void threeStatesEachGetTheirOwnLine() {
        FindingService.Result r = new FindingService.Result(
                List.of(typeConcentration()), List.of("损耗类型集中度"), 1,
                Map.of("WASTAGE_TYPE_CONCENTRATION", 1),
                List.of("某失败规则"),
                List.of(new FindingService.SkippedRule("食材损耗离群", "基线历史不足")));

        List<String> lines = renderer.renderDigestLines(r);

        assertTrue(lines.stream().anyMatch(l -> l.contains("变质")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("暂不判断")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("检查失败")), lines.toString());
    }

    @Test
    @DisplayName("UT-DIG-03: 无发现但规则跑完 → 说「均正常」，不是空列表")
    void allClearSaysSo() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of("损耗类型集中度"), 0, Map.of(), List.of(), List.of());

        List<String> lines = renderer.renderDigestLines(r);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("均正常"), lines.get(0));
    }

    @Test
    @DisplayName("UT-DIG-04: 🔴 一条规则都没跑 → 空列表，绝不说「均正常」")
    void nothingRanSaysNothing() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of(), 0, Map.of(), List.of(), List.of());

        assertTrue(renderer.renderDigestLines(r).isEmpty());
    }
}
