package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.Commission;
import com.cretas.aims.entity.enums.CommissionStatus;
import com.cretas.aims.repository.CommissionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link CommissionPendingTotalTool} (Sprint 8 P2). */
@ExtendWith(MockitoExtension.class)
class CommissionPendingTotalToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private CommissionPendingTotalTool tool;

    @Mock
    private CommissionRepository commissionRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-CPT-01: metadata")
    void metadata() {
        assertEquals("commission_pending_total", tool.getToolName());
    }

    @Test
    @DisplayName("UT-CPT-02: doExecute — sums pending + breakdown by sales")
    @SuppressWarnings("unchecked")
    void aggregates() throws Exception {
        Commission c1 = Commission.builder().id("c1").factoryId(FACTORY_ID)
                .salesOpportunityId("o1").ruleId("r1").salesId(101L)
                .percentage(new BigDecimal("5"))
                .amount(new BigDecimal("500"))
                .status(CommissionStatus.PENDING).build();
        Commission c2 = Commission.builder().id("c2").factoryId(FACTORY_ID)
                .salesOpportunityId("o2").ruleId("r1").salesId(101L)
                .percentage(new BigDecimal("5"))
                .amount(new BigDecimal("300"))
                .status(CommissionStatus.PENDING).build();
        Commission c3 = Commission.builder().id("c3").factoryId(FACTORY_ID)
                .salesOpportunityId("o3").ruleId("r1").salesId(102L)
                .percentage(new BigDecimal("5"))
                .amount(new BigDecimal("200"))
                .status(CommissionStatus.PENDING).build();
        when(commissionRepository.findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                anyString(), eq(CommissionStatus.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(c1, c2, c3)));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(3, data.get("totalCount"));
        // total = 500 + 300 + 200 = 1000
        assertEquals(0, ((BigDecimal) data.get("totalAmount")).compareTo(new BigDecimal("1000.00")));
        List<Map<String, Object>> breakdown = (List<Map<String, Object>>) data.get("breakdownBySales");
        assertEquals(2, breakdown.size());
        // first row sorted by totalAmount desc → sales 101 (800)
        assertEquals(101L, breakdown.get(0).get("salesId"));
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
