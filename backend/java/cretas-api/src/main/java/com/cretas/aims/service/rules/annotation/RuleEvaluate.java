package com.cretas.aims.service.rules.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation: this Service method should be intercepted by RuleEvaluateAspect.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §6
 *
 * Sister chat impl plan: attach to
 *   PurchaseServiceImpl.submitOrder      @RuleEvaluate("ORDER")
 *   SalesServiceImpl.createOrder         @RuleEvaluate("ORDER")
 *   InventoryServiceImpl.updateStock     @RuleEvaluate("INVENTORY")
 *   CustomerServiceImpl.update           @RuleEvaluate("CUSTOMER")
 *
 * NOT attached in this PR — would touch Phase 1's same Service files. Phase 4a sister
 * chat handles after Phase 1 merge.
 *
 * Usage:
 *   @RuleEvaluate("ORDER")
 *   public PurchaseOrder submitOrder(PurchaseOrderRequest req) { ... }
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RuleEvaluate {
    /** Scope enum name: ORDER / INVENTORY / CUSTOMER / CUSTOM (case-insensitive). */
    String value();

    /**
     * Phase 4a post-review I2: optional parameter name to use as the rule input object.
     *
     * <p>By default ({@code target=""}), the aspect picks the first non-primitive arg as
     * inputObject. This is wrong if the method has 2+ POJO args
     * (e.g. {@code updateOrder(OrderHeader header, OrderRequest body)}).
     *
     * <p>Set {@code target="body"} to bind to the parameter named "body" specifically:
     * <pre>
     * &#64;RuleEvaluate(value="ORDER", target="body")
     * public Order update(OrderHeader header, OrderRequest body) { ... }
     * </pre>
     *
     * <p>The target must match a parameter name visible at runtime — requires
     * {@code -parameters} javac flag (which Spring Boot starter sets by default).
     */
    String target() default "";
}
