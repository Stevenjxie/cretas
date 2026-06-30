package com.cretas.aims.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoReadOnlyInterceptorTest {

    @Test
    void demoTenantAllowsListSummaryPostBecauseItIsReadOnlyAnalytics() throws Exception {
        DemoReadOnlyInterceptor interceptor = new DemoReadOnlyInterceptor();
        ReflectionTestUtils.setField(interceptor, "demoEnabled", true);
        ReflectionTestUtils.setField(interceptor, "demoFactoryIdsCsv", "DEMO_REST,DEMO_FACTORY");

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/mobile/DEMO_REST/list-summary/wastage");
        request.setAttribute("factoryId", "DEMO_REST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    }

    @Test
    void demoTenantAllowsChartInsightPostBecauseItIsReadOnlyAnalytics() throws Exception {
        DemoReadOnlyInterceptor interceptor = new DemoReadOnlyInterceptor();
        ReflectionTestUtils.setField(interceptor, "demoEnabled", true);
        ReflectionTestUtils.setField(interceptor, "demoFactoryIdsCsv", "DEMO_REST,DEMO_FACTORY");

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/mobile/DEMO_REST/restaurant/chart-insight");
        request.setAttribute("factoryId", "DEMO_REST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    }

    @Test
    void demoTenantStillBlocksBusinessWritePost() throws Exception {
        DemoReadOnlyInterceptor interceptor = new DemoReadOnlyInterceptor();
        ReflectionTestUtils.setField(interceptor, "demoEnabled", true);
        ReflectionTestUtils.setField(interceptor, "demoFactoryIdsCsv", "DEMO_REST,DEMO_FACTORY");

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/mobile/DEMO_REST/restaurant/requisitions");
        request.setAttribute("factoryId", "DEMO_REST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }
}
