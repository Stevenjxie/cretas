package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.CreateSemiFinishedStocktakeRequest;
import com.cretas.aims.dto.factory.SemiFinishedStocktakeDiffPreviewDTO;
import com.cretas.aims.dto.factory.SemiFinishedStocktakeDTO;
import com.cretas.aims.dto.factory.SemiFinishedStocktakeItemUpdateDTO;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.InterimSettleReversalRequest;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.factory.SemiFinishedStocktake;
import com.cretas.aims.entity.factory.SemiFinishedStocktakeItem;
import com.cretas.aims.repository.InterimSettleReversalRequestRepository;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.factory.SemiFinishedStocktakeItemRepository;
import com.cretas.aims.repository.factory.SemiFinishedStocktakeRepository;
import com.cretas.aims.service.factory.SemiFinishedStocktakeService;
import com.cretas.aims.service.voucher.VoucherService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.service.yield.PendingInterimFeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.Optional;
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
    /** 盘点差异财务过账 (盘盈=收入/盘亏=损耗); prod 必注入 (禁止降级不做过账)。 */
    private final VoucherService voucherService;

    /** SP12: optional — 测试时不注入 (required=false 打破构造器注入限制) */
    @Autowired(required = false)
    private WorkflowEngineService workflowEngine;

    /**
     * 撤销小结治理告警 (READ-ONLY): 盘点发起/查看时, 从撤销审计查该期间已执行的撤销申请, 在盘点列上标告警点。
     * optional (required=false) — 不改构造器签名 (不破坏既有 @InjectMocks 单测); null 时跳过告警 (向后兼容)。
     * 只读撤销审计, 不写撤销/不耦合盘点写路径。
     */
    @Autowired(required = false)
    private InterimSettleReversalRequestRepository reversalRequestRepo;

    /**
     * 🔴🔒🔒 延迟扣减盲区修复 (sibling of #1216, 2026-07-05): 快照 SFI 账面须减去「待小结投料」——
     * 报工写待小结 SFI 投料 (upstreamSources.semiFinished) 但小结才 {@code consumeClerkSemiStrict} 扣减,
     * 窗口内 {@code availableQuantity} stale-high (货已投产, 账面未扣)。不减 → 仓管诚实数少 → 假盘亏 +
     * 假 借6602/贷1405 凭证, 且随后小结二次扣减 → 库存虚低。optional (required=false): 单测不注入时
     * 回退旧口径 (不减), 不破坏既有 @InjectMocks / new-构造 单测。prod 恒注入 (@Service)。
     */
    @Autowired(required = false)
    private PendingInterimFeedService pendingInterimFeedService;

    // -------------------------------------------------------
    // 盘点差异财务凭证 科目 (China GAAP, 复用 seed V20260701_02 + ExpenseVoucherGenerator 损耗 pattern)
    //   盘盈 (surplus): 借 1405 库存商品 / 贷 6301 营业外收入          (客户张权模型: 盘盈=收入)
    //   盘亏 (shortage): 借 6602.01 管理费用-损耗 / 贷 1405 库存商品   (镜像 WastageRecord 损耗过账; 盘亏=损耗)
    // -------------------------------------------------------
    private static final String SUBJECT_INVENTORY_CODE = "1405";
    private static final String SUBJECT_INVENTORY_NAME = "库存商品";
    private static final String SUBJECT_SURPLUS_INCOME_CODE = "6301";
    private static final String SUBJECT_SURPLUS_INCOME_NAME = "营业外收入";
    private static final String SUBJECT_SHORTAGE_LOSS_CODE = "6602.01";
    private static final String SUBJECT_SHORTAGE_LOSS_NAME = "管理费用-损耗";
    /** 凭证来源业务类型 (与 stocktakeId 组成 uk_voucher_source_business 幂等键)。 */
    private static final String VOUCHER_SOURCE_TYPE = "SEMI_FINISHED_STOCKTAKE";

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
        // 张权要求: 盘点任意时间可发起 (周复盘 / 月复盘皆可) — 已去除月底(threshold)日期限制。
        // 唯一去重: 同工厂同时只允许一个未完成(非终态)盘点, 上一个生效(APPLIED)/驳回(REJECTED)后即可再发起。
        long existing = stocktakeRepo.countActiveStocktake(factoryId);
        if (existing > 0) {
            throw new BusinessException(409,
                    "已有进行中的半成品盘点，请先完成或取消后再发起")
                    .withCode("DUPLICATE_STOCKTAKE")
                    .withHint("请前往盘点列表完成或驳回进行中的盘点");
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
        // 🔴 Fix (🔒🔒 延迟扣减盲区, sibling #1216, 2026-07-05): 整厂一次性预取「待小结 SFI 投料」量,
        //   快照账面须减去它 = 货架实物。null (单测未注入) → 空 map → 减 0 (旧口径, 向后兼容)。
        Map<String, BigDecimal> pendingSemiFeed = pendingInterimFeedService != null
                ? pendingInterimFeedService.pendingSemiFeedByBatchNo(factoryId)
                : java.util.Collections.emptyMap();
        List<SemiFinishedStocktakeItem> items = new ArrayList<>();
        for (SemiFinishedInventory sfi : rows) {
            SemiFinishedStocktakeItem item = new SemiFinishedStocktakeItem();
            item.setStocktake(stocktake);
            item.setSemiFinishedId(sfi.getId());
            item.setIntermediateBatchNo(sfi.getIntermediateBatchNo());
            item.setProductTypeId(sfi.getProductTypeId());
            item.setUnit(sfi.getUnit());
            // 🔴 货架实物 = availableQuantity − 待小结 SFI 投料 (来源原生单位); clamp≥0 防超投场景负账面
            //   (待扣>可用时小结本身会 SFI_INSUFFICIENT 拦, 此处不产负数误判假盘盈)。无待扣 → 减 0。
            BigDecimal shelf = physicalShelf(sfi.getAvailableQuantity(),
                    pendingSemiFeed.get(sfi.getIntermediateBatchNo()));
            item.setSystemQty(shelf.setScale(4, RoundingMode.HALF_UP));
            items.add(item);
        }
        stocktake.setItems(items);

        // Belt-and-suspenders: 上面的 countActiveStocktake 预检存在 TOCTOU 窗口 (两个并发
        // initiate 都读到 0)。DB 层 partial unique index uk_sf_stocktake_one_active
        // (V20261027_27, 谓词 status NOT IN(APPLIED,REJECTED) AND deleted_at IS NULL)
        // 是最终兜底。saveAndFlush 让 INSERT 在本方法事务内同步执行, 竞态输家在此命中唯一索引
        // → DataIntegrityViolationException, 转成与预检完全一致的友好 409 (而非裸 500)。
        SemiFinishedStocktake saved;
        try {
            saved = stocktakeRepo.saveAndFlush(stocktake);
        } catch (DataIntegrityViolationException e) {
            log.warn("半成品盘点: 并发发起命中「一厂一进行中」唯一索引, factoryId={} → 转 409", factoryId, e);
            throw new BusinessException(409,
                    "已有进行中的半成品盘点，请先完成或取消后再发起")
                    .withCode("DUPLICATE_STOCKTAKE")
                    .withHint("请前往盘点列表完成或驳回进行中的盘点");
        }
        log.info("半成品盘点: 任务已创建 factoryId={} stocktakeNo={} itemCount={}",
                factoryId, saved.getStocktakeNo(), items.size());
        SemiFinishedStocktakeDTO dto = SemiFinishedStocktakeDTO.from(saved);
        enrichReversalWarnings(dto, factoryId, saved.getPeriodMonth());
        return dto;
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

        // 盘点差异财务过账累计 (盘盈=收入/盘亏=损耗)。逐行按 delta × unitCost 估值,
        // 净盘盈总额进收入侧, 净盘亏总额进损耗侧 (同一张凭证内两侧独立, 不互相净额抵销)。
        BigDecimal surplusValue = BigDecimal.ZERO;   // Σ 盘盈行 value (正)
        BigDecimal shortageValue = BigDecimal.ZERO;  // Σ |盘亏行 value| (正)
        int uncostedCount = 0;                       // unitCost=null 无法估值的差异行 (排除出凭证)
        int valuedCount = 0;

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
            // realizedDelta = 实际发生的余量变化 (clamp 后)。盘盈/未触底盘亏 == delta;
            //   盘亏 |delta|>currentAvail (并发耗尽) 时 clamp 到 0 → realizedDelta = −currentAvail (< |delta|)。
            //   台账 ADJUST 流水 + 盘盈/盘亏凭证 都用 realizedDelta, 保证三者 (库存变动/流水/凭证) 一致,
            //   不按未真实发生的 raw delta 高估损耗 (禁止降级: 记账必须等于实际发生)。
            //   不变式 balanceAfter(newAvail) == balanceBefore(currentAvail) + realizedDelta 也因此成立。
            BigDecimal realizedDelta = newAvail.subtract(currentAvail);
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

            // 写预留的 ADJUST/STOCKTAKE 流水 (可正可负 = 实际发生的 realizedDelta; 纯数量校准, 不改成本口径)
            SemiFinishedInventoryTransaction txn = SemiFinishedInventoryTransaction.builder()
                    .factoryId(factoryId)
                    .semiFinishedId(sfi.getId())
                    .txnType(SemiFinishedInventoryTransaction.TxnType.ADJUST)
                    .sourceType(SemiFinishedInventoryTransaction.SourceType.STOCKTAKE)
                    .sourceRef(stocktakeId)
                    .quantity(realizedDelta)               // 实际余量变化 (clamp 后; balanceAfter=before+quantity)
                    .unitCostAtTxn(sfi.getUnitCost())      // 均价快照 (诚实 null 传播, 未改动)
                    .balanceAfter(newAvail)
                    .balanceCostAfter(sfi.getUnitCost())
                    .operatorId(userId)
                    .build();
            sfiTxnRepo.save(txn);
            log.info("半成品盘点 apply: SFI ADJUST factoryId={} batchNo={} realizedDelta={} (rawDelta={}) newAvailable={} adjustment={}",
                    factoryId, item.getIntermediateBatchNo(), realizedDelta, delta, newAvail, newAdjustment);

            // 差异估值 (禁止降级/诚实 null): unitCost=null (未计成本半成品) 无法估值 →
            //   排除出财务凭证 (数量已调整), 绝不臆造价值。value = realizedDelta × unitCost
            //   (按实际发生的余量变化估值, 盘亏超可用量 clamp 时不高估损耗)。
            BigDecimal unitCost = sfi.getUnitCost();
            if (unitCost == null) {
                uncostedCount++;
                log.warn("半成品盘点 apply: SFI unitCost=null 未计成本, 差异行排除出财务凭证 (数量仍调整) "
                        + "factoryId={} batchNo={} realizedDelta={}", factoryId, item.getIntermediateBatchNo(), realizedDelta);
            } else {
                BigDecimal itemValue = realizedDelta.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
                valuedCount++;
                if (itemValue.signum() > 0) {
                    surplusValue = surplusValue.add(itemValue);
                } else if (itemValue.signum() < 0) {
                    shortageValue = shortageValue.add(itemValue.negate());
                }
            }
        }

        // 财务过账: 盘盈=收入 / 盘亏=损耗 (与库存调整同一 @Transactional — 过账失败则整个 apply 回滚,
        // 绝不留下"库存已调但凭证未过"的不一致态)。全部差异行未计成本 → 不过账凭证 (仅 warn)。
        postVarianceVoucher(stocktake, factoryId, surplusValue, shortageValue, valuedCount, uncostedCount, userId);

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
        SemiFinishedStocktakeDTO dto = SemiFinishedStocktakeDTO.from(stocktake);
        enrichReversalWarnings(dto, factoryId, stocktake.getPeriodMonth());
        return dto;
    }

    /**
     * 撤销小结告警 (READ-ONLY on 撤销审计): 在盘点期间已<b>执行</b>的撤销申请所影响的半成品/成品批次上标告警点,
     * 提示盘点人核实实物 (撤销逆转了系统库存)。repo 未注入 / 期间无撤销 → 无告警 (向后兼容)。
     */
    private void enrichReversalWarnings(SemiFinishedStocktakeDTO dto, String factoryId, String periodMonth) {
        if (reversalRequestRepo == null) {
            return;
        }
        LocalDateTime[] range = resolvePeriodRange(periodMonth);
        List<InterimSettleReversalRequest> executed = reversalRequestRepo
                .findByFactoryIdAndStatusAndExecutedAtBetween(factoryId,
                        InterimSettleReversalRequest.Status.EXECUTED, range[0], range[1]);
        if (executed.isEmpty()) {
            return;
        }
        List<SemiFinishedStocktakeDTO.ReversalWarning> warnings = new ArrayList<>();
        DateTimeFormatter md = DateTimeFormatter.ofPattern("M月d日");
        for (InterimSettleReversalRequest r : executed) {
            if (r.getAffectedBatchNumbers() == null || r.getAffectedBatchNumbers().isBlank()) {
                continue;
            }
            String when = r.getExecutedAt() != null ? r.getExecutedAt().format(md) : "近期";
            for (String raw : r.getAffectedBatchNumbers().split(",")) {
                String batchNo = raw.trim();
                if (batchNo.isEmpty()) {
                    continue;
                }
                warnings.add(new SemiFinishedStocktakeDTO.ReversalWarning(batchNo,
                        "批次 " + batchNo + " 于 " + when + " 撤销过小结，请核实实物"));
            }
        }
        if (!warnings.isEmpty()) {
            dto.setWarnings(warnings);
        }
    }

    /** periodMonth → 当月 [00:00, 月末23:59:59]; 解析失败退回当月 (务实, 不清空告警)。 */
    private LocalDateTime[] resolvePeriodRange(String periodMonth) {
        YearMonth ym = parseYearMonth(periodMonth);
        if (ym == null) {
            ym = YearMonth.now();
        }
        return new LocalDateTime[]{ ym.atDay(1).atStartOfDay(), ym.atEndOfMonth().atTime(23, 59, 59) };
    }

    private YearMonth parseYearMonth(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        try {
            return YearMonth.parse(t, DateTimeFormatter.ofPattern("yyyy-MM"));
        } catch (Exception ignored) { /* try next */ }
        try {
            return YearMonth.parse(t, DateTimeFormatter.ofPattern("yyyyMM"));
        } catch (Exception ignored) { /* try next */ }
        try {
            return YearMonth.parse(t.substring(0, 7)); // "yyyy-MM-dd" → first 7 chars
        } catch (Exception ignored) { /* give up */ }
        return null;
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

        stocktake.setSubmittedBy(userId);
        stocktake.setSubmittedAt(LocalDateTime.now());

        if (workflowEngine == null) {
            throw new BusinessException(503, "审批运行时暂不可用")
                    .withCode("OA_RUNTIME_UNAVAILABLE");
        }
        Optional<ApprovalWorkflowInstance> instance = workflowEngine.startWorkflowIfConfigured(
                factoryId,
                WORKFLOW_MODULE_CODE,
                stocktakeId,
                Map.of("stocktakeNo", stocktake.getStocktakeNo(),
                       "periodMonth", stocktake.getPeriodMonth(),
                       "type", "SEMI_FINISHED_STOCKTAKE"),
                userId);
        if (instance.isPresent()) {
            stocktake.setStatus(SemiFinishedStocktake.Status.PENDING_APPROVAL);
            stocktake.setWorkflowInstanceId(instance.get().getId());
        } else {
            stocktake.setStatus(SemiFinishedStocktake.Status.APPROVED);
            stocktake.setApprovedBy(userId);
            stocktake.setApprovedAt(LocalDateTime.now());
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
        if (stocktake.getWorkflowInstanceId() == null
                && stocktake.getStatus() != SemiFinishedStocktake.Status.APPROVED) {
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

    /**
     * 过账盘点差异财务凭证 (盘盈=收入 / 盘亏=损耗)。
     *
     * <p>科目 (China GAAP): 盘盈 借 1405 库存商品 / 贷 6301 营业外收入;
     * 盘亏 借 6602.01 管理费用-损耗 / 贷 1405 库存商品 (镜像 WastageRecord 损耗过账)。
     * 同一次 apply 若既有盘盈又有盘亏, 合并进一张凭证 (两侧独立入账, 库存侧收入侧损耗侧各自 gross,
     * 不做净额抵销 — 不同 SKU 的盈亏分属不同科目, 净额抵销会错记收入/费用)。
     *
     * <p>{@code createManual} 直建 POSTED 凭证并共用本 apply 的 @Transactional:
     * 借贷不平/DB 失败 → 抛异常 → 整个 apply 回滚。凭证经 (SEMI_FINISHED_STOCKTAKE, stocktakeId)
     * 唯一约束幂等 (apply 本身已 APPLIED 状态 + @Version 防重, 唯一约束是兜底)。
     * 该路径刻意绕过期间结账 gate (mirrors 结转损益/红冲系统凭证 — 盘点校准须能过账, 不被锁定期间阻断)。
     *
     * @param valuedCount   已估值差异行数 (unitCost 非 null)
     * @param uncostedCount 未计成本被排除行数 (unitCost=null)
     */
    private void postVarianceVoucher(SemiFinishedStocktake stocktake, String factoryId,
            BigDecimal surplusValue, BigDecimal shortageValue,
            int valuedCount, int uncostedCount, Long userId) {
        boolean hasSurplus = surplusValue.signum() > 0;
        boolean hasShortage = shortageValue.signum() > 0;
        if (!hasSurplus && !hasShortage) {
            // 无可估值差异: 全 MATCH / 全被排除 (uncosted) / 估值净额均为 0 → 不过账凭证
            if (uncostedCount > 0) {
                log.warn("半成品盘点 apply: 全部差异行未计成本(unitCost=null), 不过账财务凭证 "
                        + "stocktakeNo={} uncosted={}", stocktake.getStocktakeNo(), uncostedCount);
            }
            return;
        }

        List<VoucherEntrySpec> entries = new ArrayList<>();
        String no = stocktake.getStocktakeNo();
        if (hasSurplus) {
            // 盘盈: 借 库存商品 / 贷 营业外收入
            entries.add(new VoucherEntrySpec(SUBJECT_INVENTORY_CODE, SUBJECT_INVENTORY_NAME,
                    surplusValue, null, "半成品盘盈 " + no));
            entries.add(new VoucherEntrySpec(SUBJECT_SURPLUS_INCOME_CODE, SUBJECT_SURPLUS_INCOME_NAME,
                    null, surplusValue, "半成品盘盈收益 " + no));
        }
        if (hasShortage) {
            // 盘亏: 借 管理费用-损耗 / 贷 库存商品
            entries.add(new VoucherEntrySpec(SUBJECT_SHORTAGE_LOSS_CODE, SUBJECT_SHORTAGE_LOSS_NAME,
                    shortageValue, null, "半成品盘亏损耗 " + no));
            entries.add(new VoucherEntrySpec(SUBJECT_INVENTORY_CODE, SUBJECT_INVENTORY_NAME,
                    null, shortageValue, "半成品盘亏库存减少 " + no));
        }

        String summary = no + " 半成品盘点"
                + (hasSurplus ? "盘盈" : "") + (hasShortage ? "盘亏" : "") + "差异凭证";
        voucherService.createManual(factoryId, VoucherType.INVENTORY_STOCKTAKE,
                LocalDate.now(), entries, VOUCHER_SOURCE_TYPE, stocktake.getId(), summary, userId);
        log.info("半成品盘点 apply: 已过账差异凭证 stocktakeNo={} 盘盈={} 盘亏={} valued={} uncosted={}",
                no, surplusValue, shortageValue, valuedCount, uncostedCount);
    }

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

    /**
     * 🔴🔒🔒 货架实物量 = max(0, availableQuantity − 待小结 SFI 投料) (mirror #1216 physicalShelf)。
     * clamp 到 0 防超投 (待扣 > 可用, 小结本身会 SFI_INSUFFICIENT 拦) 下算出负账面 → 反被仓管数 0
     * 误判假盘盈。pending=null (无待扣) → 减 0, 口径不变。
     */
    private static BigDecimal physicalShelf(BigDecimal available, BigDecimal pending) {
        BigDecimal a = nz(available);
        BigDecimal p = nz(pending);
        BigDecimal shelf = a.subtract(p);
        return shelf.signum() < 0 ? BigDecimal.ZERO : shelf;
    }
}
