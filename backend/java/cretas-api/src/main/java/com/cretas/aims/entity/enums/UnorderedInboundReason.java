package com.cretas.aims.entity.enums;

/** Business reason for an inbound request that has no purchase or sales order. */
public enum UnorderedInboundReason {
    /** Customer-owned material delivered before a formal sales order exists. */
    CUSTOMER_MATERIAL,
    /** Material gifted to the factory; inventory belongs to the company. */
    GIFT,
    /** Other explicitly coordinated, non-order inbound; inventory belongs to the company. */
    OTHER
}
