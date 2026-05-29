package com.cretas.aims.service.cache;

import java.time.LocalDateTime;

/**
 * Spring application event signalling that a cache purge should occur (Sprint 12).
 *
 * <p>Publishers (e.g. IntentConfigManagementService after save/update, IndicatorService after
 * indicator definition change) emit this event via {@code ApplicationEventPublisher.publishEvent(...)}.
 * The {@link CachePurgeEventListener} subscribes with {@code @TransactionalEventListener(AFTER_COMMIT)}
 * so purge fires only after the underlying transaction successfully commits — avoiding stale
 * invalidation on rollback.
 *
 * <p>Sister chats (BI indicator, 餐饮 backend) should publish this event from their own service
 * code rather than depending on this chat to find every save point. Pattern:
 *
 * <pre>
 * &#64;Autowired ApplicationEventPublisher events;
 *
 * &#64;Transactional
 * public Indicator saveIndicator(Indicator ind) {
 *     Indicator saved = indicatorRepository.save(ind);
 *     events.publishEvent(CachePurgeEvent.indicator(saved.getFactoryId(), saved.getCode(), "indicator-save"));
 *     return saved;
 * }
 * </pre>
 *
 * @param scope which cache layers to purge
 * @param factoryId target factory; null means "all factories" (caller must justify in reason)
 * @param targetCode intent code (for ROUTING) or indicator code (for INDICATOR); null means "all of scope"
 * @param reason short human-readable reason (for log + audit trail)
 * @param publishedAt when the event was created
 */
public record CachePurgeEvent(
        CachePurgeScope scope,
        String factoryId,
        String targetCode,
        String reason,
        LocalDateTime publishedAt
) {

    public static CachePurgeEvent routing(String factoryId, String intentCode, String reason) {
        return new CachePurgeEvent(CachePurgeScope.ROUTING, factoryId, intentCode, reason, LocalDateTime.now());
    }

    public static CachePurgeEvent indicator(String factoryId, String indicatorCode, String reason) {
        return new CachePurgeEvent(CachePurgeScope.INDICATOR, factoryId, indicatorCode, reason, LocalDateTime.now());
    }

    public static CachePurgeEvent all(String factoryId, String reason) {
        return new CachePurgeEvent(CachePurgeScope.ALL, factoryId, null, reason, LocalDateTime.now());
    }
}
