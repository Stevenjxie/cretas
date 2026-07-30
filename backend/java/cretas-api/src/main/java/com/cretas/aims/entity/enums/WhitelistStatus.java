package com.cretas.aims.entity.enums;

/**
 * 白名单状态枚举
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public enum WhitelistStatus {
    /**
     * 活跃状态
     */
    ACTIVE("ACTIVE", "活跃"),

    /**
     * 已禁用
     */
    DISABLED("DISABLED", "已禁用"),

    /**
     * 已过期
     */
    EXPIRED("EXPIRED", "已过期"),

    /**
     * 已达使用上限
     */
    LIMIT_REACHED("LIMIT_REACHED", "已达使用上限"),

    /**
     * 已删除
     */
    DELETED("DELETED", "已删除");

    private final String code;
    private final String description;

    WhitelistStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static WhitelistStatus fromCode(String code) {
        for (WhitelistStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown whitelist status code: " + code);
    }
}
