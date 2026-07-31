package com.cretas.aims.service.unit;

import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.inventory.FeedUnitConverter;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单位别名的<b>唯一权威</b>契约。
 *
 * <p>🔴 起因 (2026-07-31 客户现场): 报工被 409 挡住 ——「报工单位 <b>袋</b>, BOM 单位 <b>bag</b>」。
 * 它们在权威表里本就是同一个单位 ({@code alias("bag","bag","袋")})。根因不是数据脏, 是
 * <b>至少五个地方各自手抄了一张单位别名 switch</b>, 覆盖 2~21 组不等, 而权威表有 24 组:</p>
 *
 * <ul>
 *   <li>{@code ProcessSheetServiceImpl.canonicalBomUnit} —— 5 组 (已于 #2077 修)</li>
 *   <li>{@code ProductionPlanServiceImpl.canonicalReceiptUnit} —— 5 组, 结单两道 blocking 门</li>
 *   <li>{@code BomWorkflowRevisionService.canonicalUnit} —— 21 组, <b>已经漂了</b> (crate 别名不一致)</li>
 *   <li>{@code BomSeasoningWorkspaceServiceImpl.canonicalWorkflowUnit} —— 7 组</li>
 *   <li>{@code ProductionWarehouseReceiptMobileController.canonicalUnit} —— 2 组</li>
 * </ul>
 *
 * <p>两个方向都会错: 表里<b>没有</b>的原样返回 → 同一个单位被判成两个 (<b>误拦</b>);
 * 抄本里<b>多折</b>的 (个/片 都成 slice) → 本该拦的混过去 (<b>漏拦</b>)。</p>
 *
 * <p>所以它们现在全部委托到 {@link UnitContractServiceImpl#canonicalCodeOrRaw}。
 * 这组测试守的就是<b>「别再抄第六张表」</b>。</p>
 */
class UnitAliasAuthorityContractTest {

    @Nested
    @DisplayName("权威入口本身")
    class Authority {

        @ParameterizedTest(name = "{0} 与 {1} 是同一个单位")
        @CsvSource({
                // 客户现场那一对
                "袋, bag", "bag, 袋",
                // 权威表里同码的中英/多写法 —— 任意两处比较都不该判成不一致
                "件, pcs", "个, pcs", "只, pcs", "个, 件",
                "盒, box", "箱, case", "包, pack", "瓶, bottle", "罐, can",
                "桶, pail", "卷, roll", "片, slice", "份, portion", "项, item",
                "公斤, kg", "千克, kg", "斤, jin", "吨, t", "克, g", "升, l", "毫升, ml",
                // crate: 权威表的别名是「框」和「筐」—— 手抄那份漏了「框」多了「篮」
                "框, crate", "筐, crate", "框, 筐",
        })
        void aliasesOfTheSameUnitCollapseToOneCode(String left, String right) {
            assertThat(UnitContractServiceImpl.canonicalCodeOrRaw(left))
                    .isEqualTo(UnitContractServiceImpl.canonicalCodeOrRaw(right));
        }

        @ParameterizedTest(name = "{0} 与 {1} 不是同一个单位")
        @CsvSource({
                // 🔴 反方向: 抄本里 个/片 都折成 slice, 于是「个」能冒充「片」混过去
                "个, 片", "pcs, slice", "件, 片",
                // 计数 vs 包装 vs 质量, 各自独立
                "盒, 箱", "袋, 包", "kg, g", "只, 份", "瓶, 罐",
        })
        void genuinelyDifferentUnitsStayDifferent(String left, String right) {
            assertThat(UnitContractServiceImpl.canonicalCodeOrRaw(left))
                    .isNotEqualTo(UnitContractServiceImpl.canonicalCodeOrRaw(right));
        }

        /**
         * 🔴 权威别名表里 {@code alias("pcs","pcs","件","个","只")} —— 三个中文都归 pcs。
         * 但 <b>#1976 规定「一只 ≠ 一件」</b>: 计数单位按字面区分, 一只鸡不是一件包材。
         *
         * <p>所以做<b>相等判定</b>的地方必须用 {@code crossLanguageCode} 而不是
         * {@code canonicalCodeOrRaw} —— 前者只折「英文码 ↔ 该单位的中文名」,
         * 于是 袋≡bag 修好了, 而 只≠件 保住了。</p>
         *
         * <p>2026-07-31 我自己在 #2077/#2079 用错了后者, 等于悄悄放宽了这条; 本组用例就是防它再发生。</p>
         */
        @ParameterizedTest(name = "{0} 与 {1} 是同一单位的中英两种写法")
        @CsvSource({"袋, bag", "盒, box", "箱, case", "包, pack", "瓶, bottle", "罐, can",
                    "桶, pail", "卷, roll", "片, slice", "份, portion", "项, item",
                    "件, pcs", "框, crate",
                    // 可换算维度照旧全折 (千克/公斤/kg 真的是一个单位)
                    "千克, kg", "公斤, kg", "克, g", "斤, jin", "吨, t", "升, l", "毫升, ml"})
        void crossLanguageSpellingsCollapse(String chinese, String code) {
            assertThat(UnitContractServiceImpl.crossLanguageCode(chinese))
                    .isEqualTo(UnitContractServiceImpl.crossLanguageCode(code));
        }

        @ParameterizedTest(name = "{0} 与 {1} 仍是两种单位 (#1976)")
        @CsvSource({"只, 件", "个, 件", "只, 个", "只, pcs", "个, pcs"})
        void differentChineseCountingUnitsAreNotMerged(String left, String right) {
            assertThat(UnitContractServiceImpl.crossLanguageCode(left))
                    .as("#1976: 计数单位按字面区分, 一只 ≠ 一件 —— 合并会让「只」冒充「件」过闸")
                    .isNotEqualTo(UnitContractServiceImpl.crossLanguageCode(right));
        }

        @Test
        @DisplayName("canonicalCodeOrRaw 会合并 只/件 —— 记录这个差别, 相等判定别用它")
        void canonicalCodeOrRawIsTheLooserOne() {
            // 这不是缺陷, 是分工: canonicalCodeOrRaw 用于「归一成展示/存储用的码」,
            // crossLanguageCode 用于「判两个单位是不是同一个」。用错了就会踩 #1976。
            assertThat(UnitContractServiceImpl.canonicalCodeOrRaw("只"))
                    .isEqualTo(UnitContractServiceImpl.canonicalCodeOrRaw("件"));
            assertThat(UnitContractServiceImpl.crossLanguageCode("只"))
                    .isNotEqualTo(UnitContractServiceImpl.crossLanguageCode("件"));
        }

        @Test
        @DisplayName("表里没有的单位不猜, 原样小写返回 (不折成'最像'的那个)")
        void unknownUnitsAreLeftAlone() {
            assertThat(UnitContractServiceImpl.canonicalCodeOrRaw("  奇怪单位 "))
                    .isEqualTo("奇怪单位");
            assertThat(UnitContractServiceImpl.canonicalCodeOrRaw("WeirdUnit")).isEqualTo("weirdunit");
            assertThat(UnitContractServiceImpl.canonicalCodeOrRaw(null)).isNull();
        }
    }

    @Nested
    @DisplayName("各调用点确实委托到了权威入口 (别再抄第六张表)")
    class Delegation {

        /** 结单/实收: 两道 blocking 门 (WORKFLOW_OUTPUT_UNIT_MISMATCH / PRODUCTION_SETTLEMENT_OUTPUT_UNIT_MISMATCH) 拿它做 equals。 */
        @Test
        void productionPlanReceiptUnitMatchesAuthority() throws Exception {
            Method method = Class.forName("com.cretas.aims.service.impl.ProductionPlanServiceImpl")
                    .getDeclaredMethod("canonicalReceiptUnit", String.class);
            method.setAccessible(true);
            for (String unit : new String[]{"袋", "bag", "件", "个", "pcs", "箱", "框", "筐", "kg", "公斤", "未知单位"}) {
                assertThat(method.invoke(null, unit))
                        .as("结单单位归一必须与权威表的**跨语言**归一一致 (不是 canonicalCodeOrRaw ——"
                                + " 后者会把 只/个/件 并成 pcs, 违反 #1976): %s", unit)
                        .isEqualTo(UnitContractServiceImpl.crossLanguageCode(unit));
            }
        }

        /**
         * Workflow 快照比对: 判断某个投入是不是「在 slot re-keying 时消失了」。
         *
         * <p>⚠️ 这一处<b>刻意</b>连 只/个/件 一起认 —— 与结单闸不同。既有用例
         * {@code BomWorkflowRevisionServiceTest#localizedCountUnitMatchesCanonicalBomUnitDuringStableSlotRekeying}
         * 明确断言 {@code unitsCompatible("pcs","只")} 为 true。#1976「一只 ≠ 一件」管的是
         * 数量/库存换算, 不管槽位匹配, 两者别混。</p>
         */
        @ParameterizedTest
        @CsvSource({"袋, bag", "件, pcs", "框, 筐", "公斤, kg", "只, pcs", "个, 只"})
        void bomWorkflowRevisionSameUnitUsesAuthority(String left, String right) throws Exception {
            Method sameUnit = BomWorkflowRevisionService.class
                    .getDeclaredMethod("sameUnit", String.class, String.class);
            sameUnit.setAccessible(true);
            assertThat((boolean) sameUnit.invoke(null, left, right))
                    .as("%s 与 %s 是同一个单位, 不该被判成投入消失了", left, right)
                    .isTrue();
        }

        /**
         * 这三处 2026-07-31 之前各自维护私有表 (报工 2 组 / 出成率 5 组 / 订单成本 5 组,
         * 后两张<b>逐字相同</b>)。都用 crossLanguageCode —— 它们的结果参与数量换算与
         * 成本分摊维度分组, 必须守住 #1976「一只 ≠ 一件」。
         */
        @ParameterizedTest(name = "{0}#{1} 委托到 crossLanguageCode")
        @CsvSource({
                "com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl, normalizeReportingUnit",
                "com.cretas.aims.service.yield.impl.YieldReportServiceImpl, normalizeUnit",
                "com.cretas.aims.service.yield.OrderCostBreakdownService, normalizeUnit",
        })
        void quantityPathsDelegateToCrossLanguageCode(String className, String methodName) throws Exception {
            Method method = Class.forName(className).getDeclaredMethod(methodName, String.class);
            method.setAccessible(true);
            for (String unit : new String[]{"袋", "bag", "盒", "box", "件", "pcs", "只", "个",
                                            "框", "筐", "kg", "公斤", "克", "未知单位"}) {
                assertThat(method.invoke(null, unit))
                        .as("%s#%s 必须与权威表的跨语言归一一致: %s", className, methodName, unit)
                        .isEqualTo(UnitContractServiceImpl.crossLanguageCode(unit));
            }
        }

        @Test
        @DisplayName("报工侧 null 仍返回空串 (调用方按 \"kg\".equals 判, 且空串要参与 distinct)")
        void reportingUnitKeepsEmptyStringForNull() throws Exception {
            Method method = Class.forName("com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl")
                    .getDeclaredMethod("normalizeReportingUnit", String.class);
            method.setAccessible(true);
            assertThat(method.invoke(null, (Object) null)).isEqualTo("");
        }

        @Test
        @DisplayName("Workflow 快照比对仍然区分真正不同的单位")
        void bomWorkflowRevisionStillSeparatesRealDifferences() throws Exception {
            Method sameUnit = BomWorkflowRevisionService.class
                    .getDeclaredMethod("sameUnit", String.class, String.class);
            sameUnit.setAccessible(true);
            assertThat((boolean) sameUnit.invoke(null, "个", "片")).isFalse();
            assertThat((boolean) sameUnit.invoke(null, "盒", "箱")).isFalse();
        }
    }

    @Nested
    @DisplayName("计数单位判定 (错了不报错, 只是算出来的数不对)")
    class CountingUnits {

        @ParameterizedTest
        @ValueSource(strings = {
                // 🔴 英文码 —— 原实现只匹配中文字符, 这些全被当成质量单位走 kg 数学。
                //    客户那个 SKU 的单位存的正是 bag。
                "bag", "box", "case", "pack", "bottle", "can", "pcs", "portion",
                "crate", "pail", "roll", "slice", "item",
                // 中文里原来也漏的
                "箱", "框", "筐", "桶", "卷", "片", "项",
                // 原来就认得的 (只增不减)
                "盒", "袋", "包", "个", "件", "只", "份", "瓶", "罐",
                // 复合标签走模糊匹配那一段
                "盒(500g)", "大盒",
        })
        void countingUnitsAreRecognised(String unit) {
            assertThat(FeedUnitConverter.isCountUnit(unit))
                    .as("%s 是计数单位, 必须经 gramsPerUnit 折算, 不能当 kg 直投", unit)
                    .isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"kg", "g", "mg", "t", "jin", "公斤", "千克", "克", "吨", "斤", "ml", "l", "毫升", "升"})
        void massAndVolumeUnitsAreNotCountingUnits(String unit) {
            assertThat(FeedUnitConverter.isCountUnit(unit))
                    .as("%s 是质量/体积单位, 不该要求 gramsPerUnit", unit)
                    .isFalse();
        }

        @Test
        void nullIsNotACountingUnit() {
            assertThat(FeedUnitConverter.isCountUnit(null)).isFalse();
        }
    }

    /**
     * 前端 {@code utils/feedUnitConversion.ts} 里有一份**必须与后端同口径**的计数单位表。
     *
     * <p>两处实现同一件事 = 本次事故的形态本身。所以这里直接读那个 .ts 文件比对 ——
     * 谁改了一边没改另一边, 这条就红。</p>
     */
    @Nested
    @DisplayName("前后端计数单位表必须一致")
    class FrontendParity {

        @Test
        void frontendCountUnitSetCoversExactlyTheAuthorityCountingUnits() throws Exception {
            Path ts = Path.of("..", "..", "..", "web-admin", "src", "utils", "feedUnitConversion.ts")
                    .toAbsolutePath().normalize();
            assertThat(ts).as("前端计数单位表文件应存在: %s", ts).exists();
            String source = Files.readString(ts);

            Matcher block = Pattern
                    .compile("COUNT_UNIT_CODES\\s*=\\s*new Set\\(\\[(.*?)]\\)", Pattern.DOTALL)
                    .matcher(source);
            assertThat(block.find()).as("前端应有 COUNT_UNIT_CODES 集合").isTrue();

            Set<String> frontend = new LinkedHashSet<>();
            Matcher entry = Pattern.compile("'([^']+)'").matcher(block.group(1));
            while (entry.find()) {
                frontend.add(entry.group(1));
            }

            // 后端权威: 所有 COUNT/PACKAGE 单位的规范码 + 全部中文别名
            Set<String> backend = new LinkedHashSet<>();
            for (String candidate : ALL_KNOWN_UNIT_SPELLINGS) {
                if (UnitContractServiceImpl.isBuiltInCountingUnit(candidate)) {
                    backend.add(candidate);
                }
            }

            assertThat(frontend)
                    .as("前端计数单位表与后端 COUNT/PACKAGE 量纲必须逐字一致 —— "
                            + "改了一边没改另一边, 投料折算就会两边算出不同的数")
                    .containsExactlyInAnyOrderElementsOf(backend);
        }
    }

    /** 权威表里出现过的所有写法 (规范码 + 中文别名), 用来反查量纲。 */
    private static final Set<String> ALL_KNOWN_UNIT_SPELLINGS = Set.of(
            "mg", "毫克", "g", "克", "kg", "公斤", "千克", "jin", "斤", "t", "吨",
            "ml", "毫升", "l", "升",
            "mm", "毫米", "cm", "厘米", "m", "米", "km", "千米",
            "pcs", "件", "个", "只", "portion", "份", "slice", "片", "item", "项",
            "box", "盒", "case", "箱", "bag", "袋", "pack", "包", "bottle", "瓶",
            "can", "罐", "crate", "框", "筐", "pail", "桶", "roll", "卷");
}
