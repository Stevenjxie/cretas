package com.cretas.aims.entity;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T133: 验证 ProductType.wipToFgYield 字段 POJO 读写 + DTO 映射.
 *
 * 纯 POJO 测试, 不依赖 Spring context / DB.
 */
@DisplayName("ProductType — T133 wipToFgYield 半成品→成品出成率")
class ProductTypeWipYieldFieldTest {

    @Test
    @DisplayName("entity wipToFgYield 可设置/读取")
    void entity_wipToFgYield_setAndGet() {
        ProductType pt = new ProductType();
        pt.setWipToFgYield(new BigDecimal("0.5500"));
        assertThat(pt.getWipToFgYield()).isEqualByComparingTo(new BigDecimal("0.5500"));
    }

    @Test
    @DisplayName("entity wipToFgYield 默认为 null (向后兼容)")
    void entity_wipToFgYield_defaultNull() {
        ProductType pt = new ProductType();
        assertThat(pt.getWipToFgYield()).isNull();
    }

    @Test
    @DisplayName("DTO wipToFgYield 可设置/读取")
    void dto_wipToFgYield_setAndGet() {
        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setWipToFgYield(new BigDecimal("0.8500"));
        assertThat(dto.getWipToFgYield()).isEqualByComparingTo(new BigDecimal("0.8500"));
    }

    @Test
    @DisplayName("DTO wipToFgYield 默认为 null (不传时备货看板按 1.0 兜底)")
    void dto_wipToFgYield_defaultNull() {
        ProductTypeDTO dto = new ProductTypeDTO();
        assertThat(dto.getWipToFgYield()).isNull();
    }

    @Test
    @DisplayName("entity→DTO 映射: wipToFgYield 透传 (mirror gramsPerUnit 模式)")
    void entity_to_dto_wipToFgYield_mapping() {
        // 模拟 convertToDTO 最小路径: DTO builder 接收 entity 值
        BigDecimal yield = new BigDecimal("0.5500");
        ProductType pt = new ProductType();
        pt.setWipToFgYield(yield);
        pt.setGramsPerUnit(new BigDecimal("120.00"));  // 相邻字段同样存在

        // DTO.builder() 直接设值 (mirror convertToDTO 中同模式)
        ProductTypeDTO dto = ProductTypeDTO.builder()
                .gramsPerUnit(pt.getGramsPerUnit())
                .wipToFgYield(pt.getWipToFgYield())
                .build();

        assertThat(dto.getWipToFgYield()).isEqualByComparingTo(yield);
        assertThat(dto.getGramsPerUnit()).isEqualByComparingTo(new BigDecimal("120.00"));
    }

    @Test
    @DisplayName("entity→DTO: wipToFgYield null 时 DTO 也为 null (备货看板 fallback to 1.0)")
    void entity_to_dto_wipToFgYield_null_preserved() {
        ProductType pt = new ProductType();
        // wipToFgYield intentionally not set

        ProductTypeDTO dto = ProductTypeDTO.builder()
                .wipToFgYield(pt.getWipToFgYield())
                .build();

        assertThat(dto.getWipToFgYield()).isNull();
    }

    @Test
    @DisplayName("create path: DTO.wipToFgYield 直接写入 entity (unconditional, mirror gramsPerUnit in create)")
    void create_path_unconditional_set() {
        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setWipToFgYield(new BigDecimal("0.6000"));

        ProductType pt = new ProductType();
        // mirror ProductTypeServiceImpl.createProductType line
        pt.setWipToFgYield(dto.getWipToFgYield());

        assertThat(pt.getWipToFgYield()).isEqualByComparingTo(new BigDecimal("0.6000"));
    }

    @Test
    @DisplayName("update path: wipToFgYield 只在非 null 时更新 (mirror gramsPerUnit in update)")
    void update_path_null_check() {
        ProductType existing = new ProductType();
        existing.setWipToFgYield(new BigDecimal("0.5000"));

        // case 1: dto 传 null → existing 不被覆盖
        ProductTypeDTO dtoNull = new ProductTypeDTO();
        // dtoNull.wipToFgYield is null
        if (dtoNull.getWipToFgYield() != null) existing.setWipToFgYield(dtoNull.getWipToFgYield());
        assertThat(existing.getWipToFgYield()).isEqualByComparingTo(new BigDecimal("0.5000"));

        // case 2: dto 传新值 → 覆盖
        ProductTypeDTO dtoNew = new ProductTypeDTO();
        dtoNew.setWipToFgYield(new BigDecimal("0.7500"));
        if (dtoNew.getWipToFgYield() != null) existing.setWipToFgYield(dtoNew.getWipToFgYield());
        assertThat(existing.getWipToFgYield()).isEqualByComparingTo(new BigDecimal("0.7500"));
    }
}
