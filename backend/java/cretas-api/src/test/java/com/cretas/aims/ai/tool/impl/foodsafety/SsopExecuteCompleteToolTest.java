package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.SsopExecutionRecord;
import com.cretas.aims.entity.foodsafety.SsopProcedure;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.SsopExecutionRecordRepository;
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
import static org.mockito.Mockito.*;

/** Unit tests for {@link SsopExecuteCompleteTool} (Sprint 9 P2.E Phase B). */
@ExtendWith(MockitoExtension.class)
class SsopExecuteCompleteToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private SsopExecuteCompleteTool tool;

    @Mock
    private SsopExecutionRecordRepository recordRepository;

    @Mock
    private SsopProcedureRepository procedureRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-SEC-01: metadata + supportsPreview=true + WRITE")
    void metadata() {
        assertEquals("ssop_execute_complete", tool.getToolName());
        assertTrue(tool.supportsPreview());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.WRITE, tool.getActionType());
    }

    @Test
    @DisplayName("UT-SEC-02: doPreview — SCHEDULED → COMPLETED with inspector OK")
    @SuppressWarnings("unchecked")
    void previewValid() throws Exception {
        SsopExecutionRecord rec = SsopExecutionRecord.builder()
                .id(1L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("MORNING").status("SCHEDULED").build();
        when(recordRepository.findById(1L)).thenReturn(Optional.of(rec));
        when(procedureRepository.findById(100L))
                .thenReturn(Optional.of(SsopProcedure.builder()
                        .id(100L).name("生产线 A 消毒").procedureCode("SSOP-A")
                        .target("EQUIPMENT").inspectorRole("quality_mgr").build()));

        Map<String, Object> result = invoke("doPreview", Map.of(
                "recordId", 1L,
                "resultStatus", "COMPLETED",
                "inspectorUserId", 99L));
        assertEquals(true, result.get("canDo"));
        assertEquals("生产线 A 消毒", result.get("procedureName"));
    }

    @Test
    @DisplayName("UT-SEC-03: doPreview — already COMPLETED → status=ALREADY_DONE")
    @SuppressWarnings("unchecked")
    void previewAlreadyDone() throws Exception {
        SsopExecutionRecord rec = SsopExecutionRecord.builder()
                .id(1L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("MORNING").status("COMPLETED").build();
        when(recordRepository.findById(1L)).thenReturn(Optional.of(rec));

        Map<String, Object> result = invoke("doPreview", Map.of(
                "recordId", 1L, "resultStatus", "COMPLETED",
                "inspectorUserId", 99L));
        assertEquals(false, result.get("canDo"));
        assertEquals("ALREADY_DONE", result.get("status"));
    }

    @Test
    @DisplayName("UT-SEC-04: doPreview — COMPLETED w/o inspector → canDo=false")
    @SuppressWarnings("unchecked")
    void previewMissingInspector() throws Exception {
        SsopExecutionRecord rec = SsopExecutionRecord.builder()
                .id(1L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("MORNING").status("SCHEDULED").build();
        when(recordRepository.findById(1L)).thenReturn(Optional.of(rec));
        when(procedureRepository.findById(100L))
                .thenReturn(Optional.of(SsopProcedure.builder().id(100L).build()));

        Map<String, Object> result = invoke("doPreview", Map.of(
                "recordId", 1L, "resultStatus", "COMPLETED"));
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-SEC-05: doExecute — happy path COMPLETED → save called, status updated")
    @SuppressWarnings("unchecked")
    void executeValid() throws Exception {
        SsopExecutionRecord rec = SsopExecutionRecord.builder()
                .id(1L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("MORNING").status("SCHEDULED").build();
        when(recordRepository.findById(1L)).thenReturn(Optional.of(rec));
        when(procedureRepository.findById(100L))
                .thenReturn(Optional.of(SsopProcedure.builder()
                        .id(100L).name("X").procedureCode("X-1").build()));
        when(recordRepository.save(any(SsopExecutionRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = invoke("doExecute", Map.of(
                "recordId", 1L,
                "resultStatus", "COMPLETED",
                "inspectorUserId", 99L));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("COMPLETED", data.get("status"));
        assertEquals(99L, data.get("inspectorUserId"));
        verify(recordRepository, times(1)).save(any(SsopExecutionRecord.class));
    }

    @Test
    @DisplayName("UT-SEC-06: doExecute — FAILED w/o notes → BusinessException 400")
    void executeFailedMissingNotes() {
        SsopExecutionRecord rec = SsopExecutionRecord.builder()
                .id(1L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("MORNING").status("SCHEDULED").build();
        when(recordRepository.findById(1L)).thenReturn(Optional.of(rec));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", Map.of(
                        "recordId", 1L, "resultStatus", "FAILED",
                        "inspectorUserId", 99L)));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("UT-SEC-07: doExecute — wrong factoryId → 404")
    void executeWrongFactory() {
        SsopExecutionRecord rec = SsopExecutionRecord.builder()
                .id(1L).factoryId("F099").procedureId(100L)
                .shift("MORNING").status("SCHEDULED").build();
        when(recordRepository.findById(1L)).thenReturn(Optional.of(rec));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", Map.of(
                        "recordId", 1L, "resultStatus", "COMPLETED",
                        "inspectorUserId", 99L)));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("UT-SEC-08: doExecute — already final → no save, count=0")
    @SuppressWarnings("unchecked")
    void executeAlreadyDone() throws Exception {
        SsopExecutionRecord rec = SsopExecutionRecord.builder()
                .id(1L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("MORNING").status("SKIPPED").build();
        when(recordRepository.findById(1L)).thenReturn(Optional.of(rec));

        Map<String, Object> result = invoke("doExecute", Map.of(
                "recordId", 1L, "resultStatus", "COMPLETED",
                "inspectorUserId", 99L));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("ALREADY_DONE", data.get("status"));
        assertEquals(0, data.get("count"));
        verify(recordRepository, never()).save(any());
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
