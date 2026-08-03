package com.cretas.aims.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只 / 个 / 件 是三个单位 —— 撤销 {@code V20261029_48} 对<b>计数单位</b>的合并。
 *
 * <p><b>为什么只撤一部分</b>: V20261029_48 把所有中文单位归一成码, 其中两类性质完全不同 ——
 * 盒→box / 箱→case 是「一个码对一个中文写法」的纯翻译, 无信息损失;
 * 件/个/只 → pcs 是「一个码对三个中文写法」, 等于替工厂断定三者相同。
 * Steve 2026-08-03 拍板「算两个单位」, 同日另一条是「存量不动」, 所以只撤后一类。
 *
 * <p><b>本测试钉住的是几条容易被"顺手统一"掉的判据</b>, 都是源码级断言 ——
 * 迁移跑在 prod 上, 语义用例覆盖不到, 只能靠对着 SQL 文本断言关键谓词还在。
 */
@DisplayName("迁移契约 — 计数单位 只/个/件 各自独立")
class CountUnitsAreDistinctMigrationContractTest {

    private static final Path SQL = Path.of("src", "main", "resources", "db", "flyway",
            "V20261029_50__count_units_are_distinct.sql");
    private static final Path ROLLBACK = Path.of("src", "main", "resources", "db", "manual-rollback",
            "V20261029_50__count_units_are_distinct_rollback.sql");

    private String sql() throws Exception {
        return Files.readString(SQL);
    }

    /**
     * 取第 {@code occurrence} 个 {@code UPDATE <table>} 语句的正文(到分号为止)。
     *
     * <p>⚠️ 不能靠「全文里 {@code b.new_unit = 'pcs'} 出现几次」来判 —— 那个串在诊断用的
     * SELECT 里也有一次, 共 3 次。变异实测: 删掉<b>一处 UPDATE 守卫</b>后仍剩 2 次,
     * 断言 {@code >= 2} 照样绿。<b>计数型契约要数「代码构造」不是「字符出现」。</b>
     */
    private String updateBlock(String sql, String table) {
        int start = sql.indexOf("UPDATE " + table);
        assertThat(start).as("应存在 UPDATE %s 语句", table).isGreaterThan(0);
        int end = sql.indexOf(';', start);
        return sql.substring(start, end);
    }

    @Test
    @DisplayName("🔴 只撤计数那一批 —— 两处 UPDATE 各自都要带 new_unit = 'pcs'")
    void restoresOnlyTheCountBatch() throws Exception {
        String sql = sql();
        for (String table : new String[]{"raw_material_types", "product_types"}) {
            assertThat(updateBlock(sql, table))
                    .as("%s 的还原少了这个谓词, 会把 盒/箱/袋 也一起还原成中文, 违背「存量不动」", table)
                    .contains("b.new_unit = 'pcs'");
        }
    }

    @Test
    @DisplayName("🔴 不覆盖迁移之后被人手工改过的行 —— 两处 UPDATE 各自都要有这层守卫")
    void doesNotOverwriteManualEdits() throws Exception {
        String sql = sql();
        for (String table : new String[]{"raw_material_types", "product_types"}) {
            assertThat(updateBlock(sql, table))
                    .as("%s 少了 t.unit = b.new_unit, 人工修正会被台账旧值盖回去", table)
                    .contains("t.unit = b.new_unit");
        }
    }

    @Test
    @DisplayName("🔴 批次翻译表<b>不得</b>含 件/个/只 —— 否则刚还原成中文又被折回码")
    void translationTableExcludesCountUnits() throws Exception {
        String sql = sql();
        int tableStart = sql.indexOf("INSERT INTO _zh_to_code");
        int tableEnd = sql.indexOf(';', tableStart);
        assertThat(tableStart).as("翻译表应存在").isGreaterThan(0);
        String table = sql.substring(tableStart, tableEnd);
        assertThat(table)
                .as("件/个/只 正是本迁移要还原成中文的那批, 放进翻译表等于自我抵消")
                .doesNotContain("'件'")
                .doesNotContain("'个'")
                .doesNotContain("'只'");
        assertThat(table).contains("'盒','box'").contains("'箱','case'");
    }

    @Test
    @DisplayName("🔴 量纲不一致的只报不改 —— 「5 箱」不能被改写成「5 公斤」")
    void dimensionMismatchIsReportedNotUpdated() throws Exception {
        String sql = sql();
        // 批次跟随档案的那条 UPDATE 必须要求「档案存的正是该中文写法的权威码」,
        // 即 r.unit = m.code —— 有它才排除得掉 档案kg / 批次箱 这种量纲错配。
        assertThat(sql)
                .as("少了 rt.unit = m.code, 任何中文批次单位都会被改成档案单位, 含量纲不同的")
                .contains("rt.unit = m.code");
        assertThat(sql)
                .as("量纲错配的行应通过 RAISE NOTICE 报给人工")
                .contains("[待人工]");
    }

    @Test
    @DisplayName("台账不存在时整条跳过 —— 没跑过 V20261029_48 的库不该报错")
    void skipsWhenBackupLedgerAbsent() throws Exception {
        assertThat(sql()).contains("to_regclass('public.backup_sku_units_20260802') IS NULL");
    }

    @Test
    @DisplayName("回滚脚本存在, 且写明必须一并回滚写入侧")
    void rollbackScriptWarnsAboutWritePath() throws Exception {
        assertThat(Files.exists(ROLLBACK)).as("回滚脚本必须随迁移一起提供").isTrue();
        assertThat(Files.readString(ROLLBACK))
                .as("只回数据不回代码 → 下次保存又漂回去, 正是 V20261029_32 的老毛病")
                .contains("必须一并回滚写入侧");
    }
}
