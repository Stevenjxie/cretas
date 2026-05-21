package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.WechatRecord;
import com.cretas.aims.entity.enums.WechatDirection;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.WechatRecordRepository;
import com.cretas.aims.service.WechatRecordService;
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
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WechatRecordCreateTool} (Sprint 8 P1).
 *
 * Covers: metadata, R4 dedup preview, happy path execute, 409 service-side dedup.
 */
@ExtendWith(MockitoExtension.class)
class WechatRecordCreateToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private WechatRecordCreateTool tool;

    @Mock
    private WechatRecordService wechatRecordService;

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
    @DisplayName("UT-WCC-01: Tool metadata — WRITE / MEDIUM / supportsPreview=true / 3 required")
    void metadata() {
        assertEquals("wechat_record_create", tool.getToolName());
        assertEquals(ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(ToolExecutor.RiskLevel.MEDIUM, tool.getRiskLevel());
        assertTrue(tool.supportsPreview());
        assertEquals(3, tool.getRequiredParameters().size());
        assertTrue(tool.getRequiredParameters().contains("customerId"));
        assertTrue(tool.getRequiredParameters().contains("direction"));
        assertTrue(tool.getRequiredParameters().contains("messageContent"));
    }

    @Test
    @DisplayName("UT-WCC-02: doPreview — R4 dedup hit → DUPLICATE + actionHint, does NOT call service.create")
    void previewDedupHit() throws Exception {
        WechatRecord existing = WechatRecord.builder().id(999L).factoryId(FACTORY_ID)
                .customerId("c1").direction(WechatDirection.OUTBOUND)
                .messageContent("已存在").recordTime(LocalDateTime.now().minusMinutes(2))
                .createdBy(1L).build();
        when(wechatRecordRepository.findRecentByContent(anyString(), anyString(),
                anyString(), any(LocalDateTime.class)))
                .thenReturn(List.of(existing));
        when(customerRepository.findByIdAndFactoryId(eq("c1"), anyString()))
                .thenReturn(Optional.of(newCustomer()));

        Map<String, Object> params = Map.of(
                "customerId", "c1",
                "direction", "OUTBOUND",
                "messageContent", "已存在");

        Map<String, Object> result = invokeDoPreview(tool, FACTORY_ID, params, ctx());
        assertEquals("DUPLICATE", result.get("status"));
        assertEquals(false, result.get("canDo"));
        assertEquals(999L, result.get("existingId"));
        assertNotNull(result.get("actionHint"));
        assertTrue(result.get("actionHint").toString().contains("highlight=999"));
        assertTrue(result.get("message").toString().contains("张老板"));

        // R1 verified: preview does NOT call service.create
        verify(wechatRecordService, never())
                .create(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("UT-WCC-03: doPreview — no dedup hit → canDo=true with R2 context message")
    void previewClean() throws Exception {
        when(wechatRecordRepository.findRecentByContent(anyString(), anyString(),
                anyString(), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(customerRepository.findByIdAndFactoryId(eq("c1"), anyString()))
                .thenReturn(Optional.of(newCustomer()));

        Map<String, Object> params = Map.of(
                "customerId", "c1",
                "direction", "OUTBOUND",
                "messageContent", "新消息");

        Map<String, Object> result = invokeDoPreview(tool, FACTORY_ID, params, ctx());
        assertEquals("PREVIEW", result.get("status"));
        assertEquals(true, result.get("canDo"));
        assertTrue(result.get("message").toString().contains("张老板"));
        assertTrue(result.get("message").toString().contains("新消息"));
    }

    @Test
    @DisplayName("UT-WCC-04: doExecute — happy path returns id + R2 context message")
    void executeHappyPath() throws Exception {
        WechatRecord saved = WechatRecord.builder().id(7L).factoryId(FACTORY_ID)
                .customerId("c1").direction(WechatDirection.OUTBOUND)
                .messageContent("跟客户讨论价格").recordTime(LocalDateTime.now())
                .createdBy(1L).build();
        when(wechatRecordService.create(anyString(), anyString(), any(WechatDirection.class),
                anyString(), any(Long.class), any(), any(), any(), any()))
                .thenReturn(saved);
        when(customerRepository.findByIdAndFactoryId(eq("c1"), anyString()))
                .thenReturn(Optional.of(newCustomer()));

        Map<String, Object> params = Map.of(
                "customerId", "c1",
                "direction", "OUTBOUND",
                "messageContent", "跟客户讨论价格");

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID, params, ctx());
        assertTrue(result.get("message").toString().contains("张老板"));
        assertTrue(result.get("message").toString().contains("id=7"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(7L, data.get("id"));
    }

    @Test
    @DisplayName("UT-WCC-05: doExecute — service throws 409 → DUPLICATE status struct")
    void executeServiceLayerDedup() throws Exception {
        BusinessException e409 = new BusinessException(409,
                "5 分钟内已创建过相同内容的微信记录 (id=999). 请避免重复提交.")
                .withHint("/sales/customers/c1?tab=wechat&highlight=999");
        when(wechatRecordService.create(anyString(), anyString(), any(WechatDirection.class),
                anyString(), any(), any(), any(), any(), any())).thenThrow(e409);

        Map<String, Object> params = Map.of(
                "customerId", "c1",
                "direction", "OUTBOUND",
                "messageContent", "重复消息");

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID, params, ctx());
        assertEquals("DUPLICATE", result.get("status"));
        assertTrue(result.get("message").toString().contains("999"));
        assertTrue(result.get("actionHint").toString().contains("highlight=999"));
    }

    @Test
    @DisplayName("UT-WCC-06: doExecute — invalid direction throws BusinessException(400)")
    void executeInvalidDirection() {
        Map<String, Object> params = Map.of(
                "customerId", "c1",
                "direction", "INVALID",
                "messageContent", "test");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeDoExecute(tool, FACTORY_ID, params, ctx()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("direction"));
    }

    // ── helpers ──
    private Customer newCustomer() {
        Customer c = new Customer();
        c.setId("c1");
        c.setFactoryId(FACTORY_ID);
        c.setCode("CC");
        c.setCustomerCode("CC");
        c.setName("张老板");
        return c;
    }

    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        ctx.put("userName", "test-user");
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoPreview(Object tool, String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        var method = findMethod(tool.getClass(), "doPreview");
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
