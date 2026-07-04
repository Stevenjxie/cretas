package com.cretas.aims.entity.enums;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the ck_it_status CHECK constraint drift bug (4th occurrence
 * of the "enum widened but DB CHECK constraint not synced" pattern in this repo,
 * see V20261027_15 / V20261027_29 / V20261027_34 / V20261027_35).
 *
 * <p>{@code internal_transfers.ck_it_status} originates from the legacy
 * {@code database/p3_transfer_pricelist_pg.sql} bootstrap script (not Flyway-tracked)
 * and only allowed 8 of the 9 values in {@link TransferStatus}. It was missing
 * {@code REVERSED}, which #1218 added to support 撤销小结 (interim-settle reversal):
 * {@code FinishedGoodsFeedServiceImpl.reverseInterimCreate} calls
 * {@code setStatus(TransferStatus.REVERSED)} on same-factory (source==target) CONFIRMED
 * transfers whose FG child batch is reversed. Because the reversal is {@code @Transactional},
 * the CHECK violation rolls back the entire reversal transaction (500) and re-bricks the
 * #1214-unbricked 撤销小结 path whenever the FG was partially moved by an intra-factory transfer.</p>
 *
 * <p>H2 unit tests and mocked repositories can't catch this — the CHECK constraint
 * is PG-only and only exercised against real Postgres. This test instead asserts,
 * at compile/CI time, that the widening migration
 * {@code V20261027_37__widen_internal_transfer_status_check.sql} enumerates every
 * current {@link TransferStatus} value, so a future enum addition without a matching
 * CHECK-constraint migration fails CI instead of silently drifting.</p>
 */
class TransferStatusCheckConstraintTest {

    private static final String MIGRATION_RESOURCE =
            "db/flyway/V20261027_37__widen_internal_transfer_status_check.sql";

    @Test
    void widiningMigrationExistsOnClasspath() {
        ClassPathResource resource = new ClassPathResource(MIGRATION_RESOURCE);
        assertTrue(resource.exists(),
                "Expected " + MIGRATION_RESOURCE + " to be present on the classpath "
                        + "(backend/java/cretas-api/src/main/resources/" + MIGRATION_RESOURCE + ")");
    }

    @Test
    void widiningMigrationCoversEveryTransferStatusValue() throws IOException {
        String sql = readMigration();

        for (TransferStatus status : TransferStatus.values()) {
            assertTrue(sql.contains("'" + status.name() + "'"),
                    "ck_it_status widening migration is missing enum value '" + status.name()
                            + "' — DB CHECK constraint would reject rows of this status "
                            + "even though TransferStatus.java allows it (same drift class "
                            + "as V20261027_15/29/34/35).");
        }
    }

    @Test
    void widiningMigrationSpecificallyCoversReversedThatBlockedInterimSettleReversal() throws IOException {
        String sql = readMigration();

        assertTrue(sql.contains("'REVERSED'"),
                "REVERSED missing — this is what re-bricked 撤销小结-after-intra-transfer (#1218 setStatus).");
    }

    @Test
    void widiningMigrationIsIdempotent() throws IOException {
        String sql = readMigration();

        assertTrue(sql.contains("DROP CONSTRAINT IF EXISTS ck_it_status"),
                "Migration must DROP CONSTRAINT IF EXISTS before re-adding, so re-running it "
                        + "(or running it on an environment where the constraint was already "
                        + "widened manually) does not fail.");
    }

    private static String readMigration() throws IOException {
        ClassPathResource resource = new ClassPathResource(MIGRATION_RESOURCE);
        return new String(Files.readAllBytes(resource.getFile().toPath()), StandardCharsets.UTF_8);
    }
}
