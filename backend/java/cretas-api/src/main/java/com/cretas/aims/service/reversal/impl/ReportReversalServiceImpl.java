package com.cretas.aims.service.reversal.impl;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.reversal.ReportReversalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SP2 整单撤回实现。
 *
 * <p><b>三层守卫</b> (全部 OUTSIDE @Transactional):
 * <ol>
 *   <li>Guard G1: 下游领用检查 — SIT 中存在 OUT/SECONDARY_CONSUME 行 → 409 DOWNSTREAM_CONSUMED</li>
 *   <li>Guard G2: 成品出货检查 — FinishedGoodsBatch shippedQuantity > 0 → 409 FG_SHIPPED</li>
 *   <li>Guard G3: 幂等检查 — 已有 DONE 直接返回; PENDING 返回 409 ALREADY_PENDING</li>
 * </ol>
 *
 * <p><b>executeReversal 原子保证</b>: 单 @Transactional。软删报工 → 写 REVERSE SIT → 回放均价 → 更新 FGB
 * → 置 ReportReversalLog.DONE。任一失败 → 全部回滚 (Spring REQUIRED 默认)。
 *
 * <p><b>fail-soft 禁止</b>: 不使用 try/catch 吞掉内层异常 (历史事故:
 * REQUIRED 内抛异常后父事务标记 rollback-only, fail-soft catch 救不回)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportReversalServiceImpl implements ReportReversalService {

    private final ReportReversalLogRepository reversalLogRepo;
    private final ProductionBatchRepository batchRepo;
    private final ProductionReportRepository reportRepo;
    private final SemiFinishedInventoryRepository wipRepo;
    private final SemiFinishedInventoryTransactionRepository txnRepo;
    private final FinishedGoodsBatchRepository fgbRepo;

    // ==================== 提交撤回申请 ====================

    @Override
    public ReportReversalLog submitReversal(String factoryId, Long batchId, Long submittedBy, String reason) {
        // ---- 守卫全部 OUTSIDE @Transactional ----

        // G1: 下游领用检查
        String batchRef = String.valueOf(batchId);
        if (txnRepo.existsDownstreamConsumed(factoryId, batchRef)) {
            throw new BusinessException(409, String.format(
                    "批次 %d 的半成品已被下道工序领用，无法整单撤回", batchId))
                    .withCode("DOWNSTREAM_CONSUMED")
                    .withHint("请先处理下游领用记录后再申请撤回");
        }

        // 加载批次获取 planId (G2 需要)
        ProductionBatch batch = batchRepo.findByIdAndFactoryId(batchId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "生产批次不存在: " + batchId));

        // G2: 成品出货检查
        if (batch.getProductionPlanId() != null) {
            if (fgbRepo.existsShippedByFactoryIdAndProductionPlanId(factoryId, batch.getProductionPlanId())) {
                throw new BusinessException(409, String.format(
                        "批次 %d 对应计划的成品已出货，无法整单撤回", batchId))
                        .withCode("FG_SHIPPED")
                        .withHint("请确认出货记录后处理退货再申请撤回");
            }
        }

        // G3: 幂等检查
        Optional<ReportReversalLog> existing = reversalLogRepo.findByBatchIdAndReversalScopeAndDeletedAtIsNull(
                batchId, ReportReversalLog.ReversalScope.WHOLE_ORDER);
        if (existing.isPresent()) {
            ReportReversalLog ex = existing.get();
            if (ReportReversalLog.ReversalStatus.DONE == ex.getStatus()) {
                log.info("[SP2] submitReversal idempotent DONE: batchId={} logId={}", batchId, ex.getId());
                return ex;
            }
            if (ReportReversalLog.ReversalStatus.PENDING == ex.getStatus()) {
                throw new BusinessException(409, String.format(
                        "批次 %d 已有待审批的撤回申请 (id=%d)", batchId, ex.getId()))
                        .withCode("ALREADY_PENDING")
                        .withHint("请等待审批结果或联系管理员");
            }
        }

        // 检查是否有报工数据 (决定 PENDING vs 直通 DONE)
        List<ProductionReport> reports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
        boolean hasReports = !reports.isEmpty();

        ReportReversalLog.ReversalStatus initialStatus = hasReports
                ? ReportReversalLog.ReversalStatus.PENDING
                : ReportReversalLog.ReversalStatus.DONE;

        ReportReversalLog log_ = ReportReversalLog.builder()
                .factoryId(factoryId)
                .batchId(batchId)
                .planId(batch.getProductionPlanId())
                .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                .submittedBy(submittedBy)
                .reason(reason)
                .status(initialStatus)
                .build();
        ReportReversalLog saved = reversalLogRepo.save(log_);

        // 无报工数据 → 直通执行 (DONE)
        if (!hasReports) {
            executeReversal(saved.getId(), factoryId);
        }

        log.info("[SP2] submitReversal batchId={} status={} logId={}", batchId, initialStatus, saved.getId());
        return reversalLogRepo.findById(saved.getId()).orElse(saved);
    }

    // ==================== 执行撤回 (单一 @Transactional 原子) ====================

    @Override
    @Transactional
    public void executeReversal(Long logId, String factoryId) {
        ReportReversalLog log_ = reversalLogRepo.findById(logId)
                .orElseThrow(() -> new BusinessException(404, "撤回申请不存在: " + logId));
        if (!factoryId.equals(log_.getFactoryId())) {
            throw new BusinessException(403, "工厂 ID 不匹配");
        }
        // 幂等: 已 DONE 直接返回
        if (ReportReversalLog.ReversalStatus.DONE == log_.getStatus()) {
            log.warn("[SP2] executeReversal already DONE logId={}, skip", logId);
            return;
        }

        Long batchId = log_.getBatchId();
        List<Long> revertedTxnIds = new ArrayList<>();

        // ① 软删除该批次所有 YIELD 报工
        List<ProductionReport> reports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
        LocalDateTime now = LocalDateTime.now();
        for (ProductionReport r : reports) {
            r.setDeletedAt(now);
            reportRepo.save(r);
        }

        // ② 每份报工: 找到对应 IN SIT 行 → 写 REVERSE 行 → 回放移动均价
        for (ProductionReport r : reports) {
            List<SemiFinishedInventoryTransaction> inTxns =
                    txnRepo.findByFactoryIdAndReportId(factoryId, r.getId());
            for (SemiFinishedInventoryTransaction inTxn : inTxns) {
                if (!SemiFinishedInventoryTransaction.TxnType.IN.equals(inTxn.getTxnType())) {
                    continue; // 只冲销 IN 行
                }
                // 写 REVERSE 行 (负数量, 不改 IN 行)
                SemiFinishedInventoryTransaction reverseTxn = SemiFinishedInventoryTransaction.builder()
                        .factoryId(factoryId)
                        .semiFinishedId(inTxn.getSemiFinishedId())
                        .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                        .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                        .sourceRef("reversal-log:" + logId)
                        .quantity(inTxn.getQuantity().negate())
                        .unitCostAtTxn(inTxn.getUnitCostAtTxn())
                        .operatorId(log_.getApprovedBy() != null ? log_.getApprovedBy() : log_.getSubmittedBy())
                        .build();
                SemiFinishedInventoryTransaction saved = txnRepo.save(reverseTxn);
                revertedTxnIds.add(saved.getId());

                // 回放移动均价到 SemiFinishedInventory
                replayMovingAverage(inTxn.getSemiFinishedId(), factoryId, now);
            }
        }

        // ③ 将对应 FinishedGoodsBatch 标记 REVERSED (如有)
        if (log_.getPlanId() != null) {
            List<FinishedGoodsBatch> fgbs =
                    fgbRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, log_.getPlanId());
            for (FinishedGoodsBatch fgb : fgbs) {
                fgb.setStatus(FinishedGoodsBatch.Status.REVERSED);
                fgb.setReversalLogId(logId);
                fgbRepo.save(fgb);
            }
        }

        // ④ 更新 ReportReversalLog → DONE
        log_.setStatus(ReportReversalLog.ReversalStatus.DONE);
        log_.setRevertedTxnIds(revertedTxnIds);
        reversalLogRepo.save(log_);

        log.info("[SP2] executeReversal DONE logId={} batchId={} revertedTxns={}",
                logId, batchId, revertedTxnIds.size());
    }

    // ==================== 审批操作 ====================

    @Override
    public void approveReversal(Long logId, Long approvedBy) {
        ReportReversalLog log_ = reversalLogRepo.findById(logId)
                .orElseThrow(() -> new BusinessException(404, "撤回申请不存在: " + logId));
        if (ReportReversalLog.ReversalStatus.PENDING != log_.getStatus()) {
            throw new BusinessException(409, String.format(
                    "只有待审批状态的撤回申请可审批，当前状态: %s (logId=%d)", log_.getStatus(), logId))
                    .withCode("INVALID_STATUS");
        }
        log_.setApprovedBy(approvedBy);
        log_.setApprovedAt(LocalDateTime.now());
        log_.setStatus(ReportReversalLog.ReversalStatus.APPROVED);
        reversalLogRepo.save(log_);

        // 批准后自动执行撤回
        executeReversal(logId, log_.getFactoryId());
        log.info("[SP2] approveReversal logId={} approvedBy={}", logId, approvedBy);
    }

    @Override
    public void rejectReversal(Long logId, Long approvedBy, String reason) {
        ReportReversalLog log_ = reversalLogRepo.findById(logId)
                .orElseThrow(() -> new BusinessException(404, "撤回申请不存在: " + logId));
        if (ReportReversalLog.ReversalStatus.PENDING != log_.getStatus()) {
            throw new BusinessException(409, String.format(
                    "撤回申请 %d 当前状态为 %s，无法拒绝", logId, log_.getStatus()))
                    .withCode("INVALID_STATUS");
        }
        log_.setApprovedBy(approvedBy);
        log_.setApprovedAt(LocalDateTime.now());
        log_.setStatus(ReportReversalLog.ReversalStatus.REJECTED);
        if (reason != null && !reason.isBlank()) {
            log_.setReason(log_.getReason() + " | 拒绝原因: " + reason);
        }
        reversalLogRepo.save(log_);
        log.info("[SP2] rejectReversal logId={} approvedBy={}", logId, approvedBy);
    }

    @Override
    public List<ReportReversalLog> listReversals(String factoryId, String status) {
        if (status == null || status.isBlank()) {
            // 返回所有状态 — 使用 no-status 方法, 避免传 null 给 enum 参数
            return reversalLogRepo.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId);
        }
        ReportReversalLog.ReversalStatus statusEnum;
        try {
            statusEnum = ReportReversalLog.ReversalStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "无效的状态值: " + status + "，合法值: PENDING/APPROVED/DONE/REJECTED");
        }
        return reversalLogRepo
                .findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId, statusEnum);
    }

    // ==================== 私有辅助 ====================

    /**
     * 移动均价回放: 查询该 SFI 所有未冲销 IN 行 (按 createdAt ASC), 重算 unitCost。
     * 若无剩余有效 IN → unitCost = null, availableQuantity = 0, status = DEPLETED。
     */
    private void replayMovingAverage(Long sfiId, String factoryId, LocalDateTime asOf) {
        SemiFinishedInventory sfi = wipRepo.findById(sfiId)
                .orElse(null);
        if (sfi == null) {
            log.warn("[SP2] replayMovingAverage: sfiId={} not found, skip", sfiId);
            return;
        }

        List<SemiFinishedInventoryTransaction> allTxns =
                txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId);

        // 收集被 REVERSE 行对应冲销的 IN 行 id (REVERSE 的 sourceRef = "reversal-log:XXX")
        // 策略: 遍历, 统计每个 sfiId+txnType=IN 被多少 REVERSE 行覆盖 (按比 1:1 匹配数量)
        // 简化: 重放所有 IN, 跳过被 REVERSE 完全抵消的部分
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal weightedCost = BigDecimal.ZERO;

        for (SemiFinishedInventoryTransaction txn : allTxns) {
            if (SemiFinishedInventoryTransaction.TxnType.IN.equals(txn.getTxnType())) {
                BigDecimal q = txn.getQuantity();
                if (q != null && q.compareTo(BigDecimal.ZERO) > 0 && txn.getUnitCostAtTxn() != null) {
                    totalQty = totalQty.add(q);
                    weightedCost = weightedCost.add(q.multiply(txn.getUnitCostAtTxn()));
                } else if (q != null && q.compareTo(BigDecimal.ZERO) > 0) {
                    // qty without cost — add qty but keep cost null below
                    totalQty = totalQty.add(q);
                }
            } else if (SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(txn.getTxnType())) {
                // REVERSE 行减去 IN 的贡献 (quantity 为负)
                BigDecimal q = txn.getQuantity(); // 负数
                if (q != null && txn.getUnitCostAtTxn() != null) {
                    totalQty = totalQty.add(q); // 负数相加 = 减
                    weightedCost = weightedCost.add(q.multiply(txn.getUnitCostAtTxn()));
                } else if (q != null) {
                    totalQty = totalQty.add(q);
                }
            } else if (SemiFinishedInventoryTransaction.TxnType.OUT.equals(txn.getTxnType())) {
                // OUT 不影响 unitCost 计算 (仅影响 availableQuantity)
            }
        }

        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            // 无剩余有效库存
            sfi.setAvailableQuantity(BigDecimal.ZERO);
            sfi.setUnitCost(null);
            sfi.setAccumulatedCost(null);
            sfi.setStatus(SemiFinishedInventory.Status.DEPLETED);
        } else {
            sfi.setAvailableQuantity(totalQty.max(BigDecimal.ZERO));
            if (weightedCost.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal newUnitCost = weightedCost.divide(totalQty, 4, RoundingMode.HALF_UP);
                sfi.setUnitCost(newUnitCost);
                sfi.setAccumulatedCost(weightedCost.setScale(2, RoundingMode.HALF_UP));
            }
            sfi.setStatus(totalQty.compareTo(BigDecimal.ZERO) > 0
                    ? SemiFinishedInventory.Status.AVAILABLE
                    : SemiFinishedInventory.Status.DEPLETED);
        }
        wipRepo.save(sfi);
    }
}
