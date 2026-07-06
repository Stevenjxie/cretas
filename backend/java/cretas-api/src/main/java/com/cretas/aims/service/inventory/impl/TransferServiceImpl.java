package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.enums.TransferType;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.*;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.TransferDiffService;
import com.cretas.aims.service.inventory.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.stream.Stream;

@Service
public class TransferServiceImpl implements TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferServiceImpl.class);

    private final InternalTransferRepository transferRepository;
    private final InternalTransferItemRepository transferItemRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final MaterialBatchService materialBatchService;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;

    /** D1 双仓流转 (2026-05-10 spec, PR #309 A1=A). */
    @org.springframework.beans.factory.annotation.Autowired
    private WarehouseResolver warehouseResolver;

    /** Round 11 T4 — Canvas Integration Template hook 1: DB-driven validation. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    /** Round 11 T4 — Canvas Integration Template hook 2: dynamic field persist. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    /** 调拨差异找单服务（可选注入，未配置时静默跳过不影响签收主流程） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TransferDiffService transferDiffService;

    /** Round 14: formula engine for LINE_AMOUNT etc. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.FormulaEngine formulaEngine;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DefaultValueResolver defaultValueResolver;

    @Autowired
    private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;

    /**
     * fool-proof-design Rule 2 (2026-07-04): 申请人/审批人 userId → 姓名解析 (详情页展示用).
     * required=false + null-guard: 同 warehouseResolver 等既有 optional 依赖同款范式,
     * 现有 @InjectMocks 单测不注入此 bean 时不炸, 仅跳过姓名填充 (prod 恒注入).
     */
    @Autowired(required = false)
    private UserRepository userRepository;

    /**
     * 🟢 PURE DISPLAY (fool-proof-design Rule 1, 2026-07-05): 用于 getAvailableBatchesForItem
     * 算「货架实物」提示 (availableQuantity − 未小结报工消耗)。optional field injection (同
     * warehouseResolver/userRepository 既有 pattern) 避免破坏现有 7 参数构造器单测。
     */
    @Autowired(required = false)
    private MaterialConsumptionRepository materialConsumptionRepository;

    /**
     * 🔒 库存完整性修复 (2026-07-06): 调拨签收超收容忍率 (默认 2%)。
     *
     * <p><b>调拨 vs 采购超收语义完全不同</b> — 别照抄 {@link PurchaseServiceImpl#overReceiveRate}
     * 的 30%：采购超收是供应商在下单量基础上主动多发货的业务惯例（客户 audio 确认，超限要求
     * 采购另下订单兜底）；调拨是同企业内部仓库间搬运物理上的同一批货，调出方发运量就是物理
     * 上限——目标仓不可能凭空收到比发运更多的实物。这里的小额上限纯粹是容忍两端过磅（装车/
     * 卸车分别称重）的仪器误差，不是给"多收"业务合法性开口子，因此取一个很小的值 (2%)。
     *
     * <p><b>Bug 复现 (2026-07-06)</b>: {@code receiveTransfer} 对 {@code itemActualQuantities}
     * 逐行填入 {@code receivedQuantity} 时无任何上限校验；{@code confirmTransfer} →
     * {@code createTargetInventory} 直接按 {@code receivedQuantity} 建目标批次 —— 传入远大于
     * 发运量的值（如发 5kg 收 500kg）会在目标仓凭空造出库存差额，源仓库存不受影响（扣减走的是
     * {@code item.getQuantity()} 发运量，不是 receivedQuantity）。{@link TransferDiffServiceImpl}
     * 同时只检测"少收"（received &lt; shipped）生成差异单，"多收"完全没有对应检测/拦截，静默通过。
     *
     * <p>本修复：签收时逐行校验 receivedQuantity ≤ shipped × (1 + tolerance)，超出直接 409
     * 拒绝签收（fool-proof-design Rule 1: 预先显示边界，不事后报错），不建立任何超量状态。
     * Ops 可调：application.properties 设 {@code cretas.transfer.receive-over-tolerance-rate=0.05}
     * 等临时放宽，无需 rebuild。
     *
     * <p>内联默认值 (非仅 @Value 默认) 是有意为之：现有 4 个 {@code TransferReceiveActualQuantityTest}
     * 单测走 7 参数构造器 (无 Spring 容器)，若无内联默认会是 null，校验时 NPE。
     */
    @org.springframework.beans.factory.annotation.Value("${cretas.transfer.receive-over-tolerance-rate:0.02}")
    private BigDecimal transferReceiveOverToleranceRate = new BigDecimal("0.02");

    public TransferServiceImpl(InternalTransferRepository transferRepository,
                               InternalTransferItemRepository transferItemRepository,
                               MaterialBatchRepository materialBatchRepository,
                               FinishedGoodsBatchRepository finishedGoodsBatchRepository,
                               ApplicationEventPublisher applicationEventPublisher,
                               MaterialBatchService materialBatchService,
                               RawMaterialTypeRepository rawMaterialTypeRepository) {
        this.transferRepository = transferRepository;
        this.transferItemRepository = transferItemRepository;
        this.materialBatchRepository = materialBatchRepository;
        this.finishedGoodsBatchRepository = finishedGoodsBatchRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.materialBatchService = materialBatchService;
        this.rawMaterialTypeRepository = rawMaterialTypeRepository;
    }

    @Override
    @Transactional
    public InternalTransfer createTransfer(String factoryId, CreateTransferRequest request, Long userId) {
        // Round 11 T4: Canvas Integration Template hook 1 — DB-driven validation
        if (validationRuleEvaluator != null) {
            try {
                validationRuleEvaluator.validate(factoryId, "transfer", "CREATE",
                        java.util.Map.of(
                            "transferType", request.getTransferType() != null ? request.getTransferType() : "",
                            "targetFactoryId", request.getTargetFactoryId() != null ? request.getTargetFactoryId() : "",
                            "itemCount", request.getItems() != null ? request.getItems().size() : 0));
            } catch (com.cretas.aims.exception.BusinessException e) { throw e; }
            catch (Exception e) { log.warn("Canvas validation non-blocking: {}", e.getMessage()); }
        }

        // 防呆 R4 (幂等防双击): 5min 窗口内同 源厂/目标厂/请求人/调拨日期 的未完成调拨 → 409 + 已有单号 + 跳转提示
        List<InternalTransfer> dupes = transferRepository.findRecentDuplicates(
                factoryId, request.getTargetFactoryId(), userId,
                request.getTransferDate(), LocalDateTime.now().minusMinutes(5));
        if (!dupes.isEmpty()) {
            InternalTransfer existing = dupes.get(0);
            throw new BusinessException(409, String.format(
                    "5 分钟内已创建相同调拨 (调拨单 %s, 目标工厂 %s, 状态 %s), 请勿重复提交",
                    existing.getTransferNumber(), existing.getTargetFactoryId(), existing.getStatus()))
                    .withHint("如需查看已有调拨单请打开 " + existing.getTransferNumber())
                    .withHintTarget(existing.getId());
        }

        String transferNumber = generateTransferNumber(factoryId);

        InternalTransfer transfer = new InternalTransfer();
        transfer.setTransferNumber(transferNumber);
        transfer.setTransferType(TransferType.valueOf(request.getTransferType()));
        transfer.setSourceFactoryId(factoryId);
        transfer.setTargetFactoryId(request.getTargetFactoryId());
        transfer.setSourceWarehouseId(request.getSourceWarehouseId());
        transfer.setTargetWarehouseId(request.getTargetWarehouseId());
        transfer.setTransferDate(request.getTransferDate());
        transfer.setExpectedArrivalDate(request.getExpectedArrivalDate());
        transfer.setStatus(TransferStatus.DRAFT);
        transfer.setRequestedBy(userId);
        transfer.setRemark(request.getRemark());

        transfer = transferRepository.save(transfer);

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CreateTransferRequest.TransferItemDTO itemDTO : request.getItems()) {
            InternalTransferItem item = new InternalTransferItem();
            item.setTransferId(transfer.getId());
            item.setItemType(TransferItemType.valueOf(itemDTO.getItemType()));
            item.setMaterialTypeId(itemDTO.getMaterialTypeId());
            item.setProductTypeId(itemDTO.getProductTypeId());
            item.setItemName(itemDTO.getItemName());
            item.setQuantity(itemDTO.getQuantity());
            item.setUnit(itemDTO.getUnit());
            item.setUnitPrice(itemDTO.getUnitPrice());
            item.setRemark(itemDTO.getRemark());
            transfer.getItems().add(item);

            // R14: try FormulaEngine for LINE_AMOUNT, fall back to entity method
            java.math.BigDecimal lineAmt = null;
            if (formulaEngine != null && item.getQuantity() != null && item.getUnitPrice() != null) {
                try {
                    lineAmt = formulaEngine.evaluate(factoryId, "transfer", "LINE_AMOUNT",
                            java.util.Map.of("quantity", item.getQuantity(), "unitPrice", item.getUnitPrice()));
                } catch (Exception e) { /* fall back */ }
            }
            // Null-safe: 无单价的 item (e.g. D1 反向调拨余料 buildMaterialItem 不设 unitPrice)
            // getLineAmount() 返 null → add(null) 抛 NPE "augend is null" 并标记事务 rollback-only,
            // 即使调用方 fail-soft catch 也会 doom 父事务 (ReverseTransferService onProductionCompleted
            // → completeProduction UnexpectedRollbackException 500). 无价 item 对总额贡献 0。
            java.math.BigDecimal addend = lineAmt != null ? lineAmt : item.getLineAmount();
            if (addend != null) {
                totalAmount = totalAmount.add(addend);
            }
        }

        transfer.setTotalAmount(totalAmount);
        transfer = transferRepository.save(transfer);

        // Round 11 T4: Canvas Integration Template hook 2 — persist dynamic fields.
        // Customer-configured fields (运输车牌号, 司机联系方式, 预计成本) land in
        // cf_* columns on internal_transfers. Silent failure must not break creation.
        if (dynamicFieldService != null && request.getCustomFields() != null && !request.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "transfer", transfer.getId(), request.getCustomFields());
            } catch (Exception e) {
                log.warn("Canvas dynamic fields save failed for transfer {}: {}", transfer.getId(), e.getMessage());
            }
        }

        // Round 11 T4: Canvas Integration Template hook 3 — publish event for trigger chains.
        try {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.TransferCreatedEvent(
                    this, factoryId, transfer.getTargetFactoryId(),
                    transfer.getId(), transfer.getTransferNumber(),
                    transfer.getTransferType() != null ? transfer.getTransferType().name() : null,
                    transfer.getSourceWarehouseId(), transfer.getTargetWarehouseId(),
                    transfer.getTotalAmount()));
        } catch (Exception e) {
            log.warn("Publish TransferCreatedEvent failed for {}: {}", transfer.getId(), e.getMessage());
        }

        log.info("创建调拨单: sourceFactory={}, targetFactory={}, transferNumber={}", factoryId, request.getTargetFactoryId(), transferNumber);
        return transfer;
    }

    @Override
    @Transactional(readOnly = true)
    public InternalTransfer getTransferById(String factoryId, String transferId) {
        InternalTransfer transfer = transferRepository.findByIdAndEitherFactoryId(transferId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("调拨单不存在或无权访问"));
        // Force-initialize items within transaction to prevent LazyInitializationException
        // and ensure clean serialization without duplicates
        transfer.getItems().size();
        // PR #289 §B4 — populate 调出方 currentStock for detail-page "现有库存" column.
        populateCurrentStock(transfer);
        // fool-proof-design Rule 2 — populate 申请人/审批人姓名 for detail-page display (裸 userId → 姓名).
        populateRequestApproveNames(transfer);
        return transfer;
    }

    /**
     * fool-proof-design Rule 2 (2026-07-04, F006 反馈): 详情页「申请人」「审批人」之前直显裸 userId,
     * 仓管员看不懂。批量 (最多2个 id) 一次 findAllById 填充 transient requestedByName/approvedByName。
     */
    private void populateRequestApproveNames(InternalTransfer transfer) {
        if (userRepository == null || transfer == null) return;
        List<Long> userIds = Stream.of(transfer.getRequestedBy(), transfer.getApprovedBy())
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (userIds.isEmpty()) return;
        Map<Long, com.cretas.aims.entity.User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(com.cretas.aims.entity.User::getId, java.util.function.Function.identity()));
        if (transfer.getRequestedBy() != null) {
            com.cretas.aims.entity.User u = userMap.get(transfer.getRequestedBy());
            transfer.setRequestedByName(u != null ? u.getFullName() : null);
        }
        if (transfer.getApprovedBy() != null) {
            com.cretas.aims.entity.User u = userMap.get(transfer.getApprovedBy());
            transfer.setApprovedByName(u != null ? u.getFullName() : null);
        }
    }

    /**
     * Populate each item's transient {@code currentStock} field with the source-side
     * available inventory total. Failure of any single item lookup is logged and skipped
     * (left as null) — never block the read path.
     *
     * <p>PR #289 §B4 客户对接 2026-05-10 — gives 调拨 detail page a quick reference of
     * "how much stock is at the source warehouse right now" without forcing the user to
     * navigate to the warehouse module.
     */
    private void populateCurrentStock(InternalTransfer transfer) {
        if (transfer == null || transfer.getItems() == null || transfer.getItems().isEmpty()) {
            return;
        }
        String sourceFactoryId = transfer.getSourceFactoryId();
        if (sourceFactoryId == null) return;
        // D1: warehouse strategy per PR #310 §5 — 调拨 detail 页"现有库存" filter by source warehouse.
        // 若 transfer 显式指定了 sourceWarehouseId, 使用之; 否则 fallback 全 warehouse (老数据兼容).
        String sourceWarehouseId = transfer.getSourceWarehouseId();
        for (InternalTransferItem item : transfer.getItems()) {
            try {
                BigDecimal stock = null;
                if (item.getItemType() == TransferItemType.RAW_MATERIAL
                        || item.getItemType() == TransferItemType.PACKAGING_MATERIAL) {
                    if (item.getMaterialTypeId() != null) {
                        stock = sourceWarehouseId != null
                                ? materialBatchRepository.sumAvailableQuantityByMaterialTypeAndWarehouse(
                                        sourceFactoryId, item.getMaterialTypeId(), sourceWarehouseId)
                                : materialBatchRepository.sumAvailableQuantityByMaterialType(
                                        sourceFactoryId, item.getMaterialTypeId());
                    }
                } else if (item.getItemType() == TransferItemType.FINISHED_GOODS) {
                    if (item.getProductTypeId() != null) {
                        stock = sourceWarehouseId != null
                                ? finishedGoodsBatchRepository.sumAvailableQuantityByProductTypeAndWarehouse(
                                        sourceFactoryId, item.getProductTypeId(), sourceWarehouseId)
                                : finishedGoodsBatchRepository.sumAvailableQuantityByProductType(
                                        sourceFactoryId, item.getProductTypeId());
                    }
                }
                item.setCurrentStock(stock != null ? stock : BigDecimal.ZERO);
            } catch (Exception e) {
                log.warn("populateCurrentStock failed for item {} on transfer {}: {}",
                        item.getId(), transfer.getId(), e.getMessage());
            }
        }
    }

    /**
     * State-machine helper — factory-scoped lookup with eager item init.
     * Replaces the previous {@code getTransferByIdInternal} which was a TODO leftover from
     * Apr 7 W1 D2; now all state transitions enforce factoryId via {@link #getTransferById}.
     */
    private InternalTransfer loadForStateChange(String factoryId, String transferId) {
        return getTransferById(factoryId, transferId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InternalTransfer> getTransfers(String factoryId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<InternalTransfer> result = transferRepository.findByFactoryId(factoryId, pageRequest);
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    // ==================== 状态机流转 ====================

    @Override
    @Transactional
    public InternalTransfer requestTransfer(String factoryId, String transferId, Long userId) {
        InternalTransfer transfer = loadForStateChange(factoryId, transferId);
        // Source-side action: 只有调出方可以提交申请
        assertSourceFactory(factoryId, transfer, "提交申请");
        assertStatus(transfer, TransferStatus.DRAFT, "提交申请");
        transfer.setStatus(TransferStatus.REQUESTED);
        transfer.setRequestedBy(userId);
        transfer.setRequestedAt(LocalDateTime.now());
        log.info("提交调拨申请: transferId={}", transferId);
        return transferRepository.save(transfer);
    }

    @Override
    @Transactional
    public InternalTransfer approveTransfer(String factoryId, String transferId, Long userId) {
        InternalTransfer transfer = loadForStateChange(factoryId, transferId);
        assertSourceFactory(factoryId, transfer, "审批");
        assertStatus(transfer, TransferStatus.REQUESTED, "审批");
        transfer.setStatus(TransferStatus.APPROVED);
        transfer.setApprovedBy(userId);
        transfer.setApprovedAt(LocalDateTime.now());
        log.info("审批调拨: transferId={}, approvedBy={}", transferId, userId);
        return transferRepository.save(transfer);
    }

    @Override
    @Transactional
    public InternalTransfer rejectTransfer(String factoryId, String transferId, Long userId, String reason) {
        InternalTransfer transfer = loadForStateChange(factoryId, transferId);
        assertSourceFactory(factoryId, transfer, "驳回");
        assertStatus(transfer, TransferStatus.REQUESTED, "驳回");
        transfer.setStatus(TransferStatus.REJECTED);
        transfer.setApprovedBy(userId);
        transfer.setApprovedAt(LocalDateTime.now());
        transfer.setRejectReason(reason);
        log.info("驳回调拨: transferId={}, reason={}", transferId, reason);
        return transferRepository.save(transfer);
    }

    @Override
    @Transactional
    public InternalTransfer shipTransfer(String factoryId, String transferId, Long userId) {
        InternalTransfer transfer = loadForStateChange(factoryId, transferId);
        // 只有调出方可以发货 (因为要扣减调出方库存)
        assertSourceFactory(factoryId, transfer, "发货");
        assertStatus(transfer, TransferStatus.APPROVED, "发货");

        // D1: warehouse strategy per PR #310 §5 — 调拨发货扣减按 source warehouse 过滤.
        String sourceWarehouseId = transfer.getSourceWarehouseId();
        // MES↔ERP Fix #4: 同厂调拨 (源工厂==目标工厂, e.g. 生产仓→物流仓 内部搬托) 不是销售/发货,
        //   成品扣减不得动 shippedQuantity (销售口径), 否则内部搬库虚增销售/COGS + Σproduced 膨胀。
        boolean intraFactory = Objects.equals(
                transfer.getSourceFactoryId(), transfer.getTargetFactoryId());
        for (InternalTransferItem item : transfer.getItems()) {
            deductSourceInventory(transfer.getSourceFactoryId(), sourceWarehouseId, item, intraFactory);
        }

        transfer.setStatus(TransferStatus.SHIPPED);
        transfer.setShippedAt(LocalDateTime.now());
        log.info("调拨发货: transferId={}, sourceFactory={}", transferId, transfer.getSourceFactoryId());
        return transferRepository.save(transfer);
    }

    @Override
    @Transactional
    public InternalTransfer receiveTransfer(String factoryId, String transferId, Long userId) {
        return receiveTransfer(factoryId, transferId, userId, null);
    }

    @Override
    @Transactional
    public InternalTransfer receiveTransfer(String factoryId, String transferId, Long userId,
                                             Map<Long, BigDecimal> itemActualQuantities) {
        InternalTransfer transfer = loadForStateChange(factoryId, transferId);
        // 只有调入方可以签收
        assertTargetFactory(factoryId, transfer, "签收");
        assertStatus(transfer, TransferStatus.SHIPPED, "签收");

        // 🔒 库存完整性修复 (2026-07-06): 在任何 DB mutation 之前, 逐行校验实收量不超过
        // 发运量的容忍上限 (见 transferReceiveOverToleranceRate 字段注释)。防止
        // confirmTransfer 阶段按凭空放大的 receivedQuantity 建目标批次造出幽灵库存。
        // 只校验 map 中显式传入的行 — 回退到 shipped qty 的行 actual==shipped, 恒不超限。
        if (itemActualQuantities != null) {
            for (InternalTransferItem item : transfer.getItems()) {
                BigDecimal actual = item.getId() != null ? itemActualQuantities.get(item.getId()) : null;
                if (actual != null) {
                    validateReceivedNotExceedingShipped(item, actual);
                }
            }
        }

        transfer.setStatus(TransferStatus.RECEIVED);
        transfer.setReceivedAt(LocalDateTime.now());
        // BUG-3 修复: 每个 item 写入 receivedQuantity = map.getOrDefault(id, shippedQty)。
        // itemActualQuantities=null 或 item 不在 map 中时回退为 item.quantity (向后兼容)。
        // 批量 saveAll 避免 N+1。
        for (InternalTransferItem item : transfer.getItems()) {
            BigDecimal actual = (itemActualQuantities != null && item.getId() != null)
                    ? itemActualQuantities.getOrDefault(item.getId(), item.getQuantity())
                    : item.getQuantity();
            item.setReceivedQuantity(actual);
        }
        if (!transfer.getItems().isEmpty()) {
            transferItemRepository.saveAll(transfer.getItems());
        }
        InternalTransfer saved = transferRepository.save(transfer);

        // 差异检测：实收 < 发货量时自动生成差异记录（失败不影响签收主流程）
        if (transferDiffService != null) {
            try {
                var diffs = transferDiffService.detectAndGenerateDiffs(saved, userId);
                if (!diffs.isEmpty()) {
                    log.info("[TDIFF] 调拨 {} 签收后发现 {} 条差异，已生成差异单",
                            transferId, diffs.size());
                }
            } catch (Exception ex) {
                log.warn("[TDIFF] 差异检测失败（不影响签收）: transferId={}, error={}", transferId, ex.getMessage());
            }
        }

        log.info("调拨签收: transferId={}, targetFactory={}", transferId, transfer.getTargetFactoryId());
        return saved;
    }

    @Override
    @Transactional
    public InternalTransfer confirmTransfer(String factoryId, String transferId, Long userId) {
        InternalTransfer transfer = loadForStateChange(factoryId, transferId);
        // 只有调入方可以确认 (因为要增加调入方库存)
        assertTargetFactory(factoryId, transfer, "确认");
        assertStatus(transfer, TransferStatus.RECEIVED, "确认");

        // D1: warehouse strategy per PR #310 §5 — 调拨确认在 target warehouse 创建批次.
        String targetWarehouseId = transfer.getTargetWarehouseId();
        for (InternalTransferItem item : transfer.getItems()) {
            createTargetInventory(transfer.getTargetFactoryId(), targetWarehouseId, item, userId);
        }

        transfer.setStatus(TransferStatus.CONFIRMED);
        transfer.setConfirmedAt(LocalDateTime.now());
        log.info("调拨确认: transferId={}, 库存已更新", transferId);
        return transferRepository.save(transfer);
    }

    @Override
    @Transactional
    public InternalTransfer cancelTransfer(String factoryId, String transferId, Long userId, String reason) {
        InternalTransfer transfer = loadForStateChange(factoryId, transferId);
        // 调出方或调入方都可取消, 但已发货后须走退货流程
        if (transfer.getStatus().isTerminal()) {
            throw new BusinessException(409, "终态调拨单不能取消")
                    .withHint("请刷新调拨单列表查看最新状态");
        }
        if (transfer.getStatus() == TransferStatus.SHIPPED || transfer.getStatus() == TransferStatus.RECEIVED) {
            throw new BusinessException(409, "已发货或已签收的调拨单不能直接取消，请走退货流程")
                    .withHint("请前往退货单模块发起退货");
        }
        transfer.setStatus(TransferStatus.CANCELLED);
        transfer.setRejectReason(reason);
        log.info("取消调拨: transferId={}, reason={}", transferId, reason);
        return transferRepository.save(transfer);
    }

    /** 校验当前 factoryId 必须是调拨单的调出方 */
    private void assertSourceFactory(String factoryId, InternalTransfer transfer, String action) {
        if (!factoryId.equals(transfer.getSourceFactoryId())) {
            throw new BusinessException(403, action + "操作只允许调出方执行 (当前: " + factoryId
                    + ", 调出方: " + transfer.getSourceFactoryId() + ")")
                    .withHint("请使用调出方账号登录");
        }
    }

    /** 校验当前 factoryId 必须是调拨单的调入方 */
    private void assertTargetFactory(String factoryId, InternalTransfer transfer, String action) {
        if (!factoryId.equals(transfer.getTargetFactoryId())) {
            throw new BusinessException(403, action + "操作只允许调入方执行 (当前: " + factoryId
                    + ", 调入方: " + transfer.getTargetFactoryId() + ")")
                    .withHint("请使用调入方账号登录");
        }
    }

    /**
     * 🔒 库存完整性修复 (2026-07-06): 调拨签收超收上限校验核心 (单行)。
     *
     * <p>超过 {@code shipped × (1 + transferReceiveOverToleranceRate)} 抛 409, 不做任何 DB 写。
     * 调用方 (receiveTransfer 早返) 负责在任何 DB mutation 之前调用, 保证事务不被 doom
     * (同 {@link PurchaseServiceImpl#checkOverReceiveCap} fail-fast 范式)。
     *
     * @param item   调拨行 (用于取品名/发运量/单位 — message 需要 fool-proof-design Rule 2 上下文)
     * @param actual 本次实收量 (调用方保证非 null)
     */
    private void validateReceivedNotExceedingShipped(InternalTransferItem item, BigDecimal actual) {
        BigDecimal shipped = item.getQuantity();
        if (shipped == null) return; // 防御: 理论上 quantity 是 @Column(nullable=false)
        BigDecimal tolerance = transferReceiveOverToleranceRate != null
                ? transferReceiveOverToleranceRate : BigDecimal.ZERO;
        BigDecimal maxAllowed = shipped.multiply(BigDecimal.ONE.add(tolerance));
        if (actual.compareTo(maxAllowed) > 0) {
            String name = item.getItemName() != null ? item.getItemName()
                    : (item.getMaterialTypeId() != null ? item.getMaterialTypeId() : item.getProductTypeId());
            String unit = item.getUnit() != null ? " " + item.getUnit() : "";
            throw new BusinessException(409, String.format(
                    "实收量超出发运量上限: 「%s」发运 %s%s, 本次实收 %s%s, 最大可收 %s%s (含 %s%% 称重误差容忍)",
                    name,
                    shipped.stripTrailingZeros().toPlainString(), unit,
                    actual.stripTrailingZeros().toPlainString(), unit,
                    maxAllowed.stripTrailingZeros().toPlainString(), unit,
                    tolerance.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()))
                    .withHint("调拨实收量不能明显超过发运量, 请核实实收数量或联系调出方核对发运记录");
        }
    }

    @Override
    public Map<String, Object> getTransferStatistics(String factoryId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        PageRequest all = PageRequest.of(0, Integer.MAX_VALUE);

        Page<InternalTransfer> outgoing = transferRepository.findBySourceFactoryIdOrderByCreatedAtDesc(factoryId, all);
        Page<InternalTransfer> incoming = transferRepository.findByTargetFactoryIdOrderByCreatedAtDesc(factoryId, all);

        long pendingApproval = outgoing.getContent().stream()
                .filter(t -> t.getStatus() == TransferStatus.REQUESTED).count();
        long pendingReceive = incoming.getContent().stream()
                .filter(t -> t.getStatus() == TransferStatus.SHIPPED).count();

        stats.put("outgoingCount", outgoing.getTotalElements());
        stats.put("incomingCount", incoming.getTotalElements());
        stats.put("pendingApprovalCount", pendingApproval);
        stats.put("pendingReceiveCount", pendingReceive);

        return stats;
    }

    // ==================== 内部方法 ====================

    private void assertStatus(InternalTransfer transfer, TransferStatus expected, String action) {
        if (transfer.getStatus() != expected) {
            throw new BusinessException(409, String.format("当前状态[%s]不允许%s，需要[%s]",
                    transfer.getStatus().getDisplayName(), action, expected.getDisplayName()))
                    .withHint("请刷新调拨单列表查看最新状态");
        }
    }

    private String generateTransferNumber(String factoryId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long ts = System.currentTimeMillis() % 10000;
        return String.format("TRF-%s-%04d", dateStr, ts);
    }

    /**
     * D1: warehouse strategy per PR #310 §5 — 调拨发货扣减按 source warehouse 过滤.
     * sourceWarehouseId null 时 fallback 全 warehouse (老数据兼容).
     *
     * <p><b>B1 两阶段批次选择 (PR #309 B1=C, 2026-05-11)</b>: 如果用户在 SHIP 前已经
     * 指定 {@code item.sourceBatchId} (e.g. 卤味需要选新货, 不要 FEFO 最早), 校验后
     * 优先消耗该批次, 不足部分再 FEFO 兜底. 未指定时 = 默认全 FEFO (原行为).
     */
    private void deductSourceInventory(String factoryId, String sourceWarehouseId, InternalTransferItem item,
                                       boolean intraFactory) {
        // B1: 检查用户是否预选批次 (status=APPROVED 阶段写入). 若 SHIPPED+ 后被回填则也允许走 preselected 分支.
        String preselectedBatchId = item.getSourceBatchId();

        if (item.getItemType() == TransferItemType.RAW_MATERIAL || item.getItemType() == TransferItemType.PACKAGING_MATERIAL) {
            // FEFO 扣减原料/包材库存（先到期先出）— D1: filter by source warehouse if available
            List<MaterialBatch> fefoBatches = sourceWarehouseId != null
                    ? materialBatchRepository.findAvailableBatchesFEFOByWarehouse(
                            factoryId, item.getMaterialTypeId(), sourceWarehouseId)
                    : materialBatchRepository.findAvailableBatchesFEFO(factoryId, item.getMaterialTypeId());

            // B1: 若用户预选, 校验该批次有效 + reorder 到队首
            List<MaterialBatch> batches = reorderMaterialBatchesForPreselection(
                    fefoBatches, preselectedBatchId, factoryId, sourceWarehouseId, item);

            BigDecimal remaining = item.getQuantity();
            String firstConsumedBatchId = null;
            for (MaterialBatch batch : batches) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                // 使用乐观锁思路: 读取→计算→保存，@Transactional 保证原子性
                BigDecimal available = batch.getReceiptQuantity()
                        .subtract(batch.getUsedQuantity())
                        .subtract(batch.getReservedQuantity() != null ? batch.getReservedQuantity() : BigDecimal.ZERO);
                if (available.compareTo(BigDecimal.ZERO) <= 0) continue;
                BigDecimal deduct = remaining.min(available);
                batch.setUsedQuantity(batch.getUsedQuantity().add(deduct));
                if (batch.getReceiptQuantity().subtract(batch.getUsedQuantity()).compareTo(BigDecimal.ZERO) <= 0) {
                    batch.setStatus(MaterialBatchStatus.DEPLETED);
                }
                materialBatchRepository.saveAndFlush(batch); // flush 立即写入，减少并发窗口
                if (firstConsumedBatchId == null) firstConsumedBatchId = batch.getId();
                inventoryLowStockEventPublisher.publishIfLowStock(factoryId, batch, "TRANSFER_OUT");
                remaining = remaining.subtract(deduct);
                log.info("扣减原料批次: batchId={}, deduct={}, remaining={}, preselected={}",
                        batch.getId(), deduct, remaining, preselectedBatchId != null);
            }
            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                throw new BusinessException(409, String.format(
                    "原料库存不足: %s, 需要 %s, 缺少 %s",
                    item.getMaterialTypeId(), item.getQuantity(), remaining))
                        .withHint("请先采购或从其他工厂调入该原料");
            }
            // B1: 记录实际首个消耗的批次 (用户指定的 = preselected; 否则 FEFO 选中的).
            if (preselectedBatchId == null && firstConsumedBatchId != null) {
                item.setSourceBatchId(firstConsumedBatchId);
            }
        } else {
            // 扣减成品库存 — D1: filter by source warehouse if available
            List<FinishedGoodsBatch> fefoBatches = sourceWarehouseId != null
                    ? finishedGoodsBatchRepository.findAvailableBatchesByWarehouse(
                            factoryId, item.getProductTypeId(), sourceWarehouseId)
                    : finishedGoodsBatchRepository.findAvailableBatches(factoryId, item.getProductTypeId());

            // B1: 若用户预选, 校验该批次有效 + reorder 到队首
            List<FinishedGoodsBatch> batches = reorderFinishedGoodsBatchesForPreselection(
                    fefoBatches, preselectedBatchId, factoryId, sourceWarehouseId, item);

            BigDecimal remaining = item.getQuantity();
            String firstConsumedBatchId = null;
            for (FinishedGoodsBatch batch : batches) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                BigDecimal available = batch.getAvailableQuantity();
                BigDecimal deduct = remaining.min(available);
                if (intraFactory) {
                    // MES↔ERP Fix #4: 同厂调拨 = 内部搬仓, 非销售. 减 producedQuantity (对齐
                    //   FinishedGoodsFeedServiceImpl / 报损 SCRAP), 绝不动 shippedQuantity → 不虚增
                    //   销售/发货/COGS. availableQuantity = produced - shipped - reserved 仍正确下降;
                    //   源 -qty / 目标 +qty (createTargetInventory) → 工厂 Σproduced 守恒 (不 +qty 膨胀)。
                    batch.setProducedQuantity(batch.getProducedQuantity().subtract(deduct));
                } else {
                    // 跨厂调拨 = 成品离开本厂, 记 shippedQuantity (原行为不变)。
                    batch.setShippedQuantity(batch.getShippedQuantity().add(deduct));
                }
                if (batch.isDepleted()) batch.setStatus("DEPLETED");
                finishedGoodsBatchRepository.save(batch);
                if (firstConsumedBatchId == null) firstConsumedBatchId = batch.getId();
                remaining = remaining.subtract(deduct);
            }
            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                // 修复: 此前仅 log.warn 静默少发 (调出方库存不足时调拨单声称数量 > 实际扣减,
                // 接收方据单收货 → 库存对不上, 静默数据丢失)。对齐 RAW_MATERIAL 分支: 不足即 409 拦截,
                // 禁止降级处理 (符合"禁止假数据/静默失败"核心原则 + 出库可靠性红线)。
                throw new BusinessException(409, String.format(
                    "成品库存不足: %s, 需要 %s, 缺少 %s",
                    item.getProductTypeId(), item.getQuantity(), remaining))
                        .withHint("请先生产入库或减少调拨数量");
            }
            if (preselectedBatchId == null && firstConsumedBatchId != null) {
                item.setSourceBatchId(firstConsumedBatchId);
            }
        }
    }

    /**
     * B1: 若用户预选 materialBatch, 校验后 reorder 到 FEFO 队首.
     * 不存在 / 错 warehouse / 错 materialType / 错 factory → BusinessException 409.
     */
    private List<MaterialBatch> reorderMaterialBatchesForPreselection(
            List<MaterialBatch> fefoBatches,
            String preselectedBatchId,
            String factoryId,
            String sourceWarehouseId,
            InternalTransferItem item) {
        if (preselectedBatchId == null) return fefoBatches;

        // 校验 + 找到 preselected batch
        MaterialBatch preselected = null;
        List<MaterialBatch> remainingBatches = new ArrayList<>();
        for (MaterialBatch b : fefoBatches) {
            if (preselectedBatchId.equals(b.getId())) {
                preselected = b;
            } else {
                remainingBatches.add(b);
            }
        }
        if (preselected == null) {
            // 不在可用 FEFO 列表 (不存在 / depleted / 错 warehouse / 错 materialType)
            throw new BusinessException(409, String.format(
                    "指定的原料批次 %s 不可用 (不存在/已耗尽/不属于源仓库/物料不匹配)",
                    preselectedBatchId))
                    .withHint("请刷新调拨单后重新选择批次, 或留空使用默认 FEFO");
        }
        // preselected 放队首, 其余按 FEFO 顺序保留 (用于不足时兜底)
        List<MaterialBatch> reordered = new ArrayList<>(fefoBatches.size());
        reordered.add(preselected);
        reordered.addAll(remainingBatches);
        return reordered;
    }

    /**
     * B1: 若用户预选 finishedGoodsBatch, 校验后 reorder 到 FEFO 队首.
     */
    private List<FinishedGoodsBatch> reorderFinishedGoodsBatchesForPreselection(
            List<FinishedGoodsBatch> fefoBatches,
            String preselectedBatchId,
            String factoryId,
            String sourceWarehouseId,
            InternalTransferItem item) {
        if (preselectedBatchId == null) return fefoBatches;

        FinishedGoodsBatch preselected = null;
        List<FinishedGoodsBatch> remainingBatches = new ArrayList<>();
        for (FinishedGoodsBatch b : fefoBatches) {
            if (preselectedBatchId.equals(b.getId())) {
                preselected = b;
            } else {
                remainingBatches.add(b);
            }
        }
        if (preselected == null) {
            throw new BusinessException(409, String.format(
                    "指定的成品批次 %s 不可用 (不存在/已耗尽/不属于源仓库/产品不匹配)",
                    preselectedBatchId))
                    .withHint("请刷新调拨单后重新选择批次, 或留空使用默认 FEFO");
        }
        List<FinishedGoodsBatch> reordered = new ArrayList<>(fefoBatches.size());
        reordered.add(preselected);
        reordered.addAll(remainingBatches);
        return reordered;
    }

    // ==================== B1 两阶段批次选择 API (PR #309 B1=C, 2026-05-11) ====================

    /**
     * B1: 列出某 transfer item 在 source warehouse 当前可用批次, 用于 SHIP 前选批次 dropdown.
     * 仅 status=APPROVED (or earlier, before SHIP) 时有意义.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAvailableBatchesForItem(String factoryId, String transferId, Long itemId) {
        InternalTransfer transfer = loadForStateChange(factoryId, transferId);
        assertSourceFactory(factoryId, transfer, "查询可用批次");

        InternalTransferItem item = transfer.getItems().stream()
                .filter(i -> itemId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("调拨明细不存在: " + itemId));

        String sourceWarehouseId = transfer.getSourceWarehouseId();
        String sourceFactoryId = transfer.getSourceFactoryId();

        List<Map<String, Object>> result = new ArrayList<>();
        if (item.getItemType() == TransferItemType.RAW_MATERIAL || item.getItemType() == TransferItemType.PACKAGING_MATERIAL) {
            List<MaterialBatch> batches = sourceWarehouseId != null
                    ? materialBatchRepository.findAvailableBatchesFEFOByWarehouse(
                            sourceFactoryId, item.getMaterialTypeId(), sourceWarehouseId)
                    : materialBatchRepository.findAvailableBatchesFEFO(sourceFactoryId, item.getMaterialTypeId());
            // 🟢 PURE DISPLAY (fool-proof-design Rule 1, 2026-07-05): 预先算出「货架实物」= availableQuantity
            //   − 未小结报工消耗 (mirror FactoryStocktakeServiceImpl#loadUnsettledByBatch / physicalShelf,
            //   同数据源 sumUnsettledConsumptionGroupedByBatch), 让调出方在选批次时就看到真实可搬动量 —
            //   而不是被账面未扣的 stale-high availableQuantity 误导, 事后小结才发现物理不足。
            //   ⛔ 不改任何 gate/校验: availableQuantity 字段与既有前端超量校验逻辑完全不变, 本字段只是
            //   额外附加的 honest 提示。materialConsumptionRepository 为 null (单测未注入) → 空 map,
            //   physicalAvailable 退化为等于 availableQuantity (无回归)。
            Map<String, BigDecimal> unsettledByBatch = loadUnsettledForBatches(sourceFactoryId, batches);
            for (MaterialBatch b : batches) {
                BigDecimal available = b.getReceiptQuantity()
                        .subtract(b.getUsedQuantity())
                        .subtract(b.getReservedQuantity() != null ? b.getReservedQuantity() : BigDecimal.ZERO);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("batchId", b.getId());
                row.put("batchNumber", b.getBatchNumber());
                row.put("availableQuantity", available);
                row.put("physicalAvailable", physicalShelf(available, unsettledByBatch.get(b.getId())));
                row.put("expireDate", b.getExpireDate());
                row.put("warehouseId", b.getWarehouseId());
                result.add(row);
            }
        } else {
            List<FinishedGoodsBatch> batches = sourceWarehouseId != null
                    ? finishedGoodsBatchRepository.findAvailableBatchesByWarehouse(
                            sourceFactoryId, item.getProductTypeId(), sourceWarehouseId)
                    : finishedGoodsBatchRepository.findAvailableBatches(sourceFactoryId, item.getProductTypeId());
            for (FinishedGoodsBatch b : batches) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("batchId", b.getId());
                row.put("batchNumber", b.getBatchNumber());
                row.put("availableQuantity", b.getAvailableQuantity());
                row.put("expireDate", b.getExpireDate());
                row.put("warehouseId", b.getWarehouseId());
                result.add(row);
            }
        }
        return result;
    }

    /**
     * 🟢 PURE DISPLAY helper (fool-proof-design Rule 1, 2026-07-05): 逐批查未小结报工消耗量,
     * 供 {@link #getAvailableBatchesForItem} 算「货架实物」提示。与
     * {@code FactoryStocktakeServiceImpl#loadUnsettledByBatch} 同一 SQL / 同一门控口径
     * (谓词见 {@link com.cretas.aims.repository.MaterialConsumptionRepository#sumUnsettledConsumptionGroupedByBatch}),
     * 只是消费方从"盘点快照"换成"调拨发货批次选择"。materialConsumptionRepository 为 null
     * (existing @InjectMocks-less 单测未注入, 见 TransferShipBatchSelectionTest/TransferReceiveActualQuantityTest
     * 直接 `new TransferServiceImpl(...)` 7 参数构造器不含此依赖) → 空 map, 调用方按 0 处理,
     * physicalAvailable 退化为 availableQuantity (无回归)。
     */
    private Map<String, BigDecimal> loadUnsettledForBatches(String factoryId, List<MaterialBatch> batches) {
        Map<String, BigDecimal> map = new HashMap<>();
        if (materialConsumptionRepository == null || batches == null || batches.isEmpty()) {
            return map;
        }
        List<String> batchIds = batches.stream()
                .map(MaterialBatch::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (batchIds.isEmpty()) {
            return map;
        }
        for (Object[] row : materialConsumptionRepository.sumUnsettledConsumptionGroupedByBatch(factoryId, batchIds)) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }
            Object sum = row[1];
            BigDecimal value = sum instanceof BigDecimal ? (BigDecimal) sum : new BigDecimal(String.valueOf(sum));
            map.put((String) row[0], value);
        }
        return map;
    }

    /**
     * 货架实物量 = max(0, available − 未结报工消耗)。clamp 到 0 防超投场景算出负账面反被误判。
     * unsettled=null → 减 0 (mirror FactoryStocktakeServiceImpl#physicalShelf / SemiFinishedStocktakeServiceImpl#physicalShelf)。
     */
    private static BigDecimal physicalShelf(BigDecimal available, BigDecimal unsettled) {
        BigDecimal a = available != null ? available : BigDecimal.ZERO;
        BigDecimal u = unsettled != null ? unsettled : BigDecimal.ZERO;
        BigDecimal shelf = a.subtract(u);
        return shelf.signum() < 0 ? BigDecimal.ZERO : shelf;
    }

    /**
     * B1: 更新 transfer item 的 sourceBatchId (用户在 SHIP 前选批次). null = 清除预选, 走 FEFO.
     * 仅 source factory + status=APPROVED 时允许.
     */
    @Override
    @Transactional
    public InternalTransferItem updateItemSourceBatch(String factoryId, String transferId,
                                                       Long itemId, String sourceBatchId) {
        InternalTransfer transfer = loadForStateChange(factoryId, transferId);
        assertSourceFactory(factoryId, transfer, "选择批次");
        // 只允许 APPROVED 阶段改 (SHIP 时已锁定).
        if (transfer.getStatus() != TransferStatus.APPROVED) {
            throw new BusinessException(409, String.format(
                    "当前状态[%s]不允许修改批次, 仅[已批准]状态可选批次",
                    transfer.getStatus().getDisplayName()))
                    .withHint("请刷新调拨单状态");
        }
        InternalTransferItem item = transfer.getItems().stream()
                .filter(i -> itemId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("调拨明细不存在: " + itemId));

        // null = 清除预选 (走 FEFO). 非 null = 校验批次存在且属 source factory + warehouse.
        if (sourceBatchId != null) {
            validateBatchForItem(transfer, item, sourceBatchId);
        }
        item.setSourceBatchId(sourceBatchId);
        transferItemRepository.save(item);
        log.info("调拨明细批次选择: transferId={}, itemId={}, sourceBatchId={}",
                transferId, itemId, sourceBatchId);
        return item;
    }

    /**
     * B1: 校验用户选的批次属于调拨单 source factory + source warehouse + 物料/产品匹配.
     * Throws BusinessException 409 if invalid.
     */
    private void validateBatchForItem(InternalTransfer transfer, InternalTransferItem item, String batchId) {
        String sourceWarehouseId = transfer.getSourceWarehouseId();
        String sourceFactoryId = transfer.getSourceFactoryId();

        if (item.getItemType() == TransferItemType.RAW_MATERIAL || item.getItemType() == TransferItemType.PACKAGING_MATERIAL) {
            MaterialBatch b = materialBatchRepository.findById(batchId).orElse(null);
            if (b == null) {
                throw new BusinessException(409, "批次不存在: " + batchId)
                        .withHint("请刷新可用批次列表");
            }
            if (!sourceFactoryId.equals(b.getFactoryId())) {
                throw new BusinessException(409, "批次不属于源工厂")
                        .withHint("请重新选择批次");
            }
            if (sourceWarehouseId != null && !sourceWarehouseId.equals(b.getWarehouseId())) {
                throw new BusinessException(409, "批次不属于源仓库")
                        .withHint("请重新选择批次");
            }
            if (!item.getMaterialTypeId().equals(b.getMaterialTypeId())) {
                throw new BusinessException(409, "批次物料类型不匹配")
                        .withHint("请重新选择批次");
            }
            BigDecimal available = b.getReceiptQuantity()
                    .subtract(b.getUsedQuantity())
                    .subtract(b.getReservedQuantity() != null ? b.getReservedQuantity() : BigDecimal.ZERO);
            if (available.compareTo(item.getQuantity()) < 0) {
                throw new BusinessException(409, String.format(
                        "批次可用量不足: 需要 %s, 仅 %s",
                        item.getQuantity(), available))
                        .withHint("请选择其他批次或留空走 FEFO");
            }
        } else {
            FinishedGoodsBatch b = finishedGoodsBatchRepository.findById(batchId).orElse(null);
            if (b == null) {
                throw new BusinessException(409, "批次不存在: " + batchId)
                        .withHint("请刷新可用批次列表");
            }
            if (!sourceFactoryId.equals(b.getFactoryId())) {
                throw new BusinessException(409, "批次不属于源工厂")
                        .withHint("请重新选择批次");
            }
            if (sourceWarehouseId != null && !sourceWarehouseId.equals(b.getWarehouseId())) {
                throw new BusinessException(409, "批次不属于源仓库")
                        .withHint("请重新选择批次");
            }
            if (!item.getProductTypeId().equals(b.getProductTypeId())) {
                throw new BusinessException(409, "批次产品类型不匹配")
                        .withHint("请重新选择批次");
            }
            if (b.getAvailableQuantity().compareTo(item.getQuantity()) < 0) {
                throw new BusinessException(409, String.format(
                        "批次可用量不足: 需要 %s, 仅 %s",
                        item.getQuantity(), b.getAvailableQuantity()))
                        .withHint("请选择其他批次或留空走 FEFO");
            }
        }
    }

    /**
     * D1: warehouse strategy per PR #310 §5 — 调拨确认在 target warehouse 创建批次.
     * targetWarehouseId null 时 fallback 默认 WH-LOG (兼容老调拨单).
     */
    private void createTargetInventory(String targetFactoryId, String targetWarehouseId, InternalTransferItem item, Long userId) {
        BigDecimal qty = item.getReceivedQuantity() != null ? item.getReceivedQuantity() : item.getQuantity();

        // D1: 解析 target warehouse — 显式指定优先, 否则按类型 fallback
        String resolvedTargetWarehouseId = targetWarehouseId != null
                ? targetWarehouseId
                : warehouseResolver.resolveLogisticsId(targetFactoryId);  // raw material 默认 WH-LOG

        if (item.getItemType() == TransferItemType.RAW_MATERIAL) {
            // 调入方创建原料批次
            MaterialBatch batch = new MaterialBatch();
            batch.setId(UUID.randomUUID().toString());
            batch.setFactoryId(targetFactoryId);
            batch.setBatchNumber(String.format("TRF-MT-%s-%04d",
                    LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")),
                    System.currentTimeMillis() % 10000));
            batch.setMaterialTypeId(item.getMaterialTypeId());
            batch.setReceiptQuantity(qty);
            batch.setUsedQuantity(BigDecimal.ZERO);
            batch.setReservedQuantity(BigDecimal.ZERO);
            batch.setQuantityUnit(item.getUnit());
            batch.setUnitPrice(item.getUnitPrice());
            batch.setReceiptDate(LocalDate.now());
            batch.setStatus(MaterialBatchStatus.AVAILABLE);
            batch.setCreatedBy(userId);
            batch.setWarehouseId(resolvedTargetWarehouseId);  // D1 双仓
            materialBatchRepository.save(batch);
            item.setTargetBatchId(batch.getId());

            // 调入跟 PR #113 (PurchaseServiceImpl.confirmReceive) 同根因: 直接 new
            // MaterialBatch + repo.save 绕开 MaterialBatchService.createMaterialBatch
            // 路径上的 updateMovingAvgPrice。但 transfer 多了跨工厂语义复杂性 ——
            // raw_material_types 是 factory-scoped, 跨工厂调入时 source factory 的
            // raw_material_type 不属 target factory, 直接 recalc 会污染 source 的均价。
            //
            // Option B (本 PR scope): 仅同工厂场景安全 recalc; 跨工厂场景 warn-log 跳过,
            // 留 follow-up ticket 决定 schema (lookup-or-create / 全局共享 / 业务流程改造)。
            RawMaterialType materialType = rawMaterialTypeRepository
                    .findById(item.getMaterialTypeId()).orElse(null);
            if (materialType == null) {
                log.warn("transferIn moving_avg skipped: material_type {} not found, transferId={}",
                        item.getMaterialTypeId(), item.getTransferId());
            } else if (!materialType.getFactoryId().equals(targetFactoryId)) {
                log.warn(
                        "transferIn moving_avg skipped: source factory {} != target factory {} " +
                        "(schema follow-up: cross-factory raw_material_type linkage). " +
                        "transferId={}, materialTypeId={}",
                        materialType.getFactoryId(), targetFactoryId,
                        item.getTransferId(), item.getMaterialTypeId());
            } else {
                // 同工厂调入 (e.g. 仓 A→仓 B): 同根因 fix 适用, recalc 安全
                materialBatchService.recalculateMovingAvgPrice(
                        item.getMaterialTypeId(),
                        qty,
                        item.getUnitPrice(),
                        batch.getId());
            }
        } else {
            // 调入方创建成品批次
            FinishedGoodsBatch batch = new FinishedGoodsBatch();
            batch.setFactoryId(targetFactoryId);
            batch.setBatchNumber(String.format("TRF-FG-%s-%04d",
                    LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")),
                    System.currentTimeMillis() % 10000));
            batch.setProductTypeId(item.getProductTypeId());
            batch.setProductName(item.getItemName());
            batch.setProducedQuantity(qty);
            batch.setUnit(item.getUnit());
            batch.setUnitPrice(item.getUnitPrice());
            // MES↔ERP Fix #4: 保成本血缘 — 调入方成品批次继承调出方源批次的 unitCost (库存成本),
            //   否则 unitCost=null → 下游成本口径把内部搬库的成品当零成本 (honest-null → 0)。
            //   源批次 id = item.sourceBatchId (SHIP 时 deductSourceInventory 记录的首个消耗批次)。
            //   诚实 null: 源批次无成本 (unitCost=null) → 目标亦 null, 不伪造 ¥0。unitPrice(售价) 独立处理。
            if (item.getSourceBatchId() != null) {
                finishedGoodsBatchRepository.findById(item.getSourceBatchId())
                        .map(FinishedGoodsBatch::getUnitCost)
                        .ifPresent(batch::setUnitCost);
            }
            batch.setProductionDate(LocalDate.now());
            batch.setStatus("AVAILABLE");
            batch.setCreatedBy(userId);
            batch.setWarehouseId(resolvedTargetWarehouseId);  // D1 双仓
            finishedGoodsBatchRepository.save(batch);
            item.setTargetBatchId(batch.getId());
        }
    }
}
