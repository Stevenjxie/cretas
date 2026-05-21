package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.SsopProcedure;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.SsopProcedureRepository;
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
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Unit tests for {@link SsopProcedureCreateTool} (Sprint 9 P2.E Phase B). */
@ExtendWith(MockitoExtension.class)
class SsopProcedureCreateToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private SsopProcedureCreateTool tool;

    @Mock
    private SsopProcedureRepository procedureRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-SPC-01: metadata + supportsPreview=true + WRITE")
    void metadata() {
        assertEquals("ssop_procedure_create", tool.getToolName());
        assertTrue(tool.supportsPreview());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.RiskLevel.MEDIUM, tool.getRiskLevel());
    }

    @Test
    @DisplayName("UT-SPC-02: doPreview — valid input → canDo=true")
    @SuppressWarnings("unchecked")
    void previewValid() throws Exception {
        when(procedureRepository.findByFactoryIdAndProcedureCode(FACTORY_ID, "SSOP-DAILY-LINE-A"))
                .thenReturn(Optional.empty());
        when(procedureRepository.countByFactoryIdAndActiveTrue(FACTORY_ID)).thenReturn(5L);

        Map<String, Object> params = Map.of(
                "procedureCode", "SSOP-DAILY-LINE-A",
                "name", "生产线 A 每日开工前消毒",
                "target", "EQUIPMENT",
                "frequency", "DAILY",
                "inspectorRole", "quality_mgr");
        Map<String, Object> result = invoke("doPreview", params);
        assertEquals(true, result.get("canDo"));
        assertEquals("PREVIEW", result.get("status"));
        assertEquals(5L, result.get("currentActiveProcedures"));
    }

    @Test
    @DisplayName("UT-SPC-03: doPreview — invalid target enum → canDo=false")
    @SuppressWarnings("unchecked")
    void previewInvalidTarget() throws Exception {
        Map<String, Object> params = Map.of(
                "procedureCode", "SSOP-X",
                "name", "X",
                "target", "BOGUS",
                "frequency", "DAILY",
                "inspectorRole", "quality_mgr");
        Map<String, Object> result = invoke("doPreview", params);
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-SPC-04: doPreview — duplicate procedureCode → status=DUPLICATE")
    @SuppressWarnings("unchecked")
    void previewDuplicate() throws Exception {
        SsopProcedure existing = SsopProcedure.builder()
                .id(99L).name("已存在").build();
        when(procedureRepository.findByFactoryIdAndProcedureCode(FACTORY_ID, "SSOP-DUP"))
                .thenReturn(Optional.of(existing));

        Map<String, Object> params = Map.of(
                "procedureCode", "SSOP-DUP",
                "name", "X",
                "target", "EQUIPMENT",
                "frequency", "DAILY",
                "inspectorRole", "quality_mgr");
        Map<String, Object> result = invoke("doPreview", params);
        assertEquals(false, result.get("canDo"));
        assertEquals("DUPLICATE", result.get("status"));
        assertEquals(99L, result.get("existingId"));
    }

    @Test
    @DisplayName("UT-SPC-05: doExecute — valid input → save called, id returned")
    @SuppressWarnings("unchecked")
    void executeValid() throws Exception {
        when(procedureRepository.findByFactoryIdAndProcedureCode(FACTORY_ID, "SSOP-NEW"))
                .thenReturn(Optional.empty());
        when(procedureRepository.save(any(SsopProcedure.class)))
                .thenAnswer(inv -> {
                    SsopProcedure p = inv.getArgument(0);
                    p.setId(42L);
                    return p;
                });

        Map<String, Object> params = Map.of(
                "procedureCode", "SSOP-NEW",
                "name", "新模板",
                "target", "EMPLOYEE",
                "frequency", "SHIFT",
                "inspectorRole", "quality_mgr");
        Map<String, Object> result = invoke("doExecute", params);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(42L, data.get("id"));
        verify(procedureRepository, times(1)).save(any(SsopProcedure.class));
    }

    @Test
    @DisplayName("UT-SPC-06: doExecute — invalid frequency → BusinessException 400")
    void executeInvalidFrequency() {
        Map<String, Object> params = Map.of(
                "procedureCode", "SSOP-X",
                "name", "X",
                "target", "EQUIPMENT",
                "frequency", "INVALID",
                "inspectorRole", "x");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", params));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("UT-SPC-07: doExecute — duplicate → no save, count=0")
    @SuppressWarnings("unchecked")
    void executeDuplicate() throws Exception {
        SsopProcedure existing = SsopProcedure.builder().id(7L).build();
        when(procedureRepository.findByFactoryIdAndProcedureCode(FACTORY_ID, "SSOP-DUP"))
                .thenReturn(Optional.of(existing));

        Map<String, Object> params = Map.of(
                "procedureCode", "SSOP-DUP",
                "name", "X",
                "target", "EQUIPMENT",
                "frequency", "DAILY",
                "inspectorRole", "quality_mgr");
        Map<String, Object> result = invoke("doExecute", params);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("DUPLICATE", data.get("status"));
        assertEquals(0, data.get("count"));
        verify(procedureRepository, never()).save(any());
    }

    // ── helpers ──
    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String name, Map<String, Object> params) throws Exception {
        Method method = findMethod(tool.getClass(), name);
        method.setAccessible(true);
        try {
            return (Map<String, Object>) method.invoke(tool, FACTORY_ID, params, ctx());
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }

    private Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            for (Method m : clazz.getDeclaredMethods()) {
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
