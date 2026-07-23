package com.cretas.aims.service.impl;

import com.cretas.aims.config.IntentMatchingConfig;
import com.cretas.aims.dto.intent.RouteDecision;
import com.cretas.aims.repository.IntentMatchRecordRepository;
import com.cretas.aims.service.SemanticRouterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

/**
 * W1a Shadow Router Harness
 *
 * Runs {@link SemanticRouterService#route(String, String, int)} as a CHALLENGER on a background
 * thread and logs champion-vs-challenger agreement to the {@code intent_match_records} shadow
 * columns. Mirrors the original BERT shadow-classify pattern (that service was removed as
 * dead code).
 *
 * <p><strong>Never affects the live result.</strong> Any exception from the challenger router is
 * caught and logged at WARN level — the caller always completes normally.
 *
 * <p>Uses the shared {@code aiAnalysisExecutor} thread pool so shadow work shares existing
 * concurrency budget without introducing a new executor bean.
 *
 * @author Cretas Team
 * @since 2026-06-03
 */
@Slf4j
@Service
public class ShadowRouterHarnessService {

    @Autowired
    private SemanticRouterService semanticRouterService;

    @Autowired
    private IntentMatchRecordRepository recordRepository;

    @Autowired
    private IntentMatchingConfig matchingConfig;

    /**
     * Asynchronously run the semantic router as a challenger and record agreement.
     *
     * @param recordId            ID of the already-persisted {@code IntentMatchRecord}
     * @param userInput           raw user query (must be non-blank)
     * @param championIntentCode  intent code chosen by the live (champion) path
     * @param championConfidence  champion confidence score (informational, not stored here)
     * @param factoryId           factory/tenant context passed to the router
     */
    @Async("aiAnalysisExecutor")
    @Transactional
    public void shadowRoute(String recordId, String userInput,
                            String championIntentCode, double championConfidence,
                            String factoryId) {
        if (recordId == null || userInput == null || userInput.isBlank()) {
            return;
        }

        // Honour the global sample rate so shadow traffic can be throttled in production.
        double sampleRate = matchingConfig.getShadowModeSampleRate();
        if (sampleRate < 1.0 && ThreadLocalRandom.current().nextDouble() > sampleRate) {
            return;
        }

        try {
            long startMs = System.currentTimeMillis();
            RouteDecision decision = semanticRouterService.route(factoryId, userInput, 1);
            long latencyMs = System.currentTimeMillis() - startMs;

            String challengerIntent = (decision != null) ? decision.getBestMatchIntentCode() : null;
            double challengerScore  = (decision != null) ? decision.getTopScore() : 0.0;

            // Agreement: both are non-null and equal.
            boolean agreed = challengerIntent != null && challengerIntent.equals(championIntentCode);

            recordRepository.updateShadowResult(
                    recordId,
                    challengerIntent,
                    BigDecimal.valueOf(challengerScore),
                    agreed,
                    /* entropy  */ null,
                    /* margin   */ null,
                    /* maxLogit */ null
            );

            if (!agreed) {
                // ZPD boundary: disagreement sample is valuable for active learning.
                recordRepository.markZpdBoundary(recordId);
                log.info("[ShadowRouter] DISAGREE recordId={} champion={} challenger={} "
                                + "(score={}, routeType={}, latency={}ms)",
                        recordId, championIntentCode, challengerIntent,
                        String.format("%.4f", challengerScore),
                        decision != null ? decision.getRouteType() : "N/A",
                        latencyMs);
            } else {
                log.debug("[ShadowRouter] AGREE recordId={} intent={} (latency={}ms)",
                        recordId, challengerIntent, latencyMs);
            }

        } catch (Exception e) {
            // NEVER propagate — shadow harness must be transparent to the caller.
            log.warn("[ShadowRouter] failed recordId={}: {}", recordId, e.getMessage());
        }
    }
}
