package com.cretas.aims.service.intent.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link IntentMetricsDumpService}.
 *
 * <p>Covers Phase 0 instrumentation behavior:
 * <ul>
 *   <li>{@code snapshot()} returns zero counts when MeterRegistry absent (test env)</li>
 *   <li>{@code snapshot()} aggregates counters by {@code stage} tag</li>
 *   <li>{@code dumpSnapshot()} skips write when {@code enabled=false} (regression
 *       guard for the {@code @ConditionalOnProperty} bug fix: bean now always
 *       created, flag checked at runtime)</li>
 *   <li>{@code dumpSnapshot()} appends JSONL line when enabled</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-05-20
 */
@DisplayName("IntentMetricsDumpService Unit Tests")
class IntentMetricsDumpServiceTest {

    private IntentMetricsDumpService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        service = new IntentMetricsDumpService();
        meterRegistry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(service, "meterRegistry", meterRegistry);
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    @Test
    @DisplayName("snapshot() returns zero counts when MeterRegistry is null")
    void snapshot_nullRegistry_returnsZeroCounts() {
        ReflectionTestUtils.setField(service, "meterRegistry", null);

        Map<String, Object> result = service.snapshot();

        assertThat(result).containsKeys("timestamp", "jvmStartedAt", "stageHits", "total");
        assertThat((Map<?, ?>) result.get("stageHits")).isEmpty();
        assertThat(result.get("total")).isEqualTo(0L);
    }

    @Test
    @DisplayName("snapshot() timestamp is parseable ISO OffsetDateTime truncated to seconds")
    void snapshot_timestampShapeIsStable() {
        Map<String, Object> result = service.snapshot();

        String timestamp = (String) result.get("timestamp");
        // Must parse as OffsetDateTime (regression: previously LocalDateTime.toString()
        // produced variable-precision sub-second digits that broke fixed parsers).
        assertThatCode(() -> OffsetDateTime.parse(timestamp))
                .doesNotThrowAnyException();
        // truncatedTo(SECONDS) guarantees no fractional seconds in the output.
        assertThat(timestamp).doesNotContain(".");
    }

    @Test
    @DisplayName("snapshot() includes jvmStartedAt for restart detection")
    void snapshot_includesJvmStartedAt() {
        Map<String, Object> result = service.snapshot();

        String jvmStartedAt = (String) result.get("jvmStartedAt");
        assertThat(jvmStartedAt).isNotNull();
        assertThatCode(() -> OffsetDateTime.parse(jvmStartedAt))
                .doesNotThrowAnyException();
        // Two snapshot calls return the SAME jvmStartedAt (static final field).
        Map<String, Object> result2 = service.snapshot();
        assertThat(result2.get("jvmStartedAt")).isEqualTo(jvmStartedAt);
    }

    @Test
    @DisplayName("snapshot() aggregates counters by 'stage' tag")
    void snapshot_withCounters_aggregatesByStage() {
        meterRegistry.counter("intent.match.stage.hit", "stage", "PHRASE_MATCH").increment(120);
        meterRegistry.counter("intent.match.stage.hit", "stage", "SEMANTIC").increment(500);
        meterRegistry.counter("intent.match.stage.hit", "stage", "LLM").increment(30);

        Map<String, Object> result = service.snapshot();

        @SuppressWarnings("unchecked")
        Map<String, Long> hits = (Map<String, Long>) result.get("stageHits");
        assertThat(hits)
                .containsEntry("PHRASE_MATCH", 120L)
                .containsEntry("SEMANTIC", 500L)
                .containsEntry("LLM", 30L);
        assertThat(result.get("total")).isEqualTo(650L);
    }

    @Test
    @DisplayName("snapshot() ignores counters with other names")
    void snapshot_ignoresUnrelatedCounters() {
        meterRegistry.counter("intent.match.stage.hit", "stage", "SEMANTIC").increment(10);
        meterRegistry.counter("some.other.counter", "tag", "value").increment(999);
        meterRegistry.counter("intent.tool.call", "tool", "x").increment(50);

        Map<String, Object> result = service.snapshot();

        @SuppressWarnings("unchecked")
        Map<String, Long> hits = (Map<String, Long>) result.get("stageHits");
        assertThat(hits).hasSize(1).containsEntry("SEMANTIC", 10L);
        assertThat(result.get("total")).isEqualTo(10L);
    }

    @Test
    @DisplayName("dumpSnapshot() skips file write when enabled=false")
    void dumpSnapshot_disabled_skipsWrite(@TempDir Path tempDir) {
        Path target = tempDir.resolve("metrics.jsonl");
        ReflectionTestUtils.setField(service, "dumpPath", target.toString());
        ReflectionTestUtils.setField(service, "enabled", false);

        meterRegistry.counter("intent.match.stage.hit", "stage", "PHRASE_MATCH").increment(10);

        service.dumpSnapshot();

        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    @DisplayName("dumpSnapshot() skips when MeterRegistry is null")
    void dumpSnapshot_nullRegistry_skipsWrite(@TempDir Path tempDir) {
        Path target = tempDir.resolve("metrics.jsonl");
        ReflectionTestUtils.setField(service, "dumpPath", target.toString());
        ReflectionTestUtils.setField(service, "meterRegistry", null);

        service.dumpSnapshot();

        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    @DisplayName("dumpSnapshot() appends JSONL line containing stage hits, total, timestamp, jvmStartedAt")
    void dumpSnapshot_enabled_writesJsonlLine(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("metrics.jsonl");
        ReflectionTestUtils.setField(service, "dumpPath", target.toString());

        meterRegistry.counter("intent.match.stage.hit", "stage", "SEMANTIC").increment(42);
        meterRegistry.counter("intent.match.stage.hit", "stage", "LLM").increment(8);

        service.dumpSnapshot();

        assertThat(Files.exists(target)).isTrue();
        List<String> lines = Files.readAllLines(target);
        assertThat(lines).hasSize(1);
        String line = lines.get(0);
        assertThat(line)
                .contains("\"SEMANTIC\":42")
                .contains("\"LLM\":8")
                .contains("\"total\":50")
                .contains("\"timestamp\":")
                .contains("\"jvmStartedAt\":");
    }

    @Test
    @DisplayName("dumpSnapshot() appends second line on second invocation (rolling log)")
    void dumpSnapshot_secondCall_appends(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("metrics.jsonl");
        ReflectionTestUtils.setField(service, "dumpPath", target.toString());

        meterRegistry.counter("intent.match.stage.hit", "stage", "SEMANTIC").increment(1);
        service.dumpSnapshot();

        meterRegistry.counter("intent.match.stage.hit", "stage", "SEMANTIC").increment(1);
        service.dumpSnapshot();

        List<String> lines = Files.readAllLines(target);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"total\":1");
        assertThat(lines.get(1)).contains("\"total\":2");
    }

    @Test
    @DisplayName("dumpSnapshot() creates parent directories if missing")
    void dumpSnapshot_createsParentDirectory(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("nested/sub/dir/metrics.jsonl");
        ReflectionTestUtils.setField(service, "dumpPath", target.toString());

        meterRegistry.counter("intent.match.stage.hit", "stage", "SEMANTIC").increment(1);

        service.dumpSnapshot();

        assertThat(Files.exists(target)).isTrue();
    }
}
