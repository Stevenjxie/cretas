package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.SalesOpportunity;
import com.cretas.aims.entity.enums.OpportunityStage;
import com.cretas.aims.exception.InvalidStageTransitionException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.service.SalesOpportunityService;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OpportunityTransitionStageTool} (Sprint 8 P1).
 */
@ExtendWith(MockitoExtension.class)
class OpportunityTransitionStageToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private OpportunityTransitionStageTool tool;

    @Mock
    private SalesOpportunityService salesOpportunityService;

    @Mock
    private CustomerRepository customerRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-OTS-01: Tool metadata — WRITE / MEDIUM / supportsPreview=true")
    void metadata() {
        assertEquals("opportunity_transition_stage", tool.getToolName());
        assertEquals(ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(ToolExecutor.RiskLevel.MEDIUM, tool.getRiskLevel());
        assertTrue(tool.supportsPreview());
        assertEquals(2, tool.getRequiredParameters().size());
        assertTrue(tool.getRequiredParameters().contains("id"));
        assertTrue(tool.getRequiredParameters().contains("newStage"));
    }

    @Test
    @DisplayName("UT-OTS-02: doPreview — forward by 1 → canDo=true with R2 context")
    void previewForwardByOne() throws Exception {
        SalesOpportunity opp = opp("opp-1", OpportunityStage.QUALIFIED);
        when(salesOpportunityService.get(anyString(), anyString())).thenReturn(opp);
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.of(customer()));

        Map<String, Object> params = Map.of("id", "opp-1", "newStage", "DEMO");
        Map<String, Object> result = invokeDoPreview(tool, FACTORY_ID, params, ctx());
        assertEquals("PREVIEW", result.get("status"));
        assertEquals(true, result.get("canDo"));
        assertTrue(result.get("message").toString().contains("张老板"));
        assertTrue(result.get("message").toString().contains("DEMO") ||
                result.get("message").toString().contains("产品演示"));
    }

    @Test
    @DisplayName("UT-OTS-03: doPreview — same stage self-loop → PREVIEW_INVALID")
    void previewSameStage() throws Exception {
        SalesOpportunity opp = opp("opp-1", OpportunityStage.QUALIFIED);
        when(salesOpportunityService.get(anyString(), anyString())).thenReturn(opp);
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> params = Map.of("id", "opp-1", "newStage", "QUALIFIED");
        Map<String, Object> result = invokeDoPreview(tool, FACTORY_ID, params, ctx());
        assertEquals("PREVIEW_INVALID", result.get("status"));
        assertEquals(false, result.get("canDo"));
        assertTrue(result.get("message").toString().contains("起止阶段相同"));
    }

    @Test
    @DisplayName("UT-OTS-04: doPreview — skip-forward without confirmSkip → PREVIEW_INVALID")
    void previewSkipWithoutConfirm() throws Exception {
        SalesOpportunity opp = opp("opp-1", OpportunityStage.LEAD);
        when(salesOpportunityService.get(anyString(), anyString())).thenReturn(opp);
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> params = Map.of("id", "opp-1", "newStage", "PROPOSAL");
        Map<String, Object> result = invokeDoPreview(tool, FACTORY_ID, params, ctx());
        assertEquals("PREVIEW_INVALID", result.get("status"));
        assertEquals(false, result.get("canDo"));
        assertTrue(result.get("message").toString().contains("跳级"));
    }

    @Test
    @DisplayName("UT-OTS-05: doPreview — backward without reason → PREVIEW_INVALID")
    void previewBackwardWithoutReason() throws Exception {
        SalesOpportunity opp = opp("opp-1", OpportunityStage.PROPOSAL);
        when(salesOpportunityService.get(anyString(), anyString())).thenReturn(opp);
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> params = Map.of("id", "opp-1", "newStage", "QUALIFIED");
        Map<String, Object> result = invokeDoPreview(tool, FACTORY_ID, params, ctx());
        assertEquals("PREVIEW_INVALID", result.get("status"));
        assertTrue(result.get("message").toString().contains("回退"));
    }

    @Test
    @DisplayName("UT-OTS-06: doExecute — happy path, returns R2 context message")
    void executeHappyPath() throws Exception {
        SalesOpportunity saved = opp("opp-1", OpportunityStage.DEMO);
        when(salesOpportunityService.transitionStage(anyString(), anyString(),
                any(OpportunityStage.class), any(), anyBoolean(), any(), any()))
                .thenReturn(saved);
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.of(customer()));

        Map<String, Object> params = Map.of("id", "opp-1", "newStage", "DEMO");
        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID, params, ctx());
        assertTrue(result.get("message").toString().contains("张老板"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("DEMO", data.get("stage"));
    }

    @Test
    @DisplayName("UT-OTS-07: doExecute — service throws InvalidStageTransitionException → INVALID_TRANSITION result")
    void executeInvalidTransition() throws Exception {
        when(salesOpportunityService.transitionStage(anyString(), anyString(),
                any(OpportunityStage.class), any(), anyBoolean(), any(), any()))
                .thenThrow(new InvalidStageTransitionException("阶段回退需说明原因"));

        Map<String, Object> params = Map.of("id", "opp-1", "newStage", "LEAD");
        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID, params, ctx());
        assertEquals("INVALID_TRANSITION", result.get("status"));
        assertTrue(result.get("message").toString().contains("说明原因"));
        assertNotNull(result.get("actionHint"));
    }

    // ── helpers ──
    private SalesOpportunity opp(String id, OpportunityStage stage) {
        return SalesOpportunity.builder()
                .id(id).factoryId(FACTORY_ID).customerId("c1")
                .title("Test Opp").stage(stage)
                .probability(stage.getDefaultProbability())
                .ownerId(99L).createdBy(1L).build();
    }

    private Customer customer() {
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
