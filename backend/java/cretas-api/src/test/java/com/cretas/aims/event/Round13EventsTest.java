package com.cretas.aims.event;

import com.cretas.aims.engine.TriggerChainExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Round13EventsTest {

    @Test
    @SuppressWarnings("unchecked")
    void handledEvents_containsInvoiceRequestedEvent() throws Exception {
        Field f = TriggerChainExecutor.class.getDeclaredField("HANDLED_EVENTS");
        f.setAccessible(true);
        Set<String> events = (Set<String>) f.get(null);
        assertTrue(events.contains("InvoiceRequestedEvent"));
    }

    @Test
    void invoiceRequestedEvent_constructor() {
        InvoiceRequestedEvent e = new InvoiceRequestedEvent(
                this, "F1", "inv-1", "so-1", new BigDecimal("10000"));
        assertEquals("F1", e.getFactoryId());
        assertEquals("inv-1", e.getInvoiceId());
        assertEquals("so-1", e.getSalesOrderId());
        assertNotNull(e.getCreatedAt());
    }
}
