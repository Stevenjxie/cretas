package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.template.PrintPreviewTemplateRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C-PRT-1 — 单据打印 PDF Java 入口.
 *
 * <p>5 单据 GET endpoint, 每个端点:
 * <ol>
 *   <li>校验访问权限 (跟随实体权限, factoryId 隔离, RBAC defense-in-depth)</li>
 *   <li>组装打印 payload (entity → flat dict)</li>
 *   <li>RBAC: 无 {@code procurement:price:view} 权限的角色, 单价/小计/合计 等敏感字段
 *       在 payload 阶段 strip 为 "—" (PR #423 PriceFieldResponseAdvice 只 walk JSON,
 *       不 walk byte[] PDF — 必须在交给 Python 渲染之前 mask)</li>
 *   <li>POST 到 Python {@code /api/printing/{type}} 取 PDF bytes</li>
 *   <li>流回客户端 (Content-Disposition: attachment)</li>
 * </ol>
 *
 * <p><b>Sprint1-Fix-K2 (2026-05-15)</b>: PR #659 merge 时 5 endpoint 0 @RequirePermission +
 * 0 价格脱敏. 仓库管理员可以拉销售/采购订单 PDF 看到完整单价 — 绕开 PR #423 框架. 本 follow-up:
 * <ul>
 *   <li>每个 endpoint 加 module:read 网关 ({@code sales/procurement/production})</li>
 *   <li>{@link PriceMaskResolver#shouldMaskPrice(String)} 决定是否脱敏 payload 的金额字段</li>
 *   <li>{@code production-task} + {@code material-requisition} 不含金额, 仅做 RBAC 门禁</li>
 * </ul>
 *
 * @author Cretas Team — Track C
 * @since 2026-05-15 (C-PRT-1)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/print")
@CrossOrigin(origins = {"https://www.cretaceousfuture.com", "http://139.196.165.140:8086", "http://localhost:5173"})
public class PrintController {

    /** Sentinel value used in printed PDFs for users without procurement:price:view permission. */
    private static final String PRICE_MASK = "—";

    /** Top-level payload keys whose value is a price/total — replace with {@link #PRICE_MASK} when masked. */
    private static final Set<String> PRICE_TOPLEVEL_KEYS = Set.of(
            "totalAmount",
            "subtotal",
            "taxAmount",
            "discountAmount",
            "payableAmount",
            "paidAmount",
            "balance"
    );

    /** Per-line-item keys inside {@code items[]} that are price-bearing. */
    private static final Set<String> PRICE_ITEM_KEYS = Set.of(
            "unitPrice",
            "subtotal",
            "totalAmount",
            "amount",
            "price",
            "discount",
            "discountAmount",
            "tax",
            "taxAmount"
    );

    private final RestTemplate pythonRestTemplate;
    private final String pythonBaseUrl;
    private final PriceMaskResolver priceMaskResolver;

    /** Optional: 生产计划服务 (SP12 T8 公单打印取数). */
    @Autowired(required = false)
    private ProductionPlanService productionPlanService;

    /** Optional: 物料需求单服务 (SP12 T8 汇总领料单取数). */
    @Autowired(required = false)
    private FactoryMaterialRequisitionService factoryMaterialRequisitionService;

    /** Optional: 工序任务服务 (SP12 T8 生产工单工序列表取数). */
    @Autowired(required = false)
    private WorkProcessTaskService workProcessTaskService;

    /** Optional: 生产批次 repository (SP12 T8 通过 planId 找批次). */
    @Autowired(required = false)
    private ProductionBatchRepository productionBatchRepository;

    @Autowired
    public PrintController(
            @Qualifier("pythonAiRestTemplate") RestTemplate pythonRestTemplate,
            @Qualifier("pythonAiBaseUrl") String pythonBaseUrl,
            PriceMaskResolver priceMaskResolver) {
        this.pythonRestTemplate = pythonRestTemplate;
        this.pythonBaseUrl = pythonBaseUrl;
        this.priceMaskResolver = priceMaskResolver;
    }

    // ==================== 5 单据 endpoint ====================

    @GetMapping("/sales-order/{id}")
    @RequirePermission({"sales:read", "sales:read_write"})
    public ResponseEntity<byte[]> printSalesOrder(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> payload = buildSalesOrderPayload(factoryId, id, overrides);
        applyPriceMask(payload, authorization, "sales-order", true);
        return proxyToPython("sales-order", payload, "sales-order-" + id, authorization);
    }

    @GetMapping("/purchase-order/{id}")
    @RequirePermission({"procurement:read", "procurement:read_write"})
    public ResponseEntity<byte[]> printPurchaseOrder(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> payload = buildPurchaseOrderPayload(factoryId, id, overrides);
        applyPriceMask(payload, authorization, "purchase-order", true);
        return proxyToPython("purchase-order", payload, "purchase-order-" + id, authorization);
    }

    @GetMapping("/quotation/{id}")
    @RequirePermission({"sales:read", "sales:read_write"})
    public ResponseEntity<byte[]> printQuotation(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> payload = buildQuotationPayload(factoryId, id, overrides);
        applyPriceMask(payload, authorization, "quotation", true);
        return proxyToPython("quotation", payload, "quotation-" + id, authorization);
    }

    @GetMapping("/production-task/{id}")
    @RequirePermission({"production:read", "production:read_write"})
    public ResponseEntity<byte[]> printProductionTask(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        // 生产任务单不含金额字段, 仅 RBAC 门禁即可.
        Map<String, Object> payload = buildProductionTaskPayload(factoryId, id, overrides);
        return proxyToPython("production-task", payload, "production-task-" + id, authorization);
    }

    @GetMapping("/material-requisition/{id}")
    @RequirePermission({"procurement:read", "procurement:read_write",
            "warehouse:read", "warehouse:read_write"})
    public ResponseEntity<byte[]> printMaterialRequisition(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        // 领料单不含金额字段, 仅 RBAC 门禁即可.
        Map<String, Object> payload = buildMaterialRequisitionPayload(factoryId, id, overrides);
        return proxyToPython("material-requisition", payload, "material-requisition-" + id, authorization);
    }

    // ==================== Sprint 6 W3-C — 3 P1 templates (Round 13 §13) ====================
    //
    // 3 new print template categories. HJ baseline 21 categories vs Cretas 6/21 = 29%;
    // shipping these 3 brings to 9/21 = 43% (still need static / weighing / serial /
    // 售后 / 委外 / 等 for full parity).

    /**
     * 仓库出入库单 PDF (Sprint 6 W3-C P1).
     *
     * <p>F006 高频 仓管员场景. movementType in overrides decides 入库 vs 出库 title.
     * 不含金额字段 (qty/spec/batch/location only), 仅 RBAC 门禁即可.
     */
    @GetMapping("/stock-movement/{id}")
    @RequirePermission({"warehouse:read", "warehouse:read_write"})
    public ResponseEntity<byte[]> printStockMovement(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> payload = buildStockMovementPayload(factoryId, id, overrides);
        return proxyToPython("stock-movement", payload, "stock-movement-" + id, authorization);
    }

    /**
     * 财务发票/凭证 PDF (Sprint 6 W3-C P1) — 跟 PR #52 数电票 协同.
     *
     * <p>invoiceType: VAT_NORMAL (普票) / VAT_SPECIAL (专票) / VOUCHER (记账凭证).
     * 含金额字段 (subtotal/taxAmount/totalAmount + items unitPrice/taxAmount/subtotal),
     * 必走 priceMaskResolver 防 仓库管理员 等无 procurement:price:view 角色看金额.
     */
    @GetMapping("/financial-invoice/{id}")
    @RequirePermission({"finance:read", "finance:read_write"})
    public ResponseEntity<byte[]> printFinancialInvoice(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> payload = buildFinancialInvoicePayload(factoryId, id, overrides);
        applyPriceMask(payload, authorization, "financial-invoice", true);
        return proxyToPython("financial-invoice", payload, "invoice-" + id, authorization);
    }

    /**
     * 装箱单 PDF (Sprint 6 W3-C P1) — 跟 N13 W-ABA-1 抄码品 配合.
     *
     * <p>cartons[].abacaCode 承接 weighing 抄码品标签 (per .claude rules
     * `reference_abaca_term.md` — exact match '抄码' 非 '超码').
     * 不含金额字段 (重量/箱号/规格 only), 仅 RBAC 门禁即可. 允许 sales / warehouse
     * 双门 — 销售员发起装箱 + 仓管员复核装箱都得能拉.
     */
    @GetMapping("/packing-list/{id}")
    @RequirePermission({"sales:read", "sales:read_write",
            "warehouse:read", "warehouse:read_write"})
    public ResponseEntity<byte[]> printPackingList(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> payload = buildPackingListPayload(factoryId, id, overrides);
        return proxyToPython("packing-list", payload, "packing-list-" + id, authorization);
    }

    // ==================== SP12 T8 — 2 新端点: 公单 + 汇总领料单 ====================

    /**
     * 生产工单 (公单) PDF (SP12 T8).
     *
     * <p>按计划 ID 拉取计划详情 + 工序列表，组装后 POST 到 Python 渲染。
     * 不含金额字段 (工序/数量/工时 only), 仅 RBAC 门禁即可。
     *
     * <p>G4 要求: ProductionPlanService.getProductionPlanById + 工序列表 + 汇总材料.
     */
    @GetMapping("/production-work-order/{planId}")
    @RequirePermission({"production:read", "production:read_write"})
    public ResponseEntity<byte[]> printProductionWorkOrder(
            @PathVariable String factoryId,
            @PathVariable String planId,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> payload = buildProductionWorkOrderPayload(factoryId, planId, overrides);
        return proxyToPython("production-work-order", payload, "work-order-" + planId, authorization);
    }

    /**
     * 汇总领料单 PDF (SP12 T8).
     *
     * <p>跨批次汇总同一计划下所有领料需求，POST 到 Python 渲染。
     * 不含金额字段 (物料/数量/规格 only), 仅 RBAC 门禁即可.
     *
     * <p>G4 要求: FactoryMaterialRequisitionService.listByPlan 跨批次汇总.
     */
    @GetMapping("/consolidated-material-requisition/{planId}")
    @RequirePermission({"production:read", "production:read_write",
            "warehouse:read", "warehouse:read_write"})
    public ResponseEntity<byte[]> printConsolidatedMaterialRequisition(
            @PathVariable String factoryId,
            @PathVariable String planId,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> payload = buildConsolidatedMaterialRequisitionPayload(factoryId, planId, overrides);
        return proxyToPython("consolidated-material-requisition", payload,
                "consolidated-req-" + planId, authorization);
    }

    // ==================== C-PRT-EDITOR-1 (Sprint 3 Track-J) ====================

    /**
     * Schema-driven PDF preview.
     *
     * <p>Co-exists with the 5 hardcoded endpoints above (which keep serving
     * the legacy fixed-layout PDFs); this endpoint renders whatever schema
     * the print template designer has saved (or an inline schema for live
     * editor preview without persistence).
     *
     * <p>Day 4 MVP scope:
     * <ul>
     *   <li>Body shape: {@code {templateId?, inlineSchemaJson?, entityType, entityId?, mockData?}}</li>
     *   <li>One of {@code templateId} or {@code inlineSchemaJson} required.</li>
     *   <li>{@code mockData} (sample entity payload from editor) is used as
     *       {@code entityData} when {@code entityId} is absent. Day 5+ will
     *       fetch real entity rows when {@code entityId} is supplied.</li>
     *   <li>Java masks price fields in the data dict via {@link PriceMaskResolver}
     *       BEFORE proxying to Python — Python does not know about RBAC.</li>
     * </ul>
     *
     * <p>RBAC: {@code system:read} is enough — any authenticated user can
     * preview templates for their own factory. The mutation endpoint that
     * saves templates (FormTemplateController) keeps its existing
     * {@code system:read_write} gate.
     *
     * @since 2026-05-16 (Sprint 3 Track-J Day 4)
     */
    @PostMapping("/preview-template")
    @RequirePermission({"system:read", "system:read_write"})
    public ResponseEntity<byte[]> printPreviewTemplate(
            @PathVariable String factoryId,
            // Issue #712 fix (2026-05-17): typed DTO + @Valid replaces raw Map; either-or
            // (templateId vs inlineSchemaJson) still enforced below since Bean Validation
            // alone can't express that cross-field constraint without a custom validator.
            @Valid @RequestBody PrintPreviewTemplateRequest req,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        String templateId = req.getTemplateId();
        String inlineSchemaJson = req.getInlineSchemaJson();
        String entityType = req.getEntityType();

        if ((templateId == null || templateId.isBlank())
                && (inlineSchemaJson == null || inlineSchemaJson.isBlank())) {
            throw new BusinessException(400, "templateId 或 inlineSchemaJson 至少一项");
        }
        // entityType + PRINT_ prefix already enforced by @NotBlank + @Pattern on DTO.

        // ── resolve entity data (mockData for editor / TODO: real entity for entityId) ─
        Map<String, Object> entityData;
        if (req.getMockData() != null) {
            entityData = new HashMap<>(req.getMockData());
        } else {
            entityData = new HashMap<>();
        }
        // Day 5+: when entityId present, fetch real entity via the corresponding
        // service (SalesOrderService.getById etc.) and overwrite entityData.

        // ── price masking — same pattern as the 5 hardcoded endpoints ────
        boolean hasMonetary = !entityType.equals("PRINT_PRODUCTION_TASK")
                && !entityType.equals("PRINT_MATERIAL_REQUISITION");
        applyPriceMask(entityData, authorization, entityType, hasMonetary);

        // ── proxy to Python /api/printing/preview-template ───────────────
        Map<String, Object> pythonBody = new HashMap<>();
        pythonBody.put("factoryId", factoryId);
        pythonBody.put("templateId", templateId);
        pythonBody.put("inlineSchemaJson", inlineSchemaJson);
        pythonBody.put("entityType", entityType);
        pythonBody.put("entityData", entityData);

        String url = pythonBaseUrl + "/api/printing/preview-template";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Forward inbound Authorization so Python auth_middleware accepts
        // the request (mirrors PR #692 fix to proxyToPython, 2026-05-16).
        if (authorization != null && !authorization.isEmpty()) {
            headers.set("Authorization", authorization);
        }
        HttpEntity<Map<String, Object>> httpRequest = new HttpEntity<>(pythonBody, headers);

        try {
            ResponseEntity<byte[]> resp = pythonRestTemplate.exchange(
                    url, HttpMethod.POST, httpRequest, byte[].class);
            byte[] pdf = resp.getBody();
            if (pdf == null || pdf.length == 0) {
                throw new BusinessException(502, "Python 打印服务返空 PDF");
            }
            HttpHeaders out = new HttpHeaders();
            out.setContentType(MediaType.APPLICATION_PDF);
            String filename = "preview-" + entityType + "-" + (templateId != null ? templateId : "inline");
            out.setContentDisposition(org.springframework.http.ContentDisposition
                    .attachment().filename(filename + ".pdf").build());
            out.setContentLength(pdf.length);
            return new ResponseEntity<>(pdf, out, org.springframework.http.HttpStatus.OK);
        } catch (RestClientException e) {
            log.error("preview-template 代理失败 factory={} template={} url={}: {}",
                    factoryId, templateId, url, e.getMessage());
            throw new BusinessException(502, "打印服务暂不可用 — 请稍后重试");
        }
    }

    // ==================== Price masking ====================

    /**
     * Replace price-bearing payload fields with {@link #PRICE_MASK} when the caller
     * lacks {@code procurement:price:view}. Skipped entirely for doc types with no
     * monetary fields (production-task, material-requisition).
     *
     * <p>RBAC defense-in-depth: {@link com.cretas.aims.security.PriceFieldResponseAdvice}
     * (PR #423) only walks JSON response bodies — binary PDF byte[] returned by Python
     * bypasses it, so we MUST strip on the input payload before proxying.
     *
     * @param hasMonetaryFields false for production-task / material-requisition
     */
    void applyPriceMask(Map<String, Object> payload, String authorization, String docType, boolean hasMonetaryFields) {
        if (!hasMonetaryFields) {
            return;
        }
        boolean mask = priceMaskResolver.shouldMaskPrice(authorization);
        log.info("Print {} payload masking decision: docType={}, mask={}", docType, docType, mask);
        if (!mask) {
            return;
        }
        for (String key : PRICE_TOPLEVEL_KEYS) {
            if (payload.containsKey(key)) {
                payload.put(key, PRICE_MASK);
            }
        }
        Object items = payload.get("items");
        if (items instanceof List<?>) {
            for (Object item : (List<?>) items) {
                if (item instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) item;
                    for (String key : PRICE_ITEM_KEYS) {
                        if (row.containsKey(key)) {
                            row.put(key, PRICE_MASK);
                        }
                    }
                }
            }
        }
    }

    // ==================== Internal proxy ====================

    private ResponseEntity<byte[]> proxyToPython(String docType, Map<String, Object> payload, String filename, String authorization) {
        String url = pythonBaseUrl + "/api/printing/" + docType;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Smoke v2 P0 fix (2026-05-16): Python /api/printing/* requires auth.
        // Forward inbound Authorization so Python middleware accepts.
        if (authorization != null && !authorization.isEmpty()) {
            headers.set("Authorization", authorization);
        }
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<byte[]> resp = pythonRestTemplate.exchange(url, HttpMethod.POST, req, byte[].class);
            byte[] pdf = resp.getBody();
            if (pdf == null || pdf.length == 0) {
                throw new BusinessException(502, "Python 打印服务返空");
            }
            HttpHeaders out = new HttpHeaders();
            out.setContentType(MediaType.APPLICATION_PDF);
            out.setContentDisposition(org.springframework.http.ContentDisposition
                    .attachment().filename(filename + ".pdf").build());
            out.setContentLength(pdf.length);
            return new ResponseEntity<>(pdf, out, org.springframework.http.HttpStatus.OK);
        } catch (RestClientException e) {
            log.error("PDF 代理失败 docType={} url={}: {}", docType, url, e.getMessage());
            throw new BusinessException(502, "打印服务暂不可用 — 请稍后重试");
        }
    }

    // ==================== Payload builders (Day 6 MVP — stub, 后续 PR 填实体 fetch) ====================
    //
    // TODO 后续 PR 把以下 stub 替换为真实 Service 调用:
    //   - SalesOrderService.getById(factoryId, id) → DTO → Map
    //   - PurchaseOrderService.getById(factoryId, id) → DTO → Map
    //   - QuotationService / ProductionTaskService / MaterialRequisitionService
    // 当前 MVP 接受 query overrides 作为占位数据, 适合 demo + AIChat smoke 测.

    private Map<String, Object> buildSalesOrderPayload(String factoryId, String id, Map<String, String> overrides) {
        Map<String, Object> p = new HashMap<>();
        p.put("factoryName", or(overrides, "factoryName", "白垩纪食品 — " + factoryId));
        p.put("orderNumber", or(overrides, "orderNumber", id));
        p.put("orderDate", or(overrides, "orderDate", java.time.LocalDate.now().toString()));
        p.put("customerName", or(overrides, "customerName", "(客户名)"));
        p.put("salesperson", or(overrides, "salesperson", "(销售员)"));
        p.put("totalAmount", or(overrides, "totalAmount", "0"));
        p.put("remark", or(overrides, "remark", null));
        p.put("items", java.util.List.of());  // 后续 PR: SalesOrderService 拉明细
        return p;
    }

    private Map<String, Object> buildPurchaseOrderPayload(String factoryId, String id, Map<String, String> overrides) {
        Map<String, Object> p = new HashMap<>();
        p.put("factoryName", or(overrides, "factoryName", "白垩纪食品 — " + factoryId));
        p.put("orderNumber", or(overrides, "orderNumber", id));
        p.put("orderDate", or(overrides, "orderDate", java.time.LocalDate.now().toString()));
        p.put("supplierName", or(overrides, "supplierName", "(供应商)"));
        p.put("expectedDeliveryDate", or(overrides, "expectedDeliveryDate", "-"));
        p.put("totalAmount", or(overrides, "totalAmount", "0"));
        p.put("remark", or(overrides, "remark", null));
        // 二维码: 仓管员扫码进入入库流程 (客户原话 May7 part2 行 156-160)
        p.put("qrPayload", "PO:" + factoryId + ":" + id);
        p.put("items", java.util.List.of());
        return p;
    }

    private Map<String, Object> buildQuotationPayload(String factoryId, String id, Map<String, String> overrides) {
        Map<String, Object> p = new HashMap<>();
        p.put("factoryName", or(overrides, "factoryName", "白垩纪食品 — " + factoryId));
        p.put("quotationNumber", or(overrides, "quotationNumber", id));
        p.put("quotationDate", or(overrides, "quotationDate", java.time.LocalDate.now().toString()));
        p.put("customerName", or(overrides, "customerName", "(客户)"));
        p.put("validUntil", or(overrides, "validUntil", "-"));
        p.put("salesperson", or(overrides, "salesperson", "-"));
        p.put("totalAmount", or(overrides, "totalAmount", "0"));
        p.put("remark", or(overrides, "remark", null));
        p.put("items", java.util.List.of());
        return p;
    }

    private Map<String, Object> buildProductionTaskPayload(String factoryId, String id, Map<String, String> overrides) {
        Map<String, Object> p = new HashMap<>();
        p.put("factoryName", or(overrides, "factoryName", "白垩纪食品 — " + factoryId));
        p.put("taskNumber", or(overrides, "taskNumber", id));
        p.put("productName", or(overrides, "productName", "(产品)"));
        p.put("plannedQuantity", or(overrides, "plannedQuantity", "0"));
        p.put("unit", or(overrides, "unit", "kg"));
        p.put("startDate", or(overrides, "startDate", "-"));
        p.put("endDate", or(overrides, "endDate", "-"));
        p.put("workshopName", or(overrides, "workshopName", "(车间)"));
        p.put("supervisor", or(overrides, "supervisor", "-"));
        p.put("processes", java.util.List.of());
        return p;
    }

    private Map<String, Object> buildMaterialRequisitionPayload(String factoryId, String id, Map<String, String> overrides) {
        Map<String, Object> p = new HashMap<>();
        p.put("factoryName", or(overrides, "factoryName", "白垩纪食品 — " + factoryId));
        p.put("requisitionNumber", or(overrides, "requisitionNumber", id));
        p.put("productName", or(overrides, "productName", "(产品)"));
        p.put("plannedQuantity", or(overrides, "plannedQuantity", "0"));
        p.put("unit", or(overrides, "unit", "kg"));
        p.put("requestDate", or(overrides, "requestDate", java.time.LocalDate.now().toString()));
        p.put("workshop", or(overrides, "workshop", "-"));
        p.put("requester", or(overrides, "requester", "-"));
        p.put("items", java.util.List.of());
        return p;
    }

    // ==================== Sprint 6 W3-C — 3 P1 payload builders ====================
    //
    // MVP stubs (same pattern as 5 existing builders above). 后续 PR 替换为真实
    // Service 调用 (StockMovementService / InvoiceService / PackingListService).

    private Map<String, Object> buildStockMovementPayload(String factoryId, String id, Map<String, String> overrides) {
        Map<String, Object> p = new HashMap<>();
        p.put("factoryName", or(overrides, "factoryName", "白垩纪食品 — " + factoryId));
        p.put("movementNumber", or(overrides, "movementNumber", id));
        // movementType in {IN, OUT} — Python render_stock_movement upper()-cases this.
        p.put("movementType", or(overrides, "movementType", "IN"));
        p.put("movementDate", or(overrides, "movementDate", java.time.LocalDate.now().toString()));
        p.put("warehouseName", or(overrides, "warehouseName", "(仓库)"));
        p.put("sourceRef", or(overrides, "sourceRef", "-"));
        p.put("operator", or(overrides, "operator", "-"));
        p.put("totalQty", or(overrides, "totalQty", "0"));
        p.put("remark", or(overrides, "remark", null));
        p.put("items", java.util.List.of());
        return p;
    }

    private Map<String, Object> buildFinancialInvoicePayload(String factoryId, String id, Map<String, String> overrides) {
        Map<String, Object> p = new HashMap<>();
        p.put("factoryName", or(overrides, "factoryName", "白垩纪食品 — " + factoryId));
        p.put("invoiceNumber", or(overrides, "invoiceNumber", id));
        // invoiceType in {VAT_NORMAL, VAT_SPECIAL, VOUCHER}.
        p.put("invoiceType", or(overrides, "invoiceType", "VAT_NORMAL"));
        p.put("invoiceDate", or(overrides, "invoiceDate", java.time.LocalDate.now().toString()));
        p.put("partyName", or(overrides, "partyName", "(购买方)"));
        p.put("partyTaxId", or(overrides, "partyTaxId", "-"));
        p.put("sellerName", or(overrides, "sellerName", "白垩纪食品 — " + factoryId));
        p.put("sellerTaxId", or(overrides, "sellerTaxId", "-"));
        p.put("subtotal", or(overrides, "subtotal", "0"));
        p.put("taxAmount", or(overrides, "taxAmount", "0"));
        p.put("totalAmount", or(overrides, "totalAmount", "0"));
        p.put("remark", or(overrides, "remark", null));
        p.put("drawer", or(overrides, "drawer", "-"));
        p.put("items", java.util.List.of());
        return p;
    }

    private Map<String, Object> buildPackingListPayload(String factoryId, String id, Map<String, String> overrides) {
        Map<String, Object> p = new HashMap<>();
        p.put("factoryName", or(overrides, "factoryName", "白垩纪食品 — " + factoryId));
        p.put("packingNumber", or(overrides, "packingNumber", id));
        p.put("packingDate", or(overrides, "packingDate", java.time.LocalDate.now().toString()));
        p.put("salesOrderRef", or(overrides, "salesOrderRef", "-"));
        p.put("shipToName", or(overrides, "shipToName", "(收货方)"));
        p.put("shipToAddress", or(overrides, "shipToAddress", "-"));
        p.put("shipper", or(overrides, "shipper", "-"));
        p.put("totalCartons", or(overrides, "totalCartons", "0"));
        p.put("totalNetWeight", or(overrides, "totalNetWeight", "0"));
        p.put("totalGrossWeight", or(overrides, "totalGrossWeight", "0"));
        p.put("weightUnit", or(overrides, "weightUnit", "kg"));
        p.put("remark", or(overrides, "remark", null));
        p.put("cartons", java.util.List.of());
        return p;
    }

    // ==================== SP12 T8 — payload builders ====================

    /**
     * 生产工单 payload builder.
     *
     * <p>尽量从 {@link ProductionPlanService} 取真实数据; service 未注入时 fallback 到 stub.
     * 工序列表 (processes) 由 Python 模板渲染, 后续 PR 可从 ProductionBatch.getWorkProcessTasks() 填入.
     */
    private Map<String, Object> buildProductionWorkOrderPayload(
            String factoryId, String planId, Map<String, String> overrides) {
        Map<String, Object> p = new HashMap<>();
        p.put("factoryName", or(overrides, "factoryName", "白垩纪食品 — " + factoryId));
        p.put("planId", planId);

        if (productionPlanService != null) {
            try {
                ProductionPlanDTO plan = productionPlanService.getProductionPlanById(factoryId, planId);
                p.put("planNumber", plan.getPlanNumber() != null ? plan.getPlanNumber() : planId);
                p.put("productName", plan.getProductName() != null ? plan.getProductName() : "(产品)");
                p.put("productUnit", plan.getProductUnit() != null ? plan.getProductUnit() : "kg");
                p.put("plannedQuantity", plan.getPlannedQuantity() != null
                        ? plan.getPlannedQuantity().toPlainString() : "0");
                p.put("status", plan.getStatus() != null ? plan.getStatus().name() : "-");
                p.put("plannedDate", plan.getPlannedDate() != null
                        ? plan.getPlannedDate().toString()
                        : java.time.LocalDate.now().toString());
                p.put("expectedCompletionDate", plan.getExpectedCompletionDate() != null
                        ? plan.getExpectedCompletionDate().toString() : "-");
            } catch (Exception e) {
                log.warn("printProductionWorkOrder: failed to load plan {} — using stub data: {}",
                        planId, e.getMessage());
                fillProductionWorkOrderStub(p, planId, overrides);
            }
        } else {
            fillProductionWorkOrderStub(p, planId, overrides);
        }

        // 工序列表: 从该计划下所有批次的 WorkProcessTask 取 (去重, 按 processOrder 升序)
        p.put("processes", buildProcessList(factoryId, planId));
        p.put("remark", or(overrides, "remark", null));
        return p;
    }

    /**
     * 从计划下所有批次的工序任务汇总工序列表, 供生产工单 PDF 打印.
     *
     * <p>策略: 取第一个有工序任务的批次 (一般一个计划对应一个批次).
     * 若多批次时以首批次工序结构为代表 (相同产品工序模板 spawn 的结构相同).
     * Python renderer 期望字段: seq / name / standardHours / operator.
     * estimatedMinutes → standardHours 转换: minutes / 60, scale 1.
     *
     * <p>service/repo 未注入或异常时静默返回空列表 (诚实空 > 伪造假数据).
     */
    private List<Map<String, Object>> buildProcessList(String factoryId, String planId) {
        if (workProcessTaskService == null || productionBatchRepository == null) {
            log.warn("buildProcessList: workProcessTaskService or productionBatchRepository not injected, "
                    + "returning empty processes for plan {}", planId);
            return List.of();
        }
        try {
            List<ProductionBatch> batches =
                    productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId);
            if (batches == null || batches.isEmpty()) {
                log.debug("buildProcessList: no batches found for plan {} factory {}", planId, factoryId);
                return List.of();
            }
            // Use the first batch with tasks; fall through if none have tasks yet
            for (ProductionBatch batch : batches) {
                List<WorkProcessTaskDTO> tasks =
                        workProcessTaskService.listByBatch(factoryId, batch.getId());
                if (tasks == null || tasks.isEmpty()) {
                    continue;
                }
                List<Map<String, Object>> processes = new ArrayList<>();
                for (WorkProcessTaskDTO t : tasks) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("seq", t.getProcessOrder() != null ? t.getProcessOrder() : processes.size() + 1);
                    row.put("name", t.getProcessName() != null ? t.getProcessName() : t.getWorkProcessId());
                    // estimatedMinutes → hours (1 decimal place, e.g. 90 min → "1.5")
                    if (t.getEstimatedMinutes() != null && t.getEstimatedMinutes() > 0) {
                        BigDecimal hours = BigDecimal.valueOf(t.getEstimatedMinutes())
                                .divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
                        row.put("standardHours", hours.toPlainString());
                    } else {
                        row.put("standardHours", null);
                    }
                    row.put("operator", t.getAssignedToName());
                    processes.add(row);
                }
                return processes;
            }
            log.debug("buildProcessList: batches found but none have tasks spawned yet for plan {}", planId);
            return List.of();
        } catch (Exception e) {
            log.warn("buildProcessList: failed to load work process tasks for plan {} factory {}: {}",
                    planId, factoryId, e.getMessage());
            return List.of();
        }
    }

    private void fillProductionWorkOrderStub(Map<String, Object> p, String planId,
            Map<String, String> overrides) {
        p.put("planNumber", or(overrides, "planNumber", planId));
        p.put("productName", or(overrides, "productName", "(产品)"));
        p.put("productUnit", or(overrides, "productUnit", "kg"));
        p.put("plannedQuantity", or(overrides, "plannedQuantity", "0"));
        p.put("status", or(overrides, "status", "-"));
        p.put("plannedDate", or(overrides, "plannedDate",
                java.time.LocalDate.now().toString()));
        p.put("expectedCompletionDate", or(overrides, "expectedCompletionDate", "-"));
    }

    /**
     * 汇总领料单 payload builder.
     *
     * <p>从 {@link FactoryMaterialRequisitionService#listByPlan} 跨批次汇总同一计划下的所有领料需求.
     * service 未注入时 fallback 到 stub.
     */
    private Map<String, Object> buildConsolidatedMaterialRequisitionPayload(
            String factoryId, String planId, Map<String, String> overrides) {
        Map<String, Object> p = new HashMap<>();
        p.put("factoryName", or(overrides, "factoryName", "白垩纪食品 — " + factoryId));
        p.put("planId", planId);
        p.put("printDate", java.time.LocalDate.now().toString());

        // 尝试从生产计划取产品名
        if (productionPlanService != null) {
            try {
                ProductionPlanDTO plan = productionPlanService.getProductionPlanById(factoryId, planId);
                p.put("planNumber", plan.getPlanNumber() != null ? plan.getPlanNumber() : planId);
                p.put("productName", plan.getProductName() != null ? plan.getProductName() : "(产品)");
            } catch (Exception e) {
                log.warn("printConsolidatedMaterialRequisition: plan {} not found: {}", planId, e.getMessage());
                p.put("planNumber", or(overrides, "planNumber", planId));
                p.put("productName", or(overrides, "productName", "(产品)"));
            }
        } else {
            p.put("planNumber", or(overrides, "planNumber", planId));
            p.put("productName", or(overrides, "productName", "(产品)"));
        }

        // 跨批次汇总领料单明细: 展开 requisitions[].items, 按 materialTypeId 汇总 requiredQty
        List<Map<String, Object>> items = new ArrayList<>();
        if (factoryMaterialRequisitionService != null) {
            try {
                List<FactoryMaterialRequisition> requisitions =
                        factoryMaterialRequisitionService.listByPlan(factoryId, planId);
                int requisitionCount = requisitions != null ? requisitions.size() : 0;
                p.put("requisitionCount", requisitionCount);
                if (requisitions != null) {
                    // Aggregate by materialTypeId: sum requiredQty, collect batchRefs
                    Map<String, Map<String, Object>> aggregated = new LinkedHashMap<>();
                    for (FactoryMaterialRequisition req : requisitions) {
                        List<FactoryMaterialRequisitionItem> reqItems = req.getItems();
                        if (reqItems == null) continue;
                        for (FactoryMaterialRequisitionItem item : reqItems) {
                            String key = item.getMaterialTypeId() != null
                                    ? item.getMaterialTypeId() : "_unknown_";
                            Map<String, Object> row = aggregated.computeIfAbsent(key, k -> {
                                Map<String, Object> r = new LinkedHashMap<>();
                                r.put("materialName",
                                        item.getMaterialName() != null ? item.getMaterialName() : key);
                                r.put("spec", item.getMaterialCategory() != null
                                        ? item.getMaterialCategory().name() : null);
                                r.put("unit", item.getUnit());
                                r.put("totalQty", BigDecimal.ZERO);
                                r.put("batchRefs", new ArrayList<String>());
                                return r;
                            });
                            // Accumulate requiredQty
                            BigDecimal existing = (BigDecimal) row.get("totalQty");
                            BigDecimal addQty = item.getRequiredQty() != null
                                    ? item.getRequiredQty() : BigDecimal.ZERO;
                            row.put("totalQty", existing.add(addQty));
                            // Collect batch ref labels from batchNumbers jsonb
                            List<Map<String, Object>> batchNums = item.getBatchNumbers();
                            if (batchNums != null) {
                                @SuppressWarnings("unchecked")
                                List<String> batchRefs = (List<String>) row.get("batchRefs");
                                for (Map<String, Object> bn : batchNums) {
                                    Object bno = bn.get("batchNo");
                                    if (bno != null && !batchRefs.contains(bno.toString())) {
                                        batchRefs.add(bno.toString());
                                    }
                                }
                            }
                        }
                    }
                    // Convert totalQty to plain string for JSON serialization
                    for (Map<String, Object> row : aggregated.values()) {
                        BigDecimal qty = (BigDecimal) row.get("totalQty");
                        row.put("totalQty", qty.stripTrailingZeros().toPlainString());
                        // Join batchRefs list into comma-separated string for PDF cell
                        @SuppressWarnings("unchecked")
                        List<String> refs = (List<String>) row.get("batchRefs");
                        row.put("batchRefs", refs.isEmpty() ? null : String.join(", ", refs));
                        items.add(row);
                    }
                }
            } catch (Exception e) {
                log.warn("printConsolidatedMaterialRequisition: failed to load requisitions for plan {}: {}",
                        planId, e.getMessage());
                p.put("requisitionCount", 0);
            }
        } else {
            p.put("requisitionCount", 0);
        }

        p.put("items", items);
        p.put("remark", or(overrides, "remark", null));
        return p;
    }

    private Object or(Map<String, String> overrides, String key, Object fallback) {
        if (overrides == null) return fallback;
        String v = overrides.get(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
