package com.cretas.aims.entity;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SP9-M1: 验证 ProductType.quotedLaborCostPerKg 字段 POJO 读写 + DTO 映射.
 *
 * <p>纯 POJO 测试, 不依赖 Spring context / DB (mirror wipToFgYield 测试模式).</p>
 */
@DisplayName("ProductType — SP9-M1 quotedLaborCostPerKg 研发预估人工成本")
class ProductTypeQuotedLaborCostFieldTest {

    @Test
    @DisplayName("entity quotedLaborCostPerKg 可设置/读取")
    void entity_quotedLaborCostPerKg_setAndGet() {
        ProductType pt = new ProductType();
        pt.setQuotedLaborCostPerKg(new BigDecimal("12.5000"));
        assertThat(pt.getQuotedLaborCostPerKg()).isEqualByComparingTo(new BigDecimal("12.5000"));
    }

    @Test
    @DisplayName("entity quotedLaborCostPerKg 默认为 null (未配置时不显示为 0)")
    void entity_quotedLaborCostPerKg_defaultNull() {
        ProductType pt = new ProductType();
        assertThat(pt.getQuotedLaborCostPerKg()).isNull();
    }

    @Test
    @DisplayName("DTO quotedLaborCostPerKg 可设置/读取")
    void dto_quotedLaborCostPerKg_setAndGet() {
        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setQuotedLaborCostPerKg(new BigDecimal("8.7500"));
        assertThat(dto.getQuotedLaborCostPerKg()).isEqualByComparingTo(new BigDecimal("8.7500"));
    }

    @Test
    @DisplayName("DTO quotedLaborCostPerKg 默认为 null")
    void dto_quotedLaborCostPerKg_defaultNull() {
        ProductTypeDTO dto = new ProductTypeDTO();
        assertThat(dto.getQuotedLaborCostPerKg()).isNull();
    }

    @Test
    @DisplayName("entity→DTO 映射: quotedLaborCostPerKg 透传 (mirror gramsPerUnit 模式)")
    void entity_to_dto_quotedLaborCostPerKg_mapping() {
        BigDecimal quoted = new BigDecimal("15.2500");
        ProductType pt = new ProductType();
        pt.setQuotedLaborCostPerKg(quoted);
        pt.setWipToFgYield(new BigDecimal("0.5500")); // 相邻字段

        // Mirror convertToDTO builder pattern
        ProductTypeDTO dto = ProductTypeDTO.builder()
                .wipToFgYield(pt.getWipToFgYield())
                .quotedLaborCostPerKg(pt.getQuotedLaborCostPerKg())
                .build();

        assertThat(dto.getQuotedLaborCostPerKg()).isEqualByComparingTo(quoted);
        assertThat(dto.getWipToFgYield()).isEqualByComparingTo(new BigDecimal("0.5500"));
    }

    @Test
    @DisplayName("entity→DTO: quotedLaborCostPerKg null 时 DTO 也为 null (前端显示'-'语义)")
    void entity_to_dto_quotedLaborCostPerKg_null_preserved() {
        ProductType pt = new ProductType();
        // quotedLaborCostPerKg intentionally not set

        ProductTypeDTO dto = ProductTypeDTO.builder()
                .quotedLaborCostPerKg(pt.getQuotedLaborCostPerKg())
                .build();

        assertThat(dto.getQuotedLaborCostPerKg()).isNull();
    }

    @Test
    @DisplayName("create path: DTO.quotedLaborCostPerKg 直接写入 entity (mirror gramsPerUnit in create)")
    void create_path_unconditional_set() {
        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setQuotedLaborCostPerKg(new BigDecimal("20.0000"));

        ProductType pt = new ProductType();
        // mirror ProductTypeServiceImpl.buildProductTypeEntity
        pt.setQuotedLaborCostPerKg(dto.getQuotedLaborCostPerKg());

        assertThat(pt.getQuotedLaborCostPerKg()).isEqualByComparingTo(new BigDecimal("20.0000"));
    }

    @Test
    @DisplayName("create path: DTO.quotedLaborCostPerKg null → entity null (不默认 0)")
    void create_path_null_dto_gives_null_entity() {
        ProductTypeDTO dto = new ProductTypeDTO();
        // quotedLaborCostPerKg not set

        ProductType pt = new ProductType();
        pt.setQuotedLaborCostPerKg(dto.getQuotedLaborCostPerKg());

        assertThat(pt.getQuotedLaborCostPerKg()).isNull();
    }

    @Test
    @DisplayName("update path: quotedLaborCostPerKg 只在非 null 时更新 (mirror gramsPerUnit in update)")
    void update_path_null_guard() {
        ProductType existing = new ProductType();
        existing.setQuotedLaborCostPerKg(new BigDecimal("10.0000"));

        // case 1: dto 传 null → existing 不被覆盖
        ProductTypeDTO dtoNull = new ProductTypeDTO();
        if (dtoNull.getQuotedLaborCostPerKg() != null)
            existing.setQuotedLaborCostPerKg(dtoNull.getQuotedLaborCostPerKg());
        assertThat(existing.getQuotedLaborCostPerKg()).isEqualByComparingTo(new BigDecimal("10.0000"));

        // case 2: dto 传新值 → 覆盖
        ProductTypeDTO dtoNew = new ProductTypeDTO();
        dtoNew.setQuotedLaborCostPerKg(new BigDecimal("18.5000"));
        if (dtoNew.getQuotedLaborCostPerKg() != null)
            existing.setQuotedLaborCostPerKg(dtoNew.getQuotedLaborCostPerKg());
        assertThat(existing.getQuotedLaborCostPerKg()).isEqualByComparingTo(new BigDecimal("18.5000"));
    }
}
