package com.cretas.aims.service.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 盯 {@code V20261029_40__seed_missing_count_units.sql} 的源码级用例。
 *
 * <p>这条迁移有一个**加了等于没加**的失效方式: 名录行插进去了, 但 {@code usage_scopes_json}
 * 少了 {@code INVENTORY_QUANTITY} —— 写入侧
 * {@code RawMaterialTypeServiceImpl#normalizeInventoryUnit} 会调
 * {@code supportsUsage(..., INVENTORY_QUANTITY)}, 少了这个 scope 的单位一律被判成
 * 「该单位不能用于入库计量」而拒绝。行在库里、功能没有, 且不会报错。</p>
 *
 * <p>另一个失效方式是有人改迁移时漏掉某个码 —— 权威别名表里的计数单位与名录又对不齐,
 * 正是本次要修的问题本身。</p>
 */
class MissingCountUnitSeedMigrationTest {

    private static final String[] SEEDED_CODES = {"roll", "slice", "portion", "crate", "pail", "item"};
    private static final String[] SEEDED_NAMES = {"卷", "片", "份", "框", "桶", "项"};

    private static String migrationSource() throws Exception {
        Path path = Paths.get("src/main/resources/db/flyway/V20261029_40__seed_missing_count_units.sql");
        assertThat(Files.exists(path))
                .as("迁移文件必须存在: %s", path.toAbsolutePath())
                .isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("六个缺失的计数码都在迁移里, 且中文名与权威别名表一致")
    void seedsEveryMissingCountingCode() throws Exception {
        String sql = migrationSource();
        for (int i = 0; i < SEEDED_CODES.length; i++) {
            assertThat(sql)
                    .as("缺少单位码 %s", SEEDED_CODES[i])
                    .contains("'" + SEEDED_CODES[i] + "'");
            assertThat(sql)
                    .as("缺少中文名 %s", SEEDED_NAMES[i])
                    .contains("'" + SEEDED_NAMES[i] + "'");
        }
    }

    @Test
    @DisplayName("🔴 必须给 INVENTORY_QUANTITY —— 少了它行在库里但入库时会被判「不能用于入库计量」")
    void grantsInventoryQuantityScope() throws Exception {
        assertThat(migrationSource()).contains("INVENTORY_QUANTITY");
    }

    @Test
    @DisplayName("必须是幂等的 —— 重复执行不能撞 UNIQUE (factory_id, unit_code)")
    void isIdempotent() throws Exception {
        assertThat(migrationSource()).containsIgnoringCase("NOT EXISTS");
    }

    @Test
    @DisplayName("补的是系统级全局单位 (factory_id='*'), 不是塞给某个工厂")
    void seedsAsGlobalSystemUnits() throws Exception {
        String sql = migrationSource();
        assertThat(sql).contains("'*'");
        assertThat(sql).contains("COUNT");
    }

    @Test
    @DisplayName("展示名与 UnitDisplayNames 对得上 —— 两处写法漂了就是又一次「多处承载」")
    void agreesWithDisplayNames() {
        for (int i = 0; i < SEEDED_CODES.length; i++) {
            assertThat(UnitDisplayNames.display(SEEDED_CODES[i]))
                    .as("%s 的展示名应与迁移里的中文名一致", SEEDED_CODES[i])
                    .isEqualTo(SEEDED_NAMES[i]);
        }
    }

    @Test
    @DisplayName("V20261029_42: 「张」同时补进名录和权威别名表 —— 只补一处兜底那层仍不认")
    void seedsSheetUnitInBothPlaces() throws Exception {
        Path migration = Paths.get(
                "src/main/resources/db/flyway/V20261029_42__drop_dangling_bom_and_seed_sheet_unit.sql");
        assertThat(Files.exists(migration)).isTrue();
        String sql = Files.readString(migration, StandardCharsets.UTF_8);
        assertThat(sql).contains("'sheet'").contains("'张'").contains("INVENTORY_QUANTITY");
        assertThat(sql).containsIgnoringCase("NOT EXISTS");

        // 权威别名表那一半 (只改名录的话, normalize() 的兜底层仍认不出「张」)
        Path aliases = Paths.get(
                "src/main/java/com/cretas/aims/service/unit/impl/UnitContractServiceImpl.java");
        assertThat(Files.readString(aliases, StandardCharsets.UTF_8))
                .as("权威别名表必须同步补 sheet")
                .contains("alias(aliases, \"sheet\", \"sheet\", \"张\")");

        // 展示层那一半
        assertThat(UnitDisplayNames.display("sheet")).isEqualTo("张");
    }

    @Test
    @DisplayName("V20261029_42: 悬空 BOM 用软删 + NOT EXISTS 判据, 不是写死 id 硬删")
    void dropsDanglingBomBySoftDeleteWithGuard() throws Exception {
        String sql = Files.readString(Paths.get(
                "src/main/resources/db/flyway/V20261029_42__drop_dangling_bom_and_seed_sheet_unit.sql"),
                StandardCharsets.UTF_8);
        assertThat(sql).contains("deleted_at = now()");
        assertThat(sql).doesNotContain("DELETE FROM bom_recipes");
        assertThat(sql).contains("NOT EXISTS").contains("product_types");
    }
}
