package com.cretas.aims.entity.enums;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the ck_aat_type CHECK constraint drift bug (3rd occurrence
 * of the "enum widened but DB CHECK constraint not synced" pattern in this repo,
 * see V20261027_15 / V20261027_29 / V20261027_34).
 *
 * <p>{@code ar_ap_transactions.ck_aat_type} originates from the legacy
 * {@code database/p4_pos_arap_pg.sql} bootstrap script (not Flyway-tracked) and
 * only allowed 6 of the 8 values in {@link ArApTransactionType}. It was missing
 * {@code AP_CREDIT_NOTE} / {@code AR_CREDIT_NOTE}, which blocked the 期初建账
 * AP-correction endpoint ({@code POST /{factoryId}/finance/opening/correct-misrouted-ap})
 * used by SupplierMonthlyReconciliationServiceImpl / PaymentRequestServiceImpl.</p>
 *
 * <p>H2 unit tests and mocked repositories can't catch this — the CHECK constraint
 * is PG-only and only exercised against real Postgres. This test instead asserts,
 * at compile/CI time, that the widening migration
 * {@code V20261027_35__widen_ar_ap_transaction_type_check.sql} enumerates every
 * current {@link ArApTransactionType} value, so a future enum addition without a
 * matching CHECK-constraint migration fails CI instead of silently drifting.</p>
 */
class ArApTransactionTypeCheckConstraintTest {

    private static final String MIGRATION_RESOURCE =
            "db/flyway/V20261027_35__widen_ar_ap_transaction_type_check.sql";

    @Test
    void widiningMigrationExistsOnClasspath() {
        ClassPathResource resource = new ClassPathResource(MIGRATION_RESOURCE);
        assertTrue(resource.exists(),
                "Expected " + MIGRATION_RESOURCE + " to be present on the classpath "
                        + "(backend/java/cretas-api/src/main/resources/" + MIGRATION_RESOURCE + ")");
    }

    @Test
    void widiningMigrationCoversEveryArApTransactionTypeValue() throws IOException {
        String sql = readMigration();

        for (ArApTransactionType type : ArApTransactionType.values()) {
            assertTrue(sql.contains("'" + type.name() + "'"),
                    "ck_aat_type widening migration is missing enum value '" + type.name()
                            + "' — DB CHECK constraint would reject rows of this type "
                            + "even though ArApTransactionType.java allows it (same drift class "
                            + "as V20261027_15/29/34).");
        }
    }

    @Test
    void widiningMigrationSpecificallyCoversTheTwoCreditNoteTypesThatBlockedOpeningApCorrection() throws IOException {
        String sql = readMigration();

        assertTrue(sql.contains("'AP_CREDIT_NOTE'"),
                "AP_CREDIT_NOTE missing — this is what blocked correct-misrouted-ap");
        assertTrue(sql.contains("'AR_CREDIT_NOTE'"),
                "AR_CREDIT_NOTE missing — same drift class, must be included defensively");
    }

    @Test
    void widiningMigrationIsIdempotent() throws IOException {
        String sql = readMigration();

        assertTrue(sql.contains("DROP CONSTRAINT IF EXISTS ck_aat_type"),
                "Migration must DROP CONSTRAINT IF EXISTS before re-adding, so re-running it "
                        + "(or running it on an environment where the constraint was already "
                        + "widened manually) does not fail.");
    }

    private static String readMigration() throws IOException {
        ClassPathResource resource = new ClassPathResource(MIGRATION_RESOURCE);
        return new String(Files.readAllBytes(resource.getFile().toPath()), StandardCharsets.UTF_8);
    }
}
