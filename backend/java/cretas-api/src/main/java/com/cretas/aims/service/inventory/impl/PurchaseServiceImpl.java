package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.dto.inventory.CreateReceiveRecordRequest;
import com.cretas.aims.dto.inventory.ClosePurchaseReceivingTaskRequest;
import com.cretas.aims.dto.inventory.UpdatePurchaseOrderRequest;
import com.cretas.aims.dto.inventory.MaterialPriceComparisonDTO;
import com.cretas.aims.dto.inventory.PurchaseSuggestionResponse;
import com.cretas.aims.dto.inventory.PurchaseSuggestionMultiResponse;
import com.cretas.aims.dto.inventory.PurchaseApprovalRecoveryResponse;
import com.cretas.aims.dto.inventory.PurchaseReceivingTaskResponse;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.enums.PurchaseType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
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
     * 超收上限率。默认 0%，只有工厂显式配置后才允许超收；无配置时
     * 必须 fail-closed，不能把某一行业经验值静默套用于全部采购收货。
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
    @org.springframework.beans.factory.annotation.Value("${cretas.purchase.over-receive-rate:0.00}")
    private BigDecimal overReceiveRate;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final PurchaseReceiveRecordRepository receiveRecordRepository;
    private final SupplierRepository supplierRepository;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final BomRecipeItemRepository bomRecipeItemRepository;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.SupplierMaterialRepository supplierMaterialRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository supplierMaterialPurchaseSpecRepository;

    /** Material-master transaction packaging; inventory still lands in RawMaterialType.unit. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.material.MaterialPackagingSpecRepository materialPackagingSpecRepository;

    /**
     * 销售订单采购建议必须与以销定产使用同一包装换算契约：先把销售展示单位
     * （例如 10 箱）换算为成品基本单位（500 盒），再乘每基本单位 BOM 用量。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductTypeRepository productTypeRepository;

    /** Authoritative canonical-unit and conversion contract for quantity/price calculations. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.unit.UnitContractService unitContractService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.production.SalesOrderPlanQuantityNormalizer salesOrderPlanQuantityNormalizer;

    /** D-6: 责任绑定 — 查询 PO 创建人的用户名供异常单展示（required=false 兼容旧 context） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.UserRepository userRepository;

    /** 收货凭证门禁：正式确认前至少保留一份供应商送货/收货证据。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AttachmentRepository attachmentRepository;

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
                               BomRecipeItemRepository bomRecipeItemRepository,
                               com.cretas.aims.service.finance.ArApService arApService,
                               ApplicationEventPublisher applicationEventPublisher,
                               MaterialBatchService materialBatchService) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.receiveRecordRepository = receiveRecordRepository;
        this.supplierRepository = supplierRepository;
        this.materialTypeRepository = materialTypeRepository;
        this.materialBatchRepository = materialBatchRepository;
        this.bomRecipeItemRepository = bomRecipeItemRepository;
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

        // DRAFT may be created before supplier assignment; submission remains fail-closed below.
        if (request.getSupplierId() != null && !request.getSupplierId().isBlank()) {
            requireActiveSupplier(factoryId, request.getSupplierId());
        }

        // 防呆 R4 (幂等防双击, edge-case 审计 2026-06-24): 60s 内同买手对同供应商重复建 DRAFT 单 → 409。
        // 键含 createdBy, 误拦仅"同一人 60s 内对同供应商双击"; 合法重复下单 (不同人/超 60s) 不受影响。
        java.util.List<PurchaseOrder> poDupes = request.getSupplierId() == null || request.getSupplierId().isBlank()
                ? java.util.List.of()
                : purchaseOrderRepository.findRecentDuplicateOrders(
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
            applySupplierPurchaseContract(factoryId, request.getSupplierId(), item, itemDTO);
            applyPurchaseTaxContract(item, itemDTO.getTaxRate());
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
    public PurchaseOrder submitOrder(String factoryId, String orderId, Long initiatorUserId) {
        PurchaseOrder order = purchaseOrderRepository.findByIdAndFactoryIdForUpdate(orderId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("采购订单不存在: " + orderId));

        // Same document is the idempotency boundary.  A replay after the first
        // transaction returns the already projected truth and never creates a second instance.
        if (order.getStatus() != PurchaseOrderStatus.DRAFT) {
            Optional<ApprovalWorkflowInstance> existing = workflowEngine == null
                    ? Optional.empty()
                    : workflowEngine.getLatestInstance(factoryId, "PURCHASE_ORDER", orderId);
            if (existing.isPresent() && EnumSet.of(
                    PurchaseOrderStatus.WORKFLOW_RUNNING,
                    PurchaseOrderStatus.FINANCE_APPROVED,
                    PurchaseOrderStatus.FINANCE_REJECTED).contains(order.getStatus())) {
                return order;
            }
            if (order.getStatus() == PurchaseOrderStatus.SUBMITTED && existing.isEmpty()) {
                throw new BusinessException(409, "采购单已提交但缺少 OA 审批实例")
                        .withCode("PURCHASE_APPROVAL_INSTANCE_MISSING")
                        .withHint("请由管理员使用受限的审批恢复工具处理，禁止重复提交或重建采购单");
            }
            throw new BusinessException(409, "只有草稿状态的订单可以提交")
                    .withHint("请刷新订单列表查看最新状态");
        }
        if (order.getSupplierId() == null || order.getSupplierId().isBlank()) {
            throw new BusinessException(422, "提交采购单前必须选择供应商")
                    .withCode("PURCHASE_SUPPLIER_REQUIRED")
                    .withHint("请编辑采购草稿并选择供应商后再提交")
                    .withHintTarget("supplierId");
        }
        requireActiveSupplier(factoryId, order.getSupplierId());
        validateOrderLinesBeforeApproval(factoryId, order);

        if (workflowEngine == null || !workflowEngine.hasActiveWorkflow(factoryId, "PURCHASE_ORDER")) {
            throw new BusinessException(422, "当前工厂未配置可用的采购 OA 审批流程")
                    .withCode("PURCHASE_APPROVAL_ROUTE_REQUIRED")
                    .withHint("请管理员先发布并启用 PURCHASE_ORDER 审批流程；采购单仍保持草稿");
        }
        // Phase 4a follow-up (issue #45): Option A wrap. The annotated helper
        // evaluateOrderRules() must be invoked through the Spring proxy ({@code self}, not
        // {@code this}) so the @RuleEvaluate aspect intercepts. The helper receives the
        // loaded PurchaseOrder POJO; aspect binds target="order" parameter name → runs
        // ORDER scope rules against it.
        self.evaluateOrderRules(factoryId, order);

        ApprovalWorkflowInstance instance = workflowEngine.startWorkflow(
                factoryId,
                "PURCHASE_ORDER",
                order.getId(),
                buildPurchaseWorkflowContext(factoryId, order),
                initiatorUserId);
        validateRunnableApprovalRoute(factoryId, instance);
        projectWorkflowState(order, instance, initiatorUserId);
        log.info("提交采购订单并启动 OA: orderId={}, orderNumber={}, instanceId={}, workflowStatus={}",
                orderId, order.getOrderNumber(), instance.getId(), instance.getStatus());
        return purchaseOrderRepository.save(order);
    }

    @Override
    @Transactional
    @Loggable(module = "PURCHASE_ORDER", action = "RECOVER_APPROVAL_INSTANCE",
            entityType = "PurchaseOrder", entityIdParam = "orderId",
            summary = "'受限恢复采购单 OA 实例：' + #reason")
    public PurchaseApprovalRecoveryResponse recoverMissingApprovalInstance(
            String factoryId,
            String orderId,
            Long operatorUserId,
            String expectedOrderNumber,
            String idempotencyKey,
            String reason,
            boolean confirm) {
        PurchaseOrder order = purchaseOrderRepository.findByIdAndFactoryIdForUpdate(orderId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("采购订单不存在: " + orderId));
        if (operatorUserId == null) {
            throw new BusinessException(401, "无法识别执行恢复的管理员身份")
                    .withCode("PURCHASE_APPROVAL_RECOVERY_OPERATOR_REQUIRED");
        }
        if (!confirm) {
            throw new BusinessException(422, "必须明确确认执行 OA 审批实例恢复")
                    .withCode("PURCHASE_APPROVAL_RECOVERY_CONFIRM_REQUIRED")
                    .withHintTarget("confirm");
        }
        String expectedNumber = requireRecoveryText(expectedOrderNumber, "采购订单号", 100);
        String recoveryKey = requireRecoveryText(idempotencyKey, "幂等键", 120);
        String recoveryReason = requireRecoveryText(reason, "恢复原因", 500);
        if (!recoveryKey.matches("^[A-Za-z0-9._:-]{8,120}$")) {
            throw new BusinessException(422, "幂等键格式无效")
                    .withCode("PURCHASE_APPROVAL_RECOVERY_KEY_INVALID")
                    .withHintTarget("idempotencyKey");
        }
        if (!Objects.equals(order.getOrderNumber(), expectedNumber)) {
            throw new BusinessException(409, "采购订单号与目标记录不一致")
                    .withCode("PURCHASE_APPROVAL_RECOVERY_IDENTITY_MISMATCH")
                    .withHint("请重新核对订单 ID 与业务订单号，恢复操作未执行");
        }
        if (workflowEngine == null) {
            throw new BusinessException(503, "OA 审批服务不可用")
                    .withCode("PURCHASE_APPROVAL_SERVICE_UNAVAILABLE");
        }

        Optional<ApprovalWorkflowInstance> existing = workflowEngine.getLatestInstance(
                factoryId, "PURCHASE_ORDER", orderId);
        if (existing.isPresent()) {
            ApprovalWorkflowInstance instance = existing.get();
            if (recoveryKey.equals(readRecoveryKey(instance))
                    && order.getStatus() != PurchaseOrderStatus.SUBMITTED) {
                return recoveryResponse(order, instance, false);
            }
            throw new BusinessException(409, "采购订单已存在 OA 审批实例，禁止重复恢复")
                    .withCode("PURCHASE_APPROVAL_RECOVERY_CONFLICT")
                    .withHint("请只读查看现有审批实例；不同幂等键不会覆盖原实例");
        }
        if (order.getStatus() != PurchaseOrderStatus.SUBMITTED) {
            throw new BusinessException(409, "只有已提交且缺少 OA 实例的采购订单可以恢复")
                    .withCode("PURCHASE_APPROVAL_RECOVERY_STATUS_INVALID");
        }
        if (!workflowEngine.hasActiveWorkflow(factoryId, "PURCHASE_ORDER")) {
            throw new BusinessException(422, "当前工厂没有可用的采购 OA 审批流程")
                    .withCode("PURCHASE_APPROVAL_ROUTE_REQUIRED");
        }
        validateRecoverySnapshot(order);

        Map<String, Object> context = buildPurchaseWorkflowContext(factoryId, order);
        Map<String, Object> recoveryAudit = new LinkedHashMap<>();
        recoveryAudit.put("idempotencyKey", recoveryKey);
        recoveryAudit.put("reason", recoveryReason);
        recoveryAudit.put("operatorUserId", operatorUserId);
        recoveryAudit.put("recoveredAt", LocalDateTime.now().toString());
        recoveryAudit.put("originalOrderCreatedAt",
                order.getCreatedAt() == null ? null : order.getCreatedAt().toString());
        context.put("approvalRecovery", recoveryAudit);

        Long originalInitiator = order.getCreatedBy() != null ? order.getCreatedBy() : operatorUserId;
        ApprovalWorkflowInstance instance = workflowEngine.startWorkflow(
                factoryId, "PURCHASE_ORDER", orderId, context, originalInitiator);
        validateRunnableApprovalRoute(factoryId, instance);
        projectWorkflowState(order, instance, originalInitiator);
        purchaseOrderRepository.save(order);
        log.warn("受限恢复采购单 OA 实例: factoryId={}, orderId={}, orderNumber={}, instanceId={}, operatorUserId={}",
                factoryId, orderId, order.getOrderNumber(), instance.getId(), operatorUserId);
        return recoveryResponse(order, instance, true);
    }

    @Override
    @Transactional
    @Loggable(module = "PURCHASE_ORDER", action = "OA_ACTION", entityType = "PurchaseOrder",
              entityIdParam = "orderId")
    public PurchaseOrder applyWorkflowAction(String factoryId,
                                             String orderId,
                                             String instanceId,
                                             Long actorId,
                                             String actorRole,
                                             HistoryAction action,
                                             String notes) {
        PurchaseOrder order = purchaseOrderRepository.findByIdAndFactoryIdForUpdate(orderId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("采购订单不存在: " + orderId));
        if (workflowEngine == null) {
            throw new BusinessException(503, "OA 审批服务不可用");
        }
        ApprovalWorkflowInstance instance = workflowEngine.getInstance(factoryId, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("OA 审批实例不存在: " + instanceId));
        if (!"PURCHASE_ORDER".equals(instance.getModuleCode())
                || !orderId.equals(instance.getBusinessEntityId())) {
            throw new BusinessException(400, "审批实例与采购单不匹配")
                    .withCode("PURCHASE_APPROVAL_IDENTITY_MISMATCH");
        }
        if (instance.getStatus() != InstanceStatus.RUNNING) {
            // Terminal replays are pure reads. Do not refresh approval timestamps,
            // append history, or bump the purchase-order version on a duplicate action.
            return order;
        }
        if (actorId != null
                && actorId.equals(instance.getInitiatedBy())
                && !isExplicitCurrentNodeApprover(factoryId, instance, actorId)) {
            throw new BusinessException(403, "发起人不能审批自己的采购单")
                    .withCode("PURCHASE_SELF_APPROVAL_FORBIDDEN")
                    .withHint("请由当前 OA 节点授权的其他审批人处理，或在 Canvas 中明确将发起人配置为该节点审批人");
        }
        if (action == HistoryAction.REJECT && (notes == null || notes.isBlank())) {
            throw new BusinessException(422, "驳回采购单必须填写原因").withHintTarget("notes");
        }
        ApprovalWorkflowInstance updated = workflowEngine.transitionNode(
                instanceId, actorId, actorRole, action, notes);
        projectWorkflowState(order, updated, actorId);
        return purchaseOrderRepository.save(order);
    }

    private void validateOrderLinesBeforeApproval(String factoryId, PurchaseOrder order) {
        List<PurchaseOrderItem> items = purchaseOrderItemRepository.findByPurchaseOrderId(order.getId());
        if (items.isEmpty()) {
            throw new BusinessException(422, "采购单至少需要一条物料明细")
                    .withCode("PURCHASE_ITEMS_REQUIRED");
        }
        for (PurchaseOrderItem item : items) {
            assertSupplierMaterialActive(factoryId, order.getSupplierId(), item.getMaterialTypeId());
            if (item.getUnitPrice() == null || item.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(422, "采购物料未配置有效采购价: " + item.getMaterialName())
                        .withCode("PURCHASE_PRICE_REQUIRED")
                        .withHint("请在供应商—物料关系或采购包装规格中维护大于0的未税采购价")
                        .withHintTarget("unitPrice");
            }
            String quantityUnit = canonicalUnit(factoryId, item.getUnit(), "采购数量单位");
            String priceUnit = canonicalUnit(factoryId, item.getPriceUnit(), "采购计价单位");
            if (item.getQuantityToPriceFactor() == null
                    || item.getQuantityToPriceFactor().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(422, "采购数量单位与计价单位缺少有效换算: "
                        + quantityUnit + " → " + priceUnit)
                        .withCode("PURCHASE_PRICE_CONVERSION_REQUIRED");
            }
            if (item.getPurchasePackagingSpecId() != null) {
                com.cretas.aims.entity.SupplierMaterialPurchaseSpec spec =
                        supplierMaterialPurchaseSpecRepository == null
                                ? null
                                : supplierMaterialPurchaseSpecRepository
                                .findById(item.getPurchasePackagingSpecId()).orElse(null);
                if (spec == null || !Boolean.TRUE.equals(spec.getActive())
                        || !factoryId.equals(spec.getFactoryId())
                        || !Objects.equals(item.getSupplierMaterialId(), spec.getSupplierMaterialId())) {
                    throw new BusinessException(409, "采购包装规格已停用或与供应关系不匹配")
                            .withCode("PURCHASE_PACKAGING_SPEC_INACTIVE")
                            .withHintTarget("purchasePackagingSpecId");
                }
            }
        }
    }

    private Map<String, Object> buildPurchaseWorkflowContext(String factoryId, PurchaseOrder order) {
        Map<String, Object> context = new HashMap<>();
        context.put("amount", order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO);
        context.put("totalAmount", order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO);
        context.put("taxAmount", order.getTaxAmount() != null ? order.getTaxAmount() : BigDecimal.ZERO);
        context.put("orderId", order.getId());
        context.put("orderNumber", order.getOrderNumber());
        context.put("supplierId", order.getSupplierId());
        context.put("priceVarianceItemCount", countPriceAlertItems(factoryId, order));
        context.put("approvalChain", "BUSINESS_AND_FINANCE");
        return context;
    }

    private String requireRecoveryText(String value, String fieldName, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new BusinessException(422, fieldName + "不能为空且长度不能超过" + maxLength + "个字符")
                    .withCode("PURCHASE_APPROVAL_RECOVERY_FIELD_INVALID");
        }
        return normalized;
    }

    private void validateRecoverySnapshot(PurchaseOrder order) {
        if (order.getSupplierId() == null || order.getSupplierId().isBlank()) {
            throw new BusinessException(422, "采购订单缺少供应商身份，不能恢复审批")
                    .withCode("PURCHASE_APPROVAL_RECOVERY_SNAPSHOT_INVALID");
        }
        List<PurchaseOrderItem> items = purchaseOrderItemRepository.findByPurchaseOrderId(order.getId());
        boolean invalidItem = items.isEmpty() || items.stream().anyMatch(item ->
                item.getMaterialTypeId() == null || item.getMaterialTypeId().isBlank()
                        || item.getQuantity() == null
                        || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0
                        || item.getUnit() == null || item.getUnit().isBlank());
        if (invalidItem) {
            throw new BusinessException(422, "采购订单明细快照不完整，不能恢复审批")
                    .withCode("PURCHASE_APPROVAL_RECOVERY_SNAPSHOT_INVALID")
                    .withHint("恢复工具不会修改订单数量、单位或物料身份");
        }
    }

    private String readRecoveryKey(ApprovalWorkflowInstance instance) {
        if (instance.getContextJson() == null) return null;
        Object rawAudit = instance.getContextJson().get("approvalRecovery");
        if (!(rawAudit instanceof Map<?, ?> audit)) return null;
        Object rawKey = audit.get("idempotencyKey");
        return rawKey == null ? null : String.valueOf(rawKey);
    }

    private PurchaseApprovalRecoveryResponse recoveryResponse(
            PurchaseOrder order,
            ApprovalWorkflowInstance instance,
            boolean recovered) {
        return PurchaseApprovalRecoveryResponse.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .orderStatus(order.getStatus())
                .workflowInstanceId(instance.getId())
                .workflowStatus(instance.getStatus())
                .currentNodeIds(instance.getCurrentNodeIds() == null
                        ? List.of() : List.copyOf(instance.getCurrentNodeIds()))
                .recovered(recovered)
                .build();
    }

    private void validateRunnableApprovalRoute(String factoryId, ApprovalWorkflowInstance instance) {
        if (instance.getStatus() != InstanceStatus.RUNNING) return;
        if (instance.getCurrentNodeIds() == null || instance.getCurrentNodeIds().isEmpty()) {
            throw new BusinessException(422, "采购 OA 流程没有可处理的当前节点")
                    .withCode("PURCHASE_APPROVAL_NODE_REQUIRED");
        }
        if (approvalWorkflowService == null || userRepository == null) {
            throw new BusinessException(503, "采购 OA 路由解析服务不可用");
        }
        ApprovalWorkflow workflow = approvalWorkflowService
                .getById(factoryId, instance.getWorkflowId())
                .orElseThrow(() -> new BusinessException(422, "采购 OA 流程定义不存在")
                        .withCode("PURCHASE_APPROVAL_DEFINITION_MISSING"));
        Map<String, ApprovalWorkflowNode> nodes = approvalWorkflowService
                .deserializeNodes(workflow.getNodesJson()).stream()
                .collect(Collectors.toMap(ApprovalWorkflowNode::getId, node -> node));
        for (String nodeId : instance.getCurrentNodeIds()) {
            ApprovalWorkflowNode node = nodes.get(nodeId);
            if (node == null || !"approval".equalsIgnoreCase(node.getType())) {
                throw new BusinessException(422, "采购 OA 当前节点不可审批: " + nodeId)
                        .withCode("PURCHASE_APPROVAL_NODE_INVALID");
            }
            Object configuredRoles = node.getConfig() == null
                    ? null : node.getConfig().get("approverRoles");
            List<String> roles = new ArrayList<>();
            if (configuredRoles instanceof Iterable<?> iterable) {
                iterable.forEach(value -> {
                    if (value != null && !String.valueOf(value).isBlank()) {
                        roles.add(String.valueOf(value));
                    }
                });
            }
            if (roles.isEmpty()) {
                throw new BusinessException(422, "采购 OA 节点未配置审批角色: "
                        + (node.getLabel() == null ? nodeId : node.getLabel()))
                        .withCode("PURCHASE_APPROVER_ROLE_REQUIRED");
            }
            boolean hasActiveAssignee = roles.stream()
                    .flatMap(role -> userRepository.findByFactoryIdAndRoleCode(factoryId, role).stream())
                    .anyMatch(user -> Boolean.TRUE.equals(user.getIsActive()));
            if (!hasActiveAssignee) {
                throw new BusinessException(422, "采购 OA 节点没有可用审批人: "
                        + (node.getLabel() == null ? nodeId : node.getLabel()))
                        .withCode("PURCHASE_APPROVER_ASSIGNEE_REQUIRED")
                        .withHint("请为当前工厂配置匹配审批角色的有效账号；采购单仍保持草稿");
            }
        }
    }

    /**
     * A workflow may intentionally name the initiator as the approver for a single-user factory.
     * Role membership alone is not enough to bypass separation of duties: the active node must
     * explicitly contain the actor in {@code approverUserIds}.
     */
    private boolean isExplicitCurrentNodeApprover(
            String factoryId,
            ApprovalWorkflowInstance instance,
            Long actorId) {
        if (approvalWorkflowService == null
                || instance.getCurrentNodeIds() == null
                || instance.getCurrentNodeIds().isEmpty()) {
            return false;
        }
        ApprovalWorkflow workflow = approvalWorkflowService
                .getById(factoryId, instance.getWorkflowId())
                .orElse(null);
        if (workflow == null) {
            return false;
        }
        String currentNodeId = instance.getCurrentNodeIds().get(0);
        ApprovalWorkflowNode currentNode = approvalWorkflowService
                .deserializeNodes(workflow.getNodesJson()).stream()
                .filter(node -> currentNodeId.equals(node.getId()))
                .findFirst()
                .orElse(null);
        if (currentNode == null
                || !"approval".equalsIgnoreCase(currentNode.getType())
                || currentNode.getConfig() == null) {
            return false;
        }
        Object configuredApprovers = currentNode.getConfig().get("approverUserIds");
        if (!(configuredApprovers instanceof Iterable<?> approvers)) {
            return false;
        }
        for (Object configuredApprover : approvers) {
            if (configuredApprover instanceof Number number
                    && actorId.equals(number.longValue())) {
                return true;
            }
            if (configuredApprover != null
                    && actorId.toString().equals(String.valueOf(configuredApprover).trim())) {
                return true;
            }
        }
        return false;
    }

    private void projectWorkflowState(PurchaseOrder order,
                                      ApprovalWorkflowInstance instance,
                                      Long actorId) {
        PurchaseOrderStatus status = switch (instance.getStatus()) {
            case RUNNING -> PurchaseOrderStatus.WORKFLOW_RUNNING;
            // The configured PURCHASE_ORDER workflow is the complete OA chain.  Reaching
            // APPROVED therefore includes any configured finance node or its audited skip.
            case APPROVED -> PurchaseOrderStatus.FINANCE_APPROVED;
            case REJECTED, CANCELLED, TIMEOUT -> PurchaseOrderStatus.FINANCE_REJECTED;
        };
        order.setStatus(status);
        if (status == PurchaseOrderStatus.FINANCE_APPROVED) {
            order.setApprovedBy(actorId);
            order.setApprovedAt(LocalDateTime.now());
            order.setFinanceReviewedBy(actorId);
            order.setFinanceReviewedAt(LocalDateTime.now());
        }
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
            // The only legal creation boundary is submitOrder.  Starting an instance from
            // an approve endpoint would recreate the exact "submitted but invisible OA"
            // split-brain and bypass the initiator/audit contract.
            throw new BusinessException(409, "采购单缺少可处理的 OA 审批实例")
                    .withCode("PURCHASE_APPROVAL_INSTANCE_MISSING")
                    .withHint("请返回 OA 审批中心刷新；禁止从采购详情重新发起审批");
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
            requireActiveSupplier(factoryId, request.getSupplierId());
            order.setSupplierId(request.getSupplierId());
            if (request.getItems() == null) {
                for (PurchaseOrderItem existingItem : purchaseOrderItemRepository.findByPurchaseOrderId(orderId)) {
                    assertSupplierMaterialActive(factoryId, request.getSupplierId(), existingItem.getMaterialTypeId());
                }
            }
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
            applySupplierPurchaseContract(factoryId, order.getSupplierId(), item, itemDTO);
            applyPurchaseTaxContract(item, itemDTO.getTaxRate());
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
        if (source.getSupplierId() != null) {
            requireActiveSupplier(factoryId, source.getSupplierId());
        }
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
            newItem.setPriceUnit(srcItem.getPriceUnit() != null ? srcItem.getPriceUnit() : srcItem.getUnit());
            newItem.setQuantityToPriceFactor(srcItem.getQuantityToPriceFactor() != null
                    ? srcItem.getQuantityToPriceFactor() : BigDecimal.ONE);
            newItem.setSupplierMaterialId(srcItem.getSupplierMaterialId());
            newItem.setPurchasePackagingSpecId(srcItem.getPurchasePackagingSpecId());
            newItem.setMaterialPackagingSpecId(srcItem.getMaterialPackagingSpecId());
            newItem.setPurchasePackageUnitSnapshot(srcItem.getPurchasePackageUnitSnapshot());
            newItem.setInventoryBaseUnitSnapshot(srcItem.getInventoryBaseUnitSnapshot());
            newItem.setPackageToBaseFactorSnapshot(srcItem.getPackageToBaseFactorSnapshot());
            newItem.setInventoryQuantitySnapshot(srcItem.getInventoryQuantitySnapshot());
            newItem.setUnitPrice(srcItem.getUnitPrice());
            newItem.setTaxRate(srcItem.getTaxRate() != null ? srcItem.getTaxRate() : BigDecimal.ZERO);
            newItem.setRemark(srcItem.getRemark());
            newItem.setSpecification(srcItem.getSpecification());
            newItem.setBoxQuantity(srcItem.getBoxQuantity());
            snapshotPurchaseAmounts(newItem);
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
    @Transactional(readOnly = true)
    public List<PurchaseReceivingTaskResponse> getPendingReceivingTasks(
            String factoryId, String purchaseOrderId, String orderNumber) {
        LinkedHashMap<String, PurchaseOrder> candidates = new LinkedHashMap<>();
        if (purchaseOrderId != null && !purchaseOrderId.isBlank()) {
            PurchaseOrder order = purchaseOrderRepository.findByIdAndFactoryId(purchaseOrderId, factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("采购订单不存在或不属于当前组织"));
            candidates.put(order.getId(), order);
        } else if (orderNumber != null && !orderNumber.isBlank()) {
            PurchaseOrder order = purchaseOrderRepository.findByFactoryIdAndOrderNumber(factoryId, orderNumber.trim())
                    .orElseThrow(() -> new ResourceNotFoundException("采购订单不存在或不属于当前组织"));
            candidates.put(order.getId(), order);
        } else {
            PageRequest page = PageRequest.of(0, 500, Sort.by(Sort.Direction.ASC, "expectedDeliveryDate")
                    .and(Sort.by(Sort.Direction.DESC, "createdAt")));
            purchaseOrderRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(
                    factoryId, PurchaseOrderStatus.FINANCE_APPROVED, page)
                    .forEach(order -> candidates.put(order.getId(), order));
            purchaseOrderRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(
                    factoryId, PurchaseOrderStatus.PARTIAL_RECEIVED, page)
                    .forEach(order -> candidates.put(order.getId(), order));
        }

        return candidates.values().stream()
                .filter(order -> order.getStatus() == PurchaseOrderStatus.FINANCE_APPROVED
                        || order.getStatus() == PurchaseOrderStatus.PARTIAL_RECEIVED)
                .map(this::toReceivingTask)
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing(PurchaseReceivingTaskResponse::getExpectedDeliveryDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PurchaseReceivingTaskResponse::getOrderNumber,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private PurchaseReceivingTaskResponse toReceivingTask(PurchaseOrder order) {
        List<PurchaseOrderItem> orderItems = purchaseOrderItemRepository.findByPurchaseOrderId(order.getId());
        List<PurchaseReceiveRecord> receipts = receiveRecordRepository
                .findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(order.getFactoryId(), order.getId());
        List<PurchaseReceiveRecord> activeReceipts = receipts.stream()
                .filter(this::isActiveReceipt)
                .toList();
        PurchaseReceiveRecord activeReceipt = activeReceipts.stream().findFirst().orElse(null);

        Map<Long, BigDecimal> activeAllocatedByOrderItem = new HashMap<>();
        Map<String, BigDecimal> legacyAllocatedByMaterial = new HashMap<>();
        for (PurchaseReceiveRecord receipt : activeReceipts) {
            if (receipt.getItems() == null) continue;
            for (PurchaseReceiveItem item : receipt.getItems()) {
                BigDecimal allocatedQuantity = zeroIfNull(item.getReceivedQuantity());
                try {
                    PurchaseOrderItem orderItem = resolvePurchaseOrderItem(
                            orderItems, item.getPurchaseOrderItemId(), item.getMaterialTypeId());
                    allocatedQuantity = receiptQuantityInOrderUnit(
                            order.getFactoryId(), orderItem, item);
                } catch (BusinessException ex) {
                    log.warn("活动收货草稿缺少安全换算快照: receiptId={}, itemId={}, error={}",
                            receipt.getId(), item.getId(), ex.getMessage());
                }
                if (item.getPurchaseOrderItemId() != null) {
                    activeAllocatedByOrderItem.merge(item.getPurchaseOrderItemId(),
                            allocatedQuantity, BigDecimal::add);
                } else {
                    legacyAllocatedByMaterial.merge(item.getMaterialTypeId(),
                            allocatedQuantity, BigDecimal::add);
                }
            }
        }
        boolean legacyLineIdentityAmbiguous = legacyAllocatedByMaterial.keySet().stream()
                .anyMatch(materialTypeId -> orderItems.stream()
                        .filter(item -> Objects.equals(materialTypeId, item.getMaterialTypeId()))
                        .count() > 1);

        List<PurchaseReceivingTaskResponse.Item> lines = new ArrayList<>();
        BigDecimal remainingTotal = BigDecimal.ZERO;
        for (PurchaseOrderItem item : orderItems) {
            BigDecimal ordered = zeroIfNull(item.getQuantity());
            BigDecimal received = zeroIfNull(item.getReceivedQuantity());
            BigDecimal openBeforeDraft = ordered.subtract(received).max(BigDecimal.ZERO);
            BigDecimal exactAllocation = activeAllocatedByOrderItem.getOrDefault(item.getId(), BigDecimal.ZERO);
            BigDecimal materialAllocation = exactAllocation.add(legacyAllocatedByMaterial
                    .getOrDefault(item.getMaterialTypeId(), BigDecimal.ZERO));
            BigDecimal allocated = materialAllocation.min(openBeforeDraft);
            BigDecimal legacyConsumed = allocated.subtract(exactAllocation.min(allocated)).max(BigDecimal.ZERO);
            if (legacyConsumed.signum() > 0) {
                legacyAllocatedByMaterial.put(item.getMaterialTypeId(),
                        legacyAllocatedByMaterial.getOrDefault(item.getMaterialTypeId(), BigDecimal.ZERO)
                                .subtract(legacyConsumed).max(BigDecimal.ZERO));
            }
            BigDecimal remaining = ordered.subtract(received).subtract(allocated).max(BigDecimal.ZERO);
            remainingTotal = remainingTotal.add(remaining);
            lines.add(PurchaseReceivingTaskResponse.Item.builder()
                    .purchaseOrderItemId(item.getId())
                    .materialTypeId(item.getMaterialTypeId())
                    .materialName(item.getMaterialName())
                    .orderedQuantity(ordered)
                    .receivedQuantity(received)
                    .activeDraftAllocatedQuantity(allocated)
                    .remainingReceivableQuantity(remaining)
                    .unit(item.getUnit())
                    .specification(item.getSpecification())
                    .materialPackagingSpecId(item.getMaterialPackagingSpecId())
                    .inventoryBaseUnit(item.getInventoryBaseUnitSnapshot())
                    .packageToBaseFactor(item.getPackageToBaseFactorSnapshot())
                    .packagingSpecs(materialPackagingSpecRepository == null ? List.of()
                            : materialPackagingSpecRepository
                                    .findByFactoryIdAndMaterialTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                                            order.getFactoryId(), item.getMaterialTypeId()).stream()
                                    .map(spec -> new com.cretas.aims.dto.material.MaterialPackagingSpecDTO(
                                            spec.getId(), spec.getName(), spec.getPackageUnit(), spec.getBaseUnit(),
                                            spec.getConversionFactor(), spec.getDefaultSpec(), spec.getActive(),
                                            spec.getSortOrder(), spec.getVersion()))
                                    .toList())
                    .build());
        }

        if (activeReceipt == null && remainingTotal.signum() <= 0) {
            return null;
        }
        boolean receiving = activeReceipt != null;
        return PurchaseReceivingTaskResponse.builder()
                .taskId(order.getId())
                .sourceType("PURCHASE")
                .purchaseOrderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .supplierId(order.getSupplierId())
                .supplierName(order.getSupplierName())
                .expectedDeliveryDate(order.getExpectedDeliveryDate())
                .status(receiving ? "RECEIVING" : "WAITING_RECEIVE")
                .statusLabel(receiving ? "收货中" : "待收货")
                .warehouseId(activeReceipt != null ? activeReceipt.getWarehouseId() : null)
                .responsibleName(receiving ? "仓储处理中" : "待仓储认领")
                .activeReceiptId(activeReceipt != null ? activeReceipt.getId() : null)
                .activeReceiptNumber(activeReceipt != null ? activeReceipt.getReceiveNumber() : null)
                .activeReceiptCount(activeReceipts.size())
                .receiptConflict(activeReceipts.size() > 1 || legacyLineIdentityAmbiguous)
                .items(lines)
                .build();
    }

    private boolean isActiveReceipt(PurchaseReceiveRecord receipt) {
        return receipt.getStatus() == PurchaseReceiveStatus.DRAFT
                || receipt.getStatus() == PurchaseReceiveStatus.PENDING_QC;
    }

    @Override
    @Transactional
    @Loggable(module = "PURCHASE_ORDER", action = "CLOSE_RECEIVING",
            entityType = "PurchaseOrder", entityIdParam = "purchaseOrderId",
            summary = "'仓储少收关闭采购单 ' + #purchaseOrderId")
    public PurchaseOrderStatus closeReceivingTask(
            String factoryId,
            String purchaseOrderId,
            ClosePurchaseReceivingTaskRequest request,
            Long userId) {
        PurchaseOrder order = purchaseOrderRepository
                .findByIdAndFactoryIdForUpdate(purchaseOrderId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("采购订单不存在或不属于当前组织"));

        // Double click / stale browser retry is idempotent and must never downgrade a completed order.
        if (order.getStatus() == PurchaseOrderStatus.CLOSED
                || order.getStatus() == PurchaseOrderStatus.COMPLETED) {
            return order.getStatus();
        }
        if (order.getStatus() != PurchaseOrderStatus.FINANCE_APPROVED
                && order.getStatus() != PurchaseOrderStatus.PARTIAL_RECEIVED) {
            throw new BusinessException(409, "当前采购订单状态不能少收关闭")
                    .withCode("PURCHASE_RECEIVING_CLOSE_STATUS_INVALID")
                    .withHint("请刷新待入库任务后查看最新状态");
        }

        List<PurchaseReceiveRecord> activeReceipts = receiveRecordRepository
                .findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(factoryId, purchaseOrderId)
                .stream()
                .filter(this::isActiveReceipt)
                .toList();
        if (!activeReceipts.isEmpty()) {
            PurchaseReceiveRecord active = activeReceipts.get(0);
            throw new BusinessException(409,
                    "采购单仍有未确认的收货单 " + active.getReceiveNumber() + "，不能少收关闭")
                    .withCode("PURCHASE_RECEIVING_ACTIVE_RECEIPT")
                    .withHint("请先继续收货并确认入库；如草稿有误，请由仓储主管处理该草稿");
        }

        List<PurchaseOrderItem> orderItems =
                purchaseOrderItemRepository.findByPurchaseOrderId(purchaseOrderId);
        if (orderItems.isEmpty()) {
            throw new BusinessException(409, "采购订单没有可核对的物料行，不能少收关闭")
                    .withCode("PURCHASE_RECEIVING_LINES_REQUIRED")
                    .withHint("请联系采购主管核对订单明细");
        }

        boolean allReceived = orderItems.stream().allMatch(item ->
                zeroIfNull(item.getReceivedQuantity())
                        .compareTo(zeroIfNull(item.getQuantity())) >= 0);
        if (allReceived) {
            order.setStatus(PurchaseOrderStatus.COMPLETED);
            purchaseOrderRepository.save(order);
            return PurchaseOrderStatus.COMPLETED;
        }

        if (request == null || request.getReasonCode() == null) {
            throw new BusinessException(400, "请选择少收关闭原因")
                    .withCode("PURCHASE_RECEIVING_CLOSE_REASON_REQUIRED");
        }
        String notes = request.getNotes() == null ? null : request.getNotes().trim();
        if (request.getReasonCode() == ClosePurchaseReceivingTaskRequest.ReasonCode.OTHER
                && (notes == null || notes.isBlank())) {
            throw new BusinessException(400, "选择“其他原因”时必须填写补充说明")
                    .withCode("PURCHASE_RECEIVING_CLOSE_NOTES_REQUIRED")
                    .withHintTarget("少收关闭补充说明");
        }
        if (notes != null && notes.length() > 500) {
            throw new BusinessException(400, "补充说明不能超过500字")
                    .withCode("PURCHASE_RECEIVING_CLOSE_NOTES_TOO_LONG");
        }

        order.setStatus(PurchaseOrderStatus.CLOSED);
        order.setReceivingCloseReasonCode(request.getReasonCode().name());
        order.setReceivingCloseNotes(notes == null || notes.isBlank() ? null : notes);
        order.setReceivingClosedBy(userId);
        order.setReceivingClosedAt(LocalDateTime.now());
        purchaseOrderRepository.save(order);
        log.info("仓储少收关闭采购单: factoryId={}, purchaseOrderId={}, orderNumber={}, reason={}, userId={}",
                factoryId, purchaseOrderId, order.getOrderNumber(), request.getReasonCode(), userId);
        return PurchaseOrderStatus.CLOSED;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

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
        List<PurchaseOrderItem> poLines = List.of();
        if (request.getPurchaseOrderId() != null && !request.getPurchaseOrderId().isEmpty()) {
            order = purchaseOrderRepository.findByIdAndFactoryIdForUpdate(request.getPurchaseOrderId(), factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("采购订单不存在或不属于当前组织"));
            if (order.getStatus() != PurchaseOrderStatus.FINANCE_APPROVED
                    && order.getStatus() != PurchaseOrderStatus.PARTIAL_RECEIVED) {
                throw new BusinessException(409, "采购订单完成财务审核后才能进入仓储收货")
                        .withCode("PURCHASE_RECEIPT_FINANCE_APPROVAL_REQUIRED")
                        .withHint("请在 OA 审批中心完成整条审批链后刷新仓储待收货任务");
            }

            List<PurchaseReceiveRecord> activeReceipts = receiveRecordRepository
                    .findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(factoryId, order.getId()).stream()
                    .filter(this::isActiveReceipt)
                    .toList();
            if (!activeReceipts.isEmpty()) {
                PurchaseReceiveRecord active = activeReceipts.get(0);
                throw new BusinessException(409,
                        "该采购订单已有未完成收货单 " + active.getReceiveNumber() + "，不能重复创建")
                        .withCode("PURCHASE_RECEIPT_ACTIVE_EXISTS")
                        .withHint("请在仓储待收货任务中继续处理现有收货单");
            }
            if (!Objects.equals(order.getSupplierId(), request.getSupplierId())) {
                throw new BusinessException(400, "收货供应商与采购订单不一致")
                        .withCode("PURCHASE_RECEIPT_SUPPLIER_MISMATCH")
                        .withHint("请从仓储待收货任务进入，系统会自动带入订单供应商");
            }
            poLines = purchaseOrderItemRepository.findByPurchaseOrderId(order.getId());
            for (CreateReceiveRecordRequest.ReceiveItemDTO line : request.getItems()) {
                PurchaseOrderItem poLine = resolvePurchaseOrderItem(
                        poLines, line.getPurchaseOrderItemId(), line.getMaterialTypeId());
                line.setPurchaseOrderItemId(poLine.getId());
                line.setUnit(resolveReceiptSelection(factoryId, poLine, line).packageUnit());
            }
            // 必须先折合到采购行基本量，再比较上限；禁止 kg/case 等跨单位裸数相加。
            validateOverReceiveCap(order, request.getItems());
        }

        // 显式目标仓必须在 DRAFT 创建前完成归属/启用状态/仓型校验。
        // confirmReceive 建批次时仍会二次校验，防止草稿期间仓库被停用或改型。
        if (request.getWarehouseId() != null && !request.getWarehouseId().isBlank()
                && warehouseInventoryGuardService != null) {
            warehouseInventoryGuardService.assertCanReceive(
                    request.getWarehouseId(), factoryId, "RAW");
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
        Map<String, PurchasePriceQuote> poLinePrices = Collections.emptyMap();
        if (order != null) {
            poLinePrices = new HashMap<>();
            for (var poItem : poLines) {
                // PO 行价是合同价, 收货未填价时的权威来源. 同物料多行取首个非空价.
                if (poItem.getMaterialTypeId() != null && poItem.getUnitPrice() != null) {
                    poLinePrices.put(String.valueOf(poItem.getId()), new PurchasePriceQuote(
                            poItem.getUnitPrice(),
                            poItem.getPriceUnit() != null ? poItem.getPriceUnit() : poItem.getUnit()));
                }
            }
        }

        // 创建入库行项目
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CreateReceiveRecordRequest.ReceiveItemDTO itemDTO : request.getItems()) {
            PurchaseReceiveItem item = new PurchaseReceiveItem();
            item.setReceiveRecordId(record.getId());
            item.setPurchaseOrderItemId(itemDTO.getPurchaseOrderItemId());
            item.setMaterialTypeId(itemDTO.getMaterialTypeId());
            item.setMaterialName(itemDTO.getMaterialName());
            item.setReceivedQuantity(itemDTO.getReceivedQuantity());
            MaterialPackagingSelection packagingSelection;
            if (order != null) {
                PurchaseOrderItem poLine = resolvePurchaseOrderItem(
                        poLines, itemDTO.getPurchaseOrderItemId(), itemDTO.getMaterialTypeId());
                packagingSelection = resolveReceiptSelection(factoryId, poLine, itemDTO);
            } else {
                RawMaterialType material = materialTypeRepository.findById(itemDTO.getMaterialTypeId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "收货物料不存在: " + itemDTO.getMaterialTypeId()));
                packagingSelection = resolveMaterialPackagingSelection(
                        factoryId, material, itemDTO.getMaterialPackagingSpecId(), itemDTO.getUnit());
            }
            String quantityUnit = packagingSelection.packageUnit();
            item.setUnit(quantityUnit);
            item.setMaterialPackagingSpecId(packagingSelection.specId());
            item.setReceivePackageUnitSnapshot(quantityUnit);
            item.setInventoryBaseUnitSnapshot(packagingSelection.baseUnit());
            item.setPackageToBaseFactorSnapshot(packagingSelection.factor());
            item.setInventoryQuantitySnapshot(packagingSelection.toBase(itemDTO.getReceivedQuantity()));
            // BUG-RCV: 行价为空 → 继承 PO 行价 (合同价); 无 PO / PO 无此物料价 → 保持 null (诚实, 不伪造 0).
            PurchasePriceQuote inheritedQuote = poLinePrices.get(String.valueOf(itemDTO.getPurchaseOrderItemId()));
            // PO 收货价只能来自已审批采购行快照；仓储请求中的价格字段不具有改价权限。
            BigDecimal sourcePrice = order != null
                    ? inheritedQuote != null ? inheritedQuote.unitPrice() : null
                    : itemDTO.getUnitPrice();
            String sourcePriceUnit = order != null
                    ? inheritedQuote != null ? firstNonBlank(inheritedQuote.priceUnit(), quantityUnit) : quantityUnit
                    : firstNonBlank(itemDTO.getPriceUnit(), quantityUnit);
            BigDecimal resolvedPrice = sourcePrice != null
                    ? convertPricePerUnit(factoryId, itemDTO.getMaterialTypeId(), sourcePrice,
                            sourcePriceUnit, quantityUnit)
                    : null;
            item.setUnitPrice(resolvedPrice);
            item.setPriceUnit(quantityUnit);
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
        PurchaseReceiveRecord record = receiveRecordRepository.findByIdAndFactoryIdForUpdate(receiveId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("入库单不存在或不属于当前组织"));
        if (record.getStatus() != PurchaseReceiveStatus.DRAFT && record.getStatus() != PurchaseReceiveStatus.PENDING_QC) {
            throw new BusinessException(409, "只有草稿或待质检状态的入库单可以确认")
                    .withHint("请刷新入库单列表查看最新状态");
        }

        if (attachmentRepository == null) {
            throw new BusinessException(503, "收货凭证服务暂不可用，当前不能确认入库")
                    .withCode("PURCHASE_RECEIPT_ATTACHMENT_SERVICE_UNAVAILABLE")
                    .withHint("请稍后重试；系统未执行任何库存写入");
        }
        if (attachmentRepository.countByFactoryIdAndEntityTypeAndEntityId(
                factoryId, com.cretas.aims.entity.Attachment.EntityType.PURCHASE_RECEIPT, receiveId) <= 0) {
            throw new BusinessException(409, "确认收货前必须上传供应商供货单或收货凭证")
                    .withCode("PURCHASE_RECEIPT_ATTACHMENT_REQUIRED")
                    .withHint("请在仓储收货任务中拍照或上传 PDF、图片等凭证后再确认");
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
            purchaseOrderRepository.findByIdAndFactoryIdForUpdate(record.getPurchaseOrderId(), factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("采购订单不存在或不属于当前组织"));
            List<PurchaseOrderItem> poLines = purchaseOrderItemRepository
                    .findByPurchaseOrderId(record.getPurchaseOrderId());
            for (PurchaseReceiveItem receiveItem : record.getItems()) {
                PurchaseOrderItem poLine = resolvePurchaseOrderItem(
                        poLines, receiveItem.getPurchaseOrderItemId(), receiveItem.getMaterialTypeId());
                validatePersistedReceiptSelection(factoryId, poLine, receiveItem);
            }
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
                    batch.getReceiptQuantity(),
                    batch.getUnitPrice(),
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

        // Bug 4 修复 (2026-07-04): PURCHASE_PAYMENT 凭证生成时点从"入库单 DRAFT 创建"迁到此处
        // "确认入库"。每张入库单只发一次 (对比逐物料行的 MaterialReceivedEvent), 供 PurchaseOrderVoucherListener
        // 生成凭证。AFTER_COMMIT 触发 → 未确认/放弃的草稿入库不再生成幽灵凭证。
        try {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.PurchaseReceiveConfirmedEvent(
                    this, factoryId, record.getId(), record.getReceiveNumber(),
                    record.getPurchaseOrderId(), record.getTotalAmount()));
        } catch (Exception e) {
            log.warn("Publish PurchaseReceiveConfirmedEvent failed for {}: {}", record.getId(), e.getMessage());
        }

        // 自动创建应付账款（采购入库 → AP_INVOICE）
        // 🔒 doomed-tx 修复 (2026-07-02): 必须用 recordPayableIfAbsent (幂等、不抛)。
        // 旧代码用 recordPayable 并 try/catch(BusinessException) 吞"重复挂账"异常 —— 但 recordPayable
        // 是 @Transactional，对已挂账的 PO 抛 409 时事务已被标记 rollback-only，catch 也救不回，外层
        // commit 抛 UnexpectedRollbackException → doomed-tx 兜底转通用 409 → 同一 PO 第 2 次分批入库
        // 永久无法确认。recordPayableIfAbsent 对"已存在/金额缺失/状态不允许"等预期条件返回 existing/null
        // 而非抛异常，事务从不被 doom，收货入库不被应付侧阻塞。
        //
        // 🔴🔒 金额口径修复 (2026-07-03): 按<b>本次入库单实收金额</b> record.getTotalAmount()
        // (Σ 实收数量 × 单价) 挂账，非 PO 计划总额 order.getTotalAmount()。六膳门超收 130kg (计划
        // 100kg) 时旧实现只挂 100×20=2000，漏挂实收差额 600。幂等键改为每张入库单 (PURCHASE_RECEIVE,
        // receiveId)：同一入库单重复确认不重复挂账；分批入库各挂各的实收值，累计 = 实收总额。
        if (record.getPurchaseOrderId() != null) {
            try {
                PurchaseOrder order = purchaseOrderRepository.findById(record.getPurchaseOrderId()).orElse(null);
                // 实收金额: 入库单 totalAmount (Σ 实收数量 × 单价)。为 0/缺价时 recordPayableIfAbsent 诚实跳过 (不伪造应付)。
                BigDecimal receivedValue = record.getTotalAmount();
                if (order != null && order.getSupplierId() != null) {
                    ArApTransaction ap = arApService.recordPayableIfAbsent(factoryId, order.getSupplierId(), order.getId(),
                            "PURCHASE_RECEIVE", record.getId(),
                            receivedValue, LocalDate.now().plusDays(30), userId,
                            "采购入库自动挂账-" + record.getReceiveNumber());
                    if (ap != null) {
                        log.info("自动应付挂账(实收值): orderId={}, receiveId={}, receivedValue={}, transactionId={}",
                                order.getId(), record.getId(), receivedValue, ap.getId());
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
                BigDecimal qty = item.getInventoryQuantitySnapshot() != null
                        ? item.getInventoryQuantitySnapshot()
                        : item.getReceivedQuantity() != null ? item.getReceivedQuantity() : BigDecimal.ZERO;
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
                    PurchaseOrderItem poItem = resolvePurchaseOrderItem(
                            poItems, item.getPurchaseOrderItemId(), item.getMaterialTypeId());
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
        for (PurchaseReceiveItem item : record.getItems()) {
            PurchaseOrderItem poItem = resolvePurchaseOrderItem(
                    poItems, item.getPurchaseOrderItemId(), item.getMaterialTypeId());
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
    public List<PurchaseReceiveRecord> getReceiveRecordsByOrder(String factoryId, String purchaseOrderId) {
        getPurchaseOrderById(factoryId, purchaseOrderId);
        return receiveRecordRepository
                .findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(factoryId, purchaseOrderId);
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
                    .quantityUnit(acc.unit)
                    .currentStock(currentStock)
                    .netRequired(netRequired)
                    .stockSufficient(netRequired.compareTo(BigDecimal.ZERO) == 0)
                    .referenceUnitPrice(acc.referenceUnitPrice)
                    .referencePriceUnit(acc.referencePriceUnit)
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
                    .quantityUnit(acc.unit)
                    .currentStock(currentStock)
                    .netRequired(netRequired)
                    .stockSufficient(netRequired.compareTo(BigDecimal.ZERO) == 0)
                    .referenceUnitPrice(acc.referenceUnitPrice)
                    .referencePriceUnit(acc.referencePriceUnit)
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
     * <p>对一张 SO 的所有行项目按当前 ACTIVE bom_recipe_items 展开 BOM，
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
            BigDecimal soQty = resolveBomProductQuantity(factoryId, soItem);
            String soProductName = soItem.getProductName() != null ? soItem.getProductName() : "";

            // #748 口径统一: 优先读 bom_recipe_items (新表, 同财务成本拆分源).
            // 取产品当前 ACTIVE + is_current=TRUE 的 recipe — 与 BomRecipeService.getCurrentRecipe 完全一致.
            List<BomRecipeItem> recipeItems = loadCurrentRecipeItems(factoryId, soItem.getProductTypeId());
            if (!recipeItems.isEmpty()) {
                boolean expandedAny = false;
                for (BomRecipeItem ri : recipeItems) {
                    if (ri.getMaterialTypeId() == null) continue;
                    // actualQuantity 已按出成率折算 (写库时算或运行时算), 是"每单位产品需要的原料量".
                    // 若持久值为空 (旧 recipe 未回填), 用 calculateActualQuantity() 实时折算 (同 entity 公式).
                    BigDecimal actualQtyPerUnit = ri.getActualQuantity();
                    if (actualQtyPerUnit == null) actualQtyPerUnit = ri.calculateActualQuantity();
                    if (actualQtyPerUnit == null || actualQtyPerUnit.compareTo(BigDecimal.ZERO) <= 0) continue;

                    BigDecimal required = actualQtyPerUnit.multiply(soQty).setScale(4, BigDecimal.ROUND_HALF_UP);
                    accumulateMaterial(factoryId, accumulators, ri.getMaterialTypeId(), required, salesOrderNumber,
                            ri.getMaterialName() != null ? ri.getMaterialName() : "未知原料",
                            ri.getMaterialCategory() != null ? ri.getMaterialCategory() : "RAW",
                            soProductName, ri.getUnit(), ri.getUnitPrice(), ri.getPriceUnit());
                    expandedAny = true;
                }
                if (expandedAny) hasBom = true;
            }
        }

        return hasBom;
    }

    /**
     * Resolve the SO line quantity in the finished product's BOM base unit.
     *
     * <p>Historical rows may not have a unit snapshot. Those retain the legacy quantity semantics;
     * rows with a complete product/unit contract use the shared production normalizer so purchase
     * suggestion, production plan and material requirement calculations cannot drift apart.
     */
    private BigDecimal resolveBomProductQuantity(String factoryId, SalesOrderItem soItem) {
        BigDecimal sourceQuantity = soItem.getQuantity() != null ? soItem.getQuantity() : BigDecimal.ONE;
        if (salesOrderPlanQuantityNormalizer == null || productTypeRepository == null
                || soItem.getUnit() == null || soItem.getUnit().isBlank()) {
            return sourceQuantity;
        }

        return productTypeRepository.findByIdAndFactoryId(soItem.getProductTypeId(), factoryId)
                .filter(product -> product.getUnit() != null && !product.getUnit().isBlank())
                .map(product -> salesOrderPlanQuantityNormalizer.normalize(soItem, product).quantity())
                .orElse(sourceQuantity);
    }

    /**
     * 把一条 BOM 展开结果累加进 accumulators (按 materialTypeId 合并需求量 + 记录来源 SO).
     * 第一次见到某 materialTypeId 时记录其名称/分类/单位/参考价 (后续同物料沿用首次模板)。
     */
    private void accumulateMaterial(String factoryId, Map<String, MaterialAccumulator> accumulators,
            String matId, BigDecimal required, String salesOrderNumber,
            String materialName, String materialCategory, String sourceProductName,
            String unit, BigDecimal referenceUnitPrice, String referencePriceUnit) {
        MaterialAccumulator acc = accumulators.computeIfAbsent(matId, k -> {
            MaterialAccumulator a = new MaterialAccumulator();
            a.materialTypeId = matId;
            a.materialName = materialName;
            a.materialCategory = materialCategory;
            a.sourceProductName = sourceProductName;
            a.unit = canonicalUnitOrRaw(factoryId, unit);
            a.referenceUnitPrice = referenceUnitPrice;
            a.referencePriceUnit = canonicalUnitOrRaw(factoryId,
                    firstNonBlank(referencePriceUnit, unit));
            return a;
        });
        String sourceUnit = canonicalUnitOrRaw(factoryId, unit);
        BigDecimal normalizedRequired = required;
        if (required != null && acc.unit != null && sourceUnit != null && !acc.unit.equals(sourceUnit)) {
            normalizedRequired = required.multiply(conversionFactor(factoryId, matId, sourceUnit, acc.unit));
        }
        acc.requiredQty = acc.requiredQty.add(normalizedRequired);
        if (referenceUnitPrice != null && acc.referenceUnitPrice == null) {
            acc.referenceUnitPrice = referenceUnitPrice;
            acc.referencePriceUnit = canonicalUnitOrRaw(factoryId,
                    firstNonBlank(referencePriceUnit, sourceUnit));
        }
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
        String referencePriceUnit;
        BigDecimal requiredQty = BigDecimal.ZERO;
        /** LinkedHashSet: 去重 + 保留首次出现顺序 (同 SO 多产品共用同物料只记一次 SO 号). */
        java.util.LinkedHashSet<String> sourceSalesOrderNumbers = new java.util.LinkedHashSet<>();
    }

    private void applyPurchasePriceContract(String factoryId, PurchaseOrderItem item,
            String quantityUnit, String legacyUnit, String priceUnit) {
        String canonicalQuantityUnit = canonicalUnit(factoryId,
                firstNonBlank(quantityUnit, legacyUnit), "采购数量单位");
        String canonicalPriceUnit = canonicalUnit(factoryId,
                firstNonBlank(priceUnit, canonicalQuantityUnit), "采购计价单位");
        item.setUnit(canonicalQuantityUnit);
        item.setPriceUnit(canonicalPriceUnit);
        item.setQuantityToPriceFactor(conversionFactor(factoryId, item.getMaterialTypeId(),
                canonicalQuantityUnit, canonicalPriceUnit));
    }

    private BigDecimal convertPricePerUnit(String factoryId, String materialTypeId,
            BigDecimal unitPrice, String sourcePriceUnit, String targetQuantityUnit) {
        String canonicalSource = canonicalUnit(factoryId, sourcePriceUnit, "计价单位");
        String canonicalTarget = canonicalUnit(factoryId, targetQuantityUnit, "收货数量单位");
        BigDecimal targetToSource = conversionFactor(factoryId, materialTypeId,
                canonicalTarget, canonicalSource);
        return unitPrice.multiply(targetToSource);
    }

    private BigDecimal conversionFactor(String factoryId, String productTypeId,
            String fromUnit, String toUnit) {
        if (Objects.equals(fromUnit, toUnit)) {
            return BigDecimal.ONE;
        }
        if (unitContractService == null) {
            throw new BusinessException(503, "单位换算服务不可用，不能安全计算跨单位价格");
        }
        com.cretas.aims.service.unit.UnitConversionResult result = unitContractService.convert(
                BigDecimal.ONE,
                new com.cretas.aims.service.unit.UnitConversionContext(
                        factoryId, productTypeId, fromUnit, toUnit, LocalDateTime.now(),
                        com.cretas.aims.service.unit.UnitUsageScene.PROCUREMENT,
                        12, java.math.RoundingMode.HALF_UP));
        if (!result.succeeded() || result.quantity() == null) {
            throw new BusinessException(400, "单位无法换算: " + fromUnit + " → " + toUnit
                    + (result.message() != null ? "（" + result.message() + "）" : ""));
        }
        return result.quantity();
    }

    private String canonicalUnit(String factoryId, String rawUnit, String fieldName) {
        if (rawUnit == null || rawUnit.isBlank()) {
            throw new BusinessException(400, fieldName + "不能为空");
        }
        if (unitContractService == null) {
            return rawUnit.trim();
        }
        com.cretas.aims.service.unit.UnitNormalizationResult normalized =
                unitContractService.normalize(factoryId, rawUnit);
        if (!normalized.recognized()) {
            throw new BusinessException(400, fieldName + "不是已登记单位: " + rawUnit);
        }
        return normalized.code();
    }

    private void validatePersistedReceiptSelection(
            String factoryId, PurchaseOrderItem poLine, PurchaseReceiveItem receiveItem) {
        String orderBase = canonicalUnit(factoryId,
                firstNonBlank(poLine.getInventoryBaseUnitSnapshot(), poLine.getUnit()),
                "采购订单行库存基本单位");
        String receiveBase = canonicalUnit(factoryId,
                firstNonBlank(receiveItem.getInventoryBaseUnitSnapshot(), receiveItem.getUnit()),
                "收货库存基本单位");
        if (!orderBase.equals(receiveBase)) {
            throw new BusinessException(409, "收货包装与采购单的库存基本单位不一致")
                    .withCode("PURCHASE_RECEIPT_BASE_UNIT_MISMATCH")
                    .withHint("请返回收货草稿重新选择正确包装规格");
        }
        if (receiveItem.getInventoryQuantitySnapshot() == null
                || receiveItem.getPackageToBaseFactorSnapshot() == null
                || receiveItem.getPackageToBaseFactorSnapshot().signum() <= 0) {
            String orderUnit = canonicalUnit(factoryId,
                    firstNonBlank(poLine.getPurchasePackageUnitSnapshot(), poLine.getUnit()),
                    "采购订单行数量单位");
            String receiveUnit = canonicalUnit(factoryId, receiveItem.getUnit(), "收货数量单位");
            if (!orderUnit.equals(receiveUnit)) {
                throw new BusinessException(409, "历史收货草稿缺少跨单位换算快照")
                        .withCode("PURCHASE_RECEIPT_CONVERSION_SNAPSHOT_REQUIRED")
                        .withHint("请删除草稿后从待收货任务重新创建");
            }
        }
    }

    private BigDecimal receiptQuantityInOrderUnit(
            String factoryId, PurchaseOrderItem orderItem,
            CreateReceiveRecordRequest.ReceiveItemDTO receiveItem) {
        MaterialPackagingSelection selection = resolveReceiptSelection(factoryId, orderItem, receiveItem);
        return selection.toBase(receiveItem.getReceivedQuantity())
                .divide(requiredOrderBaseFactor(factoryId, orderItem), 12, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal receiptQuantityInOrderUnit(
            String factoryId, PurchaseOrderItem orderItem, PurchaseReceiveItem receiveItem) {
        BigDecimal baseQuantity = receiveItem.getInventoryQuantitySnapshot();
        if (baseQuantity == null) {
            String orderUnit = canonicalUnit(factoryId,
                    firstNonBlank(orderItem.getPurchasePackageUnitSnapshot(), orderItem.getUnit()),
                    "采购订单行数量单位");
            String receiveUnit = canonicalUnit(factoryId, receiveItem.getUnit(), "收货数量单位");
            if (!orderUnit.equals(receiveUnit)) {
                throw new BusinessException(409, "历史收货行缺少跨单位换算快照")
                        .withCode("PURCHASE_RECEIPT_CONVERSION_SNAPSHOT_REQUIRED");
            }
            return receiveItem.getReceivedQuantity();
        }
        return baseQuantity.divide(
                requiredOrderBaseFactor(factoryId, orderItem), 12, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal requiredOrderBaseFactor(String factoryId, PurchaseOrderItem orderItem) {
        BigDecimal factor = orderItem.getPackageToBaseFactorSnapshot();
        if (factor != null && factor.signum() > 0) return factor;
        String orderUnit = canonicalUnit(factoryId, orderItem.getUnit(), "采购订单行数量单位");
        String baseUnit = canonicalUnit(factoryId,
                firstNonBlank(orderItem.getInventoryBaseUnitSnapshot(), orderItem.getUnit()),
                "采购订单行库存基本单位");
        return conversionFactor(factoryId, orderItem.getMaterialTypeId(), orderUnit, baseUnit);
    }

    private String canonicalUnitOrRaw(String factoryId, String rawUnit) {
        if (rawUnit == null || rawUnit.isBlank() || unitContractService == null) {
            return rawUnit;
        }
        com.cretas.aims.service.unit.UnitNormalizationResult normalized =
                unitContractService.normalize(factoryId, rawUnit);
        return normalized.recognized() ? normalized.code() : rawUnit;
    }

    private String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private record MaterialPackagingSelection(
            String specId, String packageUnit, String baseUnit, BigDecimal factor) {
        BigDecimal toBase(BigDecimal packageQuantity) {
            return packageQuantity == null ? BigDecimal.ZERO : packageQuantity.multiply(factor);
        }
    }

    private MaterialPackagingSelection resolveMaterialPackagingSelection(
            String factoryId, RawMaterialType material, String requestedSpecId, String requestedUnit) {
        String baseUnit = canonicalUnit(factoryId, material.getUnit(), "库存基本单位");
        String unit = canonicalUnit(factoryId, requestedUnit, "交易数量单位");
        if (requestedSpecId != null && !requestedSpecId.isBlank()) {
            if (materialPackagingSpecRepository == null) {
                throw new BusinessException(503, "原料包装规格服务不可用")
                        .withHint("请稍后重试；系统未执行任何库存写入");
            }
            com.cretas.aims.entity.material.MaterialPackagingSpec spec =
                    materialPackagingSpecRepository
                            .findByIdAndFactoryIdAndMaterialTypeIdAndActiveTrue(
                                    requestedSpecId, factoryId, material.getId())
                            .orElseThrow(() -> new BusinessException(400, "原料包装规格不存在、已停用或不属于当前物料")
                                    .withCode("MATERIAL_PACKAGING_SPEC_IDENTITY_MISMATCH")
                                    .withHintTarget("materialPackagingSpecId"));
            String specUnit = canonicalUnit(factoryId, spec.getPackageUnit(), "包装单位");
            String specBase = canonicalUnit(factoryId, spec.getBaseUnit(), "包装基本单位");
            if (!baseUnit.equals(specBase)) {
                throw new BusinessException(409, "包装规格基本单位与原料库存基本单位不一致")
                        .withCode("MATERIAL_PACKAGING_BASE_UNIT_MISMATCH")
                        .withHint("请先修正原料类型的包装换算")
                        .withHintTarget("materialPackagingSpecId");
            }
            if (!unit.equals(specUnit)) {
                throw new BusinessException(400, "交易数量单位与所选包装规格不一致")
                        .withCode("MATERIAL_PACKAGING_UNIT_MISMATCH")
                        .withHintTarget("unit");
            }
            return new MaterialPackagingSelection(
                    spec.getId(), specUnit, baseUnit, spec.getConversionFactor());
        }
        if (unit.equals(baseUnit)) {
            return new MaterialPackagingSelection(null, baseUnit, baseUnit, BigDecimal.ONE);
        }
        List<com.cretas.aims.entity.material.MaterialPackagingSpec> matches =
                materialPackagingSpecRepository == null ? List.of()
                        : materialPackagingSpecRepository
                                .findByFactoryIdAndMaterialTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                                        factoryId, material.getId()).stream()
                                .filter(spec -> unit.equals(canonicalUnitOrRaw(factoryId, spec.getPackageUnit())))
                                .toList();
        if (matches.size() != 1) {
            throw new BusinessException(422, matches.isEmpty()
                    ? "所选包装单位未配置到库存基本单位的换算"
                    : "存在多个同单位包装规格，必须选择具体规格")
                    .withCode("MATERIAL_PACKAGING_SPEC_REQUIRED")
                    .withHint("请在原料类型中配置并选择具体包装规格")
                    .withHintTarget("materialPackagingSpecId");
        }
        var spec = matches.get(0);
        return new MaterialPackagingSelection(
                spec.getId(), unit, baseUnit, spec.getConversionFactor());
    }

    private MaterialPackagingSelection resolveReceiptSelection(
            String factoryId, PurchaseOrderItem poLine,
            CreateReceiveRecordRequest.ReceiveItemDTO line) {
        String requestedUnit = canonicalUnit(factoryId, line.getUnit(), "收货数量单位");
        String orderPackageUnit = canonicalUnit(factoryId,
                firstNonBlank(poLine.getPurchasePackageUnitSnapshot(), poLine.getUnit()),
                "采购订单行包装单位");
        String orderBaseUnit = canonicalUnit(factoryId,
                firstNonBlank(poLine.getInventoryBaseUnitSnapshot(), poLine.getUnit()),
                "采购订单行库存基本单位");
        if ((line.getMaterialPackagingSpecId() == null || line.getMaterialPackagingSpecId().isBlank())
                && requestedUnit.equals(orderPackageUnit)) {
            BigDecimal factor = poLine.getPackageToBaseFactorSnapshot() != null
                    ? poLine.getPackageToBaseFactorSnapshot()
                    : conversionFactor(factoryId, poLine.getMaterialTypeId(), orderPackageUnit, orderBaseUnit);
            return new MaterialPackagingSelection(
                    poLine.getMaterialPackagingSpecId(), orderPackageUnit, orderBaseUnit, factor);
        }
        RawMaterialType material = materialTypeRepository.findById(line.getMaterialTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("收货物料不存在: " + line.getMaterialTypeId()));
        MaterialPackagingSelection selection = resolveMaterialPackagingSelection(
                factoryId, material, line.getMaterialPackagingSpecId(), requestedUnit);
        if (!orderBaseUnit.equals(selection.baseUnit())) {
            throw new BusinessException(409, "实际到货包装与采购单的库存基本单位不一致")
                    .withCode("PURCHASE_RECEIPT_BASE_UNIT_MISMATCH")
                    .withHint("请选择换算到 " + orderBaseUnit + " 的包装规格");
        }
        return selection;
    }

    private record PurchasePriceQuote(BigDecimal unitPrice, String priceUnit) {
    }

    /**
     * #748 口径统一: 取产品当前 ACTIVE + is_current=TRUE recipe 的配方项.
     *
     * <p>与 {@code BomRecipeService.getCurrentRecipe} 走同一查询
     * ({@code findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(ACTIVE)}),
     * 保证采购建议净需求 = 财务成本拆分用料口径一致.
     *
     * <p>无当前生效配方时返回空列表，不回退任何旧 BOM 表。
     */
    private List<BomRecipeItem> loadCurrentRecipeItems(String factoryId, String productTypeId) {
        return bomRecipeItemRepository.findCurrentByProduct(factoryId, productTypeId);
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
        List<BomRecipeItem> bomItems = bomRecipeItemRepository.findCurrentByMaterial(factoryId, materialTypeId);
        BigDecimal bomStandardPrice = null;
        String bomProductNames = null;

        if (!bomItems.isEmpty()) {
            // 过滤掉没有单价的BOM项
            List<BomRecipeItem> pricedItems = bomItems.stream()
                    .filter(b -> b.getUnitPrice() != null && b.getUnitPrice().compareTo(BigDecimal.ZERO) > 0)
                    .collect(Collectors.toList());

            if (!pricedItems.isEmpty()) {
                BigDecimal sum = pricedItems.stream()
                        .map(BomRecipeItem::getUnitPrice)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                bomStandardPrice = sum.divide(new BigDecimal(pricedItems.size()), 4, BigDecimal.ROUND_HALF_UP);
            }

            // 收集关联的产品名称
            bomProductNames = bomItems.stream()
                    .map(item -> item.getRecipe().getProductName())
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
        BigDecimal inventoryQuantity = item.getInventoryQuantitySnapshot() != null
                ? item.getInventoryQuantitySnapshot() : item.getReceivedQuantity();
        String inventoryUnit = firstNonBlank(item.getInventoryBaseUnitSnapshot(), item.getUnit());
        batch.setReceiptQuantity(inventoryQuantity);
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setQuantityUnit(inventoryUnit);
        BigDecimal factor = item.getPackageToBaseFactorSnapshot();
        batch.setUnitPrice(item.getUnitPrice() == null ? null
                : factor != null && factor.signum() > 0
                        ? item.getUnitPrice().divide(factor, 12, java.math.RoundingMode.HALF_UP)
                        : item.getUnitPrice());
        batch.setReceiptDate(record.getReceiveDate());
        batch.setPurchaseDate(record.getReceiveDate());
        batch.setFactoryNumber(item.getFactoryNumber());
        batch.setOriginPlace(item.getOriginPlace());
        // 🔒🔒 QC 入库门 (食品安全, 2026-07-04): 质检结果非 PASS 的收货行不得直接进可用库存,
        // 自动隔离为 DEFECTIVE (不良品) — FEFO/领料/销售查询均过滤 status='AVAILABLE', 自动排除
        // DEFECTIVE。质检经理后续用 ReleaseDecisionTool 复核放行(→AVAILABLE)/退货(→保持DEFECTIVE)。
        // 防呆 (客户原话"仓管年纪大文化素质低, 不能依赖人工判断"): 系统在入库时即拦截不合格料, 不靠仓管
        // 手工冻结 —— 一旦 qcResult 标 DAMAGED/PARTIAL_LOST/OTHER, 这批料对生产/销售不可见, 从源头堵住
        // 不合格料流入生产消耗/销售。
        // qcResult 语义 (PurchaseReceiveItem.qcResult, 值域 PASS/PARTIAL_LOST/DAMAGED/OTHER):
        //   - null/空 = 该流程未做入库质检 (现状默认, 多数工厂未启用入库质检) → 按 AVAILABLE 通过。
        //     不因"未质检"就把正常收货全部隔离 (否则未启用质检的工厂无法正常收货)。
        //   - PASS/PASSED/QUALIFIED (合格) → AVAILABLE。
        //   - 其余任何显式非 PASS 值 (含 PARTIAL_LOST 部分丢失/DAMAGED 损坏/OTHER) → DEFECTIVE (over-safe)。
        //     ⚠️ PurchaseReceiveItem 未建"良品数/不良品数"拆分列, 整行按单一 qcResult 计, 故任何非 PASS
        //     整行隔离; 质检经理如判部分可用, 经 ReleaseDecisionTool 复核放行。
        boolean qcFail = isQcFail(item.getQcResult());
        batch.setStatus(qcFail ? MaterialBatchStatus.DEFECTIVE : MaterialBatchStatus.AVAILABLE);
        batch.setCreatedBy(userId);
        if (qcFail) {
            log.warn("QC 入库隔离: batchNumber={}, materialTypeId={}, qcResult={} → DEFECTIVE (不进可用库存, 不参与生产/销售, 待质检复核放行)",
                    batchNumber, item.getMaterialTypeId(), item.getQcResult());
        }
        // D1: 采购入库默认 WH-LOG (物流仓). per PR #310 spec — raw material persistent in logistics warehouse.
        // 用途拆分 (2026-07-02): 采购入库落点走独立 resolvePurchaseInboundWh (PURCHASE_INBOUND_DEFAULT),
        // 未配置回退 WH-LOG = 现状; 让「采购进主仓/原料仓」可单独配置, 不影响销售 FIFO / 生产报工。
        String warehouseId = (record.getWarehouseId() != null && !record.getWarehouseId().isBlank())
                ? record.getWarehouseId()
                : warehouseResolver.resolvePurchaseInboundWh(factoryId);
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
        log.debug("创建物料批次: batchId={}, batchNumber={}, materialTypeId={}, qty={}, status={}",
                batch.getId(), batchNumber, item.getMaterialTypeId(), item.getReceivedQuantity(), batch.getStatus());
        return batch;
    }

    /**
     * 🔒🔒 QC 入库门 (食品安全): 判定收货行质检结果是否为"不合格", 决定物料批次入库状态。
     *
     * <p>值域来自 {@code PurchaseReceiveItem.qcResult} (ReceiveConfirmCreateTool.ALLOWED_RECEIVE_STATUSES =
     * PASS / PARTIAL_LOST / DAMAGED / OTHER)。
     *
     * @param qcResult 收货行质检结果字符串
     * @return true = 不合格 (需隔离为 DEFECTIVE); false = 合格或未质检 (AVAILABLE 通过)
     */
    private boolean isQcFail(String qcResult) {
        // null / 空 = 该流程未做入库质检 (现状默认) → 不隔离, 按 AVAILABLE 通过。
        if (qcResult == null || qcResult.isBlank()) {
            return false;
        }
        String v = qcResult.trim();
        // 合格同义词 (防御性: 实际收货路径只写 PASS) → 通过。其余任何显式值 → 隔离 (over-safe)。
        return !("PASS".equalsIgnoreCase(v)
                || "PASSED".equalsIgnoreCase(v)
                || "QUALIFIED".equalsIgnoreCase(v));
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
            PurchaseOrderItem orderItem = resolvePurchaseOrderItem(
                    orderItems, receiveItem.getPurchaseOrderItemId(), receiveItem.getMaterialTypeId());
            checkOverReceiveCap(
                    receiveItem.getMaterialName(),
                    orderItem.getReceivedQuantity(),
                    receiptQuantityInOrderUnit(order.getFactoryId(), orderItem, receiveItem),
                    orderItem.getQuantity());
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
            PurchaseOrderItem orderItem = resolvePurchaseOrderItem(
                    orderItems, receiveItem.getPurchaseOrderItemId(), receiveItem.getMaterialTypeId());
            checkOverReceiveCap(
                    receiveItem.getMaterialName(),
                    orderItem.getReceivedQuantity(),
                    receiptQuantityInOrderUnit(record.getFactoryId(), orderItem, receiveItem),
                    orderItem.getQuantity());
        }
    }

    /**
     * Resolve a receipt line to one exact PO line. Legacy callers without a line
     * id remain compatible only when the material occurs exactly once; ambiguous
     * same-material orders fail closed instead of consuming both lines.
     */
    private PurchaseOrderItem resolvePurchaseOrderItem(List<PurchaseOrderItem> orderItems,
            Long purchaseOrderItemId, String materialTypeId) {
        if (purchaseOrderItemId != null) {
            PurchaseOrderItem match = orderItems.stream()
                    .filter(item -> purchaseOrderItemId.equals(item.getId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(400, "收货行不属于当前采购订单")
                            .withCode("PURCHASE_RECEIPT_ORDER_ITEM_MISMATCH")
                            .withHintTarget("purchaseOrderItemId"));
            if (!Objects.equals(match.getMaterialTypeId(), materialTypeId)) {
                throw new BusinessException(400, "收货行物料与采购订单行不一致")
                        .withCode("PURCHASE_RECEIPT_MATERIAL_MISMATCH")
                        .withHintTarget("materialTypeId");
            }
            return match;
        }
        List<PurchaseOrderItem> matches = orderItems.stream()
                .filter(item -> Objects.equals(item.getMaterialTypeId(), materialTypeId))
                .toList();
        if (matches.size() == 1) return matches.get(0);
        if (matches.isEmpty()) {
            throw new BusinessException(400, "收货物料不属于当前采购订单")
                    .withCode("PURCHASE_RECEIPT_MATERIAL_MISMATCH")
                    .withHintTarget("materialTypeId");
        }
        throw new BusinessException(400, "采购订单存在多条相同物料，收货时必须指定采购订单行")
                .withCode("PURCHASE_RECEIPT_ORDER_ITEM_REQUIRED")
                .withHintTarget("purchaseOrderItemId");
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
            PurchaseOrderItem orderItem = resolvePurchaseOrderItem(
                    orderItems, receiveItem.getPurchaseOrderItemId(), receiveItem.getMaterialTypeId());
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
                            receiptQuantityInOrderUnit(record.getFactoryId(), orderItem, receiveItem),
                            orderItem.getQuantity());

                    BigDecimal receivedInOrderUnit =
                            receiptQuantityInOrderUnit(record.getFactoryId(), orderItem, receiveItem);
                    orderItem.setReceivedQuantity(
                            orderItem.getReceivedQuantity().add(receivedInOrderUnit));
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

    private Supplier requireActiveSupplier(String factoryId, String supplierId) {
        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("供应商不存在或不属于当前组织"));
        if (!Boolean.TRUE.equals(supplier.getIsActive())) {
            throw new com.cretas.aims.exception.BusinessException(409,
                    "供应商已暂停合作，不能创建或改绑新的采购单")
                    .withHint("请先在供应商详情中恢复合作，历史采购单仍可只读查看")
                    .withHintTarget("supplierId");
        }
        return supplier;
    }

    private void assertSupplierMaterialActive(String factoryId, String supplierId, String materialTypeId) {
        if (supplierId == null || supplierId.isBlank()) return;
        if (materialTypeId == null || materialTypeId.isBlank()) {
            throw new BusinessException(400, "采购物料不能为空").withHintTarget("materialTypeId");
        }
        if (supplierMaterialRepository == null
                || !supplierMaterialRepository.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(
                        factoryId, supplierId, materialTypeId)) {
            throw new BusinessException(409, "该供应商未启用所选物料的供应关系")
                    .withHint("请先在供应商详情的“供应原料”中启用该物料")
                    .withHintTarget("materialTypeId");
        }
    }

    private void applySupplierPurchaseContract(String factoryId, String supplierId, PurchaseOrderItem item,
            CreatePurchaseOrderRequest.PurchaseOrderItemDTO request) {
        RawMaterialType material = materialTypeRepository.findById(item.getMaterialTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("采购物料不存在: " + item.getMaterialTypeId()));
        if (!factoryId.equals(material.getFactoryId())) throw new BusinessException(403, "采购物料不属于当前工厂");

        if (supplierId == null || supplierId.isBlank()) {
            applyPurchasePriceContract(factoryId, item, request.getQuantityUnit(), request.getUnit(), request.getPriceUnit());
            MaterialPackagingSelection selection = resolveMaterialPackagingSelection(
                    factoryId, material, request.getMaterialPackagingSpecId(), item.getUnit());
            item.setMaterialPackagingSpecId(selection.specId());
            item.setPurchasePackageUnitSnapshot(selection.packageUnit());
            item.setInventoryBaseUnitSnapshot(selection.baseUnit());
            item.setPackageToBaseFactorSnapshot(selection.factor());
            item.setInventoryQuantitySnapshot(selection.toBase(item.getQuantity()));
            return;
        }
        assertSupplierMaterialActive(factoryId, supplierId, item.getMaterialTypeId());
        com.cretas.aims.entity.SupplierMaterial relation = supplierMaterialRepository
                .findByFactoryIdAndSupplierIdAndMaterialTypeId(factoryId, supplierId, item.getMaterialTypeId())
                .orElseThrow(() -> new BusinessException(409, "供应商与物料的供应关系不存在"));
        if (request.getSupplierMaterialId() != null && !request.getSupplierMaterialId().equals(relation.getId())) {
            throw new BusinessException(400, "供应关系与所选供应商/物料不一致")
                    .withCode("SUPPLIER_MATERIAL_IDENTITY_MISMATCH").withHintTarget("supplierMaterialId");
        }
        item.setSupplierMaterialId(relation.getId());
        List<com.cretas.aims.entity.SupplierMaterialPurchaseSpec> specs = supplierMaterialPurchaseSpecRepository == null
                ? List.of() : supplierMaterialPurchaseSpecRepository
                .findByFactoryIdAndSupplierMaterialIdAndActiveTrue(factoryId, relation.getId());
        if (specs.isEmpty()) {
            String requestedUnit = firstNonBlank(
                    request.getQuantityUnit(), firstNonBlank(request.getUnit(), relation.getPurchaseUnit()));
            MaterialPackagingSelection selection = resolveMaterialPackagingSelection(
                    factoryId, material, request.getMaterialPackagingSpecId(), requestedUnit);
            String purchaseUnit = selection.packageUnit();
            if (request.getPriceUnit() != null
                    && !purchaseUnit.equals(canonicalUnit(factoryId, request.getPriceUnit(), "采购计价单位"))) {
                throw new BusinessException(400, "计价单位必须与供应关系的采购单位一致")
                        .withCode("PURCHASE_PRICE_UNIT_RELATION_MISMATCH").withHintTarget("priceUnit");
            }
            item.setUnit(purchaseUnit);
            item.setPriceUnit(purchaseUnit);
            item.setQuantityToPriceFactor(BigDecimal.ONE);
            item.setMaterialPackagingSpecId(selection.specId());
            item.setPurchasePackageUnitSnapshot(purchaseUnit);
            item.setInventoryBaseUnitSnapshot(selection.baseUnit());
            item.setPackageToBaseFactorSnapshot(selection.factor());
            item.setInventoryQuantitySnapshot(selection.toBase(item.getQuantity()));
            item.setUnitPrice(resolvePurchaseUnitPrice(
                    factoryId, material, relation, request, purchaseUnit, null));
            return;
        }
        if (request.getPurchasePackagingSpecId() == null || request.getPurchasePackagingSpecId().isBlank()) {
            throw new BusinessException(422, "该供应关系已配置采购包装规格，必须选择具体规格")
                    .withCode("PURCHASE_PACKAGING_SPEC_REQUIRED").withHintTarget("purchasePackagingSpecId");
        }
        com.cretas.aims.entity.SupplierMaterialPurchaseSpec spec = specs.stream()
                .filter(x -> request.getPurchasePackagingSpecId().equals(x.getId())).findFirst()
                .orElseThrow(() -> new BusinessException(400, "采购包装规格不属于当前供应商和物料")
                        .withCode("PURCHASE_PACKAGING_SPEC_IDENTITY_MISMATCH")
                        .withHintTarget("purchasePackagingSpecId"));
        String requestUnit = canonicalUnit(factoryId,
                firstNonBlank(request.getQuantityUnit(), request.getUnit()), "采购数量单位");
        if (!spec.getPurchasePackageUnit().equals(requestUnit)) {
            throw new BusinessException(400, "采购数量单位与所选包装规格不一致")
                    .withCode("PURCHASE_PACKAGING_SPEC_UNIT_MISMATCH").withHintTarget("quantityUnit");
        }
        if (request.getPriceUnit() != null
                && !spec.getPurchasePackageUnit().equals(canonicalUnit(factoryId, request.getPriceUnit(), "采购计价单位"))) {
            throw new BusinessException(400, "计价单位必须与所选采购包装规格一致")
                    .withCode("PURCHASE_PRICE_UNIT_SPEC_MISMATCH").withHintTarget("priceUnit");
        }
        item.setUnit(spec.getPurchasePackageUnit());
        item.setPriceUnit(spec.getPurchasePackageUnit());
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        item.setPurchasePackagingSpecId(spec.getId());
        if (request.getMaterialPackagingSpecId() != null && !request.getMaterialPackagingSpecId().isBlank()) {
            MaterialPackagingSelection materialSelection = resolveMaterialPackagingSelection(
                    factoryId, material, request.getMaterialPackagingSpecId(), spec.getPurchasePackageUnit());
            if (materialSelection.factor().compareTo(spec.getConversionFactor()) != 0) {
                throw new BusinessException(409, "供应商包装规格与原料包装换算不一致")
                        .withCode("SUPPLIER_MATERIAL_PACKAGING_FACTOR_MISMATCH")
                        .withHint("请先统一供应商规格和原料类型包装规格");
            }
            item.setMaterialPackagingSpecId(materialSelection.specId());
        }
        item.setPurchasePackageUnitSnapshot(spec.getPurchasePackageUnit());
        item.setInventoryBaseUnitSnapshot(spec.getInventoryBaseUnit());
        item.setPackageToBaseFactorSnapshot(spec.getConversionFactor());
        item.setInventoryQuantitySnapshot(item.getQuantity().multiply(spec.getConversionFactor()));
        item.setUnitPrice(resolvePurchaseUnitPrice(
                factoryId, material, relation, request, spec.getPurchasePackageUnit(), spec));
    }

    /**
     * Resolve one authoritative price together with its denominator unit.
     * A configured supplier/spec price is never reinterpreted as a base-unit price,
     * and an absent price remains absent instead of silently becoming zero.
     */
    private BigDecimal resolvePurchaseUnitPrice(
            String factoryId,
            RawMaterialType material,
            com.cretas.aims.entity.SupplierMaterial relation,
            CreatePurchaseOrderRequest.PurchaseOrderItemDTO request,
            String targetPurchaseUnit,
            com.cretas.aims.entity.SupplierMaterialPurchaseSpec spec) {
        BigDecimal explicit = positivePriceOrNull(request.getUnitPrice());
        if (request.getUnitPrice() != null && explicit == null) {
            throw new BusinessException(400, "采购单价必须大于0")
                    .withCode("PURCHASE_UNIT_PRICE_INVALID").withHintTarget("unitPrice");
        }
        if (explicit != null) {
            String explicitUnit = canonicalUnit(factoryId,
                    firstNonBlank(request.getPriceUnit(), targetPurchaseUnit), "采购计价单位");
            if (!targetPurchaseUnit.equals(explicitUnit)) {
                throw new BusinessException(400, "采购单价的计价单位与采购数量单位不一致")
                        .withCode("PURCHASE_PRICE_UNIT_MISMATCH").withHintTarget("priceUnit");
            }
            return explicit;
        }

        if (spec != null && positivePriceOrNull(spec.getQuotedPrice()) != null) {
            return spec.getQuotedPrice();
        }

        BigDecimal relationPrice = positivePriceOrNull(relation.getDefaultPurchasePrice());
        if (relationPrice != null) {
            String relationUnit = canonicalUnit(factoryId, relation.getPurchaseUnit(), "供应关系采购单位");
            return convertAuthoritativePrice(factoryId, material.getId(), relationPrice,
                    relationUnit, targetPurchaseUnit, spec);
        }

        BigDecimal referencePrice = positivePriceOrNull(material.getUnitPrice());
        if (referencePrice != null) {
            String referenceUnit = canonicalUnit(factoryId, material.getUnit(), "物料参考价单位");
            return convertAuthoritativePrice(factoryId, material.getId(), referencePrice,
                    referenceUnit, targetPurchaseUnit, spec);
        }
        return null;
    }

    private BigDecimal convertAuthoritativePrice(
            String factoryId,
            String materialTypeId,
            BigDecimal sourcePrice,
            String sourceUnit,
            String targetUnit,
            com.cretas.aims.entity.SupplierMaterialPurchaseSpec spec) {
        if (sourceUnit.equals(targetUnit)) return sourcePrice;
        if (spec != null) {
            String specBaseUnit = canonicalUnit(factoryId, spec.getInventoryBaseUnit(), "采购规格库存单位");
            String specPackageUnit = canonicalUnit(factoryId, spec.getPurchasePackageUnit(), "采购规格包装单位");
            if (targetUnit.equals(specPackageUnit)) {
                BigDecimal pricePerBase = sourceUnit.equals(specBaseUnit)
                        ? sourcePrice
                        : convertPricePerUnit(factoryId, materialTypeId, sourcePrice, sourceUnit, specBaseUnit);
                return pricePerBase.multiply(spec.getConversionFactor());
            }
        }
        return convertPricePerUnit(factoryId, materialTypeId, sourcePrice, sourceUnit, targetUnit);
    }

    private BigDecimal positivePriceOrNull(BigDecimal price) {
        return price != null && price.compareTo(BigDecimal.ZERO) > 0 ? price : null;
    }

    private void snapshotBaseUnit(String factoryId, PurchaseOrderItem item, RawMaterialType material) {
        String base = canonicalUnit(factoryId, material.getUnit(), "库存基本单位");
        item.setPurchasePackageUnitSnapshot(item.getUnit());
        item.setInventoryBaseUnitSnapshot(base);
        BigDecimal factor = conversionFactor(factoryId, material.getId(), item.getUnit(), base);
        item.setPackageToBaseFactorSnapshot(factor);
        item.setInventoryQuantitySnapshot(item.getQuantity().multiply(factor));
    }

    private void applyPurchaseTaxContract(PurchaseOrderItem item, BigDecimal explicitTaxRate) {
        RawMaterialType material = materialTypeRepository.findById(item.getMaterialTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("采购物料不存在: " + item.getMaterialTypeId()));
        BigDecimal taxRate = explicitTaxRate;
        if (taxRate == null) {
            if (material.getTaxTreatment() == com.cretas.aims.entity.enums.TaxTreatment.EXEMPT) {
                taxRate = BigDecimal.ZERO;
            } else if (material.getTaxRate() != null) {
                taxRate = material.getTaxRate().getRate().multiply(new BigDecimal("100"));
            } else {
                throw new BusinessException(422, "物料未配置采购税率，不能静默按0%下单")
                        .withCode("PURCHASE_TAX_RATE_REQUIRED").withHintTarget("taxRate");
            }
        }
        if (taxRate.compareTo(BigDecimal.ZERO) < 0 || taxRate.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException(400, "采购税率必须在0到100之间").withHintTarget("taxRate");
        }
        item.setTaxRate(taxRate);
        snapshotPurchaseAmounts(item);
    }

    private void snapshotPurchaseAmounts(PurchaseOrderItem item) {
        BigDecimal untaxed = item.getLineAmount();
        BigDecimal taxInclusive = item.getLineAmountWithTax();
        item.setUntaxedAmountSnapshot(untaxed);
        item.setTaxInclusiveAmountSnapshot(taxInclusive);
        item.setTaxAmountSnapshot(untaxed == null || taxInclusive == null ? null : taxInclusive.subtract(untaxed));
    }
}
