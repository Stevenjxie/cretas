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
 * P2 Bug Fix — 收货/发货/退货/调拨 数量必须 &gt; 0 (qty=0 现不返400).
 *
 * <p>四个 DTO 的行项目数量字段均已加 {@code @DecimalMin(value="0.01")}。
 * 本测试验证级联 {@code @Valid} 确实触发了内嵌 DTO 的约束, 保证:
 * <ul>
 *   <li>qty = 0 → ConstraintViolation (之前可静默 0 入库)</li>
 *   <li>qty = -5 → ConstraintViolation</li>
 *   <li>qty = 0.01 → pass (边界最小合法值)</li>
 *   <li>qty = 正常值 → pass (happy path)</li>
 * </ul>
 *
 * <p>Controller 已有 {@code @Valid @RequestBody} — 框架将把 ConstraintViolation 转为
 * 422 (或 400, 视 GlobalExceptionHandler 配置), 不再返回 200 静默接收。
 *
 * @see CreateReceiveRecordRequest
 * @see CreateDeliveryRequest
 * @see CreateReturnOrderRequest
 * @see CreateTransferRequest
 */
@DisplayName("收货/发货/退货/调拨 数量 @DecimalMin(0.01) 级联校验")
class ReceiveDeliveryQtyValidationTest {

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
    // 收货 — CreateReceiveRecordRequest.ReceiveItemDTO.receivedQuantity
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("收货: qty=0 → ConstraintViolation (P2 core bug: 之前静默入库)")
    void receive_zeroQty_violatesDecimalMin() {
        CreateReceiveRecordRequest req = validReceiveRequest(BigDecimal.ZERO);

        Set<ConstraintViolation<CreateReceiveRecordRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=0 应触发 @DecimalMin(0.01) on items[0].receivedQuantity")
                .anyMatch(v -> v.getPropertyPath().toString().contains("receivedQuantity")
                        && v.getMessage().contains("收货数量必须大于0"));
    }

    @Test
    @DisplayName("收货: qty=-5 → ConstraintViolation")
    void receive_negativeQty_violatesDecimalMin() {
        CreateReceiveRecordRequest req = validReceiveRequest(new BigDecimal("-5"));

        Set<ConstraintViolation<CreateReceiveRecordRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=-5 应触发 @DecimalMin(0.01)")
                .anyMatch(v -> v.getPropertyPath().toString().contains("receivedQuantity"));
    }

    @Test
    @DisplayName("收货: qty=0.01 → 通过 (边界最小合法值)")
    void receive_minBoundaryQty_passes() {
        CreateReceiveRecordRequest req = validReceiveRequest(new BigDecimal("0.01"));

        Set<ConstraintViolation<CreateReceiveRecordRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=0.01 是合法的最小值, 不应有 receivedQuantity 违规")
                .noneMatch(v -> v.getPropertyPath().toString().contains("receivedQuantity"));
    }

