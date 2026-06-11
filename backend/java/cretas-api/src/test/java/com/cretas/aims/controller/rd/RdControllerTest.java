package com.cretas.aims.controller.rd;

import com.cretas.aims.dto.rd.MidQuoteCalculationResultDTO;
import com.cretas.aims.dto.rd.ThreePriceComparisonDTO;
import com.cretas.aims.entity.rd.ProductMidQuote;
import com.cretas.aims.entity.rd.ProductSample;
import com.cretas.aims.entity.rd.QuotationTask;
import com.cretas.aims.entity.rd.RdRequest;
import com.cretas.aims.service.RawMaterialTypeService;
import com.cretas.aims.service.rd.ProductMidQuoteService;
import com.cretas.aims.service.rd.ProductSampleService;
import com.cretas.aims.service.rd.ThreePriceComparisonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Controller-layer tests for {@link RdController}.
 *
 * <p>Pattern: direct instantiation + Mockito (@InjectMocks) — same as
 * {@link com.cretas.aims.controller.PaymentRecordControllerTest}.
 *
 * <p>覆盖:
 * <ul>
 *   <li>研发需求: createRequest / listRequests</li>
 *   <li>样品管理: createSample / approve / reject / listSamples / getSample</li>
 *   <li>报价: submitQuotation (必填校验) / listQuotations</li>
 *   <li>SP10 中报价: calculateMidQuote (必填校验) / getThreePriceComparison</li>
 *   <li>RBAC: RequirePermission 注解存在性检查 (不走过滤链但确认注解在方法上)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RdController 单元测试")
class RdControllerTest {

    private static final String FACTORY_ID = "F006";

    @Mock private ProductSampleService      sampleService;
    @Mock private ProductMidQuoteService    midQuoteService;
    @Mock private ThreePriceComparisonService threePriceService;
    @Mock private RawMaterialTypeService    materialTypeService;

    @InjectMocks
    private RdController controller;

    // =========================================================================
    // 研发需求 — createRequest
    // =========================================================================

    @Test
    @DisplayName("RD-CT-01: createRequest — 200 + success=true + data 返回")
    @SuppressWarnings("unchecked")
    void createRequest_happyPath() {
        RdRequest stub = new RdRequest();
        stub.setRequestNumber("RD-20260611-0001");
        stub.setStatus("OPEN");
        when(sampleService.createRequest(eq(FACTORY_ID), eq("鲜湘缘"), any(), any(), any(), any()))
                .thenReturn(stub);

        Map<String, Object> body = new HashMap<>();
        body.put("customerName", "鲜湘缘");
        body.put("requirements", "猪舌新品");

        ResponseEntity<?> resp = controller.createRequest(FACTORY_ID, body, 1L);

        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> responseBody = (Map<String, Object>) resp.getBody();
        assertThat(responseBody.get("success")).isEqualTo(true);
        assertThat(responseBody.get("data")).isEqualTo(stub);
        assertThat(responseBody.get("message").toString()).contains("研发需求");
    }

    // =========================================================================
    // 研发需求 — listRequests
    // =========================================================================

