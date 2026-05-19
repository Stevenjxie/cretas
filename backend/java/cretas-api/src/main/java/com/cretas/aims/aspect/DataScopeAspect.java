package com.cretas.aims.aspect;

import com.cretas.aims.annotation.DataScope;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 数据权限 AOP (RBAC 第 2 维, Sprint 5 Track G).
 *
 * <p>拦截 {@link DataScope @DataScope} 标注的方法:
 * <ol>
 *   <li>从 {@link SecurityUtils#getCurrentUserId()} 取当前 userId</li>
 *   <li>{@link DataScopeResolver#resolve(Long)} 解析 scope</li>
 *   <li>{@link DataScopeContext#push(DataScopeContext)} 入栈</li>
 *   <li>调用原方法 — Service 实现读 {@link DataScopeContext#current()} 决定 WHERE</li>
 *   <li>finally: {@link DataScopeContext#pop()} 出栈</li>
 * </ol>
 *
 * <p>Order(2): 后于 {@link ModuleEnabledAspect} (Order 1) 执行 — 模块未启用直接拒, 不需 resolve scope.
 *
 * <h3>POC 限制 (Sprint 5)</h3>
 * 当前 aspect 仅 setup ThreadLocal context. Service 实现需自行 branch on
 * {@code DataScopeContext.current()} 决定 query. Sprint 6 sweep 时考虑实现 Specification interceptor
 * 自动 inject WHERE 子句.
 *
 * @author Cretas Team (Sprint 5 Track G)
 * @since 2026-05-19
 */
@Slf4j
@Aspect
@Component
@Order(2)
@RequiredArgsConstructor
public class DataScopeAspect {

    private final DataScopeResolver resolver;

    @Around("@annotation(scope) || @within(scope)")
    public Object aroundDataScope(ProceedingJoinPoint pjp, DataScope scope) throws Throwable {
        Long userId = SecurityUtils.getCurrentUserId();
        DataScopeContext ctx = resolver.resolve(userId);

        if (ctx == null) {
            // 匿名访问 / user 不存在: 走原方法, 不 setup context. Service 看 current()==null 自行处理.
            log.debug("DataScopeAspect: no user context for {}.{}, skip scope setup",
                    pjp.getSignature().getDeclaringTypeName(),
                    pjp.getSignature().getName());
            return pjp.proceed();
        }

        // 解析 annotation column 优先级: column() > value() > "created_by" default
        String annotatedColumn = !scope.column().isEmpty() ? scope.column()
                : !scope.value().isEmpty() ? scope.value()
                : "created_by";

        DataScopeContext effectiveCtx = DataScopeContext.builder()
                .userId(ctx.getUserId())
                .factoryId(ctx.getFactoryId())
                .roleCode(ctx.getRoleCode())
                .scope(ctx.getScope())
                .department(ctx.getDepartment())
                .column(annotatedColumn)
                .build();

        DataScopeContext.push(effectiveCtx);
        try {
            log.debug("DataScopeAspect: enter {}.{} with scope={}, userId={}, column={}",
                    pjp.getSignature().getDeclaringTypeName(),
                    pjp.getSignature().getName(),
                    effectiveCtx.getScope(),
                    effectiveCtx.getUserId(),
                    effectiveCtx.getColumn());
            return pjp.proceed();
        } finally {
            DataScopeContext.pop();
        }
    }
}
