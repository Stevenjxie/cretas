package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.CreateSemiFinishedStocktakeRequest;
import com.cretas.aims.dto.factory.SemiFinishedStocktakeDiffPreviewDTO;
import com.cretas.aims.dto.factory.SemiFinishedStocktakeDTO;
import com.cretas.aims.dto.factory.SemiFinishedStocktakeItemUpdateDTO;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.factory.SemiFinishedStocktake;
import com.cretas.aims.entity.factory.SemiFinishedStocktakeItem;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.factory.SemiFinishedStocktakeItemRepository;
import com.cretas.aims.repository.factory.SemiFinishedStocktakeRepository;
import com.cretas.aims.service.factory.SemiFinishedStocktakeService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 半成品盘点任务服务实现 (镜像 SP7 {@link FactoryStocktakeServiceImpl})。
 *
 * <p>红线:
 * <ul>
 *   <li>apply() 必须在 APPROVED 状态下执行, 幂等防重 (已 APPLIED → 409)</li>
 *   <li>半成品库存变动写预留的 {@link SemiFinishedInventoryTransaction} ADJUST/STOCKTAKE 流水留痕</li>
 *   <li>SFI 行以 {@code findForUpdate} 悲观行锁 + {@code @Version} 乐观锁双保护 (镜像 WipInventoryServiceImpl)</li>
 *   <li>角色检查通过 requestRole 参数 (非 SecurityContext, C1 孪生坑)</li>
 *   <li>全链 factory-scoped (多租户隔离)</li>
 * </ul>
 *
 * <p><b>数量语义 (delta, 保住凭证+成本不变式)</b>: 生效时在锁定的 SFI 行上应用 delta 修正
 * {@code availableQuantity_new = 当前 availableQuantity + (actualQty − systemQty快照)} (mirrors SP7),
 * 保留 count 与 apply 之间的并发产出/领用。修正只累加进独立列 {@code adjustmentQuantity}
 * (使 {@code produced − consumed + adjustment == availableQuantity_new}), <b>不动</b>
 * {@code producedQuantity / consumedQuantity / accumulatedCost / unitCost} (它们喂
 * 半成品入库/发出凭证 + unitCost 分母)。写一行 ADJUST 流水 {@code quantity = actualQty − systemQty}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemiFinishedStocktakeServiceImpl implements SemiFinishedStocktakeService {

    private final SemiFinishedStocktakeRepository stocktakeRepo;
    private final SemiFinishedStocktakeItemRepository stocktakeItemRepo;
    private final SemiFinishedInventoryRepository sfiRepo;
    private final SemiFinishedInventoryTransactionRepository sfiTxnRepo;

    /** SP12: optional — 测试时不注入 (required=false 打破构造器注入限制) */
    @Autowired(required = false)
    private WorkflowEngineService workflowEngine;

    /**
     * 月底约束: >=threshold 日才允许发起盘点。
     * prod 默认 29 (月底); test env 可设 1 以跳过限制便于 E2E。
     * 与 SP7 仓库盘点共用同一策略键。
     */
    @Value("${cretas.stocktake.month-end-threshold:29}")
    private int monthEndThreshold;

    // -------------------------------------------------------
    // 审批角色 (镜像 SP7 STOCKTAKE_APPROVAL_ROLES = INVENTORY_ADJUSTMENT 审批角色)
    // -------------------------------------------------------
    private static final java.util.Set<String> STOCKTAKE_APPROVAL_ROLES = java.util.Set.of(
            "finance_manager", "factory_super_admin", "platform_admin");

    /** INVENTORY_ADJUSTMENT workflow moduleCode (与 SP7 完全一致, 张权要求的审批) */
    private static final String WORKFLOW_MODULE_CODE = "INVENTORY_ADJUSTMENT";

    @Override
    @Transactional
    public SemiFinishedStocktakeDTO initiate(String factoryId, CreateSemiFinishedStocktakeRequest req, Long userId) {
        // 月底约束
        LocalDate today = LocalDate.now();
        if (today.getDayOfMonth() < monthEndThreshold) {
            LocalDate nextAllowedDate = today.withDayOfMonth(monthEndThreshold);
            throw new BusinessException(409,
                    "半成品盘点任务只能在月底（" + monthEndThreshold + "日后）发起，当前是 " + today +
                    "，下次可发起日期: " + nextAllowedDate)
                    .withHint("等到 " + monthEndThreshold + " 日再发起");
        }

        // 防重复发起 (同工厂同月份已有未完成盘点)
        long existing = stocktakeRepo.countActiveStocktakeForMonth(factoryId, req.getPeriodMonth());
        if (existing > 0) {
            throw new BusinessException(409,
                    "本月已有进行中的半成品盘点任务，请完成或拒绝后再发起")
                    .withCode("DUPLICATE_STOCKTAKE");
        }

        SemiFinishedStocktake stocktake = new SemiFinishedStocktake();
        stocktake.setFactoryId(factoryId);
        stocktake.setStocktakeNo(generateStocktakeNo());
        stocktake.setPeriodMonth(req.getPeriodMonth());
        stocktake.setStatus(SemiFinishedStocktake.Status.INITIATED);
        stocktake.setInitiatedBy(userId);
        stocktake.setInitiatedAt(LocalDateTime.now());
        stocktake.setNotes(req.getNotes());

        // 快照工厂全部 AVAILABLE 半成品行的当前 availableQuantity
        List<SemiFinishedInventory> rows = sfiRepo.findByFactoryIdAndStatusForStocktake(
                factoryId, SemiFinishedInventory.Status.AVAILABLE);
        List<SemiFinishedStocktakeItem> items = new ArrayList<>();
        for (SemiFinishedInventory sfi : rows) {
            SemiFinishedStocktakeItem item = new SemiFinishedStocktakeItem();
            item.setStocktake(stocktake);
            item.setSemiFinishedId(sfi.getId());
            item.setIntermediateBatchNo(sfi.getIntermediateBatchNo());
            item.setProductTypeId(sfi.getProductTypeId());
            item.setUnit(sfi.getUnit());
            item.setSystemQty(nz(sfi.getAvailableQuantity()).setScale(4, RoundingMode.HALF_UP));
            items.add(item);
        }
        stocktake.setItems(items);

        SemiFinishedStocktake saved = stocktakeRepo.save(stocktake);
        log.info("半成品盘点: 任务已创建 factoryId={} stocktakeNo={} itemCount={}",
                factoryId, saved.getStocktakeNo(), items.size());
        return SemiFinishedStocktakeDTO.from(saved);
    }

    @Override
    @Transactional
    public void updateItems(String stocktakeId, String factoryId,
                            List<SemiFinishedStocktakeItemUpdateDTO> updates, Long userId) {
        SemiFinishedStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        assertNotApplied(stocktake);
        if (stocktake.getStatus() == SemiFinishedStocktake.Status.INITIATED) {
            stocktake.setStatus(SemiFinishedStocktake.Status.COUNTING);
        }

        for (SemiFinishedStocktakeItemUpdateDTO update : updates) {
            SemiFinishedStocktakeItem item = stocktake.getItems().stream()
                    .filter(i -> i.getId().equals(update.getItemId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(404,
                            "盘点明细行不存在: " + update.getItemId()));

            item.setActualQty(update.getActualQty().setScale(4, RoundingMode.HALF_UP));
            if (update.getPhotoUrls() != null) {
                item.setPhotoUrls(update.getPhotoUrls());
            }
            if (update.getNotes() != null) {
                item.setNotes(update.getNotes());
            }
            if (item.getSystemQty() != null && item.getActualQty() != null) {
                BigDecimal diff = item.getActualQty().subtract(item.getSystemQty())
                        .setScale(4, RoundingMode.HALF_UP);
                item.setDifferenceQty(diff);
                int cmp = diff.compareTo(BigDecimal.ZERO);
                if (cmp > 0) {
                    item.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.SURPLUS);
                } else if (cmp < 0) {
                    item.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.SHORTAGE);
                } else {
                    item.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.MATCH);
                }
            }
        }
        stocktakeRepo.save(stocktake);
    }

    @Override
    @Transactional
    public void submit(String stocktakeId, String factoryId, Long userId) {
        SemiFinishedStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        assertSubmittable(stocktake);
        stocktake.setStatus(SemiFinishedStocktake.Status.PENDING_APPROVAL);
        stocktake.setSubmittedBy(userId);
        stocktake.setSubmittedAt(LocalDateTime.now());
        stocktakeRepo.save(stocktake);
        log.info("半成品盘点: 任务已提交审批 stocktakeId={}", stocktakeId);
    }

    @Override
    @Transactional
    public void approve(String stocktakeId, String factoryId, Long approverId, String requestRole) {
        assertApprovalRole(requestRole);
        SemiFinishedStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        assertStatus(stocktake, SemiFinishedStocktake.Status.PENDING_APPROVAL, "审批");
        stocktake.setStatus(SemiFinishedStocktake.Status.APPROVED);
        stocktake.setApprovedBy(approverId);
        stocktake.setApprovedAt(LocalDateTime.now());
        stocktakeRepo.save(stocktake);
        log.info("半成品盘点: 任务已审批 stocktakeId={} approverId={}", stocktakeId, approverId);
    }

    @Override
    @Transactional
    public void reject(String stocktakeId, String factoryId, String reason, Long userId, String requestRole) {
        assertApprovalRole(requestRole);
        SemiFinishedStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        assertStatus(stocktake, SemiFinishedStocktake.Status.PENDING_APPROVAL, "驳回");
        stocktake.setStatus(SemiFinishedStocktake.Status.REJECTED);
        stocktake.setRejectReason(reason);
        stocktake.setApprovedBy(userId);
        stocktake.setApprovedAt(LocalDateTime.now());
        stocktakeRepo.save(stocktake);
        log.info("半成品盘点: 任务已驳回 stocktakeId={} reason={}", stocktakeId, reason);
    }

    /**
     * 生效: 把每个差异行的 SFI 校准到实盘真值 + 写 ADJUST/STOCKTAKE 流水。
     * 红线: 必须经 APPROVED 状态; 幂等防重; 原子事务; SFI 悲观行锁 + @Version。
     */
    @Override
    @Transactional
    public void apply(String stocktakeId, String factoryId, Long userId) {
        SemiFinishedStocktake stocktake = stocktakeRepo.findById(stocktakeId)
                .orElseThrow(() -> new BusinessException(404, "盘点任务不存在: " + stocktakeId));
        if (!factoryId.equals(stocktake.getFactoryId())) {
            throw new BusinessException(403, "无权操作该盘点任务");
        }

        // 幂等防重
        if (stocktake.getStatus() == SemiFinishedStocktake.Status.APPLIED) {
            throw new BusinessException(409,
                    "盘点任务已于 " + stocktake.getAppliedAt() + " 生效，请勿重复操作")
                    .withCode("ALREADY_APPLIED")
                    .withHint("查看已生效记录");
        }
        assertStatus(stocktake, SemiFinishedStocktake.Status.APPROVED, "生效");

        for (SemiFinishedStocktakeItem item : stocktake.getItems()) {
            if (item.getDifferenceQty() == null
                    || item.getDifferenceQty().compareTo(BigDecimal.ZERO) == 0) {
                continue; // 差异为 0 的行跳过
            }

            // 悲观行锁定位目标 SFI 行 (factory-scoped)
            SemiFinishedInventory sfi = sfiRepo
                    .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                            factoryId, item.getIntermediateBatchNo())
                    .orElse(null);
            if (sfi == null) {
                log.warn("半成品盘点 apply: SFI 行不存在，跳过 factoryId={} batchNo={}",
                        factoryId, item.getIntermediateBatchNo());
                continue;
            }

            // delta 语义 (mirrors SP7 FactoryStocktakeServiceImpl:284): 重读锁定行当前余量,
            // 应用 delta = actualQty − systemQty(快照差) → 保留 count 与 apply 之间的并发产出/领用
            // (审批可能耗时数天, 绝对-set 会吞掉这期间的真实产出)。
            BigDecimal delta = item.getDifferenceQty(); // = actualQty − systemQty
            BigDecimal produced = nz(sfi.getProducedQuantity());
            BigDecimal consumed = nz(sfi.getConsumedQuantity());
            BigDecimal currentAvail = nz(sfi.getAvailableQuantity());
            BigDecimal newAvail = currentAvail.add(delta).setScale(2, RoundingMode.HALF_UP);
            if (newAvail.signum() < 0) {
                newAvail = BigDecimal.ZERO; // 防呆 not-below-zero
            }
            // 🔴 Fix 1 (禁止降级, 保住凭证+成本不变式): 只动 adjustmentQuantity, 让
            //   produced − consumed + adjustment == newAvail; producedQuantity/consumedQuantity/
            //   accumulatedCost/unitCost 全部不动 (它们喂 VoucherExportServiceImpl 半成品入库/发出
            //   凭证 + unitCost 分母)。盘盈/盘亏差异存于 ADJUST 流水; 盘盈=收入/盘亏=损耗 的
            //   财务差异凭证是文档化的 FOLLOW-UP (SP7 原料盘点也不过账损益凭证)。
            BigDecimal newAdjustment = newAvail.subtract(produced.subtract(consumed))
                    .setScale(2, RoundingMode.HALF_UP);
            sfi.setAdjustmentQuantity(newAdjustment);
            sfi.setAvailableQuantity(newAvail);
            // 状态同步 (镜像 WipInventoryServiceImpl; 不覆盖 RETURNED)
            if (!SemiFinishedInventory.Status.RETURNED.equals(sfi.getStatus())) {
                if (newAvail.compareTo(BigDecimal.ZERO) <= 0) {
                    sfi.setStatus(SemiFinishedInventory.Status.DEPLETED);
                } else {
                    sfi.setStatus(SemiFinishedInventory.Status.AVAILABLE);
                }
            }
            sfiRepo.save(sfi); // @Version 乐观锁并发保护 (produced/consumed/成本 未改动)

            // 写预留的 ADJUST/STOCKTAKE 流水 (可正可负 = 真实 delta; 纯数量校准, 不改成本口径)
            SemiFinishedInventoryTransaction txn = SemiFinishedInventoryTransaction.builder()
                    .factoryId(factoryId)
                    .semiFinishedId(sfi.getId())
                    .txnType(SemiFinishedInventoryTransaction.TxnType.ADJUST)
                    .sourceType(SemiFinishedInventoryTransaction.SourceType.STOCKTAKE)
                    .sourceRef(stocktakeId)
                    .quantity(delta)                       // 可正可负 = actualQty − systemQty
                    .unitCostAtTxn(sfi.getUnitCost())      // 均价快照 (诚实 null 传播, 未改动)
                    .balanceAfter(newAvail)
                    .balanceCostAfter(sfi.getUnitCost())
                    .operatorId(userId)
                    .build();
            sfiTxnRepo.save(txn);
            log.info("半成品盘点 apply: SFI ADJUST factoryId={} batchNo={} delta={} newAvailable={} adjustment={}",
                    factoryId, item.getIntermediateBatchNo(), delta, newAvail, newAdjustment);
        }

        stocktake.setStatus(SemiFinishedStocktake.Status.APPLIED);
        stocktake.setAppliedAt(LocalDateTime.now());
        stocktakeRepo.save(stocktake);
        log.info("半成品盘点: 任务已生效 stocktakeId={}", stocktakeId);
    }

    @Override
    public SemiFinishedStocktakeDiffPreviewDTO previewDiff(String stocktakeId, String factoryId) {
        SemiFinishedStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        SemiFinishedStocktakeDiffPreviewDTO preview = new SemiFinishedStocktakeDiffPreviewDTO();
        preview.setStocktakeId(stocktakeId);
        preview.setStocktakeNo(stocktake.getStocktakeNo());
        preview.setPeriodMonth(stocktake.getPeriodMonth());

        List<SemiFinishedStocktakeDiffPreviewDTO.DiffLine> lines = new ArrayList<>();
        int surplus = 0, shortage = 0, match = 0;
        for (SemiFinishedStocktakeItem item : stocktake.getItems()) {
            SemiFinishedStocktakeDiffPreviewDTO.DiffLine line = new SemiFinishedStocktakeDiffPreviewDTO.DiffLine();
            line.setItemId(item.getId());
            line.setSemiFinishedId(item.getSemiFinishedId());
            line.setIntermediateBatchNo(item.getIntermediateBatchNo());
            line.setProductTypeId(item.getProductTypeId());
            line.setUnit(item.getUnit());
            line.setSystemQty(item.getSystemQty());
            line.setActualQty(item.getActualQty());
            line.setDifferenceQty(item.getDifferenceQty());
            line.setDifferenceType(item.getDifferenceType() != null ? item.getDifferenceType().name() : null);
            lines.add(line);
            if (item.getDifferenceType() == SemiFinishedStocktakeItem.DifferenceType.SURPLUS) surplus++;
            else if (item.getDifferenceType() == SemiFinishedStocktakeItem.DifferenceType.SHORTAGE) shortage++;
            else if (item.getDifferenceType() == SemiFinishedStocktakeItem.DifferenceType.MATCH) match++;
        }
        preview.setDiffLines(lines);
        preview.setSurplusCount(surplus);
        preview.setShortageCount(shortage);
        preview.setMatchCount(match);
        return preview;
    }

    @Override
    public Page<SemiFinishedStocktakeDTO> list(String factoryId, SemiFinishedStocktake.Status status, Pageable pageable) {
        return stocktakeRepo.findByFactoryIdAndOptionalStatus(factoryId, status, pageable)
                .map(SemiFinishedStocktakeDTO::from);
    }

    @Override
    public SemiFinishedStocktakeDTO getDetail(String stocktakeId, String factoryId) {
        SemiFinishedStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        return SemiFinishedStocktakeDTO.from(stocktake);
    }

    @Override
    @Transactional
    public String submitForApproval(String stocktakeId, String factoryId, Long userId) {
        SemiFinishedStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        assertSubmittable(stocktake);

        // 幂等: 已有 workflowInstanceId 且 PENDING_APPROVAL → 不重复创建
        if (stocktake.getWorkflowInstanceId() != null
                && stocktake.getStatus() == SemiFinishedStocktake.Status.PENDING_APPROVAL) {
            throw new BusinessException(409,
                    "半成品盘点审批已提交 (PENDING_APPROVAL)，请勿重复提交 — 请前往[审批中心]查看")
                    .withCode("DUPLICATE_APPROVAL_REQUEST")
                    .withHint("前往审批中心: /approval-center");
        }

        stocktake.setStatus(SemiFinishedStocktake.Status.PENDING_APPROVAL);
        stocktake.setSubmittedBy(userId);
        stocktake.setSubmittedAt(LocalDateTime.now());

        // 启动 INVENTORY_ADJUSTMENT workflow (与 SP7 完全一致, 张权要求的审批)
        if (workflowEngine != null && workflowEngine.hasActiveWorkflow(factoryId, WORKFLOW_MODULE_CODE)) {
            ApprovalWorkflowInstance instance = workflowEngine.startWorkflow(
                    factoryId,
                    WORKFLOW_MODULE_CODE,
                    stocktakeId,
                    Map.of("stocktakeNo", stocktake.getStocktakeNo(),
                           "periodMonth", stocktake.getPeriodMonth(),
                           "type", "SEMI_FINISHED_STOCKTAKE"),
                    userId);
            stocktake.setWorkflowInstanceId(instance.getId());
        }

        stocktakeRepo.save(stocktake);
        log.info("半成品盘点: 任务已提交审批 stocktakeId={} workflowInstanceId={}",
                stocktakeId, stocktake.getWorkflowInstanceId());
        return stocktake.getWorkflowInstanceId();
    }

    @Override
    @Transactional
    public void executeAdjustment(String stocktakeId) {
        SemiFinishedStocktake stocktake = stocktakeRepo.findById(stocktakeId)
                .orElseThrow(() -> new BusinessException(404, "盘点任务不存在: " + stocktakeId));

        // 红线: 必须有 workflowInstanceId (不允许绕过 workflow)
        if (stocktake.getWorkflowInstanceId() == null) {
            throw new BusinessException(403,
                    "半成品盘点调账必须经过 INVENTORY_ADJUSTMENT 工作流审批，无法直接调账")
                    .withCode("WORKFLOW_BYPASS_FORBIDDEN")
                    .withHint("请先通过工作流提交审批");
        }

        if (stocktake.getStatus() == SemiFinishedStocktake.Status.APPLIED) {
            throw new BusinessException(409,
                    "盘点任务已于 " + stocktake.getAppliedAt() + " 生效，请勿重复操作")
                    .withCode("ALREADY_APPLIED")
                    .withHint("查看已生效记录");
        }
        assertStatus(stocktake, SemiFinishedStocktake.Status.APPROVED, "生效");

        // 复用 apply() 的库存调整逻辑
        apply(stocktakeId, stocktake.getFactoryId(), null);
    }

    // -------------------------------------------------------
    // private helpers
    // -------------------------------------------------------

    private void assertApprovalRole(String requestRole) {
        String normalized = requestRole == null ? "" : requestRole.toLowerCase();
        if (!STOCKTAKE_APPROVAL_ROLES.contains(normalized)) {
            throw new BusinessException(403,
                    "半成品盘点审批需要财务经理或工厂管理员角色，当前角色：" + requestRole)
                    .withHint("请联系财务经理 (finance_manager) 或工厂超管审批");
        }
    }

    private SemiFinishedStocktake findAndValidate(String stocktakeId, String factoryId) {
        SemiFinishedStocktake stocktake = stocktakeRepo.findById(stocktakeId)
                .orElseThrow(() -> new BusinessException(404, "盘点任务不存在: " + stocktakeId));
        if (!factoryId.equals(stocktake.getFactoryId())) {
            throw new BusinessException(403, "无权操作该盘点任务");
        }
        return stocktake;
    }

    private void assertSubmittable(SemiFinishedStocktake stocktake) {
        if (stocktake.getStatus() != SemiFinishedStocktake.Status.COUNTING
                && stocktake.getStatus() != SemiFinishedStocktake.Status.INITIATED
                && stocktake.getStatus() != SemiFinishedStocktake.Status.REJECTED) {
            throw new BusinessException(409,
                    "当前盘点任务状态 [" + stocktake.getStatus() + "] 不支持提交，需要 COUNTING 或 INITIATED 或 REJECTED（重提）")
                    .withHint("请先录入实盘数量后再提交");
        }
    }

    private void assertStatus(SemiFinishedStocktake stocktake, SemiFinishedStocktake.Status expected, String action) {
        if (stocktake.getStatus() != expected) {
            throw new BusinessException(409,
                    "当前盘点任务状态 [" + stocktake.getStatus() + "] 不支持操作 [" + action + "]，需要状态: " + expected);
        }
    }

    private void assertNotApplied(SemiFinishedStocktake stocktake) {
        if (stocktake.getStatus() == SemiFinishedStocktake.Status.APPLIED) {
            throw new BusinessException(409,
                    "盘点任务已于 " + stocktake.getAppliedAt() + " 生效，无法修改")
                    .withCode("ALREADY_APPLIED");
        }
    }

    private String generateStocktakeNo() {
        String month = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        return "SFST-" + month + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
