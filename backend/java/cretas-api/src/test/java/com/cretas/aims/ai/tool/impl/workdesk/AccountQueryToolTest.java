package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.enums.AccountBalanceType;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.service.finance.AccountService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AccountQueryTool} (Sprint 8 P2). */
@ExtendWith(MockitoExtension.class)
class AccountQueryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private AccountQueryTool tool;

    @Mock
    private AccountService accountService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-AQ-01: metadata — name + read-only + no required params")
    void metadata() {
        assertEquals("account_query", tool.getToolName());
        assertTrue(tool.getDescription().contains("会计科目"));
        assertTrue(tool.getRequiredParameters().isEmpty());
    }

    @Test
    @DisplayName("UT-AQ-02: doExecute — happy path returns 会计科目 list")
    @SuppressWarnings("unchecked")
    void happyPath() throws Exception {
        Account a = Account.builder()
                .id("a1").code("1001").name("库存现金")
                .category(AccountCategory.ASSET)
                .balanceType(AccountBalanceType.DEBIT_NORMAL)
                .level(1).active(true).sortOrder(0).build();
        when(accountService.listActiveVisible(anyString())).thenReturn(List.of(a));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) data.get("accounts");
        assertEquals(1, accounts.size());
        assertEquals("1001", accounts.get(0).get("code"));
        assertEquals("ASSET", accounts.get(0).get("category"));
    }

    @Test
    @DisplayName("UT-AQ-03: category filter — calls listByCategory")
    @SuppressWarnings("unchecked")
    void categoryFilter() throws Exception {
        when(accountService.listByCategory(anyString(), org.mockito.ArgumentMatchers.eq(AccountCategory.LIABILITY)))
                .thenReturn(List.of(
                        Account.builder().id("a2").code("2001").name("应付账款")
                                .category(AccountCategory.LIABILITY)
                                .balanceType(AccountBalanceType.CREDIT_NORMAL)
                                .level(1).active(true).sortOrder(0).build()));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("category", "LIABILITY"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) data.get("accounts");
        assertEquals(1, accounts.size());
        assertEquals("LIABILITY", accounts.get(0).get("category"));
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
