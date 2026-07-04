package com.cretas.aims.entity.enums;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the vouchers_voucher_type_check CHECK constraint drift bug
 * (5th occurrence of the "enum widened but DB CHECK constraint not synced" pattern
 * in this repo, see V20261027_15 / V20261027_29 / V20261027_34 / V20261027_35 /
 * V20261027_37).
 *
 * <p>PR #1226 (期末结转成本 / period-end cost carry) added {@link VoucherType#COST_CARRYOVER}
 * to record 借 6401 主营业务成本 / 贷 1405 库存商品 at period close, but
 * {@code vouchers.vouchers_voucher_type_check} (last widened by V20261027_29 to add
 * INVENTORY_STOCKTAKE) only allowed 9 of the 10 {@link VoucherType} values. Inserting
 * a COST_CARRYOVER voucher during period-close would violate the CHECK constraint,
 * causing a 500 and rolling back the entire (transactional) period-close operation
 * on its first run. The PR author initially claimed no such CHECK constraint existed;
 * a direct query against prod {@code pg_constraint} confirmed it does (2026-07-04).</p>
 *
 * <p>H2 unit tests and mocked repositories can't catch this — the CHECK constraint
 * is PG-only and only exercised against real Postgres. This test instead asserts,
 * at compile/CI time, that the widening migration
 * {@code V20261027_38__widen_vouchers_voucher_type_check.sql} enumerates every
 * current {@link VoucherType} value, so a future enum addition without a matching
 * CHECK-constraint migration fails CI instead of silently drifting.</p>
 */
class VoucherTypeCheckConstraintTest {

    private static final String MIGRATION_RESOURCE =
            "db/flyway/V20261027_38__widen_vouchers_voucher_type_check.sql";

    @Test
    void widiningMigrationExistsOnClasspath() {
        ClassPathResource resource = new ClassPathResource(MIGRATION_RESOURCE);
        assertTrue(resource.exists(),
                "Expected " + MIGRATION_RESOURCE + " to be present on the classpath "
                        + "(backend/java/cretas-api/src/main/resources/" + MIGRATION_RESOURCE + ")");
    }

    @Test
    void widiningMigrationCoversEveryVoucherTypeValue() throws IOException {
        String sql = readMigration();

        for (VoucherType type : VoucherType.values()) {
            assertTrue(sql.contains("'" + type.name() + "'"),
                    "vouchers_voucher_type_check widening migration is missing enum value '"
                            + type.name() + "' — DB CHECK constraint would reject rows of this "
                            + "voucher_type even though VoucherType.java allows it (same drift "
                            + "class as V20261027_15/29/34/35/37).");
        }
    }

    @Test
    void widiningMigrationSpecificallyCoversCostCarryoverThatBlockedPeriodEndClose() throws IOException {
        String sql = readMigration();

        assertTrue(sql.contains("'COST_CARRYOVER'"),
                "COST_CARRYOVER missing — this is what would brick the first period-end "
                        + "cost-carry close (#1226, VoucherType.COST_CARRYOVER insert).");
    }

    @Test
    void widiningMigrationIsIdempotent() throws IOException {
        String sql = readMigration();

        assertTrue(sql.contains("DROP CONSTRAINT IF EXISTS vouchers_voucher_type_check"),
                "Migration must DROP CONSTRAINT IF EXISTS before re-adding, so re-running it "
                        + "(or running it on an environment where the constraint was already "
                        + "widened manually) does not fail.");
    }

    private static String readMigration() throws IOException {
        ClassPathResource resource = new ClassPathResource(MIGRATION_RESOURCE);
        return new String(Files.readAllBytes(resource.getFile().toPath()), StandardCharsets.UTF_8);
    }
}
