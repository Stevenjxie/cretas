package com.cretas.aims.service.cache.impl;

import com.cretas.aims.repository.calibration.ToolCallCacheRepository;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.cache.CachePurgeEvent;
import com.cretas.aims.service.cache.CachePurgeScope;
import com.cretas.aims.service.cache.CachePurgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Default implementation of {@link CachePurgeService}.
 *
 * <p>Implementation notes per cache layer:
 * <ul>
 *   <li><b>semantic_cache</b>: keyed by {@code factory_id + input_hash}, intent_code column.
 *     Supports per-intent purge ({@link SemanticCacheService#invalidateByIntentCode}) and
 *     per-factory purge ({@link SemanticCacheService#invalidateByFactory}).</li>
 *   <li><b>tool_call_cache</b>: keyed by {@code session_id + tool_name + parameters_hash}.
 *     Has NO {@code factory_id} column — therefore factory-scoped purge of this table is not
 *     possible. For ROUTING and INDICATOR scopes we skip tool_call_cache (it'll expire naturally
 *     via TTL). For ALL scope we trigger an expired-rows cleanup.</li>
 * </ul>
 *
 * <p>Why TTL-expiry instead of full deleteAll for tool_call_cache on ALL scope?
 * tool_call_cache holds in-flight session data — wiping it mid-conversation breaks the
 * conversation continuity for any active session. Expired-rows cleanup is the safe default;
 * if a truly global flush is needed, call {@link ToolCallCacheRepository#deleteAll()} directly
 * via an admin tool.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CachePurgeServiceImpl implements CachePurgeService {

    private final SemanticCacheService semanticCacheService;
    private final ToolCallCacheRepository toolCallCacheRepository;

    @Override
    @Transactional
    public int purge(CachePurgeEvent event) {
        if (event == null) {
            log.warn("[CachePurge] null event ignored");
            return 0;
        }
        return switch (event.scope()) {
            case ROUTING -> purgeRouting(event.factoryId(), event.targetCode(), event.reason());
            case INDICATOR -> purgeIndicator(event.factoryId(), event.targetCode(), event.reason());
            case ALL -> purgeAll(event.factoryId(), event.reason());
        };
    }

    @Override
    @Transactional
    public int purgeRouting(String factoryId, String intentCode, String reason) {
        if (factoryId == null || factoryId.isBlank()) {
            log.warn("[CachePurge.ROUTING] blank factoryId ignored (reason={})", reason);
            return 0;
        }
        int deleted;
        if (intentCode != null && !intentCode.isBlank()) {
            deleted = semanticCacheService.invalidateByIntentCode(factoryId, intentCode);
            log.info("[CachePurge.ROUTING] factory={} intent={} deleted={} reason={}",
                    factoryId, intentCode, deleted, reason);
        } else {
            deleted = semanticCacheService.invalidateByFactory(factoryId);
            log.info("[CachePurge.ROUTING] factory={} (all intents) deleted={} reason={}",
                    factoryId, deleted, reason);
        }
        return deleted;
    }

    @Override
    @Transactional
    public int purgeIndicator(String factoryId, String indicatorCode, String reason) {
        if (factoryId == null || factoryId.isBlank()) {
            log.warn("[CachePurge.INDICATOR] blank factoryId ignored (reason={})", reason);
            return 0;
        }
        // semantic_cache has no indicator_code column — purge by factory_id covers all cached
        // execution results that may reference the changed indicator. indicatorCode is logged for
        // audit trail only.
        int deleted = semanticCacheService.invalidateByFactory(factoryId);
        log.info("[CachePurge.INDICATOR] factory={} indicator={} deleted={} reason={}",
                factoryId, indicatorCode, deleted, reason);
        return deleted;
    }

    @Override
    @Transactional
    public int purgeAll(String factoryId, String reason) {
        if (factoryId == null || factoryId.isBlank()) {
            log.warn("[CachePurge.ALL] blank factoryId — use admin tool for global flush instead");
            return 0;
        }
        int semanticDeleted = semanticCacheService.invalidateByFactory(factoryId);
        // tool_call_cache has no factory column; trigger expired-rows cleanup as a best-effort
        // sweep without disrupting active sessions.
        int toolExpired = toolCallCacheRepository.deleteExpiredCache(LocalDateTime.now());
        int total = semanticDeleted + toolExpired;
        log.info("[CachePurge.ALL] factory={} semantic_deleted={} tool_expired={} total={} reason={}",
                factoryId, semanticDeleted, toolExpired, total, reason);
        return total;
    }
}
