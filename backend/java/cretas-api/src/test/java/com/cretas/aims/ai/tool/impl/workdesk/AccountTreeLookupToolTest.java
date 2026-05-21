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

/** Unit tests for {@link AccountTreeLookupTool} (Sprint 8 P2). */
@ExtendWith(MockitoExtension.class)
class AccountTreeLookupToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private AccountTreeLookupTool tool;

    @Mock
    private AccountService accountService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-ATL-01: metadata")
    void metadata() {
        assertEquals("account_tree_lookup", tool.getToolName());
        assertTrue(tool.getDescription().contains("树"));
        assertTrue(tool.getRequiredParameters().isEmpty());
    }

    @Test
    @DisplayName("UT-ATL-02: doExecute — builds 2-level tree from flat list")
    @SuppressWarnings("unchecked")
    void buildsTree() throws Exception {
        Account root = Account.builder()
                .id("r1").code("1000").name("流动资产")
                .category(AccountCategory.ASSET).balanceType(AccountBalanceType.DEBIT_NORMAL)
                .level(1).sortOrder(0).active(true).build();
        Account child = Account.builder()
                .id("c1").code("1001").name("库存现金").parentId("r1")
                .category(AccountCategory.ASSET).balanceType(AccountBalanceType.DEBIT_NORMAL)
                .level(2).sortOrder(0).active(true).build();
        when(accountService.listVisible(anyString())).thenReturn(List.of(root, child));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> tree = (List<Map<String, Object>>) data.get("tree");

        assertEquals(1, tree.size());
        Map<String, Object> rootNode = tree.get(0);
        assertEquals("1000", rootNode.get("code"));
        List<Map<String, Object>> children = (List<Map<String, Object>>) rootNode.get("children");
        assertEquals(1, children.size());
        assertEquals("1001", children.get(0).get("code"));
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
