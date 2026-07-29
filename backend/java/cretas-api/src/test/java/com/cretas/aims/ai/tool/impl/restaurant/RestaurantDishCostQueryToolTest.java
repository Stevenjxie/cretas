package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.dto.restaurant.DishCostCardResponse;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
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
 * Unit tests for {@link RestaurantDishCostQueryTool}.
 *
 * <ul>
 *   <li>fuzzy-match product name → cost card with ¥ for price-permitted user</li>
 *   <li>RBAC fail-closed: non-price user gets margin/counts but NO ¥ cost</li>
 *   <li>no dish → readable not-found message (no exception)</li>
 *   <li>missing param → NEED_MORE_INFO</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestaurantDishCostQueryTool 单元测试")
class RestaurantDishCostQueryToolTest {

    private static final String FID = "RES_3101_009";

    @Mock DishCostCardService dishCostCardService;
    @Mock ProductTypeRepository productTypeRepository;
    @Mock PermissionService permissionService;
    @Mock UserRepository userRepository;
    /**
     * Tiered-delegate gate added in front of {@code doExecute} (see
     * {@link TieredIntentDelegate}).
     *
     * <p>It MUST be stubbed to return {@code null} — the "Python did not answer,
     * run your own flow" signal. Leaving it unstubbed does <b>not</b> work: Mockito's
     * default answer returns an <i>empty Map</i> for {@code Map}-returning methods,
     * which is non-null, so {@code doExecute} would return that empty map and every
     * assertion below would be checking a value the tool never produced.
     * Stubbing a real payload instead is equally wrong (short-circuits the tool);
     * the delegate-hit path gets its own dedicated test.
     */
    @Mock TieredIntentDelegate tieredDelegate;

    private RestaurantDishCostQueryTool tool;

    @BeforeEach
    void setUp() {
        tool = new RestaurantDishCostQueryTool();
        ReflectionTestUtils.setField(tool, "dishCostCardService", dishCostCardService);
        ReflectionTestUtils.setField(tool, "productTypeRepository", productTypeRepository);
        ReflectionTestUtils.setField(tool, "permissionService", permissionService);
        ReflectionTestUtils.setField(tool, "userRepository", userRepository);
        ReflectionTestUtils.setField(tool, "tieredDelegate", tieredDelegate);
        lenient().when(tieredDelegate.tryDelegate(anyString(), anyMap(), anyMap(), anyString()))
                .thenReturn(null);
    }

