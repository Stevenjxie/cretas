package com.cretas.aims.controller;

import com.cretas.aims.controller.finance.ArApController;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.finance.RecordTransactionRequest;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.finance.ArApService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

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
 * Length-validation tests for {@link ArApController} — AUD-5 B-A3 sister sweep
 * batch 3 (edge audit 2026-05-20).
 *
 * <p>Each test asserts that an over-length string field is rejected with a specific
 * Chinese message + 4-in-1 UX hint (per {@code .claude/rules/fool-proof-design.md}
 * cross-cutting rule) BEFORE the request reaches {@link ArApService}
 * — guaranteeing PG VARCHAR overflow can't surface as generic 409 "数据处理异常".
 *
 * <p>The typed DTO {@link RecordTransactionRequest} has no {@code @Size} annotations,
 * so controller-side pre-check is the safety net for {@code paymentReference} (100)
 * and {@code remark} (500).
 *
 * <p>All 5 write entry points (receivable / receivable-payment / payable / payable-payment /
 * adjustment) share a single {@code validateRequestLengths} helper — Rule 16 entry-point matrix.
 *
 * <p>Mirrors PR #48 / PR #76 / PR #78 length-pre-check pattern.
 *
 * @since 2026-05-20 (AUD-5 B-A3 sister sweep batch 3)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArApController AUD-5 B-A3 length pre-check")
class ArApControllerTest {

    @Mock ArApService arApService;
    @Mock MobileService mobileService;
    @Mock FactoryConfigService factoryConfigService;
    @InjectMocks ArApController controller;

    // ==================== AUD-5 B-A3: paymentReference > 100 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: AR payment.paymentReference > 100 字符 → 400 with 4-in-1 hint")
    void testLongPaymentReferenceOnArPaymentRejected() {
        // PG column: ar_ap_transactions.payment_reference VARCHAR(100). Pre-fix, a 101-char
        // input let the request reach PG and surfaced as DataIntegrityViolationException → 409.
        RecordTransactionRequest req = newBaseRequest();
        req.setPaymentReference("X".repeat(101));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.recordArPayment("F001", "Bearer token", req));

