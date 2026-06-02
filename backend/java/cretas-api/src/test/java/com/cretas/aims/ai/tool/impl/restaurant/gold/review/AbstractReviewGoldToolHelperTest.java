package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the P1 conversational-depth helpers on
 * {@link AbstractReviewGoldTool} ({@code followup} / {@code followups} /
 * {@code glossary} / {@code attachDepth}).
 *
 * <p>Uses a minimal anonymous concrete subclass since the helpers under test
 * are protected instance/static methods that don't touch the Gold client.
 */
class AbstractReviewGoldToolHelperTest {

    /** Bare concrete subclass exposing the protected helpers for testing. */
    private static final class StubReviewTool extends AbstractReviewGoldTool {
        @Override
        public String getToolName() {
            return "stub_review_tool";
        }

        @Override
        public String getDescription() {
            return "stub";
        }

        @Override
        protected Map<String, Object> queryGold(
                String factoryId, LocalDate start, LocalDate end, Map<String, Object> params) {
            return new HashMap<>();
        }

        @Override
        protected boolean isEmpty(Map<String, Object> g) {
            return true;
        }

        @Override
        protected Map<String, Object> format(Map<String, Object> g) {
            return new HashMap<>();
        }

        @Override
        protected String emptyMessage() {
            return "empty";
        }

        // Expose protected static/instance helpers to the test.
        Map<String, Object> callFollowup(String label, String question) {
            return followup(label, question);
        }

        @SafeVarargs
        final List<Map<String, Object>> callFollowups(Map<String, Object>... entries) {
            return followups(entries);
        }

        Map<String, String> callGlossary(String... kv) {
            return glossary(kv);
        }

        void callAttachDepth(Map<String, Object> result, List<Map<String, Object>> f,
                             Map<String, String> g, String guide) {
            attachDepth(result, f, g, guide);
        }
    }

    private final StubReviewTool tool = new StubReviewTool();

    @Test
    @DisplayName("UT-ARG-01: followup() builds {label, question}")
    void followupShape() {
        Map<String, Object> f = tool.callFollowup("整体评价", "客户评价怎么样");
        assertThat(f).containsEntry("label", "整体评价");
        assertThat(f).containsEntry("question", "客户评价怎么样");
    }

    @Test
    @DisplayName("UT-ARG-02: followups() collects non-null entries")
    void followupsCollectsNonNull() {
        List<Map<String, Object>> list = tool.callFollowups(
                tool.callFollowup("a", "qa"),
                null,
                tool.callFollowup("b", "qb"));
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).containsEntry("label", "a");
        assertThat(list.get(1)).containsEntry("label", "b");
    }

    @Test
    @DisplayName("UT-ARG-03: glossary() flattens k,v,k,v pairs (ordered)")
    void glossaryFlattensPairs() {
        Map<String, String> g = tool.callGlossary("服务分", "服务评分", "差评", "<=3星");
        assertThat(g).containsEntry("服务分", "服务评分");
        assertThat(g).containsEntry("差评", "<=3星");
        assertThat(g.keySet()).containsExactly("服务分", "差评");
    }

    @Test
    @DisplayName("UT-ARG-04: glossary() ignores dangling odd element")
    void glossaryIgnoresDangling() {
        Map<String, String> g = tool.callGlossary("a", "va", "b");
        assertThat(g).hasSize(1).containsEntry("a", "va");
    }

    @Test
    @DisplayName("UT-ARG-05: attachDepth() puts all three fields when non-empty")
    void attachDepthAllFields() {
        Map<String, Object> result = new HashMap<>();
        tool.callAttachDepth(result,
                tool.callFollowups(tool.callFollowup("a", "qa")),
                tool.callGlossary("服务分", "服务评分"),
                "看这张图");
        assertThat(result).containsKey("suggestedFollowups");
        assertThat(result).containsKey("glossary");
        assertThat(result).containsEntry("chartGuide", "看这张图");
    }

    @Test
    @DisplayName("UT-ARG-06: attachDepth() skips null / empty inputs")
    void attachDepthSkipsEmpty() {
        Map<String, Object> result = new HashMap<>();
        tool.callAttachDepth(result, tool.callFollowups(), tool.callGlossary(), "");
        assertThat(result).doesNotContainKey("suggestedFollowups");
        assertThat(result).doesNotContainKey("glossary");
        assertThat(result).doesNotContainKey("chartGuide");

        Map<String, Object> result2 = new HashMap<>();
        tool.callAttachDepth(result2, null, null, null);
        assertThat(result2).isEmpty();
    }
}
