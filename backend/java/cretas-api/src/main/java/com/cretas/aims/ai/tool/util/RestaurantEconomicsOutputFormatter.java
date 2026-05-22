package com.cretas.aims.ai.tool.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Sprint 11 — LLM-facing output formatter for the餐厅 economics analysis Tool.
 *
 * <p><b>Steve 决策 2 (per brief §2.5)</b>: 防 LLM narrative metadata leak —
 * whitelist 字段在 output, 其他字段一律 strip (不进 LLM context).
 *
 * <p>Whitelist (per brief §2.5):
 * <ul>
 *   <li>{@code summary}        — store_pnl_one_pager 诊断 summary</li>
 *   <li>{@code topItems}       — shrinkage_analysis Top-N 异常品</li>
 *   <li>{@code recommendations} — cost_rigidity 改进建议</li>
 *   <li>{@code evidence}       — sub-Tool warnings + sections</li>
 *   <li>{@code dataAvailable}  — top-level 全可用标志</li>
 * </ul>
 *
 * <p>Stripped (typical metadata pollutants we want to keep out of LLM):
 * {@code _toolCount, _internalId, _trace, _executionMs, _factoryId, traceId, debug}.
 *
 * <p>Usage:
 * <pre>
 *   Map&lt;String,Object&gt; rawResult = compositeTool.doExecute(...);
 *   Map&lt;String,Object&gt; safeForLlm = formatter.formatForLlm(rawResult);
 *   // pass safeForLlm to LLM prompt builder
 * </pre>
 *
 * <p>This formatter is namespaced "restaurant economics" so other Composite Tools
 * are free to define their own whitelist (no shared global state).
 */
@Slf4j
@Component
public class RestaurantEconomicsOutputFormatter {

    /**
     * Whitelisted top-level keys allowed into the LLM context.
     * Per brief §2.5 decision 2 — anything else is metadata leak risk.
     */
    public static final Set<String> WHITELIST = Set.of(
            "summary",
            "topItems",
            "recommendations",
            "evidence",
            "dataAvailable"
    );

    /**
     * Strip all non-whitelist top-level keys.
     *
     * <p>Null / empty input → empty map (defensive, never NPE).
     *
     * <p>The returned map preserves whitelist key insertion order
     * (LinkedHashMap) so LLM prompt template can rely on stable key positions.
     *
     * @param rawResult raw doExecute() output (may contain leaky metadata)
     * @return whitelist-filtered copy, safe to inject into LLM context
     */
    public Map<String, Object> formatForLlm(Map<String, Object> rawResult) {
        if (rawResult == null || rawResult.isEmpty()) {
            log.debug("formatForLlm: empty input — returning empty map");
            return Collections.emptyMap();
        }

        Map<String, Object> safe = new LinkedHashMap<>();
        int strippedCount = 0;
        for (Map.Entry<String, Object> entry : rawResult.entrySet()) {
            if (WHITELIST.contains(entry.getKey())) {
                safe.put(entry.getKey(), entry.getValue());
            } else {
                strippedCount++;
                if (log.isDebugEnabled()) {
                    log.debug("formatForLlm: stripped non-whitelist key: {}", entry.getKey());
                }
            }
        }

        if (strippedCount > 0) {
            log.info("formatForLlm: stripped {} non-whitelist keys (kept {} whitelist), "
                    + "preventing metadata leak per brief §2.5 decision 2",
                    strippedCount, safe.size());
        }
        return safe;
    }
}
