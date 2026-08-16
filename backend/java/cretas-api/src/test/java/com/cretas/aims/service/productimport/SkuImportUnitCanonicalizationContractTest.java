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
 * <p>🔴 <b>此前这里是一张私有别名表，会改错单位</b>：它写着
 * {@code case "box", "case", "carton" -> "箱"}，把 {@code box} 并进了「箱」。
 * 而权威表里 {@code box}=盒、{@code case}=箱 是<b>两个不同的单位</b>
 * （名录 {@code unit_of_measurements} 亦然）。于是用 Excel 导入一个单位写
 * {@code box} 的 SKU，落库会变成「箱」—— <b>盒变箱，静默改错</b>。
 *
 * <p>同一张表还把「个」折成「件」，正是 #1976 明确要拆开的那种合并
 * （一只鸡不是一件包材）。
 *
 * <p>⛔ <b>输出仍是中文展示名，不是英文码</b>。本方法的结果会流进
 * {@code buildSpecification} 拼出的<b>规格串</b>（{@code 200g/盒 50盒/箱}），那是给
 * 用户看的 —— 返回码会让规格串变成 {@code 200g/box 50box/case}，正是
 * {@code V20261029_32} 开头写的「用户从来不认识 pcs」那个病。
 * 存储层要不要统一成英文码是另一件事，得连展示层一起改，不在本轮。
 *
 * <p>判据分两层：真值表（行为）+ 源码扫描（防止再长出第八张私有表）。
 */
class SkuImportUnitCanonicalizationContractTest {

    // ==================== 1. 行为: 真值表 ====================

    @Nested
    @DisplayName("盒 / 箱 是两个单位, 不许互相顶替")
    class BoxIsNotCase {

        @Test
        @DisplayName("box 归一后是「盒」—— 不能变成「箱」")
        void boxStaysBox() {
            String actual = SkuImportServiceImpl.normalizeUnit("box");
            assertThat(actual)
                    .as("私有表把 box 并进「箱」, 导入一个 box 的 SKU 会被静默改成 case")
                    .isNotEqualTo("箱")
                    .isEqualTo("盒");
        }

        @Test
        @DisplayName("盒 与 箱 各回各家, 不互相顶替")
        void chineseNamesMapToTheirOwnCodes() {
            assertThat(SkuImportServiceImpl.normalizeUnit("盒")).isEqualTo("盒");
            assertThat(SkuImportServiceImpl.normalizeUnit("箱")).isEqualTo("箱");
        }

        @Test
        @DisplayName("carton 属于 case 一族, 但不能因此把 box 也拖过去")
        void cartonMapsToCaseWithoutDraggingBox() {
            // carton 原本只活在私有表里, 收敛时已并进权威表 alias("case", ..., "carton")。
            assertThat(SkuImportServiceImpl.normalizeUnit("carton")).isEqualTo("箱");
            assertThat(SkuImportServiceImpl.normalizeUnit("case")).isEqualTo("箱");
            // 关键: 它不能让 box 跟着变成箱。
            assertThat(SkuImportServiceImpl.normalizeUnit("box")).isEqualTo("盒");
        }
    }

    @Nested
    @DisplayName("英文码折成中文展示名 —— 输出要能直接进规格串给用户看")
    class FoldsToChineseDisplayName {

        @Test
        @DisplayName("英文码折成中文名 (规格串是给用户看的, 不能出现 box/case)")
        void codesFoldToChinese() {
            assertThat(SkuImportServiceImpl.normalizeUnit("bag")).isEqualTo("袋");
            assertThat(SkuImportServiceImpl.normalizeUnit("bottle")).isEqualTo("瓶");
            assertThat(SkuImportServiceImpl.normalizeUnit("slice")).isEqualTo("片");
            // sheet 此前在权威表里只有别名没有定义, 折不动
            assertThat(SkuImportServiceImpl.normalizeUnit("sheet")).isEqualTo("张");
            assertThat(SkuImportServiceImpl.normalizeUnit("张")).isEqualTo("张");
        }

        @Test
        @DisplayName("科学计量单位折成符号 —— 秤上单据上都这么写, 不翻中文")
        void scientificAliasesFoldToSymbol() {
            assertThat(SkuImportServiceImpl.normalizeUnit("公斤")).isEqualTo("kg");
            assertThat(SkuImportServiceImpl.normalizeUnit("千克")).isEqualTo("kg");
            assertThat(SkuImportServiceImpl.normalizeUnit("克")).isEqualTo("g");
            // kgs / kilogram(s) / gram(s) 原本只活在私有表里, 已并进权威表
            assertThat(SkuImportServiceImpl.normalizeUnit("kgs")).isEqualTo("kg");
            assertThat(SkuImportServiceImpl.normalizeUnit("kilograms")).isEqualTo("kg");
            assertThat(SkuImportServiceImpl.normalizeUnit("grams")).isEqualTo("g");
        }

        @Test
        @DisplayName("⛔ 只 / 个 刻意不并进 pcs —— 一只鸡不是一件包材 (#1976)")
        void distinctCountLabelsSurvive() {
            assertThat(SkuImportServiceImpl.normalizeUnit("只"))
                    .as("并进 pcs 会让「只」去顶「件」过闸")
                    .isEqualTo("只");
            assertThat(SkuImportServiceImpl.normalizeUnit("个"))
                    .as("私有表把「个」折成「件」, 正是 #1976 要拆开的那种合并")
                    .isEqualTo("个");
            // 对照: 件 是 pcs 的中文名, 原地不动
            assertThat(SkuImportServiceImpl.normalizeUnit("件")).isEqualTo("件");
            assertThat(SkuImportServiceImpl.normalizeUnit("pc")).isEqualTo("件");
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
