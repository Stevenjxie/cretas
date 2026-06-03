package com.cretas.aims.ai.tool.impl.restaurant.gold;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class RestaurantStoreRevenueRankGoldToolAvgTicketTest {
    @Test
    void derivesAvgTicket_revenueOverBillCount() {
        assertEquals(new BigDecimal("125.50"),
            RestaurantStoreRevenueRankGoldTool.deriveAvgTicket("12550.00", "100"));
    }
    @Test
    void nullWhenBillCountZeroOrMissing() {
        assertNull(RestaurantStoreRevenueRankGoldTool.deriveAvgTicket("12550.00", "0"));
        assertNull(RestaurantStoreRevenueRankGoldTool.deriveAvgTicket("12550.00", null));
        assertNull(RestaurantStoreRevenueRankGoldTool.deriveAvgTicket(null, "100"));
    }
}
