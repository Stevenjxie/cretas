package com.cretas.aims.service.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for {@link RuleViolationException} — verifies it carries ruleCode + reason and
 * is a {@link RuntimeException} (so GlobalExceptionHandler @ExceptionHandler picks it up).
 */
@DisplayName("RuleViolationException carries ruleCode + reason + actionHint + severity")
class RuleViolationExceptionTest {

    @Test
    @DisplayName("Legacy 2-arg constructor: ruleCode + reason; actionHint/severity null")
    void carries_fields() {
        RuleViolationException ex = new RuleViolationException("po_blacklist", "供应商在黑名单");
        assertEquals("po_blacklist", ex.getRuleCode());
        assertEquals("供应商在黑名单", ex.getReason());
        assertNull(ex.getActionHint());
        assertNull(ex.getSeverity());
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("po_blacklist"));
        assertTrue(ex.getMessage().contains("供应商在黑名单"));
    }

    @Test
    @DisplayName("4-arg constructor (Phase 4a C1): actionHint + severity propagate from rule config")
    void carries_action_hint_and_severity() {
        RuleViolationException ex = new RuleViolationException(
                "po_blacklist",
                "供应商在黑名单",
                "/system/suppliers/SUP-001",
                "blocking");
        assertEquals("po_blacklist", ex.getRuleCode());
        assertEquals("供应商在黑名单", ex.getReason());
        assertEquals("/system/suppliers/SUP-001", ex.getActionHint());
        assertEquals("blocking", ex.getSeverity());
    }

    @Test
    @DisplayName("4-arg constructor with null actionHint/severity")
    void carries_null_hints() {
        RuleViolationException ex = new RuleViolationException("x", "y", null, null);
        assertNull(ex.getActionHint());
        assertNull(ex.getSeverity());
    }

    @Test
    @DisplayName("Is a RuntimeException — Spring catches for ControllerAdvice mapping")
    void is_runtime_exception() {
        RuleViolationException ex = new RuleViolationException("x", "y");
        assertTrue(ex instanceof RuntimeException);
    }
}
