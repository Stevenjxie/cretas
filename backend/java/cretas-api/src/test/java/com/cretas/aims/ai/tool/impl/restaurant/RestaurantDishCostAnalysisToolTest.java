package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.dto.restaurant.DishCostCardResponse;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.restaurant.DishCostCardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the upgraded {@link RestaurantDishCostAnalysisTool} (was a stub
 * returning only counts; #57 makes it return top-N dishes WITH real cost +
 * the same RBAC gating as {@link RestaurantDishCostQueryTool}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestaurantDishCostAnalysisTool (#57 真成本) 测试")
class RestaurantDishCostAnalysisToolTest {

    private static final String FID = "RES_3101_009";

    @Mock ProductTypeRepository productTypeRepository;
    @Mock DishCostCardService dishCostCardService;
    @Mock PermissionService permissionService;
    @Mock UserRepository userRepository;

    private RestaurantDishCostAnalysisTool tool;

    @BeforeEach
    void setUp() {
        tool = new RestaurantDishCostAnalysisTool();
        ReflectionTestUtils.setField(tool, "productTypeRepository", productTypeRepository);
        ReflectionTestUtils.setField(tool, "dishCostCardService", dishCostCardService);
        ReflectionTestUtils.setField(tool, "permissionService", permissionService);
        ReflectionTestUtils.setField(tool, "userRepository", userRepository);
    }

    private static ProductType dish(String id, String name) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(FID);
        p.setName(name);
        return p;
    }

    private static DishCostCardResponse card(String id, String name, String cost, String margin) {
        return DishCostCardResponse.builder()
                .productTypeId(id).productName(name).portions(1)
                .totalIngredientCost(cost == null ? null : new BigDecimal(cost))
                .grossMargin(margin == null ? null : new BigDecimal(margin))
                .hasMissingPrices(false).recipeLineCount(2)
                .computedAt(LocalDateTime.now()).ingredients(List.of())
                .build();
    }

    private Map<String, Object> ctx(Long userId) {
        Map<String, Object> c = new HashMap<>();
        c.put("factoryId", FID);
        if (userId != null) c.put("userId", userId);
        return c;
    }

    @Test
    @DisplayName("price-permitted: top-N dishes with real ¥ cost")
    void analysis_pricePermitted() throws Exception {
        when(productTypeRepository.findByFactoryIdAndIsActive(FID, true)).thenReturn(List.of(
                dish("d1", "白卤猪舌"), dish("d2", "纸片牛肉")));
        when(dishCostCardService.getCostCard(FID, "d1", 1)).thenReturn(card("d1", "白卤猪舌", "12.94", "0.66"));
        when(dishCostCardService.getCostCard(FID, "d2", 1)).thenReturn(card("d2", "纸片牛肉", "20.50", "0.55"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(new User()));
        when(permissionService.hasPermission(any(User.class), anyString())).thenReturn(true);

        Map<String, Object> result = invoke(Map.of(), ctx(1L));
        String msg = (String) result.get("message");
        assertTrue(msg.contains("¥"), "price-permitted has ¥: " + msg);
        assertTrue(msg.contains("白卤猪舌") && msg.contains("纸片牛肉"), msg);
    }

    @Test
    @DisplayName("RBAC fail-closed: non-price user → NO ¥, margins still shown")
    void analysis_noPricePermission() throws Exception {
        when(productTypeRepository.findByFactoryIdAndIsActive(FID, true)).thenReturn(List.of(dish("d1", "白卤猪舌")));
        when(dishCostCardService.getCostCard(FID, "d1", 1)).thenReturn(card("d1", "白卤猪舌", "12.94", "0.66"));
        when(userRepository.findById(11L)).thenReturn(Optional.of(new User()));
        when(permissionService.hasPermission(any(User.class), anyString())).thenReturn(false);

        Map<String, Object> result = invoke(Map.of(), ctx(11L));
        String msg = (String) result.get("message");
        assertFalse(msg.contains("¥"), "non-price user no ¥: " + msg);
        assertTrue(msg.contains("毛利") || msg.contains("权限"), msg);
    }

    @Test
    @DisplayName("dishes without recipes are skipped (not errored)")
    void analysis_skipsDishesWithoutRecipe() throws Exception {
        when(productTypeRepository.findByFactoryIdAndIsActive(FID, true)).thenReturn(List.of(
                dish("d1", "白卤猪舌"), dish("d2", "无配方菜")));
        when(dishCostCardService.getCostCard(FID, "d1", 1)).thenReturn(card("d1", "白卤猪舌", "12.94", "0.66"));
        when(dishCostCardService.getCostCard(FID, "d2", 1)).thenThrow(new ResourceNotFoundException("配方", "id", "d2"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(new User()));
        when(permissionService.hasPermission(any(User.class), anyString())).thenReturn(true);

        Map<String, Object> result = invoke(Map.of(), ctx(1L));
        String msg = (String) result.get("message");
        assertTrue(msg.contains("白卤猪舌"), msg);
        // d2 has no recipe — should be reflected as needing config, not crash
        assertNotNull(result.get("data"));
    }

    @Test
    @DisplayName("no dishes → guidance message")
    void analysis_noDishes() throws Exception {
        when(productTypeRepository.findByFactoryIdAndIsActive(FID, true)).thenReturn(List.of());
        Map<String, Object> result = invoke(Map.of(), ctx(1L));
        assertTrue(((String) result.get("message")).contains("菜品"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(Map<String, Object> params, Map<String, Object> context) {
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(tool, "doExecute", FID, params, context);
    }
}
