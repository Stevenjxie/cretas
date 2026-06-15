package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.approval.ExecutionContext;
import com.cretas.aims.dto.inventory.CreateDeliveryRequest;
import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.dto.inventory.UpdateSalesOrderRequest;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CustomerSource;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.*;
import com.cretas.aims.event.SalesOrderConfirmedEvent;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.annotation.DataScope;
import com.cretas.aims.annotation.Loggable;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.rules.annotation.RuleEvaluate;
import com.cretas.aims.service.workflow.ApprovalWorkflowExecutor;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SalesServiceImpl implements SalesService {

    private static final Logger log = LoggerFactory.getLogger(SalesServiceImpl.class);

    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final SalesDeliveryRecordRepository deliveryRecordRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final CustomerRepository customerRepository;
    private final ProductTypeRepository productTypeRepository;
    private final com.cretas.aims.service.finance.ArApService arApService;
    private final ApplicationEventPublisher applicationEventPublisher;

    /** D1 双仓流转 (2026-05-10 spec, PR #309 A1=A) — sales 只从 WH-LOG 出. */
    @org.springframework.beans.factory.annotation.Autowired
    private WarehouseResolver warehouseResolver;

    /** P0-13 批次分配校验（可选注入，避免破坏现有构造器）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService batchAllocationService;

    /** Sales shipment auto-AR idempotency guard. Optional for legacy unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ArApTransactionRepository arApTransactionRepository;

    /** Canvas Config — 可选注入，模块未部署时不影响现有逻辑 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FactoryConfigService factoryConfigService;

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    /** Canvas V2: DB-driven default values */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DefaultValueResolver defaultValueResolver;

    /** Canvas V2: DB-driven formula engine */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.FormulaEngine formulaEngine;

    /** Canvas V3: Dynamic field persistence (cf_xxx columns) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    /** T3: 业务员双字段过渡 — 用于 resolveSalespersonField */
    @org.springframework.beans.factory.annotation.Autowired
    private UserRepository userRepository;

    /** Sprint4-H F-AR-1: BOM 标准成本查询. Optional — 模块未部署时 cost breakdown 仍可工作 (返 null). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.bom.BomRecipeService bomRecipeService;

    /** SP3: 成本超支阈值解析. Optional — 未注册时超支字段返 null. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.bom.CostVarianceService costVarianceService;

    /**
     * 六扇门 D1: 同口径标准成本解析 (料 + 研发预估标准人工). Optional.
     *
     * <p>修复假阳性超支报警: BOM 标准 (纯料) vs 实际 (含人工) 口径不一致 → 系统性虚高方差。
     * 此服务返回含人工的标准成本, 与含人工的实际成本同口径可比。未注册时回退到纯料 BOM 标准
     * (保留旧行为, 但下方逻辑会优先用 StandardCostService 的同口径值)。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.bom.StandardCostService standardCostService;

    /** SP12 实际成本拆分: 订单 → 生产计划. Optional — 未注册时 actualCostSplit 返 null. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.ProductionPlanRepository productionPlanRepository;

    /** SP12 实际成本拆分: 计划 → 生产批次 (取 laborCost/equipmentCost/otherCost). Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.ProductionBatchRepository productionBatchRepository;

    /** SP12 实际成本拆分: 批次 → 领料消耗记录 (逐料 quantity/unitPrice/totalCost). Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.MaterialConsumptionRepository materialConsumptionRepository;

    /** SP12 实际成本拆分: 物料名称/分类/单位查询. Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.RawMaterialTypeRepository rawMaterialTypeRepository;

    /** 六扇门多段成本: 批次 → 半成品段 (SemiFinishedInventory 行, 每行=一次两点报工转化). Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.SemiFinishedInventoryRepository semiFinishedInventoryRepository;

    /** 六扇门多段成本: 半成品段 → IN 流水 (取 reportId 反查 laborCost/materialCost 拆段成本). Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository semiFinishedInventoryTransactionRepository;

    /** 六扇门多段成本: 反查报工 laborCost/materialCost (段内料/人工拆分). Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.ProductionReportRepository productionReportRepository;
    // 注: 段名解析复用已有 final 字段 productTypeRepository (构造注入, line 56)。

    /**
     * Issue #793: 客户协议价 lookup — auto-applies customer-specific or global selling price
     * at SO creation when caller does not explicitly set {@code unitPrice}.
     * Optional injection so unit tests with mocked deps can run without booting price-list module.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.inventory.PriceListService priceListService;

    /**
     * P1 #23 S-CREDIT-1: 客户信用预检 (2026-05-17).
     * SUSPENDED → 硬阻塞; WARNING (exceeds) → 仅 log (soft warn, FE 通过 GET credit-status 预呈现).
     * Optional — 未注册时跳过检查 (不影响现有逻辑).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.crm.CustomerCreditCheckService customerCreditCheckService;

    /**
     * Canvas-Pricing Phase 4b (2026-05-18): 价格策略引擎注入.
     *
     * <p>Optional injection — Phase 4b 模块未部署时 (老 jar) 不影响 SO 创建.
     * 注入存在时, createSalesOrder 在 resolve unitPrice 之后调用 pricingEngine.calculate()
     * 应用 TIERED / PROMOTION / MEMBER / BUNDLE 策略 (CYCLE 是月底批处理不在此路径).
     *
     * <p>Legacy fallback: 注入为 null 时, 走原 priceListService → itemDTO.getUnitPrice() 路径,
     * 完全向前兼容.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.pricing.PricingEngine pricingEngine;

    /**
     * E-5 毛利红线守卫 (W1 六扇门追溯矩阵 E-5, 2026-06-10).
     *
     * <p>Optional injection — 未部署时跳过毛利红线检查 (不影响现有逻辑).
     * 注入存在时, createSalesOrder 在 resolve unitPrice 后对每行逐一检查,
     * belowRedline=true → 返回 200 + marginWarnings 列表 (warn 非阻断, per PR #693 / DECISIONS-5).
     * belowRedline=null (成本未配置) → WARN log + 放行 (不假装拦截, 不静默放行).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.pricing.GrossMarginRedlineService grossMarginRedlineService;

    /**
     * P1 #33 销售价 ≥ 研发预估价 跨流校验守卫 (G流 SP10 → E流销售订单, 2026-06-11).
     *
     * <p>Optional injection — 未部署时跳过估价校验 (不影响现有逻辑).
     * 注入存在时, createSalesOrder 在 resolve unitPrice 后对每行逐一检查,
     * belowEstimate=true → 收集预警到 priceWarnings (非阻断, 200 + 警告, 决策对齐 #693 防呆).
     * belowEstimate=null (研发未报价) → 诚实跳过 (不警告, 不伪造).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.pricing.EstimatePriceCheckService estimatePriceCheckService;

    /** Sprint 6 W2-B: 数据权限解析器 (optional). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DataScopeResolver dataScopeResolver;

    /** T126 Phase 1: 成品库存调整日志 Repository (optional for test ctor). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository finishedGoodsAdjustmentLogRepository;

    /** N9: configurable sales approval threshold via legacy approval chain + graph workflow. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ApprovalChainService approvalChainService;

    /** N9: optional graph lookup; absence preserves legacy sales flow in tests/partial deployments. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ApprovalWorkflowService approvalWorkflowService;

    /** N9: persisted workflow engine for factories that published SALES_ORDER_APPROVAL workflows. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorkflowEngineService workflowEngine;

    /** Legacy in-memory executor retained for compatibility with older partial deployments. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ApprovalWorkflowExecutor approvalWorkflowExecutor;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public SalesServiceImpl(SalesOrderRepository salesOrderRepository,
                            SalesOrderItemRepository salesOrderItemRepository,
                            SalesDeliveryRecordRepository deliveryRecordRepository,
                            FinishedGoodsBatchRepository finishedGoodsBatchRepository,
                            CustomerRepository customerRepository,
                            ProductTypeRepository productTypeRepository,
                            com.cretas.aims.service.finance.ArApService arApService,
                            ApplicationEventPublisher applicationEventPublisher) {
        this.salesOrderRepository = salesOrderRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.deliveryRecordRepository = deliveryRecordRepository;
        this.finishedGoodsBatchRepository = finishedGoodsBatchRepository;
        this.customerRepository = customerRepository;
        this.productTypeRepository = productTypeRepository;
        this.arApService = arApService;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    // ==================== 销售订单 ====================

    /**
     * Phase 4a follow-up (issue #38) — @RuleEvaluate attached to fire ORDER scope rules.
     *
     * <p>Method signature has POJO arg {@code request} (CreateSalesOrderRequest), so direct
     * annotation works (matches MaterialBatchServiceImpl.createMaterialBatch pattern, no
     * wrap helper needed). Aspect's {@code target="request"} binding resolves to the DTO.
     *
     * <p>If REJECT result: {@link com.cretas.aims.service.rules.RuleViolationException}
     * propagates → caught by GlobalExceptionHandler → HTTP 400 with actionHint + severity.
     */
    @Override
    @RuleEvaluate(value = "ORDER", target = "request")
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "CREATE", entityType = "SalesOrder",
              summary = "'创建销售单 客户=' + #request.customerId")
    public SalesOrder createSalesOrder(String factoryId, CreateSalesOrderRequest request, Long userId) {
        // Canvas V2: DB-driven validation — pre-compute totalAmount so rules can reference it
        BigDecimal preComputedTotal = BigDecimal.ZERO;
        Set<String> seenProductIdsForCheck = new HashSet<>();
        boolean hasDuplicate = false;
        if (request.getItems() != null) {
            for (CreateSalesOrderRequest.SalesOrderItemDTO itemDTO : request.getItems()) {
                if (itemDTO.getProductTypeId() != null && !seenProductIdsForCheck.add(itemDTO.getProductTypeId())) {
                    hasDuplicate = true;
                }
                if (itemDTO.getQuantity() != null && itemDTO.getUnitPrice() != null) {
                    BigDecimal discountRate = itemDTO.getDiscountRate() != null ? itemDTO.getDiscountRate() : BigDecimal.ZERO;
                    BigDecimal line = itemDTO.getQuantity().multiply(itemDTO.getUnitPrice())
                            .multiply(BigDecimal.ONE.subtract(discountRate));
                    preComputedTotal = preComputedTotal.add(line);
                }
            }
        }
        runConfiguredValidation(factoryId, "CREATE", Map.of(
                "itemCount", request.getItems() != null ? request.getItems().size() : 0,
                "totalAmount", preComputedTotal,
                "hasDuplicateProduct", hasDuplicate));

        // 验证客户 (Sprint 4 W2 S-INVOICE-CLIENT-1: 捕获 customer 用于 default prefill)
        com.cretas.aims.entity.Customer customer = customerRepository
                .findByIdAndFactoryId(request.getCustomerId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("客户不存在或不属于当前组织"));

        // P1 #23 S-CREDIT-1: 创建前信用预检 (防呆 R1 + R2 + R5)
        // SUSPENDED → 硬阻塞 409; WARNING (exceeds) → 仅 log soft warn (后端不阻塞, 前端通过 GET credit-status 预呈现).
        if (customerCreditCheckService != null) {
            try {
                com.cretas.aims.dto.customer.CreditStatusDTO creditStatus =
                        customerCreditCheckService.checkCreditAvailable(
                                factoryId, request.getCustomerId(), preComputedTotal);
                if (creditStatus.getCreditStatus() == com.cretas.aims.entity.enums.CreditStatus.SUSPENDED) {
                    throw new BusinessException(409,
                            "客户 " + customer.getName() + " 信用已被冻结, 无法创建新销售单")
                            .withHint(creditStatus.getSuggestedAction())
                            .withHintTarget("customerId")
                            .withSeverity("ERROR");
                }
                if (creditStatus.isExceeds()) {
                    // soft warn — 仅 log, 不阻塞 (按 backlog "超期警告" 语义, 销售有权强制下单)
                    log.warn("P1-23 信用预警: factoryId={}, customer={}, requested={}, available={}, action={}",
                            factoryId, customer.getName(), preComputedTotal,
                            creditStatus.getAvailable(), creditStatus.getSuggestedAction());
                }
            } catch (BusinessException be) {
                // 信用阻塞 — 重新抛出
                throw be;
            } catch (Exception e) {
                // 检查本身失败 (e.g. customer 不存在 — 但上面已 fetch) — 仅 log, 不阻塞 SO 创建
                log.warn("P1-23 信用检查失败 (跳过, 不阻塞 SO): factoryId={}, customerId={}, error={}",
                        factoryId, request.getCustomerId(), e.getMessage());
            }
        }

        String orderNumber = generateSalesOrderNumber(factoryId);

        SalesOrder order = new SalesOrder();
        order.setFactoryId(factoryId);
        order.setOrderNumber(orderNumber);
        order.setCustomerId(request.getCustomerId());
        order.setOrderDate(request.getOrderDate() != null ? request.getOrderDate() : java.time.LocalDate.now());
        order.setRequiredDeliveryDate(request.getRequiredDeliveryDate());
        order.setDeliveryAddress(request.getDeliveryAddress());
        order.setDiscountAmount(request.getDiscountAmount() != null ? request.getDiscountAmount() : BigDecimal.ZERO);
        order.setRemark(request.getRemark());
        resolveSalespersonField(order, request.getSalesperson(), factoryId);
        order.setShippingIncluded(request.getShippingIncluded());
        order.setShippingFee(request.getShippingFee());
        order.setExtraFees(request.getExtraFees());
        order.setQuoteId(request.getQuoteId()); // 报价→订单联动 (5016s 客户流程文档)
        order.setStatus(SalesOrderStatus.DRAFT);
        // P3 多仓: 外部渠道采购单标题 (如 "0601-熟食T+2"). Nullable.
        order.setExternalOrderTitle(request.getExternalOrderTitle());
        order.setCreatedBy(userId);

        // Sprint 4 W2 S-INVOICE-CLIENT-1 Option 3 三层 default 链 — 第 1→2 层 prefill:
        // 请求显式 > 客户级 default. SO 上的值会在 Item 创建时继续下放 (第 2→3 层).
        order.setDefaultTaxRate(request.getDefaultTaxRate() != null
                ? request.getDefaultTaxRate()
                : customer.getDefaultTaxRate());
        order.setDefaultInvoiceType(request.getDefaultInvoiceType() != null
                ? request.getDefaultInvoiceType()
                : customer.getDefaultInvoiceType());

        order = salesOrderRepository.save(order);

        // SKU 重复校验
        Set<String> seenProductIds = new HashSet<>();
        for (CreateSalesOrderRequest.SalesOrderItemDTO itemDTO : request.getItems()) {
            if (!seenProductIds.add(itemDTO.getProductTypeId())) {
                throw new BusinessException(409, "同一订单不能添加重复的产品: " + (itemDTO.getProductName() != null ? itemDTO.getProductName() : itemDTO.getProductTypeId()))
                        .withHint("请合并相同产品行, 或选择其他产品").withHintTarget("productTypeId");
            }
        }

        // 创建行项目
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<SalesOrderItem> items = new ArrayList<>();

        // E-5 毛利红线: 收集低于红线的预警文案 (非阻断 — 决策修正 2026-06-10).
        // 仅含"低于毛利红线"语义文案 (GrossMarginCheckResult 保证不含成本数值), 随订单返回前端展示.
        List<String> marginWarnings = new ArrayList<>();

        // P1 #33 销售价 ≥ 研发预估价: 收集低于研发预估价的预警文案 (非阻断 — 决策对齐 #693).
        // 仅含"低于研发预估价"语义文案 (EstimatePriceCheckResult 保证不含预估价数值), 随订单返回.
        List<String> priceWarnings = new ArrayList<>();

        for (CreateSalesOrderRequest.SalesOrderItemDTO itemDTO : request.getItems()) {
            SalesOrderItem item = new SalesOrderItem();
            item.setSalesOrderId(order.getId());
            item.setProductTypeId(itemDTO.getProductTypeId());
            // 自动填充产品名称
            String productName = itemDTO.getProductName();
            if (productName == null || productName.isBlank()) {
                productName = productTypeRepository.findById(itemDTO.getProductTypeId())
                        .map(ProductType::getName).orElse(null);
            }
            item.setProductName(productName);
            item.setQuantity(itemDTO.getQuantity());
            item.setUnit(itemDTO.getUnit());
            // Issue #793: auto-apply 客户协议价 when caller did not provide explicit unitPrice.
            // Resolution: customer-specific selling price → global selling price → caller's value.
            // User-provided price always wins (per May 7 part2 L284-303 — manual override allowed).
            BigDecimal resolvedUnitPrice = itemDTO.getUnitPrice();
            if (priceListService != null
                    && (resolvedUnitPrice == null || resolvedUnitPrice.signum() == 0)) {
                BigDecimal contractPrice = priceListService.findActivePriceForOrder(
                        factoryId,
                        request.getCustomerId(),
                        itemDTO.getProductTypeId(),
                        order.getOrderDate()).orElse(null);
                if (contractPrice != null) {
                    resolvedUnitPrice = contractPrice;
                    log.info("Issue #793: auto-applied 协议价 — SO={}, customerId={}, productId={}, "
                            + "price={}", order.getOrderNumber(), request.getCustomerId(),
                            itemDTO.getProductTypeId(), contractPrice);
                }
            }
            // Canvas-Pricing Phase 4b (2026-05-18): apply 价格策略 (TIERED / PROMOTION / MEMBER / BUNDLE).
            // 此处用 calculate() 写 PricingApplicationLog (审计 audit), CYCLE 策略由月底批处理路径处理.
            // pricingEngine 为 null 时 (老 jar 部署), 完全跳过 — 走原 resolvedUnitPrice (legacy fallback).
            if (pricingEngine != null
                    && resolvedUnitPrice != null
                    && resolvedUnitPrice.signum() > 0
                    && itemDTO.getQuantity() != null
                    && itemDTO.getQuantity().signum() > 0) {
                try {
                    // 查 productCategory (scope_filter 用)
                    String productCategory = productTypeRepository.findById(itemDTO.getProductTypeId())
                            .map(ProductType::getCategory).orElse(null);
                    // 客户分组 — Customer.importance enum (per Sprint 4 W2 spec)
                    String customerGroup = customer.getImportance() != null
                            ? customer.getImportance().name() : null;
                    // customerId: PricingRequest 期望 Long, Customer.id 是 String (length=191).
                    // 仅在 ID 为纯数字时 parse, 否则传 null (MEMBER 策略仍可走 customerGroup 路径).
                    Long customerIdLong = null;
                    if (request.getCustomerId() != null) {
                        try {
                            customerIdLong = Long.parseLong(request.getCustomerId());
                        } catch (NumberFormatException ignored) {
                            // Customer.id 是 String hash/UUID — pricingEngine 仅用作 audit log 引用,
                            // null 不阻断 strategy 应用 (per PricingRequest customerId 字段 javadoc).
                        }
                    }
                    // SP5 F3: ProductType.standardCost is now available (V20260910_43 migration).
                    // Pass it as costEstimate so PricingEngineImpl.checkWarnings() can surface
                    // below-cost warnings. Null guard: if standardCost not configured, pass null
                    // and warning is silently disabled (same as pre-SP5 behaviour).
                    java.math.BigDecimal costEstimateForLine = null;
                    try {
                        costEstimateForLine = productTypeRepository.findById(itemDTO.getProductTypeId())
                                .map(com.cretas.aims.entity.ProductType::getStandardCost)
                                .orElse(null);
                    } catch (Exception e) {
                        log.debug("SP5 F3: failed to resolve standardCost for productType {} — "
                                + "below-cost check disabled for this line: {}",
                                itemDTO.getProductTypeId(), e.getMessage());
                    }
                    com.cretas.aims.service.pricing.PricingRequest pricingReq =
                            com.cretas.aims.service.pricing.PricingRequest.builder()
                                    .factoryId(factoryId)
                                    .productId(itemDTO.getProductTypeId())
                                    // Pass full BigDecimal qty (no intValue truncation) so fractional
                                    // /kg quantities price correctly — engine now takes BigDecimal.
                                    .quantity(itemDTO.getQuantity())
                                    .unitPriceList(resolvedUnitPrice)
                                    .customerId(customerIdLong)
                                    .customerGroup(customerGroup)
                                    .productCategory(productCategory)
                                    .costEstimate(costEstimateForLine)  // SP5 F3: from ProductType.standardCost
                                    .businessEntityType("SO_LINE")
                                    .businessEntityId(order.getOrderNumber()
                                            + "/" + itemDTO.getProductTypeId())
                                    .build();
                    com.cretas.aims.service.pricing.PricingResult pricingResult =
                            pricingEngine.calculate(pricingReq);
                    if (pricingResult != null && pricingResult.getFinalPrice() != null
                            && pricingResult.getFinalPrice().signum() >= 0) {
                        // BUGFIX (2026-05-30, surfaced by F006 UI E2E): PricingResult.finalPrice
                        // is a LINE TOTAL — PricingEngineImpl computes
                        //   originalPrice = unitPriceList × quantity   (line total before strategies)
                        //   finalPrice    = originalPrice − Σ discounts (line total after strategies)
                        // but a SO item stores a PER-UNIT price. Assigning the line total directly to
                        // resolvedUnitPrice made item.unitPrice = qty × price and
                        // order.totalAmount = qty × (qty × price) = qty² × price
                        // (e.g. 276 × ¥50 was stored as unitPrice ¥13,800, total ¥3,808,800).
                        // Convert back to per-unit: perUnit = finalPrice / quantity (HALF_UP, scale 2).
                        BigDecimal finalPerUnit = lineTotalToUnitPrice(
                                pricingResult.getFinalPrice(), itemDTO.getQuantity(), resolvedUnitPrice);
                        log.info("Canvas-Pricing Phase 4b: 应用策略 — SO={}, productId={}, "
                                + "origUnitPrice={}, lineTotal(orig={}, final={}), finalUnitPrice={}, "
                                + "appliedStrategies={}, warnings={}",
                                order.getOrderNumber(), itemDTO.getProductTypeId(),
                                resolvedUnitPrice, pricingResult.getOriginalPrice(),
                                pricingResult.getFinalPrice(), finalPerUnit,
                                pricingResult.getAppliedStrategies() != null
                                        ? pricingResult.getAppliedStrategies().size() : 0,
                                pricingResult.getWarnings() != null
                                        ? pricingResult.getWarnings().size() : 0);
                        resolvedUnitPrice = finalPerUnit;
                    }
                } catch (UnsupportedOperationException e) {
                    // Phase 4b skeleton — PricingEngineImpl 抛 UnsupportedOp, 跳过应用走 legacy fallback.
                    log.debug("Phase 4b skeleton: pricingEngine.calculate 抛 UnsupportedOp, "
                            + "走 legacy unitPrice — SO={}, error={}", order.getOrderNumber(), e.getMessage());
                } catch (Exception e) {
                    // 异常不阻塞 SO 创建 — log warn 走 legacy fallback.
                    log.warn("Canvas-Pricing Phase 4b: pricingEngine.calculate 失败, "
                            + "走 legacy unitPrice — SO={}, productId={}, error={}",
                            order.getOrderNumber(), itemDTO.getProductTypeId(), e.getMessage());
                }
            }
            item.setUnitPrice(resolvedUnitPrice);

            // ── E-5 毛利红线预警 (W1 追溯矩阵 E-5; 非阻断决策修正 2026-06-10) ──────────────
            // 决策 (SP3 spec §6.3 / SP5 spec §8): 低于红线只"预警"不"卡死"
            //   客户原话: "低于底线提示红色, 不是不允许" → HTTP 200 + 警告字段, 不返回 4xx.
            // 在 unitPrice 最终确定后 (含策略引擎折扣) 才做红线检查, 保证检查的是真实报价.
            // resolvedUnitPrice=null 表示未填价格 → 跳过 (BOM/价格缺失不是红线语义).
            if (grossMarginRedlineService != null && resolvedUnitPrice != null
                    && resolvedUnitPrice.signum() > 0
                    && itemDTO.getProductTypeId() != null) {
                com.cretas.aims.dto.pricing.GrossMarginCheckResult marginResult =
                        grossMarginRedlineService.checkMargin(
                                factoryId, itemDTO.getProductTypeId(), resolvedUnitPrice);
                if (Boolean.TRUE.equals(marginResult.getBelowRedline())) {
                    // 低于毛利红线 → 收集预警 (不阻断). 文案不含成本数值 (GrossMarginCheckResult 保证).
                    String displayName = item.getProductName() != null
                            ? item.getProductName() : itemDTO.getProductTypeId();
                    String warn = "行项目「" + displayName + "」" + marginResult.getWarningMessage()
                            + " — 如需继续, 请向销售经理确认";
                    marginWarnings.add(warn);
                    log.info("E-5 毛利红线预警 (非阻断): SO 行 productTypeId={} 低于红线 — {} (订单仍创建)",
                            itemDTO.getProductTypeId(), marginResult.getWarningMessage());
                } else if (marginResult.getBelowRedline() == null) {
                    // 成本数据缺失 / 未配置毛利红线 → WARN log + 放行
                    // 按"禁止降级"原则: 不静默放行也不假装拦截; 明确 WARN 让运营可见配置缺口
                    log.warn("E-5 毛利红线: 无法核算 productTypeId={} 报价 ¥{} — {} (SO 仍创建, 请补配置标准成本/毛利率)",
                            itemDTO.getProductTypeId(), resolvedUnitPrice.toPlainString(),
                            marginResult.getWarningMessage());
                }
                // belowRedline=false → 报价合规, 静默
            }
            // ── end E-5 毛利红线预警 ────────────────────────────────────────────────────

            // ── P1 #33 销售价 ≥ 研发预估价 跨流校验 (G流 SP10 → E流; 非阻断 2026-06-11) ──────
            // 转录需求: 销售订单价不应低于研发预估价 (SP10 QuotationTask 建议售价).
            // 决策对齐 #693: 低于预估价 = 警告放行 (200 + priceWarnings), 不 409 硬拦
            //   (防呆 Rule 1: 提交前看到边界, 不提交后被拒).
            // 在 unitPrice 最终确定后 (含策略引擎折扣) 才校验, 保证比较真实报价.
            // resolvedUnitPrice=null/0 → 跳过 (未填价不是"低于预估价"语义, 与 E-5 守卫一致).
            if (estimatePriceCheckService != null && resolvedUnitPrice != null
                    && resolvedUnitPrice.signum() > 0
                    && itemDTO.getProductTypeId() != null) {
                com.cretas.aims.dto.pricing.EstimatePriceCheckResult estimateResult =
                        estimatePriceCheckService.checkAgainstEstimate(
                                factoryId, itemDTO.getProductTypeId(), resolvedUnitPrice);
                if (Boolean.TRUE.equals(estimateResult.getBelowEstimate())) {
                    // 低于研发预估价 → 收集预警 (不阻断). 文案不含预估价数值 (EstimatePriceCheckResult 保证).
                    String displayName = item.getProductName() != null
                            ? item.getProductName() : itemDTO.getProductTypeId();
                    String warn = "行项目「" + displayName + "」" + estimateResult.getWarningMessage()
                            + " — 如需继续, 请向销售经理确认";
                    priceWarnings.add(warn);
                    log.info("#33 估价预警 (非阻断): SO 行 productTypeId={} 低于研发预估价 — {} (订单仍创建)",
                            itemDTO.getProductTypeId(), estimateResult.getWarningMessage());
                }
                // belowEstimate=null (研发未报价) → 诚实跳过, 不警告不伪造 (无 WARN log: 大量产品本就无 R&D 报价)
                // belowEstimate=false → 报价合规, 静默
            }
            // ── end P1 #33 估价校验 ─────────────────────────────────────────────────────

            // Sprint 4 W2 S-INVOICE-CLIENT-1 Option 3 三层 default 链 — 第 2→3 层 prefill:
            // Item 显式 > SO defaultTaxRate (已 prefill 自客户) > 0. 用户改 SO 级 default 触发批量重算
            // 由前端 watch 处理, 后端只兜底防 NPE.
            BigDecimal effectiveTaxRate = itemDTO.getTaxRate();
            if (effectiveTaxRate == null) {
                effectiveTaxRate = order.getDefaultTaxRate() != null ? order.getDefaultTaxRate() : BigDecimal.ZERO;
            }
            item.setTaxRate(effectiveTaxRate);
            item.setDiscountRate(itemDTO.getDiscountRate() != null ? itemDTO.getDiscountRate() : BigDecimal.ZERO);
            item.setRemark(itemDTO.getRemark());
            item.setSpecification(itemDTO.getSpecification());
            item.setBoxQuantity(itemDTO.getBoxQuantity());
            // P3 多仓字段 (V20260915_01) — 全 nullable, 普通订单不传不影响现有逻辑
            item.setDestWarehouseName(itemDTO.getDestWarehouseName());
            item.setDestWarehouseCode(itemDTO.getDestWarehouseCode());
            item.setExternalPoId(itemDTO.getExternalPoId());
            item.setBarcode(itemDTO.getBarcode());
            item.setAppointmentTime(itemDTO.getAppointmentTime());
            item.setRequiredArrivalDate(itemDTO.getRequiredArrivalDate());
            items.add(item);

            // E-2 空价草稿: unitPrice 为 null 时 calculateLineAmount 返 null,
            // 该行对 totalAmount 贡献 0 (FIX-E2 已允许 totalAmount=0 草稿; 提审时强制有价).
            // 不 null-coalesce 会在此 NPE (BigDecimal.add(null)).
            BigDecimal lineAmount = calculateLineAmount(factoryId, item);
            totalAmount = totalAmount.add(lineAmount != null ? lineAmount : BigDecimal.ZERO);
        }

        salesOrderItemRepository.saveAll(items);

        order.setTotalAmount(totalAmount);
        // SP4: tax_amount = Σ (lineAmountWithTax − lineAmount) across items.
        // 使用减法兜底防止 ×(1+rate) 舍入裂缝 (spec §2.4 "税额减法兜底").
        // 向后兼容: item.taxRate=null/0 时 lineAmountWithTax==lineAmount, 贡献 0.
        BigDecimal taxAmountSum = items.stream()
                .map(it -> {
                    BigDecimal la = it.getLineAmount();
                    BigDecimal lawt = it.getLineAmountWithTax();
                    if (la == null || lawt == null) return BigDecimal.ZERO;
                    return lawt.subtract(la);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        order.setTaxAmount(taxAmountSum);
        order = salesOrderRepository.save(order);

        // Canvas V3: Persist dynamic custom fields via DynamicFieldService
        // (dual-track: JPA columns above + dynamic cf_xxx columns here)
        if (dynamicFieldService != null && request.getCustomFields() != null && !request.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "sales_order", order.getId(), request.getCustomFields());
                log.info("Persisted {} custom fields for sales order {}",
                        request.getCustomFields().size(), order.getId());
            } catch (Exception e) {
                // Non-blocking: log but don't rollback the order
                log.warn("Failed to persist custom fields for sales order {}: {}", order.getId(), e.getMessage());
            }
        }

        // E-5: 把收集到的毛利红线预警附到返回订单上 (非阻断, 随 HTTP 200 返回, 前端展示 sticky warning).
        order.setMarginWarnings(marginWarnings);
        if (!marginWarnings.isEmpty()) {
            log.info("创建销售订单: factoryId={}, orderNumber={}, 含 {} 条毛利红线预警 (非阻断)",
                    factoryId, orderNumber, marginWarnings.size());
        }

        // P1 #33: 把收集到的研发预估价预警附到返回订单上 (非阻断, 随 HTTP 200 返回, 前端 sticky warning).
        order.setPriceWarnings(priceWarnings);
        if (!priceWarnings.isEmpty()) {
            log.info("创建销售订单: factoryId={}, orderNumber={}, 含 {} 条研发预估价预警 (非阻断)",
                    factoryId, orderNumber, priceWarnings.size());
        }

        log.info("创建销售订单: factoryId={}, orderNumber={}, items={}", factoryId, orderNumber, items.size());

        // Round 4 Fix P1-14: publish SalesOrderCreatedEvent so trigger chains can react
        // (e.g. snapshot market price, auto-schedule production, notify sales manager).
        try {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.SalesOrderCreatedEvent(
                this, factoryId, order.getId(),
                order.getCustomerId(), order.getTotalAmount()));
            log.info("已发布 SalesOrderCreatedEvent: SO={}", order.getId());
        } catch (Exception e) {
            log.error("发布 SalesOrderCreatedEvent 失败: {}", e.getMessage(), e);
        }

        return order;
    }

    @Override
    public SalesOrder getSalesOrderById(String factoryId, String orderId) {
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("销售订单不存在"));
        if (!order.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权访问该销售订单")
                    .withHint("当前销售订单不属于该工厂, 无法访问");
        }
        return order;
    }

    @Override
    public PageResponse<SalesOrder> getSalesOrders(String factoryId, int page, int size) {
        return getSalesOrders(factoryId, null, page, size);
    }

    @Override
    @DataScope("created_by")  // Sprint 5 Track G POC + Sprint 6 W2-B sweep (SELF+keyword DB-side + chain)
    public PageResponse<SalesOrder> getSalesOrders(String factoryId, String keyword, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<SalesOrder> result;

        // Sprint 5 POC + Sprint 6 W2-B sweep:
        // - SELF        → findByCreatedBy / searchByKeywordAndCreatedBy (DB-side, no in-memory)
        // - SELF_AND_BELOW / DEPT_AND_BELOW → resolveCreatedByChain + IN-list query
        // - ALL / CUSTOM / no-ctx → 原 path
        DataScopeContext dsCtx = DataScopeContext.current();
        com.cretas.aims.entity.enums.DataScope scope = (dsCtx != null && dsCtx.getUserId() != null)
                ? dsCtx.getScope() : null;
        boolean selfScope = scope == com.cretas.aims.entity.enums.DataScope.SELF;
        boolean chainScope = scope == com.cretas.aims.entity.enums.DataScope.SELF_AND_BELOW
                || scope == com.cretas.aims.entity.enums.DataScope.DEPT_AND_BELOW;
        List<Long> chain = null;
        if (chainScope && dsCtx != null) {
            chain = dataScopeResolver != null
                    ? dataScopeResolver.resolveCreatedByChain(dsCtx)
                    : List.of(dsCtx.getUserId());
            if (chain == null || chain.isEmpty()) chain = List.of(dsCtx.getUserId());
        }

        if (keyword != null && !keyword.isBlank()) {
            // Bug G + audit H1: escape SQL LIKE wildcards (% _ \) so user-typed wildcards
            // become literal characters. Repository uses ESCAPE '\' clause.
            String escaped = keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
            if (selfScope) {
                log.debug("DataScope SELF + keyword (DB-side): created_by={}", dsCtx.getUserId());
                result = salesOrderRepository.searchByFactoryAndKeywordAndCreatedBy(
                        factoryId, escaped, dsCtx.getUserId(), pageRequest);
            } else if (chainScope) {
                log.debug("DataScope {} + keyword: chain size={}", scope, chain.size());
                result = salesOrderRepository.searchByFactoryAndKeywordAndCreatedByIn(
                        factoryId, escaped, chain, pageRequest);
            } else {
                result = salesOrderRepository.searchByFactoryAndKeyword(factoryId, escaped, pageRequest);
            }
        } else if (selfScope) {
            log.debug("DataScope SELF: filter sales orders by created_by={}", dsCtx.getUserId());
            result = salesOrderRepository.findByFactoryIdAndCreatedByOrderByCreatedAtDesc(
                    factoryId, dsCtx.getUserId(), pageRequest);
        } else if (chainScope) {
            log.debug("DataScope {} for sales orders: chain size={}", scope, chain.size());
            result = salesOrderRepository.findByFactoryIdAndCreatedByInOrderByCreatedAtDesc(
                    factoryId, chain, pageRequest);
        } else {
            result = salesOrderRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageRequest);
        }
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    public PageResponse<SalesOrder> getSalesOrdersByStatus(String factoryId, SalesOrderStatus status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size);
        Page<SalesOrder> result = salesOrderRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(factoryId, status, pageRequest);
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "CONFIRM", entityType = "SalesOrder",
              entityIdParam = "orderId")
    public SalesOrder confirmOrder(String factoryId, String orderId) {
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        runConfiguredValidation(factoryId, "STATUS_CHANGE", Map.of(
                "status", order.getStatus().name(), "targetStatus", "CONFIRMED"));
        if (order.getStatus() != SalesOrderStatus.DRAFT) {
            throw new BusinessException(409, "只有草稿状态的订单可以确认")
                    .withHint("请刷新订单列表查看最新状态");
        }
        checkTransitionAllowed(factoryId, order.getStatus().name(), "CONFIRMED");
        order.setStatus(SalesOrderStatus.CONFIRMED);
        order.setConfirmedAt(LocalDateTime.now());
        SalesOrder saved = salesOrderRepository.save(order);
        log.info("确认销售订单: orderId={}, orderNumber={}", orderId, saved.getOrderNumber());

        // 注意: 供应链联动不再在确认时触发
        // 新流程: CONFIRMED -> 提交财务审核 -> 财务批准后才触发供应链联动
        // 发布确认事件仅用于通知（不再驱动生产）
        try {
            applicationEventPublisher.publishEvent(new SalesOrderConfirmedEvent(this, factoryId, saved.getId()));
            log.info("已发布SalesOrderConfirmedEvent(仅通知): SO={}", saved.getId());
        } catch (Exception e) {
            log.error("发布SalesOrderConfirmedEvent失败: SO={}", saved.getId(), e);
        }

        if (!hasSalesApprovalPolicy(factoryId)) {
            return saved;
        }
        return routeSalesOrderByApprovalPolicy(factoryId, saved, "订单确认后按销售审批阈值自动提交", null);
    }

    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "SUBMIT_FINANCE", entityType = "SalesOrder",
              entityIdParam = "orderId")
    public SalesOrder submitForFinanceReview(String factoryId, String orderId) {
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        runConfiguredValidation(factoryId, "STATUS_CHANGE", Map.of(
                "status", order.getStatus().name(), "targetStatus", "PENDING_FINANCE_REVIEW"));
        if (order.getStatus() != SalesOrderStatus.CONFIRMED
                && order.getStatus() != SalesOrderStatus.FINANCE_REJECTED) {
            throw new BusinessException(409, "只有已确认或财务驳回状态的订单可以提交财务审核")
                    .withHint("请刷新订单列表查看最新状态");
        }

        if (hasSalesApprovalPolicy(factoryId)) {
            return routeSalesOrderByApprovalPolicy(factoryId, order, "按销售审批阈值提交", null);
        }

        // E-2 空价草稿支持: 进入财审前强制所有行有单价且总额 > 0
        // 需求依据: 行717 "后期审核时报价/单价应有数据不能空"
        // (草稿/确认阶段允许空价，提审是第一个强制点)
        validatePriceBeforeFinanceReview(factoryId, orderId, order);

        checkTransitionAllowed(factoryId, order.getStatus().name(), "PENDING_FINANCE_REVIEW");
        order.setStatus(SalesOrderStatus.PENDING_FINANCE_REVIEW);
        // 清除上一次审核记录，重新审核
        order.setFinanceReviewedBy(null);
        order.setFinanceReviewedAt(null);
        order.setFinanceReviewNotes(null);
        SalesOrder saved = salesOrderRepository.save(order);
        log.info("销售订单已提交财务审核: orderId={}, orderNumber={}", orderId, saved.getOrderNumber());
        return saved;
    }

    /**
     * E-2 提审价格守卫 (六扇门追溯矩阵 E-2, 2026-06-10).
     *
     * <p>草稿/确认阶段允许行项目单价为空（下单时可空）。
     * 提交财务审核时，所有行必须有正数单价且订单总额 > 0。
     *
     * <p>fool-proof Rule 1+4位一体: 报错信息精确到行（哪几行缺价）+ actionHint 指导下一步操作。
     */
    private void validatePriceBeforeFinanceReview(String factoryId, String orderId, SalesOrder order) {
        List<SalesOrderItem> items = salesOrderItemRepository.findBySalesOrderId(orderId);
        if (items == null || items.isEmpty()) {
            throw new BusinessException(409,
                    "订单「" + order.getOrderNumber() + "」没有行项目，无法提交财务审核")
                    .withHint("请先添加行项目再提审");
        }

        List<String> missingPriceLines = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            SalesOrderItem item = items.get(i);
            BigDecimal price = item.getUnitPrice();
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                String lineLabel = (item.getProductName() != null && !item.getProductName().isBlank())
                        ? "第" + (i + 1) + "行「" + item.getProductName() + "」"
                        : "第" + (i + 1) + "行（产品ID: " + item.getProductTypeId() + "）";
                missingPriceLines.add(lineLabel);
            }
        }

        if (!missingPriceLines.isEmpty()) {
            String lineDetail = String.join("、", missingPriceLines);
            throw new BusinessException(409,
                    "订单「" + order.getOrderNumber() + "」提审失败：" + lineDetail + " 单价为空——"
                    + "审核时单价不能为空（需求行717）")
                    .withHint("请先补全以上行项目的单价，再提交财务审核");
        }

        // 双重保险: 若所有行都有正价但总额仍为 0（极端边界），也拒绝
        if (order.getTotalAmount() == null || order.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(409,
                    "订单「" + order.getOrderNumber() + "」总金额为 0，无法提交财务审核")
                    .withHint("请检查行项目单价和数量是否正确填写");
        }
    }

    private SalesOrder routeSalesOrderByApprovalPolicy(String factoryId, SalesOrder order, String notes, Long reviewerId) {
        validatePriceBeforeFinanceReview(factoryId, order.getId(), order);

        Map<String, Object> context = buildSalesApprovalContext(order);
        if (Boolean.TRUE.equals(context.get("externalOrder"))) {
            return approveFinanceForOrder(factoryId, order, "外部渠道订单免审，自动通过", null, reviewerId);
        }

        if (hasActiveSalesWorkflow(factoryId)) {
            return routeSalesOrderByWorkflow(factoryId, order, context, notes, reviewerId);
        }

        boolean requiresApproval = approvalChainService != null
                && approvalChainService.requiresApproval(factoryId, DecisionType.SALES_ORDER_APPROVAL, context);

        if (requiresApproval) {
            checkTransitionAllowed(factoryId, order.getStatus().name(), "PENDING_FINANCE_REVIEW");
            order.setStatus(SalesOrderStatus.PENDING_FINANCE_REVIEW);
            order.setFinanceReviewedBy(null);
            order.setFinanceReviewedAt(null);
            order.setFinanceReviewNotes(notes);
            SalesOrder saved = salesOrderRepository.save(order);
            log.info("销售订单触发阈值审批: orderId={}, orderNumber={}, amount={}",
                    order.getId(), saved.getOrderNumber(), saved.getTotalAmount());
            return saved;
        }

        return approveFinanceForOrder(factoryId, order, "未触发销售审批阈值或满足免审配置，自动通过", null, reviewerId);
    }

    private boolean hasSalesApprovalPolicy(String factoryId) {
        if (hasActiveSalesWorkflow(factoryId)) {
            return true;
        }
        return approvalChainService != null
                && !approvalChainService.getConfigsByDecisionType(factoryId, DecisionType.SALES_ORDER_APPROVAL).isEmpty();
    }

    private boolean hasActiveSalesWorkflow(String factoryId) {
        if (workflowEngine != null && workflowEngine.hasActiveWorkflow(factoryId, "SALES_ORDER")) {
            return true;
        }
        return approvalWorkflowService != null
                && approvalWorkflowService.getActiveByDecisionType(factoryId, DecisionType.SALES_ORDER_APPROVAL).isPresent();
    }

    private Map<String, Object> buildSalesApprovalContext(SalesOrder order) {
        BigDecimal amount = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        Map<String, Object> context = new HashMap<>();
        context.put("amount", amount);
        context.put("totalAmount", amount);
        context.put("orderId", order.getId());
        context.put("orderNumber", order.getOrderNumber() != null ? order.getOrderNumber() : "");
        context.put("customerId", order.getCustomerId() != null ? order.getCustomerId() : "");
        context.put("customerName", order.getCustomerName() != null ? order.getCustomerName() : "");
        context.put("externalOrderTitle", order.getExternalOrderTitle() != null ? order.getExternalOrderTitle() : "");
        context.put("externalOrder", isExternalChannelOrder(order));
        return context;
    }

    private boolean isExternalChannelOrder(SalesOrder order) {
        if (order == null) {
            return false;
        }
        if (hasText(order.getExternalOrderTitle())) {
            return true;
        }
        Customer customer = null;
        if (customerRepository != null && hasText(order.getCustomerId())) {
            try {
                Optional<Customer> found = customerRepository.findByIdAndFactoryId(order.getCustomerId(), order.getFactoryId());
                if (found != null && found.isPresent()) {
                    customer = found.get();
                }
            } catch (Exception e) {
                log.warn("加载客户渠道信息失败，按普通销售订单处理: orderId={}, customerId={}, error={}",
                        order.getId(), order.getCustomerId(), e.getMessage());
            }
        }
        String customerName = customer != null ? customer.getName() : order.getCustomerName();
        String businessType = customer != null ? customer.getBusinessType() : null;
        String customerType = customer != null ? customer.getCustomerType() : null;
        String type = customer != null ? customer.getType() : null;
        CustomerSource source = customer != null ? customer.getSource() : null;
        return source == CustomerSource.PLATFORM
                || hasExternalChannelToken(customerName)
                || hasExternalChannelToken(businessType)
                || hasExternalChannelToken(customerType)
                || hasExternalChannelToken(type);
    }

    private boolean hasExternalChannelToken(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("external")
                || normalized.contains("channel")
                || normalized.contains("platform")
                || normalized.contains("marketplace")
                || normalized.contains("third_party")
                || normalized.contains("外部")
                || normalized.contains("渠道")
                || normalized.contains("平台");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private SalesOrder routeSalesOrderByWorkflow(String factoryId, SalesOrder order, Map<String, Object> context,
                                                 String notes, Long reviewerId) {
        if (workflowEngine == null) {
            throw new BusinessException(409,
                    "销售订单审批工作流已配置，但审批引擎不可用，无法判定是否需要财审")
                    .withHint("请检查审批工作流引擎配置，或改用审批链金额阈值配置")
                    .withHintTarget("approvalWorkflow");
        }

        ApprovalWorkflowInstance instance = workflowEngine.getCurrentInstance(factoryId, "SALES_ORDER", order.getId())
                .filter(existing -> existing.getStatus() == InstanceStatus.RUNNING)
                .orElseGet(() -> workflowEngine.startWorkflow(
                        factoryId, "SALES_ORDER", order.getId(), context, order.getCreatedBy()));

        if (instance.getStatus() == InstanceStatus.RUNNING) {
            checkTransitionAllowed(factoryId, order.getStatus().name(), "PENDING_FINANCE_REVIEW");
            order.setStatus(SalesOrderStatus.PENDING_FINANCE_REVIEW);
            order.setFinanceReviewedBy(null);
            order.setFinanceReviewedAt(null);
            order.setFinanceReviewNotes(notes);
            SalesOrder saved = salesOrderRepository.save(order);
            log.info("销售订单进入审批工作流: orderId={}, orderNumber={}, instanceId={}, amount={}",
                    order.getId(), saved.getOrderNumber(), instance.getId(), saved.getTotalAmount());
            return saved;
        }

        if (instance.getStatus() == InstanceStatus.APPROVED) {
            return approveFinanceForOrder(factoryId, order, "销售审批工作流自动通过", null, reviewerId);
        }

        if (instance.getStatus() == InstanceStatus.REJECTED) {
            checkTransitionAllowed(factoryId, order.getStatus().name(), "FINANCE_REJECTED");
            order.setStatus(SalesOrderStatus.FINANCE_REJECTED);
            order.setFinanceReviewNotes("销售审批工作流自动拒绝");
            return salesOrderRepository.save(order);
        }

        throw new BusinessException(409,
                "销售订单审批工作流未得到有效审批结果: " + instance.getStatus())
                .withHint("请检查销售订单审批工作流配置后重试")
                .withHintTarget("approvalWorkflow");
    }

    private void startSalesApprovalWorkflowIfConfigured(
            String factoryId, SalesOrder order, Map<String, Object> context) {
        if (approvalWorkflowService == null || approvalWorkflowExecutor == null) {
            return;
        }
        Optional<ApprovalWorkflow> workflow = approvalWorkflowService
                .getActiveByDecisionType(factoryId, DecisionType.SALES_ORDER_APPROVAL);
        if (workflow.isEmpty()) {
            return;
        }
        try {
            ExecutionContext execution = approvalWorkflowExecutor.start(
                    workflow.get(), order.getId(), context, order.getCreatedBy());
            log.info("已启动销售订单审批工作流: orderId={}, executionId={}, status={}",
                    order.getId(), execution.getExecutionId(), execution.getStatus());
        } catch (Exception e) {
            throw new BusinessException(409,
                    "销售订单 " + order.getOrderNumber() + " 启动审批流程失败：" + e.getMessage())
                    .withHint("请检查销售订单审批工作流配置后重试")
                    .withHintTarget("approvalWorkflow");
        }
    }

    @Override
    @Transactional
    public SalesOrder financeApproveOrder(String factoryId, String orderId, String notes, Long reviewerId) {
        return financeApproveOrder(factoryId, orderId, notes, null, reviewerId);
    }

    /**
     * 六扇门 V1 §2.2 audit fix #6: accept estimatedCost from finance reviewer dialog.
     * Pre-fix: dialog only had notes prompt — finance had no place to record cost.
     * Now: dialog shows totalAmount + cost input + auto-computed profit.
     */
    @Override
    @Transactional
    public SalesOrder financeApproveOrder(String factoryId, String orderId, String notes,
                                           java.math.BigDecimal estimatedCost, Long reviewerId) {
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        // 强制加载 items，防止事件处理器中 LazyInitializationException
        org.hibernate.Hibernate.initialize(order.getItems());
        if (order.getStatus() != SalesOrderStatus.PENDING_FINANCE_REVIEW) {
            throw new BusinessException(409, "只有待财务审核状态的订单可以审批")
                    .withHint("请刷新订单列表查看最新状态");
        }
        return approveFinanceForOrder(factoryId, order, notes, estimatedCost, reviewerId);
    }

    private SalesOrder approveFinanceForOrder(String factoryId, SalesOrder order, String notes,
                                              java.math.BigDecimal estimatedCost, Long reviewerId) {
        org.hibernate.Hibernate.initialize(order.getItems());
        checkTransitionAllowed(factoryId, order.getStatus().name(), "FINANCE_APPROVED");
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        order.setFinanceReviewedBy(reviewerId);
        order.setFinanceReviewedAt(LocalDateTime.now());
        order.setFinanceReviewNotes(notes);

        // 六扇门 V1 §2.2 audit fix #6: persist finance-reviewer-input estimatedCost
        if (estimatedCost != null) {
            order.setEstimatedCost(estimatedCost);
        }

        // 计算预估利润（如果有 estimatedCost — 当前调用或历史值）
        if (order.getEstimatedCost() != null && order.getTotalAmount() != null) {
            order.setEstimatedProfit(order.getTotalAmount().subtract(order.getEstimatedCost()));
        }

        SalesOrder saved = salesOrderRepository.save(order);
        log.info("销售订单财务审核通过: orderId={}, orderNumber={}, reviewerId={}",
                saved.getId(), saved.getOrderNumber(), reviewerId);

        // 发布财务审核通过事件 → 触发供应链联动（库存检查+生产计划+采购建议）
        try {
            applicationEventPublisher.publishEvent(
                    new SalesOrderFinanceApprovedEvent(this, factoryId, saved.getId(), reviewerId));
            log.info("已发布SalesOrderFinanceApprovedEvent: SO={}", saved.getId());
        } catch (Exception e) {
            log.error("发布SalesOrderFinanceApprovedEvent失败(不影响审批): SO={}", saved.getId(), e);
        }

        return saved;
    }

    @Override
    @Transactional
    public SalesOrder financeRejectOrder(String factoryId, String orderId, String reason, Long reviewerId) {
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        if (order.getStatus() != SalesOrderStatus.PENDING_FINANCE_REVIEW) {
            throw new BusinessException(409, "只有待财务审核状态的订单可以驳回")
                    .withHint("请刷新订单列表查看最新状态");
        }
        checkTransitionAllowed(factoryId, order.getStatus().name(), "FINANCE_REJECTED");
        order.setStatus(SalesOrderStatus.FINANCE_REJECTED);
        order.setFinanceReviewedBy(reviewerId);
        order.setFinanceReviewedAt(LocalDateTime.now());
        order.setFinanceReviewNotes(reason);
        SalesOrder saved = salesOrderRepository.save(order);
        log.info("销售订单财务审核驳回: orderId={}, orderNumber={}, reviewerId={}, reason={}",
                orderId, saved.getOrderNumber(), reviewerId, reason);
        return saved;
    }

    /**
     * Sprint4-H F-AR-1: 财务成本核算 — 拉 BOM 标准成本 + 当前预估成本 +
     * (订单完成后) 实际生产成本, 自动计算预估利润 vs 实际利润对比.
     */
    @Override
    @Transactional(readOnly = true)
    public com.cretas.aims.dto.inventory.FinanceCostBreakdown getOrderCostBreakdown(
            String factoryId, String orderId) {
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        // 强制初始化 items (lazy)
        order.getItems().size();

        BigDecimal totalAmount = order.getTotalAmount() != null
                ? order.getTotalAmount()
                : BigDecimal.ZERO;
        BigDecimal estimatedCost = order.getEstimatedCost();
        BigDecimal estimatedProfit = order.getEstimatedProfit();

        BigDecimal bomStandardCostSum = BigDecimal.ZERO;
        BigDecimal actualCostSum = BigDecimal.ZERO;
        boolean anyBomAvailable = false;
        boolean anyActualMissing = false;
        // D1: 订单级标准成本是否全口径含人工 — 任一行标准缺人工 → 不做订单级超支报警 (避免假阳性).
        boolean allStdLaborIncluded = true;

        java.util.List<com.cretas.aims.dto.inventory.FinanceCostBreakdown.LineCostBreakdown> lines =
                new java.util.ArrayList<>();

        for (SalesOrderItem item : order.getItems()) {
            BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            BigDecimal sellUnit = item.getUnitPrice();
            BigDecimal costUnit = item.getCostUnitPrice();
            BigDecimal lineAmount = (sellUnit != null)
                    ? qty.multiply(sellUnit).setScale(2, java.math.RoundingMode.HALF_UP)
                    : null;

            // D1: 同口径标准单位成本 — 含研发预估标准人工, 与含人工的实际成本可比.
            //   StandardCostService 返回 totalUnitCost = 料 + 标准人工 (口径不全时诚实 null).
            //   未注册 standardCostService 时回退到纯料 BOM 标准 (旧行为, 但口径偏低 → 下方报警会被
            //   laborIncluded 守卫拦住, 见订单级 + 行级判定)。
            BigDecimal bomUnit = null;
            BigDecimal bomLine = null;
            boolean lineStdLaborIncluded = false;
            if (item.getProductTypeId() != null) {
                try {
                    if (standardCostService != null) {
                        com.cretas.aims.service.bom.StandardCostService.StandardUnitCost std =
                                standardCostService.resolveStandardUnitCost(factoryId, item.getProductTypeId());
                        // 同口径标准 (含人工) 优先; null → 口径不全 (无 BOM/料未定价/缺研发人工) → 诚实留空
                        if (std.getTotalUnitCost() != null
                                && std.getTotalUnitCost().compareTo(BigDecimal.ZERO) > 0) {
                            bomUnit = std.getTotalUnitCost();
                            lineStdLaborIncluded = std.isLaborIncluded();
                        }
                    } else if (bomRecipeService != null) {
                        // 回退: 纯料 BOM 标准 (口径偏低, 不含人工). laborIncluded 留 false → 不误报。
                        java.util.Optional<com.cretas.aims.entity.bom.BomRecipe> recipeOpt =
                                bomRecipeService.getCurrentRecipe(factoryId, item.getProductTypeId());
                        if (recipeOpt.isPresent()) {
                            BigDecimal tc = recipeOpt.get().getTotalCost();
                            // B2 fix: null/<=0 totalCost 表示 BOM 料未定价, 不当 0 (避免误导财务).
                            if (tc != null && tc.compareTo(BigDecimal.ZERO) > 0) {
                                bomUnit = tc;
                            }
                        }
                    }
                    if (bomUnit != null) {
                        bomLine = qty.multiply(bomUnit).setScale(2, java.math.RoundingMode.HALF_UP);
                        bomStandardCostSum = bomStandardCostSum.add(bomLine);
                        anyBomAvailable = true;
                    }
                } catch (Exception e) {
                    log.warn("标准成本查询失败 (productTypeId={}): {}", item.getProductTypeId(), e.getMessage());
                }
            }

            BigDecimal actualLine = null;
            // B2 fix: costUnitPrice=0 means cost was never recorded (default or erroneous data),
            // not that cost is truly zero. Treat 0 same as null → missing.
            if (costUnit != null && costUnit.compareTo(BigDecimal.ZERO) > 0) {
                actualLine = qty.multiply(costUnit).setScale(2, java.math.RoundingMode.HALF_UP);
                actualCostSum = actualCostSum.add(actualLine);
            } else {
                anyActualMissing = true;
            }

            // SP3: 行级超支百分比.
            // D1 守卫: 仅当标准侧含人工 (lineStdLaborIncluded) 才算行级超支 — 否则标准 (纯料) 比
            //   实际 (含人工) 必然虚高超支 = 假阳性, 跳过不报 (variancePct 留 null, 财务看 caliberHint)。
            BigDecimal lineVariancePct = null;
            Boolean lineBelowThreshold = null;
            BigDecimal actualCostPerUnit = (costUnit != null && costUnit.compareTo(BigDecimal.ZERO) > 0) ? costUnit : null;
            // 实际成本存在但标准侧缺人工口径 → 标记订单级口径不全 (用于拦订单级报警)
            if (actualCostPerUnit != null && !lineStdLaborIncluded) {
                allStdLaborIncluded = false;
            }
            if (costVarianceService != null && actualCostPerUnit != null && bomUnit != null
                    && lineStdLaborIncluded) {
                lineVariancePct = costVarianceService.computeVariancePct(actualCostPerUnit, bomUnit);
                if (lineVariancePct != null) {
                    BigDecimal thresh = costVarianceService.resolveThreshold(factoryId, item.getProductTypeId());
                    lineBelowThreshold = lineVariancePct.compareTo(thresh) <= 0;
                }
            }

            lines.add(com.cretas.aims.dto.inventory.FinanceCostBreakdown.LineCostBreakdown.builder()
                    .productId(item.getProductTypeId())
                    .productName(item.getProductName())
                    .quantity(qty)
                    .unitPrice(sellUnit)
                    .lineAmount(lineAmount)
                    .bomStandardUnitCost(bomUnit)
                    .bomStandardLineCost(bomLine)
                    .actualLineCost(actualLine)
                    .standardCostPerUnit(bomUnit)
                    .actualCostPerUnit(actualCostPerUnit)
                    .variancePct(lineVariancePct)
                    .belowThreshold(lineBelowThreshold)
                    .build());
        }

        BigDecimal bomStandardCost = anyBomAvailable ? bomStandardCostSum : null;
        // actualCost: 任一行没有 costUnitPrice 即视为不完整 (订单未完成生产), 返 null
        BigDecimal actualCost = (anyActualMissing || order.getItems().isEmpty())
                ? null
                : actualCostSum;
        BigDecimal actualProfit = (actualCost != null)
                ? totalAmount.subtract(actualCost)
                : null;

        BigDecimal marginEst = null;
        BigDecimal marginAct = null;
        if (totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            if (estimatedProfit != null) {
                marginEst = estimatedProfit
                        .multiply(BigDecimal.valueOf(100))
                        .divide(totalAmount, 2, java.math.RoundingMode.HALF_UP);
            }
            if (actualProfit != null) {
                marginAct = actualProfit
                        .multiply(BigDecimal.valueOf(100))
                        .divide(totalAmount, 2, java.math.RoundingMode.HALF_UP);
            }
        }

        // B2 fix: produce specific actionable hints for each missing-cost scenario.
        StringBuilder hint = new StringBuilder();
        if (bomStandardCost == null) {
            // B2 fix: differentiate between "no BOM" and "BOM items missing unit price"
            hint.append("部分产品的 BOM 标准成本不可用 (无 ACTIVE BOM 配方, 或 BOM 配方中原料单价未配置). ");
            hint.append("请到「配方管理」为 BOM 原料填写单价后重新查看. ");
        }
        if (actualCost == null) {
            hint.append("实际成本暂不可用: 订单行成本单价未录入 (生产批次完工后系统自动回填). ");
        }
        // D1: 标准成本口径不全 (缺研发预估人工) → 提示财务标准成本未含人工, 超支对比已跳过.
        if (bomStandardCost != null && actualCost != null && !allStdLaborIncluded) {
            hint.append("标准成本未含研发预估人工 (部分产品缺 人工成本 元/kg), 已跳过超支对比以避免误报. " +
                    "请在「研发报价」补填人工成本后重新查看同口径超支. ");
        }
        String hintStr = hint.length() > 0 ? hint.toString().trim() : null;

        // SP3: 订单级超支计算.
        // D1 守卫: 仅当全部行标准侧含人工 (allStdLaborIncluded) 才做订单级超支报警 — 否则纯料标准
        //   vs 含人工实际 = 系统性虚高 = 假阳性, 跳过 (variancePct/alarmMessage 留 null)。
        java.math.BigDecimal orderVariancePct = null;
        java.math.BigDecimal orderVarianceAbsolute = null;
        Boolean orderBelowThreshold = null;
        String orderAlarmMessage = null;
        if (costVarianceService != null && actualCost != null && bomStandardCost != null
                && allStdLaborIncluded) {
            orderVariancePct = costVarianceService.computeVariancePct(actualCost, bomStandardCost);
            if (orderVariancePct != null) {
                orderVarianceAbsolute = actualCost.subtract(bomStandardCost);
                // 订单级阈值用第一行产品类型作为 hint (或 null → 工厂全局)
                String firstProductTypeId = order.getItems().isEmpty() ? null
                        : order.getItems().get(0).getProductTypeId();
                java.math.BigDecimal thresh = costVarianceService.resolveThreshold(factoryId, firstProductTypeId);
                orderBelowThreshold = orderVariancePct.compareTo(thresh) <= 0;
                if (Boolean.FALSE.equals(orderBelowThreshold)) {
                    // 通用预警文案 — 不内嵌绝对成本金额或具体百分比，
                    // 避免向无 finance:read 权限的销售角色泄露敏感数值。
                    // variancePct / varianceAbsolute 已在各自 @PriceSensitive 字段独立脱敏。
                    orderAlarmMessage = "成本超出标准阈值，请关注";
                }
            }
        }

        // SP4: 从订单头读 taxAmount (createSalesOrder/updateSalesOrder 已汇总写入)
        // 向后兼容: 旧订单 taxAmount=null → 视为 0 (未配置税率 = 免税)
        BigDecimal soTaxAmount = order.getTaxAmount() != null ? order.getTaxAmount() : BigDecimal.ZERO;
        // SP4: totalAmountWithTax = 未税净额 + 税额 (不含运费 shippingFee / extraFees; 纯价税对)
        BigDecimal totalAmountWithTax = totalAmount.add(soTaxAmount);

        return com.cretas.aims.dto.inventory.FinanceCostBreakdown.builder()
                .totalAmount(totalAmount)
                // SP4: 暴露税额 + 含税双值
                .taxAmount(soTaxAmount)
                .totalAmountWithTax(totalAmountWithTax)
                .bomStandardCost(bomStandardCost)
                .currentEstimatedCost(estimatedCost)
                .currentEstimatedProfit(estimatedProfit)
                .actualCost(actualCost)
                .actualProfit(actualProfit)
                .profitMarginEstimated(marginEst)
                .profitMarginActual(marginAct)
                .dataSourceHint(hintStr)
                .varianceAbsolute(orderVarianceAbsolute)
                .variancePct(orderVariancePct)
                .belowThreshold(orderBelowThreshold)
                .alarmMessage(orderAlarmMessage)
                // P1 #32: 委外加工费独立科目 — 诚实 null.
                // WorkProcess/WorkProcessTask 当前无 isOutsourced + outsourcedFee 列,
                // 委外加工费无生产记录来源 → 不伪造数字, 返 null.
                // 待 WorkProcess 加 is_outsourced 列后接入真实数据.
                .processingFee(null)
                // SP12: 实际成本拆分 (材料逐料 + 人工 + 制费), 来自生产真实领料/报工.
                // 诚实 null: 订单未投产 / 未领料 / 未报工时返 null. 不破坏上面聚合字段.
                .actualCostSplit(buildActualCostSplit(factoryId, orderId))
                .lines(lines)
                .build();
    }

    /**
     * SP12: 构建实际成本三分拆分 (材料逐料 / 人工 / 制费).
     *
     * <p>客户原话 (六扇门 [12:36]): "财务根据前面领料的批次去核算最终的实际成本"。
     * 把单一 actualCost 总额拆解成可核算的组分。
     *
     * <p>数据链: 销售订单 → ProductionPlan(sourceOrderId) → ProductionBatch(planId)
     * → MaterialConsumption(productionBatchId, 逐料) + 批次 laborCost / equipmentCost / otherCost。
     *
     * <p>诚实 null 策略:
     * <ul>
     *   <li>repos 未注册 (单测/模块未部署) → 整个 split 为 null;
     *   <li>订单无关联生产计划 → split 非 null 但各组分 null, dataSourceHint 说明"未投产";
     *   <li>批次无领料 → materialCost null + materials 空;
     *   <li>批次无报工 → laborCost null。
     * </ul>
     * 任何组分缺数据都不伪造数字。
     *
     * @return ActualCostSplit, 或 null (repos 不可用)
     */
    private com.cretas.aims.dto.inventory.FinanceCostBreakdown.ActualCostSplit
            buildActualCostSplit(String factoryId, String orderId) {
        // repos 未注册 → 诚实 null (不伪造)
        if (productionPlanRepository == null || productionBatchRepository == null
                || materialConsumptionRepository == null) {
            return null;
        }

        java.util.List<com.cretas.aims.entity.ProductionPlan> plans;
        try {
            plans = productionPlanRepository.findByFactoryIdAndSourceOrderId(factoryId, orderId);
        } catch (Exception e) {
            log.warn("SP12 实际成本拆分: 查询关联生产计划失败 (orderId={}): {}", orderId, e.getMessage());
            return null;
        }

        if (plans == null || plans.isEmpty()) {
            // 订单未关联生产计划 → 尚未投产, 无真实成本可拆
            return com.cretas.aims.dto.inventory.FinanceCostBreakdown.ActualCostSplit.builder()
                    .batchCount(0)
                    .materials(java.util.Collections.emptyList())
                    .dataSourceHint("订单尚未关联生产计划 (未投产), 暂无实际领料/报工成本可拆分.")
                    .build();
        }

        // 取所有关联计划下的批次
        java.util.Set<String> planIds = new java.util.HashSet<>();
        for (com.cretas.aims.entity.ProductionPlan p : plans) {
            if (p.getId() != null) {
                planIds.add(p.getId());
            }
        }

        java.util.List<com.cretas.aims.entity.ProductionBatch> batches =
                planIds.isEmpty()
                        ? java.util.Collections.emptyList()
                        : productionBatchRepository.findByFactoryIdAndProductionPlanIdIn(factoryId, planIds);

        int batchCount = batches.size();

        // ---- 人工 + 制费: 批次级 laborCost / equipmentCost + otherCost rollup ----
        BigDecimal laborSum = BigDecimal.ZERO;
        boolean anyLabor = false;
        BigDecimal overheadSum = BigDecimal.ZERO;
        boolean anyOverhead = false;
        java.util.List<Long> batchPkIds = new java.util.ArrayList<>();
        for (com.cretas.aims.entity.ProductionBatch b : batches) {
            if (b.getId() != null) {
                batchPkIds.add(b.getId());
            }
            if (b.getLaborCost() != null) {
                laborSum = laborSum.add(b.getLaborCost());
                anyLabor = true;
            }
            // 制费 = 设备成本 + 其他成本
            if (b.getEquipmentCost() != null) {
                overheadSum = overheadSum.add(b.getEquipmentCost());
                anyOverhead = true;
            }
            if (b.getOtherCost() != null) {
                overheadSum = overheadSum.add(b.getOtherCost());
                anyOverhead = true;
            }
        }
        BigDecimal laborCost = anyLabor ? laborSum.setScale(2, java.math.RoundingMode.HALF_UP) : null;
        BigDecimal overheadCost = anyOverhead ? overheadSum.setScale(2, java.math.RoundingMode.HALF_UP) : null;

        // ---- 材料: 逐料聚合 MaterialConsumption (领料消耗记录) ----
        // 按 materialTypeId 聚合: 实际用量 Σquantity, 金额 ΣtotalCost, 移动均价 = 金额/用量
        java.util.Map<String, BigDecimal> qtyByType = new java.util.LinkedHashMap<>();
        java.util.Map<String, BigDecimal> amtByType = new java.util.LinkedHashMap<>();
        // 兜底单价 (用量为 0 时用首个 unitPrice)
        java.util.Map<String, BigDecimal> fallbackUnitPrice = new java.util.HashMap<>();

        for (Long pkId : batchPkIds) {
            java.util.List<com.cretas.aims.entity.MaterialConsumption> cons;
            try {
                cons = materialConsumptionRepository.findByProductionBatchIdAndFactoryId(pkId, factoryId);
            } catch (Exception e) {
                log.warn("SP12 实际成本拆分: 查询批次 {} 领料失败: {}", pkId, e.getMessage());
                continue;
            }
            for (com.cretas.aims.entity.MaterialConsumption c : cons) {
                String typeId = c.getMaterialTypeId();
                if (typeId == null) {
                    // 无物料类型的消耗记录无法逐料归类, 跳过逐料 (但金额仍记到 "未分类")
                    typeId = "__UNCLASSIFIED__";
                }
                BigDecimal q = c.getQuantity() != null ? c.getQuantity() : BigDecimal.ZERO;
                BigDecimal amt = c.getTotalCost() != null ? c.getTotalCost() : BigDecimal.ZERO;
                qtyByType.merge(typeId, q, BigDecimal::add);
                amtByType.merge(typeId, amt, BigDecimal::add);
                if (c.getUnitPrice() != null) {
                    fallbackUnitPrice.putIfAbsent(typeId, c.getUnitPrice());
                }
            }
        }

        java.util.List<com.cretas.aims.dto.inventory.FinanceCostBreakdown.MaterialCostLine> materials =
                new java.util.ArrayList<>();
        BigDecimal materialSum = BigDecimal.ZERO;
        boolean anyMaterial = !amtByType.isEmpty();

        for (java.util.Map.Entry<String, BigDecimal> entry : amtByType.entrySet()) {
            String typeId = entry.getKey();
            BigDecimal amt = entry.getValue().setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal qty = qtyByType.getOrDefault(typeId, BigDecimal.ZERO);
            materialSum = materialSum.add(amt);

            // 移动均价 = 金额 / 用量 (用量 > 0); 否则用首个 unitPrice 兜底
            BigDecimal unitPrice = qty.compareTo(BigDecimal.ZERO) > 0
                    ? amt.divide(qty, 4, java.math.RoundingMode.HALF_UP)
                    : fallbackUnitPrice.get(typeId);

            String name = typeId;
            String category = null;
            String unit = null;
            if (!"__UNCLASSIFIED__".equals(typeId) && rawMaterialTypeRepository != null) {
                try {
                    java.util.Optional<com.cretas.aims.entity.RawMaterialType> rt =
                            rawMaterialTypeRepository.findById(typeId);
                    if (rt.isPresent()) {
                        name = rt.get().getName() != null ? rt.get().getName() : typeId;
                        category = rt.get().getCategory();
                        unit = rt.get().getUnit();
                    }
                } catch (Exception e) {
                    log.warn("SP12 实际成本拆分: 查询物料 {} 失败: {}", typeId, e.getMessage());
                }
            } else if ("__UNCLASSIFIED__".equals(typeId)) {
                name = "未分类领料";
            }

            materials.add(com.cretas.aims.dto.inventory.FinanceCostBreakdown.MaterialCostLine.builder()
                    .materialTypeId("__UNCLASSIFIED__".equals(typeId) ? null : typeId)
                    .materialName(name)
                    .category(category)
                    .actualQuantity(qty)
                    .unit(unit)
                    .unitPrice(unitPrice)
                    .amount(amt)
                    .build());
        }

        BigDecimal materialCost = anyMaterial ? materialSum.setScale(2, java.math.RoundingMode.HALF_UP) : null;

        // ---- 合计 = 非 null 组分之和 ----
        BigDecimal total = null;
        if (materialCost != null || laborCost != null || overheadCost != null) {
            total = BigDecimal.ZERO;
            if (materialCost != null) total = total.add(materialCost);
            if (laborCost != null) total = total.add(laborCost);
            if (overheadCost != null) total = total.add(overheadCost);
        }

        // ---- 数据缺失提示 ----
        StringBuilder hint = new StringBuilder();
        if (batchCount == 0) {
            hint.append("订单已关联生产计划但尚无生产批次 (未排批). ");
        }
        if (materialCost == null && batchCount > 0) {
            hint.append("批次暂无领料消耗记录 (未领料或自动扣料未执行). ");
        }
        if (laborCost == null && batchCount > 0) {
            hint.append("批次暂无人工成本 (未报工或工时×时薪未配置). ");
        }
        String hintStr = hint.length() > 0 ? hint.toString().trim() : null;

        return com.cretas.aims.dto.inventory.FinanceCostBreakdown.ActualCostSplit.builder()
                .materialCost(materialCost)
                .laborCost(laborCost)
                .overheadCost(overheadCost)
                .totalActualCost(total)
                .batchCount(batchCount)
                .materials(materials)
                .dataSourceHint(hintStr)
                .build();
    }

    // ==================== 六扇门多段成本逐段分配 (两点报工成本拆分核心) ====================

    /**
     * 六扇门多段生产成本逐段分配。
     *
     * <p>回溯链: 销售订单 → ProductionPlan(sourceOrderId) → ProductionBatch(planId)
     * → SemiFinishedInventory(batchId, 每行=一段=一次两点报工转化)
     * → SemiFinishedInventoryTransaction(IN, reportId) → ProductionReport(laborCost/materialCost)。
     *
     * <p><b>每段算法</b>:
     * <ul>
     *   <li>段排序: 按 SemiFinishedInventory.processOrder 升序 (第一段=最上游原料转化, 末段=成品);</li>
     *   <li>段材料/人工: 取该段全部 IN 流水的 reportId, 反查 ProductionReport, Σ materialCost / Σ laborCost
     *       (诚实 null: 全部 report 该字段为 null → 段该组分 null, 不伪造 0);</li>
     *   <li>段制费: report 不分制费, 段级无来源 → 诚实 null (制费在批次级, 不强行摊到段);</li>
     *   <li>段小计 = materialCost + laborCost + overheadCost (非 null 之和, 仅本段新增, 不含上游累积);</li>
     *   <li>产出半成品 unitCost = SemiFinishedInventory.unitCost (#713 已逐段滚动累积的移动均价);</li>
     *   <li>累积成本 = SemiFinishedInventory.accumulatedCost (上游 + 本段全部投入);</li>
     *   <li>每盒贡献 = 段小计 / 最终成品盒数 (末段 producedQuantity, 未知则 null)。</li>
     * </ul>
     *
     * <p><b>两点报工人工诚实 null</b>: 两点报工 (领料入 + 产出出) 时人工常"登下一期"按期间分摊,
     * 报工当下 laborCost=null → 该段 laborCost 诚实 null + laborHint。绝不伪造。
     */
    @Override
    @Transactional(readOnly = true)
    public com.cretas.aims.dto.inventory.MultiStageCostBreakdown getOrderMultiStageCost(
            String factoryId, String orderId) {
        SalesOrder order = getSalesOrderById(factoryId, orderId);

        String productName = null;
        BigDecimal orderQty = null;
        try {
            order.getItems().size();  // 强制初始化 lazy
            if (order.getItems() != null && !order.getItems().isEmpty()) {
                SalesOrderItem first = order.getItems().get(0);
                productName = first.getProductName();
                orderQty = first.getQuantity();
            }
        } catch (Exception e) {
            log.warn("多段成本: 读取订单行失败 (orderId={}): {}", orderId, e.getMessage());
        }

        // repos 未注册 → 诚实 hint (不伪造)
        if (productionPlanRepository == null || productionBatchRepository == null
                || semiFinishedInventoryRepository == null) {
            return com.cretas.aims.dto.inventory.MultiStageCostBreakdown.builder()
                    .orderId(orderId)
                    .productName(productName)
                    .stages(java.util.Collections.emptyList())
                    .stageCount(0)
                    .dataSourceHint("多段成本模块未部署。")
                    .build();
        }

        java.util.List<com.cretas.aims.entity.ProductionPlan> plans;
        try {
            plans = productionPlanRepository.findByFactoryIdAndSourceOrderId(factoryId, orderId);
        } catch (Exception e) {
            log.warn("多段成本: 查询关联生产计划失败 (orderId={}): {}", orderId, e.getMessage());
            plans = java.util.Collections.emptyList();
        }

        if (plans == null || plans.isEmpty()) {
            return com.cretas.aims.dto.inventory.MultiStageCostBreakdown.builder()
                    .orderId(orderId)
                    .productName(productName)
                    .stages(java.util.Collections.emptyList())
                    .stageCount(0)
                    .dataSourceHint("订单尚未关联生产计划 (未投产), 暂无多段成本可拆分。")
                    .build();
        }

        java.util.Set<String> planIds = new java.util.HashSet<>();
        for (com.cretas.aims.entity.ProductionPlan p : plans) {
            if (p.getId() != null) {
                planIds.add(p.getId());
            }
        }

        java.util.List<com.cretas.aims.entity.ProductionBatch> batches =
                planIds.isEmpty()
                        ? java.util.Collections.emptyList()
                        : productionBatchRepository.findByFactoryIdAndProductionPlanIdIn(factoryId, planIds);

        // 收集所有批次下的半成品段 (每行=一段)
        java.util.List<com.cretas.aims.entity.SemiFinishedInventory> allStages = new java.util.ArrayList<>();
        for (com.cretas.aims.entity.ProductionBatch b : batches) {
            if (b.getId() == null) {
                continue;
            }
            try {
                allStages.addAll(
                        semiFinishedInventoryRepository.findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, b.getId()));
            } catch (Exception e) {
                log.warn("多段成本: 查询批次 {} 半成品段失败: {}", b.getId(), e.getMessage());
            }
        }

        if (allStages.isEmpty()) {
            return com.cretas.aims.dto.inventory.MultiStageCostBreakdown.builder()
                    .orderId(orderId)
                    .productName(productName)
                    .finalOutputBoxes(orderQty)
                    .stages(java.util.Collections.emptyList())
                    .stageCount(0)
                    .dataSourceHint("订单已投产但无半成品 WIP 段记录 (纯成品直产 / 未做两点报工)。"
                            + "可在订单财审实际成本拆分查看总额。")
                    .build();
        }

        // 段排序: processOrder 升序 (null 排末尾), 同序按 id 稳定
        allStages.sort(java.util.Comparator
                .comparing((com.cretas.aims.entity.SemiFinishedInventory s) ->
                        s.getProcessOrder() == null ? Integer.MAX_VALUE : s.getProcessOrder())
                .thenComparing(s -> s.getId() == null ? Long.MAX_VALUE : s.getId()));

        // 最终成品盒数 = 末段 producedQuantity (回退订单行数量)
        com.cretas.aims.entity.SemiFinishedInventory lastStage = allStages.get(allStages.size() - 1);
        BigDecimal finalBoxes = lastStage.getProducedQuantity();
        if (finalBoxes == null || finalBoxes.signum() <= 0) {
            finalBoxes = orderQty;
        }
        String finalUnit = lastStage.getUnit();

        // 产品名映射 (批量, 避免 N+1)
        java.util.Map<String, String> ptNameMap = new java.util.HashMap<>();
        if (productTypeRepository != null) {
            java.util.Set<String> ptIds = allStages.stream()
                    .map(com.cretas.aims.entity.SemiFinishedInventory::getProductTypeId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(java.util.stream.Collectors.toSet());
            if (!ptIds.isEmpty()) {
                try {
                    productTypeRepository.findByIdIn(ptIds)
                            .forEach(pt -> ptNameMap.put(pt.getId(), pt.getName()));
                } catch (Exception e) {
                    log.warn("多段成本: 查询产品名失败: {}", e.getMessage());
                }
            }
        }

        boolean anyLaborNull = false;
        BigDecimal chainSum = null;
        BigDecimal chainPerBox = null;
        java.util.List<com.cretas.aims.dto.inventory.MultiStageCostBreakdown.StageCost> stageCosts =
                new java.util.ArrayList<>();

        for (com.cretas.aims.entity.SemiFinishedInventory stage : allStages) {
            // 段内料/人工: 反查该段 IN 流水的 reportId → ProductionReport.materialCost/laborCost
            BigDecimal stageMaterial = null;
            BigDecimal stageLabor = null;
            if (semiFinishedInventoryTransactionRepository != null
                    && productionReportRepository != null && stage.getId() != null) {
                java.util.List<com.cretas.aims.entity.SemiFinishedInventoryTransaction> txns;
                try {
                    txns = semiFinishedInventoryTransactionRepository
                            .findBySemiFinishedIdOrderByCreatedAtAsc(stage.getId());
                } catch (Exception e) {
                    log.warn("多段成本: 查询段 {} 流水失败: {}", stage.getId(), e.getMessage());
                    txns = java.util.Collections.emptyList();
                }
                java.util.Set<Long> reportIds = new java.util.LinkedHashSet<>();
                for (com.cretas.aims.entity.SemiFinishedInventoryTransaction t : txns) {
                    if (com.cretas.aims.entity.SemiFinishedInventoryTransaction.TxnType.IN.equals(t.getTxnType())
                            && t.getReportId() != null) {
                        reportIds.add(t.getReportId());
                    }
                }
                if (!reportIds.isEmpty()) {
                    java.util.List<com.cretas.aims.entity.ProductionReport> reports;
                    try {
                        reports = productionReportRepository.findAllById(reportIds);
                    } catch (Exception e) {
                        log.warn("多段成本: 反查段 {} 报工失败: {}", stage.getId(), e.getMessage());
                        reports = java.util.Collections.emptyList();
                    }
                    BigDecimal matSum = BigDecimal.ZERO;
                    boolean anyMat = false;
                    BigDecimal labSum = BigDecimal.ZERO;
                    boolean anyLab = false;
                    for (com.cretas.aims.entity.ProductionReport r : reports) {
                        if (r.getMaterialCost() != null) {
                            matSum = matSum.add(r.getMaterialCost());
                            anyMat = true;
                        }
                        if (r.getLaborCost() != null) {
                            labSum = labSum.add(r.getLaborCost());
                            anyLab = true;
                        }
                    }
                    stageMaterial = anyMat ? matSum.setScale(2, java.math.RoundingMode.HALF_UP) : null;
                    stageLabor = anyLab ? labSum.setScale(2, java.math.RoundingMode.HALF_UP) : null;
                }
            }

            // 段制费: 报工不含制费, 段级无来源 → 诚实 null (不强行摊批次级 equipmentCost 到段)
            BigDecimal stageOverhead = null;

            // 段小计 = 本段新增 (料 + 人工 + 制费), 非 null 之和; 不含上游累积 (避免重复计数)
            BigDecimal subtotal = null;
            if (stageMaterial != null || stageLabor != null || stageOverhead != null) {
                subtotal = BigDecimal.ZERO;
                if (stageMaterial != null) subtotal = subtotal.add(stageMaterial);
                if (stageLabor != null) subtotal = subtotal.add(stageLabor);
                if (stageOverhead != null) subtotal = subtotal.add(stageOverhead);
            }

            // 上游领用累积: 累积成本 − 本段新增小计 (诚实, 仅当两者都有)
            BigDecimal upstreamCarried = null;
            if (stage.getAccumulatedCost() != null && subtotal != null) {
                BigDecimal diff = stage.getAccumulatedCost().subtract(subtotal);
                if (diff.signum() > 0) {
                    upstreamCarried = diff.setScale(2, java.math.RoundingMode.HALF_UP);
                }
            }

            // 每盒贡献 = 段小计 / 最终成品盒数
            BigDecimal perBox = null;
            if (subtotal != null && finalBoxes != null && finalBoxes.signum() > 0) {
                perBox = subtotal.divide(finalBoxes, 2, java.math.RoundingMode.HALF_UP);
            }

            // 人工诚实 null → hint
            String laborHint = null;
            String stageHint = null;
            if (stageLabor == null) {
                anyLaborNull = true;
                laborHint = "本段人工登下一期 (两点报工人工按期间统一分摊, 报工当下未结)。";
                stageHint = "人工未结 (登下一期)。";
            }
            if (stageMaterial == null && stageHint == null) {
                stageHint = "本段无领料消耗记录。";
            }

            // 链合计累加 (非 null 段)
            if (subtotal != null) {
                chainSum = (chainSum == null ? BigDecimal.ZERO : chainSum).add(subtotal);
            }
            if (perBox != null) {
                chainPerBox = (chainPerBox == null ? BigDecimal.ZERO : chainPerBox).add(perBox);
            }

            String ptName = ptNameMap.get(stage.getProductTypeId());
            stageCosts.add(com.cretas.aims.dto.inventory.MultiStageCostBreakdown.StageCost.builder()
                    .stageOrder(stage.getProcessOrder())
                    .semiCode(stage.getIntermediateBatchNo())
                    .stageName(ptName != null ? ptName : stage.getIntermediateBatchNo())
                    .productName(ptName)
                    .producedQuantity(stage.getProducedQuantity())
                    .producedUnit(stage.getUnit())
                    .upstreamConsumedQuantity(null)  // 上游领用量需 lineage 边精确归因, 当前以累积成本表达
                    .upstreamCarriedCost(upstreamCarried)
                    .materialCost(stageMaterial)
                    .laborCost(stageLabor)
                    .laborHint(laborHint)
                    .overheadCost(stageOverhead)
                    .stageSubtotal(subtotal)
                    .outputUnitCost(stage.getUnitCost())
                    .accumulatedCost(stage.getAccumulatedCost())
                    .contributionPerBox(perBox)
                    .materials(java.util.Collections.emptyList())  // 逐料归因到段需 report.materialBatchRefs, 后续增强
                    .stageHint(stageHint)
                    .build());
        }

        StringBuilder hint = new StringBuilder();
        if (anyLaborNull) {
            hint.append("部分段人工登下一期 (两点报工按期间统一分摊), 段成本暂仅含料/制费, 人工待结后回填。 ");
        }
        if (finalBoxes == null) {
            hint.append("末段未产出成品, 每盒口径暂不可算。 ");
        }
        String hintStr = hint.length() > 0 ? hint.toString().trim() : null;

        return com.cretas.aims.dto.inventory.MultiStageCostBreakdown.builder()
                .orderId(orderId)
                .productName(productName)
                .finalOutputBoxes(finalBoxes)
                .finalOutputUnit(finalUnit)
                .stages(stageCosts)
                .totalChainCost(chainSum)
                .totalCostPerBox(chainPerBox)
                .stageCount(stageCosts.size())
                .dataSourceHint(hintStr)
                .build();
    }

    /**
     * B3 售价趋势 — 财审辅助决策.
     *
     * <p>返回该产品最近 {@code limit} 笔成交记录的行级单价，
     * 供财务审核人员判断本单价格是否偏离历史水平。
     * 排除 DRAFT / CANCELLED 状态的订单。
     */
    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.cretas.aims.dto.inventory.SalesPriceTrendDTO> getProductPriceTrend(
            String factoryId, String productTypeId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        java.util.Set<SalesOrderStatus> excluded = java.util.Set.of(
                SalesOrderStatus.DRAFT, SalesOrderStatus.CANCELLED);
        var rows = salesOrderItemRepository.findRecentPriceByProduct(
                factoryId, productTypeId, excluded,
                org.springframework.data.domain.PageRequest.of(0, safeLimit));
        return rows.stream()
                .map(r -> com.cretas.aims.dto.inventory.SalesPriceTrendDTO.builder()
                        .orderNumber(r.getOrderNumber())
                        .orderDate(r.getOrderDate())
                        .unitPrice(r.getUnitPrice())
                        .quantity(r.getQuantity())
                        .unit(r.getUnit())
                        .build())
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "UPDATE", entityType = "SalesOrder",
              entityIdParam = "orderId")
    public SalesOrder updateSalesOrder(String factoryId, String orderId, UpdateSalesOrderRequest request) {
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        if (order.getStatus() != SalesOrderStatus.DRAFT) {
            throw new BusinessException(409, "只有草稿状态的订单可以编辑")
                    .withHint("请刷新订单列表查看最新状态");
        }
        // Optimistic lock: explicit compare (see CustomerServiceImpl for rationale)
        if (request.getVersion() != null && !request.getVersion().equals(order.getVersion())) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                SalesOrder.class, orderId);
        }

        // Canvas V2: DB-driven validation for UPDATE — pre-compute totalAmount if items changed
        BigDecimal updateTotal = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        boolean hasDupOnUpdate = false;
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            Set<String> seenIds = new HashSet<>();
            updateTotal = BigDecimal.ZERO;
            for (CreateSalesOrderRequest.SalesOrderItemDTO itemDTO : request.getItems()) {
                if (itemDTO.getProductTypeId() != null && !seenIds.add(itemDTO.getProductTypeId())) {
                    hasDupOnUpdate = true;
                }
                if (itemDTO.getQuantity() != null && itemDTO.getUnitPrice() != null) {
                    BigDecimal discountRate = itemDTO.getDiscountRate() != null ? itemDTO.getDiscountRate() : BigDecimal.ZERO;
                    BigDecimal line = itemDTO.getQuantity().multiply(itemDTO.getUnitPrice())
                            .multiply(BigDecimal.ONE.subtract(discountRate));
                    updateTotal = updateTotal.add(line);
                }
            }
        }
        runConfiguredValidation(factoryId, "UPDATE", Map.of(
                "itemCount", request.getItems() != null ? request.getItems().size() : 0,
                "totalAmount", updateTotal,
                "hasDuplicateProduct", hasDupOnUpdate,
                "currentStatus", order.getStatus().name(),
                // Bug fix 2026-04-24: 全局规则 ID=1 (factory_validation_rules) 用 #status,
                // 历史代码只传 currentStatus → #status null → null!='DRAFT' = true →
                // 任何 SO PUT 即使 status=DRAFT 也错误抛 "只有草稿状态". 双键兼容.
                "status", order.getStatus().name()));

        if (request.getSalesperson() != null) {
            resolveSalespersonField(order, request.getSalesperson(), factoryId);
        }
        if (request.getDeliveryAddress() != null) {
            order.setDeliveryAddress(request.getDeliveryAddress());
        }
        if (request.getRemark() != null) {
            order.setRemark(request.getRemark());
        }
        if (request.getShippingIncluded() != null) {
            order.setShippingIncluded(request.getShippingIncluded());
        }
        if (request.getShippingFee() != null) {
            order.setShippingFee(request.getShippingFee());
        }
        if (request.getExtraFees() != null) {
            order.setExtraFees(request.getExtraFees());
        }
        if (request.getRequiredDeliveryDate() != null) {
            order.setRequiredDeliveryDate(request.getRequiredDeliveryDate());
        }

        // 行项目更新 + 总金额重算
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            // 删除旧行项
            List<SalesOrderItem> oldItems = salesOrderItemRepository.findBySalesOrderId(order.getId());
            salesOrderItemRepository.deleteAll(oldItems);

            // 创建新行项 + 重算总金额
            BigDecimal totalAmount = BigDecimal.ZERO;
            List<SalesOrderItem> newItems = new ArrayList<>();
            for (CreateSalesOrderRequest.SalesOrderItemDTO itemDTO : request.getItems()) {
                SalesOrderItem item = new SalesOrderItem();
                item.setSalesOrderId(order.getId());
                item.setProductTypeId(itemDTO.getProductTypeId());
                String productName = itemDTO.getProductName();
                if (productName == null || productName.isBlank()) {
                    productName = productTypeRepository.findById(itemDTO.getProductTypeId())
                            .map(ProductType::getName).orElse(null);
                }
                item.setProductName(productName);
                item.setQuantity(itemDTO.getQuantity());
                item.setUnit(itemDTO.getUnit());
                item.setUnitPrice(itemDTO.getUnitPrice());
                item.setDiscountRate(itemDTO.getDiscountRate() != null ? itemDTO.getDiscountRate() : BigDecimal.ZERO);
                // SP4 updateSalesOrder: 同 createSalesOrder 三层 default 链 — item 显式 > SO.defaultTaxRate > 0
                BigDecimal effectiveTaxRateUpdate = itemDTO.getTaxRate();
                if (effectiveTaxRateUpdate == null) {
                    effectiveTaxRateUpdate = order.getDefaultTaxRate() != null ? order.getDefaultTaxRate() : BigDecimal.ZERO;
                }
                item.setTaxRate(effectiveTaxRateUpdate);
                item.setRemark(itemDTO.getRemark());
                item.setSpecification(itemDTO.getSpecification());
                item.setBoxQuantity(itemDTO.getBoxQuantity());
                newItems.add(item);
                // E-2 空价草稿: unitPrice null → getLineAmount() null, 该行贡献 0 (同 createSalesOrder 防 NPE)
                BigDecimal lineAmount = item.getLineAmount();
                totalAmount = totalAmount.add(lineAmount != null ? lineAmount : BigDecimal.ZERO);
            }
            salesOrderItemRepository.saveAll(newItems);
            order.setTotalAmount(totalAmount);
            // SP4: 重算 tax_amount (与 createSalesOrder 逻辑对称, 减法兜底)
            BigDecimal taxAmountSumUpdate = newItems.stream()
                    .map(it -> {
                        BigDecimal la = it.getLineAmount();
                        BigDecimal lawt = it.getLineAmountWithTax();
                        if (la == null || lawt == null) return BigDecimal.ZERO;
                        return lawt.subtract(la);
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            order.setTaxAmount(taxAmountSumUpdate);
        }

        // Canvas V3: Update dynamic custom fields via DynamicFieldService
        if (dynamicFieldService != null && request.getCustomFields() != null && !request.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "sales_order", order.getId(), request.getCustomFields());
                log.info("Updated {} custom fields for sales order {}",
                        request.getCustomFields().size(), order.getId());
            } catch (Exception e) {
                log.warn("Failed to update custom fields for sales order {}: {}", order.getId(), e.getMessage());
            }
        }

        log.info("更新销售订单: orderId={}, orderNumber={}", orderId, order.getOrderNumber());
        return salesOrderRepository.save(order);
    }

    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "CANCEL", entityType = "SalesOrder",
              entityIdParam = "orderId")
    public SalesOrder cancelOrder(String factoryId, String orderId) {
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        // R39 BUG-8 fix: was only blocking COMPLETED → FINANCE_APPROVED+/PROCESSING/SHIPPED/CANCELLED
        // could be cancelled, breaking AR + production_plan invariants. Use whitelist.
        if (!com.cretas.aims.domain.OrderUsageWhitelists.SO_CANCELLABLE.contains(order.getStatus())) {
            throw new BusinessException(409,
                    "当前订单状态(" + order.getStatus().getDisplayName() + ")不允许取消。"
                  + "财务批准后请通过驳回/退款流程处理")
                    .withHint("请刷新订单列表查看最新状态");
        }
        order.setStatus(SalesOrderStatus.CANCELLED);
        log.info("取消销售订单: orderId={}, orderNumber={}", orderId, order.getOrderNumber());
        return salesOrderRepository.save(order);
    }

    /**
     * 复制销售订单 — 见 {@link SalesService#copySalesOrder} 文档.
     *
     * <p>实现说明:
     * <ul>
     *   <li>用 {@link #getSalesOrderById} 校验源订单 + factory 隔离 (404 / 403).</li>
     *   <li>新订单 status = DRAFT, createdBy = 当前用户, orderDate = 今天.</li>
     *   <li>items 用 fresh SalesOrderItem 实例 (Hibernate 不复用 id),
     *       deliveredQuantity/lockedQty/reservedQty 重置为 0.</li>
     *   <li>orderNumber 复用 {@link #generateSalesOrderNumber} (含 advisory lock 防 race).</li>
     *   <li>extraFees 是 JSON List — 浅 copy ArrayList 即可 (item 不可变).</li>
     * </ul>
     */
    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "COPY", entityType = "SalesOrder",
              entityIdParam = "sourceOrderId")
    public SalesOrder copySalesOrder(String factoryId, String sourceOrderId, Long userId) {
        SalesOrder source = getSalesOrderById(factoryId, sourceOrderId);
        // 强制初始化 items (lazy collection)
        org.hibernate.Hibernate.initialize(source.getItems());

        String newOrderNumber = generateSalesOrderNumber(factoryId);

        SalesOrder newOrder = new SalesOrder();
        newOrder.setFactoryId(factoryId);
        newOrder.setOrderNumber(newOrderNumber);
        // 复制业务字段
        newOrder.setCustomerId(source.getCustomerId());
        newOrder.setOrderDate(LocalDate.now());
        newOrder.setRequiredDeliveryDate(source.getRequiredDeliveryDate());
        newOrder.setDeliveryAddress(source.getDeliveryAddress());
        newOrder.setDiscountAmount(source.getDiscountAmount() != null
                ? source.getDiscountAmount() : BigDecimal.ZERO);
        newOrder.setRemark(source.getRemark());
        newOrder.setSalesperson(source.getSalesperson());
        newOrder.setSalespersonId(source.getSalespersonId());
        newOrder.setShippingIncluded(source.getShippingIncluded());
        newOrder.setShippingFee(source.getShippingFee());
        // extraFees 是 List<ExtraFeeItem>; 浅 copy (item 不变, 由 Hibernate JSON 再序列化)
        if (source.getExtraFees() != null) {
            newOrder.setExtraFees(new ArrayList<>(source.getExtraFees()));
        }
        newOrder.setQuoteId(source.getQuoteId());
        newOrder.setDefaultTaxRate(source.getDefaultTaxRate());
        newOrder.setDefaultInvoiceType(source.getDefaultInvoiceType());
        newOrder.setBoxQuantity(source.getBoxQuantity());
        // 重置状态字段
        newOrder.setStatus(SalesOrderStatus.DRAFT);
        newOrder.setCreatedBy(userId);
        // 不复制 confirmedAt/finance*/estimatedCost/estimatedProfit/actualShippedAmount
        // /invoiceStatus/invoicedAmount/paidAmount/settlementFlag/transportPlanStatus
        // /deliveryReminderDate/contractFile*/vflag/markerColor (默认值已 OK)

        newOrder = salesOrderRepository.save(newOrder);

        // 复制行项目 (fresh instances, 重置 delivered/locked/reserved)
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<SalesOrderItem> newItems = new ArrayList<>();
        for (SalesOrderItem srcItem : source.getItems()) {
            SalesOrderItem newItem = new SalesOrderItem();
            newItem.setSalesOrderId(newOrder.getId());
            newItem.setProductTypeId(srcItem.getProductTypeId());
            newItem.setProductName(srcItem.getProductName());
            newItem.setQuantity(srcItem.getQuantity());
            newItem.setUnit(srcItem.getUnit());
            newItem.setUnitPrice(srcItem.getUnitPrice());
            newItem.setDiscountRate(srcItem.getDiscountRate() != null
                    ? srcItem.getDiscountRate() : BigDecimal.ZERO);
            newItem.setCostUnitPrice(srcItem.getCostUnitPrice());
            newItem.setTaxRate(srcItem.getTaxRate());
            newItem.setRemark(srcItem.getRemark());
            newItem.setSpecification(srcItem.getSpecification());
            newItem.setBoxQuantity(srcItem.getBoxQuantity());
            newItem.setSourceWarehouseCode(srcItem.getSourceWarehouseCode());
            // deliveredQuantity / lockedQty / reservedQty 默认 ZERO — 不复制状态量
            newItems.add(newItem);

            BigDecimal lineAmount = newItem.getLineAmount();
            if (lineAmount != null) {
                totalAmount = totalAmount.add(lineAmount);
            }
        }
        salesOrderItemRepository.saveAll(newItems);
        newOrder.setTotalAmount(totalAmount);
        newOrder = salesOrderRepository.save(newOrder);

        log.info("复制销售订单: source={}({}) → new={}({}), items={}",
                sourceOrderId, source.getOrderNumber(),
                newOrder.getId(), newOrderNumber, newItems.size());
        return newOrder;
    }

    @Override
    @Transactional
    @Loggable(module = "SALES_ORDER", action = "DELETE", entityType = "SalesOrder",
              entityIdParam = "orderId")
    public void deleteDraft(String factoryId, String orderId, Long userId) {
        // getSalesOrderById 已含 404 + 403 跨工厂隔离
        SalesOrder order = getSalesOrderById(factoryId, orderId);
        // 状态机约束: 仅 DRAFT 可删
        if (order.getStatus() != SalesOrderStatus.DRAFT) {
            throw new BusinessException(409,
                    "仅 DRAFT 草稿状态可删除 (当前状态: " + order.getStatus().getDisplayName() + ")")
                    .withHint("如需取消已确认的订单, 请使用取消操作");
        }
        // MVP: 有 sales:read_write 权限的工厂内管理员均可删除草稿 (无 creator-only 限制)
        // 软删除 via BaseEntity @SQLDelete (UPDATE deleted_at = NOW())
        salesOrderRepository.delete(order);
        log.info("删除销售订单草稿: orderId={}, orderNumber={}, operatorId={}",
                orderId, order.getOrderNumber(), userId);
    }

    /**
     * Round 14: Compute all aggregate formulas configured for a sales order.
     * Returns formula results keyed by formula_code (e.g., "tax_group_sum").
     * The "杀手锏 G1" killer feature — 39 factories have tax_group_sum configured,
     * but it was never evaluated at runtime until now.
     */
    public Map<String, Object> computeOrderFormulas(String factoryId, String orderId) {
        Map<String, Object> results = new java.util.LinkedHashMap<>();
        if (formulaEngine == null) return results;

        // Query all factory_formulas for this factory + sales_order module
        var formulas = formulaEngine.listFormulas(factoryId, "sales_order");
        if (formulas == null || formulas.isEmpty()) return results;

        Map<String, Object> context = Map.of(
                "factoryId", factoryId,
                "parentId", orderId);

        for (var formula : formulas) {
            try {
                Object result = formulaEngine.evaluateAny(factoryId, "sales_order", formula.getFormulaCode(), context);
                if (result != null) {
                    results.put(formula.getFormulaCode(), result);
                }
            } catch (Exception e) {
                log.warn("Formula {} evaluation failed for order {}: {}", formula.getFormulaCode(), orderId, e.getMessage());
            }
        }
        return results;
    }

    // ==================== 发货/出库 ====================

    @Override
    @Transactional
    public SalesDeliveryRecord createDeliveryRecord(String factoryId, CreateDeliveryRequest request, Long userId) {
        // Round 9 Fix (R8-α Gap #1 template): Canvas validation rules now fire for
        // delivery creation. Customer-configured rules like "冷链商品必填运输温度" /
        // "发货金额 > 5万自动预警" / "物流公司必填" take effect here. Context
        // exposes {customerId, salesOrderId, itemCount, totalAmount, logisticsCompany,
        // deliveryAddress} so SpEL expressions can reason about the delivery.
        java.math.BigDecimal prelimTotal = request.getItems() == null ? java.math.BigDecimal.ZERO :
                request.getItems().stream()
                        .filter(i -> i.getUnitPrice() != null && i.getDeliveredQuantity() != null)
                        .map(i -> i.getDeliveredQuantity().multiply(i.getUnitPrice()))
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.util.Map<String, Object> validationCtx = new java.util.HashMap<>();
        validationCtx.put("customerId", request.getCustomerId());
        validationCtx.put("salesOrderId", request.getSalesOrderId());
        validationCtx.put("itemCount", request.getItems() == null ? 0 : request.getItems().size());
        validationCtx.put("totalAmount", prelimTotal);
        validationCtx.put("logisticsCompany", request.getLogisticsCompany());
        validationCtx.put("deliveryAddress", request.getDeliveryAddress());
        if (request.getCustomFields() != null) {
            // Merge customFields into validation context so rules can reference them
            // via #cf_<fieldCode> (matching the SalesOrder/MaterialBatch pattern).
            request.getCustomFields().forEach((k, v) -> validationCtx.put("cf_" + k, v));
        }
        runConfiguredValidation(factoryId, "delivery", "CREATE", validationCtx);

        // 验证客户
        customerRepository.findByIdAndFactoryId(request.getCustomerId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("客户不存在或不属于当前组织"));

        // R23 audit C3: was inline {FINANCE_APPROVED, CONFIRMED, PROCESSING, PARTIAL_DELIVERED}
        // — distinct from SO_SHIPPABLE because delivery here allows CONFIRMED (e.g.,
        // consignment / advance shipment). Centralized as SO_DELIVERABLE.
        if (request.getSalesOrderId() != null && !request.getSalesOrderId().isEmpty()) {
            SalesOrder order = getSalesOrderById(factoryId, request.getSalesOrderId());
            if (order.getStatus() == null
                    || !com.cretas.aims.domain.OrderUsageWhitelists.SO_DELIVERABLE.contains(order.getStatus())) {
                throw new BusinessException(409, "只有财务已批准/已确认/处理中/部分发货状态的订单可以创建发货单")
                        .withHint("请刷新订单列表查看最新状态");
            }

            // Issue #739 idempotency: if an in-progress draft already exists for this SO,
            // return it instead of creating a duplicate (六扇门 客户手测 2026-05-17: SO-20260511-0001
            // 有 2 个草稿 DLV — DLV-20260511-0337 / DLV-20260517-9640).
            List<SalesDeliveryRecord> existing = deliveryRecordRepository.findInProgressBySalesOrderId(
                    factoryId, request.getSalesOrderId(),
                    List.of(SalesDeliveryStatus.DRAFT,
                            SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM,
                            SalesDeliveryStatus.PICKED));
            if (!existing.isEmpty()) {
                SalesDeliveryRecord prev = existing.get(0);
                log.info("发货单幂等命中: factoryId={}, salesOrderId={}, existingDeliveryNumber={}, status={}",
                        factoryId, request.getSalesOrderId(), prev.getDeliveryNumber(), prev.getStatus());
                throw new BusinessException(409,
                        "该销售订单已有草稿发货单 " + prev.getDeliveryNumber() + " (" + prev.getStatus().getDisplayName() + ")")
                        .withHint("请查看现有发货单, 不要重复创建. 如需多次发货, 请先完成或取消现有草稿.")
                        .withHintTarget("发货记录 Tab");
            }
        }

        String deliveryNumber = generateDeliveryNumber(factoryId);

        SalesDeliveryRecord record = new SalesDeliveryRecord();
        record.setFactoryId(factoryId);
        record.setDeliveryNumber(deliveryNumber);
        record.setSalesOrderId(request.getSalesOrderId());
        record.setCustomerId(request.getCustomerId());
        record.setDeliveryDate(request.getDeliveryDate());
        record.setDeliveryAddress(request.getDeliveryAddress());
        record.setLogisticsCompany(request.getLogisticsCompany());
        record.setTrackingNumber(request.getTrackingNumber());
        // Issue #740: 销售创建后直接进入 PENDING_WAREHOUSE_CONFIRM, 等仓库确认
        // (DRAFT 留给无 salesOrderId 的临时草稿, e.g. 内部样品 / 营销赠品).
        record.setStatus(request.getSalesOrderId() != null && !request.getSalesOrderId().isEmpty()
                ? SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM
                : SalesDeliveryStatus.DRAFT);
        record.setShippedBy(userId);
        record.setRemark(request.getRemark());

        record = deliveryRecordRepository.save(record);

        // 创建发货行项目
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CreateDeliveryRequest.DeliveryItemDTO itemDTO : request.getItems()) {
            SalesDeliveryItem item = new SalesDeliveryItem();
            item.setDeliveryRecordId(record.getId());
            item.setProductTypeId(itemDTO.getProductTypeId());
            item.setProductName(itemDTO.getProductName());
            item.setDeliveredQuantity(itemDTO.getDeliveredQuantity());
            item.setUnit(itemDTO.getUnit());
            item.setUnitPrice(itemDTO.getUnitPrice());
            // T4-D5 (issue #553): propagate source warehouse code from the request DTO.
            // Inventory allocation logic does NOT yet filter by this column — that's
            // deferred follow-up. This line is the data-contract propagation only.
            item.setSourceWarehouseCode(itemDTO.getSourceWarehouseCode());
            item.setRemark(itemDTO.getRemark());
            record.getItems().add(item);

            if (itemDTO.getUnitPrice() != null) {
                totalAmount = totalAmount.add(itemDTO.getDeliveredQuantity().multiply(itemDTO.getUnitPrice()));
            }
        }

        record.setTotalAmount(totalAmount);
        record = deliveryRecordRepository.save(record);

        // Round 9 Fix (R8-α Gap #1 template): persist Canvas V3 dynamic field values
        // via DynamicFieldService. Customer-configured fields (运输温度, 司机姓名,
        // 车牌号, 温度监控仪器型号 etc.) now land in the cf_* columns of
        // sales_delivery_records. Previously the DTO had no customFields slot so
        // frontend Canvas form submissions silently dropped the values.
        if (dynamicFieldService != null && request.getCustomFields() != null && !request.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "delivery", record.getId(), request.getCustomFields());
            } catch (Exception e) {
                log.warn("Canvas dynamic fields save failed for delivery {}: {}", record.getId(), e.getMessage());
            }
        }

        // Round 9 Fix (R8-α Gap #1 template): publish event so Canvas-configured
        // trigger chains on the `delivery` module can fire (e.g. 物流推送 / 冷链启动 /
        // WMS 同步). TriggerChainExecutor.HANDLED_EVENTS has been extended to recognize
        // SalesDeliveryCreatedEvent.
        if (applicationEventPublisher != null) {
            try {
                applicationEventPublisher.publishEvent(new com.cretas.aims.event.SalesDeliveryCreatedEvent(
                        this, factoryId, record.getId(), record.getDeliveryNumber(),
                        record.getCustomerId(), record.getSalesOrderId(),
                        record.getDeliveryDate(), record.getTotalAmount()));
            } catch (Exception e) {
                log.warn("Publish SalesDeliveryCreatedEvent failed: {}", e.getMessage());
            }
        }

        log.info("创建发货单: factoryId={}, deliveryNumber={}, items={}, customFields={}",
                factoryId, deliveryNumber, request.getItems().size(),
                request.getCustomFields() == null ? 0 : request.getCustomFields().size());
        return record;
    }

    @Override
    @Transactional
    public SalesDeliveryRecord shipDelivery(String factoryId, String deliveryId, Long userId) {
        SalesDeliveryRecord record = getDeliveryRecordById(factoryId, deliveryId);
        if (record.getStatus() != SalesDeliveryStatus.DRAFT
                && record.getStatus() != SalesDeliveryStatus.PICKED
                && record.getStatus() != SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM) {
            throw new BusinessException(409, "只有草稿/待仓库确认/已拣货状态的发货单可以发货")
                    .withHint("请刷新发货单列表查看最新状态");
        }

        // P0-13 强制批次分配校验：发货行必须已完成批次分配才能发货
        if (batchAllocationService != null) {
            for (SalesDeliveryItem item : record.getItems()) {
                String itemIdStr = String.valueOf(item.getId());
                if (!batchAllocationService.isFullyAllocated(factoryId, itemIdStr)) {
                    // R49 BUG-22 fix: 之前 productName null 时 message 显示 "产品：null".
                    // 现 fallback 到 productTypeId, 再 fallback 到 itemIdStr.
                    String productLabel = item.getProductName() != null
                            ? item.getProductName()
                            : (item.getProductTypeId() != null ? item.getProductTypeId() : "未知产品");
                    throw new BusinessException(409, "发货行 " + itemIdStr
                            + "（产品：" + productLabel + "）未完成批次分配，无法确认发货")
                            .withHint("请在「发货记录」Tab 点击「分配批次」按钮,完成所有行的批次分配后再确认发货")
                            .withHintTarget("发货记录 Tab");
                }
            }
        }

        // FIFO 扣减成品库存
        for (SalesDeliveryItem item : record.getItems()) {
            deductFinishedGoodsInventory(factoryId, item);
        }

        record.setStatus(SalesDeliveryStatus.SHIPPED);

        // 更新销售订单状态
        if (record.getSalesOrderId() != null) {
            updateOrderDeliveryStatus(record);
        }

        record = deliveryRecordRepository.save(record);
        log.info("发货确认: deliveryId={}, deliveryNumber={}", deliveryId, record.getDeliveryNumber());

        // 自动创建应收账款（销售发货 → AR_INVOICE）
        if (record.getSalesOrderId() != null) {
            try {
                SalesOrder order = salesOrderRepository.findById(record.getSalesOrderId()).orElse(null);
                if (order != null && order.getCustomerId() != null && order.getTotalAmount() != null) {
                    boolean arExists = arApTransactionRepository != null
                            && arApTransactionRepository.existsByFactoryIdAndSalesOrderIdAndTransactionType(
                                    factoryId, order.getId(), ArApTransactionType.AR_INVOICE);
                    if (arExists) {
                        log.info("应收自动挂账跳过: salesOrderId={} 已存在 AR_INVOICE", order.getId());
                    } else {
                        arApService.recordReceivable(factoryId, order.getCustomerId(), order.getId(),
                            order.getTotalAmount(), LocalDate.now().plusDays(30), userId,
                            "销售发货自动挂账-" + record.getDeliveryNumber());
                    log.info("自动创建应收: orderId={}, amount={}", order.getId(), order.getTotalAmount());
                    }
                }
            } catch (BusinessException e) {
                log.warn("应收自动挂账跳过(可能已存在): {}", e.getMessage());
            } catch (Exception e) {
                log.error("应收自动挂账失败: deliveryId={}", deliveryId, e);
            }
        }

        return record;
    }

    @Override
    @Transactional
    public SalesDeliveryRecord confirmDelivered(String factoryId, String deliveryId) {
        SalesDeliveryRecord record = getDeliveryRecordById(factoryId, deliveryId);
        if (record.getStatus() != SalesDeliveryStatus.SHIPPED) {
            throw new BusinessException(409, "只有已发货状态的发货单可以确认签收")
                    .withHint("请刷新发货单列表查看最新状态");
        }
        record.setStatus(SalesDeliveryStatus.DELIVERED);
        log.info("签收确认: deliveryId={}, deliveryNumber={}", deliveryId, record.getDeliveryNumber());
        return deliveryRecordRepository.save(record);
    }

    @Override
    public SalesDeliveryRecord getDeliveryRecordById(String factoryId, String deliveryId) {
        SalesDeliveryRecord record = deliveryRecordRepository.findById(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("发货单不存在"));
        if (!record.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权访问该发货单")
                    .withHint("当前发货单不属于该工厂, 无法访问");
        }
        return record;
    }

    @Override
    public PageResponse<SalesDeliveryRecord> getDeliveryRecords(String factoryId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<SalesDeliveryRecord> result = deliveryRecordRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageRequest);
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    public List<SalesDeliveryRecord> getDeliveryRecordsByOrder(String salesOrderId) {
        return deliveryRecordRepository.findBySalesOrderId(salesOrderId);
    }

    // ==================== Issue #740 仓库侧 confirm 流程 ====================

    @Override
    public PageResponse<SalesDeliveryRecord> getPendingWarehouseDeliveries(String factoryId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<SalesDeliveryRecord> result = deliveryRecordRepository.findByFactoryIdAndStatusIn(
                factoryId,
                List.of(SalesDeliveryStatus.DRAFT,
                        SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM,
                        SalesDeliveryStatus.PICKED),
                pageRequest);
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    /**
     * Issue #740: warehouse-side confirm. Allows override of 实际发货数量 per-line
     * (车装不下 / 现场少装 等场景), then 扣库存 + 转 SHIPPED + auto AR.
     *
     * <p>actualQuantities map: key = deliveryItemId (String), value = actual qty.
     * Items not in map keep original deliveredQuantity (sales-planned).
     *
     * <p>客户原话 (六扇门 May10 L825-830):
     *   "确认发货, 他肯定去看一下库存, 实际发了多少, 他肯定手动要填一下.
     *    比如说我可能计划发发一版, 那但是可能车或者是没装下什么的, 各种各样的因素,
     *    然后他可能就发了八十"
     */
    @Override
    @Transactional
    public SalesDeliveryRecord warehouseConfirmDelivery(String factoryId, String deliveryId,
                                                        java.util.Map<String, java.math.BigDecimal> actualQuantities,
                                                        Long userId) {
        SalesDeliveryRecord record = getDeliveryRecordById(factoryId, deliveryId);

        if (record.getStatus() != SalesDeliveryStatus.DRAFT
                && record.getStatus() != SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM
                && record.getStatus() != SalesDeliveryStatus.PICKED) {
            throw new BusinessException(409, "只有 草稿/待仓库确认/已拣货 状态的发货单可以确认")
                    .withHint("请刷新发货单列表查看最新状态");
        }

        // 应用 actualQuantities 覆盖 — 仅覆盖, 不删行
        // NOTE: 若 actual < planned, 之前的批次分配 (P0-13 enforce 总量==deliveredQuantity) 会
        // 不再匹配 → shipDelivery 会 reject. 仓库需先到 "发货记录 → 分配批次" 重新分配再 confirm.
        // 此 spec 妥协: warehouse confirm 是 happy-path (车装下了就发完); actual<planned 需要
        // 走"重新分配批次"的 explicit flow. 后续 follow-up: confirm UI 同时支持调整分配.
        boolean qtyOverridden = false;
        if (actualQuantities != null && !actualQuantities.isEmpty()) {
            for (SalesDeliveryItem item : record.getItems()) {
                String itemIdKey = item.getId() == null ? null : String.valueOf(item.getId());
                if (itemIdKey == null) continue;
                java.math.BigDecimal actual = actualQuantities.get(itemIdKey);
                if (actual != null) {
                    if (actual.signum() < 0) {
                        throw new BusinessException(400, "发货行 " + itemIdKey + " 的实际发货数量不能为负")
                                .withHint("请检查输入");
                    }
                    if (item.getDeliveredQuantity() != null
                            && actual.compareTo(item.getDeliveredQuantity()) != 0) {
                        log.info("仓库 confirm 调整数量: deliveryId={}, itemId={}, plannedQty={} → actualQty={}",
                                deliveryId, itemIdKey, item.getDeliveredQuantity(), actual);
                        item.setDeliveredQuantity(actual);
                        qtyOverridden = true;
                    }
                }
            }
            if (qtyOverridden) {
                // 重新计算 totalAmount
                BigDecimal total = BigDecimal.ZERO;
                for (SalesDeliveryItem item : record.getItems()) {
                    if (item.getUnitPrice() != null && item.getDeliveredQuantity() != null) {
                        total = total.add(item.getDeliveredQuantity().multiply(item.getUnitPrice()));
                    }
                }
                record.setTotalAmount(total);
                record = deliveryRecordRepository.save(record);
            }
        }

        // 复用 shipDelivery 逻辑 — 含批次分配校验 + 扣库存 + 自动 AR
        return shipDelivery(factoryId, deliveryId, userId);
    }

    @Override
    @Transactional
    public SalesDeliveryRecord uploadDeliverySignature(String factoryId, String deliveryId,
                                                        List<String> photoUrls, String signedByName, String remark) {
        SalesDeliveryRecord record = getDeliveryRecordById(factoryId, deliveryId);
        // 将 photoUrls 存为简单 JSON 数组字符串 (避免注入 ObjectMapper)
        String json = "[]";
        if (photoUrls != null && !photoUrls.isEmpty()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < photoUrls.size(); i++) {
                if (i > 0) sb.append(",");
                String url = photoUrls.get(i) == null ? "" : photoUrls.get(i).replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append("\"").append(url).append("\"");
            }
            sb.append("]");
            json = sb.toString();
        }
        record.setSignaturePhotoUrls(json);
        record.setSignedByName(signedByName);
        record.setSignedAt(LocalDateTime.now());
        record.setSignatureRemark(remark);
        log.info("上传签收凭证: deliveryId={}, 照片数={}, 签收人={}",
                deliveryId, photoUrls == null ? 0 : photoUrls.size(), signedByName);
        return deliveryRecordRepository.save(record);
    }

    // ==================== 成品库存 ====================

    @Override
    public PageResponse<FinishedGoodsBatch> getFinishedGoodsBatches(String factoryId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<FinishedGoodsBatch> result = finishedGoodsBatchRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageRequest);
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    public FinishedGoodsBatch getFinishedGoodsBatchById(String factoryId, String batchId) {
        // Issue #786 follow-up to #761: single-item lookup (replaces FE list-filter fallback).
        // Mirror getSalesOrderById factory-isolation pattern (line 234-242).
        FinishedGoodsBatch batch = finishedGoodsBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("成品批次不存在"));
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权访问该成品批次")
                    .withHint("当前成品批次不属于该工厂, 无法访问");
        }
        return batch;
    }

    @Override
    public List<FinishedGoodsBatch> getAvailableBatches(String factoryId, String productTypeId) {
        // 老路径 — 维持 D5 WH-LOG 默认。委托给三参版本传 null sourceWarehouseCode 走 fallback.
        return getAvailableBatches(factoryId, productTypeId, null);
    }

    @Override
    public List<FinishedGoodsBatch> getAvailableBatches(String factoryId, String productTypeId,
                                                        String sourceWarehouseCode) {
        // T4-D5 #572 Phase B-1: honor caller-supplied sourceWarehouseCode (PR #547/#564 chain).
        // null/blank → WH-LOG fallback (D5 默认行为, Phase A 一致).
        String warehouseId = (sourceWarehouseCode != null && !sourceWarehouseCode.isBlank())
                ? warehouseResolver.resolveId(factoryId, sourceWarehouseCode)
                : warehouseResolver.resolveLogisticsId(factoryId);
        return finishedGoodsBatchRepository
                .findAvailableBatchesByWarehouse(factoryId, productTypeId, warehouseId);
    }

    @Override
    @Transactional
    public FinishedGoodsBatch createFinishedGoodsBatch(String factoryId, FinishedGoodsBatch batch, Long userId) {
        return doCreateFinishedGoodsBatch(factoryId, batch, userId, null);
    }

    // ==================== T126 Phase 1: 成品库存 Web 闭环 ====================

    /**
     * T126 Phase 1 (F8) — 期初入库路径。委托共享 worker, sourceType="OPENING"。
     * 不再 delegate-then-republish (旧实现会触发 trigger chain 两次)。
     */
    @Override
    @Transactional
    public FinishedGoodsBatch createOpeningFinishedGoodsBatch(String factoryId, FinishedGoodsBatch batch, Long userId) {
        return doCreateFinishedGoodsBatch(factoryId, batch, userId, "OPENING");
    }

    /**
     * 共享创建 worker。发布 <b>单个</b> FinishedGoodsCreatedEvent 携带 sourceType
     * (null=普通直接创建, "OPENING"=期初入库), 使 trigger chain 恰好触发一次且可过滤期初条目 (T126 F8)。
     * 运行在 caller 的 @Transactional 内 (private 自调用不开新事务)。
     */
    private FinishedGoodsBatch doCreateFinishedGoodsBatch(String factoryId, FinishedGoodsBatch batch, Long userId, String sourceType) {
        // Round 11 T3: Canvas Integration Template hook 1 — DB-driven validation
        if (validationRuleEvaluator != null) {
            try {
                validationRuleEvaluator.validate(factoryId, "finished_goods", "CREATE",
                        java.util.Map.of(
                            "productTypeId", batch.getProductTypeId() != null ? batch.getProductTypeId() : "",
                            "producedQuantity", batch.getProducedQuantity() != null ? batch.getProducedQuantity() : java.math.BigDecimal.ZERO));
            } catch (com.cretas.aims.exception.BusinessException e) { throw e; }
            catch (Exception e) { log.warn("Canvas validation non-blocking: {}", e.getMessage()); }
        }

        batch.setFactoryId(factoryId);
        batch.setCreatedBy(userId);
        if (batch.getBatchNumber() == null) {
            batch.setBatchNumber(generateFinishedGoodsBatchNumber(factoryId));
        }
        // D1: 成品默认 WH-WKS (per PR #310 spec §3.3) — 直接创建路径 (manual / rework) 也用 WH-WKS.
        // 若 caller 显式设置 warehouseId, 不覆盖.
        if (batch.getWarehouseId() == null) {
            batch.setWarehouseId(warehouseResolver.resolveWorkshopId(factoryId));
        }
        batch = finishedGoodsBatchRepository.save(batch);

        // Round 11 T3 — close the FinishedGoodsCreatedEvent gap. Every finished-goods creation
        // path (manual / rework / 期初) is observable to trigger chains. T126 F8: single event
        // carrying sourceType so chains fire once and can filter opening entries.
        try {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.FinishedGoodsCreatedEvent(
                    this,
                    batch.getFactoryId(),
                    null,  // sourceOrderId: null for direct creation (no production plan link)
                    batch.getProductTypeId(),
                    batch.getProducedQuantity(),
                    batch.getId(),
                    sourceType));
        } catch (Exception e) {
            log.warn("Publish FinishedGoodsCreatedEvent failed for {}: {}", batch.getId(), e.getMessage());
        }

        log.info("创建成品批次: factoryId={}, batchNumber={}, productTypeId={}, sourceType={}",
                factoryId, batch.getBatchNumber(), batch.getProductTypeId(), sourceType);
        return batch;
    }

    @Override
    @Transactional
    public com.cretas.aims.entity.inventory.FinishedGoodsBatch editFinishedGoodsBatch(
            String factoryId, String batchId,
            com.cretas.aims.dto.inventory.EditFinishedGoodsRequest request,
            Long operatorId) {
        // F12: isolation check first — getFinishedGoodsBatchById does the 403 guard
        FinishedGoodsBatch batch = getFinishedGoodsBatchById(factoryId, batchId);

        // ⛔ producedQuantity and unit are intentionally NOT touched here
        if (request.remark() != null) {
            batch.setRemark(request.remark());
        }
        if (request.storageLocation() != null) {
            batch.setStorageLocation(request.storageLocation());
        }
        if (request.expireDate() != null) {
            batch.setExpireDate(request.expireDate());
        }
        if (request.unitPrice() != null) {
            batch.setUnitPrice(request.unitPrice());
        }

        batch = finishedGoodsBatchRepository.save(batch);
        log.info("编辑成品批次: factoryId={}, batchId={}, operatorId={}", factoryId, batchId, operatorId);
        return batch;
    }

    @Override
    @Transactional
    public com.cretas.aims.entity.inventory.FinishedGoodsBatch adjustFinishedGoodsQuantity(
            String factoryId, String batchId,
            com.cretas.aims.dto.inventory.AdjustFinishedGoodsRequest request,
            Long operatorId) {
        // F12: isolation check first
        FinishedGoodsBatch batch = getFinishedGoodsBatchById(factoryId, batchId);

        BigDecimal beforeProduced = batch.getProducedQuantity();
        BigDecimal newProduced = beforeProduced.add(request.adjustmentQuantity());

        // F2: check new available quantity (newProduced - shipped - reserved >= 0)
        BigDecimal shipped  = batch.getShippedQuantity()  != null ? batch.getShippedQuantity()  : BigDecimal.ZERO;
        BigDecimal reserved = batch.getReservedQuantity() != null ? batch.getReservedQuantity() : BigDecimal.ZERO;
        BigDecimal newAvailable = newProduced.subtract(shipped).subtract(reserved);
        if (newAvailable.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(422, "调整后可用量为负数")
                    .withHint(String.format(
                            "当前已出库 %s 件, 已占用 %s 件, 调整量不得低于 %s",
                            shipped.toPlainString(),
                            reserved.toPlainString(),
                            shipped.add(reserved).subtract(beforeProduced).toPlainString()));
        }

        batch.setProducedQuantity(newProduced);
        batch = finishedGoodsBatchRepository.save(batch);

        // Write adjustment log (F1: operatorId from request attr, not SecurityContextHolder)
        if (finishedGoodsAdjustmentLogRepository != null) {
            com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog logEntry =
                    com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog.builder()
                            .factoryId(factoryId)
                            .batchId(batchId)
                            .adjustmentQuantity(request.adjustmentQuantity())
                            .beforeProduced(beforeProduced)
                            .afterProduced(newProduced)
                            .reason(request.reason())
                            .referenceType(request.referenceType() != null ? request.referenceType().name() : null)
                            .operatorId(operatorId)
                            .build();
            finishedGoodsAdjustmentLogRepository.save(logEntry);
        }

        log.info("调整成品批次数量: factoryId={}, batchId={}, delta={}, newProduced={}, operatorId={}",
                factoryId, batchId, request.adjustmentQuantity(), newProduced, operatorId);
        return batch;
    }

    @Override
    @Transactional
    public void voidFinishedGoodsBatch(String factoryId, String batchId, Long operatorId) {
        // F12: isolation check first
        FinishedGoodsBatch batch = getFinishedGoodsBatchById(factoryId, batchId);

        // F5: precondition — no shipped or reserved records
        BigDecimal shipped  = batch.getShippedQuantity()  != null ? batch.getShippedQuantity()  : BigDecimal.ZERO;
        BigDecimal reserved = batch.getReservedQuantity() != null ? batch.getReservedQuantity() : BigDecimal.ZERO;
        if (shipped.compareTo(BigDecimal.ZERO) > 0 || reserved.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException(409, "该批次已有出库/预留记录, 无法作废")
                    .withHint(String.format(
                            "已出库 %s 件, 已预留 %s 件, 请先撤销相关出库/预留后再作废",
                            shipped.toPlainString(), reserved.toPlainString()));
        }

        // Soft-delete via BaseEntity.softDelete() (sets deleted_at, @Where filters it out)
        batch.softDelete();
        finishedGoodsBatchRepository.save(batch);
        log.info("作废成品批次: factoryId={}, batchId={}, operatorId={}", factoryId, batchId, operatorId);
    }

    // ==================== 统计 ====================

    @Override
    public Map<String, Object> getSalesStatistics(String factoryId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        LocalDate now = LocalDate.now();
        LocalDate monthStart = now.withDayOfMonth(1);
        List<SalesOrder> monthlyOrders = salesOrderRepository.findByFactoryIdAndDateRange(factoryId, monthStart, now);

        long totalOrders = monthlyOrders.size();
        // R23 audit C3: was inline {CONFIRMED, PENDING_FINANCE_REVIEW, FINANCE_APPROVED, PROCESSING}
        // — semantically "still in flight, not yet delivered/cancelled". Centralized as SO_IN_FLIGHT.
        long pendingOrders = monthlyOrders.stream()
                .filter(o -> o.getStatus() != null
                        && com.cretas.aims.domain.OrderUsageWhitelists.SO_IN_FLIGHT.contains(o.getStatus()))
                .count();
        BigDecimal monthlyRevenue = monthlyOrders.stream()
                .filter(o -> o.getStatus() != SalesOrderStatus.CANCELLED)
                .map(SalesOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        stats.put("monthlySalesOrderCount", totalOrders);
        stats.put("pendingDeliveryCount", pendingOrders);
        stats.put("monthlySalesAmount", monthlyRevenue);

        return stats;
    }

    // ==================== 内部方法 ====================

    private String generateSalesOrderNumber(String factoryId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "SO-" + dateStr + "-";
        // Serialize concurrent number generation on (factory, date) via a transaction-scoped
        // advisory lock. Released automatically at transaction end. Prevents the MAX+1 race
        // against uk_so_factory_order when two POSTs land in the same millisecond.
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(CAST(:fkey AS int), CAST(:dkey AS int))")
                .setParameter("fkey", factoryId.hashCode())
                .setParameter("dkey", dateStr.hashCode())
                .getSingleResult();
        String maxNumber = salesOrderRepository.findMaxOrderNumberByPrefix(factoryId, prefix + "%");
        int seq = 1;
        if (maxNumber != null && maxNumber.startsWith(prefix)) {
            try {
                seq = Integer.parseInt(maxNumber.substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {}
        }
        return String.format("SO-%s-%04d", dateStr, seq);
    }

    private String generateDeliveryNumber(String factoryId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long ts = System.currentTimeMillis() % 10000;
        return String.format("DLV-%s-%04d", dateStr, ts);
    }

    private String generateFinishedGoodsBatchNumber(String factoryId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long ts = System.currentTimeMillis() % 10000;
        return String.format("FG-%s-%04d", dateStr, ts);
    }

    /**
     * Canvas Config: 检查工作流状态转换是否被配置允许。
     * 如果 factoryConfigService 未注入（模块未部署），默认允许所有转换（向后兼容）。
     */
    /**
     * Canvas V2: Run DB-driven validation rules before hardcoded checks.
     * If no DB rules exist, this is a no-op — existing hardcoded validation still runs.
     */
    /**
     * Canvas V2: Calculate line amount using FormulaEngine, fall back to entity method.
     */
    private BigDecimal calculateLineAmount(String factoryId, SalesOrderItem item) {
        if (formulaEngine != null && factoryId != null && item.getQuantity() != null && item.getUnitPrice() != null) {
            try {
                Map<String, Object> vars = new HashMap<>();
                vars.put("quantity", item.getQuantity());
                vars.put("unitPrice", item.getUnitPrice());
                vars.put("discountRate", item.getDiscountRate() != null ? item.getDiscountRate() : BigDecimal.ZERO);
                BigDecimal result = formulaEngine.evaluate(factoryId, "sales_order", "LINE_AMOUNT", vars);
                if (result != null) return result;
            } catch (Exception e) {
                log.debug("FormulaEngine LINE_AMOUNT failed, using entity method: {}", e.getMessage());
            }
        }
        return item.getLineAmount();
    }

    /**
     * Convert a pricing-engine <em>line total</em> back to a <em>per-unit</em> price.
     *
     * <p>{@link com.cretas.aims.service.pricing.PricingResult#getFinalPrice()} is a line total
     * ({@code unitPriceList × quantity − Σ discounts}, see {@code PricingEngineImpl}). A
     * {@link SalesOrderItem} stores a per-unit price, so the engine result must be divided by the
     * quantity before {@code setUnitPrice}. Skipping this (the 2026-05-30 bug, surfaced by the F006
     * UI E2E) stored {@code unitPrice = qty × price} and {@code totalAmount = qty² × price}.
     *
     * @return {@code lineTotal / quantity} (HALF_UP, scale 2), or {@code fallbackUnitPrice} when
     *         {@code lineTotal} is null / negative or {@code quantity} is null / non-positive.
     */
    public static BigDecimal lineTotalToUnitPrice(BigDecimal lineTotal, BigDecimal quantity,
                                                  BigDecimal fallbackUnitPrice) {
        // A negative line total must never become a negative unit price. PricingEngineImpl currently
        // clamps finalPrice to ≥ 0, but the engine contract is not enforced at compile time, so guard
        // here defensively rather than trust the upstream clamp (preflight-audit MEDIUM finding).
        if (lineTotal == null || lineTotal.signum() < 0 || quantity == null || quantity.signum() <= 0) {
            return fallbackUnitPrice;
        }
        return lineTotal.divide(quantity, 2, java.math.RoundingMode.HALF_UP);
    }

    private void runConfiguredValidation(String factoryId, String operation, Map<String, Object> context) {
        runConfiguredValidation(factoryId, "sales_order", operation, context);
    }

    // Round 9 Fix: overload with explicit moduleCode so delivery/shipment paths can
    // use their own module-scoped validation rules instead of reusing sales_order's.
    private void runConfiguredValidation(String factoryId, String moduleCode, String operation, Map<String, Object> context) {
        if (validationRuleEvaluator == null) return;
        try {
            validationRuleEvaluator.validate(factoryId, moduleCode, operation, context);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Canvas validation non-blocking error for {}.{}: {}",
                    moduleCode, operation, e.getMessage());
        }
    }

    private void checkTransitionAllowed(String factoryId, String fromStatus, String toStatus) {
        if (factoryConfigService != null) {
            try {
                if (!factoryConfigService.isTransitionAllowed(factoryId, "sales_order", fromStatus, toStatus)) {
                    throw new BusinessException(409, "当前配置不允许从 " + fromStatus + " 转换到 " + toStatus)
                            .withHint("请刷新订单列表查看最新状态");
                }
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                // No workflow config or config parse error → default to ALLOW
                log.warn("Workflow transition check failed (defaulting to ALLOW): {} → {}, error: {}", fromStatus, toStatus, e.getMessage());
            }
        }
    }

    /**
     * FIFO 扣减成品库存
     * 按生产日期从早到晚，依次扣减可用库存
     */
    private void deductFinishedGoodsInventory(String factoryId, SalesDeliveryItem item) {
        // T4-D5 #572: honor per-row sourceWarehouseCode (PR #547/#564 data contract).
        // Legacy rows (sourceWarehouseCode null) fall back to WH-LOG — preserves D5 default.
        String sourceCode = item.getSourceWarehouseCode();
        String warehouseId = (sourceCode != null && !sourceCode.isBlank())
                ? warehouseResolver.resolveId(factoryId, sourceCode)
                : warehouseResolver.resolveLogisticsId(factoryId);
        List<FinishedGoodsBatch> batches = finishedGoodsBatchRepository
                .findAvailableBatchesByWarehouse(factoryId, item.getProductTypeId(), warehouseId);

        BigDecimal remaining = item.getDeliveredQuantity();

        for (FinishedGoodsBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal available = batch.getAvailableQuantity();
            BigDecimal deduct = remaining.min(available);

            batch.setShippedQuantity(batch.getShippedQuantity().add(deduct));
            if (batch.isDepleted()) {
                batch.setStatus("DEPLETED");
            }
            finishedGoodsBatchRepository.save(batch);

            // 记录扣减的批次
            if (item.getFinishedGoodsBatchId() == null) {
                item.setFinishedGoodsBatchId(batch.getId());
            }

            remaining = remaining.subtract(deduct);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            String warehouseDisplay = (sourceCode != null && !sourceCode.isBlank()) ? sourceCode : "WH-LOG";
            throw new BusinessException(String.format("成品库存不足: 产品=%s, 仓库=%s, 缺少数量=%s",
                item.getProductTypeId(), warehouseDisplay, remaining.toPlainString()))
                .withHint("请检查 " + warehouseDisplay + " 库存, 或调整销售订单的来源仓库后再发货")
                .withHintTarget("生产计划");
        }
    }

    private void updateOrderDeliveryStatus(SalesDeliveryRecord record) {
        SalesOrder order = salesOrderRepository.findById(record.getSalesOrderId()).orElse(null);
        if (order == null) return;

        List<SalesOrderItem> orderItems = salesOrderItemRepository.findBySalesOrderId(order.getId());

        // 累加本次发货数量
        for (SalesDeliveryItem deliveryItem : record.getItems()) {
            for (SalesOrderItem orderItem : orderItems) {
                if (orderItem.getProductTypeId().equals(deliveryItem.getProductTypeId())) {
                    BigDecimal newDelivered = orderItem.getDeliveredQuantity().add(deliveryItem.getDeliveredQuantity());
                    orderItem.setDeliveredQuantity(newDelivered);
                }
            }
        }
        salesOrderItemRepository.saveAll(orderItems);

        boolean allDelivered = orderItems.stream().allMatch(item ->
                item.getDeliveredQuantity().compareTo(item.getQuantity()) >= 0);
        boolean anyDelivered = orderItems.stream().anyMatch(item ->
                item.getDeliveredQuantity().compareTo(BigDecimal.ZERO) > 0);

        if (allDelivered) {
            order.setStatus(SalesOrderStatus.COMPLETED);
            // P0-9: 全部发货完成 → transportPlanStatus = DELIVERED
            order.setTransportPlanStatus("DELIVERED");
        } else if (anyDelivered) {
            order.setStatus(SalesOrderStatus.PARTIAL_DELIVERED);
            // P0-9: 部分发货 → transportPlanStatus = IN_TRANSIT
            order.setTransportPlanStatus("IN_TRANSIT");
        } else {
            order.setStatus(SalesOrderStatus.PARTIAL_DELIVERED);
            order.setTransportPlanStatus("IN_TRANSIT");
        }
        salesOrderRepository.save(order);
    }

    // ==================== T3: 业务员双字段过渡 ====================

    /** Numeric user-id string pattern (User.id is Long, frontend sends as decimal string). */
    private static final java.util.regex.Pattern USER_ID_PATTERN =
        java.util.regex.Pattern.compile("^\\d{1,19}$");

    /**
     * 解析业务员字段 — 双字段过渡 (option 3 per spec §4.A.4).
     * 数字字符串 (User.id) → lookup user → 写入 salesperson_id + salesperson(name 快照)
     * 含非数字字符的字符串 → 老路径，只写 salesperson
     * null/空 → 不动
     */
    public void resolveSalespersonField(SalesOrder order, String input, String factoryId) {
        if (input == null || input.isBlank()) return;
        if (USER_ID_PATTERN.matcher(input).matches()) {
            Long userId = Long.parseLong(input);
            // R4 audit S2: if numeric input matches existing FK, no-op (don't re-lookup)
            // — covers the "user clicks save without touching salesperson, HR deleted user
            // mid-edit" case where re-lookup would throw 404.
            Long currentFkId = order.getSalespersonId();
            if (currentFkId != null && currentFkId.equals(userId)) {
                return;
            }
            User user = userRepository.findById(userId)
                .filter(u -> factoryId.equals(u.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("业务员不存在或不属于本工厂: " + input));
            order.setSalespersonId(userId);
            order.setSalesperson(user.getFullName());  // M1: snapshot name at save time
        } else {
            // R4 audit C2: defensive — if input string equals existing snapshot AND existing
            // FK is non-null, this is the legacy edit form re-PUTting the snapshot string
            // unchanged (LEGACY-mode list.vue dialog only sends `salesperson` not the FK).
            // Preserve the FK rather than wipe it, otherwise commission attribution silently
            // breaks for ANY legacy edit of a DYNAMIC-created SO.
            if (input.equals(order.getSalesperson()) && order.getSalespersonId() != null) {
                return;  // no-op, snapshot unchanged + FK already linked
            }
            order.setSalesperson(input);
            order.setSalespersonId(null);
        }
    }
}
