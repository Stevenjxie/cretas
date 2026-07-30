package com.cretas.aims.entity.enums;

/**
 * 重排触发类型
 *
 * @author Cretas APS V1.0
 * @since 2026-01-21
 */
public enum TriggerType {
    /**
     * 产线故障
     */
    LINE_FAULT,

    /**
     * 紧急订单插入
     */
    URGENT_ORDER_INSERT,

    /**
     * 完成概率过低
     */
    LOW_COMPLETION_PROBABILITY,

    /**
     * 物料短缺
     */
    MATERIAL_SHORTAGE
}
