package com.cretas.aims.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Round 5 Fix SEC-1: Custom role-based authorization annotation.
 *
 * Why custom instead of Spring Security @PreAuthorize:
 *   - SecurityAutoConfiguration is excluded in all application profiles
 *     (application.properties:2, application-prod.properties:2, etc).
 *   - @PreAuthorize annotations are silently NO-OPs because method security proxy
 *     is never registered.
 *   - Re-enabling Spring Security requires reworking JwtAuthInterceptor + re-testing
 *     the entire auth stack.
 *
 * This annotation is enforced by {@link RequireRoleInterceptor} which reads the
 * 'role' attribute set by JwtAuthInterceptor and rejects with 403 if mismatch.
 *
 * Usage:
 *   @RequireRole({"FACTORY_SUPER_ADMIN", "PLATFORM_SUPER_ADMIN"})
 *   @PostMapping("/publish")
 *   public ApiResponse<Void> publish(...) { ... }
 *
 * Empty array = no role restriction (but still requires authentication).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    /** Allowed role codes (lowercase FactoryUserRole enum values). At least one must match. */
    String[] value();
}
