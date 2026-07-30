package com.cretas.aims.entity.enums;

/**
 * 用户类型枚举
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public enum UserType {
    /** 平台用户 */
    PLATFORM("平台用户"),
    /** 工厂用户 */
    FACTORY("工厂用户");

    private final String displayName;

    UserType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
