package com.cretas.aims.entity.enums;

/**
 * How a customer-supplied toll-processing sales order obtains customer-owned stock.
 */
public enum CustomerStockFulfillmentMode {
    /** The formal sales order owns the later customer-supplied receiving requirements. */
    ORDER_DRIVEN,

    /** Allocate finished goods that were produced for the customer before the formal order existed. */
    PRESTOCKED
}
