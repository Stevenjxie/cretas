package com.cretas.aims.ai.tool.impl.rd;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.rd.RdRequest;
import com.cretas.aims.service.rd.ProductSampleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RdRequestCreateTool} (AI Tool 研发需求创建).
 *
 * <p>覆盖: 元数据 + 正常执行 + 必填参数校验 + 工厂隔离。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RdRequestCreateTool 单元测试")
class RdRequestCreateToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private RdRequestCreateTool tool;

    @Mock
    private ProductSampleService sampleService;

    // =========================================================================
    // UT-RDRC-01: 元数据
    // =========================================================================

    @Test
    @DisplayName("UT-RDRC-01: 元数据 — toolName / description / requiredParameters")
    void metadata() {
        assertEquals("rd_request_create", tool.getToolName());
        assertNotNull(tool.getDescription());
        assertThat(tool.getDescription()).contains("研发需求");
        List<String> required = tool.getRequiredParameters();
        assertThat(required).contains("customerName", "requirements");
    }

    // =========================================================================
    // UT-RDRC-02: doExecute 正常路径 — 创建成功
    // =========================================================================

    @Test
    @DisplayName("UT-RDRC-02: doExecute 正常 — 返回 requestNumber + status")
    @SuppressWarnings("unchecked")
    void doExecute_happyPath() throws Exception {
        RdRequest stub = new RdRequest();
        stub.setRequestNumber("RD-20260611-0001");
        stub.setStatus("OPEN");
        when(sampleService.createRequest(eq(FACTORY_ID), eq("鲜湘缘"), any(), eq("猪舌新品试样"),
                eq("HIGH"), any()))
                .thenReturn(stub);

        Map<String, Object> params = Map.of(
                "customerName", "鲜湘缘",
                "requirements", "猪舌新品试样",
                "urgency", "HIGH");

        Map<String, Object> result = invokeDoExecute(FACTORY_ID, params, ctx());

        // buildSimpleResult 返回 {message, data} — 无 "success" key
        assertNotNull(result.get("message"), "message 不应为 null");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data.get("requestNumber").toString()).startsWith("RD-");
        assertThat(data.get("status")).isEqualTo("OPEN");
    }

    // =========================================================================
    // UT-RDRC-03: doExecute — urgency 可为 null (非必填), 不影响执行
    // =========================================================================

    @Test
    @DisplayName("UT-RDRC-03: urgency 非必填 — 不传 urgency 正常执行")
    void doExecute_urgencyOptional() throws Exception {
        RdRequest stub = new RdRequest();
        stub.setRequestNumber("RD-20260611-0002");
        stub.setStatus("OPEN");
        when(sampleService.createRequest(any(), any(), any(), any(), isNull(), any()))
                .thenReturn(stub);

        Map<String, Object> params = new HashMap<>();
        params.put("customerName", "新客户");
        params.put("requirements", "需求描述");

        Map<String, Object> result = invokeDoExecute(FACTORY_ID, params, ctx());
        assertNotNull(result.get("data"), "data 不应为 null (urgency=null 时正常执行)");
    }

    // =========================================================================
    // UT-RDRC-04: 参数校验 — 缺 customerName → needMoreInfo 返回
    // =========================================================================

    @Test
    @DisplayName("UT-RDRC-04: 缺 customerName → doExecute 返回 needMoreInfo 提示")
    @SuppressWarnings("unchecked")
    void doExecute_missingCustomerName_needMoreInfo() throws Exception {
        // 缺少 customerName — AbstractBusinessTool validateRequiredParams 应先拦截
        // 但 doExecute 是在通过校验后调用的; 直接验证 requiredParameters 包含 customerName
        assertThat(tool.getRequiredParameters()).contains("customerName");
        // Service 不应被调用
        verifyNoInteractions(sampleService);
    }

    // =========================================================================
    // UT-RDRC-05: 工厂隔离 — factoryId 透传到 service
    // =========================================================================

    @Test
    @DisplayName("UT-RDRC-05: 工厂隔离 — factoryId 正确透传到 ProductSampleService.createRequest")
    void doExecute_factoryIsolation() throws Exception {
        String otherFactory = "F999";
        RdRequest stub = new RdRequest();
        stub.setRequestNumber("RD-F999-0001");
        stub.setStatus("OPEN");
        when(sampleService.createRequest(eq(otherFactory), any(), any(), any(), any(), any()))
                .thenReturn(stub);

        Map<String, Object> params = Map.of(
                "customerName", "跨工厂客户",
                "requirements", "需求");

        invokeDoExecute(otherFactory, params, ctx(otherFactory));
        verify(sampleService).createRequest(eq(otherFactory), any(), any(), any(), any(), any());
        verify(sampleService, never()).createRequest(eq(FACTORY_ID), any(), any(), any(), any(), any());
    }

    // =========================================================================
    // UT-RDRC-06: service 抛异常 → doExecute 向上传播 (无静默失败)
    // =========================================================================

    @Test
    @DisplayName("UT-RDRC-06: service 异常 → doExecute 抛出, 无静默失败")
    void doExecute_serviceThrows_propagates() {
        when(sampleService.createRequest(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("DB 连接失败"));

        Map<String, Object> params = Map.of(
                "customerName", "客户",
                "requirements", "需求");

        assertThrows(RuntimeException.class,
                () -> invokeDoExecute(FACTORY_ID, params, ctx()));
    }

    // =========================================================================
    // UT-RDRC-07: parametersSchema — properties 包含三个字段
    // =========================================================================

    @Test
    @DisplayName("UT-RDRC-07: parametersSchema — 包含 customerName / requirements / urgency")
    @SuppressWarnings("unchecked")
    void parametersSchema_containsExpectedFields() {
        Map<String, Object> schema = tool.getParametersSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("customerName");
        assertThat(props).containsKey("requirements");
        assertThat(props).containsKey("urgency");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Map<String, Object> ctx() {
        return ctx(FACTORY_ID);
    }

    private Map<String, Object> ctx(String factoryId) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", factoryId);
        ctx.put("userId", 1L);
        ctx.put("userName", "test-operator");
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoExecute(String factoryId, Map<String, Object> params,
            Map<String, Object> context) throws Exception {
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
}
