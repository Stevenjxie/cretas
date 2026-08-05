package com.cretas.aims.ai.grounding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link GroundedNumberValidator}. */
class GroundedNumberValidatorTest {

    private final GroundedNumberValidator validator = new GroundedNumberValidator();

    /** 模拟真实事实块：一条低库存发现的结构化事实。 */
    private static final List<Object> FACTS = List.of(
            Map.of("subjectName", "鲈鱼", "currentStock", 12, "safetyStock", 50, "gap", 38, "unit", "kg"));

    @Test
    @DisplayName("UT-GNV-01: 文案只引用事实里的数字 —— 全部有据")
    void allNumbersGrounded() {
        String text = "· 鲈鱼当前 12kg，低于安全线 50kg，缺口 38kg\n· 建议本周补货";

        assertEquals(List.of(), validator.findUngroundedNumbers(text, FACTS));
        assertTrue(validator.isGrounded(text, FACTS));
    }

    @Test
    @DisplayName("UT-GNV-02: 🔴 编出来的数字必须被抓出来")
    void catchesFabricatedNumber() {
        String text = "· 鲈鱼缺口 38kg，预计影响产值 21000 元";

        List<String> bad = validator.findUngroundedNumbers(text, FACTS);

        assertEquals(List.of("21000"), bad,
                "21000 不在事实里 —— 这正是「看起来精确的假数字」的形状");
        assertFalse(validator.isGrounded(text, FACTS));
    }

    @Test
    @DisplayName("UT-GNV-03: 🔴 数值比较而非字符串 —— 38.0 与 38 是同一个数")
    void comparesNumericallyNotTextually() {
        assertEquals(List.of(), validator.findUngroundedNumbers("· 缺口 38.0kg", FACTS),
                "字符串比较会把 38.0 误判成编造");
        assertEquals(List.of(), validator.findUngroundedNumbers("· 缺口 38.00kg", FACTS));
    }

    @Test
    @DisplayName("UT-GNV-04: 🔴 380 不等于 38 —— 数值比较不能把量级差异放过去")
    void magnitudeDifferenceIsNotGrounded() {
        assertEquals(List.of("380"), validator.findUngroundedNumbers("· 缺口 380kg", FACTS),
                "少一个数量级也是编造, 而且是最危险的一种");
    }

    @Test
    @DisplayName("UT-GNV-05: 标识符里的数字不算数字 —— MB-001 不该被切成 001")
    void identifiersAreNotNumbers() {
        List<Object> facts = List.of(Map.of("businessEntityId", "MB-001", "gap", 38));

        assertEquals(List.of(), validator.findUngroundedNumbers("· 批次 MB-001 缺口 38kg", facts));
    }

    @Test
    @DisplayName("UT-GNV-06: 千分位写法能对上事实里的裸数字")
    void thousandsSeparatorMatches() {
        List<Object> facts = List.of(Map.of("amount", 21000));

        assertEquals(List.of(), validator.findUngroundedNumbers("· 金额 21,000 元", facts));
    }

    @Test
    @DisplayName("UT-GNV-07: 🔴 序号不开后门 —— 「第 3 步」里的 3 无据同样被抓")
    void ordinalsGetNoExemption() {
        List<String> bad = validator.findUngroundedNumbers("第 3 步：补货", FACTS);

        assertEquals(List.of("3"), bad,
                "刻意不给小整数开后门: 一旦开了, 「缺 12kg」这类真该管的数字也会漏过去。"
                        + "对策是约束生成格式(要求用「·」分点), 而不是放松校验");
    }

    @Test
    @DisplayName("UT-GNV-08: 重复出现的无据数字只报一次, 按出现顺序")
    void deduplicatesPreservingOrder() {
        String text = "· 影响 21000 元\n· 再次强调 21000 元\n· 另有 999 元";

        assertEquals(List.of("21000", "999"), validator.findUngroundedNumbers(text, FACTS));
    }

    @Test
    @DisplayName("UT-GNV-09: 空文案/空事实的边界 —— 不抛异常")
    void emptyInputs() {
        assertEquals(List.of(), validator.findUngroundedNumbers(null, FACTS));
        assertEquals(List.of(), validator.findUngroundedNumbers("   ", FACTS));
        assertEquals(List.of("5"), validator.findUngroundedNumbers("· 共 5 项", List.of()),
                "事实为空时, 文案里任何数字都是无据的");
        assertEquals(List.of("5"), validator.findUngroundedNumbers("· 共 5 项", null));
    }

    @Test
    @DisplayName("UT-GNV-10: 不含数字的文案恒有据")
    void textWithoutNumbers() {
        assertTrue(validator.isGrounded("· 建议尽快补货并联系常用供应商", FACTS));
    }
}
