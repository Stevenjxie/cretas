package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.ColdChainEquipment;
import com.cretas.aims.entity.foodsafety.ColdChainTempReading;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.ColdChainEquipmentRepository;
import com.cretas.aims.repository.foodsafety.ColdChainTempReadingRepository;
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

/** Unit tests for {@link ColdChainTempQueryTool} (Sprint 9 P2.D). */
@ExtendWith(MockitoExtension.class)
class ColdChainTempQueryToolTest {

    private static final String FACTORY_ID = "F006";
    private static final Long EQ_ID = 42L;

    @InjectMocks
    private ColdChainTempQueryTool tool;

    @Mock
    private ColdChainEquipmentRepository equipmentRepository;

    @Mock
    private ColdChainTempReadingRepository readingRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-CCTQ-01: metadata — READ + LOW")
    void metadata() {
        assertEquals("cold_chain_temp_query", tool.getToolName());
        assertFalse(tool.supportsPreview());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.READ, tool.getActionType());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.RiskLevel.LOW, tool.getRiskLevel());
    }

    @Test
    @DisplayName("UT-CCTQ-02: query 3 readings, computes min/max/avg/deviationCount")
    @SuppressWarnings("unchecked")
    void executeWithReadings() throws Exception {
        ColdChainEquipment eq = ColdChainEquipment.builder()
                .id(EQ_ID)
                .factoryId(FACTORY_ID)
                .equipmentCode("FREEZER-A01")
                .equipmentName("1 号冰柜")
                .type("FREEZER")
                .targetTempMin(new BigDecimal("-22.0"))
                .targetTempMax(new BigDecimal("-18.0"))
                .unit("℃")
                .toleranceMinutes(15)
                .build();
        when(equipmentRepository.findById(EQ_ID)).thenReturn(Optional.of(eq));

        LocalDateTime now = LocalDateTime.now();
        ColdChainTempReading r1 = ColdChainTempReading.builder()
                .factoryId(FACTORY_ID).equipmentId(EQ_ID)
                .recordedAt(now.minusHours(2))
                .temperature(new BigDecimal("-20.0"))
                .isDeviation(false).build();
        ColdChainTempReading r2 = ColdChainTempReading.builder()
                .factoryId(FACTORY_ID).equipmentId(EQ_ID)
                .recordedAt(now.minusHours(1))
                .temperature(new BigDecimal("-15.0"))   // OUT OF RANGE, deviation
                .isDeviation(true).build();
        ColdChainTempReading r3 = ColdChainTempReading.builder()
                .factoryId(FACTORY_ID).equipmentId(EQ_ID)
                .recordedAt(now)
                .temperature(new BigDecimal("-19.0"))
                .isDeviation(false).build();
        when(readingRepository.findByEquipmentIdAndRecordedAtAfterOrderByRecordedAtAsc(
                eq(EQ_ID), any())).thenReturn(List.of(r1, r2, r3));

        Map<String, Object> params = Map.of("equipmentId", EQ_ID, "hours", 24);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(EQ_ID, data.get("equipmentId"));
        assertEquals(3, data.get("readingCount"));
        assertEquals(new BigDecimal("-20.0"), data.get("observedMin"));
        assertEquals(new BigDecimal("-15.0"), data.get("observedMax"));
        assertEquals(1, data.get("deviationCount"));
        List<Map<String, Object>> curve = (List<Map<String, Object>>) data.get("curve");
        assertEquals(3, curve.size());
    }

    @Test
    @DisplayName("UT-CCTQ-03: empty readings → R5 friendly message (no dead-end)")
    @SuppressWarnings("unchecked")
    void executeEmpty() throws Exception {
        ColdChainEquipment eq = ColdChainEquipment.builder()
                .id(EQ_ID).factoryId(FACTORY_ID)
                .equipmentCode("FREEZER-A01").equipmentName("1 号冰柜")
                .type("FREEZER")
                .targetTempMin(new BigDecimal("-22.0"))
                .targetTempMax(new BigDecimal("-18.0"))
                .unit("℃").toleranceMinutes(15)
                .build();
        when(equipmentRepository.findById(EQ_ID)).thenReturn(Optional.of(eq));
        when(readingRepository.findByEquipmentIdAndRecordedAtAfterOrderByRecordedAtAsc(
                anyLong(), any())).thenReturn(List.of());

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("equipmentId", EQ_ID), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(0, data.get("readingCount"));
        assertNull(data.get("avg"));
        String message = (String) result.get("message");
        assertTrue(message.contains("暂无温度读数"));
        assertTrue(message.contains("IoT") || message.contains("配置"),
                "Should include next-action hint per R5");
    }

    @Test
    @DisplayName("UT-CCTQ-04: equipment not found → 404")
    void executeEquipmentNotFound() {
        when(equipmentRepository.findById(EQ_ID)).thenReturn(Optional.empty());
        Map<String, Object> params = Map.of("equipmentId", EQ_ID);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("UT-CCTQ-05: no equipmentId/code → 400")
    void executeNoIdentifier() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, Map.of(), ctx()));
        assertEquals(400, ex.getCode());
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
