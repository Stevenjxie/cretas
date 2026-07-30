package com.cretas.aims.service;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;

import java.util.List;
import java.util.Set;

/**
 * 权限服务接口
 *
 * 提供用户权限检查、权限列表获取等功能
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-12-27
 */
public interface PermissionService {

    /**
     * 检查用户是否拥有指定权限
     *
     * @param user 用户对象
     * @param permissionCode 权限代码 (格式: module:action, 如 production:read)
     * @return 是否拥有权限
     */
    boolean hasPermission(User user, String permissionCode);

    /**
     * 检查用户是否拥有多个权限中的任意一个
     *
     * @param user 用户对象
     * @param permissionCodes 权限代码列表
     * @return 是否拥有任一权限
     */
    boolean hasAnyPermission(User user, String... permissionCodes);

    /**
     * 检查用户是否拥有所有指定权限
     *
     * @param user 用户对象
     * @param permissionCodes 权限代码列表
     * @return 是否拥有所有权限
     */
    boolean hasAllPermissions(User user, String... permissionCodes);

    /**
     * 获取用户所有权限列表
     *
     * @param user 用户对象
     * @return 权限代码集合
     */
    Set<String> getUserPermissions(User user);

    /**
     * 获取用户可访问的模块列表
     *
     * @param user 用户对象
     * @return 模块名称列表
     */
    List<String> getAccessibleModules(User user);

    /**
     * 检查用户是否可以访问指定模块
     *
     * @param user 用户对象
     * @param module 模块名称
     * @return 是否可访问
     */
    boolean canAccessModule(User user, String module);

    /**
     * 检查用户是否可以管理目标用户
     *
     * @param manager 管理者
     * @param target 目标用户
     * @return 是否可管理
     */
    boolean canManageUser(User manager, User target);

    /**
     * 检查用户是否可以访问指定工厂的数据
     *
     * @param user 用户对象
     * @param factoryId 工厂ID
     * @return 是否可访问
     */
    boolean canAccessFactory(User user, String factoryId);

    /**
     * 匹配权限 (支持通配符)
     *
     * @param pattern 权限模式 (如 production:*, *:read)
     * @param permission 实际权限
     * @return 是否匹配
     */
    boolean matchPermission(String pattern, String permission);

    /**
     * 获取角色的默认权限列表
     *
     * @param role 角色枚举
     * @return 权限代码集合
     */
    Set<String> getRolePermissions(FactoryUserRole role);
}
