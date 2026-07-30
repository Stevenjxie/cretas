package com.cretas.aims.entity.enums;

/**
 * 平台角色枚举
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public enum PlatformRole {
    /**
     * 超级管理员
     */
    super_admin("超级管理员", "拥有平台所有权限"),
     /**
      * 系统管理员
      */
    system_admin("系统管理员", "管理系统配置和用户"),
     /**
      * 运营管理员
      */
    operation_admin("运营管理员", "管理工厂和业务运营"),
     /**
      * 审计员
      */
    auditor("审计员", "查看和审计系统数据");
    private final String displayName;
    private final String description;
    PlatformRole(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 获取角色权限
     */
    public String[] getPermissions() {
        switch (this) {
            case super_admin:
                return new String[]{
                    "platform:all",
                    "factory:all",
                    "user:all",
                    "system:all"
                };
            case system_admin:
                return new String[]{
                    "platform:view",
                    "factory:manage",
                    "user:manage",
                    "system:config"
                };
            case operation_admin:
                return new String[]{
                    "factory:view",
                    "user:view"
                };
            case auditor:
                return new String[]{
                    "user:view",
                    "audit:all"
                };
            default:
                return new String[]{"platform:view"};
        }
    }
}
