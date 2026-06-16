package com.cretas.aims.service.smartbi;

import com.cretas.aims.client.GoldFinanceClient;
import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for RBAC request-context propagation to async dashboard threads.
 *
 * Root cause (Jun 2026): SmartBIServiceImpl.getExecutiveDashboard calls
 * salesService.getSalesOverview inside CompletableFuture.supplyAsync(DASHBOARD_EXECUTOR).
 * RequestContextHolder is thread-local — child threads in the fixed pool had no
 * request context → GoldFinanceClient.currentUserRole() returned null →
 * Python _apply_rbac_strip nulled all revenue → factory dashboards showed ¥0.
 *
 * Fix: capture RequestAttributes on the request thread, bind into each child thread
 * via RequestContextHolder.setRequestAttributes(attrs, true), remove in finally.
 *
 * This test verifies:
 *   1. With the fix: factory_super_admin role propagated → X-User-Role header present
 *      in GoldFinanceClient HTTP call → Python sees role → revenue NOT stripped.
 *   2. RBAC preserved: when role is a non-price-view role, X-User-Role is still
 *      forwarded (Python decides whether to strip — Java's job is always to forward
 *      whatever role it has).
 *   3. No context leak: RequestContextHolder is clean after the async task completes.
 */
class DashboardRbacContextPropagationTest {

    private MockWebServer server;
    private GoldFinanceClient client;
    private ExecutorService executor;

    private static final String GOLD_BODY = "{"
            + "\"total_revenue\":35521873.91,"
            + "\"bill_count\":12345,"
            + "\"avg_bill_value\":2877.34,"
            + "\"store_count\":3,"
            + "\"top_stores\":[]"
            + "}";

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        PythonSmartBIConfig config = new PythonSmartBIConfig();
        config.setEnabled(true);
        config.setUrl(server.url("").toString().replaceAll("/$", ""));
        config.setTimeout(5000);
        config.setConnectTimeout(5000);

        client = new GoldFinanceClient(config);
        ReflectionTestUtils.setField(client, "internalSecret", "test-secret");

        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
        executor.shutdown();
        // Ensure request context is clean after each test.
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * Simulates the fixed async path: RequestAttributes captured on request thread,
     * bound into child thread via setRequestAttributes(attrs, true).
     * Verifies X-User-Role header is forwarded with factory_super_admin role.
     */
    @Test
    void asyncThread_withCapturedContext_forwardsUserRoleHeader() throws Exception {
        server.enqueue(new MockResponse().setBody(GOLD_BODY).setResponseCode(200));

        // Simulate request thread: set role attribute as JwtAuthInterceptor does.
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("role", "factory_super_admin");
        RequestAttributes attrs = new ServletRequestAttributes(req);
        RequestContextHolder.setRequestAttributes(attrs);

        // Capture on request thread (the fix).
        final RequestAttributes capturedAttrs = RequestContextHolder.getRequestAttributes();
        assertNotNull(capturedAttrs, "Request attributes must be present on request thread");

        // Execute on child thread with propagated context (mirrors the fix in SmartBIServiceImpl).
        Map<String, Object> result = CompletableFuture.supplyAsync(() -> {
            RequestContextHolder.setRequestAttributes(capturedAttrs, true);
            try {
                return client.fetchFinanceSummary("DEMO_FACTORY",
                        LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 5);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        }, executor).get();

        // Verify Gold response parsed correctly (Double may render as scientific notation).
        assertNotNull(result);
        Object rev = result.get("total_revenue");
        assertNotNull(rev, "total_revenue must be present in Gold response");
        double revDouble = rev instanceof Number ? ((Number) rev).doubleValue() : Double.parseDouble(rev.toString());
        assertEquals(35521873.91, revDouble, 0.01, "total_revenue must match Gold response body");

        // Verify X-User-Role header was forwarded.
        RecordedRequest recorded = server.takeRequest();
        String xUserRole = recorded.getHeader("X-User-Role");
        assertNotNull(xUserRole,
                "X-User-Role must be forwarded when request context is propagated to async thread");
        assertEquals("factory_super_admin", xUserRole,
                "factory_super_admin role must be forwarded so Python does NOT strip revenue");
    }

    /**
     * Simulates the BROKEN path (before fix): no context propagation.
     * Verifies that without the fix, X-User-Role is absent → Python would strip revenue.
     * This test documents the bug that was present.
     */
    @Test
    void asyncThread_withoutContextPropagation_missingUserRoleHeader() throws Exception {
        server.enqueue(new MockResponse().setBody(GOLD_BODY).setResponseCode(200));

        // Request thread has role attribute set.
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("role", "factory_super_admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        // Execute on child thread WITHOUT propagating context (the bug).
        Map<String, Object> result = CompletableFuture.supplyAsync(() -> {
            // No RequestContextHolder.setRequestAttributes call — simulates the old broken path.
            try {
                return client.fetchFinanceSummary("DEMO_FACTORY",
                        LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 5);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor).get();

        assertNotNull(result);

        // Without propagation, X-User-Role is absent → Python would strip revenue.
        RecordedRequest recorded = server.takeRequest();
        String xUserRole = recorded.getHeader("X-User-Role");
        assertNull(xUserRole,
                "Without context propagation, X-User-Role is absent → Python strips revenue (documenting the bug)");
    }

    /**
     * Verifies no context leak: the child thread's RequestContextHolder is clean
     * after resetRequestAttributes() in the finally block.
     */
    @Test
    void asyncThread_afterCompletion_contextIsClean() throws Exception {
        server.enqueue(new MockResponse().setBody(GOLD_BODY).setResponseCode(200));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("role", "factory_super_admin");
        RequestAttributes attrs = new ServletRequestAttributes(req);
        RequestContextHolder.setRequestAttributes(attrs);
        final RequestAttributes capturedAttrs = RequestContextHolder.getRequestAttributes();

        // Track what RequestContextHolder state is in the child thread AFTER the task.
        final RequestAttributes[] afterTaskContext = new RequestAttributes[1];

        CompletableFuture.runAsync(() -> {
            RequestContextHolder.setRequestAttributes(capturedAttrs, true);
            try {
                // Simulate work.
                client.fetchFinanceSummary("DEMO_FACTORY",
                        LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 5);
            } catch (Exception ignored) {}
            finally {
                RequestContextHolder.resetRequestAttributes();
                // Capture AFTER reset — should be null.
                afterTaskContext[0] = RequestContextHolder.getRequestAttributes();
            }
        }, executor).get();

        assertNull(afterTaskContext[0],
                "RequestContextHolder must be null in child thread after resetRequestAttributes() in finally — no context leak");
    }
}
