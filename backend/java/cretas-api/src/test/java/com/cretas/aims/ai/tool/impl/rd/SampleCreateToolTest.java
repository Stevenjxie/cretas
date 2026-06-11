package com.cretas.aims.ai.tool.impl.rd;

import com.cretas.aims.entity.rd.ProductSample;
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
 * Unit tests for {@link SampleCreateTool} (AI Tool 样品创建).
 *
 * <p>覆盖: 元数据 + 正常执行 + 可选字段 + 工厂隔离 + 异常传播。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SampleCreateTool 单元测试")
class SampleCreateToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private SampleCreateTool tool;

    @Mock
    private ProductSampleService sampleService;

    // =========================================================================
    // UT-SCT-01: 元数据
    // =========================================================================

    @Test
    @DisplayName("UT-SCT-01: 元数据 — toolName / description / requiredParameters")
    void metadata() {
        assertEquals("rd_sample_create", tool.getToolName());
        assertNotNull(tool.getDescription());
        assertThat(tool.getDescription()).contains("样品");
        List<String> required = tool.getRequiredParameters();
        assertThat(required).contains("name");
    }

    // =========================================================================
    // UT-SCT-02: doExecute 正常路径 — 返回 sampleCode + name
    // =========================================================================

    @Test
    @DisplayName("UT-SCT-02: doExecute 正常 — 返回 sampleCode + name")
    @SuppressWarnings("unchecked")
    void doExecute_happyPath() throws Exception {
        ProductSample stub = new ProductSample();
        stub.setSampleCode("SC-20260611-0001");
        stub.setName("卤猪舌 200g");
        when(sampleService.createSample(eq(FACTORY_ID), any(), eq("卤猪舌 200g"),
                any(), any(), any(), any()))
                .thenReturn(stub);

        Map<String, Object> params = Map.of("name", "卤猪舌 200g");
        Map<String, Object> result = invokeDoExecute(FACTORY_ID, params, ctx());

        // buildSimpleResult 返回 {message, data} — 无 "success" key
        assertNotNull(result.get("message"), "message 不应为 null");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data.get("sampleCode").toString()).startsWith("SC-");
        assertThat(data.get("name")).isEqualTo("卤猪舌 200g");
    }

    // =========================================================================
    // UT-SCT-03: 全部可选字段传入 — 正常执行
    // =========================================================================

    @Test
    @DisplayName("UT-SCT-03: 携带所有可选字段 (specification/mainMaterial/rdRequestId) 正常执行")
    void doExecute_allOptionalFields() throws Exception {
        ProductSample stub = new ProductSample();
        stub.setSampleCode("SC-20260611-0002");
        stub.setName("牛腱 500g");
        when(sampleService.createSample(eq(FACTORY_ID), eq("req-001"), eq("牛腱 500g"),
                eq("500g/袋"), any(), eq("牛腱"), any()))
                .thenReturn(stub);

        Map<String, Object> params = Map.of(
                "name", "牛腱 500g",
                "specification", "500g/袋",
                "mainMaterial", "牛腱",
                "rdRequestId", "req-001");

        Map<String, Object> result = invokeDoExecute(FACTORY_ID, params, ctx());
        assertNotNull(result.get("data"), "data 不应为 null");
        verify(sampleService).createSample(eq(FACTORY_ID), eq("req-001"), eq("牛腱 500g"),
                eq("500g/袋"), any(), eq("牛腱"), any());
    }

    // =========================================================================
    // UT-SCT-04: 必填 name 验证 — requiredParameters 包含 name
    // =========================================================================

    @Test
    @DisplayName("UT-SCT-04: requiredParameters 仅包含 name (其余均可选)")
    void requiredParameters_onlyName() {
        List<String> required = tool.getRequiredParameters();
        assertThat(required).containsExactly("name");
    }

    // =========================================================================
    // UT-SCT-05: 工厂隔离 — factoryId 透传
    // =========================================================================

    @Test
    @DisplayName("UT-SCT-05: 工厂隔离 — factoryId 透传到 sampleService")
    void doExecute_factoryIsolation() throws Exception {
        String other = "F999";
        ProductSample stub = new ProductSample();
        stub.setSampleCode("SC-F999-0001");
        stub.setName("测试样品");
        when(sampleService.createSample(eq(other), any(), any(), any(), any(), any(), any()))
                .thenReturn(stub);

        Map<String, Object> params = Map.of("name", "测试样品");
        invokeDoExecute(other, params, ctx(other));

        verify(sampleService).createSample(eq(other), any(), any(), any(), any(), any(), any());
        verify(sampleService, never()).createSample(eq(FACTORY_ID), any(), any(), any(), any(), any(), any());
    }

    // =========================================================================
    // UT-SCT-06: service 异常传播 — 无静默失败
    // =========================================================================

    @Test
    @DisplayName("UT-SCT-06: service 异常 → doExecute 向上传播, 无静默失败")
    void doExecute_serviceThrows_propagates() {
        when(sampleService.createSample(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("sampleCode 冲突"));

        Map<String, Object> params = Map.of("name", "冲突样品");
        assertThrows(IllegalStateException.class,
                () -> invokeDoExecute(FACTORY_ID, params, ctx()));
    }

    // =========================================================================
    // UT-SCT-07: parametersSchema — 包含 name/specification/mainMaterial/rdRequestId
    // =========================================================================

    @Test
    @DisplayName("UT-SCT-07: parametersSchema 包含预期字段")
    @SuppressWarnings("unchecked")
    void parametersSchema_containsExpectedFields() {
        Map<String, Object> schema = tool.getParametersSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("name");
        assertThat(props).containsKey("specification");
        assertThat(props).containsKey("mainMaterial");
        assertThat(props).containsKey("rdRequestId");
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
        ctx.put("userName", "test-rd");
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
