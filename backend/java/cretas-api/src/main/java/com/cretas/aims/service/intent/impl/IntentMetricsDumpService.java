package com.cretas.aims.service.intent.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Intent Metrics Dump Service — Phase 0 instrumentation backup.
 *
 * <p>Periodically writes a JSONL snapshot of Micrometer counters tagged
 * {@code intent.match.stage.hit} to a rolling log file. The in-memory
 * Micrometer registry is lost on Java service restart; this dump provides
 * persistent backing so the 2-4 week Phase 0 data collection survives prod
 * deploys without losing observability into which layers handle which fraction
 * of intent recognition workload.
 *
 * <p>Disabled via {@code cretas.intent.metrics-dump.enabled=false} (defaults
 * to enabled). Output path configurable via {@code cretas.intent.metrics-dump.path}
 * (default includes {@code ${server.port}} suffix to avoid Blue/Green deploy
 * collisions: {@code intent-metrics-rolling-10010.jsonl} for blue,
 * {@code intent-metrics-rolling-10020.jsonl} for green — both instances can
 * run simultaneously during cutover without interleaved JSONL writes).
 * Dump frequency via {@code cretas.intent.metrics-dump.interval-ms} (default
 * 5 minutes).
 *
 * <p>Counter semantics: stage hit counts are <b>cumulative since JVM start</b>,
 * not per-interval deltas. Each snapshot includes {@code jvmStartedAt} so
 * downstream analysis can detect restart events (when {@code timestamp} jumps
 * but {@code jvmStartedAt} also resets, the next line's counts are NOT a
 * continuation of the prior series).
 *
 * <p>Snapshot shape per JSONL line:
 * <pre>
 * {"timestamp":"2026-05-20T18:45:00-04:00","jvmStartedAt":"2026-05-20T16:30:15-04:00",
 *  "stageHits":{"PHRASE_MATCH":1203,"SEMANTIC":4821,"LLM":312,"NONE":42},"total":6378}
 * </pre>
 *
 * <p>The {@link #snapshot()} method is exposed for read-on-demand access
 * from the admin endpoint {@code GET /api/admin/calibration/metrics/intent-layers}.
 *
 * <p>Defensive: any IO or registry failure is logged at WARN and swallowed.
 * Metrics dumping must never block the primary intent-matching path.
 *
 * @author Cretas Team
 * @since 2026-05-20 (Phase 0 — see docs/superpowers/specs/2026-05-20-intent-architecture-3-layer-redesign.md)
 */
@Slf4j
@Service
public class IntentMetricsDumpService {

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /**
     * Master enable flag for the scheduled JSONL dump. Default true.
     * NOTE: We deliberately do NOT use {@code @ConditionalOnProperty} because that
     * would skip bean creation when set to false, breaking
     * {@code BehaviorCalibrationController}'s {@code @RequiredArgsConstructor}
     * constructor injection. Instead the bean is always created and the flag is
     * checked inside {@link #dumpSnapshot()}. The admin
     * {@code GET /api/admin/calibration/metrics/intent-layers} endpoint
     * (which calls {@link #snapshot()}) is unaffected by this flag — querying
     * counters on demand is always allowed.
     */
    @Value("${cretas.intent.metrics-dump.enabled:true}")
    private boolean enabled;

    /**
     * Output path with {@code ${server.port}} suffix in the default to prevent
     * Blue/Green deploy (10010 active + 10020 standby) from interleaving writes
     * into the same JSONL file. Spring's PropertyPlaceholderHelper resolves the
     * nested placeholder on bean initialization.
     */
    @Value("${cretas.intent.metrics-dump.path:/www/wwwroot/cretas/logs/intent-metrics-rolling-${server.port:10010}.jsonl}")
    private String dumpPath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * JVM start timestamp emitted in every snapshot so downstream analysis can
     * detect restart resets in the cumulative counter series. Captured once at
     * class load to a fixed value (truncated to seconds to avoid variable
     * sub-second precision in {@link OffsetDateTime#toString()}).
     */
    private static final String JVM_STARTED_AT =
            OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();

    /**
     * Append a fresh snapshot to the rolling JSONL file every interval.
     * fixedDelay (vs fixedRate) so a slow disk write doesn't queue overlapping
     * invocations.
     */
    @Scheduled(fixedDelayString = "${cretas.intent.metrics-dump.interval-ms:300000}")
    public void dumpSnapshot() {
        if (!enabled || meterRegistry == null) {
            return;
        }
        try {
            Map<String, Object> snap = snapshot();
            Path path = Path.of(dumpPath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path,
                    objectMapper.writeValueAsString(snap) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("Intent metrics dump failed (path={}): {}", dumpPath, e.getMessage());
        }
    }

    /**
     * Build an on-demand snapshot of intent stage hit counters.
     *
     * <p>Returns the same shape as the JSONL dump format. Used by both the
     * scheduled dump and the admin endpoint. Returns empty {@code stageHits}
     * (with {@code total=0}) when MeterRegistry is unavailable in test envs.
     */
    public Map<String, Object> snapshot() {
        Map<String, Long> stageCounts = new LinkedHashMap<>();
        if (meterRegistry != null) {
            meterRegistry.find("intent.match.stage.hit").counters().forEach(counter -> {
                String stage = counter.getId().getTag("stage");
                if (stage != null) {
                    stageCounts.merge(stage, (long) counter.count(), Long::sum);
                }
            });
        }
        long total = stageCounts.values().stream().mapToLong(Long::longValue).sum();

        Map<String, Object> result = new LinkedHashMap<>();
        // truncatedTo(SECONDS) keeps timestamp format stable — LocalDateTime/OffsetDateTime
        // toString() drops nanos when 0, includes up to 9 digits when non-zero. Downstream
        // parsers using a fixed DateTimeFormatter break on the variable shape.
        result.put("timestamp", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        result.put("jvmStartedAt", JVM_STARTED_AT);
        result.put("stageHits", stageCounts);
        result.put("total", total);
        return result;
    }
}
