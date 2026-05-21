package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.RecallAction;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.RecallActionRepository;
import com.cretas.aims.repository.foodsafety.RecallEventRepository;
import com.cretas.aims.service.OssService;
import com.cretas.aims.service.foodsafety.RegulatoryReportPdfService;
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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RegulatoryReportGenerateTool} (Sprint 8 P3 Phase B). */
@ExtendWith(MockitoExtension.class)
class RegulatoryReportGenerateToolTest {

    private static final String FACTORY_ID = "F006";
    private static final Long EVENT_ID = 100L;

    @InjectMocks
    private RegulatoryReportGenerateTool tool;

    @Mock
    private RecallEventRepository recallEventRepository;

    @Mock
    private RecallActionRepository recallActionRepository;

    @Mock
    private RegulatoryReportPdfService regulatoryReportPdfService;

    @Mock
    private OssService ossService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-RR-01: metadata + GENERATE action")
    void metadata() {
        assertEquals("regulatory_report_generate", tool.getToolName());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.GENERATE, tool.getActionType());
    }

    @Test
    @DisplayName("UT-RR-02: doExecute — generates markdown + PDF uploaded to OSS + REPORTED")
    @SuppressWarnings("unchecked")
    void executeGeneratesReport() throws Exception {
        RecallEvent event = RecallEvent.builder()
                .id(EVENT_ID).factoryId(FACTORY_ID).eventCode("RECALL-20260518-001")
                .triggerReason("客户投诉").triggerTime(LocalDateTime.now())
                .triggeredByUserId(1L)
                .affectedProductCategory("卤猪蹄")
                .status("NOTIFYING")
                .build();
        when(recallEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(recallActionRepository.findByRecallEventIdOrderByCreatedAtAsc(EVENT_ID))
                .thenReturn(List.of());
        when(recallActionRepository.save(any(RecallAction.class)))
                .thenAnswer(inv -> {
                    RecallAction a = inv.getArgument(0);
                    a.setId(888L);
                    return a;
                });
        // Sprint 9 P1.2 Fix 2 — mock PDF service + OSS
        byte[] fakePdf = "%PDF-1.4\nfake binary\n%%EOF".getBytes();
        when(regulatoryReportPdfService.generatePdf(any(RecallEvent.class), any()))
                .thenReturn(fakePdf);
        when(ossService.uploadBytes(any(byte[].class), org.mockito.ArgumentMatchers.eq("regulatory-reports"),
                org.mockito.ArgumentMatchers.eq(FACTORY_ID),
                org.mockito.ArgumentMatchers.eq("RECALL-20260518-001.pdf"),
                org.mockito.ArgumentMatchers.eq("application/pdf")))
                .thenReturn("https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/regulatory-reports/2026/05/21/uuid_RECALL-20260518-001.pdf");

        Map<String, Object> params = Map.of("recallEventId", EVENT_ID);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        String md = (String) data.get("reportMarkdown");
        assertNotNull(md);
        assertTrue(md.contains("RECALL-20260518-001"));
        assertTrue(md.contains("食品召回事件监管上报报告"));
        assertTrue(md.contains("食品安全法"));

        // Sprint 9 P1.2 — PDF + OSS 验证
        assertEquals("UPLOADED", data.get("pdfStatus"));
        assertNotNull(data.get("pdfUrl"));
        assertTrue(((String) data.get("pdfUrl")).contains("RECALL-20260518-001.pdf"));
        assertEquals(fakePdf.length, data.get("pdfBytes"));

        verify(recallActionRepository, times(1)).save(any(RecallAction.class));
        verify(recallEventRepository, times(1)).save(any(RecallEvent.class));
        verify(regulatoryReportPdfService, times(1)).generatePdf(any(RecallEvent.class), any());
        verify(ossService, times(1)).uploadBytes(any(byte[].class), anyString(), anyString(),
                anyString(), anyString());
        assertEquals("REPORTED", event.getStatus());
    }

    @Test
    @DisplayName("UT-RR-05: doExecute — OSS 未启用时 PDF 已生成但 pdfStatus=GENERATED_OSS_UNAVAILABLE (graceful)")
    @SuppressWarnings("unchecked")
    void executeOssUnavailable() throws Exception {
        RecallEvent event = RecallEvent.builder()
                .id(EVENT_ID).factoryId(FACTORY_ID).eventCode("RECALL-20260519-002")
                .triggerReason("内部抽检").triggerTime(LocalDateTime.now())
                .triggeredByUserId(1L)
                .affectedProductCategory("酱牛肉")
                .status("INVESTIGATING")
                .build();
        when(recallEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(recallActionRepository.findByRecallEventIdOrderByCreatedAtAsc(EVENT_ID))
                .thenReturn(List.of());
        when(recallActionRepository.save(any(RecallAction.class)))
                .thenAnswer(inv -> {
                    RecallAction a = inv.getArgument(0);
                    a.setId(999L);
                    return a;
                });
        byte[] fakePdf = new byte[]{0x25, 0x50, 0x44, 0x46};  // "%PDF"
        when(regulatoryReportPdfService.generatePdf(any(RecallEvent.class), any()))
                .thenReturn(fakePdf);
        // 模拟 NoOpOssServiceImpl 抛 UnsupportedOperationException
        when(ossService.uploadBytes(any(byte[].class), anyString(), anyString(),
                anyString(), anyString()))
                .thenThrow(new UnsupportedOperationException("OSS 服务未启用"));

        Map<String, Object> params = Map.of("recallEventId", EVENT_ID);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        // markdown 仍正常
        assertNotNull(data.get("reportMarkdown"));
        // PDF 已生成但 OSS 失败 → pdfUrl null + pdfStatus GENERATED_OSS_UNAVAILABLE
        assertNull(data.get("pdfUrl"));
        assertEquals("GENERATED_OSS_UNAVAILABLE", data.get("pdfStatus"));
        assertEquals(fakePdf.length, data.get("pdfBytes"));
        // status 仍流转到 REPORTED (PDF 失败不阻断业务)
        assertEquals("REPORTED", event.getStatus());
        verify(recallActionRepository, times(1)).save(any(RecallAction.class));
    }

    @Test
    @DisplayName("UT-RR-06: doExecute — PDF 生成本身失败 时 markdown 仍可用 + pdfStatus=FAILED")
    @SuppressWarnings("unchecked")
    void executePdfGenerationFails() throws Exception {
        RecallEvent event = RecallEvent.builder()
                .id(EVENT_ID).factoryId(FACTORY_ID).eventCode("RECALL-20260520-003")
                .triggerReason("监管通知").triggerTime(LocalDateTime.now())
                .triggeredByUserId(1L)
                .affectedProductCategory("速冻水饺")
                .status("FROZEN")
                .build();
        when(recallEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(recallActionRepository.findByRecallEventIdOrderByCreatedAtAsc(EVENT_ID))
                .thenReturn(List.of());
        when(recallActionRepository.save(any(RecallAction.class)))
                .thenAnswer(inv -> {
                    RecallAction a = inv.getArgument(0);
                    a.setId(1000L);
                    return a;
                });
        when(regulatoryReportPdfService.generatePdf(any(RecallEvent.class), any()))
                .thenThrow(new RuntimeException("字体加载失败"));

        Map<String, Object> params = Map.of("recallEventId", EVENT_ID);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        // markdown 仍正常
        assertNotNull(data.get("reportMarkdown"));
        assertNull(data.get("pdfUrl"));
        assertEquals("FAILED", data.get("pdfStatus"));
        assertNull(data.get("pdfBytes"));
        assertEquals("REPORTED", event.getStatus());
    }

    @Test
    @DisplayName("UT-RR-03: doExecute — event not found → BusinessException 404")
    void executeNotFound() {
        when(recallEventRepository.findById(anyLong())).thenReturn(Optional.empty());
        Map<String, Object> params = Map.of("recallEventId", 99999L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("UT-RR-04: doExecute — wrong factory → BusinessException 403")
    void wrongFactory() {
        RecallEvent event = RecallEvent.builder()
                .id(EVENT_ID).factoryId("F999").eventCode("RECALL-X")
                .triggerReason("X").triggerTime(LocalDateTime.now()).triggeredByUserId(1L)
                .affectedProductCategory("X").build();
        when(recallEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        Map<String, Object> params = Map.of("recallEventId", EVENT_ID);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(403, ex.getCode());
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
