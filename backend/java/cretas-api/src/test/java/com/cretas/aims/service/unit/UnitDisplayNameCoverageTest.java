package com.cretas.aims.service.unit;

import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import com.cretas.aims.repository.material.MaterialPackagingSpecRepository;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 闸 —— 用户看到的单位必须是中文；两份展示名不许漂。
 *
 * <h2>为什么是「两份一致」而不是「各自都对」</h2>
 *
 * 展示名在本仓有<b>两个承载点</b>, 而且抽不成一份:
 * <ul>
 *   <li>{@link UnitContractService#describe} 返回的 {@code CanonicalUnit.displayName()}
 *       —— 权威, 但要注入 Spring, 异常类里拿不到</li>
 *   <li>{@link UnitDisplayNames} —— 纯静态, 后端拼用户文案时用它
 *       (报工缺料 409 那类话就是在异常类里拼的)</li>
 * </ul>
 *
 * 形态 D: 同一个东西有两份, 它一定会漂。抽不动就得有一道闸钉住两份必须一致 ——
 * 这道闸钉的就是这个, ⛔ 不是「各自都对」(那是两条独立断言, 同时绿也不排除内容不同)。
 *
 * <h2>判据</h2>
 * 契约目录里每一个<b>非中文</b>的码, 二选一:
 * <ol>
 *   <li>在 {@link #INTERNATIONAL_SYMBOLS} 白名单上 —— 秤上单据上都这么写, 刻意不翻</li>
 *   <li>在 {@link UnitDisplayNames} 里有条目, <b>且与契约的 displayName 逐字相同</b></li>
 * </ol>
 *
 * <p>🔴 2026-08-18 建闸时抓到的两条: {@code t} 和 {@code jin} 两个都不在表里 ——
 * 用户会看到 "5t" 和 "3jin"。{@code jin} 尤其明显, 那是<b>拼音</b>不是符号。
 * 判据是<b>「用户认不认得这个写法」</b>, 不是「它属于哪个量纲」——
 * 原来的注释写成了后者(「WEIGHT/VOLUME/LENGTH 的符号一律不进这张表」),
 * 于是拼音码顺着量纲被放过了。
 *
 * <p>⚠️ 白名单是<b>代理判据</b>(形态 E: 静态分析判不出「用户认不认得」)。
 * 它必须<b>逐个显式列出</b>, 不许写成「latin 字母就算符号」那种启发式 ——
 * 那正是 {@code jin} 溜过去的原因。
 */
class UnitDisplayNameCoverageTest {

    /**
     * 国际通行的计量符号 —— 刻意不翻。每一个都得是<b>用户在秤/单据/国标上见过的写法</b>。
     *
     * <p>⚠️ 这是<b>代理判据</b>: 静态分析判不出「用户认不认得」。所以必须<b>逐个显式列出</b>,
     * ⛔ 不许写成「latin 字母就算符号」那类启发式 —— {@code jin} 正是这么溜过去的。
     *
     * <p>{@code t} 在表内: GB 3100 里吨的法定符号就是 t, 既有断言
     * {@code UnitDisplayNamesTest#keepsScientificSymbolsAsIs} 守着它。
     */
    private static final Set<String> INTERNATIONAL_SYMBOLS = Set.of(
            "mg", "g", "kg", "t",      // 质量
            "ml", "l",                 // 体积
            "mm", "cm", "m", "km");    // 长度

    private static UnitContractService service() {
        UnitOfMeasurementRepository unitRepo = mock(UnitOfMeasurementRepository.class);
        when(unitRepo.findAllByFactoryId(anyString())).thenReturn(List.of());
        return new UnitContractServiceImpl(
                unitRepo,
                mock(ProductUnitConversionRepository.class),
                mock(MaterialPackagingHierarchyRepository.class),
                mock(MaterialPackagingSpecRepository.class));
    }

    private static boolean hasChinese(String s) {
        return s.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    @Test
    @DisplayName("阳性对照: 目录里真的有单位, 且中文码/拉丁码两类都在 —— 否则下面的遍历是空转")
    void catalogIsPopulated() {
        List<CanonicalUnit> catalog = service().catalog("F006");
        assertTrue(catalog.size() >= 20, "目录只有 " + catalog.size() + " 个单位, 这道闸在读空气");
        assertTrue(catalog.stream().anyMatch(u -> hasChinese(u.code())), "一个中文码都没有, 分类判据失效");
        assertTrue(catalog.stream().anyMatch(u -> !hasChinese(u.code())), "一个拉丁码都没有, 本闸无事可做");
    }

    @Test
    @DisplayName("🔴 每个非中文码要么是国际符号, 要么有中文展示名 —— 不许让用户看到 jin / t")
    void everyNonChineseCodeIsEitherSymbolOrTranslated() {
        List<String> offenders = new ArrayList<>();
        for (CanonicalUnit unit : service().catalog("F006")) {
            String code = unit.code();
            if (hasChinese(code) || INTERNATIONAL_SYMBOLS.contains(code)) {
                continue;
            }
            String shown = UnitDisplayNames.display(code);
            if (!hasChinese(shown)) {
                offenders.add(code + " → 用户看到「" + shown + "」(契约里它叫「"
                        + unit.displayName() + "」)");
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些码会原样丢给用户看, 既不在符号白名单上也没有中文展示名: " + offenders);
    }

    @Test
    @DisplayName("🔴 两份展示名必须逐字相同 —— 抽不成一份, 就得钉住不许漂")
    void displayNamesAgreeWithContract() {
        List<String> drifted = new ArrayList<>();
        for (CanonicalUnit unit : service().catalog("F006")) {
            String shown = UnitDisplayNames.display(unit.code());
            // 只对「这张表收了的码」比对: 没收的走原样返回, 由上一条断言管
            if (shown.equals(unit.code())) {
                continue;
            }
            if (!shown.equals(unit.displayName())) {
                drifted.add(unit.code() + ": 静态表说「" + shown
                        + "」, 契约说「" + unit.displayName() + "」");
            }
        }
        assertTrue(drifted.isEmpty(), "两份展示名不一致: " + drifted);
    }

    @Test
    @DisplayName("ton / tonne 这类英文单词要归一到 t —— 契约原来不认, 于是原样漏给用户")
    void tonneWordsNormalizeToSymbol() {
        UnitContractService svc = service();
        for (String word : new String[]{"ton", "tons", "tonne", "tonnes", "TON", " Ton "}) {
            CanonicalUnit u = svc.describe("F006", word).orElse(null);
            assertNotNull(u, "契约不认「" + word + "」⇒ 归一不到 t, 会原样存进库/丢给用户");
            assertEquals("t", u.code(), "「" + word + "」归一到了 " + u.code());
        }
        // 阴性对照: 不该把不相干的词也吞成 t
        assertTrue(svc.describe("F006", "tonto").isEmpty(), "别名匹配过宽, 把 tonto 也当成吨了");
    }

    @Test
    @DisplayName("阴性对照: 国际符号不许被翻译掉 —— kg 就该显示 kg")
    void symbolsAreNotTranslated() {
        for (String symbol : INTERNATIONAL_SYMBOLS) {
            assertEquals(symbol, UnitDisplayNames.display(symbol),
                    "国际符号 " + symbol + " 被翻译了, 与既有取舍冲突");
        }
    }
}
