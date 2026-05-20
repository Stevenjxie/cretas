package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.CallRecord;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.CallType;
import com.cretas.aims.repository.CallRecordRepository;
import com.cretas.aims.repository.CustomerRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CallRecordFollowupPendingTool} (Sprint 8 P1).
 */
@ExtendWith(MockitoExtension.class)
class CallRecordFollowupPendingToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private CallRecordFollowupPendingTool tool;

    @Mock
    private CallRecordRepository callRecordRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-CFP-01: Tool metadata")
    void metadata() {
        assertEquals("call_record_followup_pending", tool.getToolName());
        assertTrue(tool.getDescription().contains("MISSED"));
        assertTrue(tool.getRequiredParameters().isEmpty());
    }

    @Test
    @DisplayName("UT-CFP-02: filter callType=MISSED — INCOMING/OUTGOING excluded")
    @SuppressWarnings("unchecked")
    void filterByCallType() throws Exception {
        CallRecord missed = call(1L, CallType.MISSED, LocalDateTime.now().minusDays(2));
        CallRecord incoming = call(2L, CallType.INCOMING, LocalDateTime.now().minusDays(2));
        when(callRecordRepository
                .findByFactoryIdAndDeletedAtIsNullOrderByCallTimeDesc(anyString()))
                .thenReturn(List.of(missed, incoming));
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID,
                Map.of("callType", "MISSED"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        assertEquals(1, records.size());
        assertEquals("MISSED", records.get(0).get("callType"));
    }

    @Test
    @DisplayName("UT-CFP-03: filter daysSince — old records excluded")
    @SuppressWarnings("unchecked")
    void filterByDaysSince() throws Exception {
        CallRecord recent = call(1L, CallType.MISSED, LocalDateTime.now().minusDays(2));
        CallRecord old = call(2L, CallType.MISSED, LocalDateTime.now().minusDays(30));
        when(callRecordRepository
                .findByFactoryIdAndDeletedAtIsNullOrderByCallTimeDesc(anyString()))
                .thenReturn(List.of(recent, old));
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID,
                Map.of("daysSince", 7), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        assertEquals(1, records.size());
        assertEquals(1L, records.get(0).get("id"));
    }

    @Test
    @DisplayName("UT-CFP-04: invalid callType param falls back to MISSED")
    @SuppressWarnings("unchecked")
    void invalidCallTypeFallback() throws Exception {
        when(callRecordRepository
                .findByFactoryIdAndDeletedAtIsNullOrderByCallTimeDesc(anyString()))
                .thenReturn(List.of());

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID,
                Map.of("callType", "BANANA"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("MISSED", data.get("callType"));
    }

    // ── helpers ──
    private CallRecord call(Long id, CallType type, LocalDateTime time) {
        return CallRecord.builder().id(id).factoryId(FACTORY_ID).customerId("c1")
                .callType(type).callTime(time).durationSec(0)
                .createdBy(1L).build();
    }

    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoExecute(Object tool, String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        var method = findMethod(tool.getClass(), "doExecute");
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
