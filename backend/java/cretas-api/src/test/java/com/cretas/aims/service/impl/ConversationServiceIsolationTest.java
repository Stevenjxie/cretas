package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.conversation.ConversationSession.CandidateIntent;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.ExpressionLearningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationServiceIsolationTest {

    private static final String FACTORY_ID = "F-OWNER";
    private static final Long USER_ID = 42L;
    private static final String SESSION_ID = "session-foreign";

    private ConversationSessionRepository sessionRepository;
    private ExpressionLearningService learningService;
    private ConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ConversationSessionRepository.class);
        learningService = mock(ExpressionLearningService.class);
        service = new ConversationServiceImpl(
                sessionRepository,
                mock(AIIntentConfigRepository.class),
                learningService,
                mock(DashScopeClient.class));
    }

    @Test
    void foreignSessionMissDoesNotSaveOrLearnAcrossAllMutationPaths() {
        when(sessionRepository.findBySessionIdAndFactoryIdAndUserId(
                SESSION_ID, FACTORY_ID, USER_ID)).thenReturn(Optional.empty());

        ConversationService.ConversationResponse reply = service.continueConversation(
                FACTORY_ID, USER_ID, SESSION_ID, "reply");

        assertThat(reply.getStatus()).isEqualTo(ConversationSession.SessionStatus.CANCELLED);
        assertThat(reply.getMessage()).isEqualTo("会话不存在或已过期");
        assertThat(service.endConversation(FACTORY_ID, USER_ID, SESSION_ID, "QUERY")).isFalse();
        assertThat(service.cancelConversation(FACTORY_ID, USER_ID, SESSION_ID)).isFalse();
        assertThat(service.getSession(FACTORY_ID, USER_ID, SESSION_ID)).isEmpty();
        verify(sessionRepository, never()).save(any());
        verifyNoInteractions(learningService);
    }

    @Test
    void statisticsUseOnlyFactoryScopedRepositoryQueries() {
        when(sessionRepository.getSuccessRate(eq(FACTORY_ID), any(LocalDateTime.class)))
                .thenReturn(Collections.singletonList(new Object[]{2L, 1L, 1L, 0L, 4L}));
        when(sessionRepository.getAverageRoundsForCompleted(
                eq(FACTORY_ID), any(LocalDateTime.class))).thenReturn(2.5);
        when(sessionRepository.countActiveByFactoryId(FACTORY_ID)).thenReturn(3L);

        ConversationService.ConversationStatistics stats = service.getStatistics(FACTORY_ID, 7);

        assertThat(stats.getCompletedCount()).isEqualTo(2);
        assertThat(stats.getTimeoutCount()).isEqualTo(1);
        assertThat(stats.getCancelledCount()).isEqualTo(1);
        assertThat(stats.getTotalSessions()).isEqualTo(4);
        assertThat(stats.getSuccessRate()).isEqualTo(0.5);
        assertThat(stats.getAverageRounds()).isEqualTo(2.5);
        assertThat(stats.getActiveSessions()).isEqualTo(3);
        verify(sessionRepository).getSuccessRate(eq(FACTORY_ID), any(LocalDateTime.class));
        verify(sessionRepository).getAverageRoundsForCompleted(
                eq(FACTORY_ID), any(LocalDateTime.class));
        verify(sessionRepository).countActiveByFactoryId(FACTORY_ID);
        verify(sessionRepository, never()).findByStatus(any());
    }

    @Test
    void ownedSessionCanReplyConfirmAndCancelAfterExactIdentityLookup() {
        ConversationSession replySession = activeSession("session-owned-reply");
        replySession.setCandidates(List.of(CandidateIntent.builder()
                .intentCode("QUERY")
                .intentName("Query")
                .confidence(0.8)
                .build()));
        ConversationSession confirmSession = activeSession("session-owned-confirm");
        ConversationSession cancelSession = activeSession("session-owned-cancel");

        when(sessionRepository.findBySessionIdAndFactoryIdAndUserId(
                replySession.getSessionId(), FACTORY_ID, USER_ID)).thenReturn(Optional.of(replySession));
        when(sessionRepository.findBySessionIdAndFactoryIdAndUserId(
                confirmSession.getSessionId(), FACTORY_ID, USER_ID)).thenReturn(Optional.of(confirmSession));
        when(sessionRepository.findBySessionIdAndFactoryIdAndUserId(
                cancelSession.getSessionId(), FACTORY_ID, USER_ID)).thenReturn(Optional.of(cancelSession));

        ConversationService.ConversationResponse reply = service.continueConversation(
                FACTORY_ID, USER_ID, replySession.getSessionId(), "1");

        assertThat(reply.isCompleted()).isTrue();
        assertThat(reply.getIntentCode()).isEqualTo("QUERY");
        assertThat(service.endConversation(
                FACTORY_ID, USER_ID, confirmSession.getSessionId(), "CONFIRMED_QUERY")).isTrue();
        assertThat(service.cancelConversation(
                FACTORY_ID, USER_ID, cancelSession.getSessionId())).isTrue();
        assertThat(cancelSession.getStatus()).isEqualTo(ConversationSession.SessionStatus.CANCELLED);

        verify(sessionRepository).save(replySession);
        verify(sessionRepository).save(confirmSession);
        verify(sessionRepository).save(cancelSession);
        verify(learningService).learnExpression(
                FACTORY_ID, "QUERY", "owned input", 0.95,
                LearnedExpression.SourceType.USER_FEEDBACK);
        verify(learningService).learnExpression(
                FACTORY_ID, "CONFIRMED_QUERY", "owned input", 1.0,
                LearnedExpression.SourceType.USER_FEEDBACK);
    }

    private ConversationSession activeSession(String sessionId) {
        LocalDateTime now = LocalDateTime.now();
        return ConversationSession.builder()
                .sessionId(sessionId)
                .factoryId(FACTORY_ID)
                .userId(USER_ID)
                .originalInput("owned input")
                .currentRound(1)
                .maxRounds(5)
                .status(ConversationSession.SessionStatus.ACTIVE)
                .sessionMode(ConversationSession.SessionMode.INTENT_RECOGNITION)
                .messagesJson("[]")
                .candidatesJson("[]")
                .timeoutMinutes(10)
                .createdAt(now)
                .lastActiveAt(now)
                .build();
    }
}
