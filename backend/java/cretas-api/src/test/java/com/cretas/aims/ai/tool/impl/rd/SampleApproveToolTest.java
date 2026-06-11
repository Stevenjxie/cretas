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
 * Unit tests for {@link SampleApproveTool} (AI Tool 样品审核).
 *
 * <p>覆盖: 元数据 + approve 正常路径 + reject 路径 + 工厂隔离 + 异常传播。
 * 注意: approve 触发 SampleApprovedEvent → QuotationTask 自动建由 Listener 做,
 * 这里只验证 Tool 调用 service.approveSample 且返回正确结构。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SampleApproveTool 单元测试")
class SampleApproveToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String SAMPLE_ID  = "sample-001";

    @InjectMocks
    private SampleApproveTool tool;

    @Mock
    private ProductSampleService sampleService;

    // =========================================================================
    // UT-SAT-01: 元数据
    // =========================================================================

    @Test
    @DisplayName("UT-SAT-01: 元数据 — toolName / description / requiredParameters")
    void metadata() {
        assertEquals("rd_sample_approve", tool.getToolName());
        assertNotNull(tool.getDescription());
        assertThat(tool.getDescription()).contains("审核");
        List<String> required = tool.getRequiredParameters();
        assertThat(required).contains("sampleId", "action");
    }

    // =========================================================================
    // UT-SAT-02: action=approve 正常路径
    // =========================================================================

    @Test
    @DisplayName("UT-SAT-02: action=approve → 调用 approveSample, 返回 sampleCode + status")
    @SuppressWarnings("unchecked")
    void doExecute_approve_happyPath() throws Exception {
        ProductSample approved = approvedSample();
        when(sampleService.approveSample(eq(FACTORY_ID), eq(SAMPLE_ID), any(), any()))
                .thenReturn(approved);

        Map<String, Object> params = Map.of(
                "sampleId", SAMPLE_ID,
                "action", "approve");

        Map<String, Object> result = invokeDoExecute(FACTORY_ID, params, ctx());

        // buildSimpleResult 返回 {message, data} — 无 "success" key
        assertNotNull(result.get("message"), "message 不应为 null");
        assertThat(result.get("message").toString()).contains("审核通过");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data.get("sampleCode")).isEqualTo("SC-20260611-0001");
        assertThat(data.get("status")).isEqualTo("APPROVED");

        verify(sampleService).approveSample(eq(FACTORY_ID), eq(SAMPLE_ID), any(), any());
        verify(sampleService, never()).rejectSample(any(), any(), any(), any());
    }

    // =========================================================================
    // UT-SAT-03: action=APPROVE (大写) — 大小写不敏感
    // =========================================================================

    @Test
    @DisplayName("UT-SAT-03: action=APPROVE (大写) → 正常走 approve 路径")
    void doExecute_approveUpperCase() throws Exception {
        when(sampleService.approveSample(any(), any(), any(), any())).thenReturn(approvedSample());

        Map<String, Object> params = Map.of("sampleId", SAMPLE_ID, "action", "APPROVE");
        Map<String, Object> result = invokeDoExecute(FACTORY_ID, params, ctx());
        assertNotNull(result.get("data"), "data 不应为 null");
        verify(sampleService).approveSample(any(), any(), any(), any());
    }

    // =========================================================================
    // UT-SAT-04: action=reject 路径
    // =========================================================================

    @Test
    @DisplayName("UT-SAT-04: action=reject → 调用 rejectSample, 返回 REJECTED status")
    @SuppressWarnings("unchecked")
    void doExecute_reject_happyPath() throws Exception {
        ProductSample rejected = new ProductSample();
        rejected.setSampleCode("SC-20260611-0001");
        rejected.setStatus("REJECTED");
        when(sampleService.rejectSample(eq(FACTORY_ID), eq(SAMPLE_ID), any(), eq("质量不达标")))
                .thenReturn(rejected);

        Map<String, Object> params = Map.of(
                "sampleId", SAMPLE_ID,
                "action", "reject",
                "notes", "质量不达标");

        Map<String, Object> result = invokeDoExecute(FACTORY_ID, params, ctx());
        assertNotNull(result.get("data"), "data 不应为 null");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data.get("status")).isEqualTo("REJECTED");

        verify(sampleService).rejectSample(eq(FACTORY_ID), eq(SAMPLE_ID), any(), eq("质量不达标"));
        verify(sampleService, never()).approveSample(any(), any(), any(), any());
    }

    // =========================================================================
    // UT-SAT-05: notes 可为 null — approve 时 notes 为可选
    // =========================================================================

    @Test
    @DisplayName("UT-SAT-05: notes 可为 null — approve 时不带 notes 正常执行")
    void doExecute_approve_noNotes() throws Exception {
        when(sampleService.approveSample(any(), any(), any(), isNull())).thenReturn(approvedSample());

        Map<String, Object> params = Map.of("sampleId", SAMPLE_ID, "action", "approve");
        Map<String, Object> result = invokeDoExecute(FACTORY_ID, params, ctx());
        assertNotNull(result.get("data"), "data 不应为 null (notes=null 时正常执行)");
        verify(sampleService).approveSample(any(), any(), any(), isNull());
    }

    // =========================================================================
    // UT-SAT-06: 工厂隔离 — factoryId 透传
    // =========================================================================

    @Test
    @DisplayName("UT-SAT-06: 工厂隔离 — factoryId 透传到 approveSample")
    void doExecute_factoryIsolation() throws Exception {
        String other = "F999";
        when(sampleService.approveSample(eq(other), any(), any(), any())).thenReturn(approvedSample());

        Map<String, Object> params = Map.of("sampleId", "sample-f999", "action", "approve");
        invokeDoExecute(other, params, ctx(other));

        verify(sampleService).approveSample(eq(other), any(), any(), any());
        verify(sampleService, never()).approveSample(eq(FACTORY_ID), any(), any(), any());
    }

    // =========================================================================
    // UT-SAT-07: service 异常传播 — 无静默失败
    // =========================================================================

    @Test
    @DisplayName("UT-SAT-07: service 异常 → doExecute 向上传播")
    void doExecute_serviceThrows_propagates() {
        when(sampleService.approveSample(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("样品不在 SUBMITTED 状态"));

        Map<String, Object> params = Map.of("sampleId", SAMPLE_ID, "action", "approve");
        assertThrows(IllegalStateException.class,
                () -> invokeDoExecute(FACTORY_ID, params, ctx()));
    }

    // =========================================================================
    // UT-SAT-08: parametersSchema — 包含 sampleId/action/notes
    // =========================================================================

    @Test
    @DisplayName("UT-SAT-08: parametersSchema 包含预期字段")
    @SuppressWarnings("unchecked")
    void parametersSchema_containsExpectedFields() {
        Map<String, Object> schema = tool.getParametersSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("sampleId");
        assertThat(props).containsKey("action");
        assertThat(props).containsKey("notes");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ProductSample approvedSample() {
        ProductSample s = new ProductSample();
        s.setSampleCode("SC-20260611-0001");
        s.setStatus("APPROVED");
        return s;
    }

    private Map<String, Object> ctx() {
        return ctx(FACTORY_ID);
    }

    private Map<String, Object> ctx(String factoryId) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", factoryId);
        ctx.put("userId", 88L);
        ctx.put("userName", "approver");
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
