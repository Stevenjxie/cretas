package com.cretas.aims.entity.enums;

/**
 * 重排触发优先级
 * 优先级从高到低: CRITICAL > HIGH > MEDIUM > LOW
 *
 * @author Cretas APS V1.0
 * @since 2026-01-21
 */
public enum TriggerPriority {
    /**
     * 紧急 - 需要立即处理
     */
    CRITICAL,

    /**
     * 高优先级 - 尽快处理
     */
    HIGH,

    /**
     * 中优先级 - 正常处理
     */
    MEDIUM,

    /**
     * 低优先级 - 可延后处理
     */
    LOW
}
