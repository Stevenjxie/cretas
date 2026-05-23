package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Terminal LLM-summarize gate for Workdesk / Skill orchestration outputs.
 *
 * <p>Sprint 9 P0.2 fix (2026-05-22) — 4 Skills (DAILY_CUSTOMER_FOLLOWUP /
 * FOOD_SAFETY_RECALL / PURCHASER_WEEKLY_PLAN / QUALITY_CHIEF_WORKDESK)
 * leak {@code _toolCount} / {@code _executionOrder} / {@code _reasoning}
 * underscore-prefixed metadata keys directly to user-facing fields
 * (formattedText AND message). Also bare template
 * {@code "查询完成\n包含 N 项数据指标"} provides no real summary.
 *
 * <p>This gate runs AFTER existing formatters as terminal output cleaner:
 * <ol>
 *     <li>Detect underscore-prefixed keys / raw JSON dump / bare template
 *         in {@code formattedText} or {@code message}.</li>
 *     <li>If detected, build a Chinese summary prompt from {@code resultData}
 *         (with underscore keys filtered out), feed to LLM via
 *         {@link DashScopeClient#chatFast(String, String)}.</li>
 *     <li>Replace {@code formattedText} (and {@code message} when it's a raw
 *         JSON dump) with LLM-summarized Chinese text (capped at 800 chars).</li>
 *     <li>Fallback to existing template + log warning if LLM unavailable.</li>
 * </ol>
 *
 * <p>Opt-out via {@code cretas.ai.workdesk-summarizer.enabled=false}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkdeskOutputSummarizer {

    private final DashScopeClient dashScopeClient;
    private final ObjectMapper objectMapper;

    @Value("${cretas.ai.workdesk-summarizer.enabled:true}")
    private boolean enabled;

    @Value("${cretas.ai.workdesk-summarizer.max-chars:800}")
    private int maxChars;

    /** Detect underscore-prefixed key like {@code "_toolCount":} in JSON-ish payload. */
    private static final Pattern UNDERSCORE_KEY_PATTERN = Pattern.compile("\"_[a-zA-Z][a-zA-Z0-9]*\"\\s*:");

    /** Detect bare template "查询完成\n包含 N 项数据指标 — 详情请查看...". */
    private static final Pattern BARE_TEMPLATE_PATTERN = Pattern.compile(
            "^\\s*(查询完成|执行完成)[\\s\\S]{0,80}(包含\\s*\\d+\\s*项数据指标|详情请查看下方)[\\s\\S]{0,80}$");

    /** Detect raw JSON brace-heavy dump. */
    private static final double JSON_BRACE_RATIO_THRESHOLD = 0.05;

    /** Min chars to consider response "user-facing meaningful". */
    private static final int MIN_USEFUL_CHARS = 40;

    /** Skip work when chunked Tool response message already has a clean Chinese summary. */
    private static final int MAX_SUMMARIZE_BUDGET_CHARS = 8000;

    /**
     * Apply terminal LLM-summarize gate to the response.
     *
     * <p>Mutates {@code response.formattedText} (and {@code response.message} when it's
     * a raw JSON dump). No-op if disabled, response has no resultData, or the response
     * already has a clean Chinese summary.
     *
     * @param response the response to clean up (must not be null)
     */
    public void apply(IntentExecuteResponse response) {
        if (!enabled || response == null) {
            return;
        }

        String formattedText = response.getFormattedText();
        String message = response.getMessage();
        Object resultData = response.getResultData();

        boolean ftDirty = isDirty(formattedText);
        boolean msgDirty = isDirty(message);

        if (!ftDirty && !msgDirty) {
            return; // both clean, nothing to do
        }

        String summary = tryLlmSummarize(resultData, response);
        if (summary == null || summary.isBlank()) {
            log.debug("WorkdeskOutputSummarizer: LLM unavailable, keeping existing template");
            return;
        }

        if (ftDirty) {
            log.info("WorkdeskOutputSummarizer: replacing formattedText (was {} chars, dirty)",
                    formattedText != null ? formattedText.length() : 0);
            response.setFormattedText(summary);
        }
        if (msgDirty) {
            // message often consumed by conversation memory / chat history;
            // replacing with summary keeps history clean.
            log.info("WorkdeskOutputSummarizer: replacing message (was {} chars, dirty)",
                    message != null ? message.length() : 0);
            response.setMessage(summary);
        }
    }

    /**
     * Returns true when the text contains underscore-prefixed metadata keys,
     * is a bare template, or is a raw JSON dump.
     */
    boolean isDirty(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // 1. underscore-prefixed metadata key leak
        if (UNDERSCORE_KEY_PATTERN.matcher(text).find()) {
            return true;
        }
        // 2. bare template "查询完成\n包含 N 项数据指标"
        if (BARE_TEMPLATE_PATTERN.matcher(text).find()) {
            return true;
        }
        // 3. raw JSON brace-heavy dump (>5% braces, no useful Chinese narrative)
        long braceCount = text.chars().filter(c -> c == '{' || c == '}').count();
        double ratio = (double) braceCount / text.length();
        if (ratio > JSON_BRACE_RATIO_THRESHOLD) {
            return true;
        }
        // 4. unhelpfully short (mostly empty/template)
        if (text.length() < MIN_USEFUL_CHARS && text.contains("包含") && text.contains("项数据指标")) {
            return true;
        }
        return false;
    }

    /**
     * Call LLM to summarize resultData into a Chinese natural-language summary.
     *
     * @return summary string (≤ maxChars), or null on failure
     */
    private String tryLlmSummarize(Object resultData, IntentExecuteResponse response) {
        if (resultData == null) {
            return null;
        }
        try {
            // Build cleaned-up payload (drop underscore-prefixed metadata keys)
            String payloadJson = buildCleanPayload(resultData);
            if (payloadJson == null || payloadJson.length() < 5) {
                return null;
            }
            if (payloadJson.length() > MAX_SUMMARIZE_BUDGET_CHARS) {
                // truncate to keep LLM call fast (Workdesk skill rich payloads OK to clip tail)
                payloadJson = payloadJson.substring(0, MAX_SUMMARIZE_BUDGET_CHARS) + " ...(数据已截断)";
            }

            String intentName = response.getIntentName() != null
                    ? response.getIntentName() : "工作台";
            String systemPrompt = buildSystemPrompt(intentName);
            String userInput = "以下是 " + intentName + " 编排执行的工具返回数据 (JSON), 请按 system 提示输出摘要:\n\n"
                    + payloadJson;

            String llmResult;
            try {
                llmResult = dashScopeClient.chatFast(systemPrompt, userInput);
            } catch (Exception llmEx) {
                log.warn("WorkdeskOutputSummarizer: LLM call failed: {}", llmEx.getMessage());
                return null;
            }
            if (llmResult == null || llmResult.isBlank()) {
                return null;
            }
            llmResult = llmResult.trim();
            if (llmResult.length() > maxChars) {
                llmResult = llmResult.substring(0, maxChars) + "...";
            }
            return llmResult;
        } catch (Exception e) {
            log.warn("WorkdeskOutputSummarizer: summarize failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build the cleaned JSON payload by recursively dropping underscore-prefixed keys
     * (e.g. {@code _toolCount}, {@code _executionOrder}, {@code _reasoning}).
     */
    @SuppressWarnings("unchecked")
    String buildCleanPayload(Object resultData) throws JsonProcessingException {
        Object cleaned = stripUnderscoreKeys(resultData);
        return objectMapper.writeValueAsString(cleaned);
    }

    /**
     * Recursively strip underscore-prefixed keys from Maps. Lists and primitives passed through.
     */
    @SuppressWarnings("unchecked")
    Object stripUnderscoreKeys(Object obj) {
        if (obj instanceof Map) {
            Map<String, Object> src = (Map<String, Object>) obj;
            Map<String, Object> dst = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : src.entrySet()) {
                String k = e.getKey();
                if (k != null && k.startsWith("_")) {
                    continue;
                }
                dst.put(k, stripUnderscoreKeys(e.getValue()));
            }
            return dst;
        } else if (obj instanceof Iterable) {
            java.util.List<Object> dst = new java.util.ArrayList<>();
            for (Object item : (Iterable<?>) obj) {
                dst.add(stripUnderscoreKeys(item));
            }
            return dst;
        }
        return obj;
    }

    private String buildSystemPrompt(String intentName) {
        return "你是 Cretas 食品溯源系统的助手, 帮 " + intentName + " 用户解读工作台编排数据.\n\n"
                + "要求:\n"
                + "1. 用简洁的中文 (300-600 字) 概括关键信息, 包含具体数字 / 名称 / 阈值告警.\n"
                + "2. 用 markdown 结构: 短标题 + 要点列表. 不要输出 JSON 原文.\n"
                + "3. 对每个 tool 返回的关键指标都要提及 (人, 客户, 批次, 金额, 数量, 库存, 告警等).\n"
                + "4. 若数据为空或全 0, 明确说明 '当前暂无 XX' 而非编造.\n"
                + "5. 若发现风险 (库存预警 / 应收过期 / 合规问题 / 告警), 在末尾列 '风险提示' 段落 (不要用 emoji / 装饰符号).\n"
                + "6. 不要输出 _toolCount / _executionOrder / 内部 metadata 字段.\n"
                + "7. 不要使用任何 emoji 或 Unicode 装饰符号 (B 端用户偏好纯文本).\n"
                + "8. 输出不超过 800 字.\n";
    }
}
