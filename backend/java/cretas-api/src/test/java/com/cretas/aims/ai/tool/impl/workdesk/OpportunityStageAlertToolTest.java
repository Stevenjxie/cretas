package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.SalesOpportunity;
import com.cretas.aims.entity.enums.OpportunityStage;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SalesOpportunityRepository;
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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpportunityStageAlertTool} (Sprint 8 P1).
 */
@ExtendWith(MockitoExtension.class)
class OpportunityStageAlertToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private OpportunityStageAlertTool tool;

    @Mock
    private SalesOpportunityRepository salesOpportunityRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-OSA-01: Tool metadata")
    void metadata() {
        assertEquals("opportunity_stage_alert", tool.getToolName());
        assertTrue(tool.getDescription().contains("SLA"));
        assertTrue(tool.getRequiredParameters().isEmpty());
    }

    @Test
    @DisplayName("UT-OSA-02: Only stale + non-terminal opportunities included")
    @SuppressWarnings("unchecked")
    void onlyStaleNonTerminalIncluded() throws Exception {
        // Stale (updated 30d ago, NEGOTIATE) → 应入
        SalesOpportunity stale = opp("opp-1", OpportunityStage.NEGOTIATE,
                LocalDateTime.now().minusDays(30));
        // Fresh (updated 1d ago, DEMO) → 排除
        SalesOpportunity fresh = opp("opp-2", OpportunityStage.DEMO,
                LocalDateTime.now().minusDays(1));
        // Stale but terminal (CLOSED_WON 30d ago) → 排除
        SalesOpportunity terminal = opp("opp-3", OpportunityStage.CLOSED_WON,
                LocalDateTime.now().minusDays(30));

        when(salesOpportunityRepository.findByFactoryIdAndDeletedAtIsNull(
                anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(stale, fresh, terminal)));
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID,
                Map.of("slaDays", 21), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> opps = (List<Map<String, Object>>) data.get("opportunities");
        assertEquals(1, opps.size());
        assertEquals("opp-1", opps.get(0).get("id"));
        assertTrue(((Number) opps.get(0).get("stagnantDays")).longValue() >= 28);
    }

    @Test
    @DisplayName("UT-OSA-03: ownerId filter — uses owner-specific repo method")
    @SuppressWarnings("unchecked")
    void ownerIdFilter() throws Exception {
        SalesOpportunity stale = opp("opp-1", OpportunityStage.NEGOTIATE,
                LocalDateTime.now().minusDays(40));
        when(salesOpportunityRepository.findByFactoryIdAndOwnerIdAndDeletedAtIsNull(
                anyString(), eq(99L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(stale)));
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID,
                Map.of("ownerId", 99), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> opps = (List<Map<String, Object>>) data.get("opportunities");
        assertEquals(1, opps.size());
        assertEquals(99L, data.get("ownerId"));
    }

    @Test
    @DisplayName("UT-OSA-04: stagnantDays sort descending — worst-stale first")
    @SuppressWarnings("unchecked")
    void stagnantDaysSortDescending() throws Exception {
        SalesOpportunity older = opp("opp-1", OpportunityStage.NEGOTIATE,
                LocalDateTime.now().minusDays(60));
        SalesOpportunity less = opp("opp-2", OpportunityStage.DEMO,
                LocalDateTime.now().minusDays(25));

        when(salesOpportunityRepository.findByFactoryIdAndDeletedAtIsNull(
                anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(less, older)));  // input in 'less first' order
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> opps = (List<Map<String, Object>>) data.get("opportunities");
        assertEquals(2, opps.size());
        // older should come first (sorted desc by stagnantDays)
        assertEquals("opp-1", opps.get(0).get("id"));
    }

    // ── helpers ──
    private SalesOpportunity opp(String id, OpportunityStage stage, LocalDateTime updatedAt) {
        SalesOpportunity o = SalesOpportunity.builder()
                .id(id).factoryId(FACTORY_ID).customerId("c1")
                .title("商机 " + id).stage(stage)
                .probability(stage.getDefaultProbability())
                .ownerId(99L).createdBy(1L).build();
        o.setUpdatedAt(updatedAt);
        o.setCreatedAt(updatedAt);
        return o;
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
