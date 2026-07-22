package com.cretas.aims.dto.inventory;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #928 族 backlog (2026-06-16) — 销售单/采购单 行项目数量必须 &gt; 0。
 *
 * <p>#928 给四个收发货/退货/调拨 DTO 加了 {@code @DecimalMin(value="0.01")}, 但显式
 * deferred 了下单侧的两个 DTO ({@code CreateSalesOrderRequest.SalesOrderItemDTO.quantity}
 * + {@code CreatePurchaseOrderRequest.PurchaseOrderItemDTO.quantity})。本测试验证这两个
 * 下单行 qty 也已加约束, 级联 {@code @Valid} 触发内嵌 DTO 校验:
 * <ul>
 *   <li>qty = 0 → ConstraintViolation</li>
 *   <li>qty = -5 → ConstraintViolation</li>
 *   <li>qty = 0.01 → pass (边界最小合法值)</li>
 *   <li>qty = 正常值 → pass (happy path)</li>
 * </ul>
 *
 * <p>两个 controller 已有 {@code @Valid @RequestBody} (SalesController.createSalesOrder /
 * PurchaseController.createPurchaseOrder) — 框架将把 ConstraintViolation 转为 4xx,
 * 不再静默接收 qty=0 的下单行。
 *
 * @see CreateSalesOrderRequest
 * @see CreatePurchaseOrderRequest
 */
@DisplayName("销售单/采购单 行 qty @DecimalMin(0.01) 级联校验")
class OrderLineQtyValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 销售单 — CreateSalesOrderRequest.SalesOrderItemDTO.quantity
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("销售单: qty=0 → ConstraintViolation")
    void salesOrder_zeroQty_violatesDecimalMin() {
        CreateSalesOrderRequest req = validSalesOrderRequest(BigDecimal.ZERO);

        Set<ConstraintViolation<CreateSalesOrderRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=0 应触发 @DecimalMin(0.01) on items[0].quantity")
                .anyMatch(v -> v.getPropertyPath().toString().contains("quantity")
                        && v.getMessage().contains("销售数量必须大于0"));
    }

    @Test
    @DisplayName("销售单: qty=-5 → ConstraintViolation")
    void salesOrder_negativeQty_violatesDecimalMin() {
        CreateSalesOrderRequest req = validSalesOrderRequest(new BigDecimal("-5"));

        Set<ConstraintViolation<CreateSalesOrderRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=-5 应触发 @DecimalMin(0.01)")
                .anyMatch(v -> v.getPropertyPath().toString().contains("quantity"));
    }

    @Test
    @DisplayName("销售单: qty=0.01 → 通过 (边界最小合法值)")
    void salesOrder_minBoundaryQty_passes() {
        CreateSalesOrderRequest req = validSalesOrderRequest(new BigDecimal("0.01"));

        Set<ConstraintViolation<CreateSalesOrderRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=0.01 是合法最小值, 不应有 quantity 违规")
                .noneMatch(v -> v.getPropertyPath().toString().contains("quantity"));
    }

    @Test
    @DisplayName("销售单: qty=100 → 通过 (happy path)")
    void salesOrder_normalQty_passes() {
        CreateSalesOrderRequest req = validSalesOrderRequest(new BigDecimal("100"));

        Set<ConstraintViolation<CreateSalesOrderRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=100 happy path, 不应触发 quantity 校验")
                .noneMatch(v -> v.getPropertyPath().toString().contains("quantity"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 采购单 — CreatePurchaseOrderRequest.PurchaseOrderItemDTO.quantity
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("采购单: qty=0 → ConstraintViolation")
    void purchaseOrder_zeroQty_violatesDecimalMin() {
        CreatePurchaseOrderRequest req = validPurchaseOrderRequest(BigDecimal.ZERO);

        Set<ConstraintViolation<CreatePurchaseOrderRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=0 应触发 @DecimalMin(0.01) on items[0].quantity")
                .anyMatch(v -> v.getPropertyPath().toString().contains("quantity")
                        && v.getMessage().contains("采购数量必须大于0"));
    }

    @Test
    @DisplayName("采购单: qty=-5 → ConstraintViolation")
    void purchaseOrder_negativeQty_violatesDecimalMin() {
        CreatePurchaseOrderRequest req = validPurchaseOrderRequest(new BigDecimal("-5"));

        Set<ConstraintViolation<CreatePurchaseOrderRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=-5 应触发 @DecimalMin(0.01)")
                .anyMatch(v -> v.getPropertyPath().toString().contains("quantity"));
    }

    @Test
    @DisplayName("采购单: qty=0.01 → 通过 (边界最小合法值)")
    void purchaseOrder_minBoundaryQty_passes() {
        CreatePurchaseOrderRequest req = validPurchaseOrderRequest(new BigDecimal("0.01"));

        Set<ConstraintViolation<CreatePurchaseOrderRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=0.01 是合法最小值, 不应有 quantity 违规")
                .noneMatch(v -> v.getPropertyPath().toString().contains("quantity"));
    }

    @Test
    @DisplayName("采购单: qty=200 → 通过 (happy path)")
    void purchaseOrder_normalQty_passes() {
        CreatePurchaseOrderRequest req = validPurchaseOrderRequest(new BigDecimal("200"));

        Set<ConstraintViolation<CreatePurchaseOrderRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=200 happy path, 不应触发 quantity 校验")
                .noneMatch(v -> v.getPropertyPath().toString().contains("quantity"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Builder helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static CreateSalesOrderRequest validSalesOrderRequest(BigDecimal qty) {
        CreateSalesOrderRequest.SalesOrderItemDTO item = new CreateSalesOrderRequest.SalesOrderItemDTO();
        item.setProductTypeId("PT-001");
        item.setQuantity(qty);
        item.setUnit("箱");

        CreateSalesOrderRequest req = new CreateSalesOrderRequest();
        req.setCustomerId("CUST-001");
        req.setProcessingMode(com.cretas.aims.entity.enums.SalesProcessingMode.STANDARD_SALE);
        req.setMaterialSupplyMode(com.cretas.aims.entity.enums.MaterialSupplyMode.FACTORY_SUPPLIED);
        req.setOrderDate(LocalDate.now());
        req.setItems(List.of(item));
        return req;
    }

    private static CreatePurchaseOrderRequest validPurchaseOrderRequest(BigDecimal qty) {
        CreatePurchaseOrderRequest.PurchaseOrderItemDTO item = new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        item.setMaterialTypeId("MT-001");
        item.setQuantity(qty);
        item.setUnit("kg");

        CreatePurchaseOrderRequest req = new CreatePurchaseOrderRequest();
        req.setSupplierId("SUP-001");
        req.setOrderDate(LocalDate.now());
        req.setItems(List.of(item));
        return req;
    }
}
