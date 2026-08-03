package com.cretas.aims.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 带鱼两条批次的单位「箱」→ kg —— {@code V20261029_50} 留下的 [待人工] 尾巴。
 *
 * <p><b>本测试的重点是「不该动的别动」</b>: 同样是「档案 kg / 批次 箱」, 带鱼要改而
 * SHH0713羊排<b>不能改</b> —— 羊排有 {@code package_unit=箱 / base_unit=kg /
 * conversion_factor=10} 的包装规格, 那批 10 箱 = 100 kg; 按「箱是错误单位」改成 kg,
 * 100kg 会静默变成 10kg。<b>包装单位存量是合法的, 不是缺陷。</b>
 */
@DisplayName("迁移契约 — 带鱼批次单位 箱→kg, 且不误伤有包装规格的物料")
class DaiyuBatchUnitMigrationContractTest {

    private static final Path SQL = Path.of("src", "main", "resources", "db", "flyway",
            "V20261029_51__daiyu_batch_unit_box_to_kg.sql");
    private static final Path ROLLBACK = Path.of("src", "main", "resources", "db", "manual-rollback",
            "V20261029_51__daiyu_batch_unit_box_to_kg_rollback.sql");

    private String sql() throws Exception {
        return Files.readString(SQL);
    }

    @Test
    @DisplayName("🔴 有包装规格的物料必须被排除 —— 否则羊排 100kg 会变成 10kg")
    void materialsWithPackagingSpecAreExcluded() throws Exception {
        assertThat(sql())
                .as("少了这个 NOT EXISTS, 「箱」是合法包装单位的批次也会被改写, 数量含义被篡改")
                .contains("NOT EXISTS")
                .contains("material_packaging_specs")
                .contains("s.package_unit = '箱'");
    }

    /**
     * 取 {@code VALUES (...)} 那段行清单 —— <b>只看可执行部分</b>。
     *
     * <p>⚠️ 第一版直接对整个文件断言 {@code doesNotContain("MT-20260716-3809")}, 结果被
     * <b>注释</b>命中而变红 —— 而那段注释(说明为什么排除羊排)恰恰是该留的文档。
     * 与「计数型契约要数代码构造不是字符出现」同一个毛病: <b>断言要落在可执行构造上</b>。
     */
    private String hardcodedRows(String sql) {
        int start = sql.indexOf("VALUES");
        assertThat(start).as("应有写死的行清单").isGreaterThan(0);
        int end = sql.indexOf("AS t(", start);
        return sql.substring(start, end);
    }

    @Test
    @DisplayName("🔴 只动写死的那两行 —— 不按状态动态判定, 且羊排不在名单里")
    void onlyTheTwoHardcodedRows() throws Exception {
        String rows = hardcodedRows(sql());
        assertThat(rows).contains("'F001'").contains("'DEMO_FACTORY'").contains("MB-TEST-20260102-001");
        assertThat(rows)
                .as("羊排那条(有 1箱=10kg 规格)绝不能进被改的名单")
                .doesNotContain("MT-20260716-3809");
        assertThat(rows.split("MB-TEST-20260102-001", -1).length - 1)
                .as("清单里恰好两行")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 值守卫齐全 —— 档案 kg + 批次 箱 都要断言, 状态变了就跳过不猜")
    void valueGuardsArePresent() throws Exception {
        String sql = sql();
        assertThat(sql).contains("b.quantity_unit = '箱'");
        assertThat(sql).contains("rt.unit = 'kg'");
        assertThat(sql)
                .as("守卫不成立时应报出来而不是静默无操作")
                .contains("[跳过]");
    }

    @Test
    @DisplayName("只改单位不改数量 —— 124.5 箱 → 124.5 kg")
    void quantityIsNotTouched() throws Exception {
        String sql = sql();
        assertThat(sql).contains("SET quantity_unit = 'kg'");
        assertThat(sql)
                .as("迁移不得改动 receipt_quantity / used_quantity")
                .doesNotContain("receipt_quantity =")
                .doesNotContain("used_quantity =");
    }

    @Test
    @DisplayName("回滚脚本存在且只回滚当前仍是 kg 的行")
    void rollbackScriptIsGuarded() throws Exception {
        assertThat(Files.exists(ROLLBACK)).isTrue();
        assertThat(Files.readString(ROLLBACK)).contains("quantity_unit = 'kg'");
    }
}
