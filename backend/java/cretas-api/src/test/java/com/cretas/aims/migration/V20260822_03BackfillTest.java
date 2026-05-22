package com.cretas.aims.migration;

import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 Sprint 1 Day 8 — Migration backfill test for
 * {@code V20260822_03__batch_lineage_backfill.sql}.
 *
 * <p>Verifies that historical {@code batch_relations} rows are correctly
 * back-filled into {@code batch_lineage_edges} with the expected column mapping
 * and idempotency.
 *
 * <h3>Test strategy</h3>
 * <p>H2 in PG-compat mode (per {@code application-test.properties}) cannot
 * execute the migration's PL/pgSQL {@code DO $$ ... END $$} block. Instead the
 * test:
 * <ol>
 *   <li>Persists 2 {@link BatchRelation} rows + 1 soft-deleted row via JPA
 *       (H2 schema is built from entities; {@code ddl-auto=create-drop}).</li>
 *   <li>Executes the migration's INSERT clause directly via {@link JdbcTemplate}
 *       (no DO block, no RAISE NOTICE — just the inner INSERT, exactly as it
 *       runs in production after the PL/pgSQL wrapper unwraps).</li>
 *   <li>Asserts 2 corresponding {@link BatchLineageEdge} rows exist with
 *       correct field mapping (no edge from soft-deleted relation).</li>
 *   <li>Re-runs the SAME INSERT — verifies idempotency: still 2 rows, no dupes.</li>
 * </ol>
 *
 * <p><b>Note on closure trigger:</b> in production, inserting into
 * {@code batch_lineage_edges} triggers {@code fn_maintain_lineage_closure} which
 * fills {@code batch_lineage_closure}. The trigger is PL/pgSQL — H2 cannot run
 * it. Closure verification is out of scope here (covered by
 * {@link com.cretas.aims.repository.lineage.BatchLineageClosureRepositoryTest}
 * which seeds closure rows manually).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity.lineage")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.lineage")
@DisplayName("V20260822_03 — batch_relations → batch_lineage_edges backfill")
class V20260822_03BackfillTest {

    private static final String F1 = "F-BFILL-1";

    /**
     * DDL for {@code batch_relations} test table.
     * <p>In production this is created at runtime by JPA via the
     * {@link com.cretas.aims.entity.BatchRelation} entity. The test scopes
     * {@code @EntityScan} to {@code entity.lineage} only (to avoid bean
     * conflicts with the parent app context), so we hand-create the table to
     * mirror the entity's column shape.
     */
    private static final String CREATE_BATCH_RELATIONS = """
            CREATE TABLE IF NOT EXISTS batch_relations (
                id                   VARCHAR(50)    PRIMARY KEY,
                factory_id           VARCHAR(20)    NOT NULL,
                production_batch_id  BIGINT         NOT NULL,
                material_batch_id    VARCHAR(50)    NOT NULL,
                relation_type        VARCHAR(20),
                quantity_used        DECIMAL(15, 3),
                unit                 VARCHAR(20),
                used_at              TIMESTAMP,
                stage                VARCHAR(50),
                operator_id          BIGINT,
                remarks              VARCHAR(500),
                verified             BOOLEAN,
                verified_at          TIMESTAMP,
                verified_by          BIGINT,
                created_at           TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
                updated_at           TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
                deleted_at           TIMESTAMP
            )
            """;

