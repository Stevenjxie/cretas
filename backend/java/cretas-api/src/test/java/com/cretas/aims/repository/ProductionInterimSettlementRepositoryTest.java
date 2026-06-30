package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionInterimSettlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 2 幂等基础 — JPA slice test for {@link ProductionInterimSettlementRepository}.
 *
 * <p>Verifies:
 * <ol>
 *   <li>Save seq=1 and seq=2 for same (factory, plan) → findTop...Desc returns seq=2.</li>
 *   <li>Factory isolation: another factory's row is NOT returned for (F1, plan).</li>
 *   <li>{@code @PrePersist} sets non-null id + audit fields (createdAt/updatedAt).</li>
 * </ol>
 *
 * <p>JSONB {@code summary} is set null throughout (H2 PG-compat jsonb round-trip limitation,
 * same as {@link SemiFinishedInventoryRepositoryTest}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("ProductionInterimSettlementRepository — JPA slice (H2 PG-compat)")
class ProductionInterimSettlementRepositoryTest {

    private static final String F1     = "F-PIS-1";
    private static final String F2     = "F-PIS-2";
    private static final String PLAN_A = "PLAN-001";

    @Autowired
    private ProductionInterimSettlementRepository repo;

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private ProductionInterimSettlement settlement(String factoryId, String planId, int seq) {
        ProductionInterimSettlement s = new ProductionInterimSettlement();
        s.setFactoryId(factoryId);
        s.setProductionPlanId(planId);
        s.setSessionSeq(seq);
        s.setPostedAt(LocalDateTime.now());
        s.setPostedBy(null);
        s.setSummary(null);   // H2 jsonb: always null in unit tests
        return s;
    }

    // -----------------------------------------------------------------------
    // tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findTop...Desc — returns highest seq for same (factory, plan)")
    void findTopReturnsHighestSeq() {
        repo.saveAndFlush(settlement(F1, PLAN_A, 1));
        repo.saveAndFlush(settlement(F1, PLAN_A, 2));

        Optional<ProductionInterimSettlement> top =
                repo.findTopByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqDesc(F1, PLAN_A);

        assertTrue(top.isPresent(), "should find a row for (F1, PLAN_A)");
        assertEquals(2, top.get().getSessionSeq(), "must return seq=2 (the highest)");
    }

    @Test
    @DisplayName("factory isolation — different factory row not returned for F1")
    void factoryIsolation() {
        repo.saveAndFlush(settlement(F2, PLAN_A, 1));

        Optional<ProductionInterimSettlement> top =
                repo.findTopByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqDesc(F1, PLAN_A);

        assertTrue(top.isEmpty(), "F2 row must not appear when querying F1");
    }

    @Test
    @DisplayName("@PrePersist — assigns UUID id and audit timestamps on save")
    void prePersistAssignsIdAndAudit() {
        ProductionInterimSettlement saved = repo.saveAndFlush(settlement(F1, "PLAN-AUDIT", 1));

        assertNotNull(saved.getId(),        "@PrePersist must assign a non-null id");
        assertFalse(saved.getId().isBlank(), "id must not be blank");
        assertNotNull(saved.getCreatedAt(), "BaseEntity @PrePersist must set createdAt");
        assertNotNull(saved.getUpdatedAt(), "BaseEntity @PrePersist must set updatedAt");
        assertNotNull(saved.getPostedAt(),  "@PrePersist must set postedAt when null");
    }

    @Test
    @DisplayName("empty result — no rows for unknown plan returns Optional.empty")
    void emptyForUnknownPlan() {
        Optional<ProductionInterimSettlement> top =
                repo.findTopByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqDesc(
                        F1, "NO-SUCH-PLAN");

        assertTrue(top.isEmpty(), "unknown plan must return Optional.empty");
    }
}
