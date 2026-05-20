package com.cretas.aims.controller;

import com.cretas.aims.controller.warehouse.ReusableContainerController;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.warehouse.ReusableContainer;
import com.cretas.aims.entity.warehouse.ReusableContainerTransaction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.warehouse.ReusableContainerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Length-validation tests for {@link ReusableContainerController} — AUD-5 B-A3 sister sweep
 * batch 3 (edge audit 2026-05-20).
 *
 * <p>Each test asserts that an over-length string field is rejected with a specific
 * Chinese message + 4-in-1 UX hint (per {@code .claude/rules/fool-proof-design.md}
 * cross-cutting rule) BEFORE the request reaches {@link ReusableContainerService}
 * — guaranteeing PG VARCHAR overflow can't surface as generic 409 "数据处理异常".
 *
 * <p>Covers both entity-level fields ({@code containerCode/Name/specification/remark} on create)
 * and transaction-level fields ({@code customerName/remark} on ship/return/loss — Rule 16
 * entry-point matrix).
 *
 * <p>Mirrors PR #48 / PR #76 / PR #78 length-pre-check pattern.
 *
 * @since 2026-05-20 (AUD-5 B-A3 sister sweep batch 3)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableContainerController AUD-5 B-A3 length pre-check")
class ReusableContainerControllerTest {

    @Mock ReusableContainerService service;
    @InjectMocks ReusableContainerController controller;

    // ==================== AUD-5 B-A3: create.containerCode > 64 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: create.containerCode > 64 字符 → 400 with 4-in-1 hint")
    void testLongContainerCodeOnCreateRejected() {
        // PG column: reusable_containers.container_code VARCHAR(64).
        ReusableContainer dto = new ReusableContainer();
        dto.setContainerCode("X".repeat(65));
        dto.setContainerName("筐子");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", dto));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("周转耗材编号"));
        assertTrue(ex.getMessage().contains("64"));
        assertTrue(ex.getMessage().contains("65"));
        assertNotNull(ex.getActionHint());
        assertEquals("warning", ex.getSeverity());
        assertEquals("containerCode", ex.getHintTarget());
        verify(service, never()).createContainer(anyString(), any(ReusableContainer.class));
    }

    @Test
    @DisplayName("AUD-5 B-A3: create.containerName > 128 字符 → 400")
    void testLongContainerNameOnCreateRejected() {
        ReusableContainer dto = new ReusableContainer();
        dto.setContainerCode("C-001");
        dto.setContainerName("X".repeat(129));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", dto));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("周转耗材名称"));
        assertEquals("containerName", ex.getHintTarget());
        verify(service, never()).createContainer(anyString(), any(ReusableContainer.class));
    }

    @Test
    @DisplayName("AUD-5 B-A3: create.remark > 500 字符 → 400")
    void testLongContainerRemarkOnCreateRejected() {
        ReusableContainer dto = new ReusableContainer();
        dto.setContainerCode("C-001");
        dto.setContainerName("筐子");
        dto.setRemark("X".repeat(501));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", dto));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("备注"));
        assertEquals("remark", ex.getHintTarget());
        verify(service, never()).createContainer(anyString(), any(ReusableContainer.class));
    }

    // ==================== Rule 16: ship-out path ====================

    @Test
    @DisplayName("Rule 16: ship-out.customerName > 128 字符 → 400")
    void testLongCustomerNameOnShipOutRejected() {
        Map<String, Object> body = newShipBody();
        body.put("customerName", "X".repeat(129));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.shipOut("F001", "C-001", body));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("客户名称"));
        assertTrue(ex.getMessage().contains("128"));
        assertEquals("customerName", ex.getHintTarget());
        verify(service, never()).shipOut(anyString(), anyString(), any(),
                anyString(), anyString(), anyString(), anyString());
    }

    // ==================== Rule 16: return-in path ====================

    @Test
    @DisplayName("Rule 16: return-in.remark > 500 字符 → 400 (entry-point matrix)")
    void testLongRemarkOnReturnInRejected() {
        Map<String, Object> body = newShipBody();
        body.put("remark", "X".repeat(501));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.returnIn("F001", "C-001", body));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("备注"));
        assertEquals("remark", ex.getHintTarget());
        verify(service, never()).returnIn(anyString(), anyString(), any(),
                anyString(), anyString(), anyString());
    }

    // ==================== Rule 16: loss path ====================

    @Test
    @DisplayName("Rule 16: loss.customerName > 128 字符 → 400 (entry-point matrix)")
    void testLongCustomerNameOnLossRejected() {
        Map<String, Object> body = newShipBody();
        body.put("customerName", "X".repeat(200));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.loss("F001", "C-001", body));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("客户名称"));
        assertEquals("customerName", ex.getHintTarget());
        verify(service, never()).markLoss(anyString(), anyString(), any(),
                anyString(), anyString(), any(BigDecimal.class), anyString());
    }

    // ==================== Boundary: exactly at limit accepted ====================

    @Test
    @DisplayName("AUD-5 B-A3 boundary: create.containerCode at exactly 64 字符 accepted")
    void testMaxLengthContainerCodeAccepted() {
        ReusableContainer dto = new ReusableContainer();
        dto.setContainerCode("X".repeat(64));
        dto.setContainerName("筐子");
        when(service.createContainer(anyString(), any(ReusableContainer.class)))
                .thenReturn(dto);

        ApiResponse<ReusableContainer> resp = controller.create("F001", dto);
        assertNotNull(resp);
        verify(service).createContainer(anyString(), any(ReusableContainer.class));
    }

    @Test
    @DisplayName("AUD-5 B-A3: ship-out with null customerName tolerated (optional field)")
    void testNullCustomerNameOnShipOutAccepted() {
        Map<String, Object> body = newShipBody();
        body.remove("customerName");
        when(service.shipOut(anyString(), anyString(), any(), anyString(), any(), anyString(), any()))
                .thenReturn(new ReusableContainerTransaction());

        ApiResponse<ReusableContainerTransaction> resp = controller.shipOut("F001", "C-001", body);
        assertNotNull(resp);
    }

    // ==================== Helpers ====================

    private Map<String, Object> newShipBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", 5);
        body.put("customerId", "CUST-001");
        body.put("customerName", "客户A");
        body.put("salesDeliveryId", "DLV-001");
        body.put("remark", "备注");
        return body;
    }
}
