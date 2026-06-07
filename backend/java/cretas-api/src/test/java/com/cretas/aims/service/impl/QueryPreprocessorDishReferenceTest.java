package com.cretas.aims.service.impl;

import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.EntitySlot;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.QueryPreprocessorService.ReferenceResolutionResult;
import com.cretas.aims.service.QueryPreprocessorService.ResolvedEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("QueryPreprocessorServiceImpl DISH resolved references")
class QueryPreprocessorDishReferenceTest {

    @Test
    @DisplayName("resolved DISH reference is emitted with dish id and name")
    void resolvedDishReferenceIsDetected() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.resolveReference("sid-dish", "那道菜的销售趋势呢"))
                .thenReturn("菜品 叮咚卤猪蹄的销售趋势呢");
        QueryPreprocessorServiceImpl service = new QueryPreprocessorServiceImpl(
                null, null, memory, null, new ObjectMapper(), null, null);
        ConversationContext context = ConversationContext.builder()
                .sessionId("sid-dish")
                .build();
        context.setSlot(EntitySlot.SlotType.DISH, EntitySlot.dish("201", "叮咚卤猪蹄"));

        ReferenceResolutionResult result = service.resolveReferences("那道菜的销售趋势呢", context);

        assertThat(result.isHasResolutions()).isTrue();
        ResolvedEntity entity = result.getResolvedEntities().get("那道菜");
        assertThat(entity).isNotNull();
        assertThat(entity.getEntityType()).isEqualTo("dish");
        assertThat(entity.getEntityId()).isEqualTo("201");
        assertThat(entity.getEntityName()).isEqualTo("叮咚卤猪蹄");
        assertThat(result.getResolvedText()).isEqualTo("菜品 叮咚卤猪蹄的销售趋势呢");
    }

    @Test
    @DisplayName("它 can be resolved as DISH when DISH slot is present")
    void itCanResolveDish() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.resolveReference("sid-dish", "它的价格是多少"))
                .thenReturn("菜品 叮咚卤猪蹄的价格是多少");
        QueryPreprocessorServiceImpl service = new QueryPreprocessorServiceImpl(
                null, null, memory, null, new ObjectMapper(), null, null);
        ConversationContext context = ConversationContext.builder()
                .sessionId("sid-dish")
                .build();
        context.setSlot(EntitySlot.SlotType.DISH, EntitySlot.dish("201", "叮咚卤猪蹄"));

        ReferenceResolutionResult result = service.resolveReferences("它的价格是多少", context);

        ResolvedEntity entity = result.getResolvedEntities().get("它");
        assertThat(entity).isNotNull();
        assertThat(entity.getEntityType()).isEqualTo("dish");
        assertThat(entity.getEntityName()).isEqualTo("叮咚卤猪蹄");
    }
}
