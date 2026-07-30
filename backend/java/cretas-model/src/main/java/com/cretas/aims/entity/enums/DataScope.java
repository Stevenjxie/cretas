package com.cretas.aims.entity.enums;

/**
 * 数据权限范围 (RBAC 第 2 维)
 *
 * <p>HJ RBAC 4 维之一 (功能 / 数据 / 打印 / 第三方). Cretas Sprint 5 Track G 实施数据维 MVP.
 * 行级数据过滤 (row-level filter): 同样的功能权限下, 不同 scope 看到的数据范围不同.
 *
 * <h3>5 级 scope (per Round 12 §D.1 X4 finding)</h3>
 * <ul>
 *     <li>{@link #ALL}              — 整个工厂 (默认, 向后兼容)</li>
 *     <li>{@link #DEPT_AND_BELOW}   — 本部门 + 下属部门 (基于 User.department + reportsTo 链)</li>
 *     <li>{@link #SELF_AND_BELOW}   — 自己 + 下属 (基于 User.reportsTo 反向链)</li>
 *     <li>{@link #SELF}             — 仅自己创建的数据 (created_by = currentUserId)</li>
 *     <li>{@link #CUSTOM}           — 自定义 (CUSTOM dialog 选 customer/department list, MVP defer)</li>
 * </ul>
 *
 * <h3>典型场景</h3>
 * 销售员 (sales_manager 角色) with {@link #SELF} scope, login 后 list 只看自己创建的 sales orders.
 * 部门经理 with {@link #DEPT_AND_BELOW} scope, 看自己部门 + 下属部门的所有数据.
 *
 * <h3>实施方式</h3>
 * Repository / Service 方法标 {@code @DataScope("created_by")}, {@link com.cretas.aims.aspect.DataScopeAspect}
 * 自动 resolve 当前用户 scope + 注入 ThreadLocal {@link com.cretas.aims.security.DataScopeContext},
 * Service 实现读 context 决定 WHERE 子句.
 *
 * @author Cretas Team (Sprint 5 Track G)
 * @since 2026-05-19
 */
public enum DataScope {

    /**
     * 全工厂 — 默认, 向后兼容. 当前所有 endpoint 行为 ≡ ALL.
     * 通常给 factory_super_admin / platform_admin / hr_admin 等管理层.
     */
    ALL,

    /**
     * 本部门 + 下属部门. 基于 User.department + reportsTo 链向下递归.
     * 通常给 部门经理 (sales_manager / quality_manager / procurement_manager 等).
     */
    DEPT_AND_BELOW,

    /**
     * 自己 + 下属用户创建的数据. 基于 User.reportsTo 反向链.
     * 通常给 车间主任 / 组长 (workshop_supervisor / team_leader / group_leader).
     */
    SELF_AND_BELOW,

    /**
     * 仅自己创建的数据 (created_by = currentUserId).
     * 通常给 一线员工 / 销售员 (operator / warehouse_worker / sales 角色 with limited scope).
     */
    SELF,

    /**
     * 自定义 — 通过 CUSTOM dialog 配置具体 customer/department/region list.
     * MVP defer Sprint 6, 当前等价 ALL (fallback).
     */
    CUSTOM
}
