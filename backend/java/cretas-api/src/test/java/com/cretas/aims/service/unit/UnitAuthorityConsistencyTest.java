package com.cretas.aims.service.unit;

import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权威单位表内部必须自洽 —— 别名表承诺的码，单位定义表得真的有。
 *
 * <p><b>事故形态</b>：{@code systemAliases()} 里写着
 * {@code alias("sheet", "sheet", "张")}，但 {@code systemUnits()} 里
 * <b>从来没有 add("sheet", ...)</b>。于是查别名查到 {@code sheet}，
 * 再拿 {@code sheet} 去查定义 —— {@code SYSTEM_UNITS.get("sheet")} 返回 null，
 * {@code systemUnitFor()} 整体返回 null。
 *
 * <p>后果是<b>静默的</b>，且两个方向都有：
 * <ul>
 *   <li>{@code canonicalCodeOrRaw("张")} / {@code crossLanguageCode("张")}
 *       <b>原样返回「张」</b>而不是折成 {@code sheet} —— 中英两种写法被当成两个单位，
 *       明明有库存却匹配不上（这正是单位收敛要治的那个病）；</li>
 *   <li>{@code isBuiltInCountingUnit("sheet")} 返回 <b>false</b> ——
 *       「按件计数的成品必须填标准克重」这道校验对它静默失效。</li>
 * </ul>
 *
 * <p>prod 实测 {@code raw_material_types} 里「张」有 6 行在用，不是理论问题。
 *
 * <p>⚠️ 判据故意扫<b>源码</b>而不是跑几个样例：风险是「以后又加了一组别名却忘了加定义」，
 * 逐个样例永远追不上新增。
 */
class UnitAuthorityConsistencyTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/cretas/aims/service/unit/impl/UnitContractServiceImpl.java");

    private static final Pattern ALIAS = Pattern.compile("alias\\(aliases,\\s*\"([a-z0-9\u4e00-\u9fff]+)\"");
    private static final Pattern UNIT = Pattern.compile("add\\(units,\\s*\"([a-z0-9\u4e00-\u9fff]+)\"");

    private static Set<String> codesMatching(Pattern pattern, String source) {
        Set<String> codes = new TreeSet<>();
        Matcher m = pattern.matcher(source);
        while (m.find()) {
            codes.add(m.group(1));
        }
        return codes;
    }

    @Test
    @DisplayName("别名表声明的每个 code, 单位定义表里都必须有 —— 否则查得到别名却查不到定义")
    void everyAliasCodeHasACanonicalUnit() throws IOException {
        String source = Files.readString(SOURCE);

        Set<String> aliasCodes = codesMatching(ALIAS, source);
        Set<String> unitCodes = codesMatching(UNIT, source);

        // 阳性对照: 两个正则都得真的抓到东西, 否则"零差异"只是因为没扫到
        assertThat(aliasCodes).as("别名表一条都没扫到, 说明正则失配, 后面的断言无效").hasSizeGreaterThan(20);
        assertThat(unitCodes).as("单位定义表一条都没扫到, 说明正则失配").hasSizeGreaterThan(20);

        Set<String> declaredButUndefined = new TreeSet<>(aliasCodes);
        declaredButUndefined.removeAll(unitCodes);

        assertThat(declaredButUndefined)
                .as("这些 code 在 systemAliases() 里有别名, 却没有 systemUnits() 定义 —— "
                    + "systemUnitFor() 会返回 null, 中英写法折不到一起且计数量纲判不出来")
                .isEmpty();
    }

    @Test
    @DisplayName("反向: 定义了单位就该有别名 (至少码自身), 否则中文名进不来")
    void everyUnitHasAtLeastItsOwnAlias() throws IOException {
        String source = Files.readString(SOURCE);

        Set<String> aliasCodes = codesMatching(ALIAS, source);
        Set<String> unitCodes = codesMatching(UNIT, source);

        Set<String> definedButUnaliased = new TreeSet<>(unitCodes);
        definedButUnaliased.removeAll(aliasCodes);

        assertThat(definedButUnaliased)
                .as("定义了单位但没进别名表 —— 用户填中文名时认不出来")
                .isEmpty();
    }

    @Test
    @DisplayName("此前漏掉的三个必须真的能用 (行为对照, 不只是源码里有)")
    void previouslyMissingUnitsResolve() {
        // 张 / 托盘 / 板 —— 别名表早就承诺了, 单位定义表一直没兑现
        for (List<String> pair : List.of(
                List.of("张", "张"),
                List.of("托盘", "托盘"),
                List.of("板", "板"))) {
            String chinese = pair.get(0);
            String code = pair.get(1);

            assertThat(UnitContractServiceImpl.crossLanguageCode(chinese))
                    .as("%s 应折成 %s, 否则中英两种写法被当成两个单位", chinese, code)
                    .isEqualTo(code);
            assertThat(UnitContractServiceImpl.isBuiltInCountingUnit(code))
                    .as("%s 是按个数论的单位, 判 false 会让「按件计数必须填克重」静默失效", code)
                    .isTrue();
            assertThat(UnitContractServiceImpl.isBuiltInCountingUnit(chinese))
                    .as("中文写法同样要判成计数单位", chinese)
                    .isTrue();
        }
    }
}
