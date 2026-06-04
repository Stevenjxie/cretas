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

@DisplayName("QueryPreprocessorServiceImpl STORE resolved references")
class QueryPreprocessorStoreReferenceTest {

    @Test
    @DisplayName("resolved STORE reference is emitted with store id and name")
    void resolvedStoreReferenceIsDetected() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.resolveReference("sid-store", "那家店的客单价呢"))
                .thenReturn("门店 人民广场店的客单价呢");
        QueryPreprocessorServiceImpl service = new QueryPreprocessorServiceImpl(
                null, null, memory, null, new ObjectMapper(), null, null);
        ConversationContext context = ConversationContext.builder()
                .sessionId("sid-store")
                .build();
        context.setSlot(EntitySlot.SlotType.STORE, EntitySlot.store("101", "人民广场店"));

        ReferenceResolutionResult result = service.resolveReferences("那家店的客单价呢", context);

        assertThat(result.isHasResolutions()).isTrue();
        ResolvedEntity entity = result.getResolvedEntities().get("那家店");
        assertThat(entity).isNotNull();
        assertThat(entity.getEntityType()).isEqualTo("store");
        assertThat(entity.getEntityId()).isEqualTo("101");
        assertThat(entity.getEntityName()).isEqualTo("人民广场店");
        assertThat(result.getResolvedText()).isEqualTo("门店 人民广场店的客单价呢");
    }

    @Test
    @DisplayName("bare 这家 can be detected as STORE when STORE slot is present")
    void bareZheJiaCanResolveStore() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.resolveReference("sid-store", "这家的客单价呢"))
                .thenReturn("门店 人民广场店的客单价呢");
        QueryPreprocessorServiceImpl service = new QueryPreprocessorServiceImpl(
                null, null, memory, null, new ObjectMapper(), null, null);
        ConversationContext context = ConversationContext.builder()
                .sessionId("sid-store")
                .build();
        context.setSlot(EntitySlot.SlotType.STORE, EntitySlot.store("101", "人民广场店"));

        ReferenceResolutionResult result = service.resolveReferences("这家的客单价呢", context);

        ResolvedEntity entity = result.getResolvedEntities().get("这家");
        assertThat(entity).isNotNull();
        assertThat(entity.getEntityType()).isEqualTo("store");
        assertThat(entity.getEntityName()).isEqualTo("人民广场店");
    }
}
