package com.cretas.aims.entity.enums;

/**
 * 生产计划类型枚举
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-25
 */
public enum ProductionPlanType {
    /**
     * 未来计划 - 预先规划的生产计划，可能没有具体批次
     */
    FUTURE,

    /**
     * 基于库存 - 根据当前可用库存批次创建的生产计划
     */
    FROM_INVENTORY
}
