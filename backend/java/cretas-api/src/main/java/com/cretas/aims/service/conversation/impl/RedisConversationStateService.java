package com.cretas.aims.service.conversation.impl;

import com.cretas.aims.entity.conversation.ConversationTurn;
import com.cretas.aims.service.conversation.ConversationStateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis-backed conversation state service.
 *
 * <p>Storage pattern:
 * <pre>
 * Key:   conv:{factoryId}:{userId}:{deviceId}
 * Value: Redis List of JSON-serialized ConversationTurn records
 * Ops:   LPUSH (newest first) → LTRIM (cap at maxTurns) → EXPIRE (reset TTL)
 * </pre>
 *
 * <p>Fail-open behavior: when Redis is unavailable or slow, all methods
 * return neutral values (empty list, 0 count, silent drop on append)
 * rather than propagating exceptions. Conversation context is a
 * nice-to-have for better intent recognition, not a hard requirement.
 *
 * <p>Multi-device isolation via deviceId — same user on mobile + web
 * doesn't share context. Legacy callers (no deviceId) use "default".
 *
 * <p>Part of P4 Task 4.2.
 *
 * <p><strong>Conditional registration (Apr 11 2026 hot-fix)</strong>: only
 * registered when a {@link StringRedisTemplate} bean exists. On profiles
 * that exclude {@code RedisAutoConfiguration} (e.g. local {@code pg} dev
 * profile), this bean is silently absent and
 * {@link com.cretas.aims.service.impl.AIIntentServiceImpl} — which injects
 * {@link ConversationStateService} with {@code required=false} — falls back
 * to zero-context intent recognition. Without this guard, Spring's
 * constructor-resolver tries the no-arg fallback and fails with
 * {@code NoSuchMethodException: RedisConversationStateService.<init>()},
 * blocking application startup entirely.
 */
@Slf4j
@Service
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisConversationStateService implements ConversationStateService {

    @Getter
    private final StringRedisTemplate template;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final int maxTurns;

    /** Spring-managed constructor via @Value injection. */
    public RedisConversationStateService(
            StringRedisTemplate template,
            ObjectMapper objectMapper,
            @Value("${cretas.conversation.ttl-minutes:30}") long ttlMinutes,
            @Value("${cretas.conversation.max-turns:20}") int maxTurns) {
        this.template = template;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.maxTurns = Math.max(20, maxTurns);
    }

    /** Test-friendly constructor with explicit Duration. */
    public RedisConversationStateService(
            StringRedisTemplate template,
            ObjectMapper objectMapper,
            Duration ttl,
            int maxTurns) {
        this.template = template;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        this.maxTurns = maxTurns;
    }

    /**
     * Build the Redis key. Includes deviceId for multi-device isolation.
     * Legacy callers (no deviceId) use "default".
     */
    private String key(String factoryId, String userId, String deviceId) {
        String device = (deviceId == null || deviceId.isBlank()) ? "default" : deviceId;
        return "conv:" + factoryId + ":" + userId + ":" + device;
    }

    @Override
    public List<ConversationTurn> loadRecent(String factoryId, String userId, int n) {
        return loadRecent(factoryId, userId, null, n);
    }

    /**
     * Load recent turns with explicit device isolation + fail-open.
     *
     * <p>Redis failure modes:
     * <ul>
     *   <li>Unreachable / slow → empty list, LOG WARN, proceed</li>
     *   <li>Deserialization error for one entry → skip that entry, continue with rest</li>
     *   <li>null/empty → empty list (normal when no prior turns)</li>
     * </ul>
     */
    public List<ConversationTurn> loadRecent(
            String factoryId, String userId, String deviceId, int n) {
        String k = key(factoryId, userId, deviceId);
        try {
            List<String> raw = template.opsForList().range(k, 0, Math.max(0, n - 1));
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            return raw.stream()
                    .map(this::fromJson)
                    .filter(t -> t != null)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Redis loadRecent failed for key {}, fail-open with empty context: {}",
                    k, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void appendTurn(String factoryId, String userId, ConversationTurn turn) {
        appendTurn(factoryId, userId, null, turn);
    }

    public void appendTurn(
            String factoryId, String userId, String deviceId, ConversationTurn turn) {
        String k = key(factoryId, userId, deviceId);
        try {
            String json = objectMapper.writeValueAsString(turn);
            template.opsForList().leftPush(k, json);
            template.opsForList().trim(k, 0, maxTurns - 1);
            template.expire(k, ttl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize conversation turn: {}", e.getMessage(), e);
        } catch (Exception e) {
            // Fail-open: Redis unavailable = we just lose this turn's history.
            log.warn("Redis appendTurn failed for key {}, context will be incomplete: {}",
                    k, e.getMessage());
        }
    }

    @Override
    public void clear(String factoryId, String userId) {
        try {
            Set<String> keys = template.keys("conv:" + factoryId + ":" + userId + ":*");
            if (keys != null && !keys.isEmpty()) {
                template.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Redis clear failed for user {}/{}: {}", factoryId, userId, e.getMessage());
        }
    }

    @Override
    public int turnCount(String factoryId, String userId) {
        try {
            Long size = template.opsForList().size(key(factoryId, userId, null));
            return size == null ? 0 : size.intValue();
        } catch (Exception e) {
            log.warn("Redis turnCount failed: {}", e.getMessage());
            return 0;
        }
    }

    private ConversationTurn fromJson(String json) {
        try {
            return objectMapper.readValue(json, ConversationTurn.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize conversation turn: {}", e.getMessage());
            return null;
        }
    }
}
