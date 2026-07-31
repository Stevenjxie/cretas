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
                        .as("结单单位归一必须与权威表一致: %s", unit)
                        .isEqualTo(UnitContractServiceImpl.canonicalCodeOrRaw(unit));
            }
        }

        /** Workflow 快照比对: 判断某个投入是不是「在 slot re-keying 时消失了」。 */
        @ParameterizedTest
        @CsvSource({"袋, bag", "件, 个", "框, 筐", "公斤, kg"})
        void bomWorkflowRevisionSameUnitUsesAuthority(String left, String right) throws Exception {
            Method sameUnit = BomWorkflowRevisionService.class
                    .getDeclaredMethod("sameUnit", String.class, String.class);
            sameUnit.setAccessible(true);
            assertThat((boolean) sameUnit.invoke(null, left, right))
                    .as("%s 与 %s 是同一个单位, 不该被判成投入消失了", left, right)
                    .isTrue();
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
