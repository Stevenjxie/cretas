package com.cretas.aims.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for fields and computed getters containing price / amount /
 * cost information that must be masked from users lacking the
 * {@code procurement:price:view} permission.
 *
 * <h2>Field target ({@link ElementType#FIELD})</h2>
 * <p>Fields annotated with {@code @PriceSensitive} are stripped (set to
 * {@code null}) by {@link PriceFieldResponseAdvice} after the controller
 * returns and before Jackson serialization. The stripping is recursive — it
 * applies to nested objects, collections and {@link com.cretas.aims.dto.common.ApiResponse}
 * payloads.
 *
 * <h2>Method target ({@link ElementType#METHOD}) — added 2026-05-12 (P0 fix)</h2>
 * <p>Annotating a {@code @Transient} computed getter (e.g. {@code getPayableAmount()})
 * marks it as price-sensitive. {@link PriceFieldResponseAdvice} treats the
 * underlying field-backing data as nulled, and the getter itself <b>MUST</b>
 * include a defensive null guard at the top:
 *
 * <pre>{@code
 *   @Transient
 *   @PriceSensitive
 *   public BigDecimal getPayableAmount() {
 *       if (totalAmount == null) return null;   // ← defensive, prevents NPE
 *       BigDecimal discount = discountAmount != null ? discountAmount : BigDecimal.ZERO;
 *       BigDecimal tax = taxAmount != null ? taxAmount : BigDecimal.ZERO;
 *       return totalAmount.subtract(discount).add(tax);
 *   }
 * }</pre>
 *
 * <p><b>Why the defensive guard is mandatory</b>: When {@code totalAmount} is
 * stripped to {@code null} by {@link PriceFieldResponseAdvice} for a
 * {@code warehouse_manager}, Jackson will still invoke the computed getter
 * during serialization (because Jackson's {@code BeanPropertyWriter} only
 * checks property exclusion at registration time, not per-request). Without
 * the guard, {@code null.subtract(...)} throws NPE → HTTP 500.
 *
 * <p><b>Background</b>: PR #423 introduced field stripping, which exposed
 * latent NPEs in 7 {@code @Transient} computed getters that did not null-check
 * their inputs. The 2026-05-12 P0 fix added defensive guards <i>and</i> this
 * METHOD target so static analysis / future grep can find all price-derived
 * accessors at once.
 *
 * <h2>Field usage (existing)</h2>
 * <pre>{@code
 *   public class PurchaseOrderItem {
 *       @PriceSensitive
 *       private BigDecimal unitPrice;
 *
 *       @PriceSensitive
 *       private BigDecimal lineAmount;
 *   }
 * }</pre>
 *
 * <p>RBAC isolation source-of-truth: PR #415 Option B (2026-05-12), extended
 * to METHOD target in P0 hotfix (2026-05-12).
 *
 * @author Cretas Team
 * @version 1.1.0
 * @since 2026-05-12
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface PriceSensitive {

    /**
     * Optional permission code required to view this field/method.
     * Default is {@code procurement:price:view}.
     */
    String permission() default "procurement:price:view";
}
