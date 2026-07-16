package com.cretas.aims.service.startup;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AiWarmupStatusRegistry {

    public static final String INTENT_CACHE = "intentEmbeddingCache";
    public static final String SEMANTIC_MATCHER = "semanticIntentMatcher";

    private final Map<String, Snapshot> statuses = new ConcurrentHashMap<>();

    public AiWarmupStatusRegistry() {
        statuses.put(INTENT_CACHE, Snapshot.notStarted());
        statuses.put(SEMANTIC_MATCHER, Snapshot.notStarted());
    }

    public void warming(String component) {
        statuses.put(component, new Snapshot(WarmupState.WARMING, System.currentTimeMillis(), 0, null));
    }

    public void ready(String component) {
        Snapshot previous = statuses.getOrDefault(component, Snapshot.notStarted());
        statuses.put(component, new Snapshot(WarmupState.READY, previous.startedAtMs(),
                elapsed(previous.startedAtMs()), null));
    }

    public void failed(String component, Throwable error) {
        Snapshot previous = statuses.getOrDefault(component, Snapshot.notStarted());
        statuses.put(component, new Snapshot(WarmupState.FAILED, previous.startedAtMs(),
                elapsed(previous.startedAtMs()), safeMessage(error)));
    }

    public Map<String, Snapshot> snapshot() {
        Map<String, Snapshot> copy = new LinkedHashMap<>();
        copy.put(INTENT_CACHE, statuses.get(INTENT_CACHE));
        copy.put(SEMANTIC_MATCHER, statuses.get(SEMANTIC_MATCHER));
        return Map.copyOf(copy);
    }

    private long elapsed(long startedAtMs) {
        return startedAtMs == 0 ? 0 : Math.max(0, System.currentTimeMillis() - startedAtMs);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public record Snapshot(WarmupState state, long startedAtMs, long durationMs, String error) {
        static Snapshot notStarted() {
            return new Snapshot(WarmupState.NOT_STARTED, 0, 0, null);
        }
    }
}
