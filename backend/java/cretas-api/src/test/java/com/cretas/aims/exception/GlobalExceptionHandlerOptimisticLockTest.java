package com.cretas.aims.exception;

import com.cretas.aims.dto.common.ApiResponse;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-4 P1 — verify {@link GlobalExceptionHandler#handleOptimisticLockException(Exception)}
 * surfaces a 4-in-1 UX response (per qa-prompt v2.4 Rule 8 + fool-proof rule 5 next action):
 * <ul>
 *   <li>HTTP code = 409 (existing behavior, preserved)</li>
 *   <li>Specific Chinese message saying data was modified (existing, preserved)</li>
 *   <li><b>NEW:</b> {@code actionHint} = "请刷新页面查看最新数据后再编辑"
 *       so frontend can show a useful next-action prompt</li>
 *   <li><b>NEW:</b> {@code severity} = "warning" so FE interceptor renders a
 *       sticky toast (per {@code web-admin/src/api/request.ts} which routes
 *       severity=warning|error to sticky duration:0)</li>
 *   <li><b>NEW:</b> {@code hintTarget} = null (no specific field — whole row is stale)</li>
 * </ul>
 *
 * <p>Covers both JPA-level {@link OptimisticLockException} and Spring-level
 * {@link ObjectOptimisticLockingFailureException} (Hibernate translates JPA
 * exceptions through the Spring DataAccessException hierarchy, so PUT handlers
 * see the Spring-wrapped one in practice — both must route the same way).
 *
 * @since 2026-05-20 (AUD-4 hardening)
 */
@DisplayName("AUD-4 P1 — GlobalExceptionHandler optimistic-lock 4-in-1 UX")
class GlobalExceptionHandlerOptimisticLockTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("ObjectOptimisticLockingFailureException → 409 + actionHint + severity warning")
    void springLevelOptimisticLockFailure_surfaces_4in1_envelope() {
        // Spring wraps JPA OptimisticLockException into this type when Hibernate
        // throws inside @Transactional — this is what every PUT handler observes.
        ObjectOptimisticLockingFailureException ex = new ObjectOptimisticLockingFailureException(
                "com.cretas.aims.entity.pricing.PricingStrategy",
                "stale-row-id-for-test");

        ApiResponse<?> body = handler.handleOptimisticLockException(ex);

        // Preserved contract
        assertEquals((Integer) 409, body.getCode(), "HTTP 409 Conflict preserved");
        assertEquals("OPTIMISTIC_LOCK_CONFLICT", body.getErrorCode(),
                "semantic errorCode lets an owning editor recover without status-only branching");
        assertEquals(Boolean.FALSE, body.getSuccess());
        assertNotNull(body.getMessage(), "user-facing message present");
        assertTrue(body.getMessage().contains("数据已被其他用户修改"),
                "Specific message about concurrent modification: " + body.getMessage());

        // AUD-4 enrichment — must be present for FE to render sticky toast with next action
        assertEquals("请刷新页面查看最新数据后再编辑", body.getActionHint(),
                "actionHint must guide user to next action (per fool-proof rule 5)");
        assertEquals("warning", body.getSeverity(),
                "severity=warning so FE interceptor renders sticky (per qa-prompt v2.4 Rule 8c)");
        // hintTarget is null because the conflict is row-wide, not field-specific
        assertEquals(null, body.getHintTarget(),
                "hintTarget=null — whole row is stale, no single field to highlight");
    }

    @Test
    @DisplayName("JPA-level OptimisticLockException routes through the same handler")
    void jpaLevelOptimisticLockException_routes_to_same_envelope() {
        // Direct JPA exception (rarely surfaces past Spring translation, but the
        // @ExceptionHandler lists both classes so verify the JPA-side too).
        OptimisticLockException ex = new OptimisticLockException("Row was updated by another tx");

        ApiResponse<?> body = handler.handleOptimisticLockException(ex);

        assertEquals((Integer) 409, body.getCode());
        assertEquals("OPTIMISTIC_LOCK_CONFLICT", body.getErrorCode());
        assertEquals(Boolean.FALSE, body.getSuccess());
        assertTrue(body.getMessage().contains("数据已被其他用户修改"));
        assertEquals("请刷新页面查看最新数据后再编辑", body.getActionHint());
        assertEquals("warning", body.getSeverity());
    }

    @Test
    @DisplayName("Response body never leaks Hibernate internals or sensitive types")
    void responseBody_does_not_leak_hibernate_internals() {
        // Whatever the exception's internal message says (which is "Object of class
        // com.cretas.aims.entity..." in real cases), the user-facing message must
        // strip implementation detail. The handler hard-codes the user message so
        // it cannot leak — assert that explicitly so any future refactor that
        // accidentally uses ex.getMessage() trips this test.
        ObjectOptimisticLockingFailureException ex = new ObjectOptimisticLockingFailureException(
                "com.cretas.aims.entity.pricing.PricingStrategy",
                "stale-row-id-for-test");

        ApiResponse<?> body = handler.handleOptimisticLockException(ex);

        assertFalse(body.getMessage().toLowerCase().contains("hibernate"),
                "Leak guard: must not mention 'hibernate'");
        assertFalse(body.getMessage().contains("com.cretas.aims"),
                "Leak guard: must not contain Java package paths");
        assertFalse(body.getMessage().toLowerCase().contains("exception"),
                "Leak guard: must not mention 'exception'");
    }
}
