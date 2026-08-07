package com.cretas.aims.service.finding;

import com.cretas.aims.service.restaurant.RestaurantAgentActionProposalMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 发现 → 「下一步去哪」映射的约束。不起 Spring、不连库。 */
class FindingNavigationTest {

    private static Finding finding(String code, String subject, int actionability) {
        return new Finding(code, "restaurant", Finding.Severity.WARNING, actionability,
                "s-" + subject, subject, Map.of());
    }

    @Test
    @DisplayName("未登记的 code 不给按钮 —— 猜一个默认页比没有按钮更糟")
    void unknownCodeGetsNoDestination() {
        assertThat(FindingNavigation.destinationFor("SOMETHING_NEVER_SEEN")).isNull();
        assertThat(FindingNavigation.destinationFor(null)).isNull();
        assertThat(FindingNavigation.nextSteps(List.of(finding("SOMETHING_NEVER_SEEN", "x", 90))))
                .isEmpty();
    }

    @Test
    @DisplayName("LOW_STOCK 是**故意**没登记的: 它是工厂库存域, 落点不在餐饮路由里")
    void lowStockIsDeliberatelyUnmapped() {
        // 这条不是「忘了配」。写成断言是为了让下一个人看到它是个决定:
        // 要给 LOW_STOCK 配落点, 得先确认工厂库存页在餐饮租户下真的打得开。
        assertThat(FindingNavigation.destinationFor("LOW_STOCK")).isNull();
    }

    @Test
    @DisplayName("同一个落点只保留最重要的一条 —— 三个一模一样的按钮等于没有信息")
    void duplicateTargetsCollapseToTheHighestRanked() {
        List<FindingNavigation.NextStep> steps = FindingNavigation.nextSteps(List.of(
                finding("WASTAGE_SHARE_SPIKE", "罗氏虾", 40),
                finding("WASTAGE_TYPE_CONCENTRATION", "牛腩", 88),
                finding("DISH_PUZZLE_HIGH_MARGIN_LOW_VOLUME", "白灼虾", 75)));

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).target()).isEqualTo("/restaurant/wastage");
        // 保留的是 rankScore 高的那条, 所以对象名是牛腩不是罗氏虾。
        assertThat(steps.get(0).subjectName()).isEqualTo("牛腩");
        assertThat(steps.get(1).target()).isEqualTo(FindingNavigation.DISH_COST_REVIEW_TARGET);
    }

    @Test
    @DisplayName("每条都自带对象名 —— 不靠与摘要行按下标对齐")
    void everyStepIsSelfDescribing() {
        List<FindingNavigation.NextStep> steps =
                FindingNavigation.nextSteps(List.of(finding("WASTAGE_SHARE_SPIKE", "罗氏虾", 80)));

        assertThat(steps).singleElement().satisfies(s -> {
            assertThat(s.subjectName()).isEqualTo("罗氏虾");
            assertThat(s.label()).isNotBlank();
            assertThat(s.module()).isNotBlank();
        });
    }

    @Test
    @DisplayName("⛔ agent 动作提案与发现层卡片必须是同一个落点")
    void agentProposalSharesTheSameDestination() {
        assertThat(RestaurantAgentActionProposalMapper.NAVIGATION_TARGET)
                .isEqualTo(FindingNavigation.DISH_COST_REVIEW_TARGET);
    }

    @Test
    @DisplayName("每个落点都要带模块名 —— 否则前端渲染出点进去 403 的入口")
    void everyDestinationDeclaresItsModule() {
        for (String code : FindingNavigation.knownCodes()) {
            FindingNavigation.Destination d = FindingNavigation.destinationFor(code);
            assertThat(d.module()).as("%s 缺 module", code).isNotBlank();
            assertThat(d.target()).as("%s 的 target 必须是完整路径(嵌套路由要写全)", code)
                    .startsWith("/");
        }
    }

    /**
     * 🔴 这道断言才是有价值的那条：它对着**渲染器实际认识的发现码**量，
     * 而不是对着我自己登记的那几条量。新加一条 Provider 却忘了配落点时，
     * 它会点名那个码红掉 —— 只断言「我登记的都合法」对这种漏配完全沉默。
     *
     * <p>豁免必须显式写在这里，附理由（见 {@link #lowStockIsDeliberatelyUnmapped}）。
     */
    @Test
    @DisplayName("渲染器认识的每个餐饮发现码, 要么有落点, 要么在豁免名单里写明理由")
    void everyRenderedRestaurantCodeIsEitherMappedOrExplicitlyExempt() throws IOException {
        Path renderer = Path.of("src/main/java/com/cretas/aims/service/finding/FindingTextRenderer.java");
        assertThat(renderer).as("渲染器路径变了, 这道闸就量不到东西了 —— 先修路径再说").exists();

        String src = Files.readString(renderer, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"([A-Z][A-Z0-9_]{5,})\"\\.equals\\(f\\.code\\(\\)\\)").matcher(src);
        Set<String> rendered = m.results().map(r -> r.group(1)).collect(Collectors.toSet());

        assertThat(rendered).as("一个都没解析到 = 正则跟不上渲染器的写法了, 这道闸已经空转")
                .isNotEmpty();

        // 显式豁免：工厂库存域，落点不在餐饮路由里。
        Set<String> exempt = Set.of("LOW_STOCK");

        Set<String> missing = rendered.stream()
                .filter(c -> !exempt.contains(c))
                .filter(c -> FindingNavigation.destinationFor(c) == null)
                .collect(Collectors.toSet());

        assertThat(missing)
                .as("这些发现码渲染成了给用户看的话, 却没有「下一步去哪」: %s。"
                        + "要么在 FindingNavigation 里配落点, 要么加进 exempt 并写明理由", missing)
                .isEmpty();
    }
}
