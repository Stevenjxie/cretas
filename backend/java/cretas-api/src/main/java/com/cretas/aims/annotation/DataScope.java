package com.cretas.aims.annotation;

import java.lang.annotation.*;

/**
 * 数据权限注解 (RBAC 第 2 维, Sprint 5 Track G)
 *
 * <p>标注 Service 方法 → AOP 自动 resolve 当前用户的 {@link com.cretas.aims.entity.enums.DataScope}
 * 并 setup ThreadLocal {@link com.cretas.aims.security.DataScopeContext}. Service 实现读 context 决定
 * WHERE 子句过滤 (e.g. {@code AND created_by = :userId}, {@code AND department IN :deptList}).
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 销售单 list, "自己" scope → WHERE created_by = currentUserId
 * @DataScope(column = "created_by")
 * public PageResponse<SalesOrder> getSalesOrders(String factoryId, ...) {
 *     DataScopeContext ctx = DataScopeContext.current();
 *     if (ctx != null && ctx.getScope() == DataScope.SELF) {
 *         return salesOrderRepository.findByFactoryIdAndCreatedByOrderByCreatedAtDesc(
 *                 factoryId, ctx.getUserId(), pageRequest);
 *     }
 *     // 默认: ALL scope, 走原 path
 *     return salesOrderRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageRequest);
 * }
 * }</pre>
 *
 * <h3>POC scope (Sprint 5)</h3>
 * 仅 setup ThreadLocal context, Service 实现自己 branch 读取. **不自动注入 WHERE 子句** (那需要
 * Specification/QueryDSL 大改, defer Sprint 6 sweep).
 *
 * <p>Sprint 6 计划: 实现 Spring Data Specification 自动 inject — 任何 JpaSpecificationExecutor
 * Repository 透明加 scope predicate. 当前 MVP 仅 POC SalesOrder.
 *
 * @author Cretas Team (Sprint 5 Track G)
 * @since 2026-05-19
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataScope {

    /**
     * 过滤列名 (snake_case DB column or camelCase entity field) — 简写形式.
     *
     * <p>等价于 {@link #column()}, 支持 {@code @DataScope("created_by")} 简写.
     * 若同时设 {@link #value()} 和 {@link #column()}, {@link #column()} 优先.
     */
    String value() default "";

    /**
     * 过滤列名 (显式形式).
     * <ul>
     *   <li>{@code "created_by"} — SELF / SELF_AND_BELOW scope 过滤创建人</li>
     *   <li>{@code "department"} — DEPT_AND_BELOW scope 过滤部门</li>
     *   <li>{@code "customer_id"} — CUSTOM scope 过滤客户白名单</li>
     * </ul>
     * 默认 {@code ""} (回退到 {@link #value()}, 再回退到 {@code "created_by"}).
     */
    String column() default "";

    /**
     * 是否强制开启 scope 过滤. {@code false} (默认): 仅 setup context, Service 自行决定;
     * {@code true} (defer Sprint 6): 强制 throw if scope unsupported.
     */
    boolean enforce() default false;
}
