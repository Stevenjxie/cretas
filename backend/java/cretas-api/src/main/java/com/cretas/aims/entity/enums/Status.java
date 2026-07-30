package com.cretas.aims.entity.enums;

/**
 * 状态枚举
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public enum Status {
    /**
     * 激活
     */
    active("激活"),
     /**
      * 停用
      */
    inactive("停用"),
     /**
      * 锁定
      */
    locked("锁定"),
     /**
      * 待激活
      */
    pending("待激活");
    private final String displayName;
    Status(String displayName) {
        this.displayName = displayName;
    }
    public String getDisplayName() {
        return displayName;
}
}
