package com.cretas.aims.service.finding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link FindingTextRenderer}. */
class FindingTextRendererTest {

    private final FindingTextRenderer renderer = new FindingTextRenderer();

    private static Finding lowStock(String name, String current, String safety, String gap) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("currentStock", new BigDecimal(current));
        facts.put("safetyStock", new BigDecimal(safety));
        facts.put("gap", new BigDecimal(gap));
        facts.put("unit", "kg");
        facts.put("stockRatio", 24L);
        return new Finding("LOW_STOCK", "inventory", Finding.Severity.WARNING, 50,
                "M-" + name, name, facts);
    }

    @Test
    @DisplayName("UT-FTR-01: 无发现时输出「已检查 X，均正常」，且只列实际跑过的规则")
    void allClearListsOnlyCheckedRules() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of("低库存"), 0, Map.of(), List.of());

        String text = renderer.renderInline(r);

        assertTrue(text.contains("已检查"), text);
        assertTrue(text.contains("低库存"), text);
        assertTrue(text.contains("正常"), text);
        assertFalse(text.contains("临期"), "不得声称检查了未注册的规则: " + text);
    }

    @Test
    @DisplayName("UT-FTR-02: 🔴 checkedRules 为空时返回空串 —— 一条规则都没跑成，不许说任何话")
    void nothingCheckedRendersNothing() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of(), 0, Map.of(), List.of("低库存"));

        assertEquals("", renderer.renderInline(r),
                "全部规则失败时若仍输出「均正常」，就是把故障渲染成了健康");
    }

    @Test
    @DisplayName("UT-FTR-03: 单条低库存渲染出名称/当前量/安全线/缺口")
    void rendersLowStockNumbers() {
        FindingService.Result r = new FindingService.Result(
                List.of(lowStock("鲈鱼", "12", "50", "38")), List.of("低库存"), 1,
                Map.of("LOW_STOCK", 1), List.of());

        String text = renderer.renderInline(r);

        assertTrue(text.contains("鲈鱼"), text);
        assertTrue(text.contains("12"), text);
        assertTrue(text.contains("50"), text);
        assertTrue(text.contains("38"), text);
        assertTrue(text.contains("kg"), text);
    }

    @Test
    @DisplayName("UT-FTR-04: 🔴 渲染文案不得出现供应商 —— 该字段全链路不存在")
    void neverMentionsSupplier() {
        FindingService.Result r = new FindingService.Result(
                List.of(lowStock("鲈鱼", "12", "50", "38")), List.of("低库存"), 1,
                Map.of("LOW_STOCK", 1), List.of());

        String text = renderer.renderInline(r);

        assertFalse(text.contains("供应商"),
                "getLowStockWarnings 不产出 preferredSupplier，渲染层不得凭空提及: " + text);
    }

    @Test
    @DisplayName("UT-FTR-05: 超出上限时提示「还有 N 项」，N = totalCount - 已显示条数")
    void showsRemainingCount() {
        FindingService.Result r = new FindingService.Result(
                List.of(lowStock("鲈鱼", "12", "50", "38"), lowStock("带鱼", "5", "40", "35")),
                List.of("低库存"), 7, Map.of("LOW_STOCK", 7), List.of());

        String text = renderer.renderInline(r);

        assertTrue(text.contains("还有 5 项"), text);
    }

    @Test
    @DisplayName("UT-FTR-06: 未超出上限时不出现「还有」")
    void noRemainingHintWhenNotTruncated() {
        FindingService.Result r = new FindingService.Result(
                List.of(lowStock("鲈鱼", "12", "50", "38")), List.of("低库存"), 1,
                Map.of("LOW_STOCK", 1), List.of());

        assertFalse(renderer.renderInline(r).contains("还有"));
    }

    @Test
    @DisplayName("UT-FTR-07: 未知 code 走兜底模板，不抛异常也不输出 null")
    void unknownCodeFallsBack() {
        Finding unknown = new Finding("SOMETHING_NEW", "inventory",
                Finding.Severity.WARNING, 50, "X1", "神秘物料", Map.of());
        FindingService.Result r = new FindingService.Result(
                List.of(unknown), List.of("神秘规则"), 1, Map.of("SOMETHING_NEW", 1), List.of());

        String text = renderer.renderInline(r);

        assertTrue(text.contains("神秘物料"), text);
        assertFalse(text.contains("null"), text);
    }

    @Test
    @DisplayName("UT-FTR-08: 🔴 部分规则失败时不得渲染成无保留的「均正常」——必须点名失败规则")
    void partialFailureMustNotRenderUnqualifiedAllClear() {
        // 低库存成功跑完且零发现，临期同一轮失败——渲染层只看到 checkedRules
        // 非空、findings 为空，若只靠这两点判断就会输出「已检查 低库存，均正常」，
        // 对沉默的「临期」是假话。
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of("低库存"), 0, Map.of(), List.of("临期"));

        String text = renderer.renderInline(r);

        assertTrue(text.contains("低库存"), text);
        assertTrue(text.contains("临期"), text);
        assertTrue(text.contains("失败") || text.contains("无法判断"),
                "必须点名未跑完的规则, 不能只说均正常: " + text);
    }

    @Test
    @DisplayName("UT-FTR-09: 2+ 条规则均正常时用「 / 」拼接 checkedRules")
    void joinsMultipleCheckedRulesWithSlash() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of("低库存", "临期"), 0, Map.of(), List.of());

        String text = renderer.renderInline(r);

        assertTrue(text.contains("低库存 / 临期"), text);
    }
}
