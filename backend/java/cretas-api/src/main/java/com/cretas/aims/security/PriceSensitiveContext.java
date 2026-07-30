package com.cretas.aims.security;

/**
 * Thread-local context that signals to Jackson serialization layer
 * ({@link PriceSensitiveSerializerModifier}) whether the current request
 * lacks the {@code procurement:price:view} permission, so {@code @PriceSensitive}
 * properties should be hidden (emit null) without invoking getters.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Set by {@link PriceFieldResponseAdvice#beforeBodyWrite} when the current
 * user lacks the price-view permission. Cleared by
 * {@link PriceFieldResponseAdvice} in a {@code finally} block at the end of
 * the same call. Always cleared — no leaks across requests.
 *
 * <h2>Thread model</h2>
 *
 * <p>Spring MVC serializes the response body on the same thread that runs the
 * controller advice's {@code beforeBodyWrite}. Jackson invokes the serializer
 * chain (including our {@link PriceSensitiveSerializerModifier}) synchronously
 * from that thread, so the ThreadLocal set in {@code beforeBodyWrite} is
 * visible. The body is fully serialized before {@code beforeBodyWrite} returns
 * control (technically the body is queued; the actual write happens in the
 * {@link org.springframework.http.converter.HttpMessageConverter#write}
 * invocation that follows). Spring's
 * {@code ResponseBodyAdvice} contract is that {@code beforeBodyWrite} is
 * invoked just before the converter writes the body — same thread.
 *
 * <p>Async / WebFlux are out of scope (this project uses Servlet stack).
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-12 (P0 hotfix)
 */
public final class PriceSensitiveContext {

    /**
     * {@code true} when the current request lacks the price-view permission,
     * meaning {@code @PriceSensitive} properties should be hidden.
     */
    private static final ThreadLocal<Boolean> HIDE_PRICES = new ThreadLocal<>();

    private PriceSensitiveContext() {
        // Utility class
    }

    /**
     * Mark the current thread as requiring price hiding. Always pair with
     * {@link #clear()} in a {@code finally} block.
     */
    public static void hide() {
        HIDE_PRICES.set(Boolean.TRUE);
    }

    /**
     * Returns {@code true} when the property gated by {@code permission}
     * should be hidden in the current serialization. The Jackson modifier
     * uses this to short-circuit getter invocation.
     *
     * <p>Currently all {@code @PriceSensitive} permissions resolve to the
     * single hide-or-show flag (the default {@code procurement:price:view}).
     * The {@code permission} parameter is reserved for future per-permission
     * granular hiding without breaking the call site.
     */
    public static boolean shouldHide(String permission) {
        Boolean flag = HIDE_PRICES.get();
        return Boolean.TRUE.equals(flag);
    }

    /**
     * Clear the ThreadLocal. <b>MUST</b> be called in a {@code finally}
     * block at the end of every request that called {@link #hide}.
     */
    public static void clear() {
        HIDE_PRICES.remove();
    }
}
