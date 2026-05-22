package com.cretas.aims.ai.tool.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RestaurantEconomicsOutputFormatter}.
 *
 * <p>Verifies Steve 决策 2 (brief §2.5): whitelist 字段 in LLM context,
 * 其他 metadata 字段一律 strip.
 */
class RestaurantEconomicsOutputFormatterTest {

    private RestaurantEconomicsOutputFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new RestaurantEconomicsOutputFormatter();
    }

    @Test
    @DisplayName("UT-REOF-01: whitelist constant has exactly the 5 expected keys")
    void whitelistConstant() {
        assertThat(RestaurantEconomicsOutputFormatter.WHITELIST)
                .containsExactlyInAnyOrder(
                        "summary",
                        "topItems",
                        "recommendations",
                        "evidence",
                        "dataAvailable"
                );
    }

    @Test
    @DisplayName("UT-REOF-02: clean payload (only whitelist keys) — all kept")
    void cleanPayloadAllKept() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("summary", Map.of("dataAvailable", true, "data", Map.of("营收", 731048)));
        raw.put("topItems", Map.of("dataAvailable", true, "data", List.of("烤鱼档", "凉菜档")));
        raw.put("recommendations", Map.of("dataAvailable", true, "data", "缩减后厨人力 8%"));
        raw.put("evidence", List.of(Map.of("section", "store_pnl", "dataAvailable", true)));
        raw.put("dataAvailable", true);

        Map<String, Object> safe = formatter.formatForLlm(raw);

        assertThat(safe).hasSize(5);
        assertThat(safe).containsOnlyKeys("summary", "topItems", "recommendations", "evidence", "dataAvailable");
    }

    @Test
    @DisplayName("UT-REOF-03: dirty payload — metadata fields all stripped")
    void dirtyPayloadStripped() {
        Map<String, Object> raw = new LinkedHashMap<>();
        // Valid fields
        raw.put("summary", Map.of("营收", 731048));
        raw.put("topItems", List.of("烤鱼档"));
        raw.put("recommendations", "建议");
        raw.put("evidence", List.of());
        raw.put("dataAvailable", false);
        // Metadata leak candidates (per brief §2.5)
        raw.put("_toolCount", 3);
        raw.put("_internalId", "abc-123");
        raw.put("_trace", List.of("span1", "span2"));
        raw.put("_executionMs", 1234);
        raw.put("_factoryId", "F006");
        raw.put("traceId", "trace-abc");
        raw.put("debug", Map.of("env", "prod"));
        raw.put("message", "internal hint — should not leak to LLM");

        Map<String, Object> safe = formatter.formatForLlm(raw);

        // 5 whitelist kept
        assertThat(safe).hasSize(5);
        assertThat(safe).containsOnlyKeys("summary", "topItems", "recommendations", "evidence", "dataAvailable");
        // All metadata stripped
        assertThat(safe).doesNotContainKeys(
                "_toolCount", "_internalId", "_trace", "_executionMs", "_factoryId",
                "traceId", "debug", "message");
    }

    @Test
    @DisplayName("UT-REOF-04: null input → empty map (defensive)")
    void nullInputReturnsEmptyMap() {
        Map<String, Object> safe = formatter.formatForLlm(null);
        assertThat(safe).isEmpty();
    }

    @Test
    @DisplayName("UT-REOF-05: empty input → empty map")
    void emptyInputReturnsEmptyMap() {
        Map<String, Object> safe = formatter.formatForLlm(Collections.emptyMap());
        assertThat(safe).isEmpty();
    }

    @Test
    @DisplayName("UT-REOF-06: only metadata input → empty map (all stripped)")
    void onlyMetadataInput() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("_toolCount", 1);
        raw.put("traceId", "xyz");
        raw.put("debug", "info");

        Map<String, Object> safe = formatter.formatForLlm(raw);
        assertThat(safe).isEmpty();
    }

    @Test
    @DisplayName("UT-REOF-07: insertion order preserved for whitelist keys (stable LLM prompt position)")
    void insertionOrderPreserved() {
        // Input in non-canonical order
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("evidence", List.of());
        raw.put("dataAvailable", true);
        raw.put("_meta", "drop me");
        raw.put("summary", Map.of("x", 1));
        raw.put("topItems", List.of());
        raw.put("recommendations", "y");

        Map<String, Object> safe = formatter.formatForLlm(raw);

        // Order matches *input* order (whitelist-filtered, but preserves what input gave)
        assertThat(safe.keySet()).containsExactly(
                "evidence", "dataAvailable", "summary", "topItems", "recommendations");
    }

    @Test
    @DisplayName("UT-REOF-08: whitelist keys with null values are preserved (don't strip on value=null)")
    void nullValuesPreserved() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("summary", null);
        raw.put("topItems", null);
        raw.put("recommendations", null);
        raw.put("evidence", null);
        raw.put("dataAvailable", null);
        raw.put("_internal", "strip");

        Map<String, Object> safe = formatter.formatForLlm(raw);

        assertThat(safe).hasSize(5);
        assertThat(safe).containsKeys("summary", "topItems", "recommendations", "evidence", "dataAvailable");
        assertThat(safe.get("summary")).isNull();
        assertThat(safe).doesNotContainKey("_internal");
    }
}
