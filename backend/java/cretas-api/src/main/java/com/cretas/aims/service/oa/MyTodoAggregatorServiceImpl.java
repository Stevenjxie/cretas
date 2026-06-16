package com.cretas.aims.service.oa;

import com.cretas.aims.dto.oa.TodoItemDTO;
import com.cretas.aims.dto.oa.TodoItemDTO.TodoType;
import com.cretas.aims.dto.inventory.PaymentRequestApprovedDTO;
import com.cretas.aims.dto.inventory.WastageReportDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.inventory.PaymentRequestService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.inventory.WastageReportService;
import com.cretas.aims.service.restaurant.SupplierDeliveryNoteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 我的待办聚合服务实现 — RN OA 待办中心后端。
 *
 * <p>角色映射（常量，不可变）:
 * <ul>
 *   <li>finance_manager → {PURCHASE_FINANCE_REVIEW, SALES_FINANCE_REVIEW, PRICE_ANOMALY, STOCKTAKE_APPROVAL}</li>
 *   <li>cashier → {PAYMENT_DISBURSE}</li>
 *   <li>其他 → 空集合</li>
 * </ul>
 *
 * <p>fail-soft 原则: 每个域查询都包在独立 try/catch 中，单域失败 log warn + 该类型返空，
 * 不影响其他域正常返回，不拖垮整体请求（符合 .claude/rules 禁降级假数据规范）。
 *
 * <p>阈值: {@code cretas.oa.todo.detail-threshold}（默认 5000），
 * {@code needDetail = amount == null || amount.compareTo(threshold) > 0}。
 *
 * @since 2026-06-12 (OA 待办聚合 B2)
 */
@Slf4j
@Service
public class MyTodoAggregatorServiceImpl implements MyTodoAggregatorService {

    // ─── Dependencies ──────────────────────────────────────────────────────

    private final PurchaseService purchaseService;
    private final SalesService salesService;
    private final SupplierDeliveryNoteService supplierDeliveryNoteService;
    private final FactoryStocktakeRepository stocktakeRepository;
    private final FactoryWarehouseRepository warehouseRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final PaymentRequestService paymentRequestService;
    private final SalesPriceAdjustmentService salesPriceAdjustmentService;
    private final WastageReportService wastageReportService;

    // ─── Config ────────────────────────────────────────────────────────────

    /** 待办金额阈值，超过则强制进详情页审批。默认 5000 元。 */
    @Value("${cretas.oa.todo.detail-threshold:5000}")
    private BigDecimal detailThreshold = new BigDecimal("5000");

    /**
     * Spring 注入构造器（必要依赖，{@code detailThreshold} 通过 {@code @Value} 注入）。
     * 测试中通过 {@link #MyTodoAggregatorServiceImpl(PurchaseService, SalesService,
     * SupplierDeliveryNoteService, FactoryStocktakeRepository, FactoryWarehouseRepository,
     * ReturnOrderRepository, CustomerRepository, SupplierRepository, PaymentRequestService,
     * SalesPriceAdjustmentService, BigDecimal)}
     * 直接传入阈值，无需 Spring 上下文。
     *
     * <p>⚠️ 本类有两个构造器，Spring 隐式构造器注入只在「恰好一个构造器」时生效；
     * 多构造器必须显式 {@code @Autowired} 标注主构造器，否则 Spring 回退找默认构造器 →
     * {@code NoSuchMethodException: <init>()} → 启动失败（2026-06-12 prod 部署阻塞事故根因，
     * 表象为"启动 hang"：context refresh 失败但已创建 bean 的非 daemon 线程把 JVM 钉死）。
     */
    @Autowired
    public MyTodoAggregatorServiceImpl(
            PurchaseService purchaseService,
            SalesService salesService,
            SupplierDeliveryNoteService supplierDeliveryNoteService,
            FactoryStocktakeRepository stocktakeRepository,
            FactoryWarehouseRepository warehouseRepository,
            ReturnOrderRepository returnOrderRepository,
            CustomerRepository customerRepository,
            SupplierRepository supplierRepository,
            PaymentRequestService paymentRequestService,
            SalesPriceAdjustmentService salesPriceAdjustmentService,
            WastageReportService wastageReportService) {
        this.purchaseService = purchaseService;
        this.salesService = salesService;
        this.supplierDeliveryNoteService = supplierDeliveryNoteService;
        this.stocktakeRepository = stocktakeRepository;
        this.warehouseRepository = warehouseRepository;
        this.returnOrderRepository = returnOrderRepository;
        this.customerRepository = customerRepository;
        this.supplierRepository = supplierRepository;
        this.paymentRequestService = paymentRequestService;
        this.salesPriceAdjustmentService = salesPriceAdjustmentService;
        this.wastageReportService = wastageReportService;
    }

