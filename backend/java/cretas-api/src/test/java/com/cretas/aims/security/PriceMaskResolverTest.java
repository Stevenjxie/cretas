package com.cretas.aims.security;

import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks the operational-dashboard cost finance-gate (2026-06-18).
 *
 * <p>Bug context: the operational dashboard cost masking ({@code maskOperationalDashboardCosts})
 * used to gate on {@link PriceMaskResolver#shouldMaskPrice} ({@code procurement:price:view}),
 * but {@link PriceFieldResponseAdvice} Rule 2b nulls the same cost values on the finance gate
 * ({@code finance:read_write}). For a price-view-but-not-finance role (sales_manager /
 * production_manager / procurement_manager) the two layers disagreed → the cost KEY was kept
 * by the dashboard masking but its VALUE nulled by the advice → a useless
 * {@code "unitCost": null}. {@link PriceMaskResolver#shouldMaskOperationalCost} aligns the
 * dashboard masking onto the SAME finance gate so the key is cleanly removed.
 */
@ExtendWith(MockitoExtension.class)
class PriceMaskResolverTest {

    @Mock MobileService mobileService;
    @Mock UserRepository userRepository;
    @Mock PermissionService permissionService;
    @InjectMocks PriceMaskResolver resolver;

    private User stubUser(boolean hasFinanceWrite, boolean hasPriceView) {
        UserDTO dto = mock(UserDTO.class);
        when(dto.getId()).thenReturn(1L);
        when(mobileService.getUserFromToken(anyString())).thenReturn(dto);
        User user = mock(User.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        lenient().when(permissionService.hasPermission(user,
                PriceFieldResponseAdvice.FINANCE_READ_PERMISSION)).thenReturn(hasFinanceWrite);
        lenient().when(permissionService.hasPermission(user,
                PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION)).thenReturn(hasPriceView);
        return user;
    }

    @Test
    @DisplayName("sales_manager (price view but NOT finance:read_write) → operational cost masked (key removed, not left null)")
    void operationalCost_maskedForPriceButNotFinanceRole() {
        stubUser(false, true); // finance:read_write = false, procurement:price:view = true
        assertTrue(resolver.shouldMaskOperationalCost("Bearer t"),
                "price-view-but-not-finance role must have operational cost REMOVED");
        // contrast: the OLD gate (price view) would NOT have masked → this is the bug source
        assertFalse(resolver.shouldMaskPrice("Bearer t"),
                "price masking must NOT fire for a price-view role — proving the two gates diverged");
    }

    @Test
    @DisplayName("finance_manager (finance:read_write) → operational cost visible")
    void operationalCost_visibleForFinanceWriteRole() {
        stubUser(true, true);
        assertFalse(resolver.shouldMaskOperationalCost("Bearer t"));
    }

    @Test
    @DisplayName("warehouse_worker (no price, no finance) → operational cost masked")
    void operationalCost_maskedForNoPermissionRole() {
        stubUser(false, false);
        assertTrue(resolver.shouldMaskOperationalCost("Bearer t"));
    }

    @Test
    @DisplayName("closed-by-default: unresolvable caller → operational cost masked")
    void operationalCost_closedByDefaultWhenCallerUnresolvable() {
        when(mobileService.getUserFromToken(anyString())).thenReturn(null);
        assertTrue(resolver.shouldMaskOperationalCost("Bearer t"));
    }
}
