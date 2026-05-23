package com.cretas.aims.exception;

import com.cretas.aims.dto.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 11.5 P0 fix (2026-05-23) — verify MissingRequestHeaderException no longer
 * falls through to {@code handleRuntimeException} → HTTP 500 "系统处理异常".
 *
 * <p><b>Background</b>: Web admin Cookie-only requests to {@code POST
 * /api/mobile/{factoryId}/ai-intents/execute} (and 28 other controllers using
 * {@code @RequestHeader("Authorization")}) hit
 * {@code MissingRequestHeaderException} BEFORE the controller method runs.
 *
 * <p>Pre-fix: Spring's
 * {@link MissingRequestHeaderException} (extends ServletRequestBindingException →
 * RuntimeException) fell through to
 * {@link GlobalExceptionHandler#handleRuntimeException} → HTTP 500 with sanitized
 * trace code. Users saw "系统处理异常 (追踪码: C62DA8C6)" — misleading because the
 * server didn't crash, it was missing a header.
 *
 * <p>Post-fix: dedicated handler maps Authorization-missing to 401 (auth-related
 * UX) with actionHint + severity for proper toast rendering; other missing headers
 * map to 400 with header name.
 *
 * <p>Direct handler instantiation, no Spring context, ~2s test runtime.
 */
@DisplayName("Sprint 11.5 P0 — MissingRequestHeaderException handler")
class GlobalExceptionHandlerMissingHeaderTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * Build a real {@link MissingRequestHeaderException} (no mocking) — uses
     * reflection to obtain a real MethodParameter from this test class so the
     * exception constructor is fully satisfied.
     */
    private MissingRequestHeaderException buildException(String headerName) throws NoSuchMethodException {
        Method method = this.getClass().getDeclaredMethod("dummyMethod", String.class);
        MethodParameter param = new MethodParameter(method, 0);
        return new MissingRequestHeaderException(headerName, param);
    }

    /** Dummy method used only as a reflection target for MethodParameter. */
    @SuppressWarnings("unused")
    private void dummyMethod(String header) {
        // intentionally empty
    }

    @Test
    @DisplayName("Authorization header missing → HTTP 401 (not 500)")
    void missing_authorization_header_returns_401() throws NoSuchMethodException {
        MissingRequestHeaderException ex = buildException("Authorization");
        ResponseEntity<ApiResponse<?>> resp = handler.handleMissingRequestHeaderException(ex);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), resp.getStatusCode().value(),
                "Authorization-missing must map to 401, NOT 500 (Sprint 11.5 P0 regression check). "
                        + "Pre-fix Cookie-only web admin clients saw HTTP 500 '系统处理异常'.");
    }

    @Test
    @DisplayName("Authorization header missing → body contains 401 code + actionHint + severity=error")
    void missing_authorization_header_body_contract() throws NoSuchMethodException {
        MissingRequestHeaderException ex = buildException("Authorization");
        ResponseEntity<ApiResponse<?>> resp = handler.handleMissingRequestHeaderException(ex);
        ApiResponse<?> body = resp.getBody();

        assertNotNull(body, "Response body must not be null");
        assertEquals(401, body.getCode(), "ApiResponse.code must be 401");
        assertNotNull(body.getMessage(), "Response message must not be null");
        assertTrue(body.getMessage().contains("登录") || body.getMessage().contains("授权"),
                "Message must mention login/auth (got: " + body.getMessage() + ")");
        assertNotNull(body.getActionHint(),
                "actionHint must be set so frontend interceptor can render sticky toast");
        assertEquals("error", body.getSeverity(),
                "severity must be 'error' for sticky toast rendering");
    }

    @Test
    @DisplayName("Non-Authorization header missing → HTTP 400")
    void missing_other_header_returns_400() throws NoSuchMethodException {
        MissingRequestHeaderException ex = buildException("X-Custom-Header");
        ResponseEntity<ApiResponse<?>> resp = handler.handleMissingRequestHeaderException(ex);

        assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatusCode().value(),
                "Non-Authorization missing header should be 400 (request error, not auth error)");
        ApiResponse<?> body = resp.getBody();
        assertNotNull(body);
        assertTrue(body.getMessage().contains("X-Custom-Header"),
                "Message must include the missing header name (got: " + body.getMessage() + ")");
    }

    @Test
    @DisplayName("Authorization header case-insensitive matching")
    void missing_authorization_header_case_insensitive() throws NoSuchMethodException {
        // Spring sometimes normalizes header names; both forms must map to 401.
        for (String variant : new String[]{"authorization", "AUTHORIZATION", "Authorization"}) {
            MissingRequestHeaderException ex = buildException(variant);
            ResponseEntity<ApiResponse<?>> resp = handler.handleMissingRequestHeaderException(ex);
            assertEquals(HttpStatus.UNAUTHORIZED.value(), resp.getStatusCode().value(),
                    "Authorization variant '" + variant + "' must map to 401 (case-insensitive check)");
        }
    }
}
