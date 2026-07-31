package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 副产批次与单价确认列的 migration 契约。
 *
 * <p>🔴 单价刻意<b>允许 NULL</b>: 盘点确认前不臆造 0 —— 0 会被读成「这批副产不值钱」,
 * 而 NULL 如实表达「还没人确认过」。本仓禁降级处理, 这条不能松。</p>
 */
class ByproductCreditMigrationContractTest {

    private static final Path SQL = Path.of("src", "main", "resources", "db", "flyway",
            "V20261029_36__byproduct_sku_and_credit.sql");

    @Test
    void migrationAddsByproductColumnsAndIsIdempotent() throws Exception {
        assertThat(SQL).exists();
        String sql = Files.readString(SQL);

        for (String column : new String[]{
                "byproduct_source_report_id", "byproduct_unit_price",
                "byproduct_price_confirmed_at", "byproduct_price_confirmed_by"}) {
            assertThat(sql).as("缺列 %s", column).contains(column);
        }
        // 幂等: 本仓 migration 必须可重复执行
        assertThat(sql.toUpperCase()).contains("IF NOT EXISTS");
        // 单价必须允许 NULL —— 未确认就是 null, 禁降级不许默认 0
        assertThat(sql).doesNotContain("byproduct_unit_price NUMERIC(15,4) NOT NULL");
        assertThat(sql).doesNotContain("DEFAULT 0");
    }

    /**
     * 落在 db/flyway 而不是 db/migration —— 后者不是本仓的活跃 migration 目录,
     * 写进去的文件永远不会被执行 (2026-07-31 预检时差点写错)。
     */
    @Test
    void migrationLivesInTheActiveFlywayDirectory() {
        assertThat(SQL.getParent().toString().replace('\\', '/'))
                .as("活跃目录是 db/flyway")
                .endsWith("db/flyway");
        assertThat(Path.of("src", "main", "resources", "db", "flyway",
                "V20261029_35__supplier_short_name_unique.sql"))
                .as("阳性对照: 同目录下已知存在的前一个 migration —— 它不在就说明我在查错目录")
                .exists();
    }
}
