package com.cretas.aims.security;

import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.utils.TokenUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RBAC defense-in-depth helper for byte[] export endpoints (PDF / Excel).
 *
 * <p>PR #423 {@link PriceFieldResponseAdvice} only walks JSON response bodies. Binary
 * exports (PDF / Excel byte[]) bypass that strip — chat4 R1-C deep test (PR #442)
 * caught warehouse_manager curl /pdf seeing 单价/小计/合计. PR #450 (P0-C, commit
 * c290c8d8d9) fixed {@code /purchase/orders/{id}/pdf} with this same resolution +
 * gate pattern; this utility centralizes the resolve+gate boilerplate for the 6
 * remaining vulnerable binary export endpoints (Customer / Supplier / MaterialBatch /
 * ProductionPlan exports + Report Excel/PDF stubs).
 *
 * <p>Each controller injects this component, then per export endpoint:
 * <pre>{@code
 *   boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);
 *   byte[] bytes = service.exportXxx(factoryId, ..., maskPrice);
 * }</pre>
 *
 * <p>Closed-by-default: missing token / user not found / any exception → returns
 * {@code true} (mask). Mirrors the {@code currentUser == null} branch in PR #450's
 * {@code resolveCurrentUser} helper.
 *
 * @since 2026-05-12 (PR P0-C sweep — 6 vulnerable binary export endpoints)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceMaskResolver {

    private final MobileService mobileService;
    private final UserRepository userRepository;
    private final PermissionService permissionService;

    /**
     * Returns {@code true} when the caller's permission set does NOT include
     * {@link PriceFieldResponseAdvice#PRICE_VIEW_PERMISSION} ({@code procurement:price:view}),
     * meaning price fields in the binary export must be replaced with "—".
     *
     * @param authorization the raw {@code Authorization} request header (incl. {@code Bearer } prefix)
     * @return {@code true} → mask prices, {@code false} → show real values
     */
    public boolean shouldMaskPrice(String authorization) {
        User currentUser = resolveCurrentUser(authorization);
        if (currentUser == null) {
            return true; // closed-by-default: no resolvable caller → mask
        }
        return !permissionService.hasPermission(
                currentUser, PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION);
    }

    /**
     * Returns {@code true} when the caller's permission set does NOT include
     * {@link PriceFieldResponseAdvice#FINANCE_READ_PERMISSION} ({@code finance:read_write}),
     * meaning operational-dashboard COST fields ({@code unitCost} / {@code totalCost} /
     * {@code materialCost} / {@code laborCost} / ...) must be removed for that caller.
     *
     * <p>Production / operating cost is <strong>finance-sensitive</strong> — it must be
     * gated on {@code finance:read_write}, NOT on {@code procurement:price:view}. This
     * MIRRORS {@link PriceFieldResponseAdvice} Rule 2b's {@code canViewFinance} gate so
     * the two masking layers agree. Without this alignment, a price-view-but-not-finance
     * role (sales_manager / production_manager / procurement_manager) had the operational
     * cost KEY kept by the dashboard masking (which used {@link #shouldMaskPrice}) but its
     * VALUE nulled by Rule 2b → a useless {@code "unitCost": null} present-but-null
     * inconsistency. Gating both layers on {@code finance:read_write} removes the key
     * cleanly for every non-finance role; only finance-write roles see the value (the
     * pre-existing effective behaviour — Rule 2b already nulled it for everyone else).
     * Closed-by-default.
     *
     * @param authorization the raw {@code Authorization} request header (incl. {@code Bearer } prefix)
     * @return {@code true} → remove operational cost keys, {@code false} → keep real values
     * @since 2026-06-18 (dashboard cost finance-gate alignment — sales_manager must not see unitCost)
     */
    public boolean shouldMaskOperationalCost(String authorization) {
        User currentUser = resolveCurrentUser(authorization);
        if (currentUser == null) {
            return true; // closed-by-default: no resolvable caller → mask
        }
        return !permissionService.hasPermission(
                currentUser, PriceFieldResponseAdvice.FINANCE_READ_PERMISSION);
    }

    /**
     * Resolve the authenticated {@link User} entity for permission checks.
     * Returns {@code null} when the token is missing/invalid or the user row is
     * absent — caller MUST treat {@code null} as "no permission" (closed-by-default).
     * Mirror of PR #450 (PurchaseController) private helper, lifted to component
     * scope so multiple controllers can share without duplication.
     */
    private User resolveCurrentUser(String authorization) {
        try {
            String token = TokenUtils.extractToken(authorization);
            Long userId = mobileService.getUserFromToken(token).getId();
            if (userId == null) {
                return null;
            }
            return userRepository.findById(userId).orElse(null);
        } catch (Exception e) {
            log.debug("PriceMaskResolver.resolveCurrentUser failed: {}", e.getMessage());
            return null;
        }
    }
}
