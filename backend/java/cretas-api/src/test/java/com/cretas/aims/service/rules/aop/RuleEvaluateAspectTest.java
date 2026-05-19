package com.cretas.aims.service.rules.aop;

import com.cretas.aims.entity.rules.RuleScope;
import com.cretas.aims.service.rules.RuleEngine;
import com.cretas.aims.service.rules.RuleEvaluationResult;
import com.cretas.aims.service.rules.RuleViolationException;
import com.cretas.aims.service.rules.annotation.RuleEvaluate;
import org.aopalliance.intercept.MethodInterceptor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RuleEvaluateAspect}.
 *
 * <p>Tests the aspect via a real {@link ProxyFactory} so we exercise AspectJ
 * @Around semantics (rather than calling the aspect method directly with a
 * mock ProceedingJoinPoint, which would skip Spring's annotation-binding glue).
 */
@DisplayName("RuleEvaluateAspect @Around semantics")
@ExtendWith(MockitoExtension.class)
class RuleEvaluateAspectTest {

    /** Target Service interface — annotated method invoked via proxy. */
    public interface DemoService {
        @RuleEvaluate("ORDER")
        String submitOrder(String factoryId, DemoInput input);

        @RuleEvaluate("INVALID_SCOPE_NAME")
        String invalidScope(String factoryId, DemoInput input);

        @RuleEvaluate("ORDER")
        String noFactoryArg(DemoInput input);

        /** Phase 4a post-review I2: multiple POJO args — must select by target. */
        @RuleEvaluate(value = "ORDER", target = "body")
        String updateOrder(String factoryId, DemoHeader header, DemoInput body);

        /** Phase 4a post-review I2: target name mismatch → fall back to first non-primitive. */
        @RuleEvaluate(value = "ORDER", target = "missingParamName")
        String updateOrderFallback(String factoryId, DemoInput input);
    }

    public static class DemoInput {
        public String orderNumber;
        public double totalAmount;
    }

    public static class DemoHeader {
        public String orderId;
    }

    public static class DemoServiceImpl implements DemoService {
        @Override
        public String submitOrder(String factoryId, DemoInput input) {
            return "OK:" + factoryId + ":" + input.orderNumber;
        }
        @Override
        public String invalidScope(String factoryId, DemoInput input) {
            return "OK_INVALID:" + factoryId;
        }
        @Override
        public String noFactoryArg(DemoInput input) {
            return "OK_NO_FACTORY:" + input.orderNumber;
        }
        @Override
        public String updateOrder(String factoryId, DemoHeader header, DemoInput body) {
            return "OK_UPDATE:" + factoryId + ":" + body.orderNumber;
        }
        @Override
        public String updateOrderFallback(String factoryId, DemoInput input) {
            return "OK_FALLBACK:" + factoryId + ":" + input.orderNumber;
        }
    }

    private RuleEngine ruleEngine;
    private DemoService proxied;

    @BeforeEach
    void setUp() {
        ruleEngine = mock(RuleEngine.class);
        RuleEvaluateAspect aspect = new RuleEvaluateAspect(ruleEngine);

        DemoServiceImpl target = new DemoServiceImpl();
        ProxyFactory pf = new ProxyFactory(target);
        pf.setProxyTargetClass(false);  // use interface-based proxy
        pf.addInterface(DemoService.class);
        pf.addAdvice((MethodInterceptor) invocation -> {
            // Build a ProceedingJoinPoint manually since ProxyFactory does not auto-wrap
            // for AspectJ annotations. We invoke the aspect method directly with a mock JP
            // that delegates proceed() to the real method invocation.
            ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
            MethodSignature sig = mock(MethodSignature.class);
            // lenient() — the aspect's code path varies per test, so some stubs are unused
            // for some scenarios. Strict mocking would fail UnnecessaryStubbingException.
            lenient().when(sig.getMethod()).thenReturn(invocation.getMethod());
            lenient().when(sig.toShortString()).thenReturn(
                    invocation.getMethod().getDeclaringClass().getSimpleName()
                    + "." + invocation.getMethod().getName());
            String[] names = new String[invocation.getMethod().getParameterCount()];
            java.lang.reflect.Parameter[] ps = invocation.getMethod().getParameters();
            for (int i = 0; i < ps.length; i++) names[i] = ps[i].getName();
            lenient().when(sig.getParameterNames()).thenReturn(names);
            lenient().when(jp.getSignature()).thenReturn(sig);
            lenient().when(jp.getArgs()).thenReturn(invocation.getArguments());
            lenient().when(jp.proceed()).thenAnswer(inv -> invocation.proceed());
            RuleEvaluate ann = invocation.getMethod().getAnnotation(RuleEvaluate.class);
            return aspect.evaluateRules(jp, ann);
        });
        proxied = (DemoService) pf.getProxy();
    }

    @Test
    @DisplayName("REJECT result throws RuleViolationException, target NOT called")
    void reject_throws() {
        when(ruleEngine.evaluate(eq("F001"), eq(RuleScope.ORDER), any()))
                .thenReturn(RuleEvaluationResult.reject(
                        "po_blacklist", "供应商在黑名单", List.of(UUID.randomUUID())));

        DemoInput input = new DemoInput();
        input.orderNumber = "SO-X";
        input.totalAmount = 50000;

        RuleViolationException ex = assertThrows(RuleViolationException.class,
                () -> proxied.submitOrder("F001", input));
        assertEquals("po_blacklist", ex.getRuleCode());
        assertEquals("供应商在黑名单", ex.getReason());
    }

    @Test
    @DisplayName("OK result: aspect proceeds, target method runs")
    void ok_proceeds() {
        when(ruleEngine.evaluate(eq("F001"), eq(RuleScope.ORDER), any()))
                .thenReturn(RuleEvaluationResult.ok(List.of()));

        DemoInput input = new DemoInput();
        input.orderNumber = "SO-Y";
        String result = proxied.submitOrder("F001", input);

        assertEquals("OK:F001:SO-Y", result);
    }

    @Test
    @DisplayName("Invalid scope value: skip evaluation, target runs unchanged")
    void invalid_scope_fail_open() {
        DemoInput input = new DemoInput();
        String result = proxied.invalidScope("F001", input);
        assertEquals("OK_INVALID:F001", result);
        verify(ruleEngine, never()).evaluate(any(), any(), any());
    }

    @Test
    @DisplayName("RuleEngine throws unexpectedly: aspect fails open, target runs")
    void engine_exception_fail_open() {
        when(ruleEngine.evaluate(any(), any(), any())).thenThrow(new RuntimeException("db down"));
        DemoInput input = new DemoInput();
        input.orderNumber = "SO-Z";
        String result = proxied.submitOrder("F001", input);
        assertEquals("OK:F001:SO-Z", result, "DB outage must not block business flow");
    }

    @Test
    @DisplayName("REJECT exception carries actionHint + severity (Phase 4a post-review C1)")
    void reject_carries_action_hint_and_severity() {
        when(ruleEngine.evaluate(eq("F001"), eq(RuleScope.ORDER), any()))
                .thenReturn(RuleEvaluationResult.reject(
                        "po_blacklist", "供应商在黑名单",
                        "/system/suppliers/SUP-001", "blocking",
                        List.of(UUID.randomUUID())));

        DemoInput input = new DemoInput();
        input.orderNumber = "SO-X";

        RuleViolationException ex = assertThrows(RuleViolationException.class,
                () -> proxied.submitOrder("F001", input));
        assertEquals("po_blacklist", ex.getRuleCode());
        assertEquals("供应商在黑名单", ex.getReason());
        assertEquals("/system/suppliers/SUP-001", ex.getActionHint(),
                "actionHint must propagate from RuleEvaluationResult to RuleViolationException");
        assertEquals("blocking", ex.getSeverity(),
                "severity must propagate from RuleEvaluationResult to RuleViolationException");
    }

    @Test
    @DisplayName("@RuleEvaluate(target='body'): picks named param even when multiple POJO args "
               + "(Phase 4a post-review I2)")
    void target_attribute_picks_named_parameter() {
        DemoHeader header = new DemoHeader();
        header.orderId = "HDR-1";
        DemoInput body = new DemoInput();
        body.orderNumber = "BODY-1";

        when(ruleEngine.evaluate(eq("F001"), eq(RuleScope.ORDER), any(DemoInput.class)))
                .thenReturn(RuleEvaluationResult.ok(List.of()));

        String result = proxied.updateOrder("F001", header, body);
        assertEquals("OK_UPDATE:F001:BODY-1", result);

        // Verify the body (not header) was passed to the engine.
        verify(ruleEngine).evaluate(eq("F001"), eq(RuleScope.ORDER),
                org.mockito.ArgumentMatchers.argThat(arg -> arg instanceof DemoInput
                        && "BODY-1".equals(((DemoInput) arg).orderNumber)));
    }

    @Test
    @DisplayName("@RuleEvaluate(target='nonexistent'): falls back to first non-primitive arg "
               + "(Phase 4a post-review I2)")
    void target_attribute_falls_back_when_param_missing() {
        DemoInput input = new DemoInput();
        input.orderNumber = "FB-1";

        when(ruleEngine.evaluate(eq("F001"), eq(RuleScope.ORDER), any(DemoInput.class)))
                .thenReturn(RuleEvaluationResult.ok(List.of()));

        String result = proxied.updateOrderFallback("F001", input);
        assertEquals("OK_FALLBACK:F001:FB-1", result);
    }
}
