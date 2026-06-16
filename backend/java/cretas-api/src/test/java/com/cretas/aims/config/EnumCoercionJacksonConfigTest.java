package com.cretas.aims.config;

import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.enums.TaxRate;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    @DisplayName("集合空字符串 → null: 报工 photos (List<String>) 不报晦涩 400（#941 补集合维度）")
    void emptyStringCollection_coercesToNull() throws Exception {
        // 报工 photos:"" (空串而非 []) 曾落晦涩 "请求格式不正确" fallback
        // (Cannot coerce empty String to element of ArrayList) — #941 只修枚举没修集合。
        com.cretas.aims.dto.ProcessWorkReportSubmitRequest req = mapperWithCoercion()
                .readValue("{\"processTaskId\":\"x\",\"photos\":\"\",\"workerIds\":\"\"}",
                        com.cretas.aims.dto.ProcessWorkReportSubmitRequest.class);
        assertNull(req.getPhotos(), "空字符串 photos 应归一为 null（= 等同省略, 不报晦涩 400）");
        assertNull(req.getWorkerIds(), "空字符串 workerIds 应归一为 null");
    }

    @Test
    @DisplayName("跨 DTO: 销售订单 defaultInvoiceType 空字符串 → null（#941 不止 taxRate）")
    void emptyStringEnum_otherDto_coercesToNull() throws Exception {
        // CreateSalesOrderRequest.defaultInvoiceType 是 InvoiceType 枚举; 前端未配置时发 ""。
        CreateSalesOrderRequest req = mapperWithCoercion()
                .readValue("{\"customerId\":\"x\",\"defaultInvoiceType\":\"\"}", CreateSalesOrderRequest.class);
        assertNull(req.getDefaultInvoiceType(), "空字符串开票类型应归一为 null");
    }

    // ========== #938 回归护栏: 自动生成的编码字段不得带 @NotBlank ==========
    // 六扇门 F006 建档曾因 RawMaterialTypeDTO.code 上的 @NotBlank 在 @Valid 阶段
    // 提前 400 ("原材料编码不能为空") — 而前端按设计不传 code, 由 service 自动生成。
    // 这两个测试钉死回归: 谁再给自动编码字段加回 @NotBlank, CI 立刻红。
    // service 自动生成 + 查重 + DB NOT NULL 才是真正的保证, 不是 @Valid 注解。

    @Test
    @DisplayName("#938 护栏: RawMaterialTypeDTO.code 不得带 @NotBlank（自动生成）")
    void rawMaterialCode_hasNoNotBlank() throws Exception {
        assertNoNotBlank(RawMaterialTypeDTO.class, "code");
    }

    @Test
    @DisplayName("#938 护栏: ProductTypeDTO.code 不得带 @NotBlank（自动生成）")
    void productTypeCode_hasNoNotBlank() throws Exception {
        assertNoNotBlank(ProductTypeDTO.class, "code");
    }

    private void assertNoNotBlank(Class<?> dtoClass, String fieldName) throws NoSuchFieldException {
        Field f = dtoClass.getDeclaredField(fieldName);
        assertFalse(f.isAnnotationPresent(NotBlank.class),
                dtoClass.getSimpleName() + "." + fieldName
                        + " 不得带 @NotBlank — 该字段前端不传, 由 service 自动生成; "
                        + "加 @NotBlank 会在 @Valid 阶段提前 400 阻断建档 (见 #938)。");
    }
}
