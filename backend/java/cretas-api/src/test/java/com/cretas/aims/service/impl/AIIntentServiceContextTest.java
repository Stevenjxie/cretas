package com.cretas.aims.service.impl;

import com.cretas.aims.entity.conversation.ConversationTurn;
import com.cretas.aims.service.conversation.ConversationStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit test for P4 Task 4.3 — verifies that
 * AIIntentServiceImpl.recognizeIntent(factoryId, userInput, userId)
 * loads conversation context when userId is provided and appends the
 * turn after successful recognition.
 *
 * <p>Does NOT use @SpringBootTest — constructs the service with
 * mocked dependencies to keep the test fast and focused on the
 * context-tracking contract only.
 */
class AIIntentServiceContextTest {

    @Mock
    private ConversationStateService contextService;

    private AIIntentServiceImpl intentService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        // Construct the service with null pipeline/config/feedback services.
        // These are final fields injected by @RequiredArgsConstructor —
        // we pass null here because we only test the context-tracking wrapper,
        // not the downstream recognition pipeline.
        intentService = new AIIntentServiceImpl(null, null, null);
        // Inject context service via reflection (field is private, non-final)
        try {
            var field = AIIntentServiceImpl.class.getDeclaredField("conversationStateService");
            field.setAccessible(true);
            field.set(intentService, contextService);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "conversationStateService field not found — add it to AIIntentServiceImpl", e);
        }
    }

    @Test
    void recognize_loads_recent_context_when_userId_provided() {
        when(contextService.loadRecent(eq("F001"), eq("U1"), anyInt()))
                .thenReturn(List.of(
                        new ConversationTurn(
                                "帮我分析成本", "RESTAURANT_COST_RIGIDITY",
                                "restaurant_cost_rigidity_analysis", null,
                                Map.of(), 1L)
                ));

        // We expect the 3-arg overload to invoke loadRecent.
        // The intent result itself will be empty (pipelineService is null)
        // but that's fine — we only care that loadRecent was called first.
        try {
            intentService.recognizeIntent("F001", "为什么这么高", "U1");
        } catch (Exception ignored) {
            // Downstream NullPointerException expected since pipelineService is null.
            // loadRecent must have been called before delegation.
        }

        verify(contextService, atLeastOnce()).loadRecent(eq("F001"), eq("U1"), eq(20));
    }

    @Test
    void null_userId_skips_context_load() {
        try {
            intentService.recognizeIntent("F001", "hello", null);
        } catch (Exception ignored) {
            // Same expected downstream NPE
        }

        verify(contextService, never()).loadRecent(anyString(), isNull(), anyInt());
    }

    @Test
    void exception_from_context_service_is_swallowed() {
        when(contextService.loadRecent(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("Redis down"));

        // Must not propagate the Redis exception — fail-open contract
        try {
            intentService.recognizeIntent("F001", "test", "U1");
        } catch (RuntimeException e) {
            // Verify it's not the Redis exception
            if (e.getMessage() != null && e.getMessage().contains("Redis down")) {
                throw new AssertionError(
                        "Redis exception leaked into intent service caller — fail-open violated");
            }
            // Other NPE from null pipelineService is acceptable
        }
    }
}
