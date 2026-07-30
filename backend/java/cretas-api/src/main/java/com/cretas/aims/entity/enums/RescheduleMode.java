package com.cretas.aims.entity.enums;

/**
 * 重排模式
 *
 * @author Cretas APS V1.0
 * @since 2026-01-21
 */
public enum RescheduleMode {
    /**
     * 局部重排 - 只重排受影响的任务
     */
    AFFECTED_ONLY,

    /**
     * 全局重排 - 重排所有待处理和进行中的任务
     */
    FULL
}
