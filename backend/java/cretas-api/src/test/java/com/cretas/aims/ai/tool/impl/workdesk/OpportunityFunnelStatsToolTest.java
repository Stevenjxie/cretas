package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.enums.OpportunityStage;
import com.cretas.aims.repository.SalesOpportunityRepository;
import com.cretas.aims.repository.SalesOpportunityRepository.FunnelStat;
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
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Unit tests for {@link OpportunityFunnelStatsTool} (Sprint 8 P2). */
@ExtendWith(MockitoExtension.class)
class OpportunityFunnelStatsToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private OpportunityFunnelStatsTool tool;

    @Mock
    private SalesOpportunityRepository salesOpportunityRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-OFS-01: metadata")
    void metadata() {
        assertEquals("opportunity_funnel_stats", tool.getToolName());
    }

    @Test
    @DisplayName("UT-OFS-02: doExecute — aggregates count + value + expected")
    @SuppressWarnings("unchecked")
    void aggregates() throws Exception {
        FunnelStat negotiate = stat(OpportunityStage.NEGOTIATE, 3L,
                new BigDecimal("100000"), new BigDecimal("85000"));
        FunnelStat won = stat(OpportunityStage.CLOSED_WON, 2L,
                new BigDecimal("50000"), new BigDecimal("50000"));
        when(salesOpportunityRepository.getFunnelStats(anyString(), eq(null)))
                .thenReturn(List.of(negotiate, won));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        // activeCount=3 (NEGOTIATE active; CLOSED_WON terminal)
        assertEquals(3L, data.get("activeCount"));
        assertEquals(5L, data.get("totalCount"));
    }

    private FunnelStat stat(OpportunityStage s, Long cnt, BigDecimal total, BigDecimal expected) {
        return new FunnelStat() {
            @Override public OpportunityStage getStage() { return s; }
            @Override public Long getCnt() { return cnt; }
            @Override public BigDecimal getTotalValue() { return total; }
            @Override public BigDecimal getExpectedValue() { return expected; }
        };
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
