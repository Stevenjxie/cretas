package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.ColdChainEquipment;
import com.cretas.aims.exception.BusinessException;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ColdChainEquipmentRegisterTool} (Sprint 9 P2.D). */
@ExtendWith(MockitoExtension.class)
class ColdChainEquipmentRegisterToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String CODE = "FREEZER-A01";

    @InjectMocks
    private ColdChainEquipmentRegisterTool tool;

    @Mock
    private ColdChainEquipmentRepository equipmentRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-CCER-01: metadata — WRITE + Preview + MEDIUM")
    void metadata() {
        assertEquals("cold_chain_equipment_register", tool.getToolName());
        assertTrue(tool.supportsPreview());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.RiskLevel.MEDIUM, tool.getRiskLevel());
    }

    @Test
    @DisplayName("UT-CCER-02: doExecute — creates active equipment with tolerance default 15")
    @SuppressWarnings("unchecked")
    void executeCreatesEquipment() throws Exception {
        when(equipmentRepository.findByFactoryIdAndEquipmentCode(eq(FACTORY_ID), eq(CODE)))
                .thenReturn(Optional.empty());
        when(equipmentRepository.save(any(ColdChainEquipment.class))).thenAnswer(inv -> {
            ColdChainEquipment e = inv.getArgument(0);
            e.setId(42L);
            return e;
        });

        Map<String, Object> params = Map.of(
                "equipmentCode", CODE,
                "equipmentName", "1 号冰柜",
                "type", "FREEZER",
                "targetTempMin", new BigDecimal("-22.0"),
                "targetTempMax", new BigDecimal("-18.0")
        );
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(42L, data.get("id"));
        assertEquals(CODE, data.get("equipmentCode"));
        assertEquals("FREEZER", data.get("type"));
        assertEquals(15, data.get("toleranceMinutes"));
        assertTrue((Boolean) data.get("active"));
        verify(equipmentRepository, times(1)).save(any(ColdChainEquipment.class));
    }

    @Test
    @DisplayName("UT-CCER-03: R4 dedup — equipmentCode 已存在 → returns count=0 + existingId")
    @SuppressWarnings("unchecked")
    void executeDedup() throws Exception {
        ColdChainEquipment existing = ColdChainEquipment.builder()
                .id(7L).factoryId(FACTORY_ID).equipmentCode(CODE).build();
        when(equipmentRepository.findByFactoryIdAndEquipmentCode(eq(FACTORY_ID), eq(CODE)))
                .thenReturn(Optional.of(existing));

        Map<String, Object> params = Map.of(
                "equipmentCode", CODE,
                "equipmentName", "duplicate",
                "type", "FREEZER",
                "targetTempMin", new BigDecimal("-22.0"),
                "targetTempMax", new BigDecimal("-18.0")
        );
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("DUPLICATE", data.get("status"));
        assertEquals(7L, data.get("existingId"));
        assertEquals(0, data.get("count"));
        verify(equipmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT-CCER-04: R3 enum invalid type → BusinessException 400")
    void executeInvalidType() {
        Map<String, Object> params = Map.of(
                "equipmentCode", CODE,
                "equipmentName", "n",
                "type", "INVALID_TYPE",
                "targetTempMin", new BigDecimal("0"),
                "targetTempMax", new BigDecimal("8")
        );
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("UT-CCER-05: R3 temp range tempMin >= tempMax → BusinessException 400")
    void executeBadRange() {
        when(equipmentRepository.findByFactoryIdAndEquipmentCode(anyString(), anyString()))
                .thenReturn(Optional.empty());
        Map<String, Object> params = Map.of(
                "equipmentCode", CODE,
                "equipmentName", "n",
                "type", "REFRIGERATOR",
                "targetTempMin", new BigDecimal("10"),  // >= max
                "targetTempMax", new BigDecimal("8")
        );
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("targetTempMin"));
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
