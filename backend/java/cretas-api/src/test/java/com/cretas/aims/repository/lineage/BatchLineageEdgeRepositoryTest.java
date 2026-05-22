package com.cretas.aims.repository.lineage;

import com.cretas.aims.entity.lineage.BatchLineageEdge;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 Sprint 1 Day 3 — JPA slice test for {@link BatchLineageEdgeRepository}.
 *
 * <p>Verifies:
 * <ol>
 *   <li>findByFactoryIdAndSourceIdAndSourceType — downstream edges</li>
 *   <li>findByFactoryIdAndTargetIdAndTargetType — upstream edges</li>
 *   <li>{@code @PreRemove} throws on physical delete attempt (append-only forensic)</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.lineage")
@DisplayName("BatchLineageEdgeRepository — JPA slice (H2 PG-compat)")
class BatchLineageEdgeRepositoryTest {

    private static final String F1 = "F-BLE-1";
    private static final String F2 = "F-BLE-2";

    @Autowired private BatchLineageEdgeRepository repo;
    @Autowired private EntityManager em;

    @Test
    @DisplayName("findBySource — locates all downstream edges of a material batch")
    void findBySource() {
        // material-1 → production-A (RAW_TO_PRODUCTION)
        repo.saveAndFlush(newEdge(F1, "RAW_TO_PRODUCTION",
                "MATERIAL_BATCH", "material-1",
                "PRODUCTION_BATCH", "production-A"));
        // material-1 → production-B (also downstream)
        repo.saveAndFlush(newEdge(F1, "RAW_TO_PRODUCTION",
                "MATERIAL_BATCH", "material-1",
                "PRODUCTION_BATCH", "production-B"));
        // unrelated: material-2 → production-C
        repo.saveAndFlush(newEdge(F1, "RAW_TO_PRODUCTION",
                "MATERIAL_BATCH", "material-2",
                "PRODUCTION_BATCH", "production-C"));
        // F2 must not appear
        repo.saveAndFlush(newEdge(F2, "RAW_TO_PRODUCTION",
                "MATERIAL_BATCH", "material-1",
                "PRODUCTION_BATCH", "production-Z"));

        List<BatchLineageEdge> downstream = repo.findByFactoryIdAndSourceIdAndSourceType(
                F1, "material-1", "MATERIAL_BATCH");

        assertEquals(2, downstream.size(),
                "material-1 in F1 has 2 downstream edges (material-2 + F2 excluded)");
        List<String> targets = downstream.stream().map(BatchLineageEdge::getTargetId).toList();
        assertTrue(targets.contains("production-A"));
        assertTrue(targets.contains("production-B"));
    }

    @Test
    @DisplayName("findByTarget — locates all upstream edges of a production batch")
    void findByTarget() {
        // production-X has 2 raw material inputs + 1 unrelated production-Y input
        repo.saveAndFlush(newEdge(F1, "RAW_TO_PRODUCTION",
                "MATERIAL_BATCH", "raw-1",
                "PRODUCTION_BATCH", "production-X"));
        repo.saveAndFlush(newEdge(F1, "RAW_TO_PRODUCTION",
                "MATERIAL_BATCH", "raw-2",
                "PRODUCTION_BATCH", "production-X"));
        repo.saveAndFlush(newEdge(F1, "RAW_TO_PRODUCTION",
                "MATERIAL_BATCH", "raw-99",
                "PRODUCTION_BATCH", "production-Y"));  // different target

        List<BatchLineageEdge> upstream = repo.findByFactoryIdAndTargetIdAndTargetType(
                F1, "production-X", "PRODUCTION_BATCH");

        assertEquals(2, upstream.size(), "production-X has 2 raw inputs");
        List<String> sources = upstream.stream().map(BatchLineageEdge::getSourceId).toList();
        assertTrue(sources.contains("raw-1"));
        assertTrue(sources.contains("raw-2"));
    }

    @Test
    @DisplayName("@PreRemove — physical delete throws UnsupportedOperationException")
    void preRemoveBlocksPhysicalDelete() {
        BatchLineageEdge edge = repo.saveAndFlush(newEdge(F1, "PRODUCTION_TO_FINISHED",
                "PRODUCTION_BATCH", "prod-1",
                "FINISHED_BATCH", "fin-1"));
        String id = edge.getId();

        // Spring Data JPA repo.delete() ultimately calls EntityManager.remove() which
        // fires the @PreRemove callback. Capture both potential exception layers
        // (UnsupportedOperationException direct OR wrapped by Hibernate).
        Throwable thrown = assertThrows(Throwable.class, () -> {
            repo.delete(edge);
            em.flush();
        }, "delete on append-only audit entity must throw");

        // Walk the cause chain to find UnsupportedOperationException
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

        // Verify row still exists after rollback (the @PreRemove threw, transaction
        // rolled back, row should be intact)
        em.clear();
        assertTrue(repo.findById(id).isPresent(),
                "row still present — physical delete blocked");
    }

    // ============================================================
    // Helper
    // ============================================================

    private BatchLineageEdge newEdge(String factoryId, String edgeType,
                                      String sourceType, String sourceId,
                                      String targetType, String targetId) {
        BatchLineageEdge e = new BatchLineageEdge();
        e.setFactoryId(factoryId);
        e.setEdgeType(edgeType);
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        e.setQuantityUsed(new BigDecimal("10.0"));
        e.setUnit("kg");
        e.setEventTime(LocalDateTime.now());
        // Null out jsonb meta — H2 PG-compat round-trip limitation
        e.setMeta(null);
        return e;
    }
}
