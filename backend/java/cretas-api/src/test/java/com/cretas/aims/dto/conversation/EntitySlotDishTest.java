package com.cretas.aims.dto.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EntitySlot DISH factory")
class EntitySlotDishTest {

    @Test
    @DisplayName("dish factory sets id, name, display value and confidence")
    void dishFactorySetsExpectedFields() {
        EntitySlot slot = EntitySlot.dish("101", "叮咚好食光卤猪蹄");

        assertThat(slot.getType()).isEqualTo(EntitySlot.SlotType.DISH);
        assertThat(slot.getId()).isEqualTo("101");
        assertThat(slot.getName()).isEqualTo("叮咚好食光卤猪蹄");
        assertThat(slot.getDisplayValue()).isEqualTo("菜品 叮咚好食光卤猪蹄");
        assertThat(slot.getMentionCount()).isEqualTo(1);
        assertThat(slot.getConfidence()).isEqualTo(1.0);
        assertThat(slot.getMentionedAt()).isNotNull();
    }
}
