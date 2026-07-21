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

@DisplayName("ConversationMemoryServiceImpl STORE reference resolution")
class ConversationMemoryServiceImplStoreReferenceTest {

    @Test
    @DisplayName("STORE slot exists: 那家店 resolves to store display value before supplier")
    void storeSlotExists_resolvesStoreReference() {
        ConversationMemoryRepository repo = mock(ConversationMemoryRepository.class);
        ConversationMemory memory = memoryWithSlots(Map.of(
                "STORE", slot("STORE", "101", "人民广场店", "门店 人民广场店"),
                "SUPPLIER", slot("SUPPLIER", "S-1", "上海供应商", "供应商 上海供应商")
        ));
        when(repo.findBySessionId("sid-store")).thenReturn(Optional.of(memory));
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(repo, null);

        String resolved = service.resolveReference("sid-store", "那家店的客单价呢");

        assertThat(resolved).isEqualTo("门店 人民广场店的客单价呢");
    }

    @Test
    @DisplayName("STORE slot without DISH slot: 那它 resolves to the latest store")
    void storeSlotWithoutDish_resolvesAmbiguousPronounToStore() {
        ConversationMemoryRepository repo = mock(ConversationMemoryRepository.class);
        ConversationMemory memory = memoryWithSlots(Map.of(
                "STORE", slot("STORE", "101", "人民广场店", "门店 人民广场店")
        ));
        when(repo.findBySessionId("sid-store-pronoun")).thenReturn(Optional.of(memory));
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(repo, null);

        String resolved = service.resolveReference(
                "sid-store-pronoun",
                "那它的毛利率也是第一吗？");

        assertThat(resolved).isEqualTo("门店 人民广场店的毛利率也是第一吗？");
    }

    @Test
    @DisplayName("no STORE slot: 那家 still resolves as supplier, preserving factory CRM behavior")
    void noStoreSlot_supplierReferenceStillWorks() {
        ConversationMemoryRepository repo = mock(ConversationMemoryRepository.class);
        ConversationMemory memory = memoryWithSlots(Map.of(
                "SUPPLIER", slot("SUPPLIER", "S-1", "上海供应商", "供应商 上海供应商")
        ));
        when(repo.findBySessionId("sid-supplier")).thenReturn(Optional.of(memory));
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(repo, null);

        String resolved = service.resolveReference("sid-supplier", "那家的资质怎么样");

        assertThat(resolved).isEqualTo("供应商 上海供应商的资质怎么样");
    }

    private static ConversationMemory memoryWithSlots(Map<String, ConversationMemory.EntitySlotData> slots) {
        return ConversationMemory.builder()
                .factoryId("RES_3101_009")
                .userId(9L)
                .sessionId("sid")
                .entitySlots(new HashMap<>(slots))
                .build();
    }

    private static ConversationMemory.EntitySlotData slot(
            String type, String id, String name, String displayValue) {
        return ConversationMemory.EntitySlotData.builder()
                .type(type)
                .id(id)
                .name(name)
                .displayValue(displayValue)
                .mentionCount(1)
                .build();
    }
}
