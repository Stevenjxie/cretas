package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 放宽 {@code bom_recipe_items.chk_bri_category} 到第四个值 BYPRODUCT 的 migration 契约。
 *
 * <p>🔴 这条约束是 2026-07-31 接手 Task 5 时在 prod 实测发现的:
 * <pre>chk_bri_category CHECK (material_category IN ('RAW','AUXILIARY','PACKAGING'))</pre>
 * 计划里的 Task 5 是**纯前端**的 —— 加完「副产」页签与「添加副产」按钮后, 保存必然被这条
 * DB 约束打回。这正是本仓已发作 8 次的「枚举加了值, DB CHECK 白名单没跟上」漂移
 * (见 V20261029_33 的扫描注释)。</p>
 *
 * <p><b>为什么通用门禁没抓到它</b>: {@code EnumCheckConstraintDriftTest} 扫的是
 * {@code @Enumerated} 字段, 而 {@code BomRecipeItem.materialCategory} 是裸 String
 * (见该实体 material_category 列) —— 不在门禁视野内。所以这一条只能由本测试盯着。</p>
 *
 * <p><b>存量影响 = 0</b>: 放宽前不可能有任何一行是 BYPRODUCT (旧约束就是这么禁的),
 * 所以这是纯加值, 不可能让既有行违反新约束, 也不改变任何已出过的数字。</p>
 */
class BomByproductCategoryMigrationContractTest {

    private static final Path SQL = Path.of("src", "main", "resources", "db", "flyway",
            "V20261029_37__bom_recipe_item_byproduct_category.sql");

    @Test
    void migrationWidensCategoryCheckToIncludeByproduct() throws Exception {
        assertThat(SQL).exists();
        String sql = Files.readString(SQL);

        assertThat(sql).as("必须点名要改的约束").contains("chk_bri_category");
        assertThat(sql).as("第四个值").contains("BYPRODUCT");
    }

    /**
     * 放宽不能顺手把旧的三个值弄丢 —— 那会让 169 行既有明细 (prod 实测, 其中 42 行 PACKAGING)
     * 一次性全部违反约束。加值类 migration 最容易犯的错就是重建时漏抄。
     */
    @Test
    void migrationKeepsAllThreeLegacyCategories() throws Exception {
        String sql = Files.readString(SQL);
        for (String legacy : new String[]{"RAW", "AUXILIARY", "PACKAGING"}) {
            assertThat(sql).as("重建约束时漏了既有值 %s", legacy).contains("'" + legacy + "'");
        }
    }

    /** 本仓 migration 必须可重复执行: 先 DROP IF EXISTS 再 ADD, 重跑不炸。 */
    @Test
    void migrationIsIdempotent() throws Exception {
        String sql = Files.readString(SQL).toUpperCase();
        assertThat(sql).contains("DROP CONSTRAINT IF EXISTS");
    }

    @Test
    void migrationLivesInTheActiveFlywayDirectory() {
        assertThat(SQL.getParent().toString().replace('\\', '/'))
                .as("活跃目录是 db/flyway")
                .endsWith("db/flyway");
        assertThat(Path.of("src", "main", "resources", "db", "flyway",
                "V20261029_36__byproduct_sku_and_credit.sql"))
                .as("阳性对照: 同目录下已知存在的前一个 migration —— 它不在就说明我在查错目录")
                .exists();
    }
}
