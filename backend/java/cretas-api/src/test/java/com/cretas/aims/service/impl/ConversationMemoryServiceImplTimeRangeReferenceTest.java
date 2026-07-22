package com.cretas.aims.service.impl;

import com.cretas.aims.entity.conversation.ConversationMemory;
import com.cretas.aims.repository.conversation.ConversationMemoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ConversationMemoryServiceImpl TIME_RANGE reference resolution")
class ConversationMemoryServiceImplTimeRangeReferenceTest {

    @Test
    @DisplayName("刚才比较的两个日期恢复为会话中的绝对日期")
    void comparisonDateReferenceResolvesToAbsoluteRange() {
        ConversationMemoryRepository repo = mock(ConversationMemoryRepository.class);
        ConversationMemory.EntitySlotData range = ConversationMemory.EntitySlotData.builder()
                .type("TIME_RANGE")
                .id("2026-07-20 与 2026-07-19")
                .name("2026-07-20 与 2026-07-19")
                .displayValue("2026-07-20 与 2026-07-19")
                .mentionCount(1)
                .build();
        ConversationMemory memory = ConversationMemory.builder()
                .factoryId("DEMO_REST")
                .userId(9L)
                .sessionId("sid-date")
                .entitySlots(new HashMap<>(Map.of("TIME_RANGE", range)))
                .build();
        when(repo.findBySessionId("sid-date")).thenReturn(Optional.of(memory));
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(repo, null);

        String resolved = service.resolveReference(
                "sid-date",
                "那毛利呢？请沿用刚才比较的两个日期。");

        assertThat(resolved)
                .contains("2026-07-20", "2026-07-19", "毛利")
                .doesNotContain("刚才比较的两个日期");
    }
}