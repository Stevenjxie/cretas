package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.ColdChainAlert;
import com.cretas.aims.entity.foodsafety.ColdChainEquipment;
import com.cretas.aims.repository.foodsafety.ColdChainAlertRepository;
import com.cretas.aims.repository.foodsafety.ColdChainEquipmentRepository;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ColdChainAlertActiveTool} (Sprint 9 P2.D). */
@ExtendWith(MockitoExtension.class)
class ColdChainAlertActiveToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private ColdChainAlertActiveTool tool;

    @Mock
    private ColdChainAlertRepository alertRepository;

    @Mock
    private ColdChainEquipmentRepository equipmentRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-CCAA-01: metadata — READ + LOW")
    void metadata() {
        assertEquals("cold_chain_alert_active", tool.getToolName());
        assertFalse(tool.supportsPreview());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.READ, tool.getActionType());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.RiskLevel.LOW, tool.getRiskLevel());
    }

    @Test
    @DisplayName("UT-CCAA-02: empty → friendly '无活跃报警' message")
    @SuppressWarnings("unchecked")
    void executeEmpty() throws Exception {
        when(alertRepository.findByFactoryIdAndStatusInOrderByAlertTimeDesc(
                eq(FACTORY_ID), any())).thenReturn(List.of());

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(0, data.get("totalCount"));
        assertEquals(0, data.get("openCount"));
        assertEquals(0, data.get("criticalCount"));
        assertTrue(((String) result.get("message")).contains("无活跃冷链报警"));
    }

    @Test
    @DisplayName("UT-CCAA-03: 2 alerts (1 OPEN CRITICAL + 1 ACKNOWLEDGED WARNING) → stats correct")
    @SuppressWarnings("unchecked")
    void executeWithAlerts() throws Exception {
        ColdChainAlert a1 = ColdChainAlert.builder()
                .id(1L).factoryId(FACTORY_ID).equipmentId(101L)
                .alertTime(LocalDateTime.now().minusMinutes(30))
                .severity("CRITICAL")
                .minTemp(new BigDecimal("-5.0"))
                .maxTemp(new BigDecimal("2.0"))
                .durationMinutes(45)
                .status("OPEN")
                .build();
        ColdChainAlert a2 = ColdChainAlert.builder()
                .id(2L).factoryId(FACTORY_ID).equipmentId(102L)
                .alertTime(LocalDateTime.now().minusHours(2))
                .severity("WARNING")
                .minTemp(new BigDecimal("9.0"))
                .maxTemp(new BigDecimal("11.0"))
                .durationMinutes(20)
                .status("ACKNOWLEDGED")
                .acknowledgedAt(LocalDateTime.now().minusHours(1))
                .ackReason("TEMPORARY_DOOR_OPEN")
                .build();
        when(alertRepository.findByFactoryIdAndStatusInOrderByAlertTimeDesc(
                eq(FACTORY_ID), any())).thenReturn(List.of(a1, a2));

        ColdChainEquipment eq1 = ColdChainEquipment.builder()
                .id(101L).equipmentCode("FREEZER-A01").equipmentName("1 号冰柜")
                .type("FREEZER").targetTempMin(new BigDecimal("-22"))
                .targetTempMax(new BigDecimal("-18")).build();
        when(equipmentRepository.findById(101L)).thenReturn(Optional.of(eq1));
        when(equipmentRepository.findById(102L)).thenReturn(Optional.empty());  // 设备已删

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(2, data.get("totalCount"));
        assertEquals(1, data.get("openCount"));
        assertEquals(1, data.get("acknowledgedCount"));
        assertEquals(1, data.get("criticalCount"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("alerts");
        assertEquals(2, rows.size());
        assertEquals("FREEZER-A01", rows.get(0).get("equipmentCode"));
        assertEquals("(设备已删)", rows.get(1).get("equipmentCode"));
        String msg = (String) result.get("message");
        assertTrue(msg.contains("CRITICAL"));
        assertTrue(msg.contains("⛔"));
    }

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
