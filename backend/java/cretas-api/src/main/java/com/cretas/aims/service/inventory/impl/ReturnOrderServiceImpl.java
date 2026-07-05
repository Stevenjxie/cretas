package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.LinkArrayService;
import com.cretas.aims.annotation.DataScope;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.ReturnOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ReturnOrderServiceImpl implements ReturnOrderService {

    private static final Logger log = LoggerFactory.getLogger(ReturnOrderServiceImpl.class);

    /** #3(a): 挂在 AR/AP 调整上的来源单据类型, 使退货单驳回时可级联撤销挂起调整. 与 ReturnVoucherGenerator.BUSINESS_TYPE 一致. */
    private static final String ADJUSTMENT_SOURCE_TYPE = "RETURN_ORDER";

    private final ReturnOrderRepository returnOrderRepository;
    private final ReturnOrderItemRepository returnOrderItemRepository;
    private final ArApService arApService;
    private final ApplicationEventPublisher applicationEventPublisher;

    // T-RTA Phase C (issue #571): create DEFECTIVE FinishedGoodsBatch in WH-LOG on
    // sales return completion (with-goods path). @Autowired(required=false) so prior
    // wiring still works if these beans aren't available in some test contexts.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WarehouseResolver warehouseResolver;

    /** Sprint 6 W2-B: 数据权限解析器 (optional). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DataScopeResolver dataScopeResolver;

    /**
     * Issue #795 — PURCHASE_RETURN with-goods 不良品入库.
     * 镜像 SALES_RETURN with-goods 在 FinishedGoodsBatchRepository 上的写入语义,
     * 但作用于 MaterialBatch (原料退给供应商前先入库到 WH-LOG, status=DEFECTIVE).
     * required=false 兼容单元测试 mock 场景.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MaterialBatchRepository materialBatchRepository;

    /**
     * BLOCKER 2 fix: PURCHASE_RETURN with-goods 完成时从源库存扣减 (货物理离开退回供应商),
     * 写 MaterialBatchAdjustment 负数留痕 (镜像 DisposalRecordService 扣减语义)。
     * required=false 兼容单元测试 mock 场景。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;

    /** Round 11 T2 — Canvas Integration Template hook 1: DB-driven validation. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    /** Round 11 T2 — Canvas Integration Template hook 2: dynamic field persist. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DefaultValueResolver defaultValueResolver;

    /** Sprint 3 Track-F: unified cross-business link service (double-write w/ sourceOrderId). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LinkArrayService linkArrayService;

    // Bug 4 fix (2026-07): 详情页名称解析 (fool-proof-design Rule 2 — 上下文必带身份信息).
    // ReturnOrder 只存 counterpartyId/sourceOrderId/approvedBy(userId), 详情页此前直接显示
    // 原始 UUID/user id。required=false 兼容既有单元测试 mock 场景 (无这些 repo 时优雅降级为
    // 不 enrich, 而非 NPE — fail-soft, 因为名称展示不是退货单状态机的关键路径).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.CustomerRepository customerRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.SupplierRepository supplierRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.inventory.SalesOrderRepository salesOrderRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.UserRepository userRepository;

    public ReturnOrderServiceImpl(ReturnOrderRepository returnOrderRepository,
                                   ReturnOrderItemRepository returnOrderItemRepository,
                                   ArApService arApService,
                                   ApplicationEventPublisher applicationEventPublisher) {
        this.returnOrderRepository = returnOrderRepository;
        this.returnOrderItemRepository = returnOrderItemRepository;
        this.arApService = arApService;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    @Transactional
    public ReturnOrder createReturnOrder(String factoryId, CreateReturnOrderRequest request, Long userId) {
        ReturnType returnType = ReturnType.valueOf(request.getReturnType());

        // Round 11 T2: Canvas Integration Template hook 1 — DB-driven validation
        if (validationRuleEvaluator != null) {
            try {
                validationRuleEvaluator.validate(factoryId, "sales_return", "CREATE",
                        java.util.Map.of(
                            "returnType", returnType.name(),
                            "itemCount", request.getItems() != null ? request.getItems().size() : 0,
                            "counterpartyId", request.getCounterpartyId() != null ? request.getCounterpartyId() : ""));
            } catch (com.cretas.aims.exception.BusinessException e) { throw e; }
            catch (Exception e) { log.warn("Canvas validation non-blocking: {}", e.getMessage()); }
        }

        String returnNumber = generateReturnNumber(factoryId, returnType);

        ReturnOrder order = new ReturnOrder();
        order.setFactoryId(factoryId);
        order.setReturnNumber(returnNumber);
        order.setReturnType(returnType);
        order.setStatus(ReturnOrderStatus.DRAFT);
        order.setCounterpartyId(request.getCounterpartyId());
        order.setSourceOrderId(request.getSourceOrderId());
        order.setReturnDate(request.getReturnDate());
        order.setReason(request.getReason());
        order.setRemark(request.getRemark());
        // T-RTA business logic (issue #571): default TRUE (实物退货, customer's primary case).
        // Caller may explicitly set false for refund-only path.
        order.setWithGoods(request.getWithGoods() != null ? request.getWithGoods() : Boolean.TRUE);
        order.setCreatedBy(userId);

        order = returnOrderRepository.save(order);

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<ReturnOrderItem> items = new ArrayList<>();

        for (CreateReturnOrderRequest.ReturnOrderItemDTO itemDTO : request.getItems()) {
            ReturnOrderItem item = new ReturnOrderItem();
            item.setReturnOrderId(order.getId());
            item.setMaterialTypeId(itemDTO.getMaterialTypeId());
            item.setProductTypeId(itemDTO.getProductTypeId());
            item.setItemName(itemDTO.getItemName());
            item.setQuantity(itemDTO.getQuantity());
            item.setUnitPrice(itemDTO.getUnitPrice());
            item.setBatchNumber(itemDTO.getBatchNumber());
            item.setReason(itemDTO.getReason());

            BigDecimal lineAmount = BigDecimal.ZERO;
            if (itemDTO.getUnitPrice() != null && itemDTO.getQuantity() != null) {
                lineAmount = itemDTO.getQuantity().multiply(itemDTO.getUnitPrice())
                        .setScale(2, BigDecimal.ROUND_HALF_UP);
            }
            item.setLineAmount(lineAmount);
            items.add(item);
            totalAmount = totalAmount.add(lineAmount);
        }

        returnOrderItemRepository.saveAll(items);
        order.setTotalAmount(totalAmount);
        order = returnOrderRepository.save(order);

        // Sprint 3 Track-F (C-LINKARRAY-1): unified BusinessLink double-write.
        // ReturnOrder.sourceOrderId stays for backward compat; new code reads via
        // LinkArrayService.getOutboundLinks(RETURN_ORDER, id).
        if (linkArrayService != null && order.getSourceOrderId() != null && !order.getSourceOrderId().isBlank()) {
            String targetType = returnType == ReturnType.SALES_RETURN ? "SALES_ORDER" : "PURCHASE_ORDER";
            String linkType = returnType == ReturnType.SALES_RETURN ? "sale" : "free";
            try {
                linkArrayService.link(factoryId,
                        "RETURN_ORDER", order.getId(),
                        linkType,
                        targetType, order.getSourceOrderId(),
                        "退货源单", userId != null ? userId.toString() : null);
            } catch (Exception e) {
                log.warn("BusinessLink double-write failed for return order {}: {}", order.getId(), e.getMessage());
            }
        }

        // Round 11 T2: Canvas Integration Template hook 2 — persist dynamic fields.
        // Customer-configured fields (退货照片, 客诉单号, 退货责任方) land in cf_*
        // columns on return_orders. Silent failure must not break the return creation.
        if (dynamicFieldService != null && request.getCustomFields() != null && !request.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "sales_return", order.getId(), request.getCustomFields());
            } catch (Exception e) {
                log.warn("Canvas dynamic fields save failed for return order {}: {}", order.getId(), e.getMessage());
            }
        }

        // Round 11 T2: Canvas Integration Template hook 3 — publish event for trigger chains.
        // Fires on DRAFT creation of both SALES_RETURN and PURCHASE_RETURN. Factories
        // can now react via configured trigger chains (e.g., "大额退货通知财务").
        try {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.ReturnOrderCreatedEvent(
                    this, factoryId, order.getId(), order.getReturnNumber(),
                    returnType.name(), order.getCounterpartyId(),
                    order.getSourceOrderId(), order.getTotalAmount()));
        } catch (Exception e) {
            log.warn("Publish ReturnOrderCreatedEvent failed for {}: {}", order.getId(), e.getMessage());
        }

        log.info("创建退货单: factoryId={}, returnNumber={}, type={}, items={}",
                factoryId, returnNumber, returnType, items.size());
        return order;
    }

    @Override
    public ReturnOrder getReturnOrderById(String factoryId, String returnOrderId) {
        ReturnOrder order = returnOrderRepository.findById(returnOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("退货单不存在"));
        if (!order.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权访问该退货单")
                    .withHint("当前退货单不属于该工厂, 无法访问");
        }
        return order;
    }

    /**
     * Bug 4 fix (2026-07): 详情页专用 — getReturnOrderById 的结果基础上 enrich 只读展示字段
     * (对方名称/源销售单号/审批人姓名), 供 ReturnOrderController#getReturnOrder 使用。
     * 不影响 getReturnOrderById 本身 (状态机 transition 方法仍走原始无 enrich 版本, 避免给
     * 写路径徒增查询)。任一 enrich 查询失败 fail-soft (log only) —— 名称展示不阻断详情页加载。
     */
    @Override
    public ReturnOrder getReturnOrderDetail(String factoryId, String returnOrderId) {
        ReturnOrder order = getReturnOrderById(factoryId, returnOrderId);
        try {
            if (order.getCounterpartyId() != null) {
                if (order.getReturnType() == ReturnType.SALES_RETURN && customerRepository != null) {
                    customerRepository.findById(order.getCounterpartyId())
                            .ifPresent(c -> order.setCounterpartyName(c.getName()));
                } else if (order.getReturnType() == ReturnType.PURCHASE_RETURN && supplierRepository != null) {
                    supplierRepository.findById(order.getCounterpartyId())
                            .ifPresent(s -> order.setCounterpartyName(s.getName()));
                }
            }
        } catch (Exception e) {
            log.warn("退货单详情 enrich 对方名称失败 (不阻断详情加载): returnOrderId={}", returnOrderId, e);
        }
        try {
            if (order.getSourceOrderId() != null && salesOrderRepository != null) {
                salesOrderRepository.findById(order.getSourceOrderId())
                        .ifPresent(so -> order.setSourceOrderNumber(so.getOrderNumber()));
            }
        } catch (Exception e) {
            log.warn("退货单详情 enrich 源订单号失败 (不阻断详情加载): returnOrderId={}", returnOrderId, e);
        }
        try {
            if (order.getApprovedBy() != null && userRepository != null) {
                userRepository.findById(order.getApprovedBy())
                        .ifPresent(u -> order.setApprovedByName(
                                u.getFullName() != null && !u.getFullName().isBlank()
                                        ? u.getFullName() : u.getUsername()));
            }
        } catch (Exception e) {
            log.warn("退货单详情 enrich 审批人姓名失败 (不阻断详情加载): returnOrderId={}", returnOrderId, e);
        }
        return order;
    }

    @Override
    @DataScope("created_by")  // Sprint 6 W2-B — RBAC 第 2 维 (数据权限) sweep
    public PageResponse<ReturnOrder> getReturnOrders(String factoryId, ReturnType returnType,
                                                      ReturnOrderStatus status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        // Sprint 6 W2-B: 应用 DataScope. 走 unified findByFactoryIdAndFiltersAndCreatedByIn
        // 当 scope 是 SELF / SELF_AND_BELOW / DEPT_AND_BELOW (DB-side IN list).
        // ALL scope 保持原 4-branch 行为.
        DataScopeContext dsCtx = DataScopeContext.current();
        if (dsCtx != null && dsCtx.isFiltered() && dsCtx.getUserId() != null) {
            com.cretas.aims.entity.enums.DataScope scope = dsCtx.getScope();
            List<Long> chain;
            if (scope == com.cretas.aims.entity.enums.DataScope.SELF) {
                chain = List.of(dsCtx.getUserId());
            } else if (scope == com.cretas.aims.entity.enums.DataScope.SELF_AND_BELOW
                    || scope == com.cretas.aims.entity.enums.DataScope.DEPT_AND_BELOW) {
                chain = dataScopeResolver != null
                        ? dataScopeResolver.resolveCreatedByChain(dsCtx)
                        : List.of(dsCtx.getUserId());
                if (chain == null || chain.isEmpty()) chain = List.of(dsCtx.getUserId());
            } else {
                chain = null; // CUSTOM — fallback ALL
            }
            if (chain != null) {
                log.debug("DataScope {} for return orders: chain size={}", scope, chain.size());
                Page<ReturnOrder> result = returnOrderRepository.findByFactoryIdAndFiltersAndCreatedByIn(
                        factoryId, returnType, status, chain, pageRequest);
                return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
            }
        }

        Page<ReturnOrder> result;
        if (returnType != null && status != null) {
            result = returnOrderRepository.findByFactoryIdAndReturnTypeAndStatusOrderByCreatedAtDesc(
                    factoryId, returnType, status, pageRequest);
        } else if (returnType != null) {
            result = returnOrderRepository.findByFactoryIdAndReturnTypeOrderByCreatedAtDesc(
                    factoryId, returnType, pageRequest);
        } else if (status != null) {
            result = returnOrderRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(
                    factoryId, status, pageRequest);
        } else {
            result = returnOrderRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageRequest);
        }

        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    @Transactional
    public ReturnOrder submitReturnOrder(String factoryId, String returnOrderId) {
        ReturnOrder order = getReturnOrderById(factoryId, returnOrderId);
        if (order.getStatus() != ReturnOrderStatus.DRAFT) {
            throw new BusinessException(409, "只有草稿状态的退货单可以提交")
                    .withHint("请刷新退货单列表查看最新状态");
        }
        order.setStatus(ReturnOrderStatus.SUBMITTED);
        log.info("提交退货单: returnOrderId={}, returnNumber={}", returnOrderId, order.getReturnNumber());
        return returnOrderRepository.save(order);
    }

    @Override
    @Transactional
    public ReturnOrder approveReturnOrder(String factoryId, String returnOrderId, Long approverId) {
        ReturnOrder order = getReturnOrderById(factoryId, returnOrderId);
        if (order.getStatus() != ReturnOrderStatus.SUBMITTED) {
            throw new BusinessException(409, "只有已提交状态的退货单可以审批")
                    .withHint("请刷新退货单列表查看最新状态");
        }
        order.setStatus(ReturnOrderStatus.APPROVED);
        order.setApprovedBy(approverId);
        order.setApprovedAt(LocalDateTime.now());

        // T-RTA business logic (issue #571, audit BLOCKER B1): branch by withGoods.
        //
        // withGoods=true  (实物退货): defer AR/AP adjustment to completeReturnOrder. Warehouse
        //                            keeper must physically receive goods first; financial
        //                            credit happens when 不良品 inbound is confirmed.
        // withGoods=false (退款 only): trigger AR/AP adjustment now (existing behavior). No
        //                            inventory action needed since no physical goods involved.
        //
        // Customer transcript (第四次:956-1037): "有食物的话, 库存入库到总仓 ... 无食物的话就退款"
        // — the order of operations matters because real-world workflow can't credit before
        // verifying physical receipt (would create accounting/audit gap).
        boolean withGoods = Boolean.TRUE.equals(order.getWithGoods());
        if (!withGoods) {
            // No-goods path: AR/AP adjustment immediately on approve (current behavior).
            try {
                if (order.getReturnType() == ReturnType.PURCHASE_RETURN) {
                    // #3(a): link adjustment → 退货单 (sourceType/sourceId) so financeReject can cascade-cancel it.
                    arApService.recordAdjustment(factoryId,
                            com.cretas.aims.entity.enums.CounterpartyType.SUPPLIER,
                            order.getCounterpartyId(),
                            order.getTotalAmount().negate(),
                            approverId,
                            "采购退货冲减(无货)-" + order.getReturnNumber(),
                            ADJUSTMENT_SOURCE_TYPE, order.getId());
                    log.info("采购退货冲减应付(无货, 审批立即触发): returnNumber={}, amount={}", order.getReturnNumber(), order.getTotalAmount());
                } else if (order.getReturnType() == ReturnType.SALES_RETURN) {
                    arApService.recordAdjustment(factoryId,
                            com.cretas.aims.entity.enums.CounterpartyType.CUSTOMER,
                            order.getCounterpartyId(),
                            order.getTotalAmount().negate(),
                            approverId,
                            "销售退货冲减(无货)-" + order.getReturnNumber(),
                            ADJUSTMENT_SOURCE_TYPE, order.getId());
                    log.info("销售退货冲减应收(无货, 审批立即触发): returnNumber={}, amount={}", order.getReturnNumber(), order.getTotalAmount());
                }
            } catch (Exception e) {
                log.error("退货AR/AP冲减失败 (无货path): returnOrderId={}", returnOrderId, e);
            }
        } else {
            // With-goods path: AR/AP冲减 deferred until 仓库实物入库 in completeReturnOrder.
            log.info("审批退货单(有货): returnOrderId={} — AR冲减 + 库存入库 deferred to completion", returnOrderId);
        }

        log.info("审批退货单: returnOrderId={}, approvedBy={}, withGoods={}", returnOrderId, approverId, withGoods);
        return returnOrderRepository.save(order);
    }

    /**
     * 六扇门 Tier0 #16 (catalog 行2399-2416): 退货财务审批门。
     * 客户原话: "退货单发财务审批，审批后给仓管把实物拿走" / "退货跟钱有关要先财务审批确认；
     * 跟钱有关的东西都要审批"。
     *
     * <p>状态机: APPROVED → FINANCE_APPROVED。只有 finance:read_write (财务角色) 可调用
     * (Controller 层 @RequirePermission 把关)。财务审批后才能 completeReturnOrder 交仓管出货。
     *
     * <p>幂等/防呆: 非 APPROVED 状态调用 → 409 + 明确 hint。
     */
    @Override
    @Transactional
    public ReturnOrder financeApproveReturnOrder(String factoryId, String returnOrderId, Long financeUserId) {
        ReturnOrder order = getReturnOrderById(factoryId, returnOrderId);
        if (order.getStatus() != ReturnOrderStatus.APPROVED) {
            throw new BusinessException(409, "只有已通过业务审批(已审批)的退货单可以提交财务审批")
                    .withHint("当前状态: " + order.getStatus().getDisplayName()
                            + "。退货跟资金相关，需先完成业务审批再由财务审批，请刷新列表查看最新状态");
        }
        order.setStatus(ReturnOrderStatus.FINANCE_APPROVED);
        order.setFinanceApprovedBy(financeUserId);
        order.setFinanceApprovedAt(LocalDateTime.now());
        log.info("财务审批退货单: returnOrderId={}, returnNumber={}, financeApprovedBy={}",
                returnOrderId, order.getReturnNumber(), financeUserId);
        return returnOrderRepository.save(order);
    }

    @Override
    @Transactional
    public ReturnOrder financeRejectReturnOrder(String factoryId, String returnOrderId, Long financeUserId) {
        ReturnOrder order = getReturnOrderById(factoryId, returnOrderId);
        if (order.getStatus() != ReturnOrderStatus.APPROVED) {
            throw new BusinessException(409, "只有已通过业务审批的退货单可以由财务驳回")
                    .withHint("当前状态: " + order.getStatus().getDisplayName()
                            + "。请刷新退货单列表查看最新状态，避免重复审批或越级处理。");
        }
        order.setStatus(ReturnOrderStatus.REJECTED);
        order.setFinanceApprovedBy(financeUserId);
        order.setFinanceApprovedAt(LocalDateTime.now());

        // #3(a): 无货退款单在业务审批时已挂起一条 PENDING AR/AP 冲减 (recordAdjustment)。财务驳回退货单
        // 后, 这条挂起调整必须一并作废, 否则它仍留在审批队列可被第 2 位审批人通过 → 为已驳回退货单变动
        // 客户/供应商余额 (资金泄漏)。系统级联撤销 (非手工 4-眼), 幂等 (有货 path 无挂起调整 → 0 no-op)。
        try {
            int cancelled = arApService.cancelPendingAdjustmentsBySource(factoryId,
                    ADJUSTMENT_SOURCE_TYPE, order.getId(), financeUserId,
                    "退货单财务驳回, 撤销待审批冲减-" + order.getReturnNumber());
            if (cancelled > 0) {
                log.info("财务驳回退货单级联撤销挂起冲减: returnOrderId={}, cancelled={}", returnOrderId, cancelled);
            }
        } catch (Exception e) {
            // 撤销失败必须 fail-loud — 否则一条能被通过的挂起冲减留在队列 = 资金泄漏隐患。
            log.error("财务驳回退货单撤销挂起冲减失败: returnOrderId={}", returnOrderId, e);
            throw new BusinessException(500, "退货单驳回失败: 关联的待审批冲减撤销失败, 请重试")
                    .withHint("为避免已驳回退货单的余额被误变动, 系统未提交驳回。请稍后重试或联系管理员。");
        }

        log.info("财务驳回退货单: returnOrderId={}, returnNumber={}, financeUserId={}",
                returnOrderId, order.getReturnNumber(), financeUserId);
        ReturnOrder saved = returnOrderRepository.save(order);
        publishReturnOrderRejected(factoryId, saved.getId());
        return saved;
    }

    @Override
    @Transactional
    public ReturnOrder rejectReturnOrder(String factoryId, String returnOrderId) {
        ReturnOrder order = getReturnOrderById(factoryId, returnOrderId);
        if (order.getStatus() != ReturnOrderStatus.SUBMITTED) {
            throw new BusinessException(409, "只有已提交状态的退货单可以驳回")
                    .withHint("请刷新退货单列表查看最新状态");
        }
        order.setStatus(ReturnOrderStatus.REJECTED);
        log.info("驳回退货单: returnOrderId={}, returnNumber={}", returnOrderId, order.getReturnNumber());
        ReturnOrder saved = returnOrderRepository.save(order);
        publishReturnOrderRejected(factoryId, saved.getId());
        return saved;
    }

    /**
     * Bug 9 修复 (2026-07-04): 发布退货单驳回事件 → AFTER_COMMIT listener 作废对应 RETURN 凭证。
     * fail-soft: 发布失败不影响驳回主流程。
     */
    private void publishReturnOrderRejected(String factoryId, String returnOrderId) {
        try {
            applicationEventPublisher.publishEvent(
                    new com.cretas.aims.event.ReturnOrderRejectedEvent(this, factoryId, returnOrderId));
        } catch (Exception e) {
            log.error("发布ReturnOrderRejectedEvent失败(不影响驳回): RO={}", returnOrderId, e);
        }
    }

    @Override
    @Transactional
    public ReturnOrder completeReturnOrder(String factoryId, String returnOrderId) {
        ReturnOrder order = getReturnOrderById(factoryId, returnOrderId);
        // 六扇门 Tier0 #16: 财务审批门 — 退货跟钱有关，完成/出货前必须先经财务审批。
        // 完成的前置状态从 APPROVED 收紧为 FINANCE_APPROVED。防呆: 未财务审批直接完成 → 拒绝 + hint。
        if (order.getStatus() != ReturnOrderStatus.FINANCE_APPROVED) {
            String hint = order.getStatus() == ReturnOrderStatus.APPROVED
                    ? "该退货单已通过业务审批，但尚未经财务审批。退货涉及资金，需财务审批通过后才能交仓管完成/出货"
                    : "请刷新退货单列表查看最新状态";
            throw new BusinessException(409, "只有财务审批通过(财务已审)的退货单可以完成")
                    .withHint(hint);
        }

        // T-RTA business logic (issue #571 Phase B): for withGoods=true, trigger
        // deferred AR/AP冲减 now (after physical receipt confirmed). For withGoods=false,
        // AR/AP冲减 already happened at approve time — just flip status.
        boolean withGoods = Boolean.TRUE.equals(order.getWithGoods());
        if (withGoods) {
            // Deferred AR/AP冲减 from approve.
            // #3(b): 不再吞异常。此前 catch(Exception) log.error 会让"冲减失败"却仍 COMPLETE + 移库存,
            // 客户/供应商余额永远不冲减且无人知晓 (silent finance hole)。改为 fail-loud: 冲减失败整体
            // 回滚 (退货单不 COMPLETED, 库存不动), 让操作员看到明确错误并重试。
            boolean adjustmentRecorded = false;
            try {
                if (order.getReturnType() == ReturnType.PURCHASE_RETURN) {
                    arApService.recordAdjustment(factoryId,
                            com.cretas.aims.entity.enums.CounterpartyType.SUPPLIER,
                            order.getCounterpartyId(),
                            order.getTotalAmount().negate(),
                            order.getApprovedBy(),
                            "采购退货冲减(实物已入库)-" + order.getReturnNumber(),
                            ADJUSTMENT_SOURCE_TYPE, order.getId());
                    adjustmentRecorded = true;
                    log.info("采购退货冲减应付(实物已入库, completion 触发): returnNumber={}, amount={}", order.getReturnNumber(), order.getTotalAmount());
                } else if (order.getReturnType() == ReturnType.SALES_RETURN) {
                    arApService.recordAdjustment(factoryId,
                            com.cretas.aims.entity.enums.CounterpartyType.CUSTOMER,
                            order.getCounterpartyId(),
                            order.getTotalAmount().negate(),
                            order.getApprovedBy(),
                            "销售退货冲减(实物已入库)-" + order.getReturnNumber(),
                            ADJUSTMENT_SOURCE_TYPE, order.getId());
                    adjustmentRecorded = true;
                    log.info("销售退货冲减应收(实物已入库, completion 触发): returnNumber={}, amount={}", order.getReturnNumber(), order.getTotalAmount());
                }
            } catch (BusinessException e) {
                throw e; // 明确业务错误原样上抛 (客户/供应商不存在等), 保留 message + hint。
            } catch (Exception e) {
                log.error("退货AR/AP冲减失败 (有货 completion path, fail-loud 回滚): returnOrderId={}", returnOrderId, e);
                throw new BusinessException(500, "退货完成失败: 应收/应付冲减未成功, 退货单未完成")
                        .withHint("退货涉及资金, 余额冲减失败时不能完成出货。请稍后重试或联系财务/管理员核实。");
            }
            // 防呆 Rule 2/5: recordAdjustment 建的是 PENDING 冲减 (余额未变, 待财务审批调整)。
            // 完成响应必须提示操作员余额尚未变动, 避免误以为客户/供应商欠款已冲平。
            if (adjustmentRecorded) {
                order.setCompletionHint("退货已完成并移库。余额冲减 " + order.getTotalAmount().stripTrailingZeros().toPlainString()
                        + " 元已提交, 待财务审批后才会变动"
                        + (order.getReturnType() == ReturnType.PURCHASE_RETURN ? "供应商应付" : "客户应收") + "余额。");
            }
            // T-RTA Phase C (issue #571): SALES_RETURN with-goods → create DEFECTIVE
            // FinishedGoodsBatch in WH-LOG. status='DEFECTIVE' auto-excludes from existing
            // `WHERE status='AVAILABLE'` queries (调拨/销售/生产 pickers), so no schema or
            // repository refactor needed.
            //
            // Issue #795 (this commit): PURCHASE_RETURN with-goods → 镜像逻辑, 写入 MaterialBatch
            // (原料退回总仓 WH-LOG, status=DEFECTIVE). MaterialBatchStatus.DEFECTIVE 加在
            // MaterialBatchStatus enum 里; FEFO / sumAvailable / findAvailable queries 现有
            // 都过滤 status='AVAILABLE', 自动排除 DEFECTIVE, 无需 repository 重构.
            if (order.getReturnType() == ReturnType.SALES_RETURN
                    && finishedGoodsBatchRepository != null && warehouseResolver != null) {
                try {
                    String whLogId = warehouseResolver.resolveLogisticsId(factoryId);
                    int itemIdx = 0;
                    for (ReturnOrderItem item : order.getItems()) {
                        itemIdx++;
                        if (item.getProductTypeId() == null || item.getQuantity() == null
                                || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                            continue;
                        }
                        FinishedGoodsBatch batch = new FinishedGoodsBatch();
                        batch.setFactoryId(factoryId);
                        batch.setBatchNumber("RTN-" + order.getReturnNumber() + "-" + itemIdx);
                        batch.setProductTypeId(item.getProductTypeId());
                        batch.setProductName(item.getItemName());
                        batch.setProducedQuantity(item.getQuantity());
                        batch.setShippedQuantity(BigDecimal.ZERO);
                        batch.setReservedQuantity(BigDecimal.ZERO);
                        batch.setUnit("件");
                        batch.setUnitPrice(item.getUnitPrice());
                        batch.setProductionDate(LocalDate.now());
                        batch.setWarehouseId(whLogId);
                        batch.setStatus("DEFECTIVE");
                        batch.setCreatedBy(order.getApprovedBy());
                        batch.setRemark("销售退货入库(不良品) - " + order.getReturnNumber()
                                + " - 原因: " + (item.getReason() != null ? item.getReason() : order.getReason()));
                        finishedGoodsBatchRepository.save(batch);
                    }
                    log.info("销售退货 不良品入库完成: returnNumber={}, items={}, warehouse=WH-LOG",
                            order.getReturnNumber(),
                            order.getItems() != null ? order.getItems().size() : 0);
                } catch (Exception e) {
                    // Non-blocking — AR/AP 冲减 + status flip 必须生效. 库存入库失败仅影响 traceability,
                    // 仓管员可手动补录. Phase C 之后再发现失败模式, 加监控/告警 ticket.
                    log.error("销售退货 不良品入库失败 (status flip + AR冲减 仍生效): returnOrderId={}",
                            returnOrderId, e);
                }
            } else if (order.getReturnType() == ReturnType.PURCHASE_RETURN
                    && materialBatchRepository != null) {
                // BLOCKER 2 fix (取代 issue #795 反向逻辑): PURCHASE_RETURN with-goods 完成
                // = 货物理离开退回供应商 → 从现有库存【扣减】退货数量, 而非创建新批次。
                //
                // 此前 (#795) 镜像 SALES_RETURN 的"货退回来→加库存"逻辑, 给 PURCHASE_RETURN 创建
                // 一个新 DEFECTIVE MaterialBatch。但语义相反:
                //   - SALES_RETURN  (客户退货回来): 货物进厂 → 加库存 (现状, 上面分支保留)。
                //   - PURCHASE_RETURN (退给供应商): 货物离厂 → 扣库存 (本分支)。
                // 超收的量在采购收货时已加进库存 (createMaterialBatchFromReceiveItem 用全部收货量),
                // 退货完成时若再创建一个新批次 = 账面双计 (130 + 30 = 160)。正确应扣回 30 → 100。
                //
                // 扣减方式镜像 DisposalRecordService: 增 usedQuantity (不减 receiptQuantity),
                // 使 currentQuantity = receiptQuantity - usedQuantity - reservedQuantity 正确反映,
                // 并写 MaterialBatchAdjustment 负数留痕。源批次按 FEFO 跨同物料类型批次扣减
                // (ReturnOrderItem 不可靠引用单一批次; 退货物料 = exceptionQty 的同物料类型库存)。
                // #2 fix: 采购退货 = 原料退回供应商, 必须从【原料仓/采购入库仓】扣减, 而非跨所有仓 FEFO。
                // 跨仓 FEFO (findAvailableBatchesFEFO) 只排除了 PRODUCTION_BATCH (WIP), 未排除 #1171 领料搬库
                // 到车间仓 (WH-WKS) 的 sourceDocType='MATERIAL_REQUISITION' 批次 (status=AVAILABLE, 继承源
                // expireDate 常排 FEFO 最前) → 采购退货会优先吃掉生产在库的车间仓库存而非物理退回供应商的
                // 原料仓库存, 造成跨仓账实背离 + 报工待拣批次凭空消失。改用 warehouse-scoped FEFO 锁定采购
                // 入库仓 (resolvePurchaseInboundWh, 默认 WH-LOG), 车间仓批次天然不在此仓范围内。
                String rawWarehouseId = warehouseResolver != null
                        ? warehouseResolver.resolvePurchaseInboundWh(factoryId) : null;
                try {
                    int deducted = 0;
                    for (ReturnOrderItem item : order.getItems()) {
                        if (item.getMaterialTypeId() == null || item.getQuantity() == null
                                || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                            // Skip 行: PURCHASE_RETURN items 必须有 materialTypeId。
                            continue;
                        }
                        deductMaterialForPurchaseReturn(factoryId, order, item, rawWarehouseId);
                        deducted++;
                    }
                    log.info("采购退货 库存扣减完成 (BLOCKER 2): returnNumber={}, lines={}/{} items, 货退回供应商",
                            order.getReturnNumber(), deducted,
                            order.getItems() != null ? order.getItems().size() : 0);
                } catch (BusinessException e) {
                    // 库存不足 = 真错误 (账面/物理不一致), 必须 fail-loud 阻断完成, 不能静默吞。
                    // 退货单停在 FINANCE_APPROVED, 仓管员核实库存后重试。
                    throw e;
                } catch (Exception e) {
                    // 非预期错误同样 fail-loud — 退货扣减是出库可靠性红线, 不能像加库存那样 best-effort。
                    log.error("采购退货 库存扣减失败 (BLOCKER 2, 阻断完成): returnOrderId={}", returnOrderId, e);
                    throw new BusinessException(500, "采购退货库存扣减失败, 退货单未完成")
                            .withHint("请联系管理员核实库存后重试: " + e.getMessage());
                }
            }
        }

        order.setStatus(ReturnOrderStatus.COMPLETED);
        log.info("完成退货单: returnOrderId={}, returnNumber={}, withGoods={}", returnOrderId, order.getReturnNumber(), withGoods);
        return returnOrderRepository.save(order);
    }

    /**
     * BLOCKER 2: PURCHASE_RETURN with-goods 完成时, 从现有库存扣减退货数量 (货退回供应商, 物理离厂)。
     *
     * <p>扣减策略: 按 FEFO 跨同物料类型 (item.materialTypeId) 的可用批次扣减 item.quantity。
     * ReturnOrderItem 不可靠引用单一源批次 (采购入库批次不存 sourceDocId 关联到 receiveRecord),
     * 故按物料类型聚合扣减 —— 退的就是该物料类型的库存, FEFO 顺序确定性。
     *
     * <p>扣减方式镜像 {@code DisposalRecordService}: 增 usedQuantity (不减 receiptQuantity),
     * 使 {@code currentQuantity = receiptQuantity - usedQuantity - reservedQuantity} 正确反映,
     * 并写 {@link MaterialBatchAdjustment} 负数留痕。库存不足抛 409 (账面/物理不一致, fail-loud)。
     *
     * @param rawWarehouseId 采购入库仓 (原料仓) id — 退货从此仓扣减。null 时回退跨仓 FEFO
     *                       (仅 warehouseResolver 未注入的单测场景; 生产环境恒非 null)。
     * @throws BusinessException 库存不足 (409) — 阻断退货完成, 不扣成负数
     */
    private void deductMaterialForPurchaseReturn(String factoryId, ReturnOrder order, ReturnOrderItem item,
                                                 String rawWarehouseId) {
        BigDecimal returnQty = item.getQuantity().setScale(2, RoundingMode.HALF_UP);

        // #2 fix: 优先按【采购入库仓】warehouse-scoped FEFO 取批次 — 排除车间仓 (WH-WKS) 领料搬库批次,
        // 只扣物理退回供应商的原料仓库存。rawWarehouseId 为 null (单测无 resolver) 才回退跨仓 FEFO。
        List<MaterialBatch> batches = rawWarehouseId != null
                ? materialBatchRepository.findAvailableBatchesFEFOByWarehouse(
                        factoryId, item.getMaterialTypeId(), rawWarehouseId)
                : materialBatchRepository.findAvailableBatchesFEFO(factoryId, item.getMaterialTypeId());

        // 先校验总可用量 >= 退货量, 不足则整体阻断 (不部分扣减留下不一致)。
        BigDecimal totalAvailable = batches.stream()
                .map(b -> b.getCurrentQuantity() != null ? b.getCurrentQuantity() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalAvailable.compareTo(returnQty) < 0) {
            throw new BusinessException(409,
                    "退货物料「" + (item.getItemName() != null ? item.getItemName() : item.getMaterialTypeId())
                            + "」可用库存 " + totalAvailable.stripTrailingZeros().toPlainString()
                            + " 不足以退货 " + returnQty.stripTrailingZeros().toPlainString() + ", 无法完成退货")
                    .withHint("货物退回供应商需从库存扣减, 当前可用库存不足。请核实库存或退货数量。")
                    .withHintTarget("quantity");
        }

        BigDecimal remaining = returnQty;
        for (MaterialBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal available = batch.getCurrentQuantity() != null
                    ? batch.getCurrentQuantity() : BigDecimal.ZERO;
            if (available.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal deductFromBatch = available.min(remaining);
            BigDecimal usedBefore = batch.getUsedQuantity() != null
                    ? batch.getUsedQuantity() : BigDecimal.ZERO;
            BigDecimal usedAfter = usedBefore.add(deductFromBatch);
            BigDecimal availableAfter = available.subtract(deductFromBatch);

            // adjustment 留痕: before/after 取可用量, 负数变更 (与 DisposalRecordService 一致)。
            MaterialBatchAdjustment adj = new MaterialBatchAdjustment();
            adj.setId(UUID.randomUUID().toString());
            adj.setMaterialBatchId(batch.getId());
            adj.setAdjustmentType("PURCHASE_RETURN");
            adj.setQuantityBefore(available.setScale(2, RoundingMode.HALF_UP));
            adj.setAdjustmentQuantity(deductFromBatch.negate()); // 负数 (出库)
            adj.setQuantityAfter(availableAfter.setScale(2, RoundingMode.HALF_UP));
            adj.setReason("采购退货出库 [退货单 " + order.getReturnNumber() + "] 货退回供应商"
                    + (order.getReason() != null ? " - " + order.getReason() : ""));
            adj.setAdjustmentTime(LocalDateTime.now());
            adj.setAdjustedBy(order.getApprovedBy());
            adj.setNotes("returnOrderId=" + order.getId() + " materialTypeId=" + item.getMaterialTypeId());
            if (materialBatchAdjustmentRepository != null) {
                materialBatchAdjustmentRepository.save(adj);
            }

            batch.setUsedQuantity(usedAfter.setScale(2, RoundingMode.HALF_UP));
            batch.setLastUsedAt(LocalDateTime.now());
            // 批次扣空 → 标 USED_UP (与领料/报损一致, FEFO 查询自动排除)。
            if (batch.getCurrentQuantity() != null
                    && batch.getCurrentQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                batch.setStatus(MaterialBatchStatus.USED_UP);
            }
            materialBatchRepository.save(batch);

            remaining = remaining.subtract(deductFromBatch);
        }
        log.info("采购退货扣减库存: returnNumber={}, materialTypeId={}, qty={}",
                order.getReturnNumber(), item.getMaterialTypeId(), returnQty.stripTrailingZeros().toPlainString());
    }

    @Override
    public Map<String, Object> getReturnOrderStatistics(String factoryId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        PageRequest all = PageRequest.of(0, Integer.MAX_VALUE);

        Page<ReturnOrder> allOrders = returnOrderRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, all);
        List<ReturnOrder> orders = allOrders.getContent();

        long purchaseReturnCount = orders.stream().filter(o -> o.getReturnType() == ReturnType.PURCHASE_RETURN).count();
        long salesReturnCount = orders.stream().filter(o -> o.getReturnType() == ReturnType.SALES_RETURN).count();
        long pendingApproval = orders.stream().filter(o -> o.getStatus() == ReturnOrderStatus.SUBMITTED).count();

        BigDecimal totalReturnAmount = orders.stream()
                .filter(o -> o.getStatus() != ReturnOrderStatus.REJECTED)
                .map(ReturnOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        stats.put("purchaseReturnCount", purchaseReturnCount);
        stats.put("salesReturnCount", salesReturnCount);
        stats.put("pendingApprovalCount", pendingApproval);
        stats.put("totalReturnAmount", totalReturnAmount);
        return stats;
    }

    // ==================== 内部方法 ====================

    private String generateReturnNumber(String factoryId, ReturnType returnType) {
        String prefix = returnType == ReturnType.PURCHASE_RETURN ? "RT-PUR" : "RT-SAL";
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = returnOrderRepository.countByFactoryIdAndDate(factoryId, LocalDate.now());
        return String.format("%s-%s-%04d", prefix, dateStr, count + 1);
    }
}
