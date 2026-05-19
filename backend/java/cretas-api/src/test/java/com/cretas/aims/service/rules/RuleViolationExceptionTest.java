package com.cretas.aims.service.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for {@link RuleViolationException} — verifies it carries ruleCode + reason and
 * is a {@link RuntimeException} (so GlobalExceptionHandler @ExceptionHandler picks it up).
 */
@DisplayName("RuleViolationException carries ruleCode + reason")
class RuleViolationExceptionTest {

    @Test
    @DisplayName("Constructor populates fields; message contains both ruleCode and reason")
    void carries_fields() {
        RuleViolationException ex = new RuleViolationException("po_blacklist", "供应商在黑名单");
        assertEquals("po_blacklist", ex.getRuleCode());
        assertEquals("供应商在黑名单", ex.getReason());
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("po_blacklist"));
        assertTrue(ex.getMessage().contains("供应商在黑名单"));
    }

    @Test
    @DisplayName("Is a RuntimeException — Spring catches for ControllerAdvice mapping")
    void is_runtime_exception() {
        RuleViolationException ex = new RuleViolationException("x", "y");
        assertTrue(ex instanceof RuntimeException);
    }
}
