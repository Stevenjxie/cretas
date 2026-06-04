package com.cretas.aims.dto.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EntitySlot STORE factory")
class EntitySlotStoreTest {

    @Test
    @DisplayName("store factory sets id, name, display value and confidence")
    void storeFactorySetsExpectedFields() {
        EntitySlot slot = EntitySlot.store("101", "人民广场店");

        assertThat(slot.getType()).isEqualTo(EntitySlot.SlotType.STORE);
        assertThat(slot.getId()).isEqualTo("101");
        assertThat(slot.getName()).isEqualTo("人民广场店");
        assertThat(slot.getDisplayValue()).isEqualTo("门店 人民广场店");
        assertThat(slot.getMentionCount()).isEqualTo(1);
        assertThat(slot.getConfidence()).isEqualTo(1.0);
        assertThat(slot.getMentionedAt()).isNotNull();
    }
}
