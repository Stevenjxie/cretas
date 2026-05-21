package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.ColdChainAlert;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.ColdChainAlertRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

/** Unit tests for {@link ColdChainAlertAcknowledgeTool} (Sprint 9 P2.D). */
@ExtendWith(MockitoExtension.class)
class ColdChainAlertAcknowledgeToolTest {

    private static final String FACTORY_ID = "F006";
    private static final Long ALERT_ID = 42L;

    @InjectMocks
    private ColdChainAlertAcknowledgeTool tool;

    @Mock
    private ColdChainAlertRepository alertRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-CCAack-01: metadata — WRITE + Preview + MEDIUM")
    void metadata() {
        assertEquals("cold_chain_alert_acknowledge", tool.getToolName());
        assertTrue(tool.supportsPreview());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.RiskLevel.MEDIUM, tool.getRiskLevel());
    }

    @Test
    @DisplayName("UT-CCAack-02: OPEN → ACKNOWLEDGED with TEMPORARY_DOOR_OPEN reason")
    @SuppressWarnings("unchecked")
    void executeAcknowledge() throws Exception {
        ColdChainAlert a = ColdChainAlert.builder()
                .id(ALERT_ID).factoryId(FACTORY_ID).equipmentId(101L)
                .alertTime(LocalDateTime.now().minusMinutes(20))
                .severity("WARNING")
                .minTemp(new BigDecimal("9.0"))
                .maxTemp(new BigDecimal("11.0"))
                .durationMinutes(15)
                .status("OPEN").build();
        when(alertRepository.findById(ALERT_ID)).thenReturn(Optional.of(a));
        when(alertRepository.save(any(ColdChainAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> params = Map.of(
                "id", ALERT_ID,
                "ackReason", "TEMPORARY_DOOR_OPEN"
        );
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(ALERT_ID, data.get("id"));
        assertEquals("OPEN", data.get("previousStatus"));
        assertEquals("ACKNOWLEDGED", data.get("currentStatus"));
        assertEquals("TEMPORARY_DOOR_OPEN", data.get("ackReason"));
        ArgumentCaptor<ColdChainAlert> captor = ArgumentCaptor.forClass(ColdChainAlert.class);
        verify(alertRepository, times(1)).save(captor.capture());
        assertEquals("ACKNOWLEDGED", captor.getValue().getStatus());
        assertNotNull(captor.getValue().getAcknowledgedAt());
        assertEquals(1L, captor.getValue().getAcknowledgedByUserId());
    }

    @Test
    @DisplayName("UT-CCAack-03: R3 OTHER without ackRemark → BusinessException 400")
    void executeOtherWithoutRemark() {
        Map<String, Object> params = Map.of(
                "id", ALERT_ID,
                "ackReason", "OTHER"
        );
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("ackRemark"));
    }

    @Test
    @DisplayName("UT-CCAack-04: R3 invalid enum → BusinessException 400")
    void executeInvalidReason() {
        Map<String, Object> params = Map.of(
                "id", ALERT_ID,
                "ackReason", "NOT_AN_ENUM"
        );
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("UT-CCAack-05: R4 already ACKNOWLEDGED → count=0 no save")
    @SuppressWarnings("unchecked")
    void executeAlreadyAcknowledged() throws Exception {
        ColdChainAlert a = ColdChainAlert.builder()
                .id(ALERT_ID).factoryId(FACTORY_ID).equipmentId(101L)
                .alertTime(LocalDateTime.now().minusHours(2))
                .severity("WARNING")
                .minTemp(new BigDecimal("9.0")).maxTemp(new BigDecimal("11.0"))
                .durationMinutes(15)
                .status("ACKNOWLEDGED")   // already
                .acknowledgedAt(LocalDateTime.now().minusHours(1))
                .build();
        when(alertRepository.findById(ALERT_ID)).thenReturn(Optional.of(a));

        Map<String, Object> params = Map.of(
                "id", ALERT_ID,
                "ackReason", "MAINTENANCE"
        );
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("ALREADY_ACKNOWLEDGED", data.get("status"));
        assertEquals(0, data.get("count"));
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT-CCAack-06: alert not found → 404")
    void executeNotFound() {
        when(alertRepository.findById(anyLong())).thenReturn(Optional.empty());
        Map<String, Object> params = Map.of(
                "id", ALERT_ID,
                "ackReason", "POWER_FLUCTUATION"
        );
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(404, ex.getCode());
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