    /**
     * 测试友好构造器 — 允许直接设置阈值，无需 Spring 容器。
     */
    MyTodoAggregatorServiceImpl(
            PurchaseService purchaseService,
            SalesService salesService,
            SupplierDeliveryNoteService supplierDeliveryNoteService,
            FactoryStocktakeRepository stocktakeRepository,
            FactoryWarehouseRepository warehouseRepository,
            ReturnOrderRepository returnOrderRepository,
            CustomerRepository customerRepository,
            SupplierRepository supplierRepository,
            PaymentRequestService paymentRequestService,
            SalesPriceAdjustmentService salesPriceAdjustmentService,
            WastageReportService wastageReportService,
            BigDecimal detailThreshold) {
        this(purchaseService, salesService, supplierDeliveryNoteService,
                stocktakeRepository, warehouseRepository, returnOrderRepository,
                customerRepository, supplierRepository, paymentRequestService,
                salesPriceAdjustmentService, wastageReportService);
        this.detailThreshold = detailThreshold;
    }

    // ─── Role → TodoType mapping ───────────────────────────────────────────

    private static final Map<String, Set<TodoType>> ROLE_TYPES;

    static {
        Map<String, Set<TodoType>> m = new HashMap<>();
        m.put("finance_manager", Collections.unmodifiableSet(
                new LinkedHashSet<>(Arrays.asList(
                        TodoType.PURCHASE_FINANCE_REVIEW,
                        TodoType.SALES_FINANCE_REVIEW,
                        TodoType.PRICE_ANOMALY,
                        TodoType.STOCKTAKE_APPROVAL,
                        TodoType.RETURN_FINANCE_REVIEW,
                        TodoType.SALES_PRICE_ADJUSTMENT,
                        TodoType.WASTAGE_APPROVAL))));
        m.put("cashier", Collections.unmodifiableSet(
                new LinkedHashSet<>(Arrays.asList(
                        TodoType.PAYMENT_DISBURSE))));
        ROLE_TYPES = Collections.unmodifiableMap(m);
    }

    // ─── API ───────────────────────────────────────────────────────────────

