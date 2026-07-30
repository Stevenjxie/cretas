package com.cretas.aims.entity.enums;

/**
 * 变更类型枚举
 * 用于记录转换率配置等数据的变更历史
 */
public enum ChangeType {
    /**
     * 新建记录
     */
    CREATE,

    /**
     * 更新记录
     */
    UPDATE,

    /**
     * 删除记录
     */
    DELETE,

    /**
     * 激活/启用
     */
    ACTIVATE,

    /**
     * 停用/禁用
     */
    DEACTIVATE
}
