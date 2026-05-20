package com.cretas.aims.controller;

import com.cretas.aims.controller.hr.OvertimeRequestController;
import com.cretas.aims.entity.hr.OvertimeRequest;
import com.cretas.aims.entity.hr.enums.CompensationType;
import com.cretas.aims.entity.hr.enums.OvertimeType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.hr.OvertimeRequestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * Length-validation tests for {@link OvertimeRequestController} — AUD-5 B-A3 sister sweep
 * batch 3 (edge audit 2026-05-20).
 *
 * <p>Each test asserts that an over-length string field is rejected with a specific
 * Chinese message + 4-in-1 UX hint (per {@code .claude/rules/fool-proof-design.md}
 * cross-cutting rule) BEFORE the request reaches {@link OvertimeRequestService}
 * — guaranteeing PG VARCHAR overflow can't surface as generic 409 "数据处理异常".
 *
 * <p>Mirrors PR #48 (CanvasAlertController.ruleName), PR #76 (Pricing/Cron),
 * PR #78 (PaymentRecord/Leave/Expense) patterns.
 *
 * @since 2026-05-20 (AUD-5 B-A3 sister sweep batch 3)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OvertimeRequestController AUD-5 B-A3 length pre-check")
class OvertimeRequestControllerTest {

    @Mock OvertimeRequestService service;
    @InjectMocks OvertimeRequestController controller;

    // ==================== AUD-5 B-A3: create.reason > 1000 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: create.reason > 1000 字符 → 400 with 4-in-1 hint (非 PG 409)")
    void testLongReasonOnCreateRejected() {
        // PG column: overtime_requests.reason VARCHAR(1000). Pre-fix, a 1001-char input let
        // the request reach PG and surfaced as DataIntegrityViolationException → 409.
        Map<String, Object> body = newBaseBody();
        body.put("reason", "X".repeat(1001));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", 1L, body));

        assertEquals(400, ex.getCode(), "must be 400, not 409 PG overflow");
        assertTrue(ex.getMessage().contains("加班原因"),
                "message must reference field: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("1000"));
        assertTrue(ex.getMessage().contains("1001"));
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity(), "4-in-1 UX (c): severity warning");
        assertEquals("reason", ex.getHintTarget(), "4-in-1 UX (d): hintTarget = reason");
        verify(service, never()).create(anyString(), any(), any(OvertimeType.class),
                any(LocalDateTime.class), any(LocalDateTime.class), any(BigDecimal.class),
                any(CompensationType.class), anyString());
    }

    // ==================== Rule 16: update path mirrors create ====================

    @Test
    @DisplayName("Rule 16: update.reason > 1000 字符 → 400 (entry-point matrix)")
    void testLongReasonOnUpdateRejected() {
        Map<String, Object> body = newBaseBody();
        body.put("reason", "X".repeat(1500));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update("F001", "OVT-001", 1L, body));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("加班原因"));
        assertEquals("reason", ex.getHintTarget());
        verify(service, never()).update(anyString(), any(), anyString(), any(OvertimeType.class),
                any(LocalDateTime.class), any(LocalDateTime.class), any(BigDecimal.class),
                any(CompensationType.class), anyString());
    }

    // ==================== AUD-5 B-A3: reject.reason > 500 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: reject.reason > 500 字符 → 400")
    void testLongRejectReasonRejected() {
        // PG column: overtime_requests.reject_reason VARCHAR(500), tighter than reason (1000).
        Map<String, String> body = new HashMap<>();
        body.put("reason", "X".repeat(501));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.reject("F001", "OVT-001", 1L, body));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("驳回原因"));
        assertTrue(ex.getMessage().contains("500"));
        assertTrue(ex.getMessage().contains("501"));
        assertEquals("reason", ex.getHintTarget());
        verify(service, never()).reject(anyString(), any(), anyString(), anyString());
    }

    // ==================== Boundary: exactly at limit ====================

    @Test
    @DisplayName("AUD-5 B-A3 boundary: create.reason at exactly 1000 字符 accepted")
    void testMaxLengthReasonAccepted() {
        Map<String, Object> body = newBaseBody();
        body.put("reason", "X".repeat(1000));   // exactly at limit
        when(service.create(anyString(), any(), any(OvertimeType.class),
                any(LocalDateTime.class), any(LocalDateTime.class), any(BigDecimal.class),
                any(CompensationType.class), anyString())).thenReturn(new OvertimeRequest());

        ResponseEntity<?> resp = controller.create("F001", 1L, body);
        assertEquals(200, resp.getStatusCode().value());
        verify(service).create(anyString(), any(), any(OvertimeType.class),
                any(LocalDateTime.class), any(LocalDateTime.class), any(BigDecimal.class),
                any(CompensationType.class), anyString());
    }

    @Test
    @DisplayName("AUD-5 B-A3: null reason tolerated (optional field)")
    void testNullReasonAccepted() {
        Map<String, Object> body = newBaseBody();
        body.remove("reason");
        when(service.create(anyString(), any(), any(OvertimeType.class),
                any(LocalDateTime.class), any(LocalDateTime.class), any(BigDecimal.class),
                any(CompensationType.class), any())).thenReturn(new OvertimeRequest());

        ResponseEntity<?> resp = controller.create("F001", 1L, body);
        assertEquals(200, resp.getStatusCode().value());
    }

    // ==================== Helpers ====================

    private Map<String, Object> newBaseBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("overtimeType", "WEEKDAY");
        body.put("startTime", "2026-06-01T18:00:00");
        body.put("endTime", "2026-06-01T22:00:00");
        body.put("hours", "4.0");
        body.put("compensationType", "CASH");
        return body;
    }
}
