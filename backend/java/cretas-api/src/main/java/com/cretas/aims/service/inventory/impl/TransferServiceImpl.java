package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.enums.TransferType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.material.MaterialPackagingSpecRepository;
import com.cretas.aims.repository.inventory.*;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.TransferDiffService;
import com.cretas.aims.service.inventory.TransferService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.workflow.SelfApprovalPolicy;
import com.cretas.aims.service.workflow.WorkflowEngineService;
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
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class TransferServiceImpl implements TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferServiceImpl.class);

    /** 唯一允许自审批的角色 —— 见 allowsSuperAdminSelfApproval 的说明。 */
    private static final String SUPER_ADMIN_ROLE = "factory_super_admin";

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

    /** 调拨提交及审批只使用统一 OA 实例，不在调拨详情维护第二套审批状态机。 */
    @Autowired(required = false)
    private WorkflowEngineService workflowEngine;

    /**
     * 「发起人能否审批自己的单」的唯一判定处 —— 与销售单、采购单共用同一份语义。
     * 此前本处无条件禁止自审, 而采购单有私有例外, 同一条规则两种行为。
     */
    @Autowired(required = false)
    private SelfApprovalPolicy selfApprovalPolicy;

    @Autowired(required = false)
    private ApprovalWorkflowService approvalWorkflowService;

    /** 调拨 payload 单位只保存 canonical code；中文仅属于 Web displayUnit。 */
    @Autowired(required = false)
    private UnitContractService unitContractService;

    @Autowired(required = false)
    private MaterialPackagingSpecRepository materialPackagingSpecRepository;

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
        TransferType transferType = TransferType.valueOf(request.getTransferType());
        validateCreateWarehouseRoute(factoryId, request, transferType);
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

        // ⛔ 已移除「5min 窗口内重复调拨 → 409」拦截 (原 防呆 R4 幂等防双击, 2026-06-18)。
        //
        // 去重键是 (源厂 + 目标厂 + 请求人 + 调拨日期), <b>不含任何内容维度</b> —— 同一天同一人
        // 给同一目标厂调<b>不同物料</b>也会被判成"相同调拨"。2026-08-02 实测: 先建一张只含冻猪蹄的
        // 草稿, 紧接着建成品盒的调拨即被拒, 只能挤进同一张单; 而同单里只要有一个物料触发校验
        // (如包材单位不匹配) 整张单失败, 无法分批推进 —— 备料被彻底卡住。
        //
        // 同模式的兄弟实现都带内容维度 (ExpenseRequest 用 category+amount, PaymentRecord 注释明写
        // "镜像 TransferServiceImpl.findRecentDuplicates。金额是最高完整性面"), 唯独调拨没有 ——
        // 它是异类。Steve 2026-08-03 拍板: 调拨不做重复提交拦截。
        //
        // 双击风险: 会多出一张 DRAFT 草稿, 仓管可直接取消, 代价远小于"备不了料"。

        validateAndNormalizeCreateItems(factoryId, request, transferType);

        String transferNumber = generateTransferNumber(factoryId);

        InternalTransfer transfer = new InternalTransfer();
        transfer.setTransferNumber(transferNumber);
        transfer.setTransferType(transferType);
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
            item.setMaterialPackagingSpecId(itemDTO.getMaterialPackagingSpecId());
            item.setPackageQuantitySnapshot(itemDTO.getPackageQuantitySnapshot());
            item.setPackageUnitSnapshot(itemDTO.getPackageUnitSnapshot());
            item.setInventoryBaseUnitSnapshot(itemDTO.getInventoryBaseUnitSnapshot());
            item.setPackageToBaseFactorSnapshot(itemDTO.getPackageToBaseFactorSnapshot());
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

    private void validateCreateWarehouseRoute(String factoryId, CreateTransferRequest request, TransferType transferType) {
        if (transferType != TransferType.WAREHOUSE_TO_WAREHOUSE) {
            return;
        }
        if (!factoryId.equals(request.getTargetFactoryId())) {
            throw new BusinessException(400, "仓库间调拨必须在同一工厂内流转")
                    .withHint("请选择当前工厂作为调入方，跨工厂调拨请使用总部/分部调拨类型");
        }
        String sourceWarehouseId = trimToNull(request.getSourceWarehouseId());
        String targetWarehouseId = trimToNull(request.getTargetWarehouseId());
        if (sourceWarehouseId == null) {
            throw new BusinessException(400, "仓库间调拨必须选择调出仓库")
                    .withHint("请先选择实际发货的源仓库");
        }
        if (targetWarehouseId == null) {
            throw new BusinessException(400, "仓库间调拨必须选择调入仓库")
                    .withHint("请先选择实际收货的目标仓库");
        }
        if (sourceWarehouseId.equals(targetWarehouseId)) {
            throw new BusinessException(400, "调出仓库和调入仓库不能相同")
                    .withHint("请选择两个不同仓库，避免生成无意义的内部搬库单");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * M08: 创建草稿前锁定 item identity + 源仓可用量契约。
     *
     * <p>FINISHED_GOODS 必须提交 productTypeId，原料/包材必须提交 materialTypeId；
     * 仓库间调拨还会按所选 sourceWarehouseId 做一次只读库存快照校验。SHIP 阶段仍会在
     * 同一事务内重新校验并扣减，因此这里是 fail-fast UX gate，不取代最终原子门禁。
     */
    private void validateAndNormalizeCreateItems(String factoryId, CreateTransferRequest request,
                                                 TransferType transferType) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException(400, "调拨行项目不能为空")
                    .withHint("请至少添加一行要调拨的原料、包材或成品");
        }
        assertNoDuplicateItemRows(request.getItems());
        String sourceWarehouseId = trimToNull(request.getSourceWarehouseId());
        for (CreateTransferRequest.TransferItemDTO item : request.getItems()) {
            TransferItemType itemType;
            try {
                itemType = TransferItemType.valueOf(item.getItemType());
            } catch (RuntimeException ex) {
                throw new BusinessException(400, "不支持的调拨物品类型: " + item.getItemType())
                        .withHint("请选择原料/食材、包材或成品/菜品");
            }

            String canonicalUnit = canonicalTransferUnit(factoryId, item.getUnit());
            item.setUnit(canonicalUnit);
            if (itemType == TransferItemType.FINISHED_GOODS) {
                String productTypeId = trimToNull(item.getProductTypeId());
                if (productTypeId == null) {
                    throw new BusinessException(400, "成品调拨必须提交 productTypeId")
                            .withHint("请重新选择成品/菜品；不要使用原辅料/包材的 materialTypeId 契约");
                }
                if (transferType == TransferType.WAREHOUSE_TO_WAREHOUSE) {
                    BigDecimal available = finishedGoodsBatchRepository
                            .findAvailableBatchesByWarehouse(factoryId, productTypeId, sourceWarehouseId)
                            .stream()
                            .filter(batch -> canonicalUnit.equals(canonicalTransferUnit(factoryId, batch.getUnit())))
                            .map(FinishedGoodsBatch::getAvailableQuantity)
                            .filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    ensureCreateQuantityAvailable(item, available, "成品", productTypeId);
                }
            } else {
                String materialTypeId = trimToNull(item.getMaterialTypeId());
                if (materialTypeId == null) {
                    throw new BusinessException(400, "原料/包材调拨必须提交 materialTypeId")
                            .withHint("请重新选择与当前类型匹配的物料");
                }
                normalizeRawTransferPackaging(factoryId, item, materialTypeId);
                String rawInventoryUnit = item.getUnit();
                if (transferType == TransferType.WAREHOUSE_TO_WAREHOUSE) {
                    BigDecimal available = materialBatchRepository
                            .findAvailableBatchesFEFOByWarehouse(factoryId, materialTypeId, sourceWarehouseId)
                            .stream()
                            .filter(batch -> rawInventoryUnit.equals(canonicalTransferUnit(factoryId, batch.getQuantityUnit())))
                            .map(batch -> {
                                BigDecimal receipt = batch.getReceiptQuantity() != null
                                        ? batch.getReceiptQuantity() : BigDecimal.ZERO;
                                BigDecimal used = batch.getUsedQuantity() != null
                                        ? batch.getUsedQuantity() : BigDecimal.ZERO;
                                BigDecimal reserved = batch.getReservedQuantity() != null
                                        ? batch.getReservedQuantity() : BigDecimal.ZERO;
                                return receipt.subtract(used).subtract(reserved).max(BigDecimal.ZERO);
                            })
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    ensureCreateQuantityAvailable(item, available,
                            itemType == TransferItemType.PACKAGING_MATERIAL ? "包材" : "原料", materialTypeId);
                }
            }
        }
    }

    /**
     * 同一物料在一张调拨单里只允许一行 (原料/包材按 materialTypeId, 成品按 productTypeId)。
     *
     * <p><b>为什么建单就拒, 而不是在下游放宽</b> (2026-08-09 六膳门 prod 事故):
     * {@code TRF-20260809-1790} 把「金蒜牛排调味料 滚揉用」写成两行各 1000kg, 而主仓该原料
     * 只有 1000kg。建单表单、{@link #ensureCreateQuantityAvailable}、调拨详情页的
     * {@code isStockShortage} 三处都是<b>按行</b>比可用量 —— 每行 1000 ≤ 1000 全部合法,
     * 合计 2000 无人过问。直到审批通过后点「确认调拨入库」,
     * {@link #deductSourceInventory} 第一行扣光批次、第二行 FEFO 查不到批次, 才抛
     * "原料库存不足 … 缺少 1000"; 而此时明细已不可编辑 (只有 DRAFT 能改数量), 用户只能取消重建。
     *
     * <p>禁止重复行之后, "逐行需求" 与 "该物料在本单的合计需求" 恒等, 三处闸的口径差异从根上
     * 消失 —— 好过在三个地方各写一份聚合逻辑 (三份 = 三个各自漂移的入口)。
     */
    private void assertNoDuplicateItemRows(List<CreateTransferRequest.TransferItemDTO> items) {
        Map<String, List<CreateTransferRequest.TransferItemDTO>> rowsByKey = new LinkedHashMap<>();
        for (CreateTransferRequest.TransferItemDTO item : items) {
            boolean finishedGoods = TransferItemType.FINISHED_GOODS.name()
                    .equals(trimToNull(item.getItemType()));
            String id = finishedGoods ? trimToNull(item.getProductTypeId()) : trimToNull(item.getMaterialTypeId());
            // identity 缺失 (没选物料) 交给后面的逐行校验报更准确的错, 这里不抢答。
            if (id == null) continue;
            rowsByKey.computeIfAbsent((finishedGoods ? "P:" : "M:") + id, k -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<String, List<CreateTransferRequest.TransferItemDTO>> entry : rowsByKey.entrySet()) {
            List<CreateTransferRequest.TransferItemDTO> rows = entry.getValue();
            if (rows.size() < 2) continue;
            CreateTransferRequest.TransferItemDTO first = rows.get(0);
            String name = trimToNull(first.getItemName()) != null
                    ? first.getItemName() : entry.getKey().substring(2);
            throw new BusinessException(400, String.format(
                    "「%s」在本单里重复出现 %d 行%s，同一物料请合并成一行",
                    name, rows.size(), describeDuplicateTotal(rows)))
                    .withCode("TRANSFER_DUPLICATE_ITEM_ROWS")
                    .withHint("请删除多余的行, 把数量合并写在一行里 —— 分成多行时每行都会各自去扣同一批库存, "
                            + "看着每行都够, 审批通过后确认入库才会失败");
        }
    }

    /**
     * 重复行的合计量 —— 仅当各行单位一致时才敢相加 (同一原料可能一行按箱、一行按 kg,
     * 包装换算要等 {@link #normalizeRawTransferPackaging} 之后才成立, 此处尚未发生)。
     * 单位不一致时只报行数, 不编一个把箱和 kg 加在一起的假数。
     */
    private String describeDuplicateTotal(List<CreateTransferRequest.TransferItemDTO> rows) {
        String unit = trimToNull(rows.get(0).getUnit());
        BigDecimal total = BigDecimal.ZERO;
        for (CreateTransferRequest.TransferItemDTO row : rows) {
            if (row.getQuantity() == null || !Objects.equals(unit, trimToNull(row.getUnit()))) {
                return "";
            }
            total = total.add(row.getQuantity());
        }
        return String.format(" (合计 %s %s)", total.stripTrailingZeros().toPlainString(), unit);
    }

    private void ensureCreateQuantityAvailable(CreateTransferRequest.TransferItemDTO item,
                                               BigDecimal available, String label, String id) {
        BigDecimal requested = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
        if (requested.signum() <= 0) {
            throw new BusinessException(400, "调拨数量必须大于0")
                    .withHint("请填写有效的调拨数量");
        }
        if (available.compareTo(requested) < 0) {
            throw new BusinessException(409, String.format(
                    "%s源仓库存不足: %s 可用 %s %s, 申请调拨 %s %s",
                    label, id, available.stripTrailingZeros().toPlainString(), item.getUnit(),
                    requested.stripTrailingZeros().toPlainString(), item.getUnit()))
                    .withHint("请减少调拨数量或选择有库存的调出仓库");
        }
    }

    /**
     * 单位归一 —— 口径必须与报工侧 {@code ProductionStockAllocationServiceImpl#canonicalNativeUnit}
     * 完全一致, 否则同一批货在两边算成两个单位。
     *
     * <p><b>只对质量/体积归一。</b> #1976 (2026-07-29)「等价码只对科学单位成立, 计数/包装单位按
     * 字面比较」已在报工侧确立此口径: 只/件/个/袋/盒/箱 之间没有普适换算, 一只不等于一件,
     * 给它们编共同等价码等于让系统替工厂断定"两个不同的东西是同一个东西"。
     *
     * <p>调拨此前漏跟这条决定, 仍把 {@code normalize().code()} 一路用到计数单位上 ——
     * 契约别名表 {@code alias("pcs","pcs","件","个","只")} 于是把用户选的「只」写成 {@code pcs}
     * 存进调拨明细, 再由 createTargetInventory 写进目标批次。
     *
     * <p>线上后果 (LIUSHANMEN 2026-07-30): 生产仓 TRF-MT-20260730-2631 存着 501 {@code pcs},
     * 物料主档与其余批次都是「只」。库存页因本地化显示成「501 只」看不出异常, 报工分摊却按字面
     * 比较 —— {@code "只".equals("pcs")} 为 false, 整批被跳过 → 「需要 1只, 可用 0只, 缺少 1只,
     * 请联系仓管补料」。仓管看着有货, 报工的人说没货, 谁也说服不了谁。
     */
    /**
     * <p>⛔ 2026-08-03: 本轮曾把上面的 {@code filter(MASS|VOLUME)} 去掉, 想让「盒」归一成 {@code box}
     * 以治 F006 包材「可用 0」(见 docs/dispatch/2026-08-02-f006-minloop-issues.md P0-1) —— <b>已撤回</b>。
     * 两条理由:
     * <ul>
     *   <li>去掉 filter 等于同时放开 {@code alias("pcs","pcs","件","个","只")}, 把「只」写回 {@code pcs}
     *       —— 正是 LIUSHANMEN 2026-07-30 那起事故本身。上面那段注释记的就是它。</li>
     *   <li>报工侧 {@code canonicalNativeUnit} 的 filter <b>没有一起改</b>。两侧口径必须一致,
     *       只改一侧是把一处口径打架换成另一处, 不是修好。</li>
     * </ul>
     *
     * <p>P0-1 的真根因在<b>存储</b>: 同一物料档案存 {@code box} 而批次存「盒」。该修的是写入路径 +
     * 一次性数据归一, 且依赖 Steve 尚未拍板的「单位存以码还是以中文」口径 —— 定了再动。
     */
    /**
     * 两个单位写法是不是**同一个单位** —— 只用于比较, 绝不用于落库。
     *
     * <p>🔴 2026-08-14 生产实测: 清空后的 F006 上走真实流程, 调拨 15kg 冻猪蹄被
     * {@code 409 调拨包装规格与原料基本单位不一致} 挡死。抓到的真实 payload
     * {@code {"unit":"case","materialPackagingSpecId":"745b1332-…"}}, 而库里该规格
     * {@code package_unit = 箱} —— 前端 {@code toTransferItemPayload} 会把 {@code row.unit}
     * 过一遍 {@code canonicalUnitCode}(箱 → case)再提交, 后端却拿它跟「箱」字面比。
     * 库里两种写法**同时存在**(活跃原料包装规格实测 {@code case} 29 条 / {@code 箱} 7 条,
     * 后者全是默认包装), 所以这不是脏数据, 是两套词汇表。
     *
     * <p>⚠️ 这不是新问题: {@code web-admin/src/utils/unitPricing.ts} 注释写着
     * 「2026-07-31 客户就是这么被拦住的」—— 当时只在前端补了 {@code sameUnit()},
     * 后端这侧的字面比较原样留着。
     *
     * <h3>⛔ 为什么不能直接放开 {@link #canonicalTransferUnit}</h3>
     *
     * 那个方法的结果**会被写进库**({@code setPackageUnitSnapshot} / 目标批次单位)。
     * 放开它 = 把「只」写成 {@code pcs} 存进批次, 正是
     * {@code TransferUnitCanonicalizationTest} 钉住的 LIUSHANMEN 2026-07-30 事故:
     * 生产仓批次成了 {@code pcs}、物料主档是「只」, 报工按字面比较跳过整批 501 只,
     * 页面还本地化显示成「501 只」, 肉眼看不出异常。#1976 据此确立
     * 「等价码只对科学单位成立, 计数/包装单位按字面比较」。
     *
     * ⇒ 所以拆成两件事: **比较**用本方法(认中英同义), **落库**仍走
     * {@code canonicalTransferUnit} 的字面值, 且命中规格后一律改用**主数据的写法**。
     *
     * <p>语义来自既有的 {@code storageUnit} 规则: {@code 箱 ≡ case}(单一中文写法 → 归一到码),
     * 而 {@code 只 / 个 / 件} 命中「同码多中文写法」规则 2 各自保留字面 → 仍判不等
     * (一只鸡不是一件包材)。⛔ 不要改用 {@code areEquivalent}, 它只比
     * {@code normalize().code()}, 会把这三个合并。
     */
    private boolean sameTransferUnit(String factoryId, String left, String right) {
        if (java.util.Objects.equals(left, right)) return true;
        if (left == null || right == null || unitContractService == null) return false;
        String l = unitContractService.storageUnit(factoryId, left);
        String r = unitContractService.storageUnit(factoryId, right);
        return l != null && !l.isBlank() && l.equals(r);
    }

    private String canonicalTransferUnit(String factoryId, String unit) {
        String value = trimToNull(unit);
        if (value == null) return "";
        if (unitContractService == null) return value;
        return unitContractService.describe(factoryId, value)
                .filter(canonical -> canonical.dimension() == com.cretas.aims.service.unit.UnitDimension.MASS
                        || canonical.dimension() == com.cretas.aims.service.unit.UnitDimension.VOLUME)
                .map(com.cretas.aims.service.unit.CanonicalUnit::code)
                .orElseGet(() -> value.toLowerCase(java.util.Locale.ROOT));
    }

    private void normalizeRawTransferPackaging(
            String factoryId, CreateTransferRequest.TransferItemDTO item, String materialTypeId) {
        RawMaterialType material = rawMaterialTypeRepository.findById(materialTypeId)
                .orElseThrow(() -> new BusinessException(404, "调拨原料不存在: " + materialTypeId));
        if (!factoryId.equals(material.getFactoryId())) {
            throw new BusinessException(403, "调拨原料不属于当前工厂");
        }
        String baseUnit = canonicalTransferUnit(factoryId, material.getUnit());
        String transactionUnit = canonicalTransferUnit(factoryId, item.getUnit());
        BigDecimal factor = BigDecimal.ONE;
        String specId = trimToNull(item.getMaterialPackagingSpecId());

        if (Boolean.TRUE.equals(material.getIsAbacaPackaging()) && !transactionUnit.equals(baseUnit)) {
            throw new BusinessException(422, "抄码原料调拨必须按实际称重基本单位")
                    .withCode("ABACA_TRANSFER_BASE_UNIT_REQUIRED")
                    .withHint("请改用 " + baseUnit + " 输入实际重量");
        }
        if (specId != null) {
            if (materialPackagingSpecRepository == null) {
                throw new BusinessException(503, "原料包装规格服务不可用")
                        .withHint("请稍后重试；系统未执行库存扣减");
            }
            var spec = materialPackagingSpecRepository
                    .findByIdAndFactoryIdAndMaterialTypeIdAndActiveTrue(specId, factoryId, materialTypeId)
                    .orElseThrow(() -> new BusinessException(400, "调拨包装规格不存在、已停用或不属于当前原料")
                            .withCode("TRANSFER_MATERIAL_PACKAGING_SPEC_MISMATCH")
                            .withHintTarget("materialPackagingSpecId"));
            String specUnit = canonicalTransferUnit(factoryId, spec.getPackageUnit());
            String specBase = canonicalTransferUnit(factoryId, spec.getBaseUnit());
            if (!sameTransferUnit(factoryId, transactionUnit, specUnit)
                    || !sameTransferUnit(factoryId, baseUnit, specBase)) {
                throw new BusinessException(409, "调拨包装规格与原料基本单位不一致")
                        .withHint("请返回原料类型修正包装换算");
            }
            // 🔴 比较认中英同义, 但**落库一律采用主数据里的写法**。
            // 客户端送来的可能是英文码(前端 canonicalUnitCode 会把「箱」转成 case),
            // 直接存它会让快照与主档写法不一致 —— 那正是 LIUSHANMEN 2026-07-30 事故的形状
            // (调拨把「只」存成 pcs, 报工按字面比较跳过整批 501 只)。
            transactionUnit = specUnit;
            baseUnit = specBase;
            factor = spec.getConversionFactor();
        } else if (!transactionUnit.equals(baseUnit)) {
            if (materialPackagingSpecRepository == null) {
                throw new BusinessException(422, "调拨包装单位必须选择具体规格")
                        .withHintTarget("materialPackagingSpecId");
            }
            // transactionUnit 在本方法里会被重新绑定成主数据写法, 已非 effectively-final,
            // lambda 里必须用一个当下的快照。
            final String requestedUnit = transactionUnit;
            List<com.cretas.aims.entity.material.MaterialPackagingSpec> matches =
                    materialPackagingSpecRepository
                            .findByFactoryIdAndMaterialTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                                    factoryId, materialTypeId).stream()
                            .filter(spec -> sameTransferUnit(factoryId, requestedUnit,
                                    canonicalTransferUnit(factoryId, spec.getPackageUnit())))
                            .toList();
            if (matches.size() != 1) {
                throw new BusinessException(422, "调拨包装单位必须选择具体规格")
                        .withCode("TRANSFER_MATERIAL_PACKAGING_SPEC_REQUIRED")
                        .withHintTarget("materialPackagingSpecId");
            }
            var matched = matches.get(0);
            specId = matched.getId();
            // 同上: 命中规格后, 快照采用主数据的写法, 不采用客户端送来的写法。
            transactionUnit = canonicalTransferUnit(factoryId, matched.getPackageUnit());
            factor = matched.getConversionFactor();
        }
        if (factor == null || factor.signum() <= 0) {
            throw new BusinessException(409, "调拨包装换算数无效")
                    .withHint("请先修正原料包装规格");
        }

        BigDecimal packageQuantity = item.getQuantity();
        item.setMaterialPackagingSpecId(specId);
        item.setPackageQuantitySnapshot(packageQuantity);
        item.setPackageUnitSnapshot(transactionUnit);
        item.setInventoryBaseUnitSnapshot(baseUnit);
        item.setPackageToBaseFactorSnapshot(factor);
        item.setQuantity(packageQuantity.multiply(factor));
        item.setUnit(baseUnit);
        if (item.getUnitPrice() != null && factor.compareTo(BigDecimal.ONE) != 0) {
            item.setUnitPrice(item.getUnitPrice().divide(
                    factor, 12, java.math.RoundingMode.HALF_UP));
        }
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

    /** 调拨出库的消耗流水来源标识。列宽 varchar(20), 这里 12 字符。 */
    private static final String SOURCE_TRANSFER_OUT = "TRANSFER_OUT";

    /**
     * 为调拨出库扣减的**每一个**批次写一条消耗流水。
     *
     * <p>🔴 2026-08-14 生产实测: 调拨扣减此前完全不写流水。三张流水表(material_consumptions /
     * material_batch_adjustments / production_settlement_consumptions)对这些扣减一无所知,
     * 100 个活跃批次因此"库存少了但说不出去哪了", 且本周仍在新增。
     *
     * <p>⚠️ 必须逐批次写: FEFO 一次可能扣 3 个批次, 而 {@code item.sourceBatchId} 是单值列,
     * 只留得住第一个。流水是唯一能表达多批次扣减的载体。
     *
     * <p>⚠️ {@code materialConsumptionRepository} 声明为 {@code @Autowired(required = false)}
     * (为兼容既有 7 参数构造器单测)。为 null 时**必须留下 WARN** —— 静默跳过就等于把这个洞
     * 原样保留, 而且下次没人看得出来。
     */
    private void recordTransferConsumption(String factoryId, MaterialBatch batch, BigDecimal deduct,
                                           InternalTransferItem item, Long recordedBy, String transferNumber) {
        if (materialConsumptionRepository == null) {
            log.warn("调拨出库未写消耗流水(materialConsumptionRepository 未注入): batchId={}, deduct={}, transfer={}",
                    batch.getId(), deduct, transferNumber);
            return;
        }
        BigDecimal unitPrice = batch.getUnitPrice() != null ? batch.getUnitPrice() : BigDecimal.ZERO;
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setFactoryId(factoryId);
        consumption.setBatchId(batch.getId());
        consumption.setMaterialTypeId(item.getMaterialTypeId());
        consumption.setQuantity(deduct);
        consumption.setUnitPrice(unitPrice);
        consumption.setTotalCost(deduct.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP));
        consumption.setConsumptionTime(LocalDateTime.now());
        consumption.setConsumedAt(LocalDateTime.now());
        consumption.setRecordedBy(recordedBy != null ? recordedBy : 0L);
        consumption.setSourceType(SOURCE_TRANSFER_OUT);
        consumption.setNotes("调拨出库 " + (transferNumber != null ? transferNumber : ""));
        materialConsumptionRepository.save(consumption);
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
        Optional<ApprovalWorkflowInstance> existing = workflowEngine == null
                ? Optional.empty()
                : workflowEngine.getLatestInstance(
                        factoryId, "INVENTORY_TRANSFER", transferId);
        if (transfer.getStatus() == TransferStatus.REQUESTED
                && existing.filter(instance -> instance.getStatus() == InstanceStatus.RUNNING).isPresent()) {
            return transfer;
        }
        if (transfer.getStatus() == TransferStatus.APPROVED && existing.isEmpty()) {
            return transfer;
        }
        if (transfer.getStatus() != TransferStatus.DRAFT) {
            if (transfer.getStatus() == TransferStatus.REQUESTED && existing.isEmpty()) {
                throw new BusinessException(409, "调拨单已提交但缺少 OA 审批实例")
                        .withCode("TRANSFER_APPROVAL_INSTANCE_MISSING")
                        .withHint("禁止在调拨详情重复审批，请联系管理员核对统一 OA 实例");
            }
            throw new BusinessException(409, "只有草稿状态的调拨单可以提交审批")
                    .withCode("TRANSFER_SUBMIT_STATUS_INVALID");
        }
        if (workflowEngine == null) {
            throw new BusinessException(503, "审批运行时暂不可用")
                    .withCode("OA_RUNTIME_UNAVAILABLE");
        }

        Optional<ApprovalWorkflowInstance> instance = workflowEngine.startWorkflowIfConfigured(
                factoryId, "INVENTORY_TRANSFER", transferId,
                buildTransferWorkflowContext(transfer), userId);
        transfer.setRequestedBy(userId);
        transfer.setRequestedAt(LocalDateTime.now());
        if (instance.isEmpty()) {
            transfer.setStatus(TransferStatus.APPROVED);
            transfer.setApprovedBy(userId);
            transfer.setApprovedAt(LocalDateTime.now());
            log.info("调拨单无需审批，直接批准: transferId={}, transferNumber={}",
                    transferId, transfer.getTransferNumber());
            return transferRepository.save(transfer);
        }
        validateRunnableApprovalRoute(factoryId, instance.get(), userId);
        projectWorkflowState(transfer, instance.get(), userId, null);
        log.info("提交调拨申请并启动 OA: transferId={}, transferNumber={}, instanceId={}, workflowStatus={}",
                transferId, transfer.getTransferNumber(), instance.get().getId(), instance.get().getStatus());
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
        InternalTransfer saved = transferRepository.save(transfer);
        publishTerminated(saved, TransferStatus.REJECTED, reason);
        return saved;
    }

    @Override
    @Transactional
    public InternalTransfer applyWorkflowAction(String factoryId,
                                                String transferId,
                                                String instanceId,
                                                Long actorId,
                                                String actorRole,
                                                HistoryAction action,
                                                String notes) {
        InternalTransfer transfer = loadForStateChange(factoryId, transferId);
        assertSourceFactory(factoryId, transfer, "OA 审批");
        if (workflowEngine == null) {
            throw new BusinessException(503, "OA 审批服务不可用")
                    .withCode("TRANSFER_APPROVAL_SERVICE_UNAVAILABLE");
        }
        ApprovalWorkflowInstance instance = workflowEngine.getInstance(factoryId, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("OA 审批实例不存在: " + instanceId));
        if (!"INVENTORY_TRANSFER".equals(instance.getModuleCode())
                || !transferId.equals(instance.getBusinessEntityId())) {
            throw new BusinessException(400, "审批实例与调拨单不匹配")
                    .withCode("TRANSFER_APPROVAL_IDENTITY_MISMATCH");
        }
        if (instance.getStatus() != InstanceStatus.RUNNING) {
            return transfer;
        }
        if (actorId != null && actorId.equals(instance.getInitiatedBy())
                && (selfApprovalPolicy == null
                    || !selfApprovalPolicy.allowsSelfApproval(factoryId, instance, actorId, actorRole))) {
            throw new BusinessException(403, "发起人不能审批自己的调拨单")
                    .withCode("TRANSFER_SELF_APPROVAL_FORBIDDEN")
                    .withHint("请由当前 OA 节点授权的其他审批人处理，或在 Canvas 中明确将发起人配置为该节点审批人");
        }
        if (actorId != null && actorId.equals(instance.getInitiatedBy())) {
            // 自审批留痕: 少了第二双眼睛, 至少要在日志里留下"谁自己批了自己"
            log.warn("工厂总监自审批调拨单: factoryId={}, transferId={}, actorId={}, action={}",
                    factoryId, transferId, actorId, action);
        }
        if (action == HistoryAction.REJECT && (notes == null || notes.isBlank())) {
            throw new BusinessException(422, "驳回调拨单必须填写原因")
                    .withCode("TRANSFER_REJECT_REASON_REQUIRED")
                    .withHintTarget("notes");
        }
        ApprovalWorkflowInstance updated = workflowEngine.transitionNode(
                instanceId, actorId, actorRole, action, notes);
        projectWorkflowState(transfer, updated, actorId, notes);
        return transferRepository.save(transfer);
    }

    private Map<String, Object> buildTransferWorkflowContext(InternalTransfer transfer) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("transferId", transfer.getId());
        context.put("transferNumber", transfer.getTransferNumber());
        context.put("transferType", transfer.getTransferType() == null
                ? null : transfer.getTransferType().name());
        context.put("sourceFactoryId", transfer.getSourceFactoryId());
        context.put("targetFactoryId", transfer.getTargetFactoryId());
        context.put("sourceWarehouseId", transfer.getSourceWarehouseId());
        context.put("targetWarehouseId", transfer.getTargetWarehouseId());
        context.put("amount", transfer.getTotalAmount() == null
                ? BigDecimal.ZERO : transfer.getTotalAmount());
        context.put("totalAmount", transfer.getTotalAmount() == null
                ? BigDecimal.ZERO : transfer.getTotalAmount());
        context.put("itemCount", transfer.getItems() == null ? 0 : transfer.getItems().size());
        return context;
    }

    private void validateRunnableApprovalRoute(String factoryId,
                                               ApprovalWorkflowInstance instance,
                                               Long initiatorUserId) {
        if (instance.getStatus() != InstanceStatus.RUNNING) return;
        if (instance.getCurrentNodeIds() == null || instance.getCurrentNodeIds().isEmpty()) {
            throw new BusinessException(422, "库存调拨 OA 流程没有可处理的当前节点")
                    .withCode("TRANSFER_APPROVAL_NODE_REQUIRED");
        }
        if (approvalWorkflowService == null || userRepository == null) {
            throw new BusinessException(503, "库存调拨 OA 路由解析服务不可用")
                    .withCode("TRANSFER_APPROVAL_ROUTE_SERVICE_UNAVAILABLE");
        }
        ApprovalWorkflow workflow = approvalWorkflowService
                .getById(factoryId, instance.getWorkflowId())
                .orElseThrow(() -> new BusinessException(422, "库存调拨 OA 流程定义不存在")
                        .withCode("TRANSFER_APPROVAL_DEFINITION_MISSING"));
        Map<String, ApprovalWorkflowNode> nodes = approvalWorkflowService
                .deserializeNodes(workflow.getNodesJson()).stream()
                .collect(Collectors.toMap(ApprovalWorkflowNode::getId, node -> node));
        for (String nodeId : instance.getCurrentNodeIds()) {
            ApprovalWorkflowNode node = nodes.get(nodeId);
            if (node == null || !"approval".equalsIgnoreCase(node.getType())) {
                throw new BusinessException(422, "库存调拨 OA 当前节点不可审批: " + nodeId)
                        .withCode("TRANSFER_APPROVAL_NODE_INVALID");
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
                throw new BusinessException(422, "库存调拨 OA 节点未配置审批角色: "
                        + (node.getLabel() == null ? nodeId : node.getLabel()))
                        .withCode("TRANSFER_APPROVER_ROLE_REQUIRED");
            }
            boolean hasIndependentAssignee = roles.stream()
                    .flatMap(role -> userRepository.findByFactoryIdAndRoleCode(factoryId, role).stream())
                    .anyMatch(user -> Boolean.TRUE.equals(user.getIsActive())
                            && !Objects.equals(user.getId(), initiatorUserId));
            if (!hasIndependentAssignee
                    && !allowsSuperAdminSelfApproval(factoryId, roles, initiatorUserId)) {
                throw new BusinessException(422, "库存调拨 OA 节点没有独立的可用审批人: "
                        + (node.getLabel() == null ? nodeId : node.getLabel()))
                        .withCode("TRANSFER_APPROVER_ASSIGNEE_REQUIRED")
                        .withHint("请为当前工厂配置匹配审批角色的其他有效账号；调拨单仍保持草稿");
            }
        }
    }

    /**
     * 工厂总监自审批例外。
     *
     * 职责分离(发起人 != 审批人)是默认铁律, 但小工厂常常只有一个 factory_super_admin ——
     * 审批节点又只认这个角色时, 他发起的调拨永远批不掉, 调拨单会永久卡在草稿。实测某工厂
     * 因此一个多月没走通过一次调拨。
     *
     * 例外只对 factory_super_admin 开, 且必须满足两个条件:
     *   1. 该节点的 approverRoles 里确实包含 factory_super_admin —— 否则超管就能越过
     *      "本该由仓管批"的节点, 那是绕过审批而不是解死锁;
     *   2. 发起人本人就是这个工厂的 factory_super_admin。
     *
     * 其它角色一律维持"必须有第二双眼睛"。
     */
    private boolean allowsSuperAdminSelfApproval(String factoryId,
                                                 List<String> approverRoles,
                                                 Long initiatorUserId) {
        if (initiatorUserId == null || userRepository == null) return false;
        if (approverRoles.stream().noneMatch(SUPER_ADMIN_ROLE::equalsIgnoreCase)) return false;
        return userRepository.findByFactoryIdAndRoleCode(factoryId, SUPER_ADMIN_ROLE).stream()
                .anyMatch(user -> Boolean.TRUE.equals(user.getIsActive())
                        && Objects.equals(user.getId(), initiatorUserId));
    }

    private void projectWorkflowState(InternalTransfer transfer,
                                      ApprovalWorkflowInstance instance,
                                      Long actorId,
                                      String notes) {
        switch (instance.getStatus()) {
            case RUNNING -> transfer.setStatus(TransferStatus.REQUESTED);
            case APPROVED -> {
                transfer.setStatus(TransferStatus.APPROVED);
                transfer.setApprovedBy(actorId);
                transfer.setApprovedAt(LocalDateTime.now());
                transfer.setRejectReason(null);
            }
            case REJECTED, CANCELLED, TIMEOUT -> {
                transfer.setStatus(TransferStatus.REJECTED);
                transfer.setApprovedBy(actorId);
                transfer.setApprovedAt(LocalDateTime.now());
                transfer.setRejectReason(notes);
                // OA 驳回/撤销/超时同样是终止 —— 凭证在创建时就生成了, 这条路不发事件
                // 就会留下与 cancelTransfer 一模一样的悬空凭证。
                publishTerminated(transfer, TransferStatus.REJECTED, notes);
            }
        }
    }

    @Override
    @Transactional
    public InternalTransfer shipTransfer(String factoryId, String transferId, Long userId) {
        InternalTransfer transfer = loadForStateChange(factoryId, transferId);
        // 只有调出方可以发货 (因为要扣减调出方库存)
        assertSourceFactory(factoryId, transfer, "发货");
        assertCrossFactoryShipment(transfer, "发运");
        assertStatus(transfer, TransferStatus.APPROVED, "发货");

        // D1: warehouse strategy per PR #310 §5 — 调拨发货扣减按 source warehouse 过滤.
        String sourceWarehouseId = transfer.getSourceWarehouseId();
        // MES↔ERP Fix #4: 同厂调拨 (源工厂==目标工厂, e.g. 生产仓→物流仓 内部搬托) 不是销售/发货,
        //   成品扣减不得动 shippedQuantity (销售口径), 否则内部搬库虚增销售/COGS + Σproduced 膨胀。
        boolean intraFactory = Objects.equals(
                transfer.getSourceFactoryId(), transfer.getTargetFactoryId());
        for (InternalTransferItem item : transfer.getItems()) {
            deductSourceInventory(transfer.getSourceFactoryId(), sourceWarehouseId, item, intraFactory,
                    userId, transfer.getTransferNumber());
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
        assertCrossFactoryShipment(transfer, "签收");
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
        boolean intraFactory = isIntraFactory(transfer);
        assertStatus(transfer, intraFactory ? TransferStatus.APPROVED : TransferStatus.RECEIVED, "确认");

        LocalDateTime now = LocalDateTime.now();
        if (intraFactory) {
            // Same-factory warehouse moves have one responsible warehouse role.  Do
            // not manufacture a sales-like ship/receive lifecycle: confirmation
            // atomically moves the source quantity and creates the target batch.
            for (InternalTransferItem item : transfer.getItems()) {
                deductSourceInventory(transfer.getSourceFactoryId(), transfer.getSourceWarehouseId(), item, true,
                        userId, transfer.getTransferNumber());
                item.setReceivedQuantity(item.getQuantity());
            }
            if (!transfer.getItems().isEmpty()) {
                transferItemRepository.saveAll(transfer.getItems());
            }
            transfer.setShippedAt(now);
            transfer.setReceivedAt(now);
        }

        // D1: warehouse strategy per PR #310 §5 — 调拨确认在 target warehouse 创建批次.
        String targetWarehouseId = transfer.getTargetWarehouseId();
        for (InternalTransferItem item : transfer.getItems()) {
            createTargetInventory(transfer.getTargetFactoryId(), targetWarehouseId, item, userId);
        }

        transfer.setStatus(TransferStatus.CONFIRMED);
        transfer.setConfirmedAt(now);
        log.info("调拨确认: transferId={}, 库存已更新", transferId);
        InternalTransfer saved = transferRepository.save(transfer);
        // 库存真正搬完了才通知凭证侧入账 —— 凭证原先挂在"创建"上, 草稿阶段就记账 (见
        // TransferConfirmedEvent 的说明)。⚠️ 传【调出方】工厂: 跨厂调拨由调入方执行确认,
        // 而凭证一直归属调出方, 传当前 factoryId 会把凭证记到错的厂。
        if (applicationEventPublisher != null) {
            try {
                applicationEventPublisher.publishEvent(new com.cretas.aims.event.TransferConfirmedEvent(
                        this, saved.getSourceFactoryId(), saved.getId()));
            } catch (Exception e) {
                // 发事件失败不该把已经完成的入库翻掉 —— 凭证可由财务补生成 (批量补凭证工具)。
                log.warn("发布调拨确认事件失败: transferId={}, err={}", saved.getId(), e.getMessage());
            }
        }
        return saved;
    }

    @Override
    @Transactional
    public InternalTransferItem updateItemQuantity(String factoryId, String transferId,
                                                    Long itemId, BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "调拨数量必须大于 0")
                    .withHint("请填写大于 0 的可调数量").withHintTarget("quantity");
        }
        InternalTransfer transfer = loadForStateChange(factoryId, transferId);
        assertSourceFactory(factoryId, transfer, "修改调拨数量");
        assertStatus(transfer, TransferStatus.DRAFT, "修改调拨数量");
        InternalTransferItem item = transfer.getItems().stream()
                .filter(candidate -> Objects.equals(candidate.getId(), itemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "调拨明细不存在或不属于该调拨单"));
        item.setQuantity(quantity);
        item.setReceivedQuantity(null);
        InternalTransferItem saved = transferItemRepository.save(item);
        transfer.setTotalAmount(transfer.getItems().stream()
                .map(candidate -> candidate.getQuantity() != null && candidate.getUnitPrice() != null
                        ? candidate.getQuantity().multiply(candidate.getUnitPrice()) : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        transferRepository.save(transfer);
        return saved;
    }

    @Override
    @Transactional
    public int closeOpenTransfersForProductionPlan(String factoryId, String productionPlanId,
                                                    Long userId, String reason) {
        if (factoryId == null || factoryId.isBlank() || productionPlanId == null || productionPlanId.isBlank()) {
            return 0;
        }
        List<InternalTransfer> transfers = transferRepository
                .findBySourceFactoryIdAndProductionPlanIdAndStatusInOrderByCreatedAtDesc(
                        factoryId, productionPlanId,
                        List.of(TransferStatus.DRAFT, TransferStatus.REQUESTED, TransferStatus.APPROVED));
        int closed = 0;
        for (InternalTransfer transfer : transfers) {
            if (transfer.getStatus() == TransferStatus.REQUESTED && workflowEngine != null) {
                workflowEngine.getLatestInstance(factoryId, "INVENTORY_TRANSFER", transfer.getId())
                        .filter(instance -> instance.getStatus()
                                == com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus.RUNNING)
                        .ifPresent(instance -> workflowEngine.cancel(instance.getId(), userId, reason));
            }
            transfer.setStatus(TransferStatus.CANCELLED);
            transfer.setRejectReason(reason);
            transferRepository.save(transfer);
            // 生产计划取消时批量关单也是终止 —— 同样要回收凭证。
            publishTerminated(transfer, TransferStatus.CANCELLED, reason);
            closed++;
        }
        return closed;
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
        InternalTransfer saved = transferRepository.save(transfer);
        publishTerminated(saved, TransferStatus.CANCELLED, reason);
        return saved;
    }

    /**
     * 终止 (取消/驳回) 后通知凭证侧回收 —— INVENTORY_TRANSFER 凭证在<b>创建</b>时就生成了,
     * 光翻状态会在账上留一张对应不到实物流的凭证 (2026-08-09 六膳门 TRF-20260809-1790 实证:
     * 取消后借贷各 ¥10,000 的 V-2026-0023 仍挂在库里)。
     *
     * <p>只发事件、不在本事务内直接作废: voidVoucher 的内层 @Transactional 抛异常会污染终止主事务
     * (doomed-tx)。理由与销售侧 {@code SalesOrderCancelledEvent} 逐字相同。
     */
    private void publishTerminated(InternalTransfer transfer, TransferStatus terminalStatus, String reason) {
        if (applicationEventPublisher == null) return;
        try {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.TransferTerminatedEvent(
                    this, transfer.getSourceFactoryId(), transfer.getId(),
                    terminalStatus.name(), reason));
        } catch (Exception e) {
            // 发事件失败不该把已经成立的终止翻掉 —— 凭证可由财务手工作废。
            log.warn("发布调拨终止事件失败: transferId={}, status={}, err={}",
                    transfer.getId(), terminalStatus, e.getMessage());
        }
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

    private boolean isIntraFactory(InternalTransfer transfer) {
        return Objects.equals(transfer.getSourceFactoryId(), transfer.getTargetFactoryId());
    }

    private void assertCrossFactoryShipment(InternalTransfer transfer, String action) {
        if (isIntraFactory(transfer)) {
            throw new BusinessException(409, "同厂仓间调拨不需要" + action + "，请在审批通过后直接确认调拨")
                    .withCode("TRANSFER_INTRA_FACTORY_CONFIRM_REQUIRED")
                    .withHint("同厂调拨流程为：申请 → OA 批准 → 确认调拨");
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
                                       boolean intraFactory, Long recordedBy, String transferNumber) {
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

            // 🔴 2026-08-15: 扣减判据从「账面可用」改成「货架实物」= 账面 − 未小结报工消耗。
            //
            // 延迟扣减设计下 (ProductionPlanServiceImpl 注释「报工写未结消耗、暂不扣 usedQuantity,
            // 直到小结才逐笔扣」), 报工已经把料投进工序、物理上不在货架上了, 但 usedQuantity 还没动。
            // 只看账面就会放行一笔搬不动的调拨。
            //
            // 真机实测 (F006, 2026-08-15): 生产仓冻猪蹄批次 TRF-MT-20260814-7992 入库 15kg,
            // 报工消耗 10kg 写了流水但未小结 → 报工页显示「生产仓可用 5kg」, 而调拨新建页显示
            // 「可用 15kg」, 且本处与预选校验都会放行 15kg。同一批次同一时刻两个数。
            //
            // 2026-07-05 那次把 physicalShelf 引进来时明确写了「⛔ 不改任何 gate/校验」, 只做提示;
            // 本次经 owner 拍板扩到闸上 —— 提示看得见但拦不住, 等于把责任推给操作员。
            // 复用同一组 helper (loadUnsettledForBatches / physicalShelf), 口径与盘点、
            // 与批次选择器的「货架实物」完全一致, 不另起一套。
            Map<String, BigDecimal> unsettledByBatch = loadUnsettledForBatches(factoryId, batches);
            boolean unsettledBlocked = false;

            BigDecimal remaining = item.getQuantity();
            String firstConsumedBatchId = null;
            for (MaterialBatch batch : batches) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                // 使用乐观锁思路: 读取→计算→保存，@Transactional 保证原子性
                BigDecimal bookAvailable = batch.getReceiptQuantity()
                        .subtract(batch.getUsedQuantity())
                        .subtract(batch.getReservedQuantity() != null ? batch.getReservedQuantity() : BigDecimal.ZERO);
                BigDecimal available = physicalShelf(bookAvailable, unsettledByBatch.get(batch.getId()));
                if (available.compareTo(bookAvailable) < 0) {
                    unsettledBlocked = true;
                }
                if (available.compareTo(BigDecimal.ZERO) <= 0) continue;
                BigDecimal deduct = remaining.min(available);
                batch.setUsedQuantity(batch.getUsedQuantity().add(deduct));
                if (batch.getReceiptQuantity().subtract(batch.getUsedQuantity()).compareTo(BigDecimal.ZERO) <= 0) {
                    batch.setStatus(MaterialBatchStatus.DEPLETED);
                }
                materialBatchRepository.saveAndFlush(batch); // flush 立即写入，减少并发窗口
                // 🔴 2026-08-14: 这里原本只扣库存不留流水。后果实测于生产: 100 个活跃批次
                // used_quantity > 0 而 material_consumptions / 调整流水 / 结算消耗三张表都查无痕迹,
                // 本周仍在新增 —— 对溯源系统就是"答不出这批料去哪了"。
                // ⚠️ 尤其是这个 FEFO 循环会扣【多个】批次, 而下面的 item.setSourceBatchId 只记得住
                // 第一个 —— 第二个以后的批次在全库任何地方都没有记录。逐批次写流水是唯一的补法。
                recordTransferConsumption(factoryId, batch, deduct, item, recordedBy, transferNumber);
                if (firstConsumedBatchId == null) firstConsumedBatchId = batch.getId();
                inventoryLowStockEventPublisher.publishIfLowStock(factoryId, batch, "TRANSFER_OUT");
                remaining = remaining.subtract(deduct);
                log.info("扣减原料批次: batchId={}, deduct={}, remaining={}, preselected={}",
                        batch.getId(), deduct, remaining, preselectedBatchId != null);
            }
            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                // 原因不同, 出路就不同 —— 账面为 0 才该去采购; 被未结报工占住时去采购是白跑一趟,
                // 正确动作是先做生产小结把消耗落账 (或改调较小的量)。不分清楚就是让人做他做不到的事。
                throw new BusinessException(409, String.format(
                    "原料库存不足: %s, 需要 %s, 缺少 %s",
                    item.getMaterialTypeId(), item.getQuantity(), remaining))
                        .withHint(unsettledBlocked
                                ? "该批次已有报工消耗但尚未做生产小结，货架实物少于账面。请先完成生产小结，或按货架实物量调整调拨数量"
                                : "请先采购或从其他工厂调入该原料");
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
            // 与 FEFO 扣减同口径: 判据是「货架实物」不是账面 —— 见那里的 2026-08-15 注释。
            // 用户在选择器里看到的就是 physicalAvailable, 这里必须按同一个数拦, 否则
            // 「看到 5、却能提交 15」——提示与闸各说各话比没有提示更糟。
            BigDecimal bookAvailable = b.getReceiptQuantity()
                    .subtract(b.getUsedQuantity())
                    .subtract(b.getReservedQuantity() != null ? b.getReservedQuantity() : BigDecimal.ZERO);
            BigDecimal available = physicalShelf(
                    bookAvailable, loadUnsettledForBatches(sourceFactoryId, List.of(b)).get(b.getId()));
            if (available.compareTo(item.getQuantity()) < 0) {
                boolean heldByUnsettled = available.compareTo(bookAvailable) < 0;
                throw new BusinessException(409, String.format(
                        "批次可用量不足: 需要 %s, 仅 %s",
                        item.getQuantity(), available))
                        .withHint(heldByUnsettled
                                ? "该批次已有报工消耗但尚未做生产小结，货架实物少于账面。请先完成生产小结，或选择其他批次"
                                : "请选择其他批次或留空走 FEFO");
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