    @Test
    @DisplayName("RD-CT-02: listRequests — 返回分页数据")
    @SuppressWarnings("unchecked")
    void listRequests_returnsPaged() {
        Page<RdRequest> page = new PageImpl<>(List.of(new RdRequest()));
        when(sampleService.listRequests(eq(FACTORY_ID), any(), any(Pageable.class)))
                .thenReturn(page);

        ResponseEntity<?> resp = controller.listRequests(FACTORY_ID, null, 0, 20);

        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("data")).isEqualTo(page);
    }

    // =========================================================================
    // 样品 — createSample
    // =========================================================================

    @Test
    @DisplayName("RD-CT-03: createSample — 200 + success=true")
    @SuppressWarnings("unchecked")
    void createSample_happyPath() {
        ProductSample stub = buildSample("SC-001", "DRAFT");
        when(sampleService.createSample(eq(FACTORY_ID), any(), eq("卤猪舌"), any(), any(), any(), any()))
                .thenReturn(stub);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "卤猪舌");

        ResponseEntity<?> resp = controller.createSample(FACTORY_ID, body, 1L);

        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("success")).isEqualTo(true);
        assertThat(respBody.get("data")).isEqualTo(stub);
    }

    // =========================================================================
    // 样品 — approve
    // =========================================================================

    @Test
    @DisplayName("RD-CT-04: approve — 200 + message 含'审核通过'")
    @SuppressWarnings("unchecked")
    void approveSample_happyPath() {
        ProductSample approved = buildSample("SC-001", "APPROVED");
        when(sampleService.approveSample(eq(FACTORY_ID), eq("sample-001"), any(), any()))
                .thenReturn(approved);

        Map<String, String> body = Map.of("notes", "质量合格");
        ResponseEntity<?> resp = controller.approve(FACTORY_ID, "sample-001", body, 1L);

        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("success")).isEqualTo(true);
        assertThat(respBody.get("message").toString()).contains("审核通过");
    }

    // =========================================================================
    // 样品 — reject
    // =========================================================================

    @Test
    @DisplayName("RD-CT-05: reject — 200 + success=true")
    @SuppressWarnings("unchecked")
    void rejectSample_happyPath() {
        ProductSample rejected = buildSample("SC-001", "REJECTED");
        when(sampleService.rejectSample(eq(FACTORY_ID), eq("sample-001"), any(), eq("味道不达标")))
                .thenReturn(rejected);

        Map<String, String> body = Map.of("notes", "味道不达标");
        ResponseEntity<?> resp = controller.reject(FACTORY_ID, "sample-001", body, 1L);

        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("success")).isEqualTo(true);
    }

    // =========================================================================
    // 样品 — listSamples
    // =========================================================================

    @Test
    @DisplayName("RD-CT-06: listSamples — 返回分页数据")
    @SuppressWarnings("unchecked")
    void listSamples_returnsPaged() {
        Page<ProductSample> page = new PageImpl<>(List.of(buildSample("SC-001", "DRAFT")));
        when(sampleService.listSamples(eq(FACTORY_ID), any(), any(Pageable.class)))
                .thenReturn(page);

        ResponseEntity<?> resp = controller.listSamples(FACTORY_ID, null, 0, 20);
        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("data")).isEqualTo(page);
    }

    // =========================================================================
    // 样品 — getSample
    // =========================================================================

    @Test
    @DisplayName("RD-CT-07: getSample — 按 factoryId + sampleId 查询")
    @SuppressWarnings("unchecked")
    void getSample_happyPath() {
        ProductSample stub = buildSample("SC-001", "APPROVED");
        when(sampleService.getSample(eq(FACTORY_ID), eq("sample-001"))).thenReturn(stub);

        ResponseEntity<?> resp = controller.getSample(FACTORY_ID, "sample-001");
        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("data")).isEqualTo(stub);
    }

    // =========================================================================
    // 报价 — submitQuotation 必填校验
    // =========================================================================

    @Test
    @DisplayName("RD-CT-08: submitQuotation — 缺 materialCost → 400 badRequest")
    @SuppressWarnings("unchecked")
    void submitQuotation_missingMaterialCost_badRequest() {
        Map<String, Object> body = new HashMap<>();
        body.put("laborCost", "5.00");
        body.put("overheadCost", "2.00");
        body.put("suggestedPrice", "30.00");
        // materialCost 缺失

        ResponseEntity<?> resp = controller.submitQuotation("task-001", body, 1L);

        assertEquals(400, resp.getStatusCode().value());
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("success")).isEqualTo(false);
        assertThat(respBody.get("message").toString()).contains("materialCost");
        verify(sampleService, never()).submitQuotation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("RD-CT-09: submitQuotation — 全字段 → 调用 service, 200 success")
    @SuppressWarnings("unchecked")
    void submitQuotation_allFields_success() {
        QuotationTask task = new QuotationTask();
        task.setId("task-001");
        task.setStatus("QUOTED");
        when(sampleService.submitQuotation(eq("task-001"),
                eq(new BigDecimal("20.00")), eq(new BigDecimal("5.00")),
                eq(new BigDecimal("2.00")), eq(new BigDecimal("30.00")),
                isNull(), eq(1L)))
                .thenReturn(task);

        Map<String, Object> body = new HashMap<>();
        body.put("materialCost", "20.00");
        body.put("laborCost", "5.00");
        body.put("overheadCost", "2.00");
        body.put("suggestedPrice", "30.00");

        ResponseEntity<?> resp = controller.submitQuotation("task-001", body, 1L);
        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("success")).isEqualTo(true);
        assertThat(respBody.get("message").toString()).contains("报价已提交");
    }

    // =========================================================================
    // 报价 — confirmQuotation 必填校验
    // =========================================================================

    @Test
    @DisplayName("RD-CT-10: confirmQuotation — 缺 finalPrice → 400 badRequest")
    @SuppressWarnings("unchecked")
    void confirmQuotation_missingFinalPrice_badRequest() {
        Map<String, Object> body = new HashMap<>();

        ResponseEntity<?> resp = controller.confirmQuotation("task-001", body, 1L);

        assertEquals(400, resp.getStatusCode().value());
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("success")).isEqualTo(false);
        assertThat(respBody.get("message").toString()).contains("finalPrice");
    }

    // =========================================================================
    // 报价 — listQuotations
    // =========================================================================

    @Test
    @DisplayName("RD-CT-11: listQuotations — 返回分页数据")
    @SuppressWarnings("unchecked")
    void listQuotations_returnsPaged() {
        Page<QuotationTask> page = new PageImpl<>(List.of(new QuotationTask()));
        when(sampleService.listQuotations(eq(FACTORY_ID), any(), any(Pageable.class)))
                .thenReturn(page);

        ResponseEntity<?> resp = controller.listQuotations(FACTORY_ID, null, 0, 20);
        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("data")).isEqualTo(page);
    }

    // =========================================================================
    // SP10 — calculateMidQuote 必填校验
    // =========================================================================

    @Test
    @DisplayName("RD-CT-12: calculateMidQuote — 缺 trialBatchId → 400 badRequest")
    @SuppressWarnings("unchecked")
    void calculateMidQuote_missingTrialBatchId_badRequest() {
        Map<String, Object> body = Map.of("materialCostPerKg", "20.00");

        ResponseEntity<?> resp = controller.calculateMidQuote(FACTORY_ID, "task-001", body);

        assertEquals(400, resp.getStatusCode().value());
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("success")).isEqualTo(false);
        assertThat(respBody.get("message").toString()).contains("trialBatchId");
        verify(midQuoteService, never()).calculate(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("RD-CT-13: calculateMidQuote — 提供 trialBatchId → 调用 service, 200 success")
    @SuppressWarnings("unchecked")
    void calculateMidQuote_validInput_success() {
        MidQuoteCalculationResultDTO result = new MidQuoteCalculationResultDTO();
        when(midQuoteService.calculate(eq(FACTORY_ID), eq(1001L), eq("task-001"),
                any(), any(), any(), any()))
                .thenReturn(result);

        Map<String, Object> body = new HashMap<>();
        body.put("trialBatchId", "1001");

        ResponseEntity<?> resp = controller.calculateMidQuote(FACTORY_ID, "task-001", body);

        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("success")).isEqualTo(true);
        assertThat(respBody.get("message").toString()).contains("中报价");
    }

    // =========================================================================
    // SP10 — getThreePriceComparison
    // =========================================================================

    @Test
    @DisplayName("RD-CT-14: getThreePriceComparison — 通过 taskId 查三价对比")
    @SuppressWarnings("unchecked")
    void getThreePriceComparison_happyPath() {
        ThreePriceComparisonDTO dto = new ThreePriceComparisonDTO();
        when(threePriceService.getThreePriceComparisonByTaskId(eq(FACTORY_ID), eq("task-001")))
                .thenReturn(dto);

        ResponseEntity<?> resp = controller.getThreePriceComparison(FACTORY_ID, "task-001");

        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("data")).isEqualTo(dto);
        // 关键修复验证: 使用 getThreePriceComparisonByTaskId 而非直接把 taskId 当 sampleId
        verify(threePriceService).getThreePriceComparisonByTaskId(eq(FACTORY_ID), eq("task-001"));
        verify(threePriceService, never()).getThreePriceComparison(any(), any());
    }

    // =========================================================================
    // SP10 — getMidQuoteById
    // =========================================================================

    @Test
    @DisplayName("RD-CT-15: getMidQuoteById — 按 midQuoteId 查中报价详情")
    @SuppressWarnings("unchecked")
    void getMidQuoteById_happyPath() {
        ProductMidQuote midQuote = new ProductMidQuote();
        when(midQuoteService.getMidQuoteById(eq(FACTORY_ID), eq("mq-001")))
                .thenReturn(midQuote);

        ResponseEntity<?> resp = controller.getMidQuoteById(FACTORY_ID, "mq-001");

        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("data")).isEqualTo(midQuote);
    }

    // =========================================================================
    // R14 — suggestMaterialsByPrice
    // =========================================================================

    @Test
    @DisplayName("RD-CT-16: suggestMaterialsByPrice — 返回物料列表 + message 含件数")
    @SuppressWarnings("unchecked")
    void suggestMaterialsByPrice_returnsMaterials() {
        when(materialTypeService.suggestMaterialsByPriceRange(eq(FACTORY_ID), any(), any()))
                .thenReturn(List.of());

        ResponseEntity<?> resp = controller.suggestMaterialsByPrice(
                FACTORY_ID, new BigDecimal("5.00"), new BigDecimal("50.00"));

        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("message").toString()).contains("0");
    }

    // =========================================================================
    // RBAC 注解存在性检查 — 关键端点必须有 @RequirePermission
    // =========================================================================

    @Test
    @DisplayName("RD-CT-17: RBAC — createSample 方法上存在 @RequirePermission 注解")
    void rbac_createSample_hasRequirePermission() throws NoSuchMethodException {
        var method = RdController.class.getMethod("createSample",
                String.class, Map.class, Long.class);
        var annotation = method.getAnnotation(com.cretas.aims.annotation.RequirePermission.class);
        assertNotNull(annotation, "createSample 必须有 @RequirePermission");
        assertThat(annotation.value()).containsAnyOf("rd:read_write");
    }

    @Test
    @DisplayName("RD-CT-18: RBAC — approve 方法上存在 @RequirePermission 注解")
    void rbac_approve_hasRequirePermission() throws NoSuchMethodException {
        var method = RdController.class.getMethod("approve",
                String.class, String.class, Map.class, Long.class);
        var annotation = method.getAnnotation(com.cretas.aims.annotation.RequirePermission.class);
        assertNotNull(annotation, "approve 必须有 @RequirePermission");
        assertThat(annotation.value()).containsAnyOf("rd:read_write");
    }

    @Test
    @DisplayName("RD-CT-19: RBAC — listSamples 方法上存在 @RequirePermission(rd:read)")
    void rbac_listSamples_hasRequirePermission() throws NoSuchMethodException {
        var method = RdController.class.getMethod("listSamples",
                String.class, String.class, int.class, int.class);
        var annotation = method.getAnnotation(com.cretas.aims.annotation.RequirePermission.class);
        assertNotNull(annotation, "listSamples 必须有 @RequirePermission");
        assertThat(annotation.value()).containsAnyOf("rd:read");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ProductSample buildSample(String sampleCode, String status) {
        ProductSample s = new ProductSample();
        s.setId("sample-001");
        s.setFactoryId(FACTORY_ID);
        s.setSampleCode(sampleCode);
        s.setStatus(status);
        s.setName("卤猪舌");
        return s;
    }
}
