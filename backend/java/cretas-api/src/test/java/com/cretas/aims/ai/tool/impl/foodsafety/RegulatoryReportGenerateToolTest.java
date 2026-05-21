package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.RecallAction;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.RecallActionRepository;
import com.cretas.aims.repository.foodsafety.RecallEventRepository;
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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RegulatoryReportGenerateTool} (Sprint 8 P3 Phase B). */
@ExtendWith(MockitoExtension.class)
class RegulatoryReportGenerateToolTest {

    private static final String FACTORY_ID = "F006";
    private static final Long EVENT_ID = 100L;

    @InjectMocks
    private RegulatoryReportGenerateTool tool;

    @Mock
    private RecallEventRepository recallEventRepository;

    @Mock
    private RecallActionRepository recallActionRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-RR-01: metadata + GENERATE action")
    void metadata() {
        assertEquals("regulatory_report_generate", tool.getToolName());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.GENERATE, tool.getActionType());
    }

    @Test
    @DisplayName("UT-RR-02: doExecute — generates markdown report + saves RecallAction + REPORTED")
    @SuppressWarnings("unchecked")
    void executeGeneratesReport() throws Exception {
        RecallEvent event = RecallEvent.builder()
                .id(EVENT_ID).factoryId(FACTORY_ID).eventCode("RECALL-20260518-001")
                .triggerReason("客户投诉").triggerTime(LocalDateTime.now())
                .triggeredByUserId(1L)
                .affectedProductCategory("卤猪蹄")
                .status("NOTIFYING")
                .build();
        when(recallEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(recallActionRepository.findByRecallEventIdOrderByCreatedAtAsc(EVENT_ID))
                .thenReturn(List.of());
        when(recallActionRepository.save(any(RecallAction.class)))
                .thenAnswer(inv -> {
                    RecallAction a = inv.getArgument(0);
                    a.setId(888L);
                    return a;
                });

        Map<String, Object> params = Map.of("recallEventId", EVENT_ID);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        String md = (String) data.get("reportMarkdown");
        assertNotNull(md);
        assertTrue(md.contains("RECALL-20260518-001"));
        assertTrue(md.contains("食品召回事件监管上报报告"));
        assertTrue(md.contains("食品安全法"));

        verify(recallActionRepository, times(1)).save(any(RecallAction.class));
        verify(recallEventRepository, times(1)).save(any(RecallEvent.class));
        assertEquals("REPORTED", event.getStatus());
    }

    @Test
    @DisplayName("UT-RR-03: doExecute — event not found → BusinessException 404")
    void executeNotFound() {
        when(recallEventRepository.findById(anyLong())).thenReturn(Optional.empty());
        Map<String, Object> params = Map.of("recallEventId", 99999L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("UT-RR-04: doExecute — wrong factory → BusinessException 403")
    void wrongFactory() {
        RecallEvent event = RecallEvent.builder()
                .id(EVENT_ID).factoryId("F999").eventCode("RECALL-X")
                .triggerReason("X").triggerTime(LocalDateTime.now()).triggeredByUserId(1L)
                .affectedProductCategory("X").build();
        when(recallEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        Map<String, Object> params = Map.of("recallEventId", EVENT_ID);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(403, ex.getCode());
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
