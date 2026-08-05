package com.cretas.aims.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
        assertThat(Files.readString(MIGRATION)).contains("UPDATE users SET is_active = false");
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
        String sql = Files.readString(MIGRATION);
        assertThat(sql).contains("MOCK_REST 不存在或不是 RESTAURANT 类型");
        assertThat(sql).contains("RAISE EXCEPTION");
    }

    @Test
    @DisplayName("回滚按台账精确恢复, 不是「全部改回 true」")
    void rollbackIsLedgerDriven() throws Exception {
        String sql = Files.readString(ROLLBACK);
        assertThat(sql).contains("restaurant_consolidation_ledger_20260805");
        assertThat(sql).doesNotContain("WHERE type = 'RESTAURANT'");
    }
}
