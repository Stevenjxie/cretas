package com.cretas.aims.service.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UnitDisplayNamesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "box,盒",
            "bag,袋",
            "slice,片",
            "sheet,张",
            "roll,卷",
            "pcs,件",
            "case,箱",
            "pack,包",
            "bottle,瓶",
            "can,罐",
            "portion,份",
            "crate,框",
            "pail,桶",
            "tray,托盘",
            "plate,板",
            "item,项",
    })
    @DisplayName("计数/包装码翻成中文 —— 客户读不懂这些码")
    void translatesCountingCodes(String code, String expected) {
        assertThat(UnitDisplayNames.display(code)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"kg", "g", "mg", "t", "L", "ml", "mm", "cm", "m"})
    @DisplayName("⛔ 科学计量符号一律不翻 —— 与 V20261029_32 的取舍一致(秤上/单据上/国标上都这么写)")
    void keepsScientificSymbolsAsIs(String symbol) {
        assertThat(UnitDisplayNames.display(symbol)).isEqualTo(symbol);
    }

    @ParameterizedTest
    @ValueSource(strings = {"盒", "袋", "片", "公斤", "自定义单位"})
    @DisplayName("已经是中文的 / 表里没收的自定义单位, 原样返回, 不会被改写也不会变空")
    void passesThroughUnknownValues(String value) {
        assertThat(UnitDisplayNames.display(value)).isEqualTo(value);
    }

    @Test
    @DisplayName("大小写不敏感 —— 库里出现过 BOX / Box 这类写法")
    void isCaseInsensitive() {
        assertThat(UnitDisplayNames.display("BOX")).isEqualTo("盒");
        assertThat(UnitDisplayNames.display("Box")).isEqualTo("盒");
        assertThat(UnitDisplayNames.display(" box ")).isEqualTo("盒");
    }

    @Test
    @DisplayName("null / 空串不抛错 —— 调用方原本就要处理")
    void handlesNullAndBlank() {
        assertThat(UnitDisplayNames.display(null)).isNull();
        assertThat(UnitDisplayNames.display("")).isEmpty();
        assertThat(UnitDisplayNames.display("   ")).isEmpty();
    }

    @Test
    @DisplayName("mixed 是多单位混合的哨兵值, 不是真单位, 原样保留")
    void keepsMixedSentinel() {
        assertThat(UnitDisplayNames.display("mixed")).isEqualTo("mixed");
    }
}
