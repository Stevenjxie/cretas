package com.cretas.aims.service.execution;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.ToolEmbeddingRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.ToolRouterServiceImpl;
import com.cretas.aims.service.skill.impl.SkillExecutorImpl;
import com.cretas.aims.dto.skill.SkillContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W9 红线 (AI-RBAC 系统性收口) WIRING tests: prove the central {@link ToolRbacEnforcer} is invoked at
 * the dynamic tool-execution SITEs that bypass ToolDispatchService (Site B), and that a denied tool
 * NEVER reaches {@code executor.execute(...)}.
 *
 * <p>Covered here directly via the private {@code executeSingleTool} entrypoints:
 * <ul>
 *   <li><b>SITE D</b> — {@link SkillExecutorImpl} (skill orchestration)</li>
 *   <li><b>SITE E</b> — {@link ToolRouterServiceImpl} (dynamic tool chain)</li>
 * </ul>
 *
 * <p>SITE B (ToolDispatchService) is covered by {@link AiToolRbacGateTest} +
 * {@code ToolRbacEnforcerTest} (the enforcer it calls); SITE C (auto-plan) and SITE F (LLM fallback)
 * use the identical {@code enforcer.isAllowed(...)} guard inline next to the W0 write-guard.
 */
@DisplayName("W9 红线: ToolRbacEnforcer 在动态 SITE 强制 (deny → 不执行)")
class ToolRbacEnforcerWiringTest {

    private static final String FACTORY = "F006";
    private static final long OPERATOR_UID = 9001L;

    /** Real enforcer wired with a guard whose PermissionService denies (operator越权). */
    private ToolRbacEnforcer denyingEnforcer() {
        UserRepository userRepo = mock(UserRepository.class);
        PermissionService perm = mock(PermissionService.class);
        User operator = new User();
        operator.setId(OPERATOR_UID);
        operator.setFactoryId(FACTORY);
        operator.setRoleCode("operator");
        when(userRepo.findById(OPERATOR_UID)).thenReturn(Optional.of(operator));
        when(perm.hasAnyPermission(any(), any(String[].class))).thenReturn(false);
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", userRepo);
        ReflectionTestUtils.setField(guard, "permissionService", perm);
        ToolRbacEnforcer enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);
        return enforcer;
    }

    /** A mapped sensitive write tool (customer_delete). isWriteTool → true; mapped → requires perm. */
    private ToolExecutor sensitiveWriteTool() {
        ToolExecutor t = mock(ToolExecutor.class);
        when(t.getToolName()).thenReturn("customer_delete");
        when(t.getRequiredPermissions()).thenReturn(java.util.Set.of());
        when(t.getActionType()).thenReturn(ToolExecutor.ActionType.DELETE);
        return t;
    }

    private static Map<String, Object> ctx() {
        Map<String, Object> c = new HashMap<>();
        c.put("factoryId", FACTORY);
        c.put("userId", OPERATOR_UID);
        c.put("userRole", "operator");
        c.put("confirmed", true); // already confirmed → only RBAC can block (isolate the W9 layer)
        return c;
    }

    // ===================== SITE E: ToolRouterServiceImpl =====================

    @Test
    @DisplayName("SITE E (ToolRouter): operator 删客户 → RBAC 拒绝, executor.execute 不被调用")
    void siteE_deniedDoesNotExecute() throws Exception {
        ToolRouterServiceImpl router = new ToolRouterServiceImpl();
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolExecutor tool = sensitiveWriteTool();
        when(registry.getExecutor("customer_delete")).thenReturn(Optional.of(tool));

        ReflectionTestUtils.setField(router, "toolRegistry", registry);
        ReflectionTestUtils.setField(router, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(router, "toolEmbeddingRepository", mock(ToolEmbeddingRepository.class));
        ReflectionTestUtils.setField(router, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(router, "toolRbacEnforcer", denyingEnforcer());

        Method m = ToolRouterServiceImpl.class.getDeclaredMethod(
                "executeSingleTool", String.class, Map.class);
        m.setAccessible(true);

        Throwable thrown = catchInvoke(() -> m.invoke(router, "customer_delete", ctx()));
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage()).contains("没有权限");
        verify(tool, never()).execute(any(), any());
    }

    // ===================== SITE D: SkillExecutorImpl =====================

    @Test
    @DisplayName("SITE D (Skill): operator 删客户 → RBAC 拒绝, executor.execute 不被调用")
    void siteD_deniedDoesNotExecute() throws Exception {
        ToolRegistry registry = mock(ToolRegistry.class);
        SkillExecutorImpl skillExecutor = new SkillExecutorImpl(
                registry, mock(com.cretas.aims.ai.client.DashScopeClient.class), new ObjectMapper());
        ToolExecutor tool = sensitiveWriteTool();
        when(registry.getExecutor("customer_delete")).thenReturn(Optional.of(tool));
        when(registry.isToolEnabledForFactory(eq(FACTORY), eq("customer_delete"))).thenReturn(true);

        ReflectionTestUtils.setField(skillExecutor, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(skillExecutor, "toolRbacEnforcer", denyingEnforcer());

        SkillContext skillContext = SkillContext.builder()
                .factoryId(FACTORY)
                .userId(String.valueOf(OPERATOR_UID))
                .build();

        Map<String, Object> params = new HashMap<>();
        params.put("confirmed", true); // confirmed → isolate W9 RBAC layer

        Method m = SkillExecutorImpl.class.getDeclaredMethod(
                "executeSingleTool", String.class, Map.class, SkillContext.class);
        m.setAccessible(true);

        Throwable thrown = catchInvoke(() -> m.invoke(skillExecutor, "customer_delete", params, skillContext));
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage()).contains("没有权限");
        verify(tool, never()).execute(any(), any());
    }

    // ===================== helper =====================

    @FunctionalInterface
    private interface Invoker {
        Object invoke() throws Exception;
    }

    private static Throwable catchInvoke(Invoker inv) {
        try {
            inv.invoke();
            return null;
        } catch (Exception e) {
            // reflective invoke wraps the real exception in InvocationTargetException
            if (e instanceof InvocationTargetException && e.getCause() != null) {
                return e.getCause();
            }
            return e;
        }
    }
}
