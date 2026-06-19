package com.cretas.aims.controller;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.Label;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.LabelRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.TraceabilityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 溯源码扫码解析测试 (2026-06-19/20):
 * 编码规则工厂 (如六膳门) 生成的 {@code TR-{工厂}-{日期}-{序号}} 溯源码不内嵌批次号,
 * 修复前 extractBatchNumberFromTraceCode 只认 {@code TRACE-} 前缀 → 扫码必 400。
 * 修复: 经 Label.trace_code (打印时持久化) 反查 production_batch_id → 批次号。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TraceabilityController 溯源码扫码解析 (TR- via Label / 遗留 TRACE-)")
class TraceabilityControllerScanTest {

    @Mock TraceabilityService traceabilityService;
    @Mock ProductionBatchRepository productionBatchRepository;
    @Mock LabelRepository labelRepository;
    @InjectMocks TraceabilityController controller;

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> resp) {
        return (Map<String, Object>) resp.getBody();
    }

    @Test
    @DisplayName("TR-{工厂}-{日期}-{序号} 编码规则码 → 经 Label 反查批次号 → 200")
    void encodingRuleCode_resolvesViaLabel() {
        String traceCode = "TR-F006-20260619-3242";
        Label label = new Label();
        label.setTraceCode(traceCode);
        label.setProductionBatchId(8967L);
        when(labelRepository.findByTraceCodeAndDeletedAtIsNull(traceCode)).thenReturn(Optional.of(label));

        ProductionBatch batch = new ProductionBatch();
        batch.setBatchNumber("ZTEST-BG-233700");
        when(productionBatchRepository.findById(8967L)).thenReturn(Optional.of(batch));

        TraceabilityDTO.PublicTraceResponse trace = mock(TraceabilityDTO.PublicTraceResponse.class);
        lenient().when(trace.getIsValid()).thenReturn(true);
        when(traceabilityService.getPublicTrace("ZTEST-BG-233700")).thenReturn(trace);

        ResponseEntity<?> resp = controller.getTraceByCode(traceCode);
        assertEquals(Boolean.TRUE, body(resp).get("success"));
        verify(traceabilityService).getPublicTrace("ZTEST-BG-233700");
    }

    @Test
    @DisplayName("未知/未打印的编码规则码 (无 Label) → 400, 不调溯源服务")
    void unknownEncodingRuleCode_returns400() {
        when(labelRepository.findByTraceCodeAndDeletedAtIsNull("TR-F006-NOPE")).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.getTraceByCode("TR-F006-NOPE");
        assertEquals(Boolean.FALSE, body(resp).get("success"));
        assertEquals(400, body(resp).get("code"));
        verify(traceabilityService, never()).getPublicTrace(anyString());
    }

    @Test
    @DisplayName("遗留 TRACE-{批次号}-{uuid} 内嵌格式仍直接解析 (不查 Label) → 200")
    void legacyEmbeddedCode_stillResolvesDirectly() {
        TraceabilityDTO.PublicTraceResponse trace = mock(TraceabilityDTO.PublicTraceResponse.class);
        lenient().when(trace.getIsValid()).thenReturn(true);
        when(traceabilityService.getPublicTrace("BATCH-001")).thenReturn(trace);

        ResponseEntity<?> resp = controller.getTraceByCode("TRACE-BATCH-001-550e8400");
        assertEquals(Boolean.TRUE, body(resp).get("success"));
        verify(traceabilityService).getPublicTrace("BATCH-001");
        verify(labelRepository, never()).findByTraceCodeAndDeletedAtIsNull(anyString());
    }
}
