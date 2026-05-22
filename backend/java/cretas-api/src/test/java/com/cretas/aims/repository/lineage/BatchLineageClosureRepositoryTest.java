package com.cretas.aims.repository.lineage;

import com.cretas.aims.entity.lineage.BatchLineageClosure;
import jakarta.persistence.EntityManager;
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
 * Phase 1 Sprint 1 Day 3 — JPA slice test for {@link BatchLineageClosureRepository}.
 *
 * <p>Verifies:
 * <ol>
 *   <li>findAncestors — depth&gt;0 records where node is descendant, ordered by depth ASC</li>
 *   <li>findDescendants — depth&gt;0 records where node is ancestor</li>
 *   <li>findFullGraph — bidirectional (anc OR desc), depth bounded by maxDepth</li>
 *   <li>{@code @PreRemove} throws on physical delete attempt</li>
 * </ol>
 *
 * <p>Note: in production, the trigger {@code fn_maintain_lineage_closure} fills this
 * table automatically. In JPA slice tests we manually insert closure rows to verify
 * the repository query semantics — the trigger is a separate Day 2 concern.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.lineage")
@DisplayName("BatchLineageClosureRepository — JPA slice (H2 PG-compat)")
class BatchLineageClosureRepositoryTest {

    private static final String F1 = "F-BLC-1";
    private static final String F2 = "F-BLC-2";

    @Autowired private BatchLineageClosureRepository repo;
    @Autowired private EntityManager em;

    /**
     * Seed a small 3-level chain:
     *   raw-1 (MATERIAL_BATCH) → prod-1 (PRODUCTION_BATCH) → fin-1 (FINISHED_BATCH)
     *
     * Closure rows (per spec § Closure trigger semantics):
     *   raw-1 → raw-1   (depth 0, self)
     *   prod-1 → prod-1 (depth 0)
     *   fin-1 → fin-1   (depth 0)
     *   raw-1 → prod-1  (depth 1)
     *   prod-1 → fin-1  (depth 1)
     *   raw-1 → fin-1   (depth 2, transitive)
     */
    private void seedThreeLevelChain() {
        // depth=0 self
        repo.saveAndFlush(newClosure(F1, "MATERIAL_BATCH", "raw-1",
                "MATERIAL_BATCH", "raw-1", 0));
        repo.saveAndFlush(newClosure(F1, "PRODUCTION_BATCH", "prod-1",
                "PRODUCTION_BATCH", "prod-1", 0));
        repo.saveAndFlush(newClosure(F1, "FINISHED_BATCH", "fin-1",
                "FINISHED_BATCH", "fin-1", 0));
        // depth=1 direct edges
        repo.saveAndFlush(newClosure(F1, "MATERIAL_BATCH", "raw-1",
                "PRODUCTION_BATCH", "prod-1", 1));
        repo.saveAndFlush(newClosure(F1, "PRODUCTION_BATCH", "prod-1",
                "FINISHED_BATCH", "fin-1", 1));
        // depth=2 transitive
        repo.saveAndFlush(newClosure(F1, "MATERIAL_BATCH", "raw-1",
                "FINISHED_BATCH", "fin-1", 2));
    }

    @Test
    @DisplayName("findAncestors — fin-1 ancestors returns prod-1 (d=1) + raw-1 (d=2), depth ASC")
    void findAncestors() {
        seedThreeLevelChain();

        // F2 noise (must be excluded)
        repo.saveAndFlush(newClosure(F2, "MATERIAL_BATCH", "raw-99",
                "FINISHED_BATCH", "fin-1", 1));

        List<BatchLineageClosure> ancestors = repo.findAncestors(F1, "fin-1", "FINISHED_BATCH");

        assertEquals(2, ancestors.size(), "fin-1 has 2 ancestors (prod-1 + raw-1); self d=0 excluded");
        // depth ASC: prod-1 first (depth 1), raw-1 second (depth 2)
        assertEquals("prod-1", ancestors.get(0).getAncestorId());
        assertEquals(1, ancestors.get(0).getDepth().intValue());
        assertEquals("raw-1", ancestors.get(1).getAncestorId());
        assertEquals(2, ancestors.get(1).getDepth().intValue());
    }

    @Test
    @DisplayName("findDescendants — raw-1 descendants returns prod-1 (d=1) + fin-1 (d=2)")
    void findDescendants() {
        seedThreeLevelChain();

        List<BatchLineageClosure> descendants = repo.findDescendants(F1, "raw-1", "MATERIAL_BATCH");

        assertEquals(2, descendants.size(), "raw-1 has 2 descendants; self d=0 excluded");
        assertEquals("prod-1", descendants.get(0).getDescendantId());
        assertEquals(1, descendants.get(0).getDepth().intValue());
        assertEquals("fin-1", descendants.get(1).getDescendantId());
        assertEquals(2, descendants.get(1).getDepth().intValue());
    }

    @Test
    @DisplayName("findFullGraph — bidirectional traversal of prod-1 with maxDepth=5")
    void findFullGraph() {
        seedThreeLevelChain();

        List<BatchLineageClosure> graph = repo.findFullGraph(F1, "prod-1", "PRODUCTION_BATCH", 5);

        // prod-1 as ancestor: prod-1→prod-1 (d=0) + prod-1→fin-1 (d=1)
        // prod-1 as descendant: prod-1→prod-1 (d=0, same self row) + raw-1→prod-1 (d=1)
        // Unique row count: 3 (self d=0, prod-1→fin-1 d=1, raw-1→prod-1 d=1) — but self
        // row matches both anc+desc clauses, JPQL OR returns it once.
        assertEquals(3, graph.size(), "self d=0 + 1 down + 1 up");

        // depth ASC: self first
        assertEquals(0, graph.get(0).getDepth().intValue());
    }

    @Test
    @DisplayName("@PreRemove — physical delete throws UnsupportedOperationException")
    void preRemoveBlocksPhysicalDelete() {
        BatchLineageClosure c = repo.saveAndFlush(newClosure(F1,
                "MATERIAL_BATCH", "test-anc",
                "PRODUCTION_BATCH", "test-desc",
                1));
        String id = c.getId();

        Throwable thrown = assertThrows(Throwable.class, () -> {
            repo.delete(c);
            em.flush();
        }, "delete on append-only closure must throw");

        Throwable cause = thrown;
        boolean foundUoe = false;
        while (cause != null) {
            if (cause instanceof UnsupportedOperationException) {
                foundUoe = true;
                assertTrue(cause.getMessage().contains("append-only"),
                        "exception message must mention append-only");
                break;
            }
            cause = cause.getCause();
        }
        assertTrue(foundUoe, "expected UnsupportedOperationException in cause chain, got: "
                + thrown.getClass().getName() + ": " + thrown.getMessage());

        em.clear();
        assertTrue(repo.findById(id).isPresent(),
                "row still present — physical delete blocked");
    }

    // ============================================================
    // Helper
    // ============================================================

    private BatchLineageClosure newClosure(String factoryId,
                                            String ancestorType, String ancestorId,
                                            String descendantType, String descendantId,
                                            int depth) {
        BatchLineageClosure c = new BatchLineageClosure();
        c.setFactoryId(factoryId);
        c.setAncestorType(ancestorType);
        c.setAncestorId(ancestorId);
        c.setDescendantType(descendantType);
        c.setDescendantId(descendantId);
        c.setDepth(depth);
        return c;
    }
}
