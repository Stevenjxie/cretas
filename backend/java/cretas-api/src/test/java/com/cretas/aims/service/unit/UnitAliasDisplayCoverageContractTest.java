package com.cretas.aims.service.unit;

import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import com.cretas.aims.repository.material.MaterialPackagingSpecRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 闸: 契约里每一个<b>英文别名</b>都得有中文展示名。
 *
 * <h2>🔴 为什么既有的 {@link UnitDisplayNameCoverageTest} 不够 (2026-08-18 实测)</h2>
 *
 * 那道闸遍历的是 {@code catalog()} —— 契约的<b>码</b>。而库里躺着的是<b>别名</b>:
 * {@code canonicalNativeUnit} 归一失败时<b>回落字面</b>, 存进去的就是用户当初打的那个
 * 英文单词。{@code UnitContractServiceImpl:965} 的注释把这件事写得明明白白:
 *
 * <pre>归一不到 t, canonicalNativeUnit 回落字面, 用户看到的就是 "ton"。</pre>
 *
 * <p>prod 全库逐列扫 (230 个 unit/uom 文本列) 实测确实有:
 * {@code material_packaging_hierarchy.level2_unit} 与
 * {@code material_packaging_specs.package_unit} 各 1 行 {@code ton}。
 * 而 {@code UnitDisplayNames.display("ton")} 当时返回 {@code "ton"} ——
 * <b>别名这一侧一道闸都没有</b>, 所以谁也没发现。
 *
 * <h2>判据</h2>
 * 每个<b>非中文</b>的别名, 二选一:
 * <ol>
 *   <li>它就是国际计量<b>符号</b>({@code kg}/{@code t}/{@code m}…) —— 秤上单据上都这么写, 刻意不翻</li>
 *   <li>在 {@link UnitDisplayNames} 里有条目, <b>且与契约的 displayName 逐字相同</b></li>
 * </ol>
 *
 * <p>⚠️ 符号与单词的分界是<b>「用户认不认得这个写法」</b>, 不是量纲:
 * {@code t} 留着(GB 3100 法定符号), {@code ton}/{@code tonne} 要翻(英文单词)。
 * 同一条判据当初把 {@code jin} 揪了出来。
 *
 * <h2>为什么用反射拿别名表</h2>
 * {@code SYSTEM_ALIASES} 是 private static, 没有公开访问器。反射读的是<b>真实的数据结构</b>,
 * 比在源码上跑正则可靠(本仓多次栽在「grep 把 docstring 里提到的名字也数了进去」)。
 * ⚠️ 字段改名会让下面的<b>阳性对照</b>当场变红, 而不是让闸静默变成空转 —— 这是有意的。
 */
class UnitAliasDisplayCoverageContractTest {

    /**
     * 国际通行的计量符号 —— 刻意不翻。与 {@link UnitDisplayNameCoverageTest} 同一份取舍。
     *
     * <p>⚠️ 代理判据: 静态分析判不出「用户认不认得」, 所以<b>逐个显式列出</b>,
     * ⛔ 不许写成「latin 字母就算符号」那类启发式 —— {@code jin} 正是那么溜过去的。
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

    @SuppressWarnings("unchecked")
    private static Map<String, String> systemAliases() throws ReflectiveOperationException {
        Field f = UnitContractServiceImpl.class.getDeclaredField("SYSTEM_ALIASES");
        f.setAccessible(true);
        return (Map<String, String>) f.get(null);
    }

    private static boolean hasChinese(String s) {
        return s != null && s.codePoints()
                .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    @Test
    @DisplayName("阳性对照: 反射真的拿到了别名表, 且中文码/英文别名两类都在 —— 否则下面全是空转")
    void aliasTableIsReachableAndPopulated() throws ReflectiveOperationException {
        Map<String, String> aliases = systemAliases();
        assertTrue(aliases.size() >= 60,
                "只反射到 " + aliases.size() + " 条别名 —— SYSTEM_ALIASES 多半改名/改结构了, "
                        + "这道闸正在读空气");
        assertTrue(aliases.containsKey("box"), "别名表里连 box 都没有, 拿到的不是那张表");
        assertTrue(aliases.keySet().stream().anyMatch(UnitAliasDisplayCoverageContractTest::hasChinese),
                "一个中文别名都没有, 分类判据失效");
        assertTrue(aliases.keySet().stream().anyMatch(k -> !hasChinese(k)),
                "一个英文别名都没有, 本闸无事可做");
    }

    @Test
    @DisplayName("🔴 每个英文别名要么是国际符号, 要么有中文展示名 —— 不许让用户读到 ton / carton")
    void everyNonChineseAliasIsEitherSymbolOrTranslated() throws ReflectiveOperationException {
        List<String> offenders = new ArrayList<>();
        for (String alias : systemAliases().keySet()) {
            if (hasChinese(alias) || INTERNATIONAL_SYMBOLS.contains(alias)) {
                continue;
            }
            String shown = UnitDisplayNames.display(alias);
            if (!hasChinese(shown)) {
                offenders.add(alias + " → 用户看到「" + shown + "」");
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些英文别名会原样丢给用户看 —— 库里存的就是别名(归一失败会回落字面), "
                        + "请在 UnitDisplayNames 里补条目: " + offenders);
    }

    @Test
    @DisplayName("🔴 展示名与契约逐字相同 —— 抽不成一份, 就得钉住两份不许漂")
    void aliasDisplayNamesAgreeWithContract() throws ReflectiveOperationException {
        UnitContractService svc = service();
        List<String> drifted = new ArrayList<>();
        for (String alias : systemAliases().keySet()) {
            if (hasChinese(alias) || INTERNATIONAL_SYMBOLS.contains(alias)) {
                continue;
            }
            String shown = UnitDisplayNames.display(alias);
            String contractName = svc.describe("F006", alias)
                    .map(CanonicalUnit::displayName)
                    .orElse(null);
            if (contractName != null && !contractName.equals(shown)) {
                drifted.add(alias + ": 静态表说「" + shown + "」, 契约说「" + contractName + "」");
            }
        }
        assertTrue(drifted.isEmpty(), "两份展示名不一致: " + drifted);
    }

    @Test
    @DisplayName("阴性对照: 国际符号不许被翻掉 —— t 是吨的法定符号, 和 ton 不是一回事")
    void symbolsAreNotTranslated() {
        for (String symbol : INTERNATIONAL_SYMBOLS) {
            assertEquals(symbol, UnitDisplayNames.display(symbol),
                    "国际符号 " + symbol + " 被翻译了, 与既有取舍冲突");
        }
        // 同一个量纲, 符号留着 / 单词要翻 —— 这一对就是本闸的判据本身
        assertEquals("t", UnitDisplayNames.display("t"));
        assertEquals("吨", UnitDisplayNames.display("ton"));
    }

    @Test
    @DisplayName("阴性对照: 表里没收的自定义单位原样返回, 不会被改写也不会变空")
    void unknownUnitsPassThrough() {
        assertEquals("自定义单位", UnitDisplayNames.display("自定义单位"));
        assertEquals("mixed", UnitDisplayNames.display("mixed"));
        assertFalse(UnitDisplayNames.display("盒").isEmpty());
    }
}