    private static final String INSERT_BATCH_RELATION = """
            INSERT INTO batch_relations (id, factory_id, production_batch_id,
                material_batch_id, relation_type, quantity_used, unit, used_at,
                created_at, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * Adapted from {@code V20260822_03__batch_lineage_backfill.sql}'s inner
     * INSERT clause. PL/pgSQL DO block + RAISE NOTICE stripped; UUID generation
     * uses {@code RANDOM_UUID()} (H2) vs {@code gen_random_uuid()} (Postgres) —
     * functionally equivalent for the test; SQL otherwise byte-identical.
     *
     * <p>The production SQL uses {@code jsonb_build_object(...)} to populate
     * the {@code meta} column with forensic provenance (V20260822_03 source +
     * original IDs). H2 PG-compat doesn't ship {@code jsonb_build_object}
     * <em>and</em> can't round-trip the jsonb-as-VARCHAR storage through
     * Hibernate's {@link io.hypersistence.utils.hibernate.type.json.JsonBinaryType}
     * (the existing {@code BatchLineageEdgeRepositoryTest} hits the same
     * limitation and works around it by setting {@code meta=null}).
     *
     * <p>The test substitutes {@code NULL} for {@code meta}. This narrows the
     * test scope to row-count + column mapping; the JSON content of meta is
     * verified separately when the migration runs against a real Postgres in
     * Step 3.5 of {@code deploy-smartbi-python.sh}.
     */
    private static final String BACKFILL_SQL = """
            INSERT INTO batch_lineage_edges (
                id, factory_id, edge_type, source_type, source_id,
                target_type, target_id, quantity_used, unit, event_time,
                operator_id, meta, created_at, updated_at
            )
            SELECT
                CAST(RANDOM_UUID() AS VARCHAR),
                br.factory_id,
                CASE br.relation_type
                    WHEN 'REWORK' THEN 'REWORK'
                    WHEN 'BLEND'  THEN 'BLEND'
                    ELSE 'RAW_TO_PRODUCTION'
                END,
                'MATERIAL_BATCH',
                br.material_batch_id,
                'PRODUCTION_BATCH',
                CAST(br.production_batch_id AS VARCHAR),
                br.quantity_used,
                br.unit,
                COALESCE(br.used_at, br.created_at, CURRENT_TIMESTAMP),
                br.operator_id,
                NULL,                                  -- H2 PG-compat: jsonb round-trip not supported; production uses jsonb_build_object
                COALESCE(br.created_at, CURRENT_TIMESTAMP),
                CURRENT_TIMESTAMP
            FROM batch_relations br
            WHERE br.deleted_at IS NULL
              AND br.material_batch_id IS NOT NULL
              AND br.production_batch_id IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM batch_lineage_edges e
                  WHERE e.factory_id   = br.factory_id
                    AND e.source_id    = br.material_batch_id
                    AND e.source_type  = 'MATERIAL_BATCH'
                    AND e.target_id    = CAST(br.production_batch_id AS VARCHAR)
                    AND e.target_type  = 'PRODUCTION_BATCH'
              )
            """;

    @Autowired private BatchLineageEdgeRepository edgeRepo;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;

    @BeforeEach
    void createBatchRelationsTable() {
        // Drop and recreate to ensure isolation between test methods within
        // the same context (DataJpaTest's @Transactional rolls back JPA, but
        // raw JDBC against batch_relations needs its own clean slate).
        jdbc.execute("DROP TABLE IF EXISTS batch_relations");
        jdbc.execute(CREATE_BATCH_RELATIONS);
    }

    @Test
    @DisplayName("Backfill — 2 batch_relations rows produce 2 lineage edges with correct mapping")
    void backfillMapsTwoRowsCorrectly() {
        // ----------------------------------------------------------
        // Seed: 2 active rows + 1 soft-deleted (must be skipped)
        // ----------------------------------------------------------
        insertRelation(F1, "INPUT", 100L, "material-A",
                new BigDecimal("12.500"), "kg",
                LocalDateTime.of(2026, 1, 10, 8, 30), null);

        insertRelation(F1, "REWORK", 100L, "material-B",
                new BigDecimal("3.250"), "kg",
                LocalDateTime.of(2026, 1, 11, 9, 45), null);

        insertRelation(F1, "INPUT", 200L, "material-X",
                new BigDecimal("99.999"), "kg",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0)); // soft-deleted

        long beforeEdges = edgeRepo.count();
        assertEquals(0, beforeEdges, "edges empty before backfill");

        // ----------------------------------------------------------
        // Run migration backfill INSERT
        // ----------------------------------------------------------
        int inserted = jdbc.update(BACKFILL_SQL);
        assertEquals(2, inserted,
                "exactly 2 rows backfilled (soft-deleted row excluded)");

        // ----------------------------------------------------------
        // Verify edge content
        // ----------------------------------------------------------
        List<BatchLineageEdge> downstreamA = edgeRepo
                .findByFactoryIdAndSourceIdAndSourceType(F1, "material-A", "MATERIAL_BATCH");
        assertEquals(1, downstreamA.size(), "material-A → 1 edge");
        BatchLineageEdge edgeA = downstreamA.get(0);
        assertEquals("RAW_TO_PRODUCTION", edgeA.getEdgeType(),
                "INPUT relation maps to RAW_TO_PRODUCTION edge_type");
        assertEquals("100", edgeA.getTargetId(),
                "Long production_batch_id 100 stringified to '100'");
        assertEquals("PRODUCTION_BATCH", edgeA.getTargetType());
        assertEquals(0, edgeA.getQuantityUsed().compareTo(new BigDecimal("12.500")),
                "quantity_used preserved");
        assertEquals("kg", edgeA.getUnit());
        assertNotNull(edgeA.getEventTime(), "event_time backfilled from used_at");

        List<BatchLineageEdge> downstreamB = edgeRepo
                .findByFactoryIdAndSourceIdAndSourceType(F1, "material-B", "MATERIAL_BATCH");
        assertEquals(1, downstreamB.size(), "material-B → 1 edge");
        BatchLineageEdge edgeB = downstreamB.get(0);
        assertEquals("REWORK", edgeB.getEdgeType(),
                "REWORK relation maps to REWORK edge_type");

        // ----------------------------------------------------------
        // Soft-deleted row was excluded
        // ----------------------------------------------------------
        List<BatchLineageEdge> downstreamX = edgeRepo
                .findByFactoryIdAndSourceIdAndSourceType(F1, "material-X", "MATERIAL_BATCH");
        assertTrue(downstreamX.isEmpty(),
                "soft-deleted batch_relations.material_batch_id='material-X' is NOT backfilled");
    }

    @Test
    @DisplayName("Idempotency — re-running backfill INSERT does not duplicate edges")
    void backfillIsIdempotent() {
        insertRelation(F1, "INPUT", 500L, "material-IDEMP",
                new BigDecimal("1.0"), "kg",
                LocalDateTime.of(2026, 2, 1, 12, 0), null);

        // First run
        int firstInsert = jdbc.update(BACKFILL_SQL);
        assertEquals(1, firstInsert, "first run inserts 1 edge");

        long countAfter1 = edgeRepo.count();
        assertEquals(1, countAfter1);

        // Second run — NOT EXISTS guard should skip
        int secondInsert = jdbc.update(BACKFILL_SQL);
        assertEquals(0, secondInsert,
                "second run inserts 0 rows (NOT EXISTS guard prevents duplicate)");

        long countAfter2 = edgeRepo.count();
        assertEquals(1, countAfter2, "edge count unchanged after re-run");

        // Confirm only ONE edge for the (factory, source, target) tuple
        List<BatchLineageEdge> edges = edgeRepo
                .findByFactoryIdAndSourceIdAndSourceType(F1, "material-IDEMP", "MATERIAL_BATCH");
        assertEquals(1, edges.size(),
                "unique edge — no dupe created by second migration run");
        assertEquals("500", edges.get(0).getTargetId());
    }

    @Test
    @DisplayName("BLEND relation_type maps to BLEND edge_type (not default RAW_TO_PRODUCTION)")
    void blendRelationMapsToBlendEdgeType() {
        insertRelation(F1, "BLEND", 777L, "material-BLEND",
                new BigDecimal("5.5"), "kg",
                LocalDateTime.now(), null);

        int inserted = jdbc.update(BACKFILL_SQL);
        assertEquals(1, inserted);

        BatchLineageEdge edge = edgeRepo
                .findByFactoryIdAndSourceIdAndSourceType(F1, "material-BLEND", "MATERIAL_BATCH")
                .get(0);
        assertEquals("BLEND", edge.getEdgeType(),
                "BLEND relation_type explicitly mapped to BLEND edge_type");
    }

    // ============================================================
    // Helper
    // ============================================================

    /**
     * Insert a row directly into the test {@code batch_relations} table via
     * JdbcTemplate. We bypass JPA because the {@link
     * com.cretas.aims.entity.BatchRelation} entity is intentionally NOT in the
     * test's {@code @EntityScan} to avoid bean conflicts with the parent app
     * context (see class-level Javadoc).
     */
    private void insertRelation(String factoryId, String relationType,
                                 Long productionBatchId, String materialBatchId,
                                 BigDecimal quantityUsed, String unit,
                                 LocalDateTime usedAt, LocalDateTime deletedAt) {
        String id = UUID.randomUUID().toString(); // 36 chars, fits VARCHAR(50)
        jdbc.update(INSERT_BATCH_RELATION,
                id, factoryId, productionBatchId, materialBatchId,
                relationType, quantityUsed, unit, usedAt,
                usedAt /* created_at */, deletedAt);
    }
}
