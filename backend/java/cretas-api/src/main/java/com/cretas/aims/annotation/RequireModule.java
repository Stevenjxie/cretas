package com.cretas.aims.annotation;

import java.lang.annotation.*;

/**
 * 标注在 Controller 方法或类上，表示该操作需要对应模块已启用。
 * AOP 切面 ModuleEnabledAspect 会在方法执行前检查 factoryId 对应的模块是否启用。
 *
 * 用法:
 *   @RequireModule("sales_order")
 *   public ApiResponse<...> createOrder(@PathVariable String factoryId, ...) { ... }
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireModule {
    /** 模块代码，如 "sales_order"、"bom" */
    String value();
}
