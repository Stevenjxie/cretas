package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 9 P0.2 fix verification — WorkdeskOutputSummarizer 抓 4 类 dirty 输出
 * (underscore-prefixed key leak / raw JSON dump / bare 项数据指标 template / 太短)
 * + LLM-summarize replace formattedText + message.
 */
class WorkdeskOutputSummarizerTest {

    private WorkdeskOutputSummarizer summarizer;
    private DashScopeClient mockLlm;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mockLlm = mock(DashScopeClient.class);
        mapper = new ObjectMapper();
        summarizer = new WorkdeskOutputSummarizer(mockLlm, mapper);
        ReflectionTestUtils.setField(summarizer, "enabled", true);
        ReflectionTestUtils.setField(summarizer, "maxChars", 800);
    }

    // ==================== isDirty detection ====================

    @Test
    void isDirty_underscoreKey_returnsTrue() {
        String text = "{\"_toolCount\": 5, \"_executionOrder\": \"sequential\"}";
        assertThat(summarizer.isDirty(text)).isTrue();
    }

    @Test
    void isDirty_bareTemplate_returnsTrue() {
        String text = "查询完成\n包含 5 项数据指标 — 详情请查看下方数据卡片或对应报表模块。";
        assertThat(summarizer.isDirty(text)).isTrue();
    }

    @Test
    void isDirty_executionCompleteBareTemplate_returnsTrue() {
        String text = "执行完成\n包含 3 项数据指标";
        assertThat(summarizer.isDirty(text)).isTrue();
    }

    @Test
    void isDirty_rawJsonDump_returnsTrue() {
        String text = "{\"customer\":{\"data\":[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]}}";
        assertThat(summarizer.isDirty(text)).isTrue();
    }

    @Test
    void isDirty_cleanChineseSummary_returnsFalse() {
        String text = "根据最新的运营分析报告, 2026年5月期间, 贵公司的销售额达到 ¥611,355 元, " +
                "比上月增长 43568.21%. 重点客户包括叮咚-上海松江冷藏仓等 VIP 客户. " +
                "近 7 天暂无微信记录, 也暂无漏接通话需要跟进.";
        assertThat(summarizer.isDirty(text)).isFalse();
    }

    @Test
    void isDirty_nullOrEmpty_returnsFalse() {
        assertThat(summarizer.isDirty(null)).isFalse();
        assertThat(summarizer.isDirty("")).isFalse();
    }

    @Test
    void isDirty_shortButCleanText_returnsFalse() {
        String text = "今天 (~1 天内) 1 个采购单共 1 行待入库, 请按清单核对实物";
        assertThat(summarizer.isDirty(text)).isFalse();
    }

    // ==================== stripUnderscoreKeys ====================

    @Test
    void stripUnderscoreKeys_dropsTopLevelMetadata() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("customer_priority_query", Map.of("count", 16));
        input.put("_toolCount", 5);
        input.put("_executionOrder", "sequential");

        @SuppressWarnings("unchecked")
        Map<String, Object> cleaned = (Map<String, Object>) summarizer.stripUnderscoreKeys(input);

        assertThat(cleaned).containsKey("customer_priority_query");
        assertThat(cleaned).doesNotContainKey("_toolCount");
        assertThat(cleaned).doesNotContainKey("_executionOrder");
    }

    @Test
    void stripUnderscoreKeys_dropsNestedMetadata() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("count", 5);
        nested.put("_reasoning", "internal");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("haccp_status", nested);

        @SuppressWarnings("unchecked")
        Map<String, Object> cleaned = (Map<String, Object>) summarizer.stripUnderscoreKeys(input);

        @SuppressWarnings("unchecked")
        Map<String, Object> nestedCleaned = (Map<String, Object>) cleaned.get("haccp_status");
        assertThat(nestedCleaned).containsKey("count");
        assertThat(nestedCleaned).doesNotContainKey("_reasoning");
    }

    @Test
    void stripUnderscoreKeys_preservesLists() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("items", List.of(Map.of("id", 1, "_meta", "x"),
                Map.of("id", 2, "_meta", "y")));

        @SuppressWarnings("unchecked")
        Map<String, Object> cleaned = (Map<String, Object>) summarizer.stripUnderscoreKeys(input);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) cleaned.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsKey("id").doesNotContainKey("_meta");
        assertThat(items.get(1)).containsKey("id").doesNotContainKey("_meta");
    }

    // ==================== apply — full integration ====================

    @Test
    void apply_dirtyFormattedText_replacesWithLlmSummary() {
        IntentExecuteResponse response = new IntentExecuteResponse();
        response.setFormattedText("查询完成\n包含 5 项数据指标 — 详情请查看下方数据卡片或对应报表模块。");
        response.setMessage("查询完成\n包含 5 项数据指标 — 详情请查看下方数据卡片或对应报表模块。");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customer_priority_query", Map.of("count", 16, "message", "共 16 个客户已按优先级排序"));
        data.put("_toolCount", 5);
        data.put("_executionOrder", "sequential");
        response.setResultData(data);
        response.setIntentName("今日客户跟进");

        String llmSummary = "今日有 16 个客户需要跟进, 其中 VIP 客户优先. 近 7 天暂无微信记录.";
        when(mockLlm.chatFast(anyString(), anyString())).thenReturn(llmSummary);

        summarizer.apply(response);

        assertThat(response.getFormattedText()).isEqualTo(llmSummary);
        verify(mockLlm, times(1)).chatFast(anyString(), anyString());
    }

    @Test
    void apply_underscoreKeyInMessage_replacesMessage() {
        IntentExecuteResponse response = new IntentExecuteResponse();
        response.setFormattedText("已生成报告");  // 不脏
        response.setMessage("{\"customer\":{\"data\":[{\"_internal\":\"x\"}]}, \"_toolCount\":1}");
        Map<String, Object> data = new HashMap<>();
        data.put("customer", Map.of("count", 1));
        response.setResultData(data);
        response.setIntentName("test");

        when(mockLlm.chatFast(anyString(), anyString())).thenReturn("已为您找到 1 个客户记录.");

        summarizer.apply(response);

        // formattedText 干净 不动
        assertThat(response.getFormattedText()).isEqualTo("已生成报告");
        // message 脏 已替换
        assertThat(response.getMessage()).isEqualTo("已为您找到 1 个客户记录.");
    }

    @Test
    void apply_cleanResponse_noLlmCall() {
        IntentExecuteResponse response = new IntentExecuteResponse();
        response.setFormattedText("根据最新的运营分析报告, 2026年5月期间, 贵公司的销售额达到 ¥611,355 元, 比上月增长 43568.21%.");
        response.setMessage("根据最新的运营分析报告, 2026年5月期间, 贵公司的销售额达到 ¥611,355 元, 比上月增长 43568.21%.");

        summarizer.apply(response);

        verify(mockLlm, never()).chatFast(anyString(), anyString());
    }

    @Test
    void apply_llmFailure_appliesDeterministicFallback() {
        // 2026-05-23: PR #218 changes contract — when LLM unavailable but resultData
        // is non-empty Map, apply deterministic strip-and-template fallback (not keep dirty).
        IntentExecuteResponse response = new IntentExecuteResponse();
        String original = "查询完成\n包含 5 项数据指标 — 详情请查看下方数据卡片或对应报表模块。";
        response.setFormattedText(original);
        response.setMessage(original);
        response.setResultData(Map.of("foo", "bar"));
        response.setIntentName("test");

        when(mockLlm.chatFast(anyString(), anyString())).thenThrow(new RuntimeException("LLM 429"));

        summarizer.apply(response);

        // LLM failed → deterministic fallback applied (text now clean, contains intent name + structured key)
        assertThat(response.getFormattedText()).isNotEqualTo(original);
        assertThat(response.getFormattedText()).contains("test 数据摘要");
        assertThat(response.getFormattedText()).contains("foo");
        assertThat(summarizer.isDirty(response.getFormattedText())).isFalse();
        // message likewise replaced (was dirty same as formattedText)
        assertThat(response.getMessage()).isNotEqualTo(original);
        assertThat(summarizer.isDirty(response.getMessage())).isFalse();
    }

    @Test
    void apply_llmFailureWithNullResultData_dirtyBareTemplateReplacedWithSafeMessage() {
        // Edge: LLM not called (resultData null) AND buildDeterministicFallback returns
        // null (no data to template). Contract changed by #299 ("summarizer never keeps
        // dirty text — final hard-strip guarantee"): the dirty bare template is NOT kept.
        // It carries no JSON/underscore fragments to strip, so hardStripDirty cannot
        // salvage it and substitutes a clean, actionable last-resort message. Pre-#299
        // this asserted the bare template was kept verbatim ("no-regression"); that
        // behavior was intentionally superseded — keeping a known-dirty bare template
        // re-introduces the very leak WorkdeskOutputSummarizer exists to eliminate.
        IntentExecuteResponse response = new IntentExecuteResponse();
        String original = "查询完成\n包含 5 项数据指标 — 详情请查看下方数据卡片或对应报表模块。";
        response.setFormattedText(original);
        response.setMessage(original);
        response.setResultData(null);  // no data to template
        response.setIntentName("test");

        // LLM not even called because tryLlmSummarize returns null on null resultData
        summarizer.apply(response);

        // Dirty bare template replaced (never kept) with a clean last-resort message.
        assertThat(response.getFormattedText()).isNotEqualTo(original);
        assertThat(response.getMessage()).isNotEqualTo(original);
        assertThat(summarizer.isDirty(response.getFormattedText())).isFalse();
        assertThat(summarizer.isDirty(response.getMessage())).isFalse();
        assertThat(response.getFormattedText()).contains("本次查询已完成");
        assertThat(response.getMessage()).contains("本次查询已完成");
    }

    @Test
    void apply_disabled_noOp() {
        ReflectionTestUtils.setField(summarizer, "enabled", false);
        IntentExecuteResponse response = new IntentExecuteResponse();
        String dirty = "查询完成\n包含 5 项数据指标";
        response.setFormattedText(dirty);
        response.setResultData(Map.of("foo", "bar"));

        summarizer.apply(response);

        verify(mockLlm, never()).chatFast(anyString(), anyString());
        assertThat(response.getFormattedText()).isEqualTo(dirty);
    }

    @Test
    void apply_nullResponse_noOp() {
        // 不应抛异常
        summarizer.apply(null);
        verify(mockLlm, never()).chatFast(anyString(), anyString());
    }

    @Test
    void apply_nullResultData_noLlmCall() {
        IntentExecuteResponse response = new IntentExecuteResponse();
        response.setFormattedText("查询完成\n包含 5 项数据指标");
        response.setResultData(null);

        summarizer.apply(response);

        // formattedText 脏但 resultData null → 无法 summarize
        verify(mockLlm, never()).chatFast(anyString(), anyString());
    }

    @Test
    void apply_llmReturnsTooLong_caps800Chars() {
        IntentExecuteResponse response = new IntentExecuteResponse();
        response.setFormattedText("查询完成\n包含 5 项数据指标");
        response.setResultData(Map.of("foo", "bar"));
        response.setIntentName("test");

        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 100; i++) huge.append("非常长的中文内容. ");
        when(mockLlm.chatFast(anyString(), anyString())).thenReturn(huge.toString());

        summarizer.apply(response);

        assertThat(response.getFormattedText().length()).isLessThanOrEqualTo(803); // 800 + "..."
    }

    @Test
    void apply_rawJsonDumpInMessage_replacesMessage() {
        IntentExecuteResponse response = new IntentExecuteResponse();
        response.setFormattedText("查询完成");  // 短 also dirty
        // 5989-char like the sales-owner pathB
        StringBuilder dump = new StringBuilder("{\"customer_priority_query\":{\"data\":{\"limit\":50,\"count\":16,\"customers\":[");
        for (int i = 0; i < 50; i++) {
            dump.append("{\"id\":").append(i).append(",\"name\":\"客户").append(i).append("\"},");
        }
        dump.append("]}},\"_toolCount\":5}");
        response.setMessage(dump.toString());
        response.setResultData(Map.of("customer_priority_query", Map.of("count", 16)));
        response.setIntentName("今日客户跟进");

        when(mockLlm.chatFast(anyString(), anyString())).thenReturn("今日有 50 个客户需要跟进.");

        summarizer.apply(response);

        // message dirty (raw JSON) → replaced
        assertThat(response.getMessage()).isEqualTo("今日有 50 个客户需要跟进.");
    }
}
