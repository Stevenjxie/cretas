package com.cretas.aims.controller.rd;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.service.RawMaterialTypeService;
import com.cretas.aims.service.rd.ProductMidQuoteService;
import com.cretas.aims.service.rd.ProductSampleService;
import com.cretas.aims.service.rd.ThreePriceComparisonService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/mobile/{factoryId}/rd")
@RequiredArgsConstructor
public class RdController {

    private final ProductSampleService sampleService;
    private final ProductMidQuoteService midQuoteService;
    private final ThreePriceComparisonService threePriceService;
    private final RawMaterialTypeService materialTypeService;

    // ==================== 研发需求 ====================

    @RequirePermission({"rd:read_write"})
    @PostMapping("/requests")
    public ResponseEntity<?> createRequest(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        var req = sampleService.createRequest(factoryId,
                (String) body.get("customerName"), (String) body.get("customerContact"),
                (String) body.get("requirements"), (String) body.get("urgency"), userId);
        return ResponseEntity.ok(Map.of("success", true, "data", req, "message", "研发需求已创建"));
    }

    @RequirePermission({"rd:read_write"})
    @PostMapping("/requests/{requestId}/assign")
    public ResponseEntity<?> assignRequest(@PathVariable String factoryId, @PathVariable String requestId, @RequestBody Map<String, Object> body) {
        var req = sampleService.assignRequest(factoryId, requestId, Long.valueOf(body.get("assignedTo").toString()));
        return ResponseEntity.ok(Map.of("success", true, "data", req));
    }