        assertEquals(400, ex.getCode(), "must be 400, not 409 PG overflow");
        assertTrue(ex.getMessage().contains("付款凭证号"),
                "message must reference field: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("100"));
        assertTrue(ex.getMessage().contains("101"));
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity(), "4-in-1 UX (c): severity warning");
        assertEquals("paymentReference", ex.getHintTarget());
        verify(arApService, never()).recordArPayment(anyString(), anyString(), any(),
                any(BigDecimal.class), any(PaymentMethod.class), anyString(), any(), any());
    }

    // ==================== AUD-5 B-A3: remark > 500 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: receivable.remark > 500 字符 → 400")
    void testLongRemarkOnReceivableRejected() {
        // PG column: ar_ap_transactions.remark VARCHAR(500).
        RecordTransactionRequest req = newBaseRequest();
        req.setRemark("X".repeat(501));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.recordReceivable("F001", "Bearer token", req));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("备注"));
        assertTrue(ex.getMessage().contains("500"));
        assertEquals("remark", ex.getHintTarget());
        verify(arApService, never()).recordReceivable(anyString(), anyString(), any(),
                any(BigDecimal.class), any(LocalDate.class), any(), anyString());
    }

    // ==================== Rule 16: payable path mirrors receivable ====================

    @Test
    @DisplayName("Rule 16: payable.paymentReference > 100 字符 → 400 (entry-point matrix)")
    void testLongPaymentReferenceOnPayableRejected() {
        RecordTransactionRequest req = newBaseRequest();
        req.setPaymentReference("X".repeat(150));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.recordPayable("F001", "Bearer token", req));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("付款凭证号"));
        assertEquals("paymentReference", ex.getHintTarget());
        verify(arApService, never()).recordPayable(anyString(), anyString(), any(),
                any(BigDecimal.class), any(LocalDate.class), any(), anyString());
    }

    // ==================== Rule 16: AP payment path mirrors AR payment ====================

    @Test
    @DisplayName("Rule 16: AP payment.remark > 500 字符 → 400 (entry-point matrix)")
    void testLongRemarkOnApPaymentRejected() {
        RecordTransactionRequest req = newBaseRequest();
        req.setRemark("X".repeat(600));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.recordApPayment("F001", "Bearer token", req));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("备注"));
        assertEquals("remark", ex.getHintTarget());
        verify(arApService, never()).recordApPayment(anyString(), anyString(),
                any(BigDecimal.class), any(PaymentMethod.class), anyString(), any(), anyString());
    }

    // ==================== Rule 16: adjustment path ====================

    @Test
    @DisplayName("Rule 16: adjustment.paymentReference > 100 字符 → 400 (entry-point matrix)")
    void testLongPaymentReferenceOnAdjustmentRejected() {
        RecordTransactionRequest req = newBaseRequest();
        req.setPaymentReference("X".repeat(101));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.recordAdjustment("F001", "Bearer token",
                        CounterpartyType.CUSTOMER, req));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("付款凭证号"));
        assertEquals("paymentReference", ex.getHintTarget());
        verify(arApService, never()).recordAdjustment(anyString(), any(CounterpartyType.class),
                anyString(), any(BigDecimal.class), any(), anyString());
    }

    // ==================== Boundary: exactly at limit ====================

    @Test
    @DisplayName("AUD-5 B-A3 boundary: paymentReference at exactly 100 字符 accepted")
    void testMaxLengthPaymentReferenceAccepted() {
        RecordTransactionRequest req = newBaseRequest();
        req.setPaymentReference("X".repeat(100));   // exactly at limit
        com.cretas.aims.dto.user.UserDTO user = new com.cretas.aims.dto.user.UserDTO();
        user.setId(1L);
        when(mobileService.getUserFromToken(any())).thenReturn(user);
        // userId getter on User returns null by default; cast handles that
        when(arApService.recordArPayment(anyString(), anyString(), any(),
                any(BigDecimal.class), any(PaymentMethod.class), anyString(), any(), any()))
                .thenReturn(new ArApTransaction());

        ApiResponse<ArApTransaction> resp = controller.recordArPayment("F001", "Bearer token", req);
        assertNotNull(resp);
        verify(arApService).recordArPayment(anyString(), anyString(), any(),
                any(BigDecimal.class), any(PaymentMethod.class), anyString(), any(), any());
    }

    @Test
    @DisplayName("AUD-5 B-A3: null paymentReference + null remark tolerated (optional fields)")
    void testNullOptionalFieldsAccepted() {
        RecordTransactionRequest req = newBaseRequest();
        req.setPaymentReference(null);
        req.setRemark(null);
        com.cretas.aims.dto.user.UserDTO user = new com.cretas.aims.dto.user.UserDTO();
        user.setId(1L);
        when(mobileService.getUserFromToken(any())).thenReturn(user);
        when(arApService.recordArPayment(anyString(), anyString(), any(),
                any(BigDecimal.class), any(PaymentMethod.class), any(), any(), any()))
                .thenReturn(new ArApTransaction());

        ApiResponse<ArApTransaction> resp = controller.recordArPayment("F001", "Bearer token", req);
        assertNotNull(resp);
    }

    // ==================== Helpers ====================

    private RecordTransactionRequest newBaseRequest() {
        RecordTransactionRequest req = new RecordTransactionRequest();
        req.setCounterpartyId("CUST-001");
        req.setAmount(new BigDecimal("1000.00"));
        req.setDueDate(LocalDate.now().plusDays(30));
        req.setPaymentMethod("BANK_TRANSFER");
        req.setPaymentReference("REF-001");
        req.setRemark("test");
        return req;
    }
}
