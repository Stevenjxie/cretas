package com.cretas.aims.config;

import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitiveContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Guarantees that {@link PriceSensitiveContext}'s ThreadLocal is cleared at
 * the end of every request, even when serialization succeeds and the controller
 * advice never had a chance to clean up.
 *
 * <h2>Why this filter exists</h2>
 *
 * <p>{@link com.cretas.aims.security.PriceFieldResponseAdvice#beforeBodyWrite}
 * sets the ThreadLocal flag before Jackson serializes the response body.
 * Jackson serialization happens <i>after</i> {@code beforeBodyWrite} returns —
 * so the advice itself cannot put the {@code clear()} call in a {@code finally}
 * block tied to the serialization lifecycle (the advice has already returned).
 *
 * <p>This filter wraps the entire request lifecycle and clears the ThreadLocal
 * in {@code finally}, guaranteeing no leak onto pooled Tomcat worker threads.
 *
 * <h2>Order</h2>
 *
 * <p>{@link Ordered#HIGHEST_PRECEDENCE} so this filter is the outermost layer
 * — the {@code finally} runs after every other filter / interceptor /
 * controller advice has completed.
 *
 * <h2>Async dispatch</h2>
 *
 * <p>{@link OncePerRequestFilter#shouldNotFilterAsyncDispatch()} returns
 * {@code true} by default, meaning the filter would NOT run on
 * async-dispatch redispatches (e.g. when a controller returns
 * {@code Callable}, {@code DeferredResult}, or {@code SseEmitter}). On
 * async redispatch the response is written on a different pooled thread
 * — if {@link PriceFieldResponseAdvice} sets the ThreadLocal on that
 * thread but the filter does not fire, the {@code finally} block above
 * never runs and the ThreadLocal leaks onto the worker pool. Next sync
 * request landing on the same pooled thread would see
 * {@link PriceSensitiveContext#shouldHide(String)} return {@code true}
 * and intermittently strip prices for users who should see them
 * (fail-CLOSED pollution: UX defect for admins/finance, not a data
 * leak).
 *
 * <p>Today the 7 SSE/async controllers in this codebase do not return
 * any {@code @PriceSensitive}-bearing entity, so no production scenario
 * triggers the leak. The override below is defensive — it eliminates the
 * class of bug so future async endpoints returning gated entities don't
 * silently regress.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-12 (P0 hotfix; F1 async-dispatch hardening 2026-05-12)
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PriceSensitiveContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Always clear — covers happy path, errors, and short-circuited responses.
            PriceSensitiveContext.clear();
        }
    }

    /**
     * Run the filter on async-dispatch redispatches so the {@code finally}
     * cleanup above fires on the thread that wrote the async response. The
     * default {@code true} would skip the filter on async dispatch, leaving
     * the ThreadLocal set on the redispatch worker thread and leaking onto
     * the pool. See class-level javadoc for the pollution scenario.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