    @RequirePermission({"rd:read"})
    @GetMapping("/requests")
    public ResponseEntity<?> listRequests(@PathVariable String factoryId,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                sampleService.listRequests(factoryId, status, PageRequest.of(Math.max(0, page - 1), size, Sort.by(Sort.Direction.DESC, "createdAt")))));
    }

    // ==================== 样品管理 ====================

    @RequirePermission({"rd:read_write"})
    @PostMapping("/samples")
    public ResponseEntity<?> createSample(
            @PathVariable String factoryId, @RequestBody Map<String, Object> body,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        var sample = sampleService.createSample(factoryId,
                (String) body.get("rdRequestId"), (String) body.get("name"),
                (String) body.get("specification"), (String) body.get("grade"),
                (String) body.get("mainMaterial"),
                body.get("assignedTo") != null ? Long.valueOf(body.get("assignedTo").toString()) : userId);
        boolean needSave = applyExtendedFields(sample, body);
        if (needSave) sample = sampleService.updateSampleFields(sample);
        return ResponseEntity.ok(Map.of("success", true, "data", sample, "message", "样品已创建"));
    }

    /** 单独的更新字段 endpoint, 给前端编辑用 */
    @RequirePermission({"rd:read_write"})
    @PutMapping("/samples/{sampleId}")
    public ResponseEntity<?> updateSampleFields(@PathVariable String factoryId, @PathVariable String sampleId,
                                                  @RequestBody Map<String, Object> body) {
        var sample = sampleService.getSample(factoryId, sampleId);
        applyExtendedFields(sample, body);
        sample = sampleService.updateSampleFields(sample);
        return ResponseEntity.ok(Map.of("success", true, "data", sample, "message", "样品已更新"));
    }

    /**
     * 把请求 body 里的可选字段应用到 sample 实体上 — V3 + Round 2 Agent A 字段补齐.
     * 返回是否有改动 (用来决定是否调用 save).
     */
    private boolean applyExtendedFields(com.cretas.aims.entity.rd.ProductSample sample, Map<String, Object> body) {
        boolean needSave = false;
        // V3 P0 / 客户截图 14:09 字段集
        if (body.get("specification") != null) { sample.setSpecification((String) body.get("specification")); needSave = true; }
        if (body.get("grade") != null) { sample.setGrade((String) body.get("grade")); needSave = true; }
        if (body.get("mainMaterial") != null) { sample.setMainMaterial((String) body.get("mainMaterial")); needSave = true; }
        if (body.get("productLevel") != null) { sample.setProductLevel((String) body.get("productLevel")); needSave = true; }
        if (body.get("storageMethod") != null) { sample.setStorageMethod((String) body.get("storageMethod")); needSave = true; }
        if (body.get("customerName") != null) { sample.setCustomerName((String) body.get("customerName")); needSave = true; }
        if (body.get("salesperson") != null) { sample.setSalesperson((String) body.get("salesperson")); needSave = true; }
        if (body.get("remark") != null) { sample.setRemark((String) body.get("remark")); needSave = true; }
        // Round 2 Agent A 新增 8 个字段
        if (body.get("customerExpectedPrice") != null) {
            sample.setCustomerExpectedPrice(new java.math.BigDecimal(body.get("customerExpectedPrice").toString()));
            needSave = true;
        }
        if (body.get("productStatus") != null) { sample.setProductStatus((String) body.get("productStatus")); needSave = true; }
        if (body.get("customerType") != null) { sample.setCustomerType((String) body.get("customerType")); needSave = true; }
        if (body.get("customerLatestRequirement") != null) {
            sample.setCustomerLatestRequirement((String) body.get("customerLatestRequirement"));
            needSave = true;
        }
        if (body.get("sampleVersion") != null) { sample.setSampleVersion((String) body.get("sampleVersion")); needSave = true; }
        if (body.get("sellingPoints") != null) { sample.setSellingPoints((String) body.get("sellingPoints")); needSave = true; }
        if (body.get("customerCode") != null) { sample.setCustomerCode((String) body.get("customerCode")); needSave = true; }
        if (body.get("customerLevel") != null) { sample.setCustomerLevel((String) body.get("customerLevel")); needSave = true; }
        // catalog 行31: 研发试样票务字段 — 有票/无票 + 发票号
        if (body.containsKey("hasInvoice")) {
            Object raw = body.get("hasInvoice");
            if (raw == null) {
                sample.setHasInvoice(null);
            } else if (raw instanceof Boolean) {
                sample.setHasInvoice((Boolean) raw);
            } else {
                sample.setHasInvoice(Boolean.valueOf(raw.toString()));
            }
            needSave = true;
        }
        if (body.get("invoiceNumber") != null) { sample.setInvoiceNumber((String) body.get("invoiceNumber")); needSave = true; }
        return needSave;
    }

    @RequirePermission({"rd:read_write"})
    @PostMapping("/samples/{sampleId}/progress")
    public ResponseEntity<?> updateProgress(@PathVariable String factoryId, @PathVariable String sampleId,
                                             @RequestBody Map<String, String> body) {
        var sample = sampleService.updateProgress(factoryId, sampleId, body.get("note"), body.get("photoUrl"));
        return ResponseEntity.ok(Map.of("success", true, "data", sample));
    }

    @RequirePermission({"rd:read_write"})
    @PostMapping("/samples/{sampleId}/submit")
    public ResponseEntity<?> submitForApproval(@PathVariable String factoryId, @PathVariable String sampleId,
                                                @RequestAttribute(value = "userId", required = false) Long userId) {
        return ResponseEntity.ok(Map.of("success", true, "data", sampleService.submitForApproval(factoryId, sampleId, userId)));
    }

    @RequirePermission({"rd:read_write"})
    @PostMapping("/samples/{sampleId}/approve")
    public ResponseEntity<?> approve(@PathVariable String factoryId, @PathVariable String sampleId,
                                      @RequestBody(required = false) Map<String, String> body,
                                      @RequestAttribute(value = "userId", required = false) Long userId) {
        var sample = sampleService.approveSample(factoryId, sampleId, userId, body != null ? body.get("notes") : null);
        return ResponseEntity.ok(Map.of("success", true, "data", sample, "message", "样品审核通过，报价任务已自动创建"));
    }

    @RequirePermission({"rd:read_write"})
    @PostMapping("/samples/{sampleId}/reject")
    public ResponseEntity<?> reject(@PathVariable String factoryId, @PathVariable String sampleId,
                                     @RequestBody Map<String, String> body,
                                     @RequestAttribute(value = "userId", required = false) Long userId) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                sampleService.rejectSample(factoryId, sampleId, userId, body.get("notes"))));
    }

    @RequirePermission({"rd:read"})
    @GetMapping("/samples")
    public ResponseEntity<?> listSamples(@PathVariable String factoryId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                sampleService.listSamples(factoryId, status, PageRequest.of(Math.max(0, page - 1), size, Sort.by(Sort.Direction.DESC, "createdAt")))));
    }

    @RequirePermission({"rd:read"})
    @GetMapping("/samples/{sampleId}")
    public ResponseEntity<?> getSample(@PathVariable String factoryId, @PathVariable String sampleId) {
        return ResponseEntity.ok(Map.of("success", true, "data", sampleService.getSample(factoryId, sampleId)));
    }

    /**
     * P1-8: 读取样品追踪记录（新独立表）.
     * 前端 openTrackingDialog 调此接口取代从 progressNotes JSON 解析.
     */
    @RequirePermission({"rd:read"})
    @GetMapping("/samples/{sampleId}/tracking-records")
    public ResponseEntity<?> getTrackingRecords(@PathVariable String factoryId, @PathVariable String sampleId) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                sampleService.getTrackingRecords(factoryId, sampleId)));
    }

    // ==================== 报价任务 ====================

    @RequirePermission({"rd:read_write"})
    @PostMapping("/quotations/{taskId}/submit")
    public ResponseEntity<?> submitQuotation(@PathVariable String taskId, @RequestBody Map<String, Object> body,
                                              @RequestAttribute(value = "userId", required = false) Long userId) {
        for (String field : new String[]{"materialCost", "laborCost", "overheadCost", "suggestedPrice"}) {
            if (body.get(field) == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "缺少必填字段: " + field));
            }
        }
        // feedback_dto_roundtrip_silent_drop fix: 第1处 — controller 层提取 laborPerKg (可选, null 透传)
        BigDecimal laborPerKg = body.get("laborPerKg") != null
                ? new BigDecimal(body.get("laborPerKg").toString()) : null;
        var task = sampleService.submitQuotation(taskId,
                new BigDecimal(body.get("materialCost").toString()),
                new BigDecimal(body.get("laborCost").toString()),
                new BigDecimal(body.get("overheadCost").toString()),
                new BigDecimal(body.get("suggestedPrice").toString()),
                laborPerKg, userId);
        return ResponseEntity.ok(Map.of("success", true, "data", task, "message", "报价已提交"));
    }

    @RequirePermission({"rd:read_write"})
    @PostMapping("/quotations/{taskId}/confirm")
    public ResponseEntity<?> confirmQuotation(@PathVariable String taskId, @RequestBody Map<String, Object> body,
                                               @RequestAttribute(value = "userId", required = false) Long userId) {
        if (body.get("finalPrice") == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "缺少必填字段: finalPrice"));
        }
        var task = sampleService.confirmQuotation(taskId, new BigDecimal(body.get("finalPrice").toString()), userId);
        return ResponseEntity.ok(Map.of("success", true, "data", task, "message", "报价已确认"));
    }

    @RequirePermission({"rd:read"})
    @GetMapping("/quotations")
    public ResponseEntity<?> listQuotations(@PathVariable String factoryId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                sampleService.listQuotations(factoryId, status, PageRequest.of(Math.max(0, page - 1), size, Sort.by(Sort.Direction.DESC, "createdAt")))));
    }

    // ==================== SP10: 中报价 ====================

    /**
     * SP10: 根据试制批次汇算中报价.
     *
     * <p>POST /api/mobile/{factoryId}/rd/quotations/{taskId}/calculate-mid-quote</p>
     * <p>Body: { trialBatchId, materialCostPerKg, laborCostPerKg, overheadCostPerKg, varianceThresholdPct? }</p>
     */
    @RequirePermission({"rd:read_write"})
    @PostMapping("/quotations/{taskId}/calculate-mid-quote")
    public ResponseEntity<?> calculateMidQuote(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            @RequestBody Map<String, Object> body) {

        if (body.get("trialBatchId") == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "缺少必填字段: trialBatchId"));
        }

        Long trialBatchId = Long.valueOf(body.get("trialBatchId").toString());
        BigDecimal materialCost = body.get("materialCostPerKg") != null
                ? new BigDecimal(body.get("materialCostPerKg").toString()) : null;
        BigDecimal laborCost = body.get("laborCostPerKg") != null
                ? new BigDecimal(body.get("laborCostPerKg").toString()) : null;
        BigDecimal overheadCost = body.get("overheadCostPerKg") != null
                ? new BigDecimal(body.get("overheadCostPerKg").toString()) : null;
        BigDecimal threshold = body.get("varianceThresholdPct") != null
                ? new BigDecimal(body.get("varianceThresholdPct").toString()) : null;

        var result = midQuoteService.calculate(
                factoryId, trialBatchId, taskId,
                materialCost, laborCost, overheadCost, threshold);

        return ResponseEntity.ok(Map.of("success", true, "data", result, "message", "中报价汇算完成"));
    }

    /**
     * SP10: 获取三价对比.
     *
     * <p>GET /api/mobile/{factoryId}/rd/quotations/{taskId}/three-price-comparison</p>
     * <p>通过报价任务 ID 解析 sampleId 后返回预报价/中报价/实际成本三价对比.</p>
     */
    @RequirePermission({"rd:read"})
    @GetMapping("/quotations/{taskId}/three-price-comparison")
    public ResponseEntity<?> getThreePriceComparison(
            @PathVariable String factoryId,
            @PathVariable String taskId) {
        // 修复: 原实现直接把 taskId 当 sampleId 传入, 导致三价对比永远查不到数据.
        // 正确做法: 通过 QuotationTask 解析 sampleId, 再按 sampleId 聚合三价.
        var dto = threePriceService.getThreePriceComparisonByTaskId(factoryId, taskId);
        return ResponseEntity.ok(Map.of("success", true, "data", dto));
    }

    /**
     * SP10: 获取中报价详情 (by id).
     *
     * <p>GET /api/mobile/{factoryId}/rd/mid-quotes/{midQuoteId}</p>
     */
    @RequirePermission({"rd:read"})
    @GetMapping("/mid-quotes/{midQuoteId}")
    public ResponseEntity<?> getMidQuoteById(
            @PathVariable String factoryId,
            @PathVariable String midQuoteId) {
        var midQuote = midQuoteService.getMidQuoteById(factoryId, midQuoteId);
        return ResponseEntity.ok(Map.of("success", true, "data", midQuote));
    }

    /**
     * SP10: 确认中报价 (CALCULATED → CONFIRMED).
     *
     * <p>POST /api/mobile/{factoryId}/rd/mid-quotes/{midQuoteId}/confirm</p>
     * <p>Body: { notes? } — 可选备注, 无则忽略.</p>
     */
    @RequirePermission({"rd:read_write"})
    @PostMapping("/mid-quotes/{midQuoteId}/confirm")
    public ResponseEntity<?> confirmMidQuote(
            @PathVariable String factoryId,
            @PathVariable String midQuoteId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestAttribute(value = "userId", required = false) Long userId) {

        String notes = (body != null && body.get("notes") != null)
                ? body.get("notes").toString() : null;

        var confirmed = midQuoteService.confirmMidQuote(factoryId, midQuoteId, notes, userId);
        return ResponseEntity.ok(Map.of("success", true, "data", confirmed, "message", "中报价已确认"));
    }

    // ==================== R14: 研发试样选料 — 按价格区间推荐 ====================

    /**
     * R14: 按单价范围筛选候选原材料 (研发试样建单辅助).
     *
     * <pre>
     * GET /api/mobile/{factoryId}/rd/materials/suggest-by-price
     *     ?minPrice=1.00&maxPrice=50.00
     * </pre>
     *
     * <p>返回 unitPrice 在 [minPrice, maxPrice] 范围内的物料, 按单价升序, 最多100条.
     * minPrice / maxPrice 均可不传 (null = 无下/上限). 无报价的物料不参与候选.
     *
     * <p>RBAC: rd:read 可读; 价格字段由 PriceFieldResponseAdvice 对非财务角色自动遮蔽.
     */
    @RequirePermission({"rd:read"})
    @GetMapping("/materials/suggest-by-price")
    public ResponseEntity<?> suggestMaterialsByPrice(
            @PathVariable String factoryId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice) {

        var materials = materialTypeService.suggestMaterialsByPriceRange(factoryId, minPrice, maxPrice);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", materials,
                "message", "共找到 " + materials.size() + " 种原材料"));
    }

    // ==================== End R14 ====================
}
