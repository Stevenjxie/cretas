package com.cretas.aims.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeactivateRestaurantTenantsMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/flyway/V20261029_53__deactivate_nonmock_restaurant_tenants.sql");
    private static final Path ROLLBACK = Path.of(
        "../../../scripts/rollback/V20261029_53_rollback.sql");

    @Test
    @DisplayName("不删任何业务数据")
    void neverDeletes() throws Exception {
        assertThat(Files.readString(MIGRATION).toUpperCase()).doesNotContain("DELETE FROM");
    }

    @Test
    @DisplayName("用户与租户一起停 —— 只停租户会造出「能登录但 AI 全拒」的半死状态")
    void deactivatesUsersToo() throws Exception {
        // A plain contains() on the whole file text cannot distinguish an
        // executing statement from the same text sitting inside a "-- ..."
        // comment (verified by mutation: prefixing the statement line with
        // "-- MUTATED: " left the old assertion green). Require the
        // statement to be the start of an actual (non-comment) line instead.
        List<String> lines = Files.readAllLines(MIGRATION);
        boolean hasExecutableStatement = lines.stream()
                .map(String::trim)
                .anyMatch(line -> line.startsWith("UPDATE users SET is_active = false"));
        assertThat(hasExecutableStatement)
                .as("expected an executable (non-commented) 'UPDATE users SET "
                        + "is_active = false' statement in %s", MIGRATION)
                .isTrue();
    }

    @Test
    @DisplayName("台账 factory_id 与 user_id 是两个不同类型的列")
    void ledgerSeparatesIdTypes() throws Exception {
        String sql = Files.readString(MIGRATION);
        assertThat(sql).contains("factory_id   varchar(255)");
        assertThat(sql).contains("user_id      bigint");
    }

    @Test
    @DisplayName("有 MOCK_REST 存在性前置断言, 否则会把所有餐饮租户停光")
    void hasFailClosedPrecondition() throws Exception {
        // Same pattern as deactivatesUsersToo: require the statement to be
        // in an actual executable line, not commented out. If the entire
        // precondition block is prefixed with "-- ", this test must fail.
        List<String> lines = Files.readAllLines(MIGRATION);
        boolean hasFailClosedCheck = lines.stream()
                .map(String::trim)
                .anyMatch(line -> line.startsWith("RAISE EXCEPTION") && line.contains("MOCK_REST"));
        assertThat(hasFailClosedCheck)
                .as("expected an executable (non-commented) RAISE EXCEPTION statement "
                        + "for MOCK_REST check in %s", MIGRATION)
                .isTrue();
    }

    @Test
    @DisplayName("回滚按台账精确恢复, 不是「全部改回 true」")
    void rollbackIsLedgerDriven() throws Exception {
        String sql = Files.readString(ROLLBACK);
        assertThat(sql).contains("restaurant_consolidation_ledger_20260805");
        assertThat(sql).doesNotContain("WHERE type = 'RESTAURANT'");
    }
}
