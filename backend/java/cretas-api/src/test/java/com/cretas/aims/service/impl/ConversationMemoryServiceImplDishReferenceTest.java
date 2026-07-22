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

@DisplayName("ConversationMemoryServiceImpl DISH reference resolution")
class ConversationMemoryServiceImplDishReferenceTest {

    @Test
    @DisplayName("DISH slot exists: 那道菜 resolves to dish display value")
    void dishSlotExists_resolvesDishReference() {
        ConversationMemoryRepository repo = mock(ConversationMemoryRepository.class);
        ConversationMemory memory = memoryWithSlots(Map.of(
                "DISH", slot("DISH", "201", "叮咚卤猪蹄", "菜品 叮咚卤猪蹄")
        ));
        when(repo.findBySessionId("sid-dish")).thenReturn(Optional.of(memory));
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(repo, null);

        String resolved = service.resolveReference("sid-dish", "那道菜的销售趋势呢");

        assertThat(resolved).isEqualTo("菜品 叮咚卤猪蹄的销售趋势呢");
    }

    @Test
    @DisplayName("DISH slot exists: 它 resolves to dish display value")
    void dishSlotExists_itResolvesToDish() {
        ConversationMemoryRepository repo = mock(ConversationMemoryRepository.class);
        ConversationMemory memory = memoryWithSlots(Map.of(
                "DISH", slot("DISH", "201", "叮咚卤猪蹄", "菜品 叮咚卤猪蹄")
        ));
        when(repo.findBySessionId("sid-dish-it")).thenReturn(Optional.of(memory));
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(repo, null);

        String resolved = service.resolveReference("sid-dish-it", "它的价格是多少");

        assertThat(resolved).isEqualTo("菜品 叮咚卤猪蹄的价格是多少");
    }

    @Test
    @DisplayName("dish-specific metric resolves ambiguous pronoun after actual gross-margin route")
    void dishMetric_resolvesAmbiguousPronounAfterGrossMarginRoute() {
        ConversationMemoryRepository repo = mock(ConversationMemoryRepository.class);
        ConversationMemory memory = memoryWithSlots(Map.of(
                "DISH", slot("DISH", "201", "叮咚卤猪蹄", "菜品 叮咚卤猪蹄"),
                "STORE", slot("STORE", "101", "人民广场店", "门店 人民广场店")
        ));
        memory.setLastIntentCode("RESTAURANT_OPS_GROSS_MARGIN");
        when(repo.findBySessionId("sid-dish-both")).thenReturn(Optional.of(memory));
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(repo, null);

        String resolved = service.resolveReference(
                "sid-dish-both",
                "它的销量是多少");

        assertThat(resolved)
                .isEqualTo("菜品 叮咚卤猪蹄的销量是多少")
                .doesNotContain("人民广场店");

        String stillAmbiguous = service.resolveReference("sid-dish-both", "它的毛利呢");
        assertThat(stillAmbiguous).isEqualTo("它的毛利呢");
    }

    @Test
    @DisplayName("DISH slot present, STORE slot absent: 那家 still resolves as supplier (STORE/SUPPLIER parity zero-regression)")
    void dishSlotPresent_noStoreSlot_supplierReferenceStillWorks() {
        ConversationMemoryRepository repo = mock(ConversationMemoryRepository.class);
        ConversationMemory memory = memoryWithSlots(Map.of(
                "DISH", slot("DISH", "201", "叮咚卤猪蹄", "菜品 叮咚卤猪蹄"),
                "SUPPLIER", slot("SUPPLIER", "S-1", "上海供应商", "供应商 上海供应商")
        ));
        when(repo.findBySessionId("sid-supplier-noreplace")).thenReturn(Optional.of(memory));
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(repo, null);

        String resolved = service.resolveReference("sid-supplier-noreplace", "那家的资质怎么样");

        // "那家" is in SUPPLIER_REFERENCE_PATTERN but not in DISH_REFERENCE_PATTERN → SUPPLIER resolves
        assertThat(resolved).isEqualTo("供应商 上海供应商的资质怎么样");
    }

    @Test
    @DisplayName("no DISH slot: 那道菜 stays unresolved")
    void noDishSlot_dishReferenceUnresolved() {
        ConversationMemoryRepository repo = mock(ConversationMemoryRepository.class);
        ConversationMemory memory = memoryWithSlots(Map.of(
                "STORE", slot("STORE", "9", "青花椒南方百联店", "门店 青花椒南方百联店")
        ));
        when(repo.findBySessionId("sid-no-dish")).thenReturn(Optional.of(memory));
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(repo, null);

        String resolved = service.resolveReference("sid-no-dish", "那道菜的趋势呢");

        // No DISH slot → DISH pattern skips → text unchanged
        assertThat(resolved).isEqualTo("那道菜的趋势呢");
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
