package com.cretas.aims.security;

import com.cretas.aims.entity.enums.DataScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * ThreadLocal 容器, 持有当前请求的数据权限上下文 (RBAC 第 2 维, Sprint 5 Track G).
 *
 * <p>{@link com.cretas.aims.aspect.DataScopeAspect} 在 {@code @DataScope} 方法 enter 时 push
 * 当前 user 的 scope + userId + department, exit 时 pop. Service 实现 (或后续 Sprint 6
 * Specification interceptor) 读 {@link #current()} 决定 WHERE 过滤.
 *
 * <p>线程安全: {@link ThreadLocal} per-request. Spring MVC thread-per-request 模型下,
 * 同一 request 多次嵌套 {@code @DataScope} 方法用 stack 处理 ({@link #STACK_HOLDER}).
 *
 * @author Cretas Team (Sprint 5 Track G)
 * @since 2026-05-19
 */
@Getter
@Builder
@AllArgsConstructor
@ToString
public class DataScopeContext {

    /** 当前用户 id (从 JWT). null 代表未登录或匿名访问. */
    private final Long userId;

    /** 当前用户 factoryId. */
    private final String factoryId;

    /** 当前用户角色 roleCode (e.g. "sales_manager", "factory_super_admin"). */
    private final String roleCode;

    /** 当前用户 scope, 默认 {@link DataScope#ALL}. */
    private final DataScope scope;

    /** 用户部门 (User.department), DEPT_AND_BELOW scope 用. */
    private final String department;

    /** Annotation declared column (默认 "created_by"). */
    private final String column;

    // ---- ThreadLocal stack ----

    private static final ThreadLocal<java.util.Deque<DataScopeContext>> STACK_HOLDER =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

    /** 当前栈顶 context. 没有 active context 返 null. */
    public static DataScopeContext current() {
        java.util.Deque<DataScopeContext> stack = STACK_HOLDER.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /** Aspect enter: push context. */
    public static void push(DataScopeContext ctx) {
        STACK_HOLDER.get().push(ctx);
    }

    /** Aspect exit: pop context. 没有 active 时 no-op (defensive). */
    public static void pop() {
        java.util.Deque<DataScopeContext> stack = STACK_HOLDER.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        // 清理空栈避免 ThreadLocal 泄露 (容器线程池复用 thread).
        if (stack.isEmpty()) {
            STACK_HOLDER.remove();
        }
    }

    /**
     * 便捷判断: 当前 context scope 是否过滤 (SELF/SELF_AND_BELOW/DEPT_AND_BELOW/CUSTOM).
     * ALL 不过滤.
     */
    public boolean isFiltered() {
        return scope != null && scope != DataScope.ALL;
    }
}
