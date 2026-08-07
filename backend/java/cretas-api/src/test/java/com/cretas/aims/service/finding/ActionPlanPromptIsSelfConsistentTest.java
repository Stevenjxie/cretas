package com.cretas.aims.service.finding;

import com.cretas.aims.ai.grounding.GroundedNumberValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 不变量：**模型即使逐字复述我喂给它的事实，也不该被校验器拒绝。**
 *
 * <p>2026-08-07 prod 实测：{@code GET /findings/action-plan} 对 MOCK_REST
 * **4/4 次全部 409**「生成的行动建议里出现了系统数据中不存在的数字 [52]」。
 * 稳定复现 = 不是 LLM 抖动，是确定性缺陷。
 *
 * <p>根因是两处口径打架：喂给 LLM 的文本用 {@code renderDigestLines}（**含跳过规则
 * 那句**，里面有「重合度 52%」「25 种 / 13 种」），而校验器的合法数字集只由
 * **发现**的结构化 facts 构成。模型越忠实地引用我给它的话，越必然被拒。
 *
 * <p>判据：**喂给 LLM 的文本与校验用的事实集必须来自同一批发现。**
 * 修法是收窄输入（跳过规则本就不该变成行动建议），不是放宽校验。
 */
class ActionPlanPromptIsSelfConsistentTest {

    private final FindingTextRenderer renderer = new FindingTextRenderer();
    private final GroundedNumberValidator validator = new GroundedNumberValidator();

    /** 与 FindingActionPlanService#buildFacts 同构 —— 校验器拿到的就是这个。 */
    private static List<Object> buildFacts(List<Finding> findings) {
        List<Object> facts = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("对象", f.subjectName());
            item.putAll(f.facts());
            facts.add(item);
        }
        return facts;
    }

    /** 逐字照搬 prod 那次 409 的现场：2 条发现 + 1 条带数字的跳过说明。 */
    private static FindingService.Result prodShapedResult() {
        Finding puzzle = new Finding(
                "DISH_PUZZLE_HIGH_MARGIN_LOW_VOLUME", "restaurant", Finding.Severity.WARNING, 75,
                "d-1", "罗氏虾",
                Map.of("unitMargin", 78.57, "medianUnitMargin", 27.51,
                        "qty", 148408, "medianQty", 164720, "pricedDishes", 10));
        Finding wastage = new Finding(
                "WASTAGE_TYPE_CONCENTRATION", "restaurant", Finding.Severity.WARNING, 60,
                "w-1", "变质",
                Map.of("amount", 274169.64, "share", 36.5));
        return new FindingService.Result(
                List.of(puzzle, wastage),
                List.of("菜品毛利谜题", "损耗类型集中度"),
                2,
                Map.of(),
                List.of(),
                // 🔴 这条就是 52 的来源。
                List.of(new FindingService.SkippedRule(
                        "食材损耗离群",
                        "两期食材名单不可比: 近 7 天 25 种 / 基线 13 种 (重合度 52%, 需 >= 80%)")));
    }

    @Test
    @DisplayName("🔴 逐字复述喂进去的事实, 校验器必须放行 —— 否则这个接口永远 409")
    void echoingThePromptMustNeverBeRejected() {
        FindingService.Result result = prodShapedResult();
        String prompt = String.join("\n", renderer.renderFindingLines(result));
        List<Object> facts = buildFacts(result.findings());

        List<String> ungrounded = validator.findUngroundedNumbers(prompt, facts);

        assertThat(ungrounded)
                .as("喂给 LLM 的文本里出现了校验集之外的数字 %s —— 模型逐字复述就会被拒, "
                        + "这个接口会确定性 409(prod 实测 4/4)。"
                        + "修输入侧(别喂), 不要放宽校验。", ungrounded)
                .isEmpty();
    }

    @Test
    @DisplayName("阴性对照: 用 renderDigestLines 就会被拒 —— 证明上面那条不是空转")
    void theOldWiringWouldStillBeRejected() {
        FindingService.Result result = prodShapedResult();
        String oldPrompt = String.join("\n", renderer.renderDigestLines(result));

        List<String> ungrounded =
                validator.findUngroundedNumbers(oldPrompt, buildFacts(result.findings()));

        // 不断言具体是哪几个数字 —— 断言「确实会被拒」就够, 且不会因措辞微调而假红。
        assertThat(ungrounded)
                .as("renderDigestLines 竟然没带来无据数字, 那上面那条断言就量不到东西了 —— "
                        + "说明这个用例的 skippedRule 文案里已经没有数字, 请补回来")
                .isNotEmpty();
    }

    @Test
    @DisplayName("跳过说明不进提示词, 但必须仍在响应里如实透出 —— 三态不能被收窄输入弄丢")
    void skippedRulesAreStillDisclosedElsewhere() {
        FindingService.Result result = prodShapedResult();

        assertThat(renderer.renderFindingLines(result))
                .as("发现行不该含跳过说明")
                .noneMatch(line -> line.contains("暂不判断"));
        assertThat(renderer.renderDigestLines(result))
                .as("给用户看的摘要仍要说出「判不了」这一态")
                .anyMatch(line -> line.contains("暂不判断"));
        assertThat(result.skippedRules()).hasSize(1);
    }
}
