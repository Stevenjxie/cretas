package com.cretas.aims.entity.alerts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlertTypeConverter 容错 — 未知 alert_type 不抛异常 (防 2026-06-25 启动阻断事故复现).
 */
@DisplayName("AlertTypeConverter 容错读取")
class AlertTypeConverterTest {

    private final AlertTypeConverter c = new AlertTypeConverter();

    @Test
    @DisplayName("合法值正常解析")
    void validValue_parses() {
        assertThat(c.convertToEntityAttribute("INVENTORY_LOW")).isEqualTo(AlertType.INVENTORY_LOW);
        assertThat(c.convertToEntityAttribute("CUSTOMER_PAYMENT_OVERDUE"))
                .isEqualTo(AlertType.CUSTOMER_PAYMENT_OVERDUE);
    }

    @Test
    @DisplayName("未知值返回 null 不抛 (事故脏数据 PAYMENT_OVERDUE/YIELD_LOW/WASTAGE_HIGH)")
    void unknownValue_returnsNullNotThrow() {
        assertThat(c.convertToEntityAttribute("PAYMENT_OVERDUE")).isNull();
        assertThat(c.convertToEntityAttribute("YIELD_LOW")).isNull();
        assertThat(c.convertToEntityAttribute("WASTAGE_HIGH")).isNull();
    }

    @Test
    @DisplayName("null / 空白返回 null")
    void nullAndBlank_returnNull() {
        assertThat(c.convertToEntityAttribute(null)).isNull();
        assertThat(c.convertToEntityAttribute("")).isNull();
        assertThat(c.convertToEntityAttribute("   ")).isNull();
    }

    @Test
    @DisplayName("写库: 枚举 → name, null → null")
    void toDatabaseColumn() {
        assertThat(c.convertToDatabaseColumn(AlertType.INVENTORY_LOW)).isEqualTo("INVENTORY_LOW");
        assertThat(c.convertToDatabaseColumn(null)).isNull();
    }
}
