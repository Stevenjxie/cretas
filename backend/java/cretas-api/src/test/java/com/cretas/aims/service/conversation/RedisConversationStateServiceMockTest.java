package com.cretas.aims.service.conversation;

import com.cretas.aims.entity.conversation.ConversationTurn;
import com.cretas.aims.service.conversation.impl.RedisConversationStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito-based unit tests for RedisConversationStateService.
 * Runs without Docker — CI can also run the Testcontainers integration
 * test for end-to-end verification.
 */
class RedisConversationStateServiceMockTest {

    private StringRedisTemplate template;
    private ListOperations<String, String> listOps;
    private ObjectMapper mapper;
    private RedisConversationStateService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        template = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        when(template.opsForList()).thenReturn(listOps);
        mapper = new ObjectMapper();
        service = new RedisConversationStateService(
                template, mapper, Duration.ofMinutes(30), 10);
    }

    @Test
    void appendTurn_calls_lpush_ltrim_expire_in_order() throws Exception {
        ConversationTurn turn = new ConversationTurn(
                "帮我看成本刚性",
                "RESTAURANT_COST_RIGIDITY",
                "restaurant_cost_rigidity_analysis",
                null,
                Map.of("costRigidity", 0.561),
                1700000000000L
        );

        service.appendTurn("F001", "U100", turn);

        verify(listOps, times(1)).leftPush(eq("conv:F001:U100:default"), anyString());
        verify(listOps, times(1)).trim(eq("conv:F001:U100:default"), eq(0L), eq(9L));
        verify(template, times(1)).expire(
                eq("conv:F001:U100:default"), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void productionConstructor_never_retains_fewer_than_twenty_turns() {
        RedisConversationStateService productionService =
                new RedisConversationStateService(template, mapper, 30L, 10);

        productionService.appendTurn(
                "F001", "U100", ConversationTurn.userOnly("keep twenty turns"));

        verify(listOps).trim("conv:F001:U100:default", 0L, 19L);
    }

    @Test
    void loadRecent_returns_parsed_turns() throws Exception {
        ConversationTurn turn1 = new ConversationTurn(
                "msg1", null, null, null, null, 100L);
        ConversationTurn turn2 = new ConversationTurn(
                "msg2", null, null, null, null, 200L);
        String json1 = mapper.writeValueAsString(turn1);
        String json2 = mapper.writeValueAsString(turn2);

        when(listOps.range(eq("conv:F001:U100:default"), eq(0L), eq(4L)))
                .thenReturn(List.of(json1, json2));

        List<ConversationTurn> loaded = service.loadRecent("F001", "U100", 5);

        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).userMessage()).isEqualTo("msg1");
        assertThat(loaded.get(1).userMessage()).isEqualTo("msg2");
    }

    @Test
    void loadRecent_returns_empty_list_when_redis_returns_null() {
        when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(null);

        List<ConversationTurn> loaded = service.loadRecent("F001", "U100", 5);

        assertThat(loaded).isEmpty();
    }

    @Test
    void loadRecent_is_fail_open_when_redis_throws() {
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("Redis unavailable"));

        // MUST NOT throw — conversation context is best-effort
        List<ConversationTurn> loaded = service.loadRecent("F001", "U100", 5);

        assertThat(loaded).isEmpty();
    }

    @Test
    void appendTurn_is_fail_open_when_redis_throws() {
        when(listOps.leftPush(anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis down"));

        // MUST NOT throw — if Redis fails, we just lose this turn's history
        service.appendTurn("F001", "U100", ConversationTurn.userOnly("hi"));
    }

    @Test
    void loadRecent_skips_unparseable_entries() {
        when(listOps.range(eq("conv:F001:U100:default"), eq(0L), eq(4L)))
                .thenReturn(List.of("{bad_json", "{\"userMessage\":\"good\",\"timestamp\":100}"));

        List<ConversationTurn> loaded = service.loadRecent("F001", "U100", 5);

        // Bad entry dropped, good entry kept
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).userMessage()).isEqualTo("good");
    }

    @Test
    void clear_deletes_all_device_keys_for_user() {
        when(template.keys(eq("conv:F001:U100:*")))
                .thenReturn(Set.of("conv:F001:U100:default", "conv:F001:U100:mobile"));

        service.clear("F001", "U100");

        verify(template, times(1)).delete(any(Set.class));
    }

    @Test
    void turnCount_returns_size() {
        when(listOps.size(eq("conv:F001:U100:default"))).thenReturn(7L);

        int count = service.turnCount("F001", "U100");

        assertThat(count).isEqualTo(7);
    }

    @Test
    void turnCount_returns_zero_on_redis_failure() {
        when(listOps.size(anyString())).thenThrow(new RuntimeException("Redis down"));

        int count = service.turnCount("F001", "U100");

        assertThat(count).isEqualTo(0);
    }

    @Test
    void conversationTurn_userOnly_factory_works() {
        ConversationTurn turn = ConversationTurn.userOnly("test message");
        assertThat(turn.userMessage()).isEqualTo("test message");
        assertThat(turn.intentCode()).isNull();
        assertThat(turn.toolName()).isNull();
        assertThat(turn.skillName()).isNull();
        assertThat(turn.response()).isNull();
        assertThat(turn.timestamp()).isGreaterThan(0L);
    }
}
