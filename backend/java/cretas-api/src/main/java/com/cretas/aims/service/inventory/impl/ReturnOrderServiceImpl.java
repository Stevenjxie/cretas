package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.InboundType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ReturnOrderServiceImpl implements ReturnOrderService {

    private static final Logger log = LoggerFactory.getLogger(ReturnOrderServiceImpl.class);

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
                    arApService.recordAdjustment(factoryId,
                            com.cretas.aims.entity.enums.CounterpartyType.SUPPLIER,
                            order.getCounterpartyId(),
                            order.getTotalAmount().negate(),
                            approverId,
                            "采购退货冲减(无货)-" + order.getReturnNumber());
                    log.info("采购退货冲减应付(无货, 审批立即触发): returnNumber={}, amount={}", order.getReturnNumber(), order.getTotalAmount());
                } else if (order.getReturnType() == ReturnType.SALES_RETURN) {
                    arApService.recordAdjustment(factoryId,
                            com.cretas.aims.entity.enums.CounterpartyType.CUSTOMER,
                            order.getCounterpartyId(),
                            order.getTotalAmount().negate(),
                            approverId,
                            "销售退货冲减(无货)-" + order.getReturnNumber());
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
    public ReturnOrder rejectReturnOrder(String factoryId, String returnOrderId) {
        ReturnOrder order = getReturnOrderById(factoryId, returnOrderId);
        if (order.getStatus() != ReturnOrderStatus.SUBMITTED) {
            throw new BusinessException(409, "只有已提交状态的退货单可以驳回")
                    .withHint("请刷新退货单列表查看最新状态");
        }
        order.setStatus(ReturnOrderStatus.REJECTED);
        log.info("驳回退货单: returnOrderId={}, returnNumber={}", returnOrderId, order.getReturnNumber());
        return returnOrderRepository.save(order);
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
            try {
                if (order.getReturnType() == ReturnType.PURCHASE_RETURN) {
                    arApService.recordAdjustment(factoryId,
                            com.cretas.aims.entity.enums.CounterpartyType.SUPPLIER,
                            order.getCounterpartyId(),
                            order.getTotalAmount().negate(),
                            order.getApprovedBy(),
                            "采购退货冲减(实物已入库)-" + order.getReturnNumber());
                    log.info("采购退货冲减应付(实物已入库, completion 触发): returnNumber={}, amount={}", order.getReturnNumber(), order.getTotalAmount());
                } else if (order.getReturnType() == ReturnType.SALES_RETURN) {
                    arApService.recordAdjustment(factoryId,
                            com.cretas.aims.entity.enums.CounterpartyType.CUSTOMER,
                            order.getCounterpartyId(),
                            order.getTotalAmount().negate(),
                            order.getApprovedBy(),
                            "销售退货冲减(实物已入库)-" + order.getReturnNumber());
                    log.info("销售退货冲减应收(实物已入库, completion 触发): returnNumber={}, amount={}", order.getReturnNumber(), order.getTotalAmount());
                }
            } catch (Exception e) {
                log.error("退货AR/AP冲减失败 (有货 completion path): returnOrderId={}", returnOrderId, e);
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
                    && materialBatchRepository != null && warehouseResolver != null) {
                // Issue #795: PURCHASE_RETURN with-goods → 不良品入库 (镜像 SALES_RETURN logic).
                // 顾客 (六扇门第四次 May 10 L980-1011): "有食物的话, 库存入库到总仓 + 不良品状态".
                // 现实场景: 采购退货发现质量问题, 物料先回 WH-LOG, 后续仓管员决定丢弃 / 退给供应商.
                try {
                    String whLogId = warehouseResolver.resolveLogisticsId(factoryId);
                    int itemIdx = 0;
                    int created = 0;
                    for (ReturnOrderItem item : order.getItems()) {
                        itemIdx++;
                        if (item.getMaterialTypeId() == null || item.getQuantity() == null
                                || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                            // Skip 行: PURCHASE_RETURN items 必须有 materialTypeId.
                            // 历史脏数据 / 误填 productTypeId 的 row, 跳过 inventory 创建,
                            // status flip + AR/AP 冲减 仍然 OK.
                            continue;
                        }
                        MaterialBatch batch = new MaterialBatch();
                        batch.setId(UUID.randomUUID().toString());
                        batch.setFactoryId(factoryId);
                        batch.setBatchNumber("RTN-" + order.getReturnNumber() + "-" + itemIdx);
                        batch.setMaterialTypeId(item.getMaterialTypeId());
                        batch.setReceiptQuantity(item.getQuantity());
                        batch.setUsedQuantity(BigDecimal.ZERO);
                        batch.setReservedQuantity(BigDecimal.ZERO);
                        // 单位 — ReturnOrderItem 没有 unit 字段, 默认 "件" 跟 SALES_RETURN 对齐.
                        // 仓管员后续 disposal 流程可纠正.
                        batch.setQuantityUnit("件");
                        batch.setUnitPrice(item.getUnitPrice());
                        batch.setReceiptDate(LocalDate.now());
                        batch.setPurchaseDate(LocalDate.now());
                        batch.setWarehouseId(whLogId);
                        batch.setStatus(MaterialBatchStatus.DEFECTIVE);
                        batch.setInboundType(InboundType.SUPPLIER_RETURN);
                        // P0-17 source doc traceability (镜像采购入库 createMaterialBatchFromReceiveItem 的语义).
                        batch.setSourceDocType("PURCHASE_RETURN");
                        batch.setSourceDocId(order.getId());
                        batch.setCreatedBy(order.getApprovedBy());
                        batch.setNotes("采购退货入库(不良品) - " + order.getReturnNumber()
                                + " - 原因: " + (item.getReason() != null ? item.getReason() : order.getReason()));
                        materialBatchRepository.save(batch);
                        created++;
                    }
                    log.info("采购退货 不良品入库完成 (issue #795): returnNumber={}, batches={}/{} items, warehouse=WH-LOG",
                            order.getReturnNumber(), created,
                            order.getItems() != null ? order.getItems().size() : 0);
                } catch (Exception e) {
                    // Non-blocking — AR/AP 冲减 + status flip 必须生效. 库存入库失败仅影响 traceability,
                    // 仓管员可手动补录. 镜像 SALES_RETURN 的错误恢复策略.
                    log.error("采购退货 不良品入库失败 (status flip + AP冲减 仍生效, issue #795): returnOrderId={}",
                            returnOrderId, e);
                }
            }
        }

        order.setStatus(ReturnOrderStatus.COMPLETED);
        log.info("完成退货单: returnOrderId={}, returnNumber={}, withGoods={}", returnOrderId, order.getReturnNumber(), withGoods);
        return returnOrderRepository.save(order);
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
