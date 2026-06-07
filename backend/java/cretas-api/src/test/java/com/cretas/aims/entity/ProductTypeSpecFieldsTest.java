package com.cretas.aims.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T123: 验证 ProductType 新字段 level1Unit + baseProductName 的 POJO 逻辑.
 *
 * 不依赖 Spring context / DB — 纯 POJO 测试.
 */
@DisplayName("ProductType — T123 规格两级单位 + 名称分离字段")
class ProductTypeSpecFieldsTest {

    @Test
    @DisplayName("level1Unit 字段可设置/读取")
    void level1Unit_setAndGet() {
        ProductType pt = new ProductType();
        pt.setLevel1Unit("筐");
        assertThat(pt.getLevel1Unit()).isEqualTo("筐");
    }

    @Test
    @DisplayName("baseProductName 字段可设置/读取")
    void baseProductName_setAndGet() {
        ProductType pt = new ProductType();
        pt.setBaseProductName("好食光卤猪蹄");
        assertThat(pt.getBaseProductName()).isEqualTo("好食光卤猪蹄");
    }

    @Test
    @DisplayName("新字段默认为 null (向后兼容)")
    void newFields_defaultNull() {
        ProductType pt = new ProductType();
        assertThat(pt.getLevel1Unit()).isNull();
        assertThat(pt.getBaseProductName()).isNull();
    }

    @Test
    @DisplayName("两级单位换算: 1 level1Unit = boxConversionCoefficient 个 unit")
    void twoLevelUnitConversion_semantics() {
        // 1 筐 = 10 盒 — 这是语义测试, 确保三字段一起工作
        ProductType pt = new ProductType();
        pt.setLevel1Unit("筐");
        pt.setUnit("盒");
        pt.setBoxConversionCoefficient(new BigDecimal("10"));
        pt.setGramsPerUnit(new BigDecimal("120"));  // 每盒 120g

        // 验证: 1 筐 = 10 盒, 每盒 120g → 1 筐 = 1200g
        BigDecimal gramsPerLevel1 = pt.getGramsPerUnit()
                .multiply(pt.getBoxConversionCoefficient());
        assertThat(gramsPerLevel1).isEqualByComparingTo(new BigDecimal("1200"));
        assertThat(pt.getLevel1Unit()).isEqualTo("筐");
        assertThat(pt.getUnit()).isEqualTo("盒");
    }

    @Test
    @DisplayName("baseProductName 用于名称分离: 产品名 vs 基础名可不同")
    void baseProductName_separationFromName() {
        ProductType pt = new ProductType();
        pt.setName("叮咚好食光卤猪蹄 200g×4");
        pt.setBaseProductName("好食光卤猪蹄");
        pt.setRelatedCustomer("叮咚买菜");

        // name 包含客户/规格后缀, baseProductName 是纯产品名
        assertThat(pt.getBaseProductName()).doesNotContain("叮咚");
        assertThat(pt.getName()).contains("叮咚");
    }

    @Test
    @DisplayName("customerId 可设置 — 客户打通验证")
    void customerId_setAndGet() {
        ProductType pt = new ProductType();
        pt.setCustomerId("CUST-001");
        pt.setRelatedCustomer("叮咚买菜");
        assertThat(pt.getCustomerId()).isEqualTo("CUST-001");
        assertThat(pt.getRelatedCustomer()).isEqualTo("叮咚买菜");
    }

    @Test
    @DisplayName("boxConversionCoefficient 现有语义未变 (sales_order 箱数计算兼容)")
    void boxConversionCoefficient_existingSemantics_unchanged() {
        // 验证现有字段语义: 已有销售订单用 quantity / boxConversionCoefficient = 箱数
        // 新的 T123 只是给它加了 level1Unit 做显示搭档, 不改其值含义
        ProductType pt = new ProductType();
        pt.setBoxConversionCoefficient(new BigDecimal("10.0000"));
        BigDecimal orderQuantity = new BigDecimal("50.0");
        BigDecimal expectedBoxes = orderQuantity.divide(pt.getBoxConversionCoefficient(), 2, java.math.RoundingMode.HALF_UP);
        assertThat(expectedBoxes).isEqualByComparingTo(new BigDecimal("5.00"));
    }
}
