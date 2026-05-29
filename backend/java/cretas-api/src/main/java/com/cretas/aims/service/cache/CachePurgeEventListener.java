package com.cretas.aims.service.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges {@link CachePurgeEvent} Spring application events to {@link CachePurgeService}.
 *
 * <p>Uses {@code AFTER_COMMIT} phase — purge fires only after the publishing transaction
 * successfully commits. This is intentional: if the underlying config save rolls back, we
 * must NOT have invalidated cache (would force unnecessary re-computation for unchanged data).
 *
 * <p>The {@code fallbackExecution=true} ensures the listener runs even when there's no
 * transaction context (e.g. when published from a {@code @PostConstruct} or test code that
 * doesn't open a transaction).
 *
 * <p>Wiring for sister chats:
 * <pre>
 * &#64;Autowired ApplicationEventPublisher events;
 *
 * &#64;Transactional
 * public AIIntentConfig saveIntentConfig(AIIntentConfig cfg) {
 *     AIIntentConfig saved = repo.save(cfg);
 *     events.publishEvent(CachePurgeEvent.routing(saved.getFactoryId(), saved.getIntentCode(),
 *             "intent-config-save"));
 *     return saved;
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CachePurgeEventListener {

    private final CachePurgeService cachePurgeService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCachePurgeRequested(CachePurgeEvent event) {
        try {
            int deleted = cachePurgeService.purge(event);
            log.debug("[CachePurgeEventListener] purged {} rows for {}", deleted, event);
        } catch (Exception e) {
            // never fail the publisher's commit due to a cache invalidation hiccup — log and
            // continue. Cache will self-heal via TTL or next manual purge.
            log.warn("[CachePurgeEventListener] purge failed for {}: {}", event, e.getMessage());
        }
    }
}
