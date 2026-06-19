package com.cretas.aims.controller;

import com.cretas.aims.controller.hr.LeaveRequestController;
import com.cretas.aims.entity.hr.LeaveRequest;
import com.cretas.aims.entity.hr.enums.LeaveType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.hr.LeaveRequestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * Length-validation tests for {@link LeaveRequestController} — AUD-5 B-A3 sister sweep
 * batch 2 (edge audit 2026-05-20).
 *
 * <p>Each test asserts that an over-length string field is rejected with a specific
 * Chinese message + 4-in-1 UX hint (per {@code .claude/rules/fool-proof-design.md}
 * cross-cutting rule) BEFORE the request reaches {@link LeaveRequestService}
 * — guaranteeing PG VARCHAR overflow can't surface as generic 409 "数据处理异常".
 *
 * <p>Mirrors PR #48 (CanvasAlertController.ruleName) and PR #76 (Pricing/Cron) patterns.
 *
 * @since 2026-05-20 (AUD-5 B-A3 sister sweep batch 2)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LeaveRequestController AUD-5 B-A3 length pre-check")
class LeaveRequestControllerTest {

    @Mock LeaveRequestService service;
    @InjectMocks LeaveRequestController controller;

    // ==================== AUD-5 B-A3: create.reason > 1000 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: create.reason > 1000 字符 → 400 with 4-in-1 hint (非 PG 409)")
    void testLongReasonOnCreateRejected() {
        // PG column: leave_requests.reason VARCHAR(1000). Pre-fix, a 1001-char input let
        // the request reach PG and surfaced as DataIntegrityViolationException → 409.
        Map<String, Object> body = newBaseBody();
        body.put("reason", "X".repeat(1001));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", 1L, body));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("请假原因"));
        assertTrue(ex.getMessage().contains("1000"));
        assertTrue(ex.getMessage().contains("1001"));
        assertNotNull(ex.getActionHint());
        assertEquals("warning", ex.getSeverity());
        assertEquals("reason", ex.getHintTarget());
        verify(service, never()).create(anyString(), any(), any(LeaveType.class),
                any(LocalDate.class), any(LocalDate.class), any(BigDecimal.class), anyString());
    }

    // ==================== Rule 16: update path mirrors create ====================

    @Test
    @DisplayName("Rule 16: update.reason > 1000 字符 → 400 (entry-point matrix)")
    void testLongReasonOnUpdateRejected() {
        Map<String, Object> body = newBaseBody();
        body.put("reason", "X".repeat(1500));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update("F001", "L-001", 1L, body));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("请假原因"));
        assertEquals("reason", ex.getHintTarget());
        verify(service, never()).update(anyString(), any(), anyString(), any(LeaveType.class),
                any(LocalDate.class), any(LocalDate.class), any(BigDecimal.class), anyString());
    }

    // ==================== AUD-5 B-A3: reject.reason > 500 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: reject.reason > 500 字符 → 400")
    void testLongRejectReasonRejected() {
        // PG column: leave_requests.reject_reason VARCHAR(500), tighter than reason (1000).
        Map<String, String> body = new HashMap<>();
        body.put("reason", "X".repeat(501));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.reject("F001", "L-001", 1L, body));

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
        // Stub: return a non-null LeaveRequest so the controller's Map.of(...) doesn't NPE.
        when(service.create(anyString(), any(), any(LeaveType.class), any(LocalDate.class),
                any(LocalDate.class), any(BigDecimal.class), anyString())).thenReturn(new LeaveRequest());

        ResponseEntity<?> resp = controller.create("F001", 1L, body);
        assertEquals(200, resp.getStatusCode().value());
        verify(service).create(anyString(), any(), any(LeaveType.class), any(LocalDate.class),
                any(LocalDate.class), any(BigDecimal.class), anyString());
    }

    @Test
    @DisplayName("缺 leaveType → 400 + 字段提示 (非 raw Map valueOf(null) 的 500 NPE)")
    void testMissingLeaveTypeReturns400NotNpe() {
        Map<String, Object> body = newBaseBody();
        body.remove("leaveType");   // 必填枚举缺失 — 旧代码 valueOf(null) → NPE → 500
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", 1L, body));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("请假类型"),
                "400 message must name the missing field, was: " + ex.getMessage());
        verify(service, never()).create(anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("缺 startDate → 400 (非 LocalDate.parse(null) 的 500)")
    void testMissingStartDateReturns400NotNpe() {
        Map<String, Object> body = newBaseBody();
        body.remove("startDate");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", 1L, body));
        assertEquals(400, ex.getCode());
        verify(service, never()).create(anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("缺 durationHours → 400 (非 body.get(...).toString() 的 500)")
    void testMissingDurationReturns400NotNpe() {
        Map<String, Object> body = newBaseBody();
        body.remove("durationHours");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", 1L, body));
        assertEquals(400, ex.getCode());
        verify(service, never()).create(anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AUD-5 B-A3: null reason tolerated (optional field)")
    void testNullReasonAccepted() {
        Map<String, Object> body = newBaseBody();
        body.remove("reason");   // explicitly absent
        when(service.create(anyString(), any(), any(LeaveType.class), any(LocalDate.class),
                any(LocalDate.class), any(BigDecimal.class), any())).thenReturn(new LeaveRequest());

        ResponseEntity<?> resp = controller.create("F001", 1L, body);
        assertEquals(200, resp.getStatusCode().value());
        verify(service).create(anyString(), any(), any(LeaveType.class), any(LocalDate.class),
                any(LocalDate.class), any(BigDecimal.class), any());
    }

    // ==================== Helpers ====================

    private Map<String, Object> newBaseBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("leaveType", "PERSONAL");   // any valid LeaveType
        body.put("startDate", "2026-06-01");
        body.put("endDate", "2026-06-03");
        body.put("durationHours", "16.0");
        return body;
    }
}
