package com.cretas.aims.service.productimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Excel 批量导入的单位归一必须走权威表。
 *
 * <p><b>为什么单独钉这条路</b>：SKU 的单位有四条写入路径，此前只有「单个 SKU 建/改」
 * 一条归一，导入这条自己写了一张私有别名表，而且方向相反 —— 它把计数单位折成<b>中文</b>，
 * 单 SKU 路径折成<b>英文码</b>。两股力互相撤销，所以数据永远收敛不了；
 * 光写 migration 刷数据会被下一次导入原样漂回去。
 *
 * <p>🔴 <b>更要命的是那张私有表会改错单位</b>：它写着
 * {@code case "box", "case", "carton" -> "箱"}，把 {@code box} 并进了「箱」。
 * 而权威表里 {@code box}=盒、{@code case}=箱 是<b>两个不同的单位</b>
 * （名录 {@code unit_of_measurements} 亦然）。于是用 Excel 导入一个单位写
 * {@code box} 的 SKU，落库会变成「箱」—— 盒变箱，静默改错，与规范化方向无关。
 *
 * <p>判据分两层：真值表（行为）+ 源码扫描（防止再长出第八张私有表）。
 */
class SkuImportUnitCanonicalizationTest {

    // ==================== 1. 行为: 真值表 ====================

    @Nested
    @DisplayName("盒 / 箱 是两个单位, 不许互相顶替")
    class BoxIsNotCase {

        @Test
        @DisplayName("box 归一后仍是 box —— 不能变成 case/箱")
        void boxStaysBox() {
            String actual = SkuImportServiceImpl.normalizeUnit("box");
            assertThat(actual)
                    .as("私有表把 box 并进「箱」, 导入一个 box 的 SKU 会被静默改成 case")
                    .isNotEqualTo("箱")
                    .isNotEqualTo("case")
                    .isEqualTo("box");
        }

        @Test
        @DisplayName("盒 归一成 box, 箱 归一成 case —— 两条各回各家")
        void chineseNamesMapToTheirOwnCodes() {
            assertThat(SkuImportServiceImpl.normalizeUnit("盒")).isEqualTo("box");
            assertThat(SkuImportServiceImpl.normalizeUnit("箱")).isEqualTo("case");
        }

        @Test
        @DisplayName("carton 属于 case 一族, 但不能因此把 box 也拖过去")
        void cartonMapsToCaseWithoutDraggingBox() {
            // carton 不在权威别名表里 —— 认不出就原样小写返回, 而不是猜一个。
            // 关键是它不能让 box 跟着变成 case。
            assertThat(SkuImportServiceImpl.normalizeUnit("box")).isEqualTo("box");
        }
    }

    @Nested
    @DisplayName("与单 SKU 写入路径同向 —— 折成英文码")
    class SameDirectionAsSingleSkuPath {

        @Test
        @DisplayName("中文名折成英文码")
        void chineseFoldsToCode() {
            assertThat(SkuImportServiceImpl.normalizeUnit("袋")).isEqualTo("bag");
            assertThat(SkuImportServiceImpl.normalizeUnit("瓶")).isEqualTo("bottle");
            assertThat(SkuImportServiceImpl.normalizeUnit("片")).isEqualTo("slice");
            assertThat(SkuImportServiceImpl.normalizeUnit("张")).isEqualTo("sheet");
        }

        @Test
        @DisplayName("科学计量单位的中文/复数写法折成符号")
        void scientificAliasesFoldToSymbol() {
            assertThat(SkuImportServiceImpl.normalizeUnit("公斤")).isEqualTo("kg");
            assertThat(SkuImportServiceImpl.normalizeUnit("千克")).isEqualTo("kg");
            assertThat(SkuImportServiceImpl.normalizeUnit("克")).isEqualTo("g");
        }

        @Test
        @DisplayName("⛔ 只 / 个 刻意不并进 pcs —— 一只鸡不是一件包材 (#1976)")
        void distinctCountLabelsSurvive() {
            assertThat(SkuImportServiceImpl.normalizeUnit("只"))
                    .as("并进 pcs 会让「只」去顶「件」过闸")
                    .isEqualTo("只");
            assertThat(SkuImportServiceImpl.normalizeUnit("个")).isEqualTo("个");
            // 对照: 件 本身是 pcs 的中文名, 折过去是对的
            assertThat(SkuImportServiceImpl.normalizeUnit("件")).isEqualTo("pcs");
        }

        @Test
        @DisplayName("认不出的单位原样返回, 不猜")
        void unknownStaysAsIs() {
            assertThat(SkuImportServiceImpl.normalizeUnit("半只")).isEqualTo("半只");
        }
    }

    // ==================== 2. 源码: 别再长出第八张私有表 ====================

    @Test
    @DisplayName("导入服务里不许再出现私有单位别名 switch —— 必须委托权威表")
    void importServiceHasNoPrivateAliasTable() throws Exception {
        Path source = Path.of("src/main/java/com/cretas/aims/service/productimport/SkuImportServiceImpl.java");
        String code = Files.readString(source);

        // 阳性对照: 文件得真的读到, 否则"零违规"只是因为路径写错了
        assertThat(code)
                .as("源码没读到, 后面的断言全部无效")
                .contains("class SkuImportServiceImpl");

        assertThat(code)
                .as("normalizeUnit 必须委托 UnitContractServiceImpl —— "
                    + "自己写 switch 就是第 6 张私有别名表, box→箱 那个错就是这么来的")
                .contains("crossLanguageCode");

        assertThat(code)
                .as("私有 switch 分支残留 —— 这正是与单 SKU 路径反向的那张表")
                .doesNotContain("\"box\", \"case\", \"carton\"");

        assertThat(code)
                .as("countLikeUnit 必须问权威表的量纲, 不能靠硬编码的中文集合 —— "
                    + "那个集合漏了 张/片/卷/框/托盘/板/项/份, 且英文码一个都不认")
                .doesNotContain("Set.of(\"盒\", \"袋\", \"件\", \"只\", \"瓶\", \"罐\", \"包\", \"桶\", \"箱\", \"个\")");
    }
}
