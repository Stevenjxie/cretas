package com.cretas.aims.config;

import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.enums.TaxRate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 守护 {@link EnumCoercionJacksonConfig}：请求体里可选枚举字段提交空字符串 {@code ""}
 * 时归一成 {@code null}，而不是在反序列化阶段抛 400。
 *
 * <p>回归用例 — 六扇门（F006）新建原料类型税率留空 → "请求格式不正确，请检查JSON格式"
 * （2026-06-16）。这是 web 层契约 bug：service 单测调用 service 时绕过了 Jackson
 * {@code @RequestBody} 反序列化，永远照不到此类问题，所以必须在序列化层守。
 */
class EnumCoercionJacksonConfigTest {

    /** 复刻 Spring Boot 把 customizer 应用到自动配置 ObjectMapper 的过程。 */
    private ObjectMapper mapperWithCoercion() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new EnumCoercionJacksonConfig().enumEmptyStringAsNullCustomizer().customize(builder);
        return builder.build();
    }

    @Test
    @DisplayName("可选枚举 taxRate 提交空字符串 → null（不报 400）")
    void emptyStringEnum_coercesToNull() throws Exception {
        RawMaterialTypeDTO dto = mapperWithCoercion()
                .readValue("{\"name\":\"测试\",\"taxRate\":\"\"}", RawMaterialTypeDTO.class);
        assertNull(dto.getTaxRate(), "空字符串税率应归一为 null（未配置）");
        assertEquals("测试", dto.getName());
    }

    @Test
    @DisplayName("合法枚举值仍正常解析")
    void validEnum_stillParses() throws Exception {
        RawMaterialTypeDTO dto = mapperWithCoercion()
                .readValue("{\"taxRate\":\"TAX_9\"}", RawMaterialTypeDTO.class);
        assertEquals(TaxRate.TAX_9, dto.getTaxRate());
    }

    @Test
    @DisplayName("真正非法的枚举值仍然报错（不掩盖真错误）")
    void invalidEnum_stillThrows() {
        assertThrows(Exception.class, () -> mapperWithCoercion()
                .readValue("{\"taxRate\":\"NOT_A_RATE\"}", RawMaterialTypeDTO.class));
    }
}