    @Override
    public List<TodoItemDTO> listTodos(String factoryId, String callerRole) {
        Set<TodoType> types = ROLE_TYPES.getOrDefault(callerRole, Collections.emptySet());
        if (types.isEmpty()) {
            return Collections.emptyList();
        }

        List<TodoItemDTO> all = new ArrayList<>();
        for (TodoType type : types) {
            try {
                List<TodoItemDTO> items = fetchByType(factoryId, callerRole, type);
                all.addAll(items);
            } catch (Exception e) {
                log.warn("[MyTodoAggregator] fan-out failed for type={} factoryId={}: {}",
                        type, factoryId, e.getMessage(), e);
                // fail-soft: skip this type, continue with others
            }
        }

        // Sort by submittedAt descending (null submittedAt goes last)
        all.sort(Comparator.comparing(TodoItemDTO::getSubmittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        return all;
    }

    @Override
    public int countTodos(String factoryId, String callerRole) {
        return listTodos(factoryId, callerRole).size();
    }

    // ─── Per-type fan-out ─────────────────────────────────────────────────

    private List<TodoItemDTO> fetchByType(String factoryId, String callerRole, TodoType type) {
        return switch (type) {
            case PURCHASE_FINANCE_REVIEW -> fetchPurchaseFinanceReview(factoryId);
            case SALES_FINANCE_REVIEW    -> fetchSalesFinanceReview(factoryId);
            case PRICE_ANOMALY           -> fetchPriceAnomaly(factoryId);
            case STOCKTAKE_APPROVAL      -> fetchStocktakeApproval(factoryId);
            case RETURN_FINANCE_REVIEW   -> fetchReturnFinanceReview(factoryId);
            case SALES_PRICE_ADJUSTMENT  -> fetchSalesPriceAdjustment(factoryId);
            case WASTAGE_APPROVAL        -> fetchWastageApproval(factoryId, callerRole);
            case PAYMENT_DISBURSE        -> fetchPaymentDisburse(factoryId);
        };
    }

    // ── 采购财审 ─────────────────────────────────────────────────────────

    private List<TodoItemDTO> fetchPurchaseFinanceReview(String factoryId) {
        var page = purchaseService.getPurchaseOrdersByStatus(
                factoryId, PurchaseOrderStatus.PENDING_FINANCE_REVIEW, 1, Integer.MAX_VALUE);
        if (page == null || page.getContent() == null) return Collections.emptyList();

        return page.getContent().stream()
                .map(po -> mapPurchaseOrder(po))
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapPurchaseOrder(PurchaseOrder po) {
        BigDecimal amount = po.getTotalAmount();
        String supplierName = po.getSupplierName();
        String title = "采购财审 — " + (supplierName != null ? supplierName : po.getOrderNumber());

        // submittedAt: use createdAt as best proxy for when it entered PENDING_FINANCE_REVIEW
        // (PurchaseOrder doesn't track financeSubmittedAt separately)
        LocalDateTime submittedAt = po.getCreatedAt();

        return TodoItemDTO.builder()
                .type(TodoType.PURCHASE_FINANCE_REVIEW)
                .refId(po.getId())
                .refNumber(po.getOrderNumber())
                .title(title)
                .amount(amount)
                .counterparty(supplierName)
                .submittedAt(submittedAt)
                .needDetail(needsDetail(amount))
                .detailPath("TodoDetail/purchase/" + po.getId())
                .build();
    }

    // ── 销售财审 ─────────────────────────────────────────────────────────

    private List<TodoItemDTO> fetchSalesFinanceReview(String factoryId) {
        var page = salesService.getSalesOrdersByStatus(
                factoryId, SalesOrderStatus.PENDING_FINANCE_REVIEW, 1, Integer.MAX_VALUE);
        if (page == null || page.getContent() == null) return Collections.emptyList();

        return page.getContent().stream()
                .map(so -> mapSalesOrder(so))
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapSalesOrder(SalesOrder so) {
        BigDecimal amount = so.getTotalAmount();
        String customerName = so.getCustomerName();
        String title = "销售财审 — " + (customerName != null ? customerName : so.getOrderNumber());

        LocalDateTime submittedAt = so.getCreatedAt();

        return TodoItemDTO.builder()
                .type(TodoType.SALES_FINANCE_REVIEW)
                .refId(so.getId())
                .refNumber(so.getOrderNumber())
                .title(title)
                .amount(amount)
                .counterparty(customerName)
                .submittedAt(submittedAt)
                .needDetail(needsDetail(amount))
                .detailPath("TodoDetail/sales/" + so.getId())
                .build();
    }

    // ── 价格异常 ─────────────────────────────────────────────────────────

    private List<TodoItemDTO> fetchPriceAnomaly(String factoryId) {
        Page<SupplierDeliveryNote> page = supplierDeliveryNoteService
                .listPendingPriceAnomalyApprovals(factoryId, null,
                        PageRequest.of(0, Integer.MAX_VALUE / 2));
        if (page == null) return Collections.emptyList();

        return page.getContent().stream()
                .map(note -> mapPriceAnomaly(note))
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapPriceAnomaly(SupplierDeliveryNote note) {
        BigDecimal amount = note.getTotalAmount();
        String supplierName = note.getSupplierName();
        String title = "价格异常 — " + (supplierName != null ? supplierName : note.getNoteNumber());

        LocalDateTime submittedAt = note.getPriceAnomalySubmittedAt();
        if (submittedAt == null) {
            submittedAt = note.getCreatedAt();
        }

        return TodoItemDTO.builder()
                .type(TodoType.PRICE_ANOMALY)
                .refId(note.getId())
                .refNumber(note.getNoteNumber())
                .title(title)
                .amount(amount)
                .counterparty(supplierName)
                .submittedAt(submittedAt)
                .needDetail(needsDetail(amount))
                .detailPath("TodoDetail/priceAnomaly/" + note.getId())
                .build();
    }

    // ── 盘点审批 ─────────────────────────────────────────────────────────

    private List<TodoItemDTO> fetchStocktakeApproval(String factoryId) {
        Page<FactoryStocktake> page = stocktakeRepository.findByFactoryIdAndOptionalStatus(
                factoryId, FactoryStocktake.Status.PENDING_APPROVAL,
                PageRequest.of(0, Integer.MAX_VALUE / 2));
        if (page == null) return Collections.emptyList();

        return page.getContent().stream()
                .map(s -> mapStocktake(s))
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapStocktake(FactoryStocktake stocktake) {
        // 盘点无金额字段 → needDetail=true（保守，强制看详情）
        String warehouseName = resolveWarehouseName(stocktake);
        String title = "盘点审批 — " + stocktake.getStocktakeNo()
                + "（" + stocktake.getPeriodMonth() + "）";

        LocalDateTime submittedAt = stocktake.getSubmittedAt();
        if (submittedAt == null) {
            submittedAt = stocktake.getCreatedAt();
        }

        return TodoItemDTO.builder()
                .type(TodoType.STOCKTAKE_APPROVAL)
                .refId(stocktake.getId())
                .refNumber(stocktake.getStocktakeNo())
                .title(title)
                .amount(null)  // 盘点任务无金额
                .counterparty(warehouseName)
                .submittedAt(submittedAt)
                .needDetail(true)  // 盘点无金额 → 保守 true
                .detailPath("TodoDetail/stocktake/" + stocktake.getId())
                .build();
    }

    // ── 付款（出纳） ──────────────────────────────────────────────────────

    private List<TodoItemDTO> fetchReturnFinanceReview(String factoryId) {
        Page<ReturnOrder> page = returnOrderRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(
                factoryId, ReturnOrderStatus.APPROVED,
                PageRequest.of(0, Integer.MAX_VALUE / 2));
        if (page == null) return Collections.emptyList();

        return page.getContent().stream()
                .map(this::mapReturnOrder)
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapReturnOrder(ReturnOrder order) {
        BigDecimal amount = order.getTotalAmount();
        String counterparty = resolveReturnCounterparty(order);
        LocalDateTime submittedAt = order.getApprovedAt();
        if (submittedAt == null) {
            submittedAt = order.getUpdatedAt();
        }
        if (submittedAt == null) {
            submittedAt = order.getCreatedAt();
        }

        return TodoItemDTO.builder()
                .type(TodoType.RETURN_FINANCE_REVIEW)
                .refId(order.getId())
                .refNumber(order.getReturnNumber())
                .title("退货财审 — " + order.getReturnNumber())
                .amount(amount)
                .counterparty(counterparty)
                .submittedAt(submittedAt)
                .needDetail(needsDetail(amount))
                .detailPath("TodoDetail/return/" + order.getId())
                .build();
    }

    // ── 销售改价待审批 ────────────────────────────────────────────────────

    private List<TodoItemDTO> fetchSalesPriceAdjustment(String factoryId) {
        List<SalesPriceAdjustmentRecord> records =
                salesPriceAdjustmentService.listPendingApprovals(factoryId);
        if (records == null) return Collections.emptyList();

        return records.stream()
                .map(this::mapSalesPriceAdjustment)
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapSalesPriceAdjustment(SalesPriceAdjustmentRecord r) {
        // 改价无单笔"金额"语义 → 用新单价做展示金额 (Rule 2 上下文); needDetail 保守 true,
        // 价格审批必须看改前/改后对比, 不允许一键通过.
        BigDecimal newPrice = r.getNewUnitPrice();
        String changeDir = (r.getOldUnitPrice() != null && r.getNewUnitPrice() != null
                && r.getNewUnitPrice().compareTo(r.getOldUnitPrice()) < 0) ? "降价" : "涨价";
        String title = "改价审批 — " + changeDir
                + " " + (r.getOldUnitPrice() != null ? r.getOldUnitPrice().stripTrailingZeros().toPlainString() : "?")
                + " → " + (newPrice != null ? newPrice.stripTrailingZeros().toPlainString() : "?");

        return TodoItemDTO.builder()
                .type(TodoType.SALES_PRICE_ADJUSTMENT)
                .refId(r.getId())
                .refNumber(r.getSalesOrderId())
                .title(title)
                .amount(newPrice)
                .counterparty(r.getAdjustedByName())
                .submittedBy(r.getAdjustedByName())
                .submittedAt(r.getCreatedAt())
                .needDetail(true)  // 改价审批必须看改前/改后对比 → 强制进详情
                .detailPath("TodoDetail/salesPriceAdjustment/" + r.getId())
                .build();
    }

    private List<TodoItemDTO> fetchWastageApproval(String factoryId, String callerRole) {
        Page<WastageReportDTO> page = wastageReportService.listPending(
                factoryId, callerRole, PageRequest.of(0, Integer.MAX_VALUE / 2));
        if (page == null || page.getContent() == null) return Collections.emptyList();

        return page.getContent().stream()
                .map(this::mapWastageReport)
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapWastageReport(WastageReportDTO report) {
        String counterparty = resolveWastageCounterparty(report);
        LocalDateTime submittedAt = report.getSubmittedAt();
        if (submittedAt == null) {
            submittedAt = report.getCreatedAt();
        }

        return TodoItemDTO.builder()
                .type(TodoType.WASTAGE_APPROVAL)
                .refId(report.getId())
                .refNumber(report.getReportNo())
                .title("报损审批 — " + report.getReportNo())
                .amount(null)
                .counterparty(counterparty)
                .submittedBy(report.getSubmittedBy() != null ? String.valueOf(report.getSubmittedBy()) : null)
                .submittedAt(submittedAt)
                .needDetail(true)
                .detailPath("TodoDetail/wastage/" + report.getId())
                .build();
    }

    // ── 付款（出纳） ──────────────────────────────────────────────────────

    private List<TodoItemDTO> fetchPaymentDisburse(String factoryId) {
        List<PaymentRequestApprovedDTO> prs = paymentRequestService.listApprovedForPaymentWithDetails(factoryId);
        if (prs == null) return Collections.emptyList();

        return prs.stream()
                .map(pr -> mapPaymentRequest(pr))
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapPaymentRequest(PaymentRequestApprovedDTO pr) {
        BigDecimal amount = pr.getAmount();
        String counterparty = hasText(pr.getSupplierName()) ? pr.getSupplierName() : "付款对象需核对";
        String title = "付款 — " + pr.getRequestNumber();

        LocalDateTime submittedAt = pr.getCreatedAt();
        if (submittedAt == null) {
            submittedAt = pr.getApprovedAt();
        }

        return TodoItemDTO.builder()
                .type(TodoType.PAYMENT_DISBURSE)
                .refId(pr.getId())
                .refNumber(pr.getRequestNumber())
                .title(title)
                .amount(amount)
                .counterparty(counterparty)
                .submittedAt(submittedAt)
                .needDetail(needsDetail(amount))
                .detailPath("TodoDetail/payment/" + pr.getId())
                .build();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    /**
     * 判断是否需要进详情页审批。
     * 规则: amount == null（无金额信息保守处理）OR amount > threshold → true。
     * amount == threshold → false（等于阈值视为小额，允许一键审批）。
     */
    private boolean needsDetail(BigDecimal amount) {
        if (amount == null) return true;
        return amount.compareTo(detailThreshold) > 0;
    }

    private String resolveWarehouseName(FactoryStocktake stocktake) {
        if (stocktake == null || !hasText(stocktake.getWarehouseId())) {
            return "盘点仓库需核对";
        }
        return warehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(
                        stocktake.getWarehouseId(), stocktake.getFactoryId())
                .map(FactoryWarehouse::getName)
                .filter(this::hasText)
                .orElse("盘点仓库需核对");
    }

    private String resolveWastageCounterparty(WastageReportDTO report) {
        if (report == null) {
            return "报损位置需核对";
        }
        if (hasText(report.getWarehouseId())) {
            return warehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(
                            report.getWarehouseId(), report.getFactoryId())
                    .map(FactoryWarehouse::getName)
                    .filter(this::hasText)
                    .orElse("报损仓库需核对");
        }
        if (hasText(report.getTrackType())) {
            return report.getTrackType();
        }
        return "报损位置需核对";
    }

    private String resolveReturnCounterparty(ReturnOrder order) {
        if (order == null || !hasText(order.getCounterpartyId())) {
            return "退货对象需核对";
        }
        String counterpartyId = order.getCounterpartyId();
        String factoryId = order.getFactoryId();
        if (order.getReturnType() == ReturnType.SALES_RETURN) {
            return customerRepository.findByIdAndFactoryId(counterpartyId, factoryId)
                    .map(Customer::getName)
                    .filter(this::hasText)
                    .orElse("客户需核对");
        }
        if (order.getReturnType() == ReturnType.PURCHASE_RETURN) {
            return supplierRepository.findByIdAndFactoryId(counterpartyId, factoryId)
                    .map(Supplier::getName)
                    .filter(this::hasText)
                    .orElse("供应商需核对");
        }
        return "退货对象需核对";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
