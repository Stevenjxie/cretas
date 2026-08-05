package com.cretas.aims.service.finding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Finding}. */
class FindingTest {

    private Finding of(Finding.Severity severity, int actionability) {
        return new Finding("LOW_STOCK", "inventory", severity, actionability,
                "M001", "鲈鱼", Map.of("gap", 38));
    }

    @Test
    @DisplayName("UT-FND-01: severity 权重 CRITICAL=3 > WARNING=2 > INFO=1")
    void severityWeights() {
        assertEquals(3, Finding.Severity.CRITICAL.weight());
        assertEquals(2, Finding.Severity.WARNING.weight());
        assertEquals(1, Finding.Severity.INFO.weight());
    }

    @Test
    @DisplayName("UT-FND-02: rankScore = severity*100 + actionability")
    void rankScoreFormula() {
        assertEquals(350, of(Finding.Severity.CRITICAL, 50).rankScore());
        assertEquals(250, of(Finding.Severity.WARNING, 50).rankScore());
        assertEquals(199, of(Finding.Severity.INFO, 99).rankScore());
    }

    @Test
    @DisplayName("UT-FND-03: severity 压过 actionability —— INFO 满 actionability 也排不过 WARNING")
    void severityDominatesActionability() {
        assertTrue(of(Finding.Severity.WARNING, 0).rankScore()
                > of(Finding.Severity.INFO, 99).rankScore());
    }
}
