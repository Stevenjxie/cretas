package com.cretas.aims.logistics.entity.enums;

/**
 * 门店配送订单的「执行态」—— 排线确认后, 司机实际配送的送达情况。
 * 与规划态 {@link DeliveryOrderStatus}(IMPORTED/PLANNED/CONFIRMED/CANCELLED)是两个独立维度。
 */
public enum DeliveryExecutionStatus {
    /** 待送达(默认)。 */
    PENDING,
    /** 已送达。 */
    DELIVERED,
    /** 异常未送达(带原因 + 处置)。 */
    EXCEPTION
}
