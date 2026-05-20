package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import com.cretas.aims.service.notify.NotifySenderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Length-validation tests for {@link NotifyTemplateController} — AUD-5 B-A3 sister sweep
 * (edge audit 2026-05-20).
 *
 * <p>Scope is narrower than the Pricing / Cron tests: the CRUD endpoints (create / update /
 * delete) in {@code NotifyTemplateController} currently return 501 stubs as their FIRST
 * statement, so any length pre-check on those methods would be unreachable code — they
 * will gain validation when Phase 3 sister chat replaces stubs with real persistence.
 *
 * <p>What IS active and worth gating: {@link NotifyTemplateController#testSend} which
 * actually looks up a template by {@code templateCode}. Pre-fix, an over-length
 * {@code templateCode} would silently miss in the repo lookup (returning 404
 * "通知模板不存在") which masks the real issue (input violates the VARCHAR(100) contract).
 * Post-fix surfaces a specific 400 with the actual vs allowed length.
 *
 * <p>Tests use pure Mockito — no Spring context — and verify the {@code testSend} path
 * never reaches the {@code templateRepo.findByFactoryIdAndTemplateCode} call when length
 * validation fails.
 *
 * @since 2026-05-20 (AUD-5 B-A3 sister sweep)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotifyTemplateController AUD-5 B-A3 length pre-check")
class NotifyTemplateControllerTest {

    @Mock NotifyTemplateRepository templateRepo;
    @Mock NotifySenderRegistry notifySenderRegistry;
    @InjectMocks NotifyTemplateController controller;

    // ==================== AUD-5 B-A3: templateCode > 100 chars in testSend ====================

    @Test
    @DisplayName("AUD-5 B-A3: templateCode > 100 字符 → 400 with specific length hint")
    void testLongTemplateCodeRejected() {
        // PG column: notify_templates.template_code VARCHAR(100). Pre-fix, an over-length
        // input would silently miss in repo lookup → 404 TEMPLATE_NOT_FOUND, masking the
        // real validation issue. Post-fix delivers specific 400 with actual length.
        Map<String, Object> body = new HashMap<>();
        body.put("templateCode", "X".repeat(101));   // 101 chars, max is 100

        ApiResponse<Map<String, Object>> resp = controller.testSend("F001", body);

        assertNotNull(resp);
        assertEquals(400, resp.getCode(), "must be 400, not 404 silent miss");
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("templateCode"),
                "message must reference field name: " + resp.getMessage());
        assertTrue(resp.getMessage().contains("100"),
                "message must include limit (100): " + resp.getMessage());
        assertTrue(resp.getMessage().contains("101"),
                "message must include current length (101): " + resp.getMessage());
        assertNotNull(resp.getActionHint(),
                "4-in-1 UX (a): actionHint required for user next-step");
        assertEquals("warning", resp.getSeverity(), "4-in-1 UX (c): severity warning");
        // CRITICAL: repo lookup must NOT be called — validation fires before lookup
        verify(templateRepo, never()).findByFactoryIdAndTemplateCode(anyString(), anyString());
    }

    @Test
    @DisplayName("AUD-5 B-A3: very long templateCode (500 chars) also rejected")
    void testVeryLongTemplateCodeRejected() {
        // Defense-in-depth: large payloads should fail fast at the boundary.
        Map<String, Object> body = new HashMap<>();
        body.put("templateCode", "Z".repeat(500));

        ApiResponse<Map<String, Object>> resp = controller.testSend("F001", body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("500"),
                "message must include the actual length: " + resp.getMessage());
        verify(templateRepo, never()).findByFactoryIdAndTemplateCode(anyString(), anyString());
    }

    // ==================== Boundary: exactly 100 chars should NOT trip length check ====================

    @Test
    @DisplayName("AUD-5 B-A3 boundary: templateCode at exactly 100 字符 passes length check")
    void testMaxLengthTemplateCodeAccepted() {
        // 100-char templateCode is at-but-not-over the PG VARCHAR(100) cap.
        // Length validation must accept it; the request then proceeds to repo lookup
        // (which mocks an empty Optional → 404 TEMPLATE_NOT_FOUND from the next check).
        // What we care about here: length pre-check did NOT short-circuit at this length.
        String exactlyAtCap = "X".repeat(100);
        Map<String, Object> body = new HashMap<>();
        body.put("templateCode", exactlyAtCap);

        ApiResponse<Map<String, Object>> resp = controller.testSend("F001", body);

        // Will be 404 (template doesn't exist in mock), NOT 400 VALIDATION.
        // 404 means length validation passed — which is what we're asserting.
        assertNotNull(resp);
        assertEquals(404, resp.getCode(), "100-char should pass length check then fail at repo lookup");
        assertEquals("TEMPLATE_NOT_FOUND", resp.getErrorCode());
        // The repo lookup DID get reached with the exact 100-char string
        verify(templateRepo).findByFactoryIdAndTemplateCode("F001", exactlyAtCap);
    }

    // ==================== Sanity: existing blank-check still fires =====================

    @Test
    @DisplayName("Pre-existing: blank templateCode still returns specific 400 (regression guard)")
    void testBlankTemplateCodeRejectedFirst() {
        // Ensure new length check did NOT break the existing blank-check (which fires earlier).
        Map<String, Object> body = new HashMap<>();
        body.put("templateCode", "");

        ApiResponse<Map<String, Object>> resp = controller.testSend("F001", body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("templateCode 必填"),
                "blank check must surface its specific message, not the length one: " + resp.getMessage());
        verify(templateRepo, never()).findByFactoryIdAndTemplateCode(anyString(), anyString());
    }

    @Test
    @DisplayName("Pre-existing: null templateCode still rejected (regression guard)")
    void testNullTemplateCodeRejected() {
        Map<String, Object> body = new HashMap<>();
        // templateCode absent entirely
        body.put("recipientUserIds", java.util.List.of(1L));

        ApiResponse<Map<String, Object>> resp = controller.testSend("F001", body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        verify(templateRepo, never()).findByFactoryIdAndTemplateCode(anyString(), anyString());
    }
}
