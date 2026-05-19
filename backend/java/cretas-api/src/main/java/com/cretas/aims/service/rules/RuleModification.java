package com.cretas.aims.service.rules;

import java.util.UUID;

/**
 * Single field modification applied by a MODIFY rule.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §2.2
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
public record RuleModification(
        String field,
        Object oldValue,
        Object newValue,
        UUID ruleId) {
}
