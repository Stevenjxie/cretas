package com.cretas.aims.controller;

import com.cretas.aims.controller.sales.OperationalQuoteController;
import com.cretas.aims.entity.sales.OperationalQuote;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.sales.OperationalQuoteRepository;
import com.cretas.aims.service.sales.OperationalQuoteService;
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
 * Length-validation tests for {@link OperationalQuoteController} — AUD-5 B-A3 sister sweep
 * batch 2 (edge audit 2026-05-20).
 *
 * <p>Each test asserts that an over-length string field is rejected with a specific
 * Chinese message + 4-in-1 UX hint (per {@code .claude/rules/fool-proof-design.md}
 * cross-cutting rule) BEFORE the request reaches {@link OperationalQuoteService}
 * — guaranteeing PG VARCHAR overflow can't surface as generic 409 "数据处理异常".
 *
 * <p>Mirrors PR #48 (CanvasAlertController.ruleName) and PR #76 (Pricing/Cron) patterns.
 *
 * @since 2026-05-20 (AUD-5 B-A3 sister sweep batch 2)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OperationalQuoteController AUD-5 B-A3 length pre-check")
class OperationalQuoteControllerTest {

    @Mock OperationalQuoteService quoteService;
    @Mock OperationalQuoteRepository quoteRepository;
    @InjectMocks OperationalQuoteController controller;

    // ==================== AUD-5 B-A3: createQuote.quotedByName > 100 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: createQuote.quotedByName > 100 字符 → 400 with 4-in-1 hint")
    void testLongQuotedByNameRejected() {
        // PG column: operational_quotes.quoted_by_name VARCHAR(100). Pre-fix, a 101-char
        // input let the request reach PG and surfaced as DataIntegrityViolationException →
        // generic 409 "数据处理异常". Pre-check delivers specific 400 with current vs max.
        Map<String, Object> body = newCreateBody();
        body.put("quotedByName", "X".repeat(101));   // 101 chars, max is 100

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createQuote("F001", body, 1L));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("录价人姓名"));
        assertTrue(ex.getMessage().contains("100"));
        assertTrue(ex.getMessage().contains("101"));
        assertNotNull(ex.getActionHint());
        assertEquals("warning", ex.getSeverity());
        assertEquals("quotedByName", ex.getHintTarget());
        verify(quoteService, never()).createQuote(anyString(), any(), anyString(),
                anyString(), any(), any(), any(), any(), any());
    }

    // ==================== AUD-5 B-A3: submitPrice.quoteType > 20 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: submitPrice.quoteType > 20 字符 → 400")
    void testLongQuoteTypeRejected() {
        // PG column: operational_quotes.quote_type VARCHAR(20).
        Map<String, Object> body = new HashMap<>();
        body.put("quoteType", "X".repeat(21));   // 21 chars, max is 20

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.submitPrice("F001", "Q-001", body));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("报价类型"));
        assertTrue(ex.getMessage().contains("20"));
        assertNotNull(ex.getActionHint());
        assertEquals("warning", ex.getSeverity());
        assertEquals("quoteType", ex.getHintTarget());
        verify(quoteService, never()).submitQuote(anyString(), anyString(), anyString(),
                any(BigDecimal.class), anyString(), any(BigDecimal.class), any(BigDecimal.class),
                any(LocalDate.class), anyString());
    }

    @Test
    @DisplayName("AUD-5 B-A3: submitPrice.unit > 20 字符 → 400")
    void testLongUnitRejected() {
        // PG column: operational_quotes.unit VARCHAR(20).
        Map<String, Object> body = new HashMap<>();
        body.put("quoteType", "FIXED");
        body.put("unit", "x".repeat(21));   // 21 chars, > 20

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.submitPrice("F001", "Q-001", body));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("计量单位"));
        assertEquals("unit", ex.getHintTarget());
        verify(quoteService, never()).submitQuote(anyString(), anyString(), anyString(),
                any(BigDecimal.class), anyString(), any(BigDecimal.class), any(BigDecimal.class),
                any(LocalDate.class), anyString());
    }

    // ==================== AUD-5 B-A3: approve.approverName > 100 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: approve.approverName > 100 字符 → 400")
    void testLongApproverNameOnApproveRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("approverName", "X".repeat(101));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.approve("F001", "Q-001", body, 1L));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("审批人姓名"));
        assertEquals("approverName", ex.getHintTarget());
        verify(quoteService, never()).approveQuote(anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("Rule 16: reject.approverName > 100 字符 → 400 (entry-point matrix)")
    void testLongApproverNameOnRejectRejected() {
        // Rule 16: approve + reject are independent code paths — both must validate.
        Map<String, Object> body = new HashMap<>();
        body.put("approverName", "X".repeat(101));
        body.put("reason", "some reason");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.reject("F001", "Q-001", body, 1L));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("审批人姓名"));
        assertEquals("approverName", ex.getHintTarget());
        verify(quoteService, never()).rejectQuote(anyString(), anyString(), any(), any(), any());
    }

    // ==================== Boundary: exactly at limit ====================

    @Test
    @DisplayName("AUD-5 B-A3 boundary: quotedByName at exactly 100 字符 accepted")
    void testMaxLengthQuotedByNameAccepted() {
        Map<String, Object> body = newCreateBody();
        body.put("quotedByName", "X".repeat(100));   // exactly at limit
        // Stub: return a non-null OperationalQuote so the controller's Map.of(...) doesn't NPE.
        when(quoteService.createQuote(anyString(), any(), anyString(), anyString(),
                any(), any(), any(), any(), any())).thenReturn(new OperationalQuote());

        ResponseEntity<?> resp = controller.createQuote("F001", body, 1L);
        assertEquals(200, resp.getStatusCode().value());
        verify(quoteService).createQuote(anyString(), any(), anyString(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AUD-5 B-A3: null approverName tolerated on approve (optional field)")
    void testNullApproverNameAccepted() {
        // approverName is optional — null = not provided. Validation must skip.
        Map<String, Object> body = new HashMap<>();
        // approverName intentionally missing
        when(quoteService.approveQuote(anyString(), anyString(), any(), any(), any()))
                .thenReturn(new OperationalQuote());

        ResponseEntity<?> resp = controller.approve("F001", "Q-001", body, 1L);
        assertEquals(200, resp.getStatusCode().value());
        verify(quoteService).approveQuote(anyString(), anyString(), any(), any(), any());
    }

    // ==================== Helpers ====================

    private Map<String, Object> newCreateBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", "CUST-001");
        body.put("productTypeId", "PT-001");
        return body;
    }
}
