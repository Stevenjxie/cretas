package com.cretas.aims.service.intent.seed;

import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.service.EmbeddingClient;
import com.cretas.aims.service.ExpressionLearningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FuzzyExpressionSeeder - 模糊问句种子表达器")
class FuzzyExpressionSeederTest {

    @Mock
    private ExpressionLearningService expressionLearningService;

    @Mock
    private EmbeddingClient embeddingClient;

    @InjectMocks
    private FuzzyExpressionSeeder seeder;

    /** 已停用的孤儿意图, 绝不应被种入 */
    private static final Set<String> DEACTIVATED = Set.of(
            "RESTAURANT_DISH_LIST",
            "RESTAURANT_PERFORMANCE_EVAL",
            "RESTAURANT_REVIEW_COMPETITIVE",
            "RESTAURANT_SLOW_SELLER_QUERY");

    @Test
    @DisplayName("embedding 不可用时整体跳过, 不学习任何表达 (避免死种子)")
    void skipsWhenEmbeddingUnavailable() {
        when(embeddingClient.isAvailable()).thenReturn(false);

        seeder.run(null);

        verify(expressionLearningService, never())
                .learnExpressions(anyString(), anyString(), anyList(), anyDouble(), any());
    }

    @Test
    @DisplayName("embedding 可用时, 每个活跃意图各调用一次 learnExpressions, 全局作用域 + MANUAL + 置信度 1.0")
    void seedsActiveIntentsGloballyWhenAvailable() {
        when(embeddingClient.isAvailable()).thenReturn(true);
        when(expressionLearningService.learnExpressions(
                anyString(), anyString(), anyList(), anyDouble(), any()))
                .thenReturn(1);

        seeder.run(null);

        int expectedIntents = FuzzyExpressionSeeder.SEEDS.size();
        ArgumentCaptor<String> factoryCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> intentCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Double> confCap = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<LearnedExpression.SourceType> srcCap =
                ArgumentCaptor.forClass(LearnedExpression.SourceType.class);

        verify(expressionLearningService, times(expectedIntents))
                .learnExpressions(factoryCap.capture(), intentCap.capture(), anyList(),
                        confCap.capture(), srcCap.capture());

        assertThat(factoryCap.getAllValues()).containsOnly("*");
        assertThat(confCap.getAllValues()).containsOnly(1.0);
        assertThat(srcCap.getAllValues()).containsOnly(LearnedExpression.SourceType.MANUAL);
        // 覆盖本会话上线的关键意图
        assertThat(intentCap.getAllValues())
                .contains("RESTAURANT_REVIEW_SUMMARY",
                        "RESTAURANT_RATING_REVENUE_CORRELATION",
                        "COMPREHENSIVE_SYNTHESIS",
                        "RESTAURANT_BESTSELLER_QUERY",
                        "RESTAURANT_DISH_SLOW");
    }

    @Test
    @DisplayName("种子表绝不包含已停用的孤儿意图")
    void doesNotSeedDeactivatedOrphans() {
        assertThat(FuzzyExpressionSeeder.SEEDS.keySet())
                .doesNotContainAnyElementsOf(DEACTIVATED);
    }

    @Test
    @DisplayName("每个意图至少 2 条模糊问句, 无空白 / 无重复")
    void seedListsAreWellFormed() {
        FuzzyExpressionSeeder.SEEDS.forEach((intent, phrases) -> {
            assertThat(phrases).as("意图 %s 至少 2 条", intent).hasSizeGreaterThanOrEqualTo(2);
            assertThat(phrases).as("意图 %s 无空白", intent)
                    .allSatisfy(p -> assertThat(p).isNotBlank());
            assertThat(phrases).as("意图 %s 无重复", intent).doesNotHaveDuplicates();
        });
    }

    @Test
    @DisplayName("learnExpressions 抛异常时不向上传播 (种子失败不阻塞启动)")
    void swallowsExceptionsToNotBlockStartup() {
        when(embeddingClient.isAvailable()).thenReturn(true);
        when(expressionLearningService.learnExpressions(
                anyString(), anyString(), anyList(), anyDouble(), any()))
                .thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> seeder.run(null)).doesNotThrowAnyException();
    }

    // 便捷的 any() for SourceType (避免 import 冲突)
    @Test
    @DisplayName("restaurant review demo natural phrases are seeded to review intents")
    void restaurantReviewDemoPhrasesAreSeededToReviewIntents() {
        assertThat(FuzzyExpressionSeeder.SEEDS.get("RESTAURANT_REVIEW_SUMMARY"))
                .contains("\u5927\u4f17\u70b9\u8bc4\u53e3\u7891\u600e\u4e48\u6837");
        assertThat(FuzzyExpressionSeeder.SEEDS.get("RESTAURANT_REVIEW_GOOD_TAGS"))
                .contains("\u54ea\u51e0\u4e2a\u83dc\u54c1\u53e3\u7891\u6700\u597d",
                        "\u54ea\u4e9b\u83dc\u53e3\u7891\u6700\u597d",
                        "\u83dc\u54c1\u53e3\u7891\u6700\u597d\u7684\u662f\u54ea\u4e9b");
        assertThat(FuzzyExpressionSeeder.SEEDS.get("RESTAURANT_REVIEW_COMPLAINT"))
                .contains("\u4f4e\u661f\u8bc4\u4ef7\u5e94\u8be5\u600e\u4e48\u6539\u5584",
                        "\u4f4e\u661f\u8bc4\u4ef7\u600e\u4e48\u6539\u5584",
                        "\u5dee\u8bc4\u5e94\u8be5\u600e\u4e48\u6539\u5584",
                        "\u5dee\u8bc4\u6539\u5584\u5efa\u8bae");
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
