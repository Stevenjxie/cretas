package com.cretas.aims.service.oa;

import com.cretas.aims.dto.oa.TodoItemDTO;
import com.cretas.aims.dto.oa.TodoItemDTO.TodoType;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.service.inventory.PaymentRequestService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.restaurant.SupplierDeliveryNoteService;
import lombok.extern.slf4j.Slf4j;
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
    private final PaymentRequestService paymentRequestService;

    // ─── Config ────────────────────────────────────────────────────────────

    /** 待办金额阈值，超过则强制进详情页审批。默认 5000 元。 */
    @Value("${cretas.oa.todo.detail-threshold:5000}")
    private BigDecimal detailThreshold = new BigDecimal("5000");

    /**
     * Spring 注入构造器（5 个必要依赖，{@code detailThreshold} 通过 {@code @Value} 注入）。
     * 测试中通过 {@link #MyTodoAggregatorServiceImpl(PurchaseService, SalesService,
     * SupplierDeliveryNoteService, FactoryStocktakeRepository, PaymentRequestService, BigDecimal)}
     * 直接传入阈值，无需 Spring 上下文。
     */
    public MyTodoAggregatorServiceImpl(
            PurchaseService purchaseService,
            SalesService salesService,
            SupplierDeliveryNoteService supplierDeliveryNoteService,
            FactoryStocktakeRepository stocktakeRepository,
            PaymentRequestService paymentRequestService) {
        this.purchaseService = purchaseService;
        this.salesService = salesService;
        this.supplierDeliveryNoteService = supplierDeliveryNoteService;
        this.stocktakeRepository = stocktakeRepository;
        this.paymentRequestService = paymentRequestService;
    }

    /**
     * 测试友好构造器 — 允许直接设置阈值，无需 Spring 容器。
     */
    MyTodoAggregatorServiceImpl(
            PurchaseService purchaseService,
            SalesService salesService,
            SupplierDeliveryNoteService supplierDeliveryNoteService,
            FactoryStocktakeRepository stocktakeRepository,
            PaymentRequestService paymentRequestService,
            BigDecimal detailThreshold) {
        this(purchaseService, salesService, supplierDeliveryNoteService,
                stocktakeRepository, paymentRequestService);
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
                        TodoType.STOCKTAKE_APPROVAL))));
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
                List<TodoItemDTO> items = fetchByType(factoryId, type);
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

    private List<TodoItemDTO> fetchByType(String factoryId, TodoType type) {
        return switch (type) {
            case PURCHASE_FINANCE_REVIEW -> fetchPurchaseFinanceReview(factoryId);
            case SALES_FINANCE_REVIEW    -> fetchSalesFinanceReview(factoryId);
            case PRICE_ANOMALY           -> fetchPriceAnomaly(factoryId);
            case STOCKTAKE_APPROVAL      -> fetchStocktakeApproval(factoryId);
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
                .counterparty(stocktake.getWarehouseId())
                .submittedAt(submittedAt)
                .needDetail(true)  // 盘点无金额 → 保守 true
                .detailPath("TodoDetail/stocktake/" + stocktake.getId())
                .build();
    }

    // ── 付款（出纳） ──────────────────────────────────────────────────────

    private List<TodoItemDTO> fetchPaymentDisburse(String factoryId) {
        List<PaymentRequest> prs = paymentRequestService.listApprovedForPayment(factoryId);
        if (prs == null) return Collections.emptyList();

        return prs.stream()
                .map(pr -> mapPaymentRequest(pr))
                .collect(Collectors.toList());
    }

    private TodoItemDTO mapPaymentRequest(PaymentRequest pr) {
        BigDecimal amount = pr.getAmount();
        // supplierId is a UUID — counterparty is best effort; detail screen shows supplier name
        String counterparty = pr.getSupplierId();
        String title = "付款 — " + pr.getRequestNumber();

        LocalDateTime submittedAt = pr.getCreatedAt();

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
}
