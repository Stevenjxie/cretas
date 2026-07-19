package com.cretas.aims.ai.capability;

import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.FactoryUserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FactoryCapabilityPackSelectorTest {
    private final FactoryCapabilityPackSelector selector =
            new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry());

    @Test
    void selectsOnlyExactRoleAndFactoryBusinessType() {
        assertThat(selector.select(FactoryUserRole.operator, FactoryType.FACTORY))
                .get().extracting(FactoryCapabilityPack::packId)
                .isEqualTo("factory.operator");
        assertThat(selector.select(
                FactoryUserRole.warehouse_worker, FactoryType.CENTRAL_KITCHEN))
                .get().extracting(FactoryCapabilityPack::packId)
                .isEqualTo("factory.warehouse");
        assertThat(selector.select(FactoryUserRole.restaurant_manager, FactoryType.RESTAURANT))
                .isEmpty();
        assertThat(selector.select(FactoryUserRole.viewer, FactoryType.FACTORY)).isEmpty();
    }

    @Test
    void queryMatchIsBoundedAndReturnsExplicitNoMatch() {
        assertThat(selector.match(
                FactoryUserRole.quality_inspector, FactoryType.FACTORY, "查看待检关键项"))
                .get().extracting(FactoryCapabilityPack::packId)
                .isEqualTo("factory.quality");
        assertThat(selector.match(
                FactoryUserRole.quality_inspector, FactoryType.FACTORY, "查看采购付款"))
                .isEmpty();
        assertThatThrownBy(() -> selector.match(
                FactoryUserRole.operator, FactoryType.FACTORY, "x".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> selector.match(
                FactoryUserRole.operator, FactoryType.FACTORY, "批次\n任务"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
