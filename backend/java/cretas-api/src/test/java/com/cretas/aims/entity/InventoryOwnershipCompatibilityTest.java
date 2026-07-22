package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Inventory ownership new-row defaults and historical-null compatibility")
class InventoryOwnershipCompatibilityTest {

    @Test
    @DisplayName("new ordinary raw and finished batches default to company owned")
    void newInventory_defaultsToCompanyOwned() {
        assertEquals(InventoryOwnership.COMPANY_OWNED, new MaterialBatch().getOwnership());
        assertEquals(InventoryOwnership.COMPANY_OWNED, new FinishedGoodsBatch().getOwnership());
    }

    @Test
    @DisplayName("persisted historical null can be represented without inference")
    void historicalNull_remainsUnknown() {
        MaterialBatch raw = new MaterialBatch();
        raw.setOwnership(null);
        FinishedGoodsBatch finished = new FinishedGoodsBatch();
        finished.setOwnership(null);

        assertNull(raw.getOwnership());
        assertNull(finished.getOwnership());
    }
}
