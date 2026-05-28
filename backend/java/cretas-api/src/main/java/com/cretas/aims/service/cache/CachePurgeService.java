package com.cretas.aims.service.cache;

/**
 * Cache purge service (Sprint 12 — addresses
 * {@code feedback_stale_cache_poisoning_survives_backend_fix} HARD rule).
 *
 * <p>Provides programmatic purge of caches that affect AI routing decisions and indicator value
 * retrieval. Cache layers touched:
 * <ul>
 *   <li>{@code semantic_cache} (via {@link com.cretas.aims.service.SemanticCacheService}) — keyed
 *     by {@code factory_id + input_hash}, stores intent_result + execution_result. Routing-poisoning
 *     vector when an old result is cached before a fix and replayed after.</li>
 *   <li>{@code tool_call_cache} (via {@link com.cretas.aims.repository.calibration.ToolCallCacheRepository})
 *     — keyed by {@code session_id + tool_name + parameters_hash}. Stores raw tool results.</li>
 * </ul>
 *
 * <p>Two entry points:
 * <ol>
 *   <li>Synchronous: {@link #purge(CachePurgeEvent)} — caller blocks until purge done. Returns
 *     number of rows deleted across all touched layers.</li>
 *   <li>Asynchronous via Spring event — publishers fire {@link CachePurgeEvent} and the
 *     {@link CachePurgeEventListener} bridges to this service. Recommended for normal service
 *     code paths.</li>
 * </ol>
 */
public interface CachePurgeService {

    /**
     * Execute a purge request against the cache layers indicated by the event's scope.
     *
     * @param event purge request (scope + targets)
     * @return total rows deleted across all cache layers
     */
    int purge(CachePurgeEvent event);

    /**
     * Convenience: purge intent-routing caches.
     *
     * @param factoryId target factory, never null
     * @param intentCode optional — if null, all routing rows for the factory are purged
     * @param reason short human-readable reason for log
     * @return rows deleted
     */
    int purgeRouting(String factoryId, String intentCode, String reason);

    /**
     * Convenience: purge indicator-related caches.
     *
     * @param factoryId target factory, never null
     * @param indicatorCode optional — currently used only for log/audit (semantic cache is not
     *                      keyed by indicator code, so the purge is factory-wide)
     * @param reason short human-readable reason
     * @return rows deleted
     */
    int purgeIndicator(String factoryId, String indicatorCode, String reason);

    /**
     * Convenience: purge ALL caches for a factory (semantic + tool_call). Use cautiously.
     *
     * @param factoryId target factory, never null
     * @param reason short human-readable reason
     * @return rows deleted
     */
    int purgeAll(String factoryId, String reason);
}
