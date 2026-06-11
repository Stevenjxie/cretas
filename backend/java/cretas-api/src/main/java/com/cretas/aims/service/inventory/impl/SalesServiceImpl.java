package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreateDeliveryRequest;
import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.dto.inventory.UpdateSalesOrderRequest;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.entity.User;
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
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.rules.annotation.RuleEvaluate;
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
     * belowRedline=true → 409 BusinessException (fool-proof Rule 1: 预先拦截).
     * belowRedline=null (成本未配置) → WARN log + 放行 (不假装拦截, 不静默放行).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.pricing.GrossMarginRedlineService grossMarginRedlineService;

    /** Sprint 6 W2-B: 数据权限解析器 (optional). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DataScopeResolver dataScopeResolver;

    /** T126 Phase 1: 成品库存调整日志 Repository (optional for test ctor). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository finishedGoodsAdjustmentLogRepository;

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
        // P3 多仓: 叮咚采购单标题 (如 "0601-熟食T+2"). Nullable.
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

        return saved;
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
                orderId, saved.getOrderNumber(), reviewerId);

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

        java.util.List<com.cretas.aims.dto.inventory.FinanceCostBreakdown.LineCostBreakdown> lines =
                new java.util.ArrayList<>();

        for (SalesOrderItem item : order.getItems()) {
            BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            BigDecimal sellUnit = item.getUnitPrice();
            BigDecimal costUnit = item.getCostUnitPrice();
            BigDecimal lineAmount = (sellUnit != null)
                    ? qty.multiply(sellUnit).setScale(2, java.math.RoundingMode.HALF_UP)
                    : null;

            BigDecimal bomUnit = null;
            BigDecimal bomLine = null;
            if (bomRecipeService != null && item.getProductTypeId() != null) {
                try {
                    java.util.Optional<com.cretas.aims.entity.bom.BomRecipe> recipeOpt =
                            bomRecipeService.getCurrentRecipe(factoryId, item.getProductTypeId());
                    if (recipeOpt.isPresent()) {
                        BigDecimal tc = recipeOpt.get().getTotalCost();
                        // B2 fix: treat null or <= 0 totalCost as "BOM items have no unit price configured".
                        // A BOM that exists but has totalCost=null (hasNullPrice path) or =0 (empty-items
                        // path) must not be reported as 0 — that would mislead finance into thinking
                        // production truly costs nothing.  null → front-end shows "-" (honest).
                        if (tc != null && tc.compareTo(BigDecimal.ZERO) > 0) {
                            bomUnit = tc;
                            bomLine = qty.multiply(bomUnit).setScale(2, java.math.RoundingMode.HALF_UP);
                            bomStandardCostSum = bomStandardCostSum.add(bomLine);
                            anyBomAvailable = true;
                        }
                        // else: BOM exists but prices not configured → bomUnit stays null (honest gap)
                    }
                } catch (Exception e) {
                    log.warn("BOM 标准成本查询失败 (productTypeId={}): {}", item.getProductTypeId(), e.getMessage());
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

            // SP3: 行级超支百分比
            BigDecimal lineVariancePct = null;
            Boolean lineBelowThreshold = null;
            BigDecimal actualCostPerUnit = (costUnit != null && costUnit.compareTo(BigDecimal.ZERO) > 0) ? costUnit : null;
            if (costVarianceService != null && actualCostPerUnit != null && bomUnit != null) {
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
        String hintStr = hint.length() > 0 ? hint.toString().trim() : null;

        // SP3: 订单级超支计算
        java.math.BigDecimal orderVariancePct = null;
        java.math.BigDecimal orderVarianceAbsolute = null;
        Boolean orderBelowThreshold = null;
        String orderAlarmMessage = null;
        if (costVarianceService != null && actualCost != null && bomStandardCost != null) {
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

        return com.cretas.aims.dto.inventory.FinanceCostBreakdown.builder()
                .totalAmount(totalAmount)
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
                .lines(lines)
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
