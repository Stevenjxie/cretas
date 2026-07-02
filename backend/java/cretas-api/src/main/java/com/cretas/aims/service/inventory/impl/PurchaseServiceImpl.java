package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.dto.inventory.CreateReceiveRecordRequest;
import com.cretas.aims.dto.inventory.UpdatePurchaseOrderRequest;
import com.cretas.aims.dto.inventory.MaterialPriceComparisonDTO;
import com.cretas.aims.dto.inventory.PurchaseSuggestionResponse;
import com.cretas.aims.dto.inventory.PurchaseSuggestionMultiResponse;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.enums.PurchaseType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderApprovalRuleRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.entity.inventory.PurchaseOrderApprovalRule;
import com.cretas.aims.event.MaterialReceivedEvent;
import com.cretas.aims.annotation.DataScope;
import com.cretas.aims.annotation.Loggable;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.inventory.PurchaseService;
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
public class PurchaseServiceImpl implements PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseServiceImpl.class);

    /**
     * 抄收上限率（默认 30%，per audio May 7 客户通话 "餐饮/物业行业正常抄收应该是30%以内"）。
     *
     * 实际累计收货量上限 = 下单量 × (1 + overReceiveRate)。超过则 confirmReceive 抛
     * BusinessException 409 + 事务回滚，要求采购另下新订单。
     *
     * 旧逻辑漏洞：updateOrderReceiveStatus 仅累加 + 设状态，无上限校验 → 分批入库
     * 第二次/N 次可无限超收。客户在 audio 中提及的 30% 上限即此修复。
     *
     * 双轨说明：纯 backend service-level 校验，LEGACY 和 CANVAS (DynamicModulePage)
     * 都走同一 createReceiveRecord → confirmReceive 路径，不分模式。
     *
     * Ops 调整：在 application.properties 设 cretas.purchase.over-receive-rate=0.50
     * 等可临时放宽，无需 rebuild。
     */
    @org.springframework.beans.factory.annotation.Value("${cretas.purchase.over-receive-rate:0.30}")
    private BigDecimal overReceiveRate;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final PurchaseReceiveRecordRepository receiveRecordRepository;
    private final SupplierRepository supplierRepository;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final BomItemRepository bomItemRepository;
    private final com.cretas.aims.service.finance.ArApService arApService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final MaterialBatchService materialBatchService;

    /**
     * SP6 — 采购异常单生成（SP6 新增 bean）。@Lazy 防止循环依赖（PurchaseService ↔ PurchaseExceptionService）。
     * required=false 兼容旧 ApplicationContext（SP6 migration 未 apply 时保持 PurchaseService 可启动）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.cretas.aims.service.inventory.PurchaseExceptionService purchaseExceptionService;

    /** Rule 2 hydration: lookup SO orderNumber for PO.salesOrderNumber @Transient. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SalesOrderRepository salesOrderRepository;

    /** 开始采购: 从 SO 展开原料需求. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SalesOrderItemRepository salesOrderItemRepository;

    /**
     * #748 口径统一 (2026-06-11): 采购建议优先读 bom_recipe_items (新表), 与财务成本拆分
     * ({@code SalesServiceImpl.getOrderCostBreakdown} 经 {@code BomRecipeService.getCurrentRecipe})
     * 同一 BOM 源, 消除"财务算的料 vs 采购建议的料对不上"的口径不一致.
     * 仅当产品无 ACTIVE recipe 时回退到 legacy bom_items (向后兼容旧品).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.bom.BomRecipeRepository bomRecipeRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.bom.BomRecipeItemRepository bomRecipeItemRepository;

    /** D-6: 责任绑定 — 查询 PO 创建人的用户名供异常单展示（required=false 兼容旧 context） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.UserRepository userRepository;

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    /**
     * Round 11 T1 — Canvas Integration Template hook 2.
     * Writes factory-configured dynamic fields into cf_* columns on
     * purchase_receive_records so downstream trigger chains, reports, and
     * exports can read them alongside the standard columns.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DefaultValueResolver defaultValueResolver;

    /** D1 双仓流转 (2026-05-10 spec, PR #309 A1=A) — 采购入库默认 WH-LOG. */
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.service.factory.WarehouseResolver warehouseResolver;

    /**
     * SP7 §3.3 仓库库存守卫 (W1 红线 #03): 采购确认收货前校验仓库类型 ↔ 物料大类.
     * 采购入库的是原料 (RAW), legacy/null 类型仓库自动放行 (防误拦 F006 现有 legacy 仓).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.factory.WarehouseInventoryGuardService warehouseInventoryGuardService;

    /**
     * 三价对比偏差预警阈值最终兜底值 (10%).
     *
     * <p>Resolution chain: PurchaseOrderApprovalRule.priceVarianceThreshold (per-factory rule) →
     * ThresholdResolverService (Canvas Thresholds Hub per-factory config) → this hard-coded fallback.
     * Sprint2-J seeded default 10%/10万元 rules; Phase A P0-3 wired Canvas Thresholds Hub override.
     */
    private static final BigDecimal FALLBACK_PRICE_ALERT_THRESHOLD = new BigDecimal("10");

    /** Sprint2-J P-FIN-1: per-factory 可配置审核规则. required=false 兼容老 ApplicationContext (启动顺序). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PurchaseOrderApprovalRuleRepository approvalRuleRepository;

    /**
     * Canvas-Thresholds resolver (Phase A P0-3) — overlays FALLBACK_PRICE_ALERT_THRESHOLD with
     * per-factory config when no PurchaseOrderApprovalRule.priceVarianceThreshold is set.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.canvas.ThresholdResolverService thresholdResolver;

    private BigDecimal resolvePriceAlertThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_PRICE_ALERT_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId,
                com.cretas.aims.service.canvas.ThresholdKeys.PURCHASE_PRICE_ALERT_RATIO,
                FALLBACK_PRICE_ALERT_THRESHOLD);
    }

    /**
     * Sprint2-J follow-up: 通用通知服务 (P1-5).
     * approveOrder 触发 PENDING_FINANCE_REVIEW 时通知 finance_manager 角色.
     * 当前 impl DbNotificationServiceImpl 持久化到 notifications 表,
     * Track B1 (DingTalk) merge 后 @Primary 切换即转钉钉, 业务无需改动.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.notification.NotificationService notificationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.bom.BomPriceAdjustmentService bomPriceAdjustmentService;

    /**
     * Phase 1 Canvas-Workflow B.6 — workflow engine 替换 evaluateApprovalTrigger.
     *
     * <p>{@code approveOrder} 优先走 Canvas-configured workflow (graph DAG).
     * 当 factory 无 active PURCHASE_ORDER_APPROVAL workflow 时, 回落 legacy
     * {@link #legacyApproveOrder} (PR #859 approval_rules 临时方案).
     *
     * <p>{@code required=false} — Phase 1 灰度期允许工程师把 bean 移除测兜底路径.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorkflowEngineService workflowEngine;

    /** Phase 1 B.6 — 用于查询 active workflow 的 graph 节点 (notifyNextStage 需要). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ApprovalWorkflowService approvalWorkflowService;

    /** Sprint 6 W2-B: 数据权限解析器 (optional). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DataScopeResolver dataScopeResolver;

    /**
     * Phase 4a follow-up (issue #45) — self-proxy reference for Spring AOP aspect interception.
     *
     * <p>Spring CGLIB / JDK proxy only intercepts calls that go THROUGH the proxy. Direct
     * {@code this.evaluateOrderRules(...)} calls bypass the proxy → aspect does NOT fire.
     * By calling {@code self.evaluateOrderRules(...)} we route through the proxy, triggering
     * {@link com.cretas.aims.service.rules.aop.RuleEvaluateAspect}.
     *
     * <p>{@code @Lazy} breaks the Spring BeanCurrentlyInCreationException on
     * self-referencing constructor wiring (well-known pattern, see Spring Framework docs).
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private PurchaseService self;

    public PurchaseServiceImpl(PurchaseOrderRepository purchaseOrderRepository,
                               PurchaseOrderItemRepository purchaseOrderItemRepository,
                               PurchaseReceiveRecordRepository receiveRecordRepository,
                               SupplierRepository supplierRepository,
                               RawMaterialTypeRepository materialTypeRepository,
                               MaterialBatchRepository materialBatchRepository,
                               BomItemRepository bomItemRepository,
                               com.cretas.aims.service.finance.ArApService arApService,
                               ApplicationEventPublisher applicationEventPublisher,
                               MaterialBatchService materialBatchService) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.receiveRecordRepository = receiveRecordRepository;
        this.supplierRepository = supplierRepository;
        this.materialTypeRepository = materialTypeRepository;
        this.materialBatchRepository = materialBatchRepository;
        this.bomItemRepository = bomItemRepository;
        this.arApService = arApService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.materialBatchService = materialBatchService;
    }

    // ==================== 采购订单 ====================

    @Override
    @Transactional
    @Loggable(module = "PURCHASE_ORDER", action = "CREATE", entityType = "PurchaseOrder",
              summary = "'创建采购单 ' + #request.supplierName")
    public PurchaseOrder createPurchaseOrder(String factoryId, CreatePurchaseOrderRequest request, Long userId) {
        // Canvas V2: DB-driven validation
        if (validationRuleEvaluator != null) {
            try {
                validationRuleEvaluator.validate(factoryId, "purchase_order", "CREATE",
                        java.util.Map.of("itemCount", request.getItems() != null ? request.getItems().size() : 0));
            } catch (com.cretas.aims.exception.BusinessException e) { throw e; }
            catch (Exception e) { log.warn("Canvas validation non-blocking: {}", e.getMessage()); }
        }

        // 验证供应商
        supplierRepository.findByIdAndFactoryId(request.getSupplierId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("供应商不存在或不属于当前组织"));

        // 防呆 R4 (幂等防双击, edge-case 审计 2026-06-24): 60s 内同买手对同供应商重复建 DRAFT 单 → 409。
        // 键含 createdBy, 误拦仅"同一人 60s 内对同供应商双击"; 合法重复下单 (不同人/超 60s) 不受影响。
        java.util.List<PurchaseOrder> poDupes = purchaseOrderRepository.findRecentDuplicateOrders(
                factoryId, request.getSupplierId(), userId, java.time.LocalDateTime.now().minusSeconds(60));
        if (!poDupes.isEmpty()) {
            PurchaseOrder existing = poDupes.get(0);
            throw new com.cretas.aims.exception.BusinessException(409, String.format(
                    "60 秒内已对该供应商创建采购单 (%s, 状态 %s), 如确为另一单请稍候再建",
                    existing.getOrderNumber(), existing.getStatus()))
                    .withHint("如需查看已有采购单请打开 " + existing.getOrderNumber())
                    .withHintTarget(existing.getId());
        }

        // 生成订单号: PO-YYYYMMDD-序号
        String orderNumber = generateOrderNumber(factoryId);

        PurchaseOrder order = new PurchaseOrder();
        order.setFactoryId(factoryId);
        order.setOrderNumber(orderNumber);
        order.setSupplierId(request.getSupplierId());
        order.setPurchaseType(PurchaseType.valueOf(request.getPurchaseType()));
        order.setOrderDate(request.getOrderDate());
        order.setExpectedDeliveryDate(request.getExpectedDeliveryDate());
        order.setRemark(request.getRemark());
        // W-12 fix: persist salesOrderId for cross-module tracking (SO detail "关联采购" tab)
        order.setSalesOrderId(request.getSalesOrderId());
        // SP6 — 合同号 / 结算方式 / 开票提醒天数（全 3 处 DTO-roundtrip 修复）
        order.setContractNumber(request.getContractNumber());
        if (request.getSettlementType() != null && !request.getSettlementType().isBlank()) {
            order.setSettlementType(com.cretas.aims.entity.enums.SettlementType.valueOf(request.getSettlementType()));
        }
        order.setInvoiceReminderDays(request.getInvoiceReminderDays());
        order.setStatus(PurchaseOrderStatus.DRAFT);
        order.setCreatedBy(userId);

        // 保存订单（@PrePersist 自动生成 UUID）
        order = purchaseOrderRepository.save(order);

        // 创建行项目
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;
        List<PurchaseOrderItem> items = new ArrayList<>();

        for (CreatePurchaseOrderRequest.PurchaseOrderItemDTO itemDTO : request.getItems()) {
            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setPurchaseOrderId(order.getId());
            item.setMaterialTypeId(itemDTO.getMaterialTypeId());
            // 自动填充原料名称：前端未传时从基础数据查询
            String materialName = itemDTO.getMaterialName();
            if (materialName == null || materialName.isBlank()) {
                materialName = materialTypeRepository.findById(itemDTO.getMaterialTypeId())
                        .map(RawMaterialType::getName).orElse(null);
            }
            item.setMaterialName(materialName);
            item.setQuantity(itemDTO.getQuantity());
            item.setUnit(itemDTO.getUnit());
            item.setUnitPrice(itemDTO.getUnitPrice());
            item.setTaxRate(itemDTO.getTaxRate() != null ? itemDTO.getTaxRate() : BigDecimal.ZERO);
            item.setRemark(itemDTO.getRemark());
            item.setSpecification(itemDTO.getSpecification());
            item.setBoxQuantity(itemDTO.getBoxQuantity());
            items.add(item);

            BigDecimal lineAmount = item.getLineAmount();
            if (lineAmount != null) {
                totalAmount = totalAmount.add(lineAmount);
                BigDecimal lineAmountWithTax = item.getLineAmountWithTax();
                if (lineAmountWithTax != null) {
                    taxAmount = taxAmount.add(lineAmountWithTax.subtract(lineAmount));
                }
            }
        }

        purchaseOrderItemRepository.saveAll(items);

        order.setTotalAmount(totalAmount);
        order.setTaxAmount(taxAmount);
        order = purchaseOrderRepository.save(order);

        log.info("创建采购订单: factoryId={}, orderNumber={}, items={}", factoryId, orderNumber, items.size());
        return order;
    }

    @Override
    public PurchaseOrder getPurchaseOrderById(String factoryId, String orderId) {
        PurchaseOrder order = purchaseOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("采购订单不存在"));
        if (!order.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权访问该采购订单")
                    .withHint("当前采购订单不属于该工厂, 无法访问");
        }
        hydrateSalesOrderNumber(order);
        return order;
    }

    @Override
    public PurchaseOrder getPurchaseOrderByNumber(String factoryId, String orderNumber) {
        PurchaseOrder order = purchaseOrderRepository.findByFactoryIdAndOrderNumber(factoryId, orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("采购订单不存在: " + orderNumber));
        hydrateSalesOrderNumber(order);
        return order;
    }

    /**
     * Rule 2 hydration: 给 PO 填 salesOrderNumber (@Transient). 前端"关联销售订单"
     * 展示直接用, 免去 1+N 查询 SO. Null-safe — 无 salesOrderId 时不查.
     */
    private void hydrateSalesOrderNumber(PurchaseOrder order) {
        if (order == null || order.getSalesOrderId() == null || salesOrderRepository == null) return;
        try {
            salesOrderRepository.findById(order.getSalesOrderId())
                    .ifPresent(so -> order.setSalesOrderNumber(so.getOrderNumber()));
        } catch (Exception e) {
            log.debug("hydrate salesOrderNumber failed for PO {}: {}", order.getId(), e.getMessage());
        }
    }

    /**
     * Rule 2 hydration (batch): 给 list 结果一次性填 salesOrderNumber, 避免 1+N.
     * 收集唯一 salesOrderIds → findAllById → map → set 回每个 PO.
     */
    private void hydrateSalesOrderNumbers(List<PurchaseOrder> orders) {
        if (orders == null || orders.isEmpty() || salesOrderRepository == null) return;
        Set<String> ids = orders.stream()
                .map(PurchaseOrder::getSalesOrderId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return;
        try {
            Map<String, String> idToNumber = new HashMap<>();
            salesOrderRepository.findAllById(ids).forEach(so -> idToNumber.put(so.getId(), so.getOrderNumber()));
            for (PurchaseOrder po : orders) {
                if (po.getSalesOrderId() != null) {
                    String num = idToNumber.get(po.getSalesOrderId());
                    if (num != null) po.setSalesOrderNumber(num);
                }
            }
        } catch (Exception e) {
            log.debug("batch hydrate salesOrderNumber failed: {}", e.getMessage());
        }
    }

    @Override
    @DataScope("created_by")  // Sprint 6 W2-B — RBAC 第 2 维 (数据权限) sweep
    public PageResponse<PurchaseOrder> getPurchaseOrders(String factoryId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        // Sprint 6 W2-B: dispatch on DataScope — SELF / SELF_AND_BELOW / DEPT_AND_BELOW filter
        // by created_by (single or chain). ALL scope (默认) 走原 path.
        DataScopeContext dsCtx = DataScopeContext.current();
        Page<PurchaseOrder> result;
        if (dsCtx != null && dsCtx.isFiltered() && dsCtx.getUserId() != null) {
            com.cretas.aims.entity.enums.DataScope scope = dsCtx.getScope();
            if (scope == com.cretas.aims.entity.enums.DataScope.SELF) {
                log.debug("DataScope SELF for purchase orders: created_by={}", dsCtx.getUserId());
                result = purchaseOrderRepository.findByFactoryIdAndCreatedByOrderByCreatedAtDesc(
                        factoryId, dsCtx.getUserId(), pageRequest);
            } else if (scope == com.cretas.aims.entity.enums.DataScope.SELF_AND_BELOW
                    || scope == com.cretas.aims.entity.enums.DataScope.DEPT_AND_BELOW) {
                List<Long> chain = dataScopeResolver != null
                        ? dataScopeResolver.resolveCreatedByChain(dsCtx)
                        : List.of(dsCtx.getUserId());
                if (chain == null || chain.isEmpty()) chain = List.of(dsCtx.getUserId());
                log.debug("DataScope {} for purchase orders: chain size={}", scope, chain.size());
                result = purchaseOrderRepository.findByFactoryIdAndCreatedByInOrderByCreatedAtDesc(
                        factoryId, chain, pageRequest);
            } else {
                // CUSTOM defer Sprint 7
                result = purchaseOrderRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageRequest);
            }
        } else {
            result = purchaseOrderRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageRequest);
        }

        hydrateSalesOrderNumbers(result.getContent());
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    public PageResponse<PurchaseOrder> getPurchaseOrdersByStatus(String factoryId, PurchaseOrderStatus status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size);
        Page<PurchaseOrder> result = purchaseOrderRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(factoryId, status, pageRequest);
        hydrateSalesOrderNumbers(result.getContent());
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    public PageResponse<PurchaseOrder> getPurchaseOrdersBySalesOrder(String factoryId, String salesOrderId, int page, int size) {
        // W-12 fix: SO detail page's "关联采购" tab needs this filter
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PurchaseOrder> result = purchaseOrderRepository.findByFactoryIdAndSalesOrderId(factoryId, salesOrderId, pageRequest);
        hydrateSalesOrderNumbers(result.getContent());
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    @Transactional
    @Loggable(module = "PURCHASE_ORDER", action = "SUBMIT", entityType = "PurchaseOrder",
              entityIdParam = "orderId")
    public PurchaseOrder submitOrder(String factoryId, String orderId) {
        PurchaseOrder order = getPurchaseOrderById(factoryId, orderId);
        // Phase 4a follow-up (issue #45): Option A wrap. The annotated helper
        // evaluateOrderRules() must be invoked through the Spring proxy ({@code self}, not
        // {@code this}) so the @RuleEvaluate aspect intercepts. The helper receives the
        // loaded PurchaseOrder POJO; aspect binds target="order" parameter name → runs
        // ORDER scope rules against it.
        self.evaluateOrderRules(factoryId, order);
        if (order.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new BusinessException(409, "只有草稿状态的订单可以提交")
                    .withHint("请刷新订单列表查看最新状态");
        }
        order.setStatus(PurchaseOrderStatus.SUBMITTED);
        log.info("提交采购订单: orderId={}, orderNumber={}", orderId, order.getOrderNumber());
        return purchaseOrderRepository.save(order);
    }

    /**
     * Phase 4a follow-up (issue #45) — RuleEngine bridge for {@link #submitOrder(String, String)}.
     *
     * <p>Why this method exists: the original {@code submitOrder(String, String)} signature has
     * only String args, so {@code @RuleEvaluate} attached there would no-op (inputObject=null
     * per aspect's String/Number/Boolean skip). We can't change the public signature without
     * breaking callers. Solution: load the {@link PurchaseOrder} POJO inside submitOrder, then
     * call this annotated wrapper via the self-proxy. The aspect's {@code target="order"}
     * parameter-name binding resolves to our POJO and runs ORDER scope rules against it.
     *
     * <p><b>Must be called via {@code self.evaluateOrderRules(...)}</b> (not
     * {@code this.evaluateOrderRules(...)}) — Spring CGLIB proxy only intercepts external /
     * proxy-routed calls. Direct {@code this.*} self-invocation bypasses the proxy, leaving
     * the aspect silent. See the {@code self} field JavaDoc.
     *
     * <p>Body is intentionally empty — all logic happens in
     * {@link com.cretas.aims.service.rules.aop.RuleEvaluateAspect#evaluateRules}:
     * <ul>
     *   <li>REJECT → throws {@link com.cretas.aims.service.rules.RuleViolationException}
     *       (propagates out of submitOrder → caught by GlobalExceptionHandler → HTTP 400)</li>
     *   <li>MODIFY → mutates {@code order} in-place via BeanWrapper before this method returns
     *       (changes visible to submitOrder's subsequent {@code order.setStatus(...)} line)</li>
     *   <li>LOG / TRIGGER_WORKFLOW → side-effect only, returns normally</li>
     * </ul>
     */
    @Override
    @RuleEvaluate(value = "ORDER", target = "order")
    public void evaluateOrderRules(String factoryId, PurchaseOrder order) {
        // Intentionally empty — RuleEvaluateAspect @Around intercepts here.
    }

    /**
     * Phase 1 Canvas-Workflow B.6 — approveOrder 优先走 Canvas-configured workflow.
     *
     * <p>分支策略:
     * <ol>
     *   <li>{@link WorkflowEngineService} bean 不可用 → legacy path</li>
     *   <li>同一 PO 已有 RUNNING workflow instance (人工 resume) → {@code transitionNode}</li>
     *   <li>Otherwise, try {@code startWorkflow}; 若 factory 无 active workflow ({@code IllegalArgumentException})
     *       → legacy fallback</li>
     * </ol>
     *
     * <p>workflow 终态 → PO status 映射:
     * <ul>
     *   <li>{@code InstanceStatus.APPROVED} → {@code PurchaseOrderStatus.APPROVED}</li>
     *   <li>{@code InstanceStatus.REJECTED} → 保留 SUBMITTED + audit note (enum 暂无 REJECTED 值)</li>
     *   <li>{@code InstanceStatus.RUNNING} → {@code PurchaseOrderStatus.WORKFLOW_RUNNING} + notify next-stage approvers</li>
     *   <li>{@code CANCELLED / TIMEOUT} → 回 SUBMITTED 等待人工 retry</li>
     * </ul>
     *
     * <p>TODO (Phase 2 / Sprint 4): 增加 {@code fromNodeId} 请求参数, 允许 controller 精确指定
     * parallel 场景下当前审批的具体 active node id. B.4 当前 derive from {@code currentNodeIds[0]}.
     */
    @Override
    @Transactional
    @Loggable(module = "PURCHASE_ORDER", action = "APPROVE", entityType = "PurchaseOrder",
              entityIdParam = "orderId")
    public PurchaseOrder approveOrder(String factoryId, String orderId, Long approvedBy) {
        PurchaseOrder order = getPurchaseOrderById(factoryId, orderId);
        // 兼容 WORKFLOW_RUNNING — 多步审批中, 同一审批人继续推进的语义合理.
        if (order.getStatus() != PurchaseOrderStatus.SUBMITTED
                && order.getStatus() != PurchaseOrderStatus.WORKFLOW_RUNNING) {
            throw new BusinessException(409, "只有已提交或审批中状态的订单可以审批")
                    .withHint("请刷新订单列表查看最新状态");
        }

        // Phase 1 B.6 — workflow engine 不可用直接走 legacy path
        if (workflowEngine == null) {
            return legacyApproveOrder(factoryId, orderId, approvedBy, order);
        }

        // Phase 1 hotfix 2026-05-18 — pre-check active workflow 存在, 避免 startWorkflow
        // 的 orElseThrow 路径触发 Spring "Transaction marked rollback-only" 陷阱.
        // 之前 try/catch IllegalArgumentException 形式即使 catch 也会让外层事务回滚 →
        // commit 时 UnexpectedRollbackException, F001 等无 workflow 工厂 PO 审批整体失败.
        if (!workflowEngine.hasActiveWorkflow(factoryId, "PURCHASE_ORDER")) {
            log.info("No active PURCHASE_ORDER_APPROVAL workflow for factory={}; using legacy path",
                    factoryId);
            return legacyApproveOrder(factoryId, orderId, approvedBy, order);
        }

        // Build context for workflow evaluation (SpEL 在 edges 上读 #context.xxx)
        Map<String, Object> context = new HashMap<>();
        context.put("amount", order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO);
        context.put("totalAmount", order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO);
        context.put("orderId", order.getId());
        context.put("supplierId", order.getSupplierId() != null ? order.getSupplierId() : "");
        context.put("priceVarianceItemCount", countPriceAlertItems(factoryId, order));
        context.put("decision", "APPROVE");

        // 是否已有 RUNNING workflow (resume) 还是首次启动
        Optional<ApprovalWorkflowInstance> existing = workflowEngine.getCurrentInstance(
                factoryId, "PURCHASE_ORDER", order.getId());

        ApprovalWorkflowInstance instance;
        if (existing.isPresent() && existing.get().getStatus() == InstanceStatus.RUNNING) {
            // Resume — transition the current node
            instance = workflowEngine.transitionNode(
                    existing.get().getId(), approvedBy, "factory_super_admin",
                    HistoryAction.APPROVE, "采购订单审批");
        } else {
            // Start new workflow — pre-check 已确认 hasActiveWorkflow=true, 此处不应抛.
            // 若仍 IllegalArgumentException (race condition: workflow 刚被 disable), 让异常
            // 自然往上抛 → controller 返 4xx → 用户重试 (此时 hasActiveWorkflow 返 false → legacy).
            instance = workflowEngine.startWorkflow(
                    factoryId, "PURCHASE_ORDER", order.getId(), context, approvedBy);
        }

        // Translate workflow instance status to PO status
        order.setApprovedBy(approvedBy);
        order.setApprovedAt(LocalDateTime.now());
        order.setStatus(translateInstanceStatus(instance.getStatus()));

        if (instance.getStatus() == InstanceStatus.RUNNING) {
            // 通知下一阶段 approvers (审批人角色读自 active approval node 的 approverRoles)
            notifyNextStage(factoryId, order, instance);
            log.info("采购订单 → 工作流审批中: orderId={}, instanceId={}, currentNodes={}",
                    orderId, instance.getId(), instance.getCurrentNodeIds());
        } else {
            log.info("采购订单 → 工作流终态: orderId={}, instanceId={}, instanceStatus={}, poStatus={}",
                    orderId, instance.getId(), instance.getStatus(), order.getStatus());
        }

        return purchaseOrderRepository.save(order);
    }

    /**
     * Phase 1 B.6 legacy fallback — workflow engine 不可用或 factory 无 active workflow 时调用.
     *
     * <p>保留 Sprint2-J P-FIN-1 的 evaluateApprovalTrigger 路径: 总额 / 三价偏差触发
     * PENDING_FINANCE_REVIEW. 这是 PR #859 临时方案, 等 Phase 1+2 全部 factory 都迁
     * 到 Canvas workflow 后可删除.
     */
    private PurchaseOrder legacyApproveOrder(String factoryId, String orderId, Long approvedBy, PurchaseOrder order) {
        // Sprint2-J P-FIN-1: 评估审核规则 → 是否需要财务复核
        // 触发条件 (任一): (a) 任一行三价 priceAlert=true (偏差 > 阈值)
        //                  (b) 订单 totalAmount > rule.amountThreshold
        ApprovalTriggerEvaluation eval = evaluateApprovalTrigger(factoryId, order);

        order.setApprovedBy(approvedBy);
        order.setApprovedAt(LocalDateTime.now());

        if (eval.requiresFinanceReview()) {
            // 自动跳过 APPROVED, 直接进 PENDING_FINANCE_REVIEW —
            // 业务语义: 运营审批已 implicit 通过 (approvedBy/At 已记录),
            // 但财务复核未通过前不允许 receive/PO 履行 → 状态机锁定.
            order.setStatus(PurchaseOrderStatus.PENDING_FINANCE_REVIEW);
            log.info("[legacy] 审批采购订单 → 触发财务复核: orderId={}, approvedBy={}, 原因={}",
                    orderId, approvedBy, eval.reason());
        } else {
            order.setStatus(PurchaseOrderStatus.APPROVED);
            log.info("[legacy] 审批采购订单: orderId={}, approvedBy={}", orderId, approvedBy);
        }

        PurchaseOrder saved = purchaseOrderRepository.save(order);

        // Sprint2-J follow-up: 流入 PENDING_FINANCE_REVIEW 通知 finance_manager 角色.
        // 业务: 财务及时看到待审 → 缩短审批等待 → 不阻塞采购履约.
        // 当前 DbNotificationServiceImpl 写 notifications 表; Track B1 merge 后转钉钉.
        if (eval.requiresFinanceReview() && notificationService != null) {
            try {
                notificationService.notifyRole(
                        factoryId,
                        "FINANCE_MANAGER",
                        "采购单待财审",
                        String.format("采购单 %s 已运营审批, 待财务复核 (%s, 总额 ¥%s)",
                                saved.getOrderNumber(), eval.reason(), saved.getTotalAmount()));
            } catch (Exception e) {
                // 通知失败不应阻塞审批主流程 — 状态机已 commit, 业务正确性不受影响
                log.warn("财务复核通知发送失败 (主流程不受影响): orderId={}, error={}",
                        orderId, e.getMessage());
            }
        }

        return saved;
    }

    /**
     * Phase 1 B.6 — workflow instance status → PO status 映射.
     *
     * <p>{@code REJECTED} 暂保留 SUBMITTED (enum 尚未新增 REJECTED 终态), 上层日志已写入.
     * Phase 2 可考虑加 PurchaseOrderStatus.REJECTED.
     */
    private PurchaseOrderStatus translateInstanceStatus(InstanceStatus status) {
        if (status == null) return PurchaseOrderStatus.SUBMITTED;
        return switch (status) {
            case APPROVED -> PurchaseOrderStatus.APPROVED;
            case RUNNING -> PurchaseOrderStatus.WORKFLOW_RUNNING;
            // REJECTED — 没有专门枚举值, 暂回退 SUBMITTED 让用户改完重新提交.
            // CANCELLED / TIMEOUT — 边缘情况, 实例已结束, PO 回 SUBMITTED 可重新审批.
            case REJECTED, CANCELLED, TIMEOUT -> PurchaseOrderStatus.SUBMITTED;
        };
    }

    /**
     * Phase 1 B.6 — 统计触发三价标红的行数, 供 workflow context 使用.
     *
     * <p>逻辑同 {@link #evaluateApprovalTrigger} 但只返回计数, 不评估金额阈值. 失败时返 0
     * (fail-open, 不阻塞主审批).
     */
    private int countPriceAlertItems(String factoryId, PurchaseOrder order) {
        try {
            PurchaseOrderApprovalRule rule = resolveActiveRule(factoryId);
            BigDecimal threshold = rule != null && rule.getPriceVarianceThreshold() != null
                    ? rule.getPriceVarianceThreshold()
                    : resolvePriceAlertThreshold(factoryId);
            List<PurchaseOrderItem> items = purchaseOrderItemRepository.findByPurchaseOrderId(order.getId());
            int count = 0;
            for (PurchaseOrderItem item : items) {
                if (item.getMaterialTypeId() == null || item.getUnitPrice() == null) continue;
                MaterialPriceComparisonDTO dto = buildPriceComparison(factoryId,
                        item.getMaterialTypeId(), item.getMaterialName(),
                        item.getUnitPrice(), threshold);
                if (Boolean.TRUE.equals(dto.getPriceAlert())) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            log.warn("三价计数失败 (fail-open 返 0): orderId={}, error={}", order.getId(), e.getMessage());
            return 0;
        }
    }

    /**
     * Phase 1 B.6 — 通知下一阶段 active approval 节点对应的所有 approverRoles.
     *
     * <p>仅 type=approval 的 active 节点会发通知. notify/condition/parallel/join 自动节点
     * 跳过 (workflow 引擎已 autoAdvance).
     */
    @SuppressWarnings("unchecked")
    private void notifyNextStage(String factoryId, PurchaseOrder order, ApprovalWorkflowInstance instance) {
        if (notificationService == null || approvalWorkflowService == null) {
            return;
        }
        if (instance.getCurrentNodeIds() == null || instance.getCurrentNodeIds().isEmpty()) {
            return;
        }
        try {
            ApprovalWorkflow workflow = approvalWorkflowService
                    .getById(factoryId, instance.getWorkflowId()).orElse(null);
            if (workflow == null) return;
            List<ApprovalWorkflowNode> nodes = approvalWorkflowService
                    .deserializeNodes(workflow.getNodesJson());
            Map<String, ApprovalWorkflowNode> byId = new HashMap<>();
            for (ApprovalWorkflowNode n : nodes) {
                byId.put(n.getId(), n);
            }
            for (String nodeId : instance.getCurrentNodeIds()) {
                ApprovalWorkflowNode node = byId.get(nodeId);
                if (node == null || !"approval".equals(node.getType())) continue;
                Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
                Object roles = config.get("approverRoles");
                if (!(roles instanceof List<?> roleList)) continue;
                for (Object roleRaw : roleList) {
                    String role = String.valueOf(roleRaw);
                    if (role.isBlank()) continue;
                    try {
                        notificationService.notifyRole(
                                factoryId, role, "采购单待审",
                                String.format("采购单 %s 待%s审批 (节点: %s, 总额 ¥%s)",
                                        order.getOrderNumber(), role, node.getLabel(),
                                        order.getTotalAmount() == null ? "-" : order.getTotalAmount()));
                    } catch (Exception inner) {
                        log.warn("通知 role={} 失败 (主流程不受影响): orderId={}, error={}",
                                role, order.getId(), inner.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("notifyNextStage 失败 (主流程不受影响): orderId={}, instanceId={}, error={}",
                    order.getId(), instance.getId(), e.getMessage());
        }
    }

    /**
     * Sprint2-J P-FIN-1: 评估订单是否需要财务复核.
     *
     * <p>顺序: (1) 取 factory 启用规则 — 无规则 fallback 兜底值 10% / null 金额阈值
     * (2) 算三价 priceComparisons (复用 buildPriceComparison) — 任一行 priceAlert=true → 触发
     * (3) 比较 totalAmount vs amountThreshold — 超过 → 触发
     *
     * <p>不抛异常 — 评估失败 (e.g. 三价数据缺失) 时 fail-open 走 APPROVED 路径,
     * 由 ops 在 ApprovalRule 数据修复后重试 submit. 这符合 CLAUDE.md "禁止降级处理"
     * 的反面: 这里不是把错误显示成正常, 而是评估本身不强制 fail-closed (财务复核
     * 是 nice-to-have 拦截, 非业务正确性必要条件).
     */
    private ApprovalTriggerEvaluation evaluateApprovalTrigger(String factoryId, PurchaseOrder order) {
        PurchaseOrderApprovalRule rule = resolveActiveRule(factoryId);
        BigDecimal priceThreshold = rule != null && rule.getPriceVarianceThreshold() != null
                ? rule.getPriceVarianceThreshold()
                : resolvePriceAlertThreshold(factoryId);
        BigDecimal amountThreshold = rule != null ? rule.getAmountThreshold() : null;

        // 总金额触发 (先判, 不需要查每行三价)
        if (amountThreshold != null && order.getTotalAmount() != null
                && order.getTotalAmount().compareTo(amountThreshold) > 0) {
            return new ApprovalTriggerEvaluation(true,
                    String.format("总金额 ¥%s > 阈值 ¥%s", order.getTotalAmount(), amountThreshold));
        }

        // 三价标红触发 (逐行查 BOM + 移动均, 复用 buildPriceComparison)
        try {
            List<PurchaseOrderItem> items = purchaseOrderItemRepository.findByPurchaseOrderId(order.getId());
            int alertCount = 0;
            for (PurchaseOrderItem item : items) {
                if (item.getMaterialTypeId() == null || item.getUnitPrice() == null) continue;
                MaterialPriceComparisonDTO dto = buildPriceComparison(factoryId,
                        item.getMaterialTypeId(), item.getMaterialName(), item.getUnitPrice(), priceThreshold);
                if (Boolean.TRUE.equals(dto.getPriceAlert())) {
                    alertCount++;
                }
            }
            if (alertCount > 0) {
                return new ApprovalTriggerEvaluation(true,
                        String.format("三价标红 %d 项 (偏差 > %s%%)", alertCount, priceThreshold));
            }
        } catch (Exception e) {
            log.warn("三价评估失败 (fail-open → APPROVED): orderId={}, error={}", order.getId(), e.getMessage());
        }

        return new ApprovalTriggerEvaluation(false, "未触发 (价格 + 金额均在阈值内)");
    }

    /**
     * 取 factory 当前启用的审核规则. 多条 enabled=true 时按 createdAt desc 取首条
     * (ops 灰度新规则: 引入新规则后旧的应该 disabled).
     *
     * @return null 表示无启用规则 — caller 应 fallback 到代码兜底值
     */
    private PurchaseOrderApprovalRule resolveActiveRule(String factoryId) {
        if (approvalRuleRepository == null) return null;
        List<PurchaseOrderApprovalRule> rules =
                approvalRuleRepository.findByFactoryIdAndEnabledTrueOrderByCreatedAtDesc(factoryId);
        return rules.isEmpty() ? null : rules.get(0);
    }

    /** Approval trigger 评估结果 — internal record. */
    private record ApprovalTriggerEvaluation(boolean requiresFinanceReview, String reason) {}

    @Override
    @Transactional
    public PurchaseOrder submitForFinanceReview(String factoryId, String orderId) {
        PurchaseOrder order = getPurchaseOrderById(factoryId, orderId);
        if (order.getStatus() != PurchaseOrderStatus.APPROVED) {
            throw new BusinessException(409, "只有已审批状态的订单可以提交财务审核")
                    .withHint("请刷新订单列表查看最新状态");
        }
        order.setStatus(PurchaseOrderStatus.PENDING_FINANCE_REVIEW);
        log.info("采购订单提交财务审核: orderId={}", orderId);
        return purchaseOrderRepository.save(order);
    }

    @Override
    @Transactional
    public PurchaseOrder financeApproveOrder(String factoryId, String orderId, Long reviewedBy, String notes) {
        PurchaseOrder order = getPurchaseOrderById(factoryId, orderId);
        if (order.getStatus() != PurchaseOrderStatus.PENDING_FINANCE_REVIEW) {
            throw new BusinessException(409, "只有待财务审核状态的订单可以审核")
                    .withHint("请刷新订单列表查看最新状态");
        }
        order.setStatus(PurchaseOrderStatus.FINANCE_APPROVED);
        order.setFinanceReviewedBy(reviewedBy);
        order.setFinanceReviewedAt(java.time.LocalDateTime.now());
        order.setFinanceReviewNotes(notes);
        log.info("采购订单财务审核通过: orderId={}, reviewedBy={}", orderId, reviewedBy);
        return purchaseOrderRepository.save(order);
    }

    @Override
    @Transactional
    public PurchaseOrder financeRejectOrder(String factoryId, String orderId, Long reviewedBy, String notes) {
        PurchaseOrder order = getPurchaseOrderById(factoryId, orderId);
        if (order.getStatus() != PurchaseOrderStatus.PENDING_FINANCE_REVIEW) {
            throw new BusinessException(409, "只有待财务审核状态的订单可以驳回")
                    .withHint("请刷新订单列表查看最新状态");
        }
        order.setStatus(PurchaseOrderStatus.FINANCE_REJECTED);
        order.setFinanceReviewedBy(reviewedBy);
        order.setFinanceReviewedAt(java.time.LocalDateTime.now());
        order.setFinanceReviewNotes(notes);
        log.info("采购订单财务审核驳回: orderId={}, reviewedBy={}", orderId, reviewedBy);
        return purchaseOrderRepository.save(order);
    }

    @Override
    @Transactional
    @Loggable(module = "PURCHASE_ORDER", action = "CANCEL", entityType = "PurchaseOrder",
              entityIdParam = "orderId")
    public PurchaseOrder cancelOrder(String factoryId, String orderId) {
        PurchaseOrder order = getPurchaseOrderById(factoryId, orderId);
        // R39 BUG-8 sister fix: was only blocking COMPLETED/CLOSED → FINANCE_APPROVED/PARTIAL_RECEIVED/CANCELLED
        // could be cancelled, breaking AP + inventory invariants. Use whitelist.
        if (!com.cretas.aims.domain.OrderUsageWhitelists.PO_CANCELLABLE.contains(order.getStatus())) {
            throw new BusinessException(409,
                    "当前采购单状态(" + order.getStatus().getDisplayName() + ")不允许取消。"
                  + "财务批准/部分到货后请通过退货流程处理")
                    .withHint("请刷新订单列表查看最新状态");
        }
        order.setStatus(PurchaseOrderStatus.CANCELLED);
        log.info("取消采购订单: orderId={}, orderNumber={}", orderId, order.getOrderNumber());
        return purchaseOrderRepository.save(order);
    }

    @Override
    @Transactional
    @Loggable(module = "PURCHASE_ORDER", action = "UPDATE", entityType = "PurchaseOrder",
              entityIdParam = "orderId")
    public PurchaseOrder updateDraftOrder(String factoryId, String orderId, UpdatePurchaseOrderRequest request) {
        PurchaseOrder order = getPurchaseOrderById(factoryId, orderId);
        if (order.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new BusinessException(409, "只有草稿状态的订单可以编辑")
                    .withHint("请刷新订单列表查看最新状态");
        }
        // Optimistic lock: explicit compare (see CustomerServiceImpl for rationale)
        if (request.getVersion() != null && !request.getVersion().equals(order.getVersion())) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                PurchaseOrder.class, orderId);
        }

        // Partial update — only touch fields the caller sent
        if (request.getSupplierId() != null) {
            // Validate supplier only when changed
            supplierRepository.findByIdAndFactoryId(request.getSupplierId(), factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("供应商不存在或不属于当前组织"));
            order.setSupplierId(request.getSupplierId());
        }
        if (request.getPurchaseType() != null) {
            order.setPurchaseType(PurchaseType.valueOf(request.getPurchaseType()));
        }
        if (request.getOrderDate() != null) {
            order.setOrderDate(request.getOrderDate());
        }
        if (request.getExpectedDeliveryDate() != null) {
            order.setExpectedDeliveryDate(request.getExpectedDeliveryDate());
        }
        if (request.getRemark() != null) {
            order.setRemark(request.getRemark());
        }
        // W-12 fix: also update salesOrderId (null-safe — null means caller didn't send, not unlink)
        if (request.getSalesOrderId() != null) {
            order.setSalesOrderId(request.getSalesOrderId());
        }
        // SP6 — 合同号: null=不更新, ""=清除, 非空字符串=设新值
        if (request.getContractNumber() != null) {
            order.setContractNumber(request.getContractNumber().isBlank() ? null : request.getContractNumber());
        }
        // SP6 — 结算方式 null-guard
        if (request.getSettlementType() != null && !request.getSettlementType().isBlank()) {
            order.setSettlementType(com.cretas.aims.entity.enums.SettlementType.valueOf(request.getSettlementType()));
        }
        // SP6 — 开票提醒天数 null-guard
        if (request.getInvoiceReminderDays() != null) {
            order.setInvoiceReminderDays(request.getInvoiceReminderDays());
        }

        // Replace items only when request.items is provided (null = keep existing)
        BigDecimal totalAmount = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal taxAmount = order.getTaxAmount() != null ? order.getTaxAmount() : BigDecimal.ZERO;
        List<PurchaseOrderItem> items = new ArrayList<>();

        if (request.getItems() != null) {
        purchaseOrderItemRepository.deleteAll(
                purchaseOrderItemRepository.findByPurchaseOrderId(orderId));

        totalAmount = BigDecimal.ZERO;
        taxAmount = BigDecimal.ZERO;
        for (CreatePurchaseOrderRequest.PurchaseOrderItemDTO itemDTO : request.getItems()) {
            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setPurchaseOrderId(orderId);
            item.setMaterialTypeId(itemDTO.getMaterialTypeId());
            item.setMaterialName(itemDTO.getMaterialName());
            item.setQuantity(itemDTO.getQuantity());
            item.setUnit(itemDTO.getUnit());
            item.setUnitPrice(itemDTO.getUnitPrice());
            item.setTaxRate(itemDTO.getTaxRate() != null ? itemDTO.getTaxRate() : BigDecimal.ZERO);
            item.setRemark(itemDTO.getRemark());
            items.add(item);

            BigDecimal lineAmount = item.getLineAmount();
            if (lineAmount != null) {
                totalAmount = totalAmount.add(lineAmount);
                BigDecimal lineAmountWithTax = item.getLineAmountWithTax();
                if (lineAmountWithTax != null) {
                    taxAmount = taxAmount.add(lineAmountWithTax.subtract(lineAmount));
                }
            }
        }

        purchaseOrderItemRepository.saveAll(items);
        order.setTotalAmount(totalAmount);
        order.setTaxAmount(taxAmount);
        } // end if (request.getItems() != null)
        order = purchaseOrderRepository.save(order);

        log.info("编辑草稿采购订单: orderId={}, orderNumber={}", orderId, order.getOrderNumber());
        return order;
    }

    /**
     * 复制采购订单 — 见 {@link PurchaseService#copyPurchaseOrder} 文档.
     *
     * <p>实现说明:
     * <ul>
     *   <li>用 {@link #getPurchaseOrderById} 校验源订单 + factory 隔离 (404 / 403).</li>
     *   <li>新订单 status = DRAFT, createdBy = 当前用户, orderDate = 今天.</li>
     *   <li>items 用 fresh PurchaseOrderItem 实例 (Hibernate 不复用 id), receivedQuantity 重置为 0.</li>
     *   <li>orderNumber 复用 {@link #generateOrderNumber} 防 race condition.</li>
     * </ul>
     */
    @Override
    @Transactional
    @Loggable(module = "PURCHASE_ORDER", action = "COPY", entityType = "PurchaseOrder",
              entityIdParam = "sourceOrderId")
    public PurchaseOrder copyPurchaseOrder(String factoryId, String sourceOrderId, Long userId) {
        PurchaseOrder source = getPurchaseOrderById(factoryId, sourceOrderId);
        // 强制初始化 items (lazy collection)
        org.hibernate.Hibernate.initialize(source.getItems());

        String newOrderNumber = generateOrderNumber(factoryId);

        PurchaseOrder newOrder = new PurchaseOrder();
        newOrder.setFactoryId(factoryId);
        newOrder.setOrderNumber(newOrderNumber);
        // 复制业务字段
        newOrder.setSupplierId(source.getSupplierId());
        newOrder.setPurchaseType(source.getPurchaseType());
        newOrder.setIsImported(source.getIsImported());
        newOrder.setOrderDate(LocalDate.now());
        newOrder.setExpectedDeliveryDate(source.getExpectedDeliveryDate());
        newOrder.setRemark(source.getRemark());
        newOrder.setSalesOrderId(source.getSalesOrderId());
        newOrder.setInquiryQuoteId(source.getInquiryQuoteId());
        // 重置状态字段
        newOrder.setStatus(PurchaseOrderStatus.DRAFT);
        newOrder.setCreatedBy(userId);
        // 不复制 approvedBy/approvedAt/finance*/vflag/markerColor (默认值已 OK)

        newOrder = purchaseOrderRepository.save(newOrder);

        // 复制行项目 (fresh instances, 重置 receivedQuantity)
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;
        List<PurchaseOrderItem> newItems = new ArrayList<>();
        for (PurchaseOrderItem srcItem : source.getItems()) {
            PurchaseOrderItem newItem = new PurchaseOrderItem();
            newItem.setPurchaseOrderId(newOrder.getId());
            newItem.setMaterialTypeId(srcItem.getMaterialTypeId());
            newItem.setMaterialName(srcItem.getMaterialName());
            newItem.setQuantity(srcItem.getQuantity());
            newItem.setUnit(srcItem.getUnit());
            newItem.setUnitPrice(srcItem.getUnitPrice());
            newItem.setTaxRate(srcItem.getTaxRate() != null ? srcItem.getTaxRate() : BigDecimal.ZERO);
            newItem.setRemark(srcItem.getRemark());
            newItem.setSpecification(srcItem.getSpecification());
            newItem.setBoxQuantity(srcItem.getBoxQuantity());
            // receivedQuantity 默认 ZERO — 不复制源 receivedQuantity
            newItems.add(newItem);

            BigDecimal lineAmount = newItem.getLineAmount();
            if (lineAmount != null) {
                totalAmount = totalAmount.add(lineAmount);
                BigDecimal lineWithTax = newItem.getLineAmountWithTax();
                if (lineWithTax != null) {
                    taxAmount = taxAmount.add(lineWithTax.subtract(lineAmount));
                }
            }
        }
        purchaseOrderItemRepository.saveAll(newItems);
        newOrder.setTotalAmount(totalAmount);
        newOrder.setTaxAmount(taxAmount);
        newOrder = purchaseOrderRepository.save(newOrder);

        log.info("复制采购订单: source={}({}) → new={}({}), items={}",
                sourceOrderId, source.getOrderNumber(),
                newOrder.getId(), newOrderNumber, newItems.size());
        return newOrder;
    }

    // ==================== 采购入库 ====================

    @Override
    @Transactional
    public PurchaseReceiveRecord createReceiveRecord(String factoryId, CreateReceiveRecordRequest request, Long userId) {
        // Round 11 T1: Canvas Integration Template hook 1 — DB-driven validation
        if (validationRuleEvaluator != null) {
            try {
                validationRuleEvaluator.validate(factoryId, "purchase_receipt", "CREATE",
                        java.util.Map.of(
                            "itemCount", request.getItems() != null ? request.getItems().size() : 0,
                            "supplierId", request.getSupplierId() != null ? request.getSupplierId() : ""));
            } catch (com.cretas.aims.exception.BusinessException e) { throw e; }
            catch (Exception e) { log.warn("Canvas validation non-blocking: {}", e.getMessage()); }
        }

        // 验证供应商
        supplierRepository.findByIdAndFactoryId(request.getSupplierId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("供应商不存在或不属于当前组织"));

        // R23 audit C3: was inline {APPROVED, FINANCE_APPROVED, PARTIAL_RECEIVED}
        // — distinct from PO_RECEIVABLE because this stricter ops-side variant
        // excludes PENDING_FINANCE_REVIEW (only operational receive). Centralized as PO_OPS_RECEIVABLE.
        // BUG-RCV (2026-06-11): hoist order 到方法作用域, 供下方收货行从 PO 行价继承复用 (避免二次加载).
        PurchaseOrder order = null;
        if (request.getPurchaseOrderId() != null && !request.getPurchaseOrderId().isEmpty()) {
            order = getPurchaseOrderById(factoryId, request.getPurchaseOrderId());
            if (order.getStatus() == null
                    || !com.cretas.aims.domain.OrderUsageWhitelists.PO_OPS_RECEIVABLE.contains(order.getStatus())) {
                throw new BusinessException(409, "只有已审批、财务已审核或部分到货状态的订单可以入库")
                        .withHint("请刷新订单列表查看最新状态");
            }

            // PR #173 reviewer follow-up I-2: 早返超收上限校验.
            // 旧行为: cap 校验只在 confirmReceive → updateOrderReceiveStatus (line ~845) 触发,
            // 即用户走完 DRAFT 创建 + QC 流程, 在 confirm 阶段才知道超收 → 体验差.
            // 新行为: 在 DRAFT 创建时同步校验 (基于 request.items 的累计预估),
            // 用户立即看到 "超出可入库上限" 提示, 不用再走质检流程.
            // 注: confirmReceive 也保留 updateOrderReceiveStatus 内的二次校验作为防御
            // (防止 DRAFT → PENDING_QC → CONFIRMED 期间另一并发入库已 commit, 致使原本合法的草稿在
            // confirm 时变非法).
            validateOverReceiveCap(order, request.getItems());
        }

        // 生成入库单号: RCV-YYYYMMDD-序号
        String receiveNumber = generateReceiveNumber(factoryId);

        PurchaseReceiveRecord record = new PurchaseReceiveRecord();
        record.setFactoryId(factoryId);
        record.setReceiveNumber(receiveNumber);
        record.setPurchaseOrderId(request.getPurchaseOrderId());
        record.setSupplierId(request.getSupplierId());
        record.setReceiveDate(request.getReceiveDate());
        record.setWarehouseId(request.getWarehouseId());
        record.setStatus(PurchaseReceiveStatus.DRAFT);
        record.setReceivedBy(userId);
        record.setRemark(request.getRemark());

        record = receiveRecordRepository.save(record);

        // BUG-RCV 修复 (2026-06-11): 收货行单价为空时从 PO 行价继承.
        // 防呆 (客户原话"告诉仓管收多少就行"): 仓管收货不必懂/不必填价, 系统按采购合同自动带价.
        // 否则批次 unit_price=null → 移动加权均价算不出 → 材料成本静默丢失 (财务红线).
        // 单点修在收货行创建处: 批次 (createMaterialBatchFromReceiveItem 读 item.getUnitPrice())
        // + 入库总额自然继承, 无需在多处重复.
        // 注: 用 purchaseOrderItemRepository (与 validateOverReceiveCap 一致), 不用 order.getItems()
        // (@OneToMany LAZY, 跨查询可能未加载).
        Map<String, BigDecimal> poLinePrices = Collections.emptyMap();
        if (order != null) {
            poLinePrices = new HashMap<>();
            for (var poItem : purchaseOrderItemRepository.findByPurchaseOrderId(order.getId())) {
                // PO 行价是合同价, 收货未填价时的权威来源. 同物料多行取首个非空价.
                if (poItem.getMaterialTypeId() != null && poItem.getUnitPrice() != null) {
                    poLinePrices.putIfAbsent(poItem.getMaterialTypeId(), poItem.getUnitPrice());
                }
            }
        }

        // 创建入库行项目
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CreateReceiveRecordRequest.ReceiveItemDTO itemDTO : request.getItems()) {
            PurchaseReceiveItem item = new PurchaseReceiveItem();
            item.setReceiveRecordId(record.getId());
            item.setMaterialTypeId(itemDTO.getMaterialTypeId());
            item.setMaterialName(itemDTO.getMaterialName());
            item.setReceivedQuantity(itemDTO.getReceivedQuantity());
            item.setUnit(itemDTO.getUnit());
            // BUG-RCV: 行价为空 → 继承 PO 行价 (合同价); 无 PO / PO 无此物料价 → 保持 null (诚实, 不伪造 0).
            BigDecimal resolvedPrice = itemDTO.getUnitPrice() != null
                    ? itemDTO.getUnitPrice()
                    : poLinePrices.get(itemDTO.getMaterialTypeId());
            item.setUnitPrice(resolvedPrice);
            item.setQcResult(itemDTO.getQcResult());
            item.setFactoryNumber(itemDTO.getFactoryNumber());
            item.setOriginPlace(itemDTO.getOriginPlace());
            item.setRemark(itemDTO.getRemark());
            record.getItems().add(item);

            if (resolvedPrice != null && itemDTO.getReceivedQuantity() != null) {
                totalAmount = totalAmount.add(itemDTO.getReceivedQuantity().multiply(resolvedPrice));
            }
        }

        record.setTotalAmount(totalAmount);
        record = receiveRecordRepository.save(record);

        // Round 11 T1: Canvas Integration Template hook 2 — persist dynamic fields.
        // Customer-configured fields (运输温度记录, 质检报告附件, 外包装状态) land in
        // cf_* columns on purchase_receive_records. Silent failure must not break
        // the receive record creation.
        if (dynamicFieldService != null && request.getCustomFields() != null && !request.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "purchase_receipt", record.getId(), request.getCustomFields());
            } catch (Exception e) {
                log.warn("Canvas dynamic fields save failed for purchase receive {}: {}", record.getId(), e.getMessage());
            }
        }

        // Round 11 T1: Canvas Integration Template hook 3 — publish event for trigger chains.
        // Fires on DRAFT creation; distinct from MaterialReceivedEvent which fires on
        // CONFIRMED state via confirmReceive. Factories can now react to receive-draft
        // creation (e.g., auto-create QC sampling task).
        try {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.PurchaseReceiveCreatedEvent(
                    this, factoryId, record.getId(), record.getReceiveNumber(),
                    record.getSupplierId(), record.getPurchaseOrderId(), record.getTotalAmount()));
        } catch (Exception e) {
            log.warn("Publish PurchaseReceiveCreatedEvent failed for {}: {}", record.getId(), e.getMessage());
        }

        log.info("创建入库单: factoryId={}, receiveNumber={}, items={}", factoryId, receiveNumber, request.getItems().size());
        return record;
    }

    @Override
    @Transactional
    public PurchaseReceiveRecord confirmReceive(String factoryId, String receiveId, Long userId) {
        PurchaseReceiveRecord record = getReceiveRecordById(factoryId, receiveId);
        if (record.getStatus() != PurchaseReceiveStatus.DRAFT && record.getStatus() != PurchaseReceiveStatus.PENDING_QC) {
            throw new BusinessException(409, "只有草稿或待质检状态的入库单可以确认")
                    .withHint("请刷新入库单列表查看最新状态");
        }

        // 🔒 doomed-tx 修复 (六扇门 2026-06-15, #774 族复发): 超收上限 fail-fast.
        // 旧链路: createMaterialBatch (DB 写) → updateOrderReceiveStatus 内才校验超收 → 抛
        // BusinessException → @Transactional 已有 pending 写 + 内层 @Transactional (event
        // listener / recalculateMovingAvgPrice / recordPayable) 一旦 join 当前 tx 并被标
        // rollback-only → commit 阶段 UnexpectedRollbackException (500) 而非干净 409.
        // 新链路: 在任何 DB mutation 之前先校验超收, 事务未被 doom, 异常干净 propagate → 409.
        // (createReceiveRecord 已有早返校验, 但 DRAFT → PENDING_QC 期间并发入库 commit 可使
        //  原合法草稿在 confirm 时变非法; confirm 时再校验一次保证 fail-fast 不依赖创建期。)
        if (record.getPurchaseOrderId() != null) {
            validateOverReceiveCapForConfirm(record);
        }

        // 确认入库：为每个行项目创建 MaterialBatch
        // 注意: createMaterialBatchFromReceiveItem 直接 new MaterialBatch + repo.save,
        // 绕开了 MaterialBatchService.createMaterialBatch 路径上的 updateMovingAvgPrice。
        // 必须在此显式调用 recalculateMovingAvgPrice, 否则 raw_material_types.moving_avg_price
        // 永远不会被采购入库流程更新, 三价对比的"移动均价"列将永远为 -。
        for (PurchaseReceiveItem item : record.getItems()) {
            MaterialBatch batch = createMaterialBatchFromReceiveItem(factoryId, record, item, userId);
            item.setMaterialBatchId(batch.getId());
            materialBatchService.recalculateMovingAvgPrice(
                    item.getMaterialTypeId(),
                    item.getReceivedQuantity(),
                    item.getUnitPrice(),
                    batch.getId());
        }

        // 更新入库单状态
        record.setStatus(PurchaseReceiveStatus.CONFIRMED);

        // 如果关联采购订单，更新订单的已收货数量和状态
        if (record.getPurchaseOrderId() != null) {
            updateOrderReceiveStatus(record);
        }

        record = receiveRecordRepository.save(record);
        if (bomPriceAdjustmentService != null) {
            try {
                bomPriceAdjustmentService.generateFromReceive(factoryId, record);
            } catch (Exception e) {
                log.warn("BOM price adjustment proposal generation failed: receiveId={}, error={}",
                        receiveId, e.getMessage());
            }
        }
        log.info("确认入库: receiveId={}, receiveNumber={}, batchesCreated={}", receiveId, record.getReceiveNumber(), record.getItems().size());

        // 自动创建应付账款（采购入库 → AP_INVOICE）
        // 🔒 doomed-tx 修复 (2026-07-02): 必须用 recordPayableIfAbsent (幂等、不抛)。
        // 旧代码用 recordPayable 并 try/catch(BusinessException) 吞"重复挂账"异常 —— 但 recordPayable
        // 是 @Transactional，对已挂账的 PO 抛 409 时事务已被标记 rollback-only，catch 也救不回，外层
        // commit 抛 UnexpectedRollbackException → doomed-tx 兜底转通用 409 → 同一 PO 第 2 次分批入库
        // 永久无法确认。recordPayableIfAbsent 对"已存在/金额缺失/状态不允许"等预期条件返回 existing/null
        // 而非抛异常，事务从不被 doom，收货入库不被应付侧阻塞。
        if (record.getPurchaseOrderId() != null) {
            try {
                PurchaseOrder order = purchaseOrderRepository.findById(record.getPurchaseOrderId()).orElse(null);
                if (order != null && order.getSupplierId() != null && order.getTotalAmount() != null) {
                    ArApTransaction ap = arApService.recordPayableIfAbsent(factoryId, order.getSupplierId(), order.getId(),
                            order.getTotalAmount(), LocalDate.now().plusDays(30), userId,
                            "采购入库自动挂账-" + record.getReceiveNumber());
                    if (ap != null) {
                        log.info("自动应付挂账: orderId={}, amount={}, transactionId={}",
                                order.getId(), order.getTotalAmount(), ap.getId());
                    }
                }
            } catch (Exception e) {
                // 幂等方法不会因"重复/状态"抛异常; 走到这里是真正的意外错误。收货入库不阻塞, 记日志。
                log.error("应付自动挂账失败(非预期): receiveId={}", receiveId, e);
            }
        }

        // 发布物料收货事件 → 触发供应链联动（检查PP原料到齐状态）
        for (PurchaseReceiveItem item : record.getItems()) {
            try {
                BigDecimal qty = item.getReceivedQuantity() != null ? item.getReceivedQuantity() : BigDecimal.ZERO;
                applicationEventPublisher.publishEvent(new MaterialReceivedEvent(
                    this, factoryId, record.getPurchaseOrderId(),
                    item.getMaterialTypeId(), qty));
                log.info("已发布MaterialReceivedEvent: material={}, qty={}", item.getMaterialTypeId(), qty);
            } catch (Exception e) {
                log.error("发布MaterialReceivedEvent失败(不影响主流程): material={}", item.getMaterialTypeId(), e);
            }
        }

        // SP6 — 生成采购异常单（超收/少收检查）
        // fail-soft: 异常单生成失败不阻塞入库确认主流程
        if (purchaseExceptionService != null && record.getPurchaseOrderId() != null) {
            List<PurchaseOrderItem> poItems =
                    purchaseOrderItemRepository.findByPurchaseOrderId(record.getPurchaseOrderId());
            // 按 materialTypeId 建索引，快速查 PO 计划数量
            Map<String, PurchaseOrderItem> poItemMap = new HashMap<>();
            for (PurchaseOrderItem pi : poItems) {
                if (pi.getMaterialTypeId() != null) {
                    poItemMap.put(pi.getMaterialTypeId(), pi);
                }
            }

            // D-6 责任绑定：取 PO 创建人作为异常单责任人
            Long ownerUserId = null;
            String ownerName = null;
            try {
                PurchaseOrder po = purchaseOrderRepository.findById(record.getPurchaseOrderId()).orElse(null);
                if (po != null && po.getCreatedBy() != null) {
                    ownerUserId = po.getCreatedBy();
                    if (userRepository != null) {
                        ownerName = userRepository.findById(ownerUserId)
                                .map(u -> u.getUsername())
                                .orElse(null);
                    }
                }
            } catch (Exception e) {
                log.warn("[SP6] 查询 PO 责任人失败（不影响异常单生成）: poId={}, error={}",
                        record.getPurchaseOrderId(), e.getMessage());
            }

            final Long finalOwnerUserId = ownerUserId;
            final String finalOwnerName = ownerName;
            for (PurchaseReceiveItem item : record.getItems()) {
                try {
                    PurchaseOrderItem poItem =
                            item.getMaterialTypeId() != null ? poItemMap.get(item.getMaterialTypeId()) : null;
                    BigDecimal poQty = poItem != null ? poItem.getQuantity() : null;
                    purchaseExceptionService.generateExceptionsForReceive(
                            factoryId,
                            record.getId(),
                            record.getPurchaseOrderId(),
                            record.getSupplierId(),
                            item.getMaterialTypeId(),
                            item.getMaterialName(),
                            poQty,
                            item.getReceivedQuantity(),
                            item.getUnit(),
                            userId,
                            finalOwnerUserId,
                            finalOwnerName);
                } catch (Exception e) {
                    log.warn("[SP6] 生成采购异常单失败（不影响入库主流程）: receiveId={}, material={}, error={}",
                            receiveId, item.getMaterialTypeId(), e.getMessage());
                }
            }
        }

        // 收货差异通知采购人（超收/少收自动通知 PO 创建人）
        // fail-soft: 通知失败不阻塞入库确认主流程
        if (notificationService != null && record.getPurchaseOrderId() != null) {
            try {
                notifyPurchaserOnReceiveVariance(factoryId, record);
            } catch (Exception e) {
                log.warn("[VARIANCE-NOTIFY] 收货差异通知失败（不影响入库主流程）: receiveId={}, error={}",
                        record.getId(), e.getMessage());
            }
        }

        return record;
    }

    /**
     * 收货数量差异通知采购人.
     *
     * <p>逐行比较入库单实收数量 vs 采购单计划数量，有差异（超收/少收）则通过
     * {@link com.cretas.aims.service.notification.NotificationService#notifyUser} 通知
     * PO 创建人（即采购经手人）.
     *
     * <p>仅在关联了采购订单的入库单上生效；独立入库单（无 PO）不触发.
     * 完全 fail-soft — 任何内部异常均记录 WARN 并静默跳过，不影响主流程.
     */
    private void notifyPurchaserOnReceiveVariance(String factoryId, PurchaseReceiveRecord record) {
        PurchaseOrder po = purchaseOrderRepository.findById(record.getPurchaseOrderId()).orElse(null);
        if (po == null || po.getCreatedBy() == null) {
            log.debug("[VARIANCE-NOTIFY] PO 未找到或无创建人: poId={}", record.getPurchaseOrderId());
            return;
        }

        List<PurchaseOrderItem> poItems =
                purchaseOrderItemRepository.findByPurchaseOrderId(record.getPurchaseOrderId());
        Map<String, PurchaseOrderItem> poItemMap = new HashMap<>();
        for (PurchaseOrderItem pi : poItems) {
            if (pi.getMaterialTypeId() != null) {
                poItemMap.put(pi.getMaterialTypeId(), pi);
            }
        }

        for (PurchaseReceiveItem item : record.getItems()) {
            PurchaseOrderItem poItem =
                    item.getMaterialTypeId() != null ? poItemMap.get(item.getMaterialTypeId()) : null;
            if (poItem == null || poItem.getQuantity() == null) {
                continue; // 未关联 PO 行，跳过差异检查
            }

            BigDecimal ordered = poItem.getQuantity();
            BigDecimal received = item.getReceivedQuantity() != null
                    ? item.getReceivedQuantity() : BigDecimal.ZERO;
            int cmp = received.compareTo(ordered);
            if (cmp == 0) {
                continue; // 数量一致，无差异
            }

            String materialName = item.getMaterialName() != null ? item.getMaterialName() : item.getMaterialTypeId();
            String unit = item.getUnit() != null ? item.getUnit() : "";
            BigDecimal variance = received.subtract(ordered);
            String varianceSign = cmp > 0 ? "+" : "";
            String type = cmp > 0 ? "超收" : "少收";

            String title = String.format("入库%s提醒 — %s", type, materialName);
            String body = String.format(
                    "采购单 %s 入库单 %s 存在%s差异。\n品名: %s\n采购量: %s %s\n实收量: %s %s\n差异: %s%s %s",
                    po.getOrderNumber(),
                    record.getReceiveNumber(),
                    type,
                    materialName,
                    ordered.stripTrailingZeros().toPlainString(), unit,
                    received.stripTrailingZeros().toPlainString(), unit,
                    varianceSign, variance.stripTrailingZeros().toPlainString(), unit);

            notificationService.notifyUser(factoryId, po.getCreatedBy(), title, body);
            log.info("[VARIANCE-NOTIFY] {}通知已发送: poId={}, material={}, ordered={}, received={}, userId={}",
                    type, po.getId(), materialName, ordered, received, po.getCreatedBy());
        }
    }

    @Override
    public PurchaseReceiveRecord getReceiveRecordById(String factoryId, String receiveId) {
        PurchaseReceiveRecord record = receiveRecordRepository.findById(receiveId)
                .orElseThrow(() -> new ResourceNotFoundException("入库单不存在"));
        if (!record.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权访问该入库单")
                    .withHint("当前入库单不属于该工厂, 无法访问");
        }
        return record;
    }

    @Override
    public PageResponse<PurchaseReceiveRecord> getReceiveRecords(String factoryId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PurchaseReceiveRecord> result = receiveRecordRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageRequest);
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    public List<PurchaseReceiveRecord> getReceiveRecordsByOrder(String purchaseOrderId) {
        return receiveRecordRepository.findByPurchaseOrderId(purchaseOrderId);
    }

    @Override
    public Map<String, Object> getCumulativeReceived(String factoryId, String orderId) {
        // Issue #787 follow-up to PR #782 / #775: backend aggregate replaces FE-only page-rows聚合.
        // getPurchaseOrderById already enforces factory isolation (BusinessException 403 if cross-factory).
        PurchaseOrder order = getPurchaseOrderById(factoryId, orderId);
        List<PurchaseOrderItem> items = purchaseOrderItemRepository.findByPurchaseOrderId(order.getId());

        BigDecimal plannedTotal = BigDecimal.ZERO;
        BigDecimal cumulativeReceived = BigDecimal.ZERO;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (PurchaseOrderItem item : items) {
            BigDecimal planned = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            BigDecimal received = item.getReceivedQuantity() != null ? item.getReceivedQuantity() : BigDecimal.ZERO;
            BigDecimal pending = planned.subtract(received);
            plannedTotal = plannedTotal.add(planned);
            cumulativeReceived = cumulativeReceived.add(received);

            // LinkedHashMap to preserve field order for FE deterministic shape.
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("materialId", item.getMaterialTypeId());
            line.put("materialName", item.getMaterialName());
            line.put("plannedQty", planned);
            line.put("receivedQty", received);
            line.put("pendingQty", pending);
            line.put("unit", item.getUnit());
            lines.add(line);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("poId", order.getId());
        result.put("orderNumber", order.getOrderNumber());
        result.put("plannedTotal", plannedTotal);
        result.put("cumulativeReceived", cumulativeReceived);
        result.put("lines", lines);
        return result;
    }

    @Override
    public List<Map<String, Object>> getOrderReceiveSequence(String factoryId, String orderId) {
        // 单元 G (F006 R-B3): 分次收货时序明细 — 客户张权 "第一次收了多少第二次收了多少更直观".
        // getPurchaseOrderById 已强制工厂隔离 (BusinessException 403 if cross-factory).
        PurchaseOrder order = getPurchaseOrderById(factoryId, orderId);

        List<PurchaseReceiveRecord> records = receiveRecordRepository
                .findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(factoryId, order.getId());

        List<Map<String, Object>> sequence = new ArrayList<>();
        int seq = 1;
        for (PurchaseReceiveRecord record : records) {
            BigDecimal totalQuantity = BigDecimal.ZERO;
            List<Map<String, Object>> items = new ArrayList<>();
            for (PurchaseReceiveItem item : record.getItems() != null ? record.getItems() : List.<PurchaseReceiveItem>of()) {
                BigDecimal qty = item.getReceivedQuantity() != null ? item.getReceivedQuantity() : BigDecimal.ZERO;
                totalQuantity = totalQuantity.add(qty);

                Map<String, Object> itemMap = new LinkedHashMap<>();
                itemMap.put("materialName", item.getMaterialName());
                itemMap.put("quantity", qty);
                itemMap.put("unit", item.getUnit());
                items.add(itemMap);
            }

            // createdByName best-effort: receivedByUser 关联 (LAZY) 若已加载则取 fullName, 否则 null.
            String createdByName = null;
            com.cretas.aims.entity.User user = record.getReceivedByUser();
            if (user != null) {
                createdByName = user.getFullName();
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("seq", seq++);
            entry.put("receiveId", record.getId());
            entry.put("receiveNumber", record.getReceiveNumber());
            entry.put("receiveDate", record.getReceiveDate());
            entry.put("createdAt", record.getCreatedAt());
            entry.put("createdByName", createdByName);
            entry.put("totalQuantity", totalQuantity);
            entry.put("items", items);
            sequence.add(entry);
        }
        return sequence;
    }

    // ==================== 统计 ====================

    @Override
    public Map<String, Object> getPurchaseStatistics(String factoryId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        // 本月采购统计
        LocalDate now = LocalDate.now();
        LocalDate monthStart = now.withDayOfMonth(1);
        List<PurchaseOrder> monthlyOrders = purchaseOrderRepository.findByFactoryIdAndDateRange(factoryId, monthStart, now);

        long totalOrders = monthlyOrders.size();
        long pendingOrders = monthlyOrders.stream()
                .filter(o -> o.getStatus() == PurchaseOrderStatus.SUBMITTED)
                .count();
        BigDecimal monthlyAmount = monthlyOrders.stream()
                .filter(o -> o.getStatus() != PurchaseOrderStatus.CANCELLED)
                .map(PurchaseOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        stats.put("monthlyOrderCount", totalOrders);
        stats.put("pendingApprovalCount", pendingOrders);
        stats.put("monthlyPurchaseAmount", monthlyAmount);

        return stats;
    }

    // ==================== 开始采购 — 从 SO 生成采购建议 ====================

    @Override
    @Transactional(readOnly = true)
    public PurchaseSuggestionResponse generatePurchaseSuggestion(String factoryId, String salesOrderId) {
        // 1. 加载 SO（多租户隔离）
        if (salesOrderRepository == null) {
            throw new BusinessException("销售订单服务不可用");
        }
        SalesOrder so = salesOrderRepository.findById(salesOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("SalesOrder", "id", salesOrderId));
        if (!factoryId.equals(so.getFactoryId())) {
            throw new BusinessException(403, "无权访问此销售订单");
        }

        // 2. 加载 SO 行项目
        List<SalesOrderItem> soItems = salesOrderItemRepository != null
                ? salesOrderItemRepository.findBySalesOrderId(salesOrderId)
                : List.of();
        if (soItems.isEmpty()) {
            return PurchaseSuggestionResponse.builder()
                    .salesOrderId(salesOrderId)
                    .salesOrderNumber(so.getOrderNumber())
                    .customerName(so.getCustomerName())
                    .hasBom(false)
                    .items(List.of())
                    .build();
        }

        // 3. BOM 展开（共享逻辑）：每个 SO 行 × BOM 原料用量，按 materialTypeId 合并
        Map<String, MaterialAccumulator> accumulators = new LinkedHashMap<>();
        boolean hasBom = expandSoItemsInto(factoryId, so.getOrderNumber(), soItems, accumulators);

        // 4. 查每种原料当前库存，计算净需求（共享逻辑）
        List<PurchaseSuggestionResponse.SuggestionItem> resultItems = new ArrayList<>();
        for (MaterialAccumulator acc : accumulators.values()) {
            BigDecimal totalRequired = acc.requiredQty.setScale(4, BigDecimal.ROUND_HALF_UP);
            BigDecimal currentStock = resolveCurrentStock(factoryId, acc.materialTypeId);
            BigDecimal netRequired = computeNetRequired(totalRequired, currentStock);

            resultItems.add(PurchaseSuggestionResponse.SuggestionItem.builder()
                    .materialTypeId(acc.materialTypeId)
                    .materialName(acc.materialName)
                    .materialCategory(acc.materialCategory)
                    .sourceProductName(acc.sourceProductName)
                    .requiredQuantity(totalRequired)
                    .unit(acc.unit)
                    .currentStock(currentStock)
                    .netRequired(netRequired)
                    .stockSufficient(netRequired.compareTo(BigDecimal.ZERO) == 0)
                    .referenceUnitPrice(acc.referenceUnitPrice)
                    .build());
        }

        return PurchaseSuggestionResponse.builder()
                .salesOrderId(salesOrderId)
                .salesOrderNumber(so.getOrderNumber())
                .customerName(so.getCustomerName())
                .hasBom(hasBom)
                .items(resultItems)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseSuggestionMultiResponse generatePurchaseSuggestionMulti(
            String factoryId, List<String> salesOrderIds) {
        // 0. 入参校验
        if (salesOrderRepository == null) {
            throw new BusinessException("销售订单服务不可用");
        }
        if (salesOrderIds == null || salesOrderIds.isEmpty()) {
            throw new BusinessException("请至少选择一张销售订单")
                    .withHint("勾选一张或多张销售订单后再合并生成采购建议");
        }
        // 去重 (用户可能误重复追加同一张 SO), 保留首次出现顺序.
        List<String> distinctIds = salesOrderIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (distinctIds.isEmpty()) {
            throw new BusinessException("请至少选择一张销售订单")
                    .withHint("勾选一张或多张销售订单后再合并生成采购建议");
        }

        // 1. 跨所有 SO 累加需求 (库存只在最后扣一次).
        Map<String, MaterialAccumulator> accumulators = new LinkedHashMap<>();
        List<String> orderedIds = new ArrayList<>();
        List<String> orderedNumbers = new ArrayList<>();
        Set<String> customerNames = new LinkedHashSet<>();
        List<PurchaseSuggestionMultiResponse.SoSummary> soSummaries = new ArrayList<>();
        boolean anyBom = false;

        for (String soId : distinctIds) {
            SalesOrder so = salesOrderRepository.findById(soId)
                    .orElseThrow(() -> new ResourceNotFoundException("SalesOrder", "id", soId));
            // 多租户隔离: 任一 SO 不属本厂 → 403 (与单 SO 行为一致).
            if (!factoryId.equals(so.getFactoryId())) {
                throw new BusinessException(403, "无权访问此销售订单 " + soId);
            }

            orderedIds.add(soId);
            orderedNumbers.add(so.getOrderNumber());
            if (so.getCustomerName() != null) customerNames.add(so.getCustomerName());

            List<SalesOrderItem> soItems = salesOrderItemRepository != null
                    ? salesOrderItemRepository.findBySalesOrderId(soId)
                    : List.of();

            // 展开本 SO 的 BOM, 累加进共享 accumulators (跨 SO 同 materialTypeId 合并).
            boolean soHasBom = !soItems.isEmpty()
                    && expandSoItemsInto(factoryId, so.getOrderNumber(), soItems, accumulators);
            if (soHasBom) anyBom = true;

            // 诚实暴露: 该 SO 无行项目 / 无 BOM → hasBom=false, 不静默跳过.
            soSummaries.add(PurchaseSuggestionMultiResponse.SoSummary.builder()
                    .salesOrderId(soId)
                    .salesOrderNumber(so.getOrderNumber())
                    .customerName(so.getCustomerName())
                    .hasBom(soHasBom)
                    .build());
        }

        // 2. 合并后统一扣一次库存 (库存只有一份, 净需求 = Σrequired − 当前库存).
        List<PurchaseSuggestionMultiResponse.SuggestionItem> resultItems = new ArrayList<>();
        for (MaterialAccumulator acc : accumulators.values()) {
            BigDecimal totalRequired = acc.requiredQty.setScale(4, BigDecimal.ROUND_HALF_UP);
            BigDecimal currentStock = resolveCurrentStock(factoryId, acc.materialTypeId);
            BigDecimal netRequired = computeNetRequired(totalRequired, currentStock);

            resultItems.add(PurchaseSuggestionMultiResponse.SuggestionItem.builder()
                    .materialTypeId(acc.materialTypeId)
                    .materialName(acc.materialName)
                    .materialCategory(acc.materialCategory)
                    .sourceProductName(acc.sourceProductName)
                    .sourceSalesOrderNumbers(new ArrayList<>(acc.sourceSalesOrderNumbers))
                    .requiredQuantity(totalRequired)
                    .unit(acc.unit)
                    .currentStock(currentStock)
                    .netRequired(netRequired)
                    .stockSufficient(netRequired.compareTo(BigDecimal.ZERO) == 0)
                    .referenceUnitPrice(acc.referenceUnitPrice)
                    .build());
        }

        return PurchaseSuggestionMultiResponse.builder()
                .salesOrderIds(orderedIds)
                .salesOrderNumbers(orderedNumbers)
                .customerNames(new ArrayList<>(customerNames))
                .hasBom(anyBom)
                .items(resultItems)
                .soSummaries(soSummaries)
                .build();
    }

    /**
     * 共享 BOM 展开 + 跨 SO 聚合逻辑 (单 SO 与多 SO 共用).
     *
     * <p>对一张 SO 的所有行项目展开 BOM (优先 bom_recipe_items, 回退 legacy bom_items),
     * 按 materialTypeId 累加进传入的 {@code accumulators} —— 多次调用 (多 SO) 会跨 SO 合并同物料。
     * <b>本方法不扣库存</b>: 库存只在调用方汇总后统一扣一次, 避免多 SO 各扣一次重复计算。
     *
     * @return 本次调用是否至少展开出一条 BOM 明细 (该 SO 的 hasBom)
     */
    private boolean expandSoItemsInto(String factoryId, String salesOrderNumber,
            List<SalesOrderItem> soItems, Map<String, MaterialAccumulator> accumulators) {
        boolean hasBom = false;

        for (SalesOrderItem soItem : soItems) {
            if (soItem.getProductTypeId() == null) continue;
            BigDecimal soQty = soItem.getQuantity() != null ? soItem.getQuantity() : BigDecimal.ONE;
            String soProductName = soItem.getProductName() != null ? soItem.getProductName() : "";

            // #748 口径统一: 优先读 bom_recipe_items (新表, 同财务成本拆分源).
            // 取产品当前 ACTIVE + is_current=TRUE 的 recipe — 与 BomRecipeService.getCurrentRecipe 完全一致.
            List<BomRecipeItem> recipeItems = loadCurrentRecipeItems(factoryId, soItem.getProductTypeId());
            if (recipeItems != null && !recipeItems.isEmpty()) {
                boolean expandedAny = false;
                for (BomRecipeItem ri : recipeItems) {
                    if (ri.getMaterialTypeId() == null) continue;
                    // actualQuantity 已按出成率折算 (写库时算或运行时算), 是"每单位产品需要的原料量".
                    // 若持久值为空 (旧 recipe 未回填), 用 calculateActualQuantity() 实时折算 (同 entity 公式).
                    BigDecimal actualQtyPerUnit = ri.getActualQuantity();
                    if (actualQtyPerUnit == null) actualQtyPerUnit = ri.calculateActualQuantity();
                    if (actualQtyPerUnit == null || actualQtyPerUnit.compareTo(BigDecimal.ZERO) <= 0) continue;

                    BigDecimal required = actualQtyPerUnit.multiply(soQty).setScale(4, BigDecimal.ROUND_HALF_UP);
                    accumulateMaterial(accumulators, ri.getMaterialTypeId(), required, salesOrderNumber,
                            ri.getMaterialName() != null ? ri.getMaterialName() : "未知原料",
                            ri.getMaterialCategory() != null ? ri.getMaterialCategory() : "RAW",
                            soProductName, ri.getUnit(), ri.getUnitPrice());
                    expandedAny = true;
                }
                if (expandedAny) hasBom = true;
                // recipe 存在即以它为准 — 不再回退 legacy (避免双源重复计料).
                continue;
            }

            // 向后兼容: 该产品无 ACTIVE recipe → 回退 legacy bom_items.
            List<BomItem> bomItems = bomItemRepository
                    .findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(
                            factoryId, soItem.getProductTypeId());
            if (bomItems.isEmpty()) continue;

            hasBom = true;

            for (BomItem bom : bomItems) {
                if (bom.getMaterialTypeId() == null) continue;
                // actualQuantity 已按出成率折算，是"每单位产品需要的原料量"
                BigDecimal actualQtyPerUnit = bom.getActualQuantity();
                if (actualQtyPerUnit == null || actualQtyPerUnit.compareTo(BigDecimal.ZERO) <= 0) continue;

                BigDecimal required = actualQtyPerUnit.multiply(soQty).setScale(4, BigDecimal.ROUND_HALF_UP);
                accumulateMaterial(accumulators, bom.getMaterialTypeId(), required, salesOrderNumber,
                        bom.getMaterialName() != null ? bom.getMaterialName() : "未知原料",
                        bom.getMaterialCategory() != null ? bom.getMaterialCategory() : "RAW",
                        soProductName, bom.getUnit(), bom.getUnitPrice());
            }
        }

        return hasBom;
    }

    /**
     * 把一条 BOM 展开结果累加进 accumulators (按 materialTypeId 合并需求量 + 记录来源 SO).
     * 第一次见到某 materialTypeId 时记录其名称/分类/单位/参考价 (后续同物料沿用首次模板)。
     */
    private void accumulateMaterial(Map<String, MaterialAccumulator> accumulators,
            String matId, BigDecimal required, String salesOrderNumber,
            String materialName, String materialCategory, String sourceProductName,
            String unit, BigDecimal referenceUnitPrice) {
        MaterialAccumulator acc = accumulators.computeIfAbsent(matId, k -> {
            MaterialAccumulator a = new MaterialAccumulator();
            a.materialTypeId = matId;
            a.materialName = materialName;
            a.materialCategory = materialCategory;
            a.sourceProductName = sourceProductName;
            a.unit = unit;
            a.referenceUnitPrice = referenceUnitPrice;
            return a;
        });
        acc.requiredQty = acc.requiredQty.add(required);
        if (salesOrderNumber != null) acc.sourceSalesOrderNumbers.add(salesOrderNumber);
    }

    /** 查某物料当前可用库存 (null-safe, repo 缺失返 0). */
    private BigDecimal resolveCurrentStock(String factoryId, String materialTypeId) {
        BigDecimal currentStock = materialBatchRepository != null
                ? materialBatchRepository.sumAvailableQuantityByMaterialType(factoryId, materialTypeId)
                : BigDecimal.ZERO;
        return currentStock != null ? currentStock : BigDecimal.ZERO;
    }

    /** 净需求 = max(required − stock, 0), scale 4. */
    private BigDecimal computeNetRequired(BigDecimal totalRequired, BigDecimal currentStock) {
        BigDecimal netRequired = totalRequired.subtract(currentStock);
        if (netRequired.compareTo(BigDecimal.ZERO) < 0) netRequired = BigDecimal.ZERO;
        return netRequired.setScale(4, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * 共享累加器 — 跨 SO 按 materialTypeId 合并需求量 + 来源 SO 号 (去重保序).
     * 库存与净需求由调用方在汇总后统一计算 (本结构只攒 required 与来源).
     */
    private static class MaterialAccumulator {
        String materialTypeId;
        String materialName;
        String materialCategory;
        String sourceProductName;
        String unit;
        BigDecimal referenceUnitPrice;
        BigDecimal requiredQty = BigDecimal.ZERO;
        /** LinkedHashSet: 去重 + 保留首次出现顺序 (同 SO 多产品共用同物料只记一次 SO 号). */
        java.util.LinkedHashSet<String> sourceSalesOrderNumbers = new java.util.LinkedHashSet<>();
    }

    /**
     * #748 口径统一: 取产品当前 ACTIVE + is_current=TRUE recipe 的配方项.
     *
     * <p>与 {@code BomRecipeService.getCurrentRecipe} 走同一查询
     * ({@code findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(ACTIVE)}),
     * 保证采购建议净需求 = 财务成本拆分用料口径一致.
     *
     * <p>repo 未注入 (老 ApplicationContext) 或无 recipe → 返 null (调用方回退 legacy).
     */
    private List<BomRecipeItem> loadCurrentRecipeItems(String factoryId, String productTypeId) {
        if (bomRecipeRepository == null || bomRecipeItemRepository == null) {
            return null;
        }
        java.util.Optional<com.cretas.aims.entity.bom.BomRecipe> recipeOpt =
                bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                        factoryId, productTypeId, com.cretas.aims.entity.bom.BomRecipe.Status.ACTIVE);
        if (recipeOpt.isEmpty()) {
            return null;
        }
        // 显式按 recipeId 查 items (LAZY 集合在事务外可能 detach; 直查 repo 稳妥).
        return bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(recipeOpt.get().getId());
    }

    // ==================== 三价对比 ====================

    @Override
    public List<MaterialPriceComparisonDTO> getOrderPriceComparison(String factoryId, String orderId) {
        PurchaseOrder order = getPurchaseOrderById(factoryId, orderId);
        List<PurchaseOrderItem> items = purchaseOrderItemRepository.findByPurchaseOrderId(order.getId());

        // Sprint2-J: 取 factory 当前规则一次, 转给每行 — 避免行级 N+1 规则查询
        PurchaseOrderApprovalRule rule = resolveActiveRule(factoryId);
        BigDecimal threshold = rule != null && rule.getPriceVarianceThreshold() != null
                ? rule.getPriceVarianceThreshold() : resolvePriceAlertThreshold(factoryId);

        return items.stream()
                .map(item -> buildPriceComparison(factoryId, item.getMaterialTypeId(),
                        item.getMaterialName(), item.getUnitPrice(), threshold))
                .collect(Collectors.toList());
    }

    @Override
    public MaterialPriceComparisonDTO getMaterialPriceInfo(String factoryId, String materialTypeId, BigDecimal currentPrice) {
        RawMaterialType materialType = materialTypeRepository.findById(materialTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("原料类型不存在: " + materialTypeId));
        if (!materialType.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权访问该原料类型")
                    .withHint("当前原料类型不属于该工厂, 无法访问");
        }
        PurchaseOrderApprovalRule rule = resolveActiveRule(factoryId);
        BigDecimal threshold = rule != null && rule.getPriceVarianceThreshold() != null
                ? rule.getPriceVarianceThreshold() : resolvePriceAlertThreshold(factoryId);
        return buildPriceComparison(factoryId, materialTypeId, materialType.getName(), currentPrice, threshold);
    }

    /**
     * 构建单个原料的三价对比数据.
     *
     * <p>2026-05-15 (Day 8-9): 新增 {@code dataSourceHint} 字段, 解释为何某价 null.
     * 客户原话 (六扇门第三次 May7): "三家对比没有 — 可能是一些数据的 bug". 实际是
     * 移动均价基于入库 (raw_material_types.moving_avg_price 来自历次入库累积),
     * BOM 标准价基于 BOM 配置 — 新原料 / 未入库 / 未配 BOM 时这两个字段必然 null.
     * 此前 UI 显示"-", 客户误以为是 bug. 现在 dataSourceHint 明确告诉用户原因.
     */
    private MaterialPriceComparisonDTO buildPriceComparison(String factoryId, String materialTypeId,
                                                             String materialName, BigDecimal currentPrice,
                                                             BigDecimal alertThreshold) {
        BigDecimal effectiveThreshold = alertThreshold != null ? alertThreshold : resolvePriceAlertThreshold(factoryId);
        // 1. 查询原料类型获取移动平均价和基础信息
        RawMaterialType materialType = materialTypeRepository.findById(materialTypeId).orElse(null);
        BigDecimal movingAvgPrice = materialType != null ? materialType.getMovingAvgPrice() : null;
        String materialCode = materialType != null ? materialType.getCode() : null;
        String unit = materialType != null ? materialType.getUnit() : null;
        String name = materialName != null ? materialName : (materialType != null ? materialType.getName() : materialTypeId);

        // 2. 查询BOM获取标准单价（如果该原料出现在多个产品的BOM中，取平均值）
        List<BomItem> bomItems = bomItemRepository.findByFactoryIdAndMaterialTypeIdAndDeletedAtIsNull(factoryId, materialTypeId);
        BigDecimal bomStandardPrice = null;
        String bomProductNames = null;

        if (!bomItems.isEmpty()) {
            // 过滤掉没有单价的BOM项
            List<BomItem> pricedItems = bomItems.stream()
                    .filter(b -> b.getUnitPrice() != null && b.getUnitPrice().compareTo(BigDecimal.ZERO) > 0)
                    .collect(Collectors.toList());

            if (!pricedItems.isEmpty()) {
                BigDecimal sum = pricedItems.stream()
                        .map(BomItem::getUnitPrice)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                bomStandardPrice = sum.divide(new BigDecimal(pricedItems.size()), 4, BigDecimal.ROUND_HALF_UP);
            }

            // 收集关联的产品名称
            bomProductNames = bomItems.stream()
                    .map(BomItem::getProductName)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.joining(", "));
            if (bomProductNames.isEmpty()) {
                bomProductNames = null;
            }
        }

        // 3. 计算偏差百分比
        BigDecimal varianceFromBom = calculateVariance(currentPrice, bomStandardPrice);
        BigDecimal varianceFromAvg = calculateVariance(currentPrice, movingAvgPrice);

        // 4. 判断是否价格异常 (Sprint2-J: 用 caller 传入的 per-factory 阈值, 兜底 10%)
        boolean alert = false;
        if (varianceFromBom != null && varianceFromBom.abs().compareTo(effectiveThreshold) > 0) {
            alert = true;
        }
        if (varianceFromAvg != null && varianceFromAvg.abs().compareTo(effectiveThreshold) > 0) {
            alert = true;
        }

        // 5. 数据源诊断 — 帮 F006 仓管区分 "数据 bug" 和 "业务正常空态"
        String dataSourceHint = PurchaseServiceImpl.buildPriceDataSourceHint(bomStandardPrice, movingAvgPrice);

        return MaterialPriceComparisonDTO.builder()
                .materialTypeId(materialTypeId)
                .materialName(name)
                .materialCode(materialCode)
                .unit(unit)
                .bomStandardPrice(bomStandardPrice)
                .movingAvgPrice(movingAvgPrice)
                .currentPrice(currentPrice)
                .varianceFromBom(varianceFromBom)
                .varianceFromAvg(varianceFromAvg)
                .priceAlert(alert)
                .bomProductNames(bomProductNames)
                .dataSourceHint(dataSourceHint)
                .build();
    }

    /**
     * 构造数据源缺失提示 — Day 8-9 三价对比 bug 修复.
     *
     * <p>客户场景区分:
     * <ul>
     *   <li>BOM null + 移动均价 null → "新原料首次采购" (业务正常)</li>
     *   <li>BOM null + 移动均价 OK → "未配 BOM, 仅可对比历史均价"</li>
     *   <li>BOM OK + 移动均价 null → "尚无入库, 仅可对比 BOM 标准价"</li>
     *   <li>双有 → null (正常对比, 无需 hint)</li>
     * </ul>
     */
    // package-private static for test (PurchaseServicePriceHintTest) — pure function, 无依赖
    static String buildPriceDataSourceHint(BigDecimal bomStandardPrice, BigDecimal movingAvgPrice) {
        boolean missingBom = (bomStandardPrice == null);
        boolean missingAvg = (movingAvgPrice == null);
        if (!missingBom && !missingAvg) {
            return null;
        }
        if (missingBom && missingAvg) {
            return "新原料首次采购 — 三价对比基于 BOM 标准价 + 历次入库均价, 当前两项均无数据, 入库后自动累积 (这是预期状态, 不是 bug)";
        }
        if (missingBom) {
            return "该原料未配置 BOM 标准价 — 仅可对比历史入库均价. 在 BOM 模块为相关产品添加该原料以启用 BOM 对比";
        }
        // missingAvg only
        return "尚无该原料的入库记录 — 仅可对比 BOM 标准价. 入库一次后即累积移动均价";
    }

    /**
     * 计算偏差百分比: (current - reference) / reference * 100
     * @return 偏差百分比，正数表示当前价高于参考价；null 表示无法计算
     */
    private BigDecimal calculateVariance(BigDecimal currentPrice, BigDecimal referencePrice) {
        if (currentPrice == null || referencePrice == null || referencePrice.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return currentPrice.subtract(referencePrice)
                .divide(referencePrice, 4, BigDecimal.ROUND_HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    // ==================== 内部方法 ====================

    private String generateOrderNumber(String factoryId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "PO-" + dateStr + "-";
        Optional<String> maxNumber = purchaseOrderRepository.findMaxOrderNumberByPrefix(factoryId, prefix + "%");
        if (maxNumber.isPresent()) {
            try {
                int seq = Integer.parseInt(maxNumber.get().substring(prefix.length()));
                return String.format("%s%04d", prefix, seq + 1);
            } catch (NumberFormatException e) {
                // 序号解析失败，回退到计数方式
            }
        }
        long count = purchaseOrderRepository.countByFactoryIdAndDate(factoryId, LocalDate.now());
        return String.format("%s%04d", prefix, count + 1);
    }

    private String generateReceiveNumber(String factoryId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        // 用时间戳后4位 + 随机数确保唯一
        long ts = System.currentTimeMillis() % 10000;
        return String.format("RCV-%s-%04d", dateStr, ts);
    }

    private MaterialBatch createMaterialBatchFromReceiveItem(String factoryId, PurchaseReceiveRecord record, PurchaseReceiveItem item, Long userId) {
        // 查找原料类型获取保质期等信息
        RawMaterialType materialType = materialTypeRepository.findById(item.getMaterialTypeId()).orElse(null);

        String batchNumber = String.format("MT-%s-%04d",
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")),
                System.currentTimeMillis() % 10000);

        MaterialBatch batch = new MaterialBatch();
        batch.setId(UUID.randomUUID().toString());
        batch.setFactoryId(factoryId);
        batch.setBatchNumber(batchNumber);
        batch.setMaterialTypeId(item.getMaterialTypeId());
        batch.setSupplierId(record.getSupplierId());
        batch.setReceiptQuantity(item.getReceivedQuantity());
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setQuantityUnit(item.getUnit());
        batch.setUnitPrice(item.getUnitPrice());
        batch.setReceiptDate(record.getReceiveDate());
        batch.setPurchaseDate(record.getReceiveDate());
        batch.setFactoryNumber(item.getFactoryNumber());
        batch.setOriginPlace(item.getOriginPlace());
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setCreatedBy(userId);
        // D1: 采购入库默认 WH-LOG (物流仓). per PR #310 spec — raw material persistent in logistics warehouse.
        String warehouseId = (record.getWarehouseId() != null && !record.getWarehouseId().isBlank())
                ? record.getWarehouseId()
                : warehouseResolver.resolveLogisticsId(factoryId);
        batch.setWarehouseId(warehouseId);

        // SP7 §3.3 (W1 红线 #03): 采购收货前校验目标仓库类型 — 原料只能入 RAW/SALTED/legacy 仓.
        // 守卫在 save 之前抛 422, 不污染事务. legacy/null 类型仓库自动放行.
        if (warehouseInventoryGuardService != null) {
            warehouseInventoryGuardService.assertCanReceive(warehouseId, factoryId, "RAW");
        }

        // 根据原料类型计算过期日期
        if (materialType != null && materialType.getShelfLifeDays() != null) {
            batch.setExpireDate(record.getReceiveDate().plusDays(materialType.getShelfLifeDays()));
        }

        batch = materialBatchRepository.save(batch);
        log.debug("创建物料批次: batchId={}, batchNumber={}, materialTypeId={}, qty={}",
                batch.getId(), batchNumber, item.getMaterialTypeId(), item.getReceivedQuantity());
        return batch;
    }

    /**
     * PR #173 reviewer follow-up I-2 (May 9 2026): 抄收上限校验提取为可复用 helper.
     *
     * 原 audio P1-7 (commit e44b1fd28) 的校验只在 confirmReceive → updateOrderReceiveStatus 触发,
     * 即用户已走完 DRAFT → PENDING_QC 流程后才知道超收 → 体验差 + 已经创建了一些副作用 (in-memory state).
     *
     * 提取后:
     * - createReceiveRecord (DRAFT 创建早返) — 用 CreateReceiveRecordRequest.ReceiveItemDTO
     * - updateOrderReceiveStatus (confirmReceive 二次防御) — 用 PurchaseReceiveItem
     *
     * 双校验防御并发: DRAFT 创建时合法 → PENDING_QC 期间另一并发入库已 commit
     * → confirmReceive 阶段累计已超 cap → 二次校验抛 → 事务回滚.
     *
     * @param order 已加载的 PurchaseOrder (非 null, caller 责任)
     * @param items 入库 items, 用 (materialTypeId, materialName, receivedQuantity) 三元组校验
     */
    private void validateOverReceiveCap(PurchaseOrder order,
            List<CreateReceiveRecordRequest.ReceiveItemDTO> items) {
        if (items == null || items.isEmpty()) return;

        List<PurchaseOrderItem> orderItems = purchaseOrderItemRepository.findByPurchaseOrderId(order.getId());
        for (CreateReceiveRecordRequest.ReceiveItemDTO receiveItem : items) {
            for (PurchaseOrderItem orderItem : orderItems) {
                if (orderItem.getMaterialTypeId().equals(receiveItem.getMaterialTypeId())) {
                    checkOverReceiveCap(
                            receiveItem.getMaterialName(),
                            orderItem.getReceivedQuantity(),
                            receiveItem.getReceivedQuantity(),
                            orderItem.getQuantity());
                }
            }
        }
    }

    /**
     * 🔒 doomed-tx 修复 (六扇门 2026-06-15): confirmReceive 阶段的超收 fail-fast 校验.
     *
     * <p>用 {@link PurchaseReceiveItem} (DRAFT 入库行) 累加到 PO 行 receivedQuantity, 在
     * confirmReceive 创建任何 MaterialBatch <b>之前</b> 校验超收上限. 超限直接抛干净
     * {@link BusinessException}(409), 事务不被 doom → HTTP 409 而非 UnexpectedRollbackException(500).
     *
     * <p>与 {@link #validateOverReceiveCap} 共享 {@link #checkOverReceiveCap} 核心, 二者唯一
     * 区别是 item 类型 (创建期用 DTO, 确认期用持久化 PurchaseReceiveItem).
     *
     * @param record 已加载的 DRAFT/PENDING_QC 入库单 (其 purchaseOrderId 非 null, caller 责任)
     */
    private void validateOverReceiveCapForConfirm(PurchaseReceiveRecord record) {
        List<PurchaseOrderItem> orderItems =
                purchaseOrderItemRepository.findByPurchaseOrderId(record.getPurchaseOrderId());
        for (PurchaseReceiveItem receiveItem : record.getItems()) {
            for (PurchaseOrderItem orderItem : orderItems) {
                if (orderItem.getMaterialTypeId().equals(receiveItem.getMaterialTypeId())) {
                    checkOverReceiveCap(
                            receiveItem.getMaterialName(),
                            orderItem.getReceivedQuantity(),
                            receiveItem.getReceivedQuantity(),
                            orderItem.getQuantity());
                }
            }
        }
    }

    /**
     * 超收上限校验核心 (单行). 超过 {@code orderedQty × (1 + overReceiveRate)} 抛 409.
     *
     * <p>所有调用站点 (createReceiveRecord 早返 / confirmReceive fail-fast / updateOrderReceiveStatus
     * 二次防御) 共享此核心, 保证 message 与边界语义一致. 不做任何 DB 写, 调用方负责在 DB mutation 前调用.
     *
     * @param materialName    物料名 (用于 message)
     * @param alreadyReceived PO 行已累计收货量 (null 视为 0)
     * @param thisReceive     本次收货量 (null 视为 0)
     * @param orderedQty      PO 行下单量 (null → 无上限, 跳过)
     */
    private void checkOverReceiveCap(String materialName,
            BigDecimal alreadyReceived, BigDecimal thisReceive, BigDecimal orderedQty) {
        if (orderedQty == null) return;
        BigDecimal already = alreadyReceived != null ? alreadyReceived : BigDecimal.ZERO;
        BigDecimal thisQty = thisReceive != null ? thisReceive : BigDecimal.ZERO;
        BigDecimal newReceived = already.add(thisQty);
        BigDecimal maxAllowed = orderedQty.multiply(BigDecimal.ONE.add(overReceiveRate));
        if (newReceived.compareTo(maxAllowed) > 0) {
            throw new BusinessException(409, String.format(
                    "超出可入库上限: 物料「%s」已收 %s, 本次 %s, 累计 %s, 下单 %s, 最大可收 %s (含 %s%% 抄收)",
                    materialName,
                    already.stripTrailingZeros().toPlainString(),
                    thisQty.stripTrailingZeros().toPlainString(),
                    newReceived.stripTrailingZeros().toPlainString(),
                    orderedQty.stripTrailingZeros().toPlainString(),
                    maxAllowed.stripTrailingZeros().toPlainString(),
                    overReceiveRate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()))
                    .withHint("如需更多入库, 请采购另下新订单或联系采购员调整下单量");
        }
    }

    private void updateOrderReceiveStatus(PurchaseReceiveRecord record) {
        PurchaseOrder order = purchaseOrderRepository.findById(record.getPurchaseOrderId()).orElse(null);
        if (order == null) return;

        List<PurchaseOrderItem> orderItems = purchaseOrderItemRepository.findByPurchaseOrderId(order.getId());

        // 累加本次入库数量到订单行项目
        for (PurchaseReceiveItem receiveItem : record.getItems()) {
            for (PurchaseOrderItem orderItem : orderItems) {
                if (orderItem.getMaterialTypeId().equals(receiveItem.getMaterialTypeId())) {
                    // May 9 fix (audio P1-7): 抄收上限校验 (二次防御).
                    // 旧逻辑无上限 → 分批入库时累计可任意超出下单量.
                    // 客户原话: "正常抄收应该是30%以内, 如果有特殊情况, 那采购另外再下个单子".
                    // doomed-tx 修复 (六扇门 2026-06-15): 主校验已前移到 confirmReceive 入口
                    // (validateOverReceiveCapForConfirm) — 在任何 MaterialBatch 创建前 fail-fast,
                    // 事务不被 doom. 此处保留共享 helper 校验作为兜底 (理论上前移后不会触发,
                    // 但保留保证即便有路径绕过入口校验, 累加 setReceivedQuantity 前仍守住上限).
                    checkOverReceiveCap(
                            receiveItem.getMaterialName(),
                            orderItem.getReceivedQuantity(),
                            receiveItem.getReceivedQuantity(),
                            orderItem.getQuantity());

                    orderItem.setReceivedQuantity(
                            orderItem.getReceivedQuantity().add(receiveItem.getReceivedQuantity()));
                }
            }
        }
        purchaseOrderItemRepository.saveAll(orderItems);

        // 判断是否全部到货
        boolean allReceived = orderItems.stream().allMatch(item ->
                item.getReceivedQuantity().compareTo(item.getQuantity()) >= 0);

        if (allReceived) {
            order.setStatus(PurchaseOrderStatus.COMPLETED);
        } else {
            order.setStatus(PurchaseOrderStatus.PARTIAL_RECEIVED);
        }
        purchaseOrderRepository.save(order);
    }
}
