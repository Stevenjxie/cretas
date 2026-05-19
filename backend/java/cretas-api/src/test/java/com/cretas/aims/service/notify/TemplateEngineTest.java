package com.cretas.aims.service.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TemplateEngine} — Phase 3 Canvas-Notify Step T5.
 *
 * <p>Covers:
 * <ul>
 *   <li>UT-TE-01: 单变量替换 happy path</li>
 *   <li>UT-TE-02: 多变量替换 + 同一变量出现多次</li>
 *   <li>UT-TE-03: 变量周围有空白 (容忍 {{ var }})</li>
 *   <li>UT-TE-04: 缺少变量值 → IllegalArgumentException (fool-proof Rule 1)</li>
 *   <li>UT-TE-05: 空模板 / null 模板 return as-is</li>
 *   <li>UT-TE-06: 无占位符模板 return 原文不变</li>
 *   <li>UT-TE-07: 替换值含特殊字符 ($, \) 不破坏 regex</li>
 *   <li>UT-TE-08: null params + 模板含占位符 → IAE</li>
 *   <li>UT-TE-09: 数字类型 value 正确 toString</li>
 * </ul>
 *
 * @since 2026-05-18 (Phase 3 impl)
 */
class TemplateEngineTest {

    private final TemplateEngine engine = new TemplateEngine();

    @Test
    @DisplayName("UT-TE-01: 单变量替换 happy path")
    void singleVarReplacement() {
        Map<String, Object> params = Map.of("name", "张三");
        assertEquals("你好, 张三!", engine.render("你好, {{name}}!", params));
    }

    @Test
    @DisplayName("UT-TE-02: 多变量替换 + 同一变量出现多次")
    void multipleVarsAndRepeated() {
        Map<String, Object> params = Map.of("name", "张三", "count", 3);
        String rendered = engine.render(
                "{{name}} 有 {{count}} 笔待审单, {{name}} 请尽快处理", params);
        assertEquals("张三 有 3 笔待审单, 张三 请尽快处理", rendered);
    }

    @Test
    @DisplayName("UT-TE-03: 变量周围允许空白 ({{ var }} 也 OK)")
    void whitespaceTolerance() {
        Map<String, Object> params = Map.of("amount", "1000");
        assertEquals("金额 1000 元", engine.render("金额 {{ amount }} 元", params));
        assertEquals("金额 1000 元", engine.render("金额 {{amount }} 元", params));
        assertEquals("金额 1000 元", engine.render("金额 {{ amount}} 元", params));
    }

    @Test
    @DisplayName("UT-TE-04: 缺少变量值 → IllegalArgumentException (fool-proof Rule 1)")
    void missingVarThrowsIAE() {
        Map<String, Object> params = Map.of("name", "张三");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.render("你好 {{name}}, 你有 {{count}} 笔单", params));
        assertTrue(ex.getMessage().contains("count"),
                "异常 message 应含缺失变量名: " + ex.getMessage());
    }

    @Test
    @DisplayName("UT-TE-05: 空模板 / null 模板 return as-is")
    void emptyOrNullTemplate() {
        assertNull(engine.render(null, Map.of("x", "y")));
        assertEquals("", engine.render("", Map.of("x", "y")));
    }

    @Test
    @DisplayName("UT-TE-06: 无占位符模板 return 原文")
    void noPlaceholderReturnsOriginal() {
        String template = "纯文本通知, 无变量";
        assertEquals(template, engine.render(template, Map.of("x", "y")));
        assertEquals(template, engine.render(template, new HashMap<>()));
    }

    @Test
    @DisplayName("UT-TE-07: 替换值含 $ 和 \\ 特殊字符 不破坏正则")
    void specialCharsInValue() {
        Map<String, Object> params = Map.of("path", "C:\\Users\\foo$bar");
        String rendered = engine.render("路径: {{path}}", params);
        assertEquals("路径: C:\\Users\\foo$bar", rendered);
    }

    @Test
    @DisplayName("UT-TE-08: null params 模板含占位符 → IAE (不静默返空字符串)")
    void nullParamsWithPlaceholder() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.render("你有 {{count}} 笔单", null));
        assertTrue(ex.getMessage().contains("count"));
    }

    @Test
    @DisplayName("UT-TE-09: 数字类型 value 自动 toString")
    void numericValueAutoToString() {
        Map<String, Object> params = Map.of("count", 42, "ratio", 0.85);
        assertEquals("count=42, ratio=0.85", engine.render("count={{count}}, ratio={{ratio}}", params));
    }
}
