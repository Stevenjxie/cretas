package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.WechatRecord;
import com.cretas.aims.entity.enums.WechatDirection;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.WechatRecordRepository;
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
 * Unit tests for {@link WechatRecordRecentQueryTool} (Sprint 8 P1).
 */
@ExtendWith(MockitoExtension.class)
class WechatRecordRecentQueryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private WechatRecordRecentQueryTool tool;

    @Mock
    private WechatRecordRepository wechatRecordRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-WCQ-01: Tool metadata")
    void metadata() {
        assertEquals("wechat_record_recent_query", tool.getToolName());
        assertTrue(tool.getDescription().contains("微信"));
        assertTrue(tool.getRequiredParameters().isEmpty());
    }

    @Test
    @DisplayName("UT-WCQ-02: doExecute — happy path, returns list with R2 customerName")
    @SuppressWarnings("unchecked")
    void happyPath() throws Exception {
        WechatRecord r = WechatRecord.builder()
                .id(1L).factoryId(FACTORY_ID).customerId("c1")
                .direction(WechatDirection.INBOUND)
                .messageContent("客户问报价")
                .recordTime(LocalDateTime.now().minusDays(2))
                .createdBy(1L).createdByName("销售A")
                .build();
        when(wechatRecordRepository
                .findByFactoryIdAndDeletedAtIsNullOrderByRecordTimeDesc(anyString()))
                .thenReturn(List.of(r));
        when(customerRepository.findByIdAndFactoryId(eq("c1"), anyString()))
                .thenReturn(Optional.of(newCustomer("c1", "张老板")));

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        assertEquals(1, records.size());
        assertEquals("张老板", records.get(0).get("customerName"));
        assertEquals("INBOUND", records.get(0).get("direction"));
        assertNotNull(records.get(0).get("daysAgo"));
    }

    @Test
    @DisplayName("UT-WCQ-03: customer name resolution failure → null (not crash)")
    @SuppressWarnings("unchecked")
    void customerNameResolutionFailure() throws Exception {
        WechatRecord r = WechatRecord.builder()
                .id(1L).factoryId(FACTORY_ID).customerId("c-unknown")
                .direction(WechatDirection.OUTBOUND)
                .messageContent("hi")
                .recordTime(LocalDateTime.now().minusHours(1))
                .createdBy(1L).build();
        when(wechatRecordRepository
                .findByFactoryIdAndDeletedAtIsNullOrderByRecordTimeDesc(anyString()))
                .thenReturn(List.of(r));
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        assertEquals(1, records.size());
        assertNull(records.get(0).get("customerName"));
    }

    @Test
    @DisplayName("UT-WCQ-04: filter daysSince — excludes old records")
    @SuppressWarnings("unchecked")
    void daysSinceFilter() throws Exception {
        WechatRecord recent = WechatRecord.builder()
                .id(1L).factoryId(FACTORY_ID).customerId("c1")
                .direction(WechatDirection.INBOUND).messageContent("近期")
                .recordTime(LocalDateTime.now().minusDays(2))
                .createdBy(1L).build();
        WechatRecord old = WechatRecord.builder()
                .id(2L).factoryId(FACTORY_ID).customerId("c1")
                .direction(WechatDirection.INBOUND).messageContent("老旧")
                .recordTime(LocalDateTime.now().minusDays(30))
                .createdBy(1L).build();
        when(wechatRecordRepository
                .findByFactoryIdAndDeletedAtIsNullOrderByRecordTimeDesc(anyString()))
                .thenReturn(List.of(recent, old));
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID,
                Map.of("daysSince", 7), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        assertEquals(1, records.size(), "old record should be excluded");
        assertEquals(1L, records.get(0).get("id"));
    }

    // ── helpers ──
    private Customer newCustomer(String id, String name) {
        Customer c = new Customer();
        c.setId(id);
        c.setFactoryId(FACTORY_ID);
        c.setCode("CC");
        c.setCustomerCode("CC");
        c.setName(name);
        return c;
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