    @Test
    @DisplayName("收货: qty=100 → 通过 (happy path)")
    void receive_normalQty_passes() {
        CreateReceiveRecordRequest req = validReceiveRequest(new BigDecimal("100"));

        Set<ConstraintViolation<CreateReceiveRecordRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=100 happy path, 不应触发 receivedQuantity 校验")
                .noneMatch(v -> v.getPropertyPath().toString().contains("receivedQuantity"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 发货 — CreateDeliveryRequest.DeliveryItemDTO.deliveredQuantity
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("发货: qty=0 → ConstraintViolation")
    void delivery_zeroQty_violatesDecimalMin() {
        CreateDeliveryRequest req = validDeliveryRequest(BigDecimal.ZERO);

        Set<ConstraintViolation<CreateDeliveryRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=0 应触发 @DecimalMin(0.01) on items[0].deliveredQuantity")
                .anyMatch(v -> v.getPropertyPath().toString().contains("deliveredQuantity")
                        && v.getMessage().contains("发货数量必须大于0"));
    }

    @Test
    @DisplayName("发货: qty=50 → 通过 (happy path)")
    void delivery_normalQty_passes() {
        CreateDeliveryRequest req = validDeliveryRequest(new BigDecimal("50"));

        Set<ConstraintViolation<CreateDeliveryRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=50 不应触发 deliveredQuantity 校验")
                .noneMatch(v -> v.getPropertyPath().toString().contains("deliveredQuantity"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 退货 — CreateReturnOrderRequest.ReturnOrderItemDTO.quantity
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("退货: qty=0 → ConstraintViolation")
    void returnOrder_zeroQty_violatesDecimalMin() {
        CreateReturnOrderRequest req = validReturnOrderRequest(BigDecimal.ZERO);

        Set<ConstraintViolation<CreateReturnOrderRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=0 应触发 @DecimalMin(0.01) on items[0].quantity")
                .anyMatch(v -> v.getPropertyPath().toString().contains("quantity")
                        && v.getMessage().contains("退货数量必须大于0"));
    }

    @Test
    @DisplayName("退货: qty=10 → 通过 (happy path)")
    void returnOrder_normalQty_passes() {
        CreateReturnOrderRequest req = validReturnOrderRequest(new BigDecimal("10"));

        Set<ConstraintViolation<CreateReturnOrderRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=10 不应触发 quantity 校验")
                .noneMatch(v -> v.getPropertyPath().toString().contains("quantity"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 调拨 — CreateTransferRequest.TransferItemDTO.quantity
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("调拨: qty=0 → ConstraintViolation")
    void transfer_zeroQty_violatesDecimalMin() {
        CreateTransferRequest req = validTransferRequest(BigDecimal.ZERO);

        Set<ConstraintViolation<CreateTransferRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=0 应触发 @DecimalMin(0.01) on items[0].quantity")
                .anyMatch(v -> v.getPropertyPath().toString().contains("quantity")
                        && v.getMessage().contains("调拨数量必须大于0"));
    }

    @Test
    @DisplayName("调拨: qty=20 → 通过 (happy path)")
    void transfer_normalQty_passes() {
        CreateTransferRequest req = validTransferRequest(new BigDecimal("20"));

        Set<ConstraintViolation<CreateTransferRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("qty=20 不应触发 quantity 校验")
                .noneMatch(v -> v.getPropertyPath().toString().contains("quantity"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Builder helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static CreateReceiveRecordRequest validReceiveRequest(BigDecimal qty) {
        CreateReceiveRecordRequest.ReceiveItemDTO item = new CreateReceiveRecordRequest.ReceiveItemDTO();
        item.setMaterialTypeId("MT-001");
        item.setReceivedQuantity(qty);
        item.setUnit("kg");

        CreateReceiveRecordRequest req = new CreateReceiveRecordRequest();
        req.setSupplierId("SUP-001");
        req.setReceiveDate(LocalDate.now());
        req.setItems(List.of(item));
        return req;
    }

    private static CreateDeliveryRequest validDeliveryRequest(BigDecimal qty) {
        CreateDeliveryRequest.DeliveryItemDTO item = new CreateDeliveryRequest.DeliveryItemDTO();
        item.setProductTypeId("PT-001");
        item.setDeliveredQuantity(qty);
        item.setUnit("箱");

        CreateDeliveryRequest req = new CreateDeliveryRequest();
        req.setCustomerId("CUST-001");
        req.setDeliveryDate(LocalDate.now());
        req.setItems(List.of(item));
        return req;
    }

    private static CreateReturnOrderRequest validReturnOrderRequest(BigDecimal qty) {
        CreateReturnOrderRequest.ReturnOrderItemDTO item = new CreateReturnOrderRequest.ReturnOrderItemDTO();
        item.setMaterialTypeId("MT-001");
        item.setQuantity(qty);

        CreateReturnOrderRequest req = new CreateReturnOrderRequest();
        req.setReturnType("PURCHASE_RETURN");
        req.setCounterpartyId("SUP-001");
        req.setReturnDate(LocalDate.now());
        req.setItems(List.of(item));
        return req;
    }

    private static CreateTransferRequest validTransferRequest(BigDecimal qty) {
        CreateTransferRequest.TransferItemDTO item = new CreateTransferRequest.TransferItemDTO();
        item.setItemType("RAW_MATERIAL");
        item.setMaterialTypeId("MT-001");
        item.setQuantity(qty);
        item.setUnit("kg");

        CreateTransferRequest req = new CreateTransferRequest();
        req.setTransferType("INTERNAL");
        req.setTargetFactoryId("F002");
        req.setTransferDate(LocalDate.now());
        req.setItems(List.of(item));
        return req;
    }
}
