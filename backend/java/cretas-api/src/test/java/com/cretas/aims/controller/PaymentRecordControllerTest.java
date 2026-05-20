package com.cretas.aims.controller;

import com.cretas.aims.controller.finance.PaymentRecordController;
import com.cretas.aims.entity.finance.PaymentRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PaymentRecordRepository;
import com.cretas.aims.service.finance.PaymentRecordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

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
 * Length-validation tests for {@link PaymentRecordController} — AUD-5 B-A3 sister sweep
 * batch 2 (edge audit 2026-05-20).
 *
 * <p>Each test asserts that an over-length string field is rejected with a specific
 * Chinese message + 4-in-1 UX hint (per {@code .claude/rules/fool-proof-design.md}
 * cross-cutting rule) BEFORE the request reaches {@link PaymentRecordService#recordPayment}
 * — guaranteeing PG VARCHAR overflow can't surface as generic 409 "数据处理异常".
 *
 * <p>Mirrors PR #48 (CanvasAlertController.ruleName) and PR #76 (Pricing/Cron) patterns.
 *
 * @since 2026-05-20 (AUD-5 B-A3 sister sweep batch 2)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentRecordController AUD-5 B-A3 length pre-check")
class PaymentRecordControllerTest {

    @Mock PaymentRecordService paymentRecordService;
    @Mock PaymentRecordRepository paymentRecordRepository;
    @InjectMocks PaymentRecordController controller;

    // ==================== AUD-5 B-A3: paymentReference > 200 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: paymentReference > 200 字符 → 400 with 4-in-1 hint (非 PG 409)")
    void testLongPaymentReferenceRejected() {
        // PG column: payment_records.payment_reference VARCHAR(200). Pre-fix, a 201-char
        // input let the request reach PG and surfaced as DataIntegrityViolationException →
        // generic 409 "数据处理异常". Pre-check delivers specific 400 with current vs
        // max length so user can immediately spot the issue.
        Map<String, Object> body = newBaseBody();
        body.put("paymentReference", "X".repeat(201));   // 201 chars, max is 200

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.recordPayment("F001", body, 1L),
                "201-char paymentReference 必须 reject");

        assertEquals(400, ex.getCode(), "must be 400, not 409 PG overflow");
        assertTrue(ex.getMessage().contains("银行流水号"),
                "message must reference the field name: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("200"),
                "message must include the limit (200): " + ex.getMessage());
        assertTrue(ex.getMessage().contains("201"),
                "message must include the current length (201): " + ex.getMessage());
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity(), "4-in-1 UX (c): severity warning");
        assertEquals("paymentReference", ex.getHintTarget(),
                "4-in-1 UX (d): hintTarget must point at paymentReference");
        verify(paymentRecordService, never()).recordPayment(anyString(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    // ==================== AUD-5 B-A3: receiptUrl > 500 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: receiptUrl > 500 字符 → 400 with 4-in-1 hint")
    void testLongReceiptUrlRejected() {
        // PG column: payment_records.receipt_url VARCHAR(500).
        Map<String, Object> body = newBaseBody();
        body.put("receiptUrl", "https://example.com/" + "x".repeat(490));   // > 500

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.recordPayment("F001", body, 1L));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("凭证URL"));
        assertTrue(ex.getMessage().contains("500"));
        assertNotNull(ex.getActionHint());
        assertEquals("warning", ex.getSeverity());
        assertEquals("receiptUrl", ex.getHintTarget());
        verify(paymentRecordService, never()).recordPayment(anyString(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    // ==================== Boundary: exactly at limit ====================

    @Test
    @DisplayName("AUD-5 B-A3 boundary: paymentReference at exactly 200 字符 accepted")
    void testMaxLengthPaymentReferenceAccepted() {
        Map<String, Object> body = newBaseBody();
        body.put("paymentReference", "X".repeat(200));   // exactly at limit

        // Stub: return a non-null PaymentRecord so the controller's Map.of(...) doesn't NPE.
        when(paymentRecordService.recordPayment(anyString(), any(),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PaymentRecord());

        ResponseEntity<?> resp = controller.recordPayment("F001", body, 1L);
        assertEquals(200, resp.getStatusCode().value(), "200-char should pass length check");
        verify(paymentRecordService).recordPayment(anyString(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    // ==================== Rule 16: update-equivalent fields covered ====================
    // Note: PaymentRecordController has no separate "update" endpoint for these fields —
    // verify, reject, listX all take different inputs. recordPayment is the only entry
    // point that ingests paymentReference + receiptUrl. So Rule 16 entry-matrix is
    // automatically satisfied (single entry).

    // ==================== Regression: existing null guards still fire ====================

    @Test
    @DisplayName("Pre-existing: missing salesOrderId still returns 400 (regression guard)")
    void testMissingSalesOrderIdStillRejected() {
        Map<String, Object> body = newBaseBody();
        body.remove("salesOrderId");

        ResponseEntity<?> resp = controller.recordPayment("F001", body, 1L);
        assertEquals(400, resp.getStatusCode().value());
        verify(paymentRecordService, never()).recordPayment(anyString(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    // ==================== Helpers ====================

    private Map<String, Object> newBaseBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("salesOrderId", "SO-001");
        body.put("amount", "1000.00");
        body.put("paymentMethod", "BANK_TRANSFER");
        return body;
    }
}
