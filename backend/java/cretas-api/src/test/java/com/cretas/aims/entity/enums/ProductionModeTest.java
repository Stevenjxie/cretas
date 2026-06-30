package com.cretas.aims.entity.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1 — ProductionMode 枚举 fromString 合约 + mapper 路径覆盖。
 *
 * <p>纯 POJO 单元测试, 无 Spring 上下文。</p>
 */
@DisplayName("ProductionMode.fromString 合约")
class ProductionModeTest {

    // ─── fromString 边界 ───────────────────────────────────────────────

    @Test
    @DisplayName("null → BY_ORDER (向后兼容)")
    void fromString_null_returnsByOrder() {
        assertThat(ProductionMode.fromString(null)).isEqualTo(ProductionMode.BY_ORDER);
    }

    @Test
    @DisplayName("blank → BY_ORDER")
    void fromString_blank_returnsByOrder() {
        assertThat(ProductionMode.fromString("   ")).isEqualTo(ProductionMode.BY_ORDER);
    }

    @Test
    @DisplayName("非法值 → BY_ORDER")
    void fromString_invalid_returnsByOrder() {
        assertThat(ProductionMode.fromString("UNKNOWN_MODE")).isEqualTo(ProductionMode.BY_ORDER);
    }

    @Test
    @DisplayName("'BY_ORDER' (大写) → BY_ORDER")
    void fromString_byOrder_exact() {
        assertThat(ProductionMode.fromString("BY_ORDER")).isEqualTo(ProductionMode.BY_ORDER);
    }

    @Test
    @DisplayName("'by_stock' (小写) → BY_STOCK")
    void fromString_byStock_lowercase() {
        assertThat(ProductionMode.fromString("by_stock")).isEqualTo(ProductionMode.BY_STOCK);
    }

    @Test
    @DisplayName("'BY_STOCK' (大写) → BY_STOCK")
    void fromString_byStock_uppercase() {
        assertThat(ProductionMode.fromString("BY_STOCK")).isEqualTo(ProductionMode.BY_STOCK);
    }

    @Test
    @DisplayName("'  BY_STOCK  ' (前后空格) → BY_STOCK")
    void fromString_byStock_withWhitespace() {
        assertThat(ProductionMode.fromString("  BY_STOCK  ")).isEqualTo(ProductionMode.BY_STOCK);
    }

    // ─── Mapper 路径: productionMode 设置后从 entity 读回 ─────────────

    @Test
    @DisplayName("mapper path: entity 默认 productionMode = BY_ORDER")
    void entity_defaultProductionMode_isByOrder() {
        com.cretas.aims.entity.ProductionPlan plan = new com.cretas.aims.entity.ProductionPlan();
        assertThat(plan.getProductionMode()).isEqualTo(ProductionMode.BY_ORDER);
    }

    @Test
    @DisplayName("mapper path: 设置 BY_STOCK 后读回正确")
    void entity_setByStock_readBack() {
        com.cretas.aims.entity.ProductionPlan plan = new com.cretas.aims.entity.ProductionPlan();
        plan.setProductionMode(ProductionMode.BY_STOCK);
        assertThat(plan.getProductionMode()).isEqualTo(ProductionMode.BY_STOCK);
    }

    @Test
    @DisplayName("mapper: fromString(null) 赋给 entity → BY_ORDER")
    void mapper_fromStringNull_setsEntityByOrder() {
        com.cretas.aims.entity.ProductionPlan plan = new com.cretas.aims.entity.ProductionPlan();
        plan.setProductionMode(ProductionMode.fromString(null));
        assertThat(plan.getProductionMode()).isEqualTo(ProductionMode.BY_ORDER);
    }

    @Test
    @DisplayName("mapper: fromString('BY_STOCK') 赋给 entity → BY_STOCK")
    void mapper_fromStringByStock_setsEntityByStock() {
        com.cretas.aims.entity.ProductionPlan plan = new com.cretas.aims.entity.ProductionPlan();
        plan.setProductionMode(ProductionMode.fromString("BY_STOCK"));
        assertThat(plan.getProductionMode()).isEqualTo(ProductionMode.BY_STOCK);
    }

    // ─── DTO 路径 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("ProductionPlanDTO.productionMode 字段存在且默认 null")
    void dto_productionModeField_existsAndNullByDefault() {
        com.cretas.aims.dto.production.ProductionPlanDTO dto =
                new com.cretas.aims.dto.production.ProductionPlanDTO();
        assertThat(dto.getProductionMode()).isNull();
    }

    @Test
    @DisplayName("ProductionPlanDTO.productionMode 可 set/get BY_STOCK")
    void dto_productionModeField_setGetByStock() {
        com.cretas.aims.dto.production.ProductionPlanDTO dto =
                new com.cretas.aims.dto.production.ProductionPlanDTO();
        dto.setProductionMode("BY_STOCK");
        assertThat(dto.getProductionMode()).isEqualTo("BY_STOCK");
    }

    // ─── Request DTO 路径 ──────────────────────────────────────────────

    @Test
    @DisplayName("CreateProductionPlanRequest.productionMode 字段存在且默认 null")
    void request_productionModeField_existsAndNullByDefault() {
        com.cretas.aims.dto.production.CreateProductionPlanRequest req =
                new com.cretas.aims.dto.production.CreateProductionPlanRequest();
        assertThat(req.getProductionMode()).isNull();
    }
}
