package com.cretas.aims.service.cache;

/**
 * Cache purge scope (Sprint 12).
 *
 * <p>Defines which cache layers are affected by a purge request:
 * <ul>
 *   <li>{@link #ROUTING} — purge intent-routing caches (semantic_cache by intent_code + tool_call_cache).
 *     Triggered when {@code ai_intent_configs} is updated, IntentKnowledgeBase phrase map is reloaded,
 *     or a routing fix is deployed.</li>
 *   <li>{@link #INDICATOR} — purge indicator-related caches (semantic_cache by factory_id).
 *     Triggered when {@code indicators} / {@code indicator_thresholds} / {@code indicator_computations}
 *     are written. The semantic cache stores execution results that include indicator values, so any
 *     indicator change should invalidate result-bearing cache entries.</li>
 *   <li>{@link #ALL} — purge both routing and indicator scopes plus tool_call_cache for the factory.
 *     Used by the admin endpoint for emergency cache flush.</li>
 * </ul>
 *
 * <p>Note: scope is informational — the {@link CachePurgeService} implementation decides which
 * repository methods to call for each scope. ROUTING + INDICATOR are NOT mutually exclusive — a
 * single event with scope=ROUTING does not preclude another event with scope=INDICATOR firing later.
 */
public enum CachePurgeScope {
    ROUTING,
    INDICATOR,
    ALL
}
