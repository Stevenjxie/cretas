package com.cretas.aims.scheduler;

import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.service.indicator.IndicatorQueryService;
import com.cretas.aims.service.indicator.dto.IndicatorValueResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 1 Sprint 1 Day 9 — daily indicator recompute scheduler.
 *
 * <p>Runs on every Spring instance (10010 blue / 10020 green / 10011 test) but
 * {@link SchedulerLock} ensures only ONE instance actually executes per day —
 * critical for Blue/Green deploy overlap windows.
 *
 * <p>Iterates all active indicators per active factory. For each indicator
 * dispatches via {@link IndicatorQueryService#computeForCode} which internally
 * routes by {@code computeStrategy}:
 * <ul>
 *   <li>REALTIME / CACHED — re-fetch via Python client (or in-memory aggregation
 *       when Python stub returns BigDecimal.ZERO during Phase 2B incubation)</li>
 *   <li>PRECOMPUTED — skipped here; Phase 2B Python-side cron owns those</li>
 *   <li>JPA_QUERY — re-runs query against current Cretas data</li>
 * </ul>
 *
 * <p>Per {@code .claude/rules/fool-proof-design.md} HARD rule (degraded mode):
 * one indicator failure does NOT abort the batch. Failures are logged + counted
 * + exposed via metrics; the scheduler continues to the next indicator.
 *
 * <p>Period semantics for Sprint 1: monthly rolling window
 * {@code (today - 30 days, today)}. Phase 2 can add per-indicator period
 * configuration via {@code Indicator.config} JSONB.
 *
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 9)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndicatorRecomputeScheduler {

    private final FactoryRepository factoryRepository;
    private final IndicatorRepository indicatorRepository;
    private final IndicatorQueryService indicatorQueryService;

    @Value("${cretas.indicator.recompute.enabled:true}")
    private boolean enabled;

    /**
     * Daily recompute at 02:00 server time.
     *
     * <p>cron 秒 分 时 日 月 周 — {@code 0 0 2 * * ?} = 02:00:00 every day.
     *
     * <p>{@code lockAtMostFor = PT30M}: even if JVM crashes mid-run, after
     * 30 min the lock auto-releases so the next day's run can proceed.
     *
     * <p>{@code lockAtLeastFor = PT5M}: prevents fast-cycle re-runs from
     * blue→green deploys; once acquired, lock held ≥5 min regardless of
     * actual run duration.
     */
    @Scheduled(cron = "${cretas.indicator.recompute.cron:0 0 2 * * ?}")
    @SchedulerLock(
            name = "IndicatorRecomputeScheduler.recomputeAll",
            lockAtMostFor = "PT30M",
            lockAtLeastFor = "PT5M")
    public void recomputeAll() {
        if (!enabled) {
            log.debug("[IndicatorRecomputeScheduler] disabled (cretas.indicator.recompute.enabled=false)");
            return;
        }

        long startMs = System.currentTimeMillis();
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(30);
        log.info("[IndicatorRecomputeScheduler] starting daily recompute, period=({} .. {})", start, end);

        List<String> factoryIds;
        try {
            factoryIds = factoryRepository.findAllActiveFactoryIds();
        } catch (Exception e) {
            log.error("[IndicatorRecomputeScheduler] failed loading active factories: {}", e.getMessage(), e);
            return;
        }

        int totalIndicators = 0;
        int success = 0;
        int skipped = 0;
        int failed = 0;

        for (String factoryId : factoryIds) {
            List<Indicator> indicators;
            try {
                indicators = indicatorRepository.findActiveByFactoryId(factoryId);
            } catch (Exception e) {
                log.error("[IndicatorRecomputeScheduler] failed loading indicators for factory={}: {}",
                        factoryId, e.getMessage(), e);
                continue;
            }
            totalIndicators += indicators.size();

            for (Indicator ind : indicators) {
                String strategy = ind.getComputeStrategy();
                if ("PRECOMPUTED".equalsIgnoreCase(strategy)) {
                    // PRECOMPUTED is owned by Phase 2B Python-side cron — skip here
                    skipped++;
                    log.debug("[IndicatorRecomputeScheduler] skip PRECOMPUTED indicator code={} factory={}",
                            ind.getCode(), factoryId);
                    continue;
                }

                try {
                    IndicatorValueResult result = indicatorQueryService.computeForCode(
                            ind.getCode(), factoryId, start, end);
                    if (result != null) {
                        success++;
                        log.debug("[IndicatorRecomputeScheduler] recomputed code={} factory={} value={} source={}",
                                ind.getCode(), factoryId, result.value(), result.source());
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("[IndicatorRecomputeScheduler] recompute failed code={} factory={}: {}",
                            ind.getCode(), factoryId, e.getMessage());
                    // Per fool-proof-design: don't abort batch, continue
                }
            }
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("[IndicatorRecomputeScheduler] complete: factories={} indicators={} success={} skipped={} failed={} duration_ms={}",
                factoryIds.size(), totalIndicators, success, skipped, failed, durationMs);
    }
}