    private static ProductType dish(String id, String name) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(FID);
        p.setName(name);
        p.setUnit("份");
        return p;
    }

    private static DishCostCardResponse card() {
        return DishCostCardResponse.builder()
                .productTypeId("dish-1")
                .productName("白卤猪舌")
                .portions(1)
                .totalIngredientCost(new BigDecimal("12.94"))
                .sellPrice(new BigDecimal("38.00"))
                .grossMargin(new BigDecimal("0.6596"))
                .hasMissingPrices(false)
                .recipeLineCount(2)
                .computedAt(LocalDateTime.now())
                .ingredients(List.of(
                        DishCostCardResponse.IngredientCostLine.builder()
                                .rawMaterialTypeId("m1").materialName("猪舌")
                                .standardQty(new BigDecimal("0.45")).actualQty(new BigDecimal("0.775862"))
                                .unit("kg").netYieldRate(new BigDecimal("0.58"))
                                .unitPrice(new BigDecimal("15.90")).itemCost(new BigDecimal("12.34"))
                                .build()))
                .build();
    }

    private Map<String, Object> ctx(Long userId) {
        Map<String, Object> c = new HashMap<>();
        c.put("factoryId", FID);
        if (userId != null) {
            c.put("userId", userId);
        }
        return c;
    }

    @Test
    @DisplayName("price-permitted user: ¥ cost + margin in message")
    void execute_pricePermitted() throws Exception {
        when(productTypeRepository.findByFactoryIdAndName(FID, "白卤猪舌")).thenReturn(Optional.of(dish("dish-1", "白卤猪舌")));
        when(dishCostCardService.getCostCard(FID, "dish-1", 1)).thenReturn(card());
        when(userRepository.findById(1L)).thenReturn(Optional.of(new User()));
        when(permissionService.hasPermission(any(User.class), anyString())).thenReturn(true);

        Map<String, Object> params = Map.of("productName", "白卤猪舌");
        Map<String, Object> result = invokeDoExecute(params, ctx(1L));

        String msg = (String) result.get("message");
        assertNotNull(msg);
        assertTrue(msg.contains("白卤猪舌"), "message has dish name: " + msg);
        assertTrue(msg.contains("¥"), "price-permitted message has ¥: " + msg);
        assertTrue(msg.contains("12.94") || msg.contains("食材成本"), "has cost: " + msg);
        assertTrue(msg.contains("毛利"), "has margin: " + msg);
    }

    @Test
    @DisplayName("RBAC fail-closed: non-price user → NO ¥ cost, margin omitted note")
    void execute_noPricePermission() throws Exception {
        when(productTypeRepository.findByFactoryIdAndName(FID, "白卤猪舌")).thenReturn(Optional.of(dish("dish-1", "白卤猪舌")));
        when(dishCostCardService.getCostCard(FID, "dish-1", 1)).thenReturn(card());
        when(userRepository.findById(11L)).thenReturn(Optional.of(new User()));
        when(permissionService.hasPermission(any(User.class), anyString())).thenReturn(false);

        Map<String, Object> result = invokeDoExecute(Map.of("productName", "白卤猪舌"), ctx(11L));

        String msg = (String) result.get("message");
        assertNotNull(msg);
        assertTrue(msg.contains("白卤猪舌"), "still names the dish");
        assertFalse(msg.contains("¥"), "non-price user message must NOT contain ¥: " + msg);
        assertTrue(msg.contains("权限") || msg.contains("无权"), "explains permission gating: " + msg);
    }

    @Test
    @DisplayName("RBAC fail-closed: missing userId → NO ¥ cost (fail-closed)")
    void execute_missingUserId_failClosed() throws Exception {
        when(productTypeRepository.findByFactoryIdAndName(FID, "白卤猪舌")).thenReturn(Optional.of(dish("dish-1", "白卤猪舌")));
        when(dishCostCardService.getCostCard(FID, "dish-1", 1)).thenReturn(card());

        Map<String, Object> result = invokeDoExecute(Map.of("productName", "白卤猪舌"), ctx(null));

        String msg = (String) result.get("message");
        assertFalse(msg.contains("¥"), "missing userId fail-closed (no ¥): " + msg);
    }

    @Test
    @DisplayName("no matching dish → readable not-found message, no exception")
    void execute_noDish() throws Exception {
        when(productTypeRepository.findByFactoryIdAndName(FID, "不存在的菜")).thenReturn(Optional.empty());
        when(productTypeRepository.findByFactoryIdAndIsActive(FID, true)).thenReturn(List.of(dish("dish-1", "白卤猪舌")));

        Map<String, Object> result = invokeDoExecute(Map.of("productName", "不存在的菜"), ctx(1L));

        String msg = (String) result.get("message");
        assertNotNull(msg);
        assertTrue(msg.contains("未找到") || msg.contains("没有找到") || msg.contains("找不到"), "not-found message: " + msg);
    }

    @Test
    @DisplayName("fuzzy contains match: '猪舌' matches '白卤猪舌'")
    void execute_fuzzyContains() throws Exception {
        when(productTypeRepository.findByFactoryIdAndName(FID, "猪舌")).thenReturn(Optional.empty());
        when(productTypeRepository.findByFactoryIdAndIsActive(FID, true)).thenReturn(List.of(dish("dish-1", "白卤猪舌")));
        when(dishCostCardService.getCostCard(FID, "dish-1", 1)).thenReturn(card());
        when(userRepository.findById(1L)).thenReturn(Optional.of(new User()));
        when(permissionService.hasPermission(any(User.class), anyString())).thenReturn(true);

        Map<String, Object> result = invokeDoExecute(Map.of("productName", "猪舌"), ctx(1L));

        assertTrue(((String) result.get("message")).contains("白卤猪舌"));
        verify(dishCostCardService).getCostCard(FID, "dish-1", 1);
    }

    @Test
    @DisplayName("tiered delegate 命中 → 直接返回 Python 答案, 不查工厂库")
    void execute_delegateHit_shortCircuits() throws Exception {
        Map<String, Object> pythonAnswer = Map.of(
                "dataAvailable", true,
                "message", "白卤猪舌 食材成本 ¥12.94",
                "tieredDelegate", true);
        when(tieredDelegate.tryDelegate(eq(FID), anyMap(), anyMap(), eq("restaurant_dish_cost_query")))
                .thenReturn(pythonAnswer);

        Map<String, Object> result = invokeDoExecute(Map.of("productName", "白卤猪舌"), ctx(1L));

        assertEquals(pythonAnswer, result);
        verifyNoInteractions(productTypeRepository, dishCostCardService);
    }

    @Test
    @DisplayName("getToolName / getRequiredParameters contract")
    @SuppressWarnings("unchecked")
    void contract() {
        assertEquals("restaurant_dish_cost_query", tool.getToolName());
        // getRequiredParameters() is intentionally EMPTY: the productName check moved
        // *behind* the tiered-delegate gate inside doExecute, otherwise a follow-up
        // like "成本如何" (dish carried by the Python session) would be bounced by the
        // param validator before the delegate ever ran. The check itself is asserted
        // by missingProductName_asksForDishName below.
        List<String> required = (List<String>) ReflectionTestUtils.invokeMethod(tool, "getRequiredParameters");
        assertNotNull(required);
        assertTrue(required.isEmpty(), "productName 校验后移到 doExecute 委派门之后: " + required);
    }

    @Test
    @DisplayName("委派未命中 + 无 productName → 反问菜品名 (不抛异常)")
    void missingProductName_asksForDishName() throws Exception {
        Map<String, Object> result = invokeDoExecute(new HashMap<>(), ctx(1L));

        String msg = (String) result.get("message");
        assertNotNull(msg);
        assertTrue(msg.contains("菜品名称"), "缺 productName 时应反问菜品名: " + msg);
        verifyNoInteractions(dishCostCardService);
    }

    /** Reflective bridge to the protected doExecute. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoExecute(Map<String, Object> params, Map<String, Object> context) throws Exception {
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                tool, "doExecute", FID, params, context);
    }
}
