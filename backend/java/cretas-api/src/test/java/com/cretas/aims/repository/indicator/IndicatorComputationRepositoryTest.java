package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.IndicatorComputation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 Sprint 1 Day 3 — JPA slice test for {@link IndicatorComputationRepository}.
 *
 * <p>Verifies:
 * <ol>
 *   <li>findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull — filters active + sorts by priority ASC</li>
 *   <li>findActiveByComputeType — filters by compute_type</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.indicator")
@DisplayName("IndicatorComputationRepository — JPA slice (H2 PG-compat)")
class IndicatorComputationRepositoryTest {

    @Autowired private IndicatorComputationRepository repo;

    @Test
    @DisplayName("findByIndicatorIdAndIsActiveTrue — only active rows, ordered by priority ASC")
    void findByIndicatorAndActive() {
        String indicatorId = "ind-yield-rate";

        // Same indicator: 1 primary active (prio 1) + 1 fallback active (prio 2) + 1 inactive (prio 0)
        repo.saveAndFlush(newComputation(indicatorId, "PYTHON_ENDPOINT",
                "/api/smartbi/analysis/factory/yield-rate", true, 1));
        repo.saveAndFlush(newComputation(indicatorId, "FORMULA",
                "qualifiedQty / totalQty * 100", true, 2));
        repo.saveAndFlush(newComputation(indicatorId, "JPA_QUERY",
                "computeYieldFallback", false, 0));  // inactive

        // Different indicator (must not appear)
        repo.saveAndFlush(newComputation("ind-other", "PYTHON_ENDPOINT",
                "/api/other", true, 1));

        List<IndicatorComputation> active =
                repo.findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(indicatorId);

        assertEquals(2, active.size(), "inactive excluded, ind-other excluded");
        // Priority ASC: primary first
        assertEquals(1, active.get(0).getPriority().intValue());
        assertEquals("PYTHON_ENDPOINT", active.get(0).getComputeType());
        assertEquals(2, active.get(1).getPriority().intValue());
        assertEquals("FORMULA", active.get(1).getComputeType());
    }

    @Test
    @DisplayName("findActiveByComputeType — only active rows of given type")
    void findActiveByComputeType() {
        // 3 PYTHON_ENDPOINT (2 active + 1 inactive) + 1 FORMULA active
        repo.saveAndFlush(newComputation("ind-1", "PYTHON_ENDPOINT", "/api/1", true, 1));
        repo.saveAndFlush(newComputation("ind-2", "PYTHON_ENDPOINT", "/api/2", true, 1));
        repo.saveAndFlush(newComputation("ind-3", "PYTHON_ENDPOINT", "/api/3", false, 1));
        repo.saveAndFlush(newComputation("ind-4", "FORMULA", "1+1", true, 1));

        List<IndicatorComputation> pythonActive = repo.findActiveByComputeType("PYTHON_ENDPOINT");
        List<IndicatorComputation> formulaActive = repo.findActiveByComputeType("FORMULA");

        assertEquals(2, pythonActive.size(), "ind-3 inactive excluded");
        assertEquals(1, formulaActive.size());
        assertEquals("FORMULA", formulaActive.get(0).getComputeType());
    }

    // ============================================================
    // Helper
    // ============================================================

    private IndicatorComputation newComputation(String indicatorId, String computeType,
                                                 String computeSource, boolean active,
                                                 int priority) {
        IndicatorComputation c = new IndicatorComputation();
        c.setIndicatorId(indicatorId);
        c.setComputeType(computeType);
        c.setComputeSource(computeSource);
        c.setIsActive(active);
        c.setPriority(priority);
        // Null out jsonb params — H2 PG-compat round-trip limitation
        c.setParams(null);
        return c;
    }
}
