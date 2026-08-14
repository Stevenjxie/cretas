package com.cretas.aims.service.processentry;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钉住 {@code convertReportingQuantityToStorage} 的判等口径。
 *
 * <p><b>为什么需要这条</b>: 2026-08-14 实测, 这个方法原来用私有表
 * {@code massUnitCode} 判「两个单位是不是同一个」, 而那张表把
 * {@code 片/slice/piece/pcs/个} 折成一个桶 —— 于是<b>报工填「个」、批次存储单位是「片」
 * 被判成同一单位, 数量原样扣库存</b>。方法自己的 javadoc 写着「其他单位不能猜测,
 * 否则会把只/袋当 kg, 直接污染库存与成本」, 被它自己的 helper 破掉了。
 *
 * <p>不是理论风险: 生产库里 56 种原料用「个」、7 种用「片」(成品侧 8/4), 且有活批次。
 *
 * <p><b>两个方向各钉一条</b> —— 只钉「个≠片」会让人用「把所有折叠删光」蒙混过去,
 * 而那会把 2026-07-31 咬过客户的<b>误拦</b>(袋≠bag / 盒≠box)引回来。
 */
class ProcessSheetReportingUnitFoldTest {

    // ── 漏拦方向: 不同的计数单位不许互相冒充 ──────────────────────────────
    @Test
    @DisplayName("报工「个」不能按存储单位「片」扣减 —— 计数单位按字面比较")
    void countUnitsAreNotInterchangeable() {
        assertThatThrownBy(() -> convert(new BigDecimal("10"), "个", "片"))
                .hasCauseInstanceOf(BusinessException.class)
                .cause()
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo("PROCESS_SHEET_SOURCE_UNIT_MISMATCH"));
    }

    @Test
    @DisplayName("英文写法同样不许冒充: pcs 报工 / 片 存储")
    void englishSpellingDoesNotBypassTheContract() {
        assertThatThrownBy(() -> convert(new BigDecimal("10"), "pcs", "片"))
                .hasCauseInstanceOf(BusinessException.class);
    }

    // ── 误拦方向: 同一个单位的两种写法必须仍然认 ──────────────────────────
    @Test
    @DisplayName("盒 与 box 是同一个单位的两种写法 —— 必须放行, 否则是 2026-07-31 那条误拦")
    void crossLanguageSpellingOfTheSameUnitStillPasses() throws Exception {
        assertThat(convert(new BigDecimal("7"), "盒", "box")).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("同一个单位原样通过")
    void identicalUnitPassesThrough() throws Exception {
        assertThat(convert(new BigDecimal("3.5"), "片", "片")).isEqualByComparingTo("3.5");
    }

    // ── 质量换算不能被这次改动碰掉 ────────────────────────────────────────
    @Test
    @DisplayName("kg 报工 / g 存储 仍然 ×1000")
    void kgToGramStillConverts() throws Exception {
        assertThat(convert(new BigDecimal("2"), "kg", "g")).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("g 报工 / kg 存储 仍然 ÷1000")
    void gramToKgStillConverts() throws Exception {
        assertThat(convert(new BigDecimal("2500"), "g", "kg")).isEqualByComparingTo("2.5");
    }

    @Test
    @DisplayName("中文质量写法也走同一条换算: 公斤 → 克")
    void chineseMassSpellingConverts() throws Exception {
        assertThat(convert(new BigDecimal("1"), "公斤", "克")).isEqualByComparingTo("1000");
    }

    // ── 阳性对照: 仪器本身能测 ────────────────────────────────────────────
    @Test
    @DisplayName("阳性对照 —— 反射真的拿到了那个方法, 而不是静默跳过")
    void theInstrumentActuallyReachesTheMethod() throws Exception {
        assertThat(method()).isNotNull();
        assertThat(method().getParameterCount())
                .as("签名变了就该在这里红, 而不是让上面几条悄悄失去覆盖")
                .isEqualTo(5);
    }

    // ── harness ───────────────────────────────────────────────────────────
    /**
     * 类上是 {@code @RequiredArgsConstructor}, 构造器吃全部 final 协作者 —— 这里绕过构造,
     * {@code unitContractService} 因而为 null。这不是将就: 此时
     * {@code configuredUnitsEquivalent} 退到 {@code builtInUnitsEquivalent},
     * 走的正是 UnitContractServiceImpl 的内置权威表 —— 也就是本测试要钉的那个口径。
     */
    private static BigDecimal convert(BigDecimal qty, String reportingUnit, String storageUnit)
            throws Exception {
        Method m = method();
        m.setAccessible(true);
        ProcessSheetServiceImpl target =
                org.mockito.Mockito.mock(ProcessSheetServiceImpl.class,
                        org.mockito.Mockito.CALLS_REAL_METHODS);
        return (BigDecimal) m.invoke(target, "F006", qty, reportingUnit, storageUnit, "原料批次");
    }

    private static Method method() throws Exception {
        return ProcessSheetServiceImpl.class.getDeclaredMethod(
                "convertReportingQuantityToStorage",
                String.class, BigDecimal.class, String.class, String.class, String.class);
    }
}
