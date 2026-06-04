package com.cretas.aims.controller.restaurant;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.restaurant.DishCostCardResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.restaurant.DishCostCardService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DishCostCardController} including the RBAC price-strip
 * behaviour driven by {@link PriceFieldResponseAdvice} on the
 * {@code @PriceSensitive} DTO fields.
 *
 * <ul>
 *   <li>controller returns populated card (service mocked) + portion clamp</li>
 *   <li>RBAC: warehouse_manager (no price perm) → cost fields stripped to null</li>
 *   <li>RBAC: factory_super_admin (price perm) → cost fields preserved</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DishCostCardController + RBAC price strip 测试")
class DishCostCardControllerTest {

    private static final String FID = "RES_3101_009";
    private static final String DISH = "dish-1";
    private static final String PRICE_PERM = "procurement:price:view";

    @Mock DishCostCardService dishCostCardService;
    @Mock PermissionService permissionService;
    @Mock UserRepository userRepository;

    private DishCostCardController controller;
    private PriceFieldResponseAdvice advice;

    @BeforeEach
    void setUp() {
        controller = new DishCostCardController(dishCostCardService);
        advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissionService);
        ReflectionTestUtils.setField(advice, "userRepository", userRepository);
    }

    private static DishCostCardResponse sampleCard() {
        return DishCostCardResponse.builder()
                .productTypeId(DISH)
                .productName("白卤猪舌")
                .portions(1)
                .totalIngredientCost(new BigDecimal("12.9362"))
                .sellPrice(new BigDecimal("38.00"))
                .grossMargin(new BigDecimal("0.6596"))
                .hasMissingPrices(false)
                .recipeLineCount(2)
                .computedAt(LocalDateTime.now())
                .ingredients(List.of(
                        DishCostCardResponse.IngredientCostLine.builder()
                                .rawMaterialTypeId("m1")
                                .materialName("猪舌")
                                .standardQty(new BigDecimal("0.45"))
                                .actualQty(new BigDecimal("0.775862"))
                                .unit("kg")
                                .netYieldRate(new BigDecimal("0.58"))
                                .unitPrice(new BigDecimal("15.90"))
                                .itemCost(new BigDecimal("12.3362"))
                                .build()))
                .build();
    }

    private User user(long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private ServletServerHttpRequest requestForUser(long userId) {
        MockHttpServletRequest servletReq = new MockHttpServletRequest();
        servletReq.setAttribute("userId", userId);
        return new ServletServerHttpRequest(servletReq);
    }

    @Test
    @DisplayName("controller 返回填充的成本卡 (service mocked)")
    void controller_returnsPopulatedCard() {
        when(dishCostCardService.getCostCard(FID, DISH, 1)).thenReturn(sampleCard());

        ApiResponse<DishCostCardResponse> resp = controller.costCard(FID, DISH, 1);

        assertTrue(resp.getSuccess());
        assertEquals("白卤猪舌", resp.getData().getProductName());
        assertEquals(new BigDecimal("12.9362"), resp.getData().getTotalIngredientCost());
        verify(dishCostCardService).getCostCard(FID, DISH, 1);
    }

    @Test
    @DisplayName("controller clamps portions to [1, 9999]")
    void controller_clampsPortions() {
        when(dishCostCardService.getCostCard(eq(FID), eq(DISH), anyInt())).thenReturn(sampleCard());

        controller.costCard(FID, DISH, 0);       // → clamped to 1
        controller.costCard(FID, DISH, 100000);  // → clamped to 9999

        verify(dishCostCardService).getCostCard(FID, DISH, 1);
        verify(dishCostCardService).getCostCard(FID, DISH, 9999);
    }

    @Test
    @DisplayName("RBAC: warehouse_manager (no price perm) → 成本字段被剥离为 null")
    void rbac_warehouseManager_costStripped() {
        DishCostCardResponse card = sampleCard();
        when(userRepository.findById(11L)).thenReturn(Optional.of(user(11L)));
        when(permissionService.hasPermission(any(User.class), eq(PRICE_PERM))).thenReturn(false);

        advice.beforeBodyWrite(card, null, MediaType.APPLICATION_JSON, null,
                requestForUser(11L), null);

        // top-level @PriceSensitive cost fields stripped
        assertNull(card.getTotalIngredientCost());
        assertNull(card.getSellPrice());
        assertNull(card.getGrossMargin());
        // nested line cost fields stripped recursively
        assertNull(card.getIngredients().get(0).getUnitPrice());
        assertNull(card.getIngredients().get(0).getItemCost());
        // non-price fields survive
        assertEquals("白卤猪舌", card.getProductName());
        assertEquals(new BigDecimal("0.775862"), card.getIngredients().get(0).getActualQty());
    }

    @Test
    @DisplayName("RBAC: factory_super_admin (price perm) → 成本字段保留")
    void rbac_admin_costPreserved() {
        DishCostCardResponse card = sampleCard();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(permissionService.hasPermission(any(User.class), eq(PRICE_PERM))).thenReturn(true);

        advice.beforeBodyWrite(card, null, MediaType.APPLICATION_JSON, null,
                requestForUser(1L), null);

        assertEquals(new BigDecimal("12.9362"), card.getTotalIngredientCost());
        assertEquals(new BigDecimal("38.00"), card.getSellPrice());
        assertEquals(new BigDecimal("15.90"), card.getIngredients().get(0).getUnitPrice());
        assertEquals(new BigDecimal("12.3362"), card.getIngredients().get(0).getItemCost());
    }
}
