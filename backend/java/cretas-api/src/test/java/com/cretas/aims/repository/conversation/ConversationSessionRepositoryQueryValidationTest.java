package com.cretas.aims.repository.conversation;

import com.cretas.aims.entity.conversation.ConversationSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup and tenant-isolation gate for conversation session queries. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ConversationSessionRepositoryQueryValidationTest {

    @Autowired
    ConversationSessionRepository repository;

    @Test
    void exactSessionLookupAndStatisticsAreIsolatedByFactoryAndUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String factoryA = "F-CONV-A-" + suffix;
        String factoryB = "F-CONV-B-" + suffix;
        LocalDateTime now = LocalDateTime.now();

        ConversationSession owned = session(
                "conv-a1-" + suffix, factoryA, 101L,
                ConversationSession.SessionStatus.ACTIVE, 1, now);
        ConversationSession sameFactoryOtherUser = session(
                "conv-a2-" + suffix, factoryA, 202L,
                ConversationSession.SessionStatus.ACTIVE, 1, now);
        ConversationSession completedA = session(
                "conv-a3-" + suffix, factoryA, 101L,
                ConversationSession.SessionStatus.COMPLETED, 4, now);
        ConversationSession activeB = session(
                "conv-b1-" + suffix, factoryB, 101L,
                ConversationSession.SessionStatus.ACTIVE, 1, now);
        ConversationSession completedB = session(
                "conv-b2-" + suffix, factoryB, 101L,
                ConversationSession.SessionStatus.COMPLETED, 9, now);
        repository.saveAllAndFlush(List.of(
                owned, sameFactoryOtherUser, completedA, activeB, completedB));

        assertThat(repository.findBySessionIdAndFactoryIdAndUserId(
                owned.getSessionId(), factoryA, 101L)).isPresent();
        assertThat(repository.findBySessionIdAndFactoryIdAndUserId(
                owned.getSessionId(), factoryA, 202L)).isEmpty();
        assertThat(repository.findBySessionIdAndFactoryIdAndUserId(
                owned.getSessionId(), factoryB, 101L)).isEmpty();

        Object[] factoryARate = repository.getSuccessRate(
                factoryA, now.minusMinutes(1)).get(0);
        assertThat(((Number) factoryARate[0]).longValue()).isEqualTo(1);
        assertThat(((Number) factoryARate[4]).longValue()).isEqualTo(3);
        assertThat(repository.getAverageRoundsForCompleted(factoryA, now.minusMinutes(1)))
                .isEqualTo(4.0);
        assertThat(repository.countActiveByFactoryId(factoryA)).isEqualTo(2);

        Object[] factoryBRate = repository.getSuccessRate(
                factoryB, now.minusMinutes(1)).get(0);
        assertThat(((Number) factoryBRate[0]).longValue()).isEqualTo(1);
        assertThat(((Number) factoryBRate[4]).longValue()).isEqualTo(2);
        assertThat(repository.getAverageRoundsForCompleted(factoryB, now.minusMinutes(1)))
                .isEqualTo(9.0);
        assertThat(repository.countActiveByFactoryId(factoryB)).isEqualTo(1);
    }

    private ConversationSession session(
            String sessionId,
            String factoryId,
            Long userId,
            ConversationSession.SessionStatus status,
            int currentRound,
            LocalDateTime createdAt) {
        return ConversationSession.builder()
                .sessionId(sessionId)
                .factoryId(factoryId)
                .userId(userId)
                .originalInput("test")
                .currentRound(currentRound)
                .maxRounds(10)
                .status(status)
                .sessionMode(ConversationSession.SessionMode.INTENT_RECOGNITION)
                .messagesJson("[]")
                .candidatesJson("[]")
                .timeoutMinutes(10)
                .createdAt(createdAt)
                .lastActiveAt(createdAt)
                .build();
    }
}
