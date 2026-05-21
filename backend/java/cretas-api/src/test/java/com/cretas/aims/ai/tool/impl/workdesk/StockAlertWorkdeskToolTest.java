package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.service.MaterialBatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link StockAlertWorkdeskTool} (Sprint 8 P4b). */
@ExtendWith(MockitoExtension.class)
class StockAlertWorkdeskToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private StockAlertWorkdeskTool tool;

    @Mock
    private MaterialBatchService materialBatchService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-SAW-01: metadata")
    void metadata() {
        assertEquals("stock_alert", tool.getToolName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("低库存"));
    }

    @Test
    @DisplayName("UT-SAW-02: doExecute — adds recommendedReplenishQty = gap × 1.5")
    @SuppressWarnings("unchecked")
    void replenishCalculation() throws Exception {
        Map<String, Object> w1 = new HashMap<>();
        w1.put("materialTypeId", "M001");
        w1.put("materialName", "猪肉");
        w1.put("category", "肉类");
        w1.put("currentStock", 20.0);
        w1.put("safetyStock", 100.0);
        w1.put("unit", "kg");
        when(materialBatchService.getLowStockWarnings(anyString())).thenReturn(List.of(w1));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) data.get("warnings");
        assertEquals(1, warnings.size());
        Map<String, Object> w = warnings.get(0);
        // gap = 100 - 20 = 80; replenish = 80 * 1.5 = 120
        assertEquals(120.0, (Double) w.get("recommendedReplenishQty"), 0.01);
        assertEquals(80.0, ((Number) w.get("gap")).doubleValue(), 0.01);
        assertNotNull(w.get("displayHint"));
    }

    @Test
    @DisplayName("UT-SAW-03: doExecute — materialCategory filter")
    @SuppressWarnings("unchecked")
    void categoryFilter() throws Exception {
        Map<String, Object> w1 = new HashMap<>();
        w1.put("materialTypeId", "M001"); w1.put("materialName", "猪肉");
        w1.put("category", "肉类"); w1.put("currentStock", 20.0);
        w1.put("safetyStock", 100.0); w1.put("unit", "kg");
        Map<String, Object> w2 = new HashMap<>();
        w2.put("materialTypeId", "M002"); w2.put("materialName", "盐");
        w2.put("category", "调料"); w2.put("currentStock", 5.0);
        w2.put("safetyStock", 50.0); w2.put("unit", "kg");
        when(materialBatchService.getLowStockWarnings(anyString())).thenReturn(List.of(w1, w2));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("materialCategory", "肉类"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) data.get("warnings");
        assertEquals(1, warnings.size());
        assertEquals("猪肉", warnings.get(0).get("materialName"));
    }

    @Test
    @DisplayName("UT-SAW-04: doExecute — empty service result returns ok message")
    @SuppressWarnings("unchecked")
    void emptyResult() throws Exception {
        when(materialBatchService.getLowStockWarnings(anyString())).thenReturn(List.of());

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(0, data.get("total"));
        String msg = (String) result.get("message");
        assertTrue(msg.contains("没有") || msg.contains("充足"));
    }

    // ── helpers ──
    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        return ctx;
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

    private void injectField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new IllegalArgumentException("Field not found: " + name);
    }
}
