package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.dto.restaurant.WastageAccountability;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.restaurant.WastageRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wave2 损耗责任制 — RestaurantWastageSummaryTool 价权门控单测。
 *
 * <p>Review IMPORTANT: AI 工具路径绕过 /accountability 端点的 @RequirePermission, 且按
 * 责任人/档口的中文键 "损耗成本" 不被 PriceFieldResponseAdvice 脱敏 → 无价权餐饮角色可经
 * AI 助手读取逐人逐档口成本。本测试锁定: 有价权 → 金额可见; 无价权 → 金额全隐, 仅留数量
 * 与责任归属 + 金额说明。fail-CLOSED。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestaurantWastageSummaryTool — 损耗成本价权门控 (RBAC bypass 修复)")
class RestaurantWastageSummaryToolCostGateTest {

    private static final String FACTORY_ID = "RES_3101_007";

    @InjectMocks
    private RestaurantWastageSummaryTool tool;

    @Mock
    private MaterialBatchRepository materialBatchRepository;

    @Mock
    private WastageRecordService wastageRecordService;

    @Mock
    private PermissionService permissionService;

    @Mock
    private UserRepository userRepository;

    private final User user = mock(User.class);

    @BeforeEach
    void setUp() {
        // 无过期批次 → 估算损耗金额=¥0.00 (仍受门控决定是否输出)。
        when(materialBatchRepository.findExpiredBatches(FACTORY_ID))
                .thenReturn(Collections.emptyList());

        WastageAccountability.ByOperator op = new WastageAccountability.ByOperator();
        op.setOperatorId(10L);
        op.setOperatorName("张三");
        op.setCount(3L);
        op.setTotalQuantity(new BigDecimal("5"));
        op.setTotalCost(new BigDecimal("120.50"));

        WastageAccountability.BySection sec = new WastageAccountability.BySection();
        sec.setSectionCode("SEAFOOD");
        sec.setSectionName("海鲜档");
        sec.setCount(2L);
        sec.setTotalQuantity(new BigDecimal("3"));
        sec.setTotalCost(new BigDecimal("80.00"));

        WastageAccountability acc = new WastageAccountability();
        acc.setStartDate("2026-06-01");
        acc.setEndDate("2026-06-04");
        acc.setTotalCost(new BigDecimal("200.50"));
        acc.setTotalCount(5L);
        acc.setByOperator(List.of(op));
        acc.setBySection(List.of(sec));

        when(wastageRecordService.getAccountability(eq(FACTORY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(acc);
        // findById 仅在 userId 存在的两个测试用到 (missingUserId 早退不调) → lenient。
        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("有价权 (procurement:price:view / finance) → 金额字段全部可见")
    @SuppressWarnings("unchecked")
    void priceRole_seesCost() throws Exception {
        when(permissionService.hasPermission(any(User.class), anyString())).thenReturn(true);

        Map<String, Object> result = invokeDoExecute();

        assertTrue(result.containsKey("估算损耗金额"), "有价权应见估算损耗金额");
        assertTrue(result.containsKey("本月已审批损耗成本"), "有价权应见本月损耗成本");
        assertFalse(result.containsKey("金额说明"), "有价权不应出现金额说明");

        List<Map<String, Object>> byOperator = (List<Map<String, Object>>) result.get("按责任人");
        assertTrue(byOperator.get(0).containsKey("损耗成本"), "有价权应见逐责任人损耗成本");
        assertEquals("张三", byOperator.get(0).get("责任人"));

        List<Map<String, Object>> bySection = (List<Map<String, Object>>) result.get("按档口");
        assertTrue(bySection.get(0).containsKey("损耗成本"), "有价权应见逐档口损耗成本");
    }

    @Test
    @DisplayName("无价权 → 金额全隐, 仅留数量与责任归属 + 金额说明 (fail-closed)")
    @SuppressWarnings("unchecked")
    void noPriceRole_costStripped() throws Exception {
        when(permissionService.hasPermission(any(User.class), anyString())).thenReturn(false);

        Map<String, Object> result = invokeDoExecute();

        assertFalse(result.containsKey("估算损耗金额"), "无价权不应见估算损耗金额");
        assertFalse(result.containsKey("本月已审批损耗成本"), "无价权不应见本月损耗成本");
        assertTrue(result.containsKey("金额说明"), "无价权应出现金额说明引导");
        // 数量仍可见 (责任归属透明化不依赖金额)。
        assertEquals(5L, ((Long) result.get("本月已审批损耗记录数")).longValue());

        List<Map<String, Object>> byOperator = (List<Map<String, Object>>) result.get("按责任人");
        assertFalse(byOperator.get(0).containsKey("损耗成本"), "无价权不应见逐责任人损耗成本");
        assertTrue(byOperator.get(0).containsKey("责任人"), "无价权仍应见责任人姓名");
        assertTrue(byOperator.get(0).containsKey("损耗记录数"), "无价权仍应见记录数");

        List<Map<String, Object>> bySection = (List<Map<String, Object>>) result.get("按档口");
        assertFalse(bySection.get(0).containsKey("损耗成本"), "无价权不应见逐档口损耗成本");
    }

    @Test
    @DisplayName("userId 缺失 → fail-closed 隐藏金额")
    @SuppressWarnings("unchecked")
    void missingUserId_failsClosed() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        // 故意不放 userId → getUserId 返 null → canViewCost=false。

        Map<String, Object> result = (Map<String, Object>) invoke("doExecute", FACTORY_ID, Map.of(), ctx);

        assertFalse(result.containsKey("估算损耗金额"), "userId 缺失应 fail-closed 隐藏金额");
        assertTrue(result.containsKey("金额说明"));
    }

    // ── helpers ──
    private Map<String, Object> invokeDoExecute() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        return invoke("doExecute", FACTORY_ID, Map.of(), ctx);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String name, String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        var method = findMethod(tool.getClass(), name);
        method.setAccessible(true);
        try {
            return (Map<String, Object>) method.invoke(tool, factoryId, params, context);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }

    private java.lang.reflect.Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            for (var m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
            }
            clazz = clazz.getSuperclass();
        }
        throw new IllegalArgumentException("Method not found: " + name);
    }
}
