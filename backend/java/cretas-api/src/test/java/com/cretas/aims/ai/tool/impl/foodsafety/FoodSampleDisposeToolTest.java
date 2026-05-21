package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.FoodSample;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.FoodSampleRepository;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link FoodSampleDisposeTool} (Sprint 9 P2.A). */
@ExtendWith(MockitoExtension.class)
class FoodSampleDisposeToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private FoodSampleDisposeTool tool;

    @Mock
    private FoodSampleRepository foodSampleRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    private FoodSample sample(String status) {
        return FoodSample.builder()
                .id(123L)
                .factoryId(FACTORY_ID)
                .batchNumber("B-1")
                .productName("卤猪蹄")
                .sampleQuantity(new BigDecimal("130"))
                .sampleTime(LocalDateTime.now().minusHours(50))
                .expireTime(LocalDateTime.now().minusHours(2))
                .status(status)
                .build();
    }

    @Test
    @DisplayName("UT-FSD-01: metadata — WRITE + Preview + HIGH risk")
    void metadata() {
        assertEquals("food_sample_dispose", tool.getToolName());
        assertTrue(tool.supportsPreview());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.RiskLevel.HIGH, tool.getRiskLevel());
    }

    @Test
    @DisplayName("UT-FSD-02: doExecute SCHEDULED → DISPOSED, audit fields set")
    @SuppressWarnings("unchecked")
    void executeScheduled() throws Exception {
        FoodSample s = sample("RETAINED");
        when(foodSampleRepository.findById(123L)).thenReturn(Optional.of(s));

        Map<String, Object> params = Map.of("id", 123L, "reason", "SCHEDULED");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("RETAINED", data.get("previousStatus"));
        assertEquals("DISPOSED", data.get("currentStatus"));
        assertEquals("DISPOSED", s.getStatus());
        assertNotNull(s.getDisposedAt());
        assertEquals(1L, s.getDisposedByUserId());
        assertEquals("SCHEDULED", s.getDisposeReason());
        verify(foodSampleRepository, times(1)).save(any(FoodSample.class));
    }

    @Test
    @DisplayName("UT-FSD-03: doExecute INSPECTION → USED_FOR_INSPECTION")
    @SuppressWarnings("unchecked")
    void executeInspection() throws Exception {
        FoodSample s = sample("RETAINED");
        when(foodSampleRepository.findById(123L)).thenReturn(Optional.of(s));

        Map<String, Object> params = Map.of(
                "id", 123L,
                "reason", "INSPECTION",
                "notes", "客户张三投诉送疾控检测");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("USED_FOR_INSPECTION", data.get("currentStatus"));
        assertEquals("USED_FOR_INSPECTION", s.getStatus());
        assertTrue(s.getDisposeReason().contains("INSPECTION"));
        assertTrue(s.getDisposeReason().contains("客户张三"));
    }

    @Test
    @DisplayName("UT-FSD-04: R4 already DISPOSED → ALREADY_DISPOSED, no save")
    @SuppressWarnings("unchecked")
    void executeAlreadyDisposed() throws Exception {
        FoodSample s = sample("DISPOSED");
        s.setDisposedAt(LocalDateTime.now().minusHours(1));
        when(foodSampleRepository.findById(123L)).thenReturn(Optional.of(s));

        Map<String, Object> params = Map.of("id", 123L, "reason", "SCHEDULED");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("ALREADY_DISPOSED", data.get("status"));
        verify(foodSampleRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT-FSD-05: invalid reason → BusinessException 400")
    void executeBadReason() {
        Map<String, Object> params = Map.of("id", 123L, "reason", "INVALID_X");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("UT-FSD-06: not found → BusinessException 404")
    void executeNotFound() {
        when(foodSampleRepository.findById(anyLong())).thenReturn(Optional.empty());
        Map<String, Object> params = Map.of("id", 999L, "reason", "SCHEDULED");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("UT-FSD-07: wrong factory → BusinessException 403")
    void executeWrongFactory() {
        FoodSample s = sample("RETAINED");
        s.setFactoryId("F999"); // 不属于当前 factory
        when(foodSampleRepository.findById(123L)).thenReturn(Optional.of(s));
        Map<String, Object> params = Map.of("id", 123L, "reason", "SCHEDULED");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(403, ex.getCode());
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
