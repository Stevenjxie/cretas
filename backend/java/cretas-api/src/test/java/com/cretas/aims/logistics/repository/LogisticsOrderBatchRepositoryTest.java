package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsOrderBatch;
import com.cretas.aims.logistics.entity.enums.OrderBatchStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 — JPA slice test for {@link LogisticsOrderBatchRepository} (H2 PG-compat,
 * mirrors {@code SemiFinishedInventoryRepositoryTest} conventions).
 *
 * <p>Verifies:
 * <ol>
 *   <li>save + read back — @PrePersist UUID id + audit cols + enum default PREVIEWED</li>
 *   <li>idempotency lookup — findByFactoryIdAndBusinessDateAndSourceFingerprintAndDeletedAtIsNull</li>
 *   <li>DB unique index {@code uq_lob_idempotent} rejects a true duplicate insert (same
 *       factory + business_date + source_fingerprint) — spec §2 决策 7 (幂等键, 禁静默双写)</li>
 *   <li>findByFactoryIdOrderByBusinessDateDesc — factory-scoped, newest first</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims")
@EnableJpaRepositories(basePackages = "com.cretas.aims")
@DisplayName("LogisticsOrderBatchRepository — JPA slice (H2 PG-compat)")
class LogisticsOrderBatchRepositoryTest {

    private static final String F1 = "F-LOG-1";
    private static final String F2 = "F-LOG-2";

    @Autowired
    private LogisticsOrderBatchRepository repo;

    @Test
    @DisplayName("save — @PrePersist UUID id + audit cols + status default PREVIEWED")
    void saveAssignsDefaults() {
        LogisticsOrderBatch saved = repo.saveAndFlush(batch(F1, LocalDate.of(2026, 7, 11), "BATCH-001", "fp-001"));

        assertNotNull(saved.getId(), "@PrePersist must assign UUID id");
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertEquals(OrderBatchStatus.PREVIEWED, saved.getStatus());
        assertEquals(0L, saved.getVersion(), "Hibernate assigns version 0 on first persist");

        LogisticsOrderBatch reloaded = repo.findById(saved.getId()).orElseThrow();
        assertEquals("BATCH-001", reloaded.getBatchNumber());
        assertEquals("fp-001", reloaded.getSourceFingerprint());
    }

    @Test
    @DisplayName("idempotency lookup — findByFactoryIdAndBusinessDateAndSourceFingerprintAndDeletedAtIsNull")
    void idempotencyLookup() {
        LocalDate date = LocalDate.of(2026, 7, 11);
        repo.saveAndFlush(batch(F1, date, "BATCH-IDEM-1", "fp-idem-1"));

        Optional<LogisticsOrderBatch> found =
                repo.findByFactoryIdAndBusinessDateAndSourceFingerprintAndDeletedAtIsNull(F1, date, "fp-idem-1");
        assertTrue(found.isPresent(), "should locate existing batch by idempotency key");
        assertEquals("BATCH-IDEM-1", found.get().getBatchNumber());

        assertTrue(
                repo.findByFactoryIdAndBusinessDateAndSourceFingerprintAndDeletedAtIsNull(F1, date, "fp-no-such")
                        .isEmpty(),
                "unknown fingerprint returns empty — no accidental match");
    }

    @Test
    @DisplayName("DB unique index uq_lob_idempotent rejects true duplicate (factory+date+fingerprint)")
    void duplicateIdempotencyKeyRejected() {
        LocalDate date = LocalDate.of(2026, 7, 11);
        repo.saveAndFlush(batch(F1, date, "BATCH-DUP-1", "fp-dup"));

        LogisticsOrderBatch dup = batch(F1, date, "BATCH-DUP-2", "fp-dup"); // same factory+date+fingerprint
        assertThrows(DataIntegrityViolationException.class, () -> repo.saveAndFlush(dup),
                "second insert with identical (factory_id, business_date, source_fingerprint) must violate uq_lob_idempotent");
    }

    @Test
    @DisplayName("findByFactoryIdOrderByBusinessDateDesc — factory scoped, newest first")
    void findByFactoryOrderedDesc() {
        repo.saveAndFlush(batch(F1, LocalDate.of(2026, 7, 1), "BATCH-OLD", "fp-old"));
        repo.saveAndFlush(batch(F1, LocalDate.of(2026, 7, 11), "BATCH-NEW", "fp-new"));
        repo.saveAndFlush(batch(F2, LocalDate.of(2026, 7, 20), "BATCH-OTHER-FACTORY", "fp-other")); // other factory

        List<LogisticsOrderBatch> rows = repo.findByFactoryIdOrderByBusinessDateDesc(F1);
        assertEquals(2, rows.size(), "F1 has 2 batches (F2 excluded)");
        assertEquals("BATCH-NEW", rows.get(0).getBatchNumber(), "newest business_date first");
        assertEquals("BATCH-OLD", rows.get(1).getBatchNumber());
    }

    // ============================================================
    // Helper
    // ============================================================

    private LogisticsOrderBatch batch(String factoryId, LocalDate businessDate, String batchNumber, String fingerprint) {
        return LogisticsOrderBatch.builder()
                .factoryId(factoryId)
                .businessDate(businessDate)
                .batchNumber(batchNumber)
                .sourceFilename("orders.xlsx")
                .sourceFingerprint(fingerprint)
                .build();
    }
}
