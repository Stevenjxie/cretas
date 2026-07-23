package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.template.PrintPreviewTemplateRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import com.cretas.aims.service.inventory.TransferService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import com.cretas.aims.utils.JwtUtil;
import com.cretas.aims.utils.TokenUtils;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    private static final String DOCUMENT_SECTION_WORK_ORDER = "work-order";
    private static final String DOCUMENT_SECTION_MATERIAL_REQUISITION = "material-requisition";
    private static final String DOCUMENT_SECTION_BATCHING_SHEET = "batching-sheet";
    private static final List<String> DEFAULT_PRODUCTION_DOCUMENT_SECTIONS = List.of(
            DOCUMENT_SECTION_WORK_ORDER,
            DOCUMENT_SECTION_MATERIAL_REQUISITION,
            DOCUMENT_SECTION_BATCHING_SHEET);

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

    /** Optional: 调拨服务 (transfer-instruction 打印取数). */
    @Autowired(required = false)
    private TransferService transferService;

    /** Warehouse-owned purchase receipt source for printable receiving documents. */
    @Autowired(required = false)
    private PurchaseService purchaseService;

    /** Optional: 产品类型 repository (配料单 — 取 单锅产能 算锅数). */
    @Autowired(required = false)
    private com.cretas.aims.repository.ProductTypeRepository productTypeRepository;

    /** Pinned BOM/Workflow fallback used before operational tasks and requisitions exist. */
    @Autowired(required = false)
    private BomRecipeRepository bomRecipeRepository;

    @Autowired(required = false)
    private BomRecipeItemRepository bomRecipeItemRepository;

    @Autowired(required = false)
    private BomSeasoningItemRepository bomSeasoningItemRepository;

    @Autowired(required = false)
    private BomWorkflowRevisionService bomWorkflowRevisionService;

    @Autowired(required = false)
    private WorkProcessRepository workProcessRepository;

    /** Optional: JWT helper used only for printed-by audit metadata. */
    @Autowired(required = false)
    private JwtUtil jwtUtil;

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

    @GetMapping("/purchase-receipt/{id}")
    @RequirePermission({"warehouse:read", "warehouse:read_write", "inventory:read_write"})
    public ResponseEntity<byte[]> printPurchaseReceipt(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> payload = buildPurchaseReceiptPayload(factoryId, id, overrides);
        return proxyToPython("stock-movement", payload, "purchase-receipt-" + id, authorization);
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
        applyPrintAudit(payload, authorization);
        return proxyToPython("production-work-order", payload, "work-order-" + planId, authorization);
    }

    /**
     * 多 SO 合并公单 PDF (P1 #37).
     *
     * <p>接受多个计划 ID (planIds), 取每个计划的详情 + 工序列表, 合并为一张公单打印.
     * 场景: 六扇门等工厂多笔销售订单合并生产, 需一张工单覆盖全部 SO.
     *
     * <p>payload 结构:
     * <pre>{
     *   factoryName, printDate,
     *   orders: [{planId, planNumber, sourceOrderId, productName, productUnit,
     *             plannedQuantity, customerName, requiredDeliveryDate}],
     *   processes: [{seq, name, standardHours, operator}],   // 去重合并 (按首个含工序批次)
     *   totalOrders: int,
     *   totalQuantityByProduct: [{productName, unit, totalQty}],
     *   remark
     * }</pre>
     *
     * <p>planIds 通过 {@code @RequestParam List<String>} 接收, 支持重复参数:
     * {@code ?planIds=p1&planIds=p2} 或逗号分隔 (Spring 自动拆分).
     * 上限 20 个 plan, 超出返回 400.
     * 诚实策略: 某个 planId 不存在时跳过 (记 warn log), 不抛全局错误.
     */
    @GetMapping("/production-work-order-multi")
    @RequirePermission({"production:read", "production:read_write"})
    public ResponseEntity<byte[]> printProductionWorkOrderMulti(
            @PathVariable String factoryId,
            @RequestParam List<String> planIds,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (planIds == null || planIds.isEmpty()) {
            throw new BusinessException(400, "planIds 不能为空 — 至少传 1 个计划 ID");
        }
        if (planIds.size() > 20) {
            throw new BusinessException(400, "planIds 超出上限 (最多 20 个), 当前: " + planIds.size());
        }
        Map<String, Object> payload = buildMultiSoWorkOrderPayload(factoryId, planIds, overrides);
        return proxyToPython("production-work-order-multi", payload,
                "work-order-multi-" + factoryId + "-" + planIds.size() + "plans", authorization);
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
        applyPrintAudit(payload, authorization);
        return proxyToPython("consolidated-material-requisition", payload,
                "consolidated-req-" + planId, authorization);
    }

    /**
     * 配料单 PDF (六扇门 配料员按锅配料, 转录 [87:50-88:00]).
     *
     * <p>锅数 = ceil(计划产量 / 单锅产能[ProductType.singlePotCapacity]); 每锅料量 = 物料总需求 / 锅数。
     * 单锅产能未配置时 potCount=null → 模板显"请先在产品配置单锅产能"。配料员当前用现有生产/报工角色兼任。
     */
    @GetMapping("/batching-sheet/{planId}")
    @RequirePermission({"production:read", "production:read_write",
            "warehouse:read", "warehouse:read_write"})
    public ResponseEntity<byte[]> printBatchingSheet(
            @PathVariable String factoryId,
            @PathVariable String planId,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> payload = buildBatchingSheetPayload(factoryId, planId, overrides);
        applyPrintAudit(payload, authorization);
        return proxyToPython("batching-sheet", payload, "batching-sheet-" + planId, authorization);
    }

    /**
     * Generate one production-document package PDF while preserving the three
     * independent document models. The Python renderer starts every selected
     * section on a new page and keeps one continuous page-number sequence.
     *
     * <p>The package is deliberately fail-closed: it is never emitted when a
     * requested section has no authoritative plan data. Requiring both read
     * permissions prevents this convenience endpoint from bypassing either
     * the production-document or warehouse-document gates.</p>
     */
    @GetMapping("/production-document-pack/{planId}")
    @RequirePermission(
            value = {"production:read", "warehouse:read"},
            requireAll = true,
            message = "生产单据包包含生产与仓库单据，请先取得对应章节的查看权限")
    public ResponseEntity<byte[]> printProductionDocumentPackage(
            @PathVariable String factoryId,
            @PathVariable String planId,
            @RequestParam(name = "chapters", required = false) List<String> sections,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        List<String> selectedSections = normalizeProductionDocumentSections(sections);
        Map<String, Object> payload = buildProductionDocumentPackagePayload(
                factoryId, planId, selectedSections, overrides);
        applyPrintAudit(payload, authorization);
        return proxyToPython("production-document-package", payload,
                "production-documents-" + planId, authorization);
    }

    private List<String> normalizeProductionDocumentSections(List<String> sections) {
        if (sections == null || sections.isEmpty()) {
            return DEFAULT_PRODUCTION_DOCUMENT_SECTIONS;
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String raw : sections) {
            if (raw == null) continue;
            for (String token : raw.split(",")) {
                String section = token.trim().toLowerCase(java.util.Locale.ROOT);
                if (section.isEmpty()) continue;
                if (!DEFAULT_PRODUCTION_DOCUMENT_SECTIONS.contains(section)) {
                    throw new BusinessException(400, "未知生产单据章节: " + token)
                            .withHint("可选章节: work-order, material-requisition, batching-sheet");
                }
                selected.add(section);
            }
        }
        if (selected.isEmpty()) {
            throw new BusinessException(400, "至少选择一个生产单据章节");
        }
        return List.copyOf(selected);
    }

    private Map<String, Object> buildProductionDocumentPackagePayload(
            String factoryId,
            String planId,
            List<String> sections,
            Map<String, String> overrides) {
        if (productionPlanService == null) {
            throw new BusinessException(503, "生产计划服务不可用 — 无法生成生产单据包");
        }

        final ProductionPlanDTO plan;
        try {
            plan = productionPlanService.getProductionPlanById(factoryId, planId);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(404, "生产计划不存在或不可访问 — 无法生成生产单据包: " + planId);
        }
        if (plan.getSelectedBomRecipeId() == null || plan.getSelectedBomVersion() == null
                || plan.getSelectedWorkflowId() == null || plan.getSelectedWorkflowVersion() == null) {
            throw new BusinessException(409, "生产计划缺少锁定的 BOM 或 Workflow 版本，不能生成单据包")
                    .withHint("请先完成生产计划的 BOM/Workflow 版本锁定");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("factoryName", "白垩纪食品 — " + factoryId);
        payload.put("planId", planId);
        payload.put("planNumber", plan.getPlanNumber() != null ? plan.getPlanNumber() : planId);
        payload.put("productTypeId", plan.getProductTypeId());
        payload.put("sku", resolveProductCode(factoryId, plan.getProductTypeId()));
        payload.put("productName", plan.getProductName() != null ? plan.getProductName() : "-");
        payload.put("batchDate", plan.getBatchDate() != null ? plan.getBatchDate().toString() : "-");
        payload.put("bomRecipeId", plan.getSelectedBomRecipeId());
        payload.put("bomVersion", plan.getSelectedBomVersion());
        payload.put("workflowId", plan.getSelectedWorkflowId());
        payload.put("workflowVersion", plan.getSelectedWorkflowVersion());
        payload.put("generatedAt", java.time.LocalDateTime.now().toString());
        payload.put("sections", sections);

        if (sections.contains(DOCUMENT_SECTION_WORK_ORDER)) {
            Map<String, Object> workOrder = buildProductionWorkOrderPayload(factoryId, planId, overrides);
            applyPinnedPlanSnapshot(workOrder, plan);
            requireDocumentRows(workOrder, "processes", "生产工单缺少工序数据");
            payload.put("workOrder", workOrder);
        }
        if (sections.contains(DOCUMENT_SECTION_MATERIAL_REQUISITION)) {
            Map<String, Object> requisition = buildConsolidatedMaterialRequisitionPayload(
                    factoryId, planId, overrides);
            applyPinnedPlanSnapshot(requisition, plan);
            requireDocumentRows(requisition, "items", "领料单缺少物料需求数据");
            payload.put("materialRequisition", requisition);
        }
        if (sections.contains(DOCUMENT_SECTION_BATCHING_SHEET)) {
            Map<String, Object> batching = buildBatchingSheetPayload(factoryId, planId, overrides);
            applyPinnedPlanSnapshot(batching, plan);
            requireDocumentRows(batching, "items", "配料单缺少物料需求数据");
            payload.put("batchingSheet", batching);
        }
        return payload;
    }

    private void applyPinnedPlanSnapshot(Map<String, Object> section, ProductionPlanDTO plan) {
        section.put("planNumber", plan.getPlanNumber());
        section.put("productTypeId", plan.getProductTypeId());
        section.put("productName", plan.getProductName());
        section.put("productUnit", firstNonBlank(
                plan.getPlannedUnit(), plan.getWorkflowOutputUnit(), plan.getProductUnit(), "kg"));
        section.put("plannedQuantity", plan.getPlannedQuantity() != null
                && plan.getPlannedQuantity().compareTo(BigDecimal.ZERO) > 0
                ? formatQty(plan.getPlannedQuantity()) : "按实际报工确定");
        section.put("batchDate", plan.getBatchDate() != null ? plan.getBatchDate().toString() : "-");
        section.put("bomRecipeId", plan.getSelectedBomRecipeId());
        section.put("bomVersion", plan.getSelectedBomVersion());
        section.put("workflowId", plan.getSelectedWorkflowId());
        section.put("workflowVersion", plan.getSelectedWorkflowVersion());
    }

    private void requireDocumentRows(Map<String, Object> section, String key, String message) {
        Object value = section.get(key);
        if (!(value instanceof List<?> rows) || rows.isEmpty()) {
            throw new BusinessException(409, message)
                    .withHint("单据包不会以空白章节伪装为完整单据，请先补齐该章节数据");
        }
    }

    private String resolveProductCode(String factoryId, String productTypeId) {
        if (productTypeRepository == null || productTypeId == null) return productTypeId;
        return productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .map(com.cretas.aims.entity.ProductType::getCode)
                .filter(code -> !code.isBlank())
                .orElse(productTypeId);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    /**
     * 配料单 payload builder. 锅数 = ceil(计划产量 / 单锅产能); 每锅料量由 Python 渲染时按 总需求/锅数 算。
     */
    private Map<String, Object> buildBatchingSheetPayload(
            String factoryId, String planId, Map<String, String> overrides) {
        Map<String, Object> p = new HashMap<>();
        p.put("factoryName", or(overrides, "factoryName", "白垩纪食品 — " + factoryId));
        p.put("planId", planId);
        p.put("printDate", java.time.LocalDate.now().toString());

        String productTypeId = null;
        java.math.BigDecimal plannedQty = null;
        String plannedUnit = null;
        if (productionPlanService != null) {
            try {
                ProductionPlanDTO plan = productionPlanService.getProductionPlanById(factoryId, planId);
                p.put("planNumber", plan.getPlanNumber() != null ? plan.getPlanNumber() : planId);
                p.put("productName", plan.getProductName() != null ? plan.getProductName() : "(产品)");
                p.put("salesOrderNumbers", salesOrderNumbers(plan));
                productTypeId = plan.getProductTypeId();
                plannedQty = plan.getPlannedQuantity();
                plannedUnit = plan.getPlannedUnit();
            } catch (Exception e) {
                log.warn("printBatchingSheet: plan {} not found: {}", planId, e.getMessage());
                p.put("planNumber", or(overrides, "planNumber", planId));
                p.put("productName", or(overrides, "productName", "(产品)"));
                p.put("salesOrderNumbers", or(overrides, "salesOrderNumbers", "-"));
            }
        } else {
            p.put("planNumber", or(overrides, "planNumber", planId));
            p.put("productName", or(overrides, "productName", "(产品)"));
            p.put("salesOrderNumbers", or(overrides, "salesOrderNumbers", "-"));
        }

        // 单锅产能 + 单位 取自 ProductType
        java.math.BigDecimal potCapacity = null;
        String unit = plannedUnit;
        if (productTypeRepository != null && productTypeId != null) {
            // 跨租户安全: 按 (id, factoryId) 查, 防 productTypeId 指向别厂产品 (复用项目既有红线修法)
            com.cretas.aims.entity.ProductType pt =
                    productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId).orElse(null);
            if (pt != null) {
                potCapacity = pt.getSinglePotCapacity();
                if (unit == null || unit.isBlank()) {
                    unit = pt.getUnit();
                }
            }
        }
        if (unit == null || unit.isBlank()) unit = "kg";
        p.put("plannedQuantity", plannedQty != null ? formatQty(plannedQty) : "-");
        p.put("unit", unit);
        p.put("singlePotCapacity", potCapacity != null ? formatQty(potCapacity) : null);

        // 锅数 = ceil(计划产量 / 单锅产能); 未配置单锅产能 → null (模板显提示, 不伪造)
        Integer potCount = null;
        if (potCapacity != null && potCapacity.compareTo(java.math.BigDecimal.ZERO) > 0 && plannedQty != null) {
            potCount = plannedQty.divide(potCapacity, 0, java.math.RoundingMode.CEILING).intValue();
        }
        p.put("potCount", potCount);

        // 物料明细 (复用跨批次汇总): 每锅料量由 Python 按 totalQty/potCount 算
        List<Map<String, Object>> items = new ArrayList<>();
        if (factoryMaterialRequisitionService != null) {
            try {
                List<FactoryMaterialRequisition> requisitions =
                        factoryMaterialRequisitionService.listByPlan(factoryId, planId);
                items.addAll(aggregateMaterialRequirementRows(requisitions));
            } catch (Exception e) {
                log.warn("printBatchingSheet: failed to load requisitions for plan {}: {}", planId, e.getMessage());
            }
        }
        if (items.isEmpty()) {
            items.addAll(buildBomReferenceRows(factoryId, planId, false));
            p.put("dataStatus", items.isEmpty()
                    ? "尚未生成配料需求，且计划缺少可读取的固定 BOM"
                    : "尚未生成配料实绩；以下为固定 BOM 的原辅料关系与工序比例");
        } else {
            p.put("dataStatus", "已生成领料需求/实绩");
        }
        p.put("items", items);
        p.put("remark", or(overrides, "remark", null));
        return p;
    }

    // ==================== 调拨指示单 (transfer-instruction 打印) ====================

    /**
     * 调拨指示单 PDF — 客户[37:00] "无手机一天打一张纸质指示单".
     *
     * <p>打印内容: 调拨单号 + 调拨日期 + 调出仓/调入仓 + 申请人 + 物料明细 (物料名/批次/数量/单位)
     * + 签名区 (仓管员发料 + 仓管员收料).
     *
     * <p>不含金额字段, 仅 RBAC 门禁即可.
     * 允许 warehouse + inventory 双门 — 仓管员发起打印, 计划员复核都需能拉.
     *
     * <p>使用方式: GET /api/mobile/{factoryId}/print/transfer-instruction/{transferId}
     * 真实数据优先从 {@link TransferService#getTransferById} 取; service 未注入时 fallback stub.
     */
    @GetMapping("/transfer-instruction/{transferId}")
    @RequirePermission({"warehouse:read", "warehouse:read_write",
            "inventory:read", "inventory:write"})
    public ResponseEntity<byte[]> printTransferInstruction(
            @PathVariable String factoryId,
            @PathVariable String transferId,
            @RequestParam(required = false) Map<String, String> overrides,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> payload = buildTransferInstructionPayload(factoryId, transferId, overrides);
        return proxyToPython("transfer-instruction", payload, "transfer-instruction-" + transferId, authorization);
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

    private void applyPrintAudit(Map<String, Object> payload, String authorization) {
        payload.put("printDate", java.time.LocalDate.now().toString());
        String printedBy = "-";
        String printedAccount = "-";
        if (jwtUtil != null && TokenUtils.isValidAuthorizationHeader(authorization)) {
            try {
                String token = TokenUtils.extractToken(authorization);
                Long userId = jwtUtil.getUserIdFromToken(token);
                String username = jwtUtil.getUsernameFromToken(token);
                if (username != null && !username.isBlank()) {
                    printedBy = username;
                }
                if (userId != null) {
                    printedAccount = userId.toString();
                } else if (username != null && !username.isBlank()) {
                    printedAccount = username;
                }
            } catch (Exception e) {
                log.debug("applyPrintAudit: failed to resolve printer from token: {}", e.getMessage());
            }
        }
        payload.put("printedBy", printedBy);
        payload.put("printedAccount", printedAccount);
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

    // ==================== Payload builders ====================
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

    private Map<String, Object> buildPurchaseReceiptPayload(
            String factoryId, String id, Map<String, String> overrides) {
        if (purchaseService == null) {
            throw new BusinessException(503, "采购收货服务不可用，无法生成收货单");
        }
        PurchaseReceiveRecord receipt = purchaseService.getReceiveRecordById(factoryId, id);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("factoryName", or(overrides, "factoryName", "白垩纪食品 — " + factoryId));
        p.put("movementNumber", receipt.getReceiveNumber());
        p.put("movementType", "IN");
        p.put("movementDate", receipt.getReceiveDate() == null ? "-" : receipt.getReceiveDate().toString());
        p.put("warehouseName", receipt.getWarehouseId() == null ? "待指定仓库" : receipt.getWarehouseId());
        p.put("sourceRef", receipt.getPurchaseOrderNumber() == null
                ? receipt.getPurchaseOrderId() : receipt.getPurchaseOrderNumber());
        p.put("supplierName", receipt.getSupplierName());
        p.put("operator", receipt.getReceivedBy());
        p.put("status", receipt.getStatus() == null ? "-" : receipt.getStatus().name());
        p.put("remark", receipt.getRemark());

        List<Map<String, Object>> items = new ArrayList<>();
        if (receipt.getItems() != null) {
            for (PurchaseReceiveItem item : receipt.getItems()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("materialName", item.getMaterialName());
                row.put("quantity", item.getReceivedQuantity());
                row.put("unit", item.getUnit());
                row.put("batchNumber", item.getMaterialBatchId());
                row.put("location", receipt.getWarehouseId());
                row.put("qcResult", item.getQcResult());
                items.add(row);
            }
        }
        p.put("totalQty", items.size() + "项");
        p.put("items", items);
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
     * <p>计划头必须从 {@link ProductionPlanService} 取真实数据; 计划不存在时直接报错.
     * 工序/材料明细如果还未生成, 返回空列表, 不伪造占位行.
     */
    private Map<String, Object> buildProductionWorkOrderPayload(
            String factoryId, String planId, Map<String, String> overrides) {
        Map<String, Object> p = new HashMap<>();
        p.put("factoryName", "白垩纪食品 — " + factoryId);
        p.put("planId", planId);
        p.put("printDate", java.time.LocalDate.now().toString());
        p.put("printedBy", "-");
        p.put("printedAccount", "-");

        if (productionPlanService == null) {
            throw new BusinessException(503, "生产计划服务不可用 — 无法生成真实生产工单");
        }

        try {
            ProductionPlanDTO plan = productionPlanService.getProductionPlanById(factoryId, planId);
            String planNumber = plan.getPlanNumber() != null ? plan.getPlanNumber() : planId;
            p.put("planNumber", planNumber);
            p.put("productionOrderNumber", planNumber);
            p.put("salesOrderNumbers", salesOrderNumbers(plan));
            p.put("sourceOrderId", plan.getSourceOrderId() != null ? plan.getSourceOrderId() : "-");
            p.put("productName", plan.getProductName() != null ? plan.getProductName() : "-");
            p.put("productUnit", plan.getProductUnit() != null ? plan.getProductUnit() : "kg");
            p.put("plannedQuantity", plan.getPlannedQuantity() != null
                    && plan.getPlannedQuantity().compareTo(BigDecimal.ZERO) > 0
                    ? plan.getPlannedQuantity().toPlainString() : "按实际报工确定");
            p.put("expectedOutput", plan.getPlannedQuantity() != null
                    && plan.getPlannedQuantity().compareTo(BigDecimal.ZERO) > 0
                    ? plan.getPlannedQuantity().toPlainString() : "按实际报工确定");
            p.put("status", plan.getStatus() != null ? plan.getStatus().name() : "-");
            p.put("plannedDate", plan.getPlannedDate() != null
                    ? plan.getPlannedDate().toString()
                    : java.time.LocalDate.now().toString());
            p.put("productionDate", p.get("plannedDate"));
            p.put("expectedCompletionDate", plan.getExpectedCompletionDate() != null
                    ? plan.getExpectedCompletionDate().toString() : "-");
            // N5 抬头: 交货日期 = 预计完成日, 客户名称, 制单人
            p.put("deliveryDate", plan.getExpectedCompletionDate() != null
                    ? plan.getExpectedCompletionDate().toString() : "-");
            p.put("customerName", plan.getSourceCustomerName() != null
                    && !plan.getSourceCustomerName().isBlank()
                    ? plan.getSourceCustomerName() : "-");
            p.put("createdBy", plan.getCreatedBy() != null ? plan.getCreatedBy().toString() : "-");
            p.put("createdByName", plan.getCreatedByName() != null
                    && !plan.getCreatedByName().isBlank()
                    ? plan.getCreatedByName() : "-");
            p.put("preparedBy", p.get("createdByName"));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("printProductionWorkOrder: failed to load plan {} for factory {}: {}",
                    planId, factoryId, e.getMessage());
            throw new BusinessException(404, "生产计划不存在或不可访问 — 无法生成生产工单: " + planId);
        }

        // 工序列表: 从该计划下所有批次的 WorkProcessTask 取 (去重, 按 processOrder 升序)
        List<Map<String, Object>> processes = buildProcessList(factoryId, planId);
        List<Map<String, Object>> materialItems = buildMaterialRequirementRows(factoryId, planId);
        p.put("processes", processes);
        p.put("materialItems", materialItems);
        p.put("processDataStatus", processes.isEmpty()
                ? "尚未生成工序任务，且计划缺少可读取的固定 Workflow"
                : rowsUseReference(processes)
                        ? "工序任务尚未生成；以下路线来自计划固定 Workflow"
                        : "已生成工序任务");
        p.put("materialDataStatus", materialItems.isEmpty()
                ? "尚未生成领料需求，且计划缺少可读取的固定 BOM"
                : rowsUseReference(materialItems)
                        ? "领料单尚未生成；以下为计划固定 BOM 参考，未知投入量待计划/报工确认"
                        : "已生成领料需求/实绩");
        p.put("remark", or(overrides, "remark", null));
        return p;
    }

    private boolean rowsUseReference(List<Map<String, Object>> rows) {
        return rows != null && !rows.isEmpty()
                && rows.stream()
                        .map(row -> row.get("dataSource"))
                        .filter(java.util.Objects::nonNull)
                        .map(Object::toString)
                        .anyMatch(source -> source.contains("固定") || source.contains("参考"));
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
                    + "using pinned Workflow for plan {}", planId);
            return buildPinnedProcessRows(factoryId, planId);
        }
        try {
            List<ProductionBatch> batches =
                    productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId);
            if (batches == null || batches.isEmpty()) {
                log.debug("buildProcessList: no batches found for plan {} factory {}", planId, factoryId);
                return buildPinnedProcessRows(factoryId, planId);
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
            return buildPinnedProcessRows(factoryId, planId);
        } catch (Exception e) {
            log.warn("buildProcessList: failed to load work process tasks for plan {} factory {}: {}",
                    planId, factoryId, e.getMessage());
            return buildPinnedProcessRows(factoryId, planId);
        }
    }

    private List<Map<String, Object>> buildMaterialRequirementRows(String factoryId, String planId) {
        if (factoryMaterialRequisitionService == null) {
            log.warn("buildMaterialRequirementRows: factoryMaterialRequisitionService not injected, "
                    + "using pinned BOM for plan {}", planId);
            return buildBomReferenceRows(factoryId, planId, true);
        }
        try {
            List<FactoryMaterialRequisition> requisitions =
                    factoryMaterialRequisitionService.listByPlan(factoryId, planId);
            List<Map<String, Object>> rows = aggregateMaterialRequirementRows(requisitions);
            return rows.isEmpty() ? buildBomReferenceRows(factoryId, planId, true) : rows;
        } catch (Exception e) {
            log.warn("buildMaterialRequirementRows: failed to load requisitions for plan {} factory {}: {}",
                    planId, factoryId, e.getMessage());
            return buildBomReferenceRows(factoryId, planId, true);
        }
    }

    private List<Map<String, Object>> buildPinnedProcessRows(String factoryId, String planId) {
        if (productionPlanService == null
                || bomWorkflowRevisionService == null
                || workProcessRepository == null) {
            return List.of();
        }
        try {
            ProductionPlanDTO plan = productionPlanService.getProductionPlanById(factoryId, planId);
            Optional<BomRecipe> recipe = resolvePinnedRecipe(factoryId, plan);
            if (recipe.isEmpty()) {
                return List.of();
            }
            PinnedWorkflowGraph graph =
                    bomWorkflowRevisionService.resolvePinnedGraph(factoryId, recipe.get());
            List<String> processIds = graph.processes().stream()
                    .map(PinnedWorkflowGraph.ProcessStep::workProcessId)
                    .distinct()
                    .toList();
            Map<String, WorkProcess> processById = workProcessRepository
                    .findByFactoryIdAndIdIn(factoryId, processIds)
                    .stream()
                    .collect(Collectors.toMap(WorkProcess::getId, Function.identity()));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (PinnedWorkflowGraph.ProcessStep step : graph.processes()) {
                WorkProcess process = processById.get(step.workProcessId());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seq", step.order());
                row.put("name", process != null && process.getProcessName() != null
                        ? process.getProcessName() : step.workProcessId());
                row.put("standardHours", process != null ? hours(process.getEstimatedMinutes()) : null);
                row.put("operator", null);
                row.put("dataSource", "计划固定 Workflow");
                rows.add(row);
            }
            return rows;
        } catch (Exception e) {
            log.warn("buildPinnedProcessRows: unable to resolve pinned Workflow for plan {} factory {}: {}",
                    planId, factoryId, e.getMessage());
            return List.of();
        }
    }

    private String hours(Integer estimatedMinutes) {
        if (estimatedMinutes == null || estimatedMinutes <= 0) {
            return null;
        }
        return BigDecimal.valueOf(estimatedMinutes)
                .divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private Optional<BomRecipe> resolvePinnedRecipe(String factoryId, ProductionPlanDTO plan) {
        if (bomRecipeRepository == null || plan == null) {
            return Optional.empty();
        }
        if (plan.getSelectedBomRecipeId() != null && !plan.getSelectedBomRecipeId().isBlank()) {
            return bomRecipeRepository.findById(plan.getSelectedBomRecipeId())
                    .filter(recipe -> factoryId.equals(recipe.getFactoryId()))
                    .filter(recipe -> plan.getProductTypeId() == null
                            || plan.getProductTypeId().equals(recipe.getProductTypeId()));
        }
        // A printout must not silently drift to a later ACTIVE BOM when this plan has
        // no pinned recipe identity. In that case the document stays explicit about
        // missing plan truth instead of borrowing today's master data.
        return Optional.empty();
    }

    private List<Map<String, Object>> buildBomReferenceRows(
            String factoryId, String planId, boolean includePackaging) {
        if (productionPlanService == null
                || bomRecipeItemRepository == null
                || bomRecipeRepository == null) {
            return List.of();
        }
        try {
            ProductionPlanDTO plan = productionPlanService.getProductionPlanById(factoryId, planId);
            Optional<BomRecipe> recipeOptional = resolvePinnedRecipe(factoryId, plan);
            if (recipeOptional.isEmpty()) {
                return List.of();
            }
            BomRecipe recipe = recipeOptional.get();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (BomRecipeItem item :
                    bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(recipe.getId())) {
                String category = item.getMaterialCategory() != null
                        ? item.getMaterialCategory().toUpperCase() : "RAW";
                if (!includePackaging && "PACKAGING".equals(category)) {
                    continue;
                }
                rows.add(bomReferenceRow(item, category, plan.getPlannedQuantity()));
            }
            rows.addAll(buildSeasoningReferenceRows(factoryId, recipe));
            return rows;
        } catch (Exception e) {
            log.warn("buildBomReferenceRows: unable to resolve pinned BOM for plan {} factory {}: {}",
                    planId, factoryId, e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> bomReferenceRow(
            BomRecipeItem item, String category, BigDecimal plannedQuantity) {
        Map<String, Object> row = new LinkedHashMap<>();
        String quantityDisplay = referenceQuantityDisplay(item, category, plannedQuantity);
        row.put("materialName", item.getMaterialName() != null
                ? item.getMaterialName() : item.getMaterialTypeId());
        row.put("category", materialCategoryLabel(category));
        row.put("unit", item.getUnit());
        row.put("plannedRawQty", "RAW".equals(category) ? quantityDisplay : "");
        row.put("plannedAuxiliaryQty", "AUXILIARY".equals(category) ? quantityDisplay : "");
        row.put("plannedSemiFinishedQty", "");
        row.put("totalQty", quantityDisplay);
        row.put("transactedQty", quantityDisplay);
        row.put("plannedIssueQty", "待生成领料单");
        row.put("deliveredQty", "未发料");
        row.put("actualUsedQty", "待报工");
        row.put("batchRefs", null);
        row.put("unitCost", null);
        row.put("dataSource", "计划固定 BOM 参考");
        return row;
    }

    private String referenceQuantityDisplay(
            BomRecipeItem item, String category, BigDecimal plannedQuantity) {
        BigDecimal quantity = item.getStandardQuantity();
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            quantity = item.getNaturalQuantity();
        }
        if ("PACKAGING".equals(category) && quantity != null
                && plannedQuantity != null && plannedQuantity.compareTo(BigDecimal.ZERO) > 0) {
            return formatQty(quantity.multiply(plannedQuantity));
        }
        if (quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0) {
            return "参考 " + formatQty(quantity) + " / 单位成品";
        }
        return "计划投料待填写";
    }

    private List<Map<String, Object>> buildSeasoningReferenceRows(
            String factoryId, BomRecipe recipe) {
        if (bomSeasoningItemRepository == null) {
            return List.of();
        }
        List<BomSeasoningItem> items =
                bomSeasoningItemRepository.findByRecipeIdOrderBySeqAsc(recipe.getId());
        if (items.isEmpty()) {
            return List.of();
        }
        List<String> processIds = items.stream()
                .map(BomSeasoningItem::getWorkProcessId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<String, WorkProcess> processById =
                workProcessRepository == null || processIds.isEmpty()
                        ? Map.of()
                        : workProcessRepository.findByFactoryIdAndIdIn(factoryId, processIds)
                                .stream()
                                .collect(Collectors.toMap(WorkProcess::getId, Function.identity()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BomSeasoningItem item : items) {
            WorkProcess process = processById.get(item.getWorkProcessId());
            String processName = process != null ? process.getProcessName() : "对应工序";
            String ratio = formatQty(item.getDosagePerKgG()) + " g/kg";
            if (item.getSubsequentPotRatio() != null) {
                ratio += "；后续锅 "
                        + item.getSubsequentPotRatio().multiply(new BigDecimal("100"))
                                .stripTrailingZeros().toPlainString()
                        + "%";
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialName", item.getName() + "（" + processName + "）");
            row.put("category", "工序辅料");
            row.put("unit", "g/kg");
            row.put("plannedRawQty", "");
            row.put("plannedAuxiliaryQty", ratio);
            row.put("plannedSemiFinishedQty", "");
            row.put("totalQty", ratio);
            row.put("transactedQty", ratio);
            row.put("plannedIssueQty", "待生成领料单");
            row.put("deliveredQty", "未发料");
            row.put("actualUsedQty", "待报工");
            row.put("batchRefs", null);
            row.put("perPotQty", ratio);
            row.put("dataSource", "BOM 工序辅料比例");
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> aggregateMaterialRequirementRows(
            List<FactoryMaterialRequisition> requisitions) {
        if (requisitions == null || requisitions.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> aggregated = new LinkedHashMap<>();
        for (FactoryMaterialRequisition req : requisitions) {
            List<FactoryMaterialRequisitionItem> reqItems = req.getItems();
            if (reqItems == null) continue;
            for (FactoryMaterialRequisitionItem item : reqItems) {
                String bucket = materialBucket(item);
                String materialKey = item.getMaterialTypeId() != null ? item.getMaterialTypeId() : "_unknown_";
                String key = materialKey + "|" + bucket;
                Map<String, Object> row = aggregated.computeIfAbsent(key, k -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("materialName", item.getMaterialName() != null ? item.getMaterialName() : materialKey);
                    r.put("category", materialCategoryLabel(bucket));
                    r.put("unit", item.getUnit());
                    r.put("plannedRawQtyValue", BigDecimal.ZERO);
                    r.put("plannedAuxiliaryQtyValue", BigDecimal.ZERO);
                    r.put("plannedSemiFinishedQtyValue", BigDecimal.ZERO);
                    r.put("totalQtyValue", BigDecimal.ZERO);
                    // SP12 T8 续: 成交(应需=requiredQty=totalQty) / 打算(已拣=pickedQty) / 送到(已发=issuedQty)
                    // 三列 (转录行2902-2904 [86:53-55])。picked/issued null-init → 未拣发时显空, 不伪造 0。
                    r.put("plannedIssueQtyValue", null);
                    r.put("deliveredQtyValue", null);
                    r.put("actualUsedQtyValue", null);
                    r.put("batchRefsList", new ArrayList<String>());
                    return r;
                });

                BigDecimal requiredQty = item.getRequiredQty() != null ? item.getRequiredQty() : BigDecimal.ZERO;
                addQty(row, "totalQtyValue", requiredQty);
                if ("RAW".equals(bucket)) {
                    addQty(row, "plannedRawQtyValue", requiredQty);
                } else if ("SEMI_FINISHED".equals(bucket)) {
                    addQty(row, "plannedSemiFinishedQtyValue", requiredQty);
                } else {
                    addQty(row, "plannedAuxiliaryQtyValue", requiredQty);
                }

                if (item.getPickedQty() != null) {
                    BigDecimal existing = (BigDecimal) row.get("plannedIssueQtyValue");
                    row.put("plannedIssueQtyValue",
                            existing == null ? item.getPickedQty() : existing.add(item.getPickedQty()));
                }
                if (item.getIssuedQty() != null) {
                    BigDecimal existing = (BigDecimal) row.get("deliveredQtyValue");
                    row.put("deliveredQtyValue",
                            existing == null ? item.getIssuedQty() : existing.add(item.getIssuedQty()));
                }
                if (item.getConsumedQty() != null) {
                    BigDecimal existing = (BigDecimal) row.get("actualUsedQtyValue");
                    row.put("actualUsedQtyValue",
                            existing == null ? item.getConsumedQty() : existing.add(item.getConsumedQty()));
                }

                List<Map<String, Object>> batchNums = item.getBatchNumbers();
                if (batchNums != null) {
                    @SuppressWarnings("unchecked")
                    List<String> batchRefs = (List<String>) row.get("batchRefsList");
                    for (Map<String, Object> bn : batchNums) {
                        Object bno = bn.get("batchNo");
                        if (bno != null && !batchRefs.contains(bno.toString())) {
                            batchRefs.add(bno.toString());
                        }
                    }
                }
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : aggregated.values()) {
            BigDecimal plannedRaw = (BigDecimal) row.remove("plannedRawQtyValue");
            BigDecimal plannedAux = (BigDecimal) row.remove("plannedAuxiliaryQtyValue");
            BigDecimal plannedSemi = (BigDecimal) row.remove("plannedSemiFinishedQtyValue");
            BigDecimal totalQty = (BigDecimal) row.remove("totalQtyValue");
            BigDecimal plannedIssue = (BigDecimal) row.remove("plannedIssueQtyValue");
            BigDecimal delivered = (BigDecimal) row.remove("deliveredQtyValue");
            BigDecimal actualUsed = (BigDecimal) row.remove("actualUsedQtyValue");
            @SuppressWarnings("unchecked")
            List<String> batchRefs = (List<String>) row.remove("batchRefsList");

            row.put("plannedRawQty", positiveQtyOrBlank(plannedRaw));
            row.put("plannedAuxiliaryQty", positiveQtyOrBlank(plannedAux));
            row.put("plannedSemiFinishedQty", positiveQtyOrBlank(plannedSemi));
            row.put("totalQty", formatQty(totalQty));
            // 成交(应需) = totalQty(requiredQty); 打算(已拣) = pickedQty; 送到(已发) = issuedQty。
            // 未拣/未发显 "________" (诚实空, 仓库填), 不伪造 0。
            row.put("transactedQty", formatQty(totalQty));
            row.put("plannedIssueQty", plannedIssue != null ? formatQty(plannedIssue) : "________");
            row.put("deliveredQty", delivered != null ? formatQty(delivered) : "________");
            row.put("actualUsedQty", actualUsed != null ? formatQty(actualUsed) : "________");
            row.put("batchRefs", batchRefs.isEmpty() ? null : String.join(", ", batchRefs));
            rows.add(row);
        }
        return rows;
    }

    private void addQty(Map<String, Object> row, String key, BigDecimal qty) {
        BigDecimal existing = (BigDecimal) row.get(key);
        row.put(key, existing.add(qty));
    }

    private String positiveQtyOrBlank(BigDecimal qty) {
        return qty != null && qty.compareTo(BigDecimal.ZERO) > 0 ? formatQty(qty) : "";
    }

    private String formatQty(BigDecimal qty) {
        if (qty == null) return "0";
        return qty.stripTrailingZeros().toPlainString();
    }

    private String materialBucket(FactoryMaterialRequisitionItem item) {
        if (item.getMaterialCategory() == null) return "RAW";
        String category = item.getMaterialCategory().name();
        if (category.contains("SEMI") || category.contains("WIP")) {
            return "SEMI_FINISHED";
        }
        if ("RAW".equals(category)) {
            return "RAW";
        }
        return "AUXILIARY";
    }

    private String materialCategoryLabel(String bucket) {
        return switch (bucket) {
            case "RAW" -> "原料";
            case "SEMI_FINISHED" -> "半成品";
            case "PACKAGING" -> "包材";
            default -> "辅料";
        };
    }

    private String salesOrderNumbers(ProductionPlanDTO plan) {
        if (plan.getCustomerOrderNumber() != null && !plan.getCustomerOrderNumber().isBlank()) {
            return plan.getCustomerOrderNumber();
        }
        if (plan.getSourceOrderIds() != null && !plan.getSourceOrderIds().isEmpty()) {
            return String.join(", ", plan.getSourceOrderIds());
        }
        return plan.getSourceOrderId() != null && !plan.getSourceOrderId().isBlank()
                ? plan.getSourceOrderId() : "-";
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

        // 尝试从生产计划取产品名 + 双单号 (销售单号 + 生产计划单号) — C-051
        if (productionPlanService != null) {
            try {
                ProductionPlanDTO plan = productionPlanService.getProductionPlanById(factoryId, planId);
                p.put("planNumber", plan.getPlanNumber() != null ? plan.getPlanNumber() : planId);
                p.put("productName", plan.getProductName() != null ? plan.getProductName() : "(产品)");
                // C-051 双单号: sourceOrderId = 关联销售单号 (单 SO 场景); sourceOrderIds = 多 SO
                p.put("sourceOrderId",
                        plan.getSourceOrderId() != null ? plan.getSourceOrderId() : "-");
                // 多 SO 合并场景: 展示全部关联销售单号 (逗号分隔), 仅单 SO 时等同 sourceOrderId
                p.put("salesOrderNumbers", salesOrderNumbers(plan));
            } catch (Exception e) {
                log.warn("printConsolidatedMaterialRequisition: plan {} not found: {}", planId, e.getMessage());
                p.put("planNumber", or(overrides, "planNumber", planId));
                p.put("productName", or(overrides, "productName", "(产品)"));
                p.put("sourceOrderId", or(overrides, "sourceOrderId", "-"));
                p.put("salesOrderNumbers", or(overrides, "salesOrderNumbers", "-"));
            }
        } else {
            p.put("planNumber", or(overrides, "planNumber", planId));
            p.put("productName", or(overrides, "productName", "(产品)"));
            p.put("sourceOrderId", or(overrides, "sourceOrderId", "-"));
            p.put("salesOrderNumbers", or(overrides, "salesOrderNumbers", "-"));
        }

        // 跨批次汇总领料单明细: 展开 requisitions[].items, 按 materialTypeId + 分类汇总 requiredQty
        List<Map<String, Object>> items = new ArrayList<>();
        if (factoryMaterialRequisitionService != null) {
            try {
                List<FactoryMaterialRequisition> requisitions =
                        factoryMaterialRequisitionService.listByPlan(factoryId, planId);
                int requisitionCount = requisitions != null ? requisitions.size() : 0;
                p.put("requisitionCount", requisitionCount);
                items.addAll(aggregateMaterialRequirementRows(requisitions));
            } catch (Exception e) {
                log.warn("printConsolidatedMaterialRequisition: failed to load requisitions for plan {}: {}",
                        planId, e.getMessage());
                p.put("requisitionCount", 0);
            }
        } else {
            p.put("requisitionCount", 0);
        }

        if (items.isEmpty()) {
            items.addAll(buildBomReferenceRows(factoryId, planId, true));
            p.put("dataStatus", items.isEmpty()
                    ? "尚未生成领料需求，且计划缺少可读取的固定 BOM"
                    : "尚未生成领料单；以下为计划固定 BOM 参考，不代表已拣料或已发料");
        } else {
            p.put("dataStatus", "已生成领料需求/实绩");
        }
        p.put("items", items);
        p.put("remark", or(overrides, "remark", null));
        return p;
    }

    // ==================== P1 #37 — 多 SO 合并公单 payload builder ====================

    /**
     * 多 SO 合并生产工单 payload builder.
     *
     * <p>策略:
     * <ol>
     *   <li>遍历 planIds, 对每个 planId 调用 {@link #buildSingleOrderEntry} 取计划摘要.</li>
     *   <li>工序列表: 遍历所有 planId, 取第一个含工序的批次的工序作为"代表工序",
     *       按 processName 去重后按 seq 排列 (相同产品工序模板结构相同, 合并打印).</li>
     *   <li>按 (productName, unit) 汇总计划数量, 供 renderer 显示合计区块.</li>
     *   <li>诚实原则: 某 planId 找不到时跳过 + warn log, 不伪造数据.</li>
     * </ol>
     */
    private Map<String, Object> buildMultiSoWorkOrderPayload(
            String factoryId, List<String> planIds, Map<String, String> overrides) {
        Map<String, Object> p = new HashMap<>();
        p.put("factoryName", or(overrides, "factoryName", "白垩纪食品 — " + factoryId));
        p.put("printDate", java.time.LocalDate.now().toString());

        // ── 1. 逐个计划取摘要行 ──────────────────────────────────────────────────
        List<Map<String, Object>> orders = new ArrayList<>();
        for (String planId : planIds) {
            Map<String, Object> entry = buildSingleOrderEntry(factoryId, planId, overrides);
            if (entry != null) {
                orders.add(entry);
            }
        }
        p.put("orders", orders);
        p.put("totalOrders", orders.size());

        // ── 2. 工序列表: 合并所有计划的工序, 按 name 去重 ────────────────────────
        List<Map<String, Object>> mergedProcesses = buildMergedProcessList(factoryId, planIds);
        p.put("processes", mergedProcesses);

        // ── 3. 按 (productName, unit) 汇总计划数量 ───────────────────────────────
        Map<String, Map<String, Object>> qtyAgg = new LinkedHashMap<>();
        for (Map<String, Object> order : orders) {
            String pname = String.valueOf(order.getOrDefault("productName", "(产品)"));
            String unit = String.valueOf(order.getOrDefault("productUnit", "kg"));
            String aggKey = pname + "||" + unit;
            Map<String, Object> aggRow = qtyAgg.computeIfAbsent(aggKey, k -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("productName", pname);
                r.put("unit", unit);
                r.put("totalQty", BigDecimal.ZERO);
                return r;
            });
            Object qtyObj = order.get("plannedQuantity");
            if (qtyObj != null) {
                try {
                    BigDecimal existing = (BigDecimal) aggRow.get("totalQty");
                    aggRow.put("totalQty", existing.add(new BigDecimal(qtyObj.toString())));
                } catch (NumberFormatException ignored) { /* leave as-is */ }
            }
        }
        List<Map<String, Object>> totalQtyByProduct = new ArrayList<>();
        for (Map<String, Object> row : qtyAgg.values()) {
            BigDecimal qty = (BigDecimal) row.get("totalQty");
            row.put("totalQty", qty.stripTrailingZeros().toPlainString());
            totalQtyByProduct.add(row);
        }
        p.put("totalQuantityByProduct", totalQtyByProduct);

        p.put("remark", or(overrides, "remark", null));
        return p;
    }

    /**
     * 单个计划的摘要行 (用于多 SO 合并公单的 orders[] 列表).
     *
     * @return null 如果计划不存在 (调用方跳过)
     */
    private Map<String, Object> buildSingleOrderEntry(
            String factoryId, String planId, Map<String, String> overrides) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("planId", planId);

        if (productionPlanService != null) {
            try {
                ProductionPlanDTO plan = productionPlanService.getProductionPlanById(factoryId, planId);
                entry.put("planNumber", plan.getPlanNumber() != null ? plan.getPlanNumber() : planId);
                entry.put("sourceOrderId",
                        plan.getSourceOrderId() != null ? plan.getSourceOrderId() : "-");
                entry.put("productName", plan.getProductName() != null ? plan.getProductName() : "(产品)");
                entry.put("productUnit", plan.getProductUnit() != null ? plan.getProductUnit() : "kg");
                entry.put("plannedQuantity", plan.getPlannedQuantity() != null
                        ? plan.getPlannedQuantity() : BigDecimal.ZERO);
                entry.put("status", plan.getStatus() != null ? plan.getStatus().name() : "-");
                entry.put("plannedDate", plan.getPlannedDate() != null
                        ? plan.getPlannedDate().toString() : "-");
                entry.put("expectedCompletionDate", plan.getExpectedCompletionDate() != null
                        ? plan.getExpectedCompletionDate().toString() : "-");
                // 客户名 (from sourceCustomerName if available in DTO)
                entry.put("customerName",
                        plan.getSourceCustomerName() != null ? plan.getSourceCustomerName() : "-");
                return entry;
            } catch (Exception e) {
                log.warn("buildSingleOrderEntry: plan {} not found in factory {} — skipping: {}",
                        planId, factoryId, e.getMessage());
                return null;  // 诚实: 计划不存在时跳过
            }
        } else {
            // service 未注入 — 返回 stub entry (保证 PDF 可渲染, 字段标为 "(stub)")
            log.warn("buildSingleOrderEntry: productionPlanService not injected, returning stub for plan {}",
                    planId);
            entry.put("planNumber", planId);
            entry.put("sourceOrderId", "-");
            entry.put("productName", "(产品)");
            entry.put("productUnit", "kg");
            entry.put("plannedQuantity", BigDecimal.ZERO);
            entry.put("status", "-");
            entry.put("plannedDate", java.time.LocalDate.now().toString());
            entry.put("expectedCompletionDate", "-");
            entry.put("customerName", "-");
            return entry;
        }
    }

    /**
     * 合并多个计划的工序列表, 按 processName 去重.
     *
     * <p>遍历所有 planId, 取到第一个有工序的就作为代表. 对相同产品(工序模板相同的计划),
     * 取到的工序结构一致; 不同产品的工序合并后按 name 去重, 保留首次出现的 seq/standardHours.
     */
    private List<Map<String, Object>> buildMergedProcessList(String factoryId, List<String> planIds) {
        Set<String> seenProcessNames = new LinkedHashSet<>();
        List<Map<String, Object>> merged = new ArrayList<>();
        for (String planId : planIds) {
            List<Map<String, Object>> procs = buildProcessList(factoryId, planId);
            for (Map<String, Object> proc : procs) {
                String name = String.valueOf(proc.getOrDefault("name", ""));
                if (!name.isBlank() && seenProcessNames.add(name)) {
                    merged.add(proc);
                }
            }
        }
        return merged;
    }

    private Object or(Map<String, String> overrides, String key, Object fallback) {
        if (overrides == null) return fallback;
        String v = overrides.get(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    // ==================== 调拨指示单 payload builder ====================

    /**
     * 调拨指示单 payload builder.
     *
     * <p>字段:
     * <ul>
     *   <li>factoryName — 工厂名</li>
     *   <li>transferNumber — 调拨单号</li>
     *   <li>transferDate — 调拨日期</li>
     *   <li>sourceWarehouseId / targetWarehouseId — 调出仓 / 调入仓 ID</li>
     *   <li>sourceWarehouseName / targetWarehouseName — 调出仓 / 调入仓 名称 (如能解析)</li>
     *   <li>status — 调拨状态 (中文)</li>
     *   <li>requestedBy — 申请人</li>
     *   <li>expectedArrivalDate — 期望到货日</li>
     *   <li>remark — 备注</li>
     *   <li>items — [{itemName, spec, qty, unit, batchId}]</li>
     * </ul>
     *
     * <p>诚实原则: service 未注入或 transfer 不存在时, fallback stub (PDF 仍可渲染).
     */
    private Map<String, Object> buildTransferInstructionPayload(
            String factoryId, String transferId, Map<String, String> overrides) {
        Map<String, Object> p = new HashMap<>();
        p.put("factoryName", or(overrides, "factoryName", "白垩纪食品 — " + factoryId));
        p.put("transferId", transferId);
        p.put("printDate", java.time.LocalDate.now().toString());

        if (transferService != null) {
            try {
                InternalTransfer transfer = transferService.getTransferById(factoryId, transferId);
                p.put("transferNumber",
                        transfer.getTransferNumber() != null ? transfer.getTransferNumber() : transferId);
                p.put("transferDate",
                        transfer.getTransferDate() != null ? transfer.getTransferDate().toString() : "-");
                p.put("sourceWarehouseId",
                        transfer.getSourceWarehouseId() != null ? transfer.getSourceWarehouseId() : "-");
                p.put("targetWarehouseId",
                        transfer.getTargetWarehouseId() != null ? transfer.getTargetWarehouseId() : "-");
                // 中文状态映射
                p.put("status", transfer.getStatus() != null
                        ? translateTransferStatus(transfer.getStatus().name()) : "-");
                p.put("requestedBy",
                        transfer.getRequestedBy() != null ? transfer.getRequestedBy().toString() : "-");
                p.put("expectedArrivalDate",
                        transfer.getExpectedArrivalDate() != null
                                ? transfer.getExpectedArrivalDate().toString() : "-");
                p.put("remark", transfer.getRemark() != null ? transfer.getRemark()
                        : or(overrides, "remark", null));
                // 明细行
                List<Map<String, Object>> items = new ArrayList<>();
                if (transfer.getItems() != null) {
                    for (InternalTransferItem item : transfer.getItems()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("itemName",
                                item.getItemName() != null ? item.getItemName() : "-");
                        row.put("spec", null);  // InternalTransferItem 无 spec 字段, 诚实空
                        row.put("qty", item.getQuantity() != null
                                ? item.getQuantity().stripTrailingZeros().toPlainString() : "0");
                        row.put("unit", item.getUnit() != null ? item.getUnit() : "-");
                        // 批次 ID (发货前为 null 则 "-")
                        row.put("batchId",
                                item.getSourceBatchId() != null ? item.getSourceBatchId() : "-");
                        items.add(row);
                    }
                }
                p.put("items", items);
            } catch (Exception e) {
                log.warn("printTransferInstruction: transfer {} not found in factory {} — using stub: {}",
                        transferId, factoryId, e.getMessage());
                fillTransferInstructionStub(p, transferId, overrides);
            }
        } else {
            log.warn("printTransferInstruction: transferService not injected — using stub for transfer {}",
                    transferId);
            fillTransferInstructionStub(p, transferId, overrides);
        }
        return p;
    }

    private void fillTransferInstructionStub(Map<String, Object> p, String transferId,
            Map<String, String> overrides) {
        p.put("transferNumber", or(overrides, "transferNumber", transferId));
        p.put("transferDate", or(overrides, "transferDate", java.time.LocalDate.now().toString()));
        p.put("sourceWarehouseId", or(overrides, "sourceWarehouseId", "(调出仓)"));
        p.put("targetWarehouseId", or(overrides, "targetWarehouseId", "(调入仓)"));
        p.put("status", or(overrides, "status", "-"));
        p.put("requestedBy", or(overrides, "requestedBy", "-"));
        p.put("expectedArrivalDate", or(overrides, "expectedArrivalDate", "-"));
        p.put("remark", or(overrides, "remark", null));
        p.put("items", java.util.List.of());
    }

    private String translateTransferStatus(String status) {
        return switch (status) {
            case "DRAFT"     -> "草稿";
            case "REQUESTED" -> "待审批";
            case "APPROVED"  -> "已审批";
            case "SHIPPED"   -> "已发货";
            case "RECEIVED"  -> "已签收";
            case "CONFIRMED" -> "已确认";
            case "CANCELLED" -> "已取消";
            default          -> status;
        };
    }
}
