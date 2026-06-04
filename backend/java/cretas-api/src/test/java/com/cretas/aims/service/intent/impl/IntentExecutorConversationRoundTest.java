package com.cretas.aims.service.intent.impl;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.execution.IntentExecutionOrchestrator;
import com.cretas.aims.service.execution.MultiIntentExecutionService;
import com.cretas.aims.service.execution.SseStreamingService;
import com.cretas.aims.service.impl.IntentExecutorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for X1 Part A — populate {@code conversationRound} on the execute response
 * ({@link IntentExecutorServiceImpl}).
 *
 * <p>Scene (prod QA): {@code conversationRound} was null in EVERY response — the DTO field existed
 * but was never populated. The facade now fills it from the persisted conversation message count at
 * the single chokepoint that wraps every orchestrator return path. Round is 1-based, derived as
 * {@code (messageCount + 1) / 2} (clamped to ≥ 1 once a session exists). Fail-open: round stays null
 * if there is no session, no persisted context, or the memory store throws.
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
@DisplayName("X1 Part A — conversationRound population")
class IntentExecutorConversationRoundTest {

    private IntentExecutionOrchestrator orchestrator;
    private ConversationMemoryService conversationMemoryService;
    private IntentExecutorServiceImpl facade;

    @BeforeEach
    void setUp() {
        orchestrator = mock(IntentExecutionOrchestrator.class);
        SseStreamingService sseStreamingService = mock(SseStreamingService.class);
        MultiIntentExecutionService multiIntentExecutionService = mock(MultiIntentExecutionService.class);
        conversationMemoryService = mock(ConversationMemoryService.class);
        // @RequiredArgsConstructor field order: orchestrator, sse, multiIntent, memory.
        facade = new IntentExecutorServiceImpl(
                orchestrator, sseStreamingService, multiIntentExecutionService, conversationMemoryService);
    }

    private IntentExecuteRequest requestWithSession(String sessionId) {
        IntentExecuteRequest req = new IntentExecuteRequest();
        req.setUserInput("上个月呢");
        req.setSessionId(sessionId);
        return req;
    }

    private ConversationContext contextWithMessageCount(int messageCount) {
        return ConversationContext.builder()
                .sessionId("sess-1")
                .factoryId("RES_3101_009")
                .userId(9L)
                .messageCount(messageCount)
                .build();
    }

    @Test
    @DisplayName("session with persisted messages -> conversationRound populated (round 2 from msgCount 4)")
    void populatesRoundFromMessageCount() {
        when(orchestrator.execute(anyString(), any(), any(), anyString()))
                .thenReturn(IntentExecuteResponse.builder().status("COMPLETED").build());
        // After turn 2's user+assistant persist, messageCount = 4 -> round (4+1)/2 = 2.
        when(conversationMemoryService.getContext("sess-1"))
                .thenReturn(contextWithMessageCount(4));

        IntentExecuteResponse response = facade.execute(
                "RES_3101_009", requestWithSession("sess-1"), 9L, "restaurant_admin");

        assertThat(response.getConversationRound()).isEqualTo(2);
    }

    @Test
    @DisplayName("session exists but messageCount 0 -> round clamped to 1 (never 0)")
    void clampsToOneForEmptyMessageCount() {
        when(orchestrator.execute(anyString(), any(), any(), anyString()))
                .thenReturn(IntentExecuteResponse.builder().status("NEED_CLARIFICATION").build());
        when(conversationMemoryService.getContext("sess-1"))
                .thenReturn(contextWithMessageCount(0));

        IntentExecuteResponse response = facade.execute(
                "RES_3101_009", requestWithSession("sess-1"), 9L, "restaurant_admin");

        assertThat(response.getConversationRound()).isEqualTo(1);
    }

    @Test
    @DisplayName("no session (standalone query) -> conversationRound stays null")
    void noSessionLeavesRoundNull() {
        when(orchestrator.execute(anyString(), any(), any(), anyString()))
                .thenReturn(IntentExecuteResponse.builder().status("COMPLETED").build());

        IntentExecuteResponse response = facade.execute(
                "RES_3101_009", requestWithSession(null), 9L, "restaurant_admin");

        assertThat(response.getConversationRound()).isNull();
    }

    @Test
    @DisplayName("session present but no persisted context -> round stays null (fail-open)")
    void missingContextLeavesRoundNull() {
        when(orchestrator.execute(anyString(), any(), any(), anyString()))
                .thenReturn(IntentExecuteResponse.builder().status("COMPLETED").build());
        when(conversationMemoryService.getContext("sess-1")).thenReturn(null);

        IntentExecuteResponse response = facade.execute(
                "RES_3101_009", requestWithSession("sess-1"), 9L, "restaurant_admin");

        assertThat(response.getConversationRound()).isNull();
    }

    @Test
    @DisplayName("memory store throws -> fail-open, round null, no crash")
    void memoryThrowsFailsOpen() {
        when(orchestrator.execute(anyString(), any(), any(), anyString()))
                .thenReturn(IntentExecuteResponse.builder().status("COMPLETED").build());
        when(conversationMemoryService.getContext("sess-1"))
                .thenThrow(new RuntimeException("redis down"));

        IntentExecuteResponse response = facade.execute(
                "RES_3101_009", requestWithSession("sess-1"), 9L, "restaurant_admin");

        assertThat(response.getConversationRound()).isNull();
    }

    @Test
    @DisplayName("round already set upstream -> preserved, not overridden")
    void preservesUpstreamRound() {
        when(orchestrator.execute(anyString(), any(), any(), anyString()))
                .thenReturn(IntentExecuteResponse.builder().status("COMPLETED").conversationRound(7).build());
        when(conversationMemoryService.getContext("sess-1"))
                .thenReturn(contextWithMessageCount(4));

        IntentExecuteResponse response = facade.execute(
                "RES_3101_009", requestWithSession("sess-1"), 9L, "restaurant_admin");

        assertThat(response.getConversationRound()).isEqualTo(7);
    }

    @Test
    @DisplayName("preview path also populates conversationRound")
    void previewPopulatesRound() {
        when(orchestrator.execute(anyString(), any(), any(), anyString()))
                .thenReturn(IntentExecuteResponse.builder().status("COMPLETED").build());
        when(conversationMemoryService.getContext("sess-1"))
                .thenReturn(contextWithMessageCount(2));

        IntentExecuteResponse response = facade.preview(
                "RES_3101_009", requestWithSession("sess-1"), 9L, "restaurant_admin");

        assertThat(response.getConversationRound()).isEqualTo(1);
    }
}
