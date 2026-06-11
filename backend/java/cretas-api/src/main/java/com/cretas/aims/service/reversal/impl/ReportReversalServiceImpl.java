package com.cretas.aims.service.reversal.impl;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.entity.enums.NotificationType;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.reversal.ReportReversalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * <p><b>SP12 快速撤回通道</b> (Fast Path):
 * <p>满足以下全部条件时，跳过审批直接执行撤回 (仍经过 G1/G2/G3 守卫，不绕过安全检查):
 * <ul>
 *   <li>提交人 == 本次操作人 (只有本人能快速撤回自己的报工)</li>
 *   <li>G1 通过 — 无下游领用消耗 (库存安全绝不妥协)</li>
 *   <li>报工提交时间 ≤ {@value #FAST_PATH_WINDOW_MINUTES} 分钟内 (短时间窗口, 防止滥用)</li>
 * </ul>
 * 不满足任一条件 → 退回完整审批流 (PENDING)，同时附上诚实提示 (为何需要审批)。
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
    // BUG-4 修复: 批次加载用户姓名 (单次 IN query, 无 N+1)
    private final UserRepository userRepository;
    // BUG-1 联动: 整单撤回需复位 WorkProcessTask (COMPLETED → PENDING), 否则任务卡终态无法重报
    private final WorkProcessTaskRepository workProcessTaskRepository;
    // W6 #27: 驳回撤回申请时通知申请人 (App 内通知)
    private final NotificationService notificationService;

    /**
     * 断点2 修复 (2026-06-11, Fable E2E): 撤回时清 SalesOrderItem.costUnitPrice (置 null),
     * 让重报新成本事件能重新回填 (OrderCostBackfillListener 是 write-once — 仅 null 时写).
     * 否则撤回后 SO 挂着被撤回报工的成本, 重报因字段非 null 被跳过 → 永不刷新, 脏数据不可自愈.
     *
     * <p>field-inject (required=false): 不改 @RequiredArgsConstructor 生成的构造器,
     * 不破坏既有 ReportReversalListNamesTest 的显式构造调用. 未注入 (老 context / 单测未提供)
     * → 跳过清字段 (旧行为, 不抛异常).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionPlanRepository productionPlanRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SalesOrderItemRepository salesOrderItemRepository;

    /**
     * SP12 快速撤回时间窗口 (分钟)。
     * 在此窗口内, 本人提交 + 无下游消费 → 快速撤回无需审批。
     * 超出此窗口 → 退回完整审批流。
     */
    static final int FAST_PATH_WINDOW_MINUTES = 5;

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

        // 检查是否有报工数据
        List<ProductionReport> reports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
        boolean hasReports = !reports.isEmpty();

        // ---- SP12 快速撤回路径判断 ----
        // 条件: 本人提交 + 无下游消费(G1已通过) + 最新报工在时间窗口内
        // 注意: G1/G2 守卫已经在上方通过，快速撤回不绕过任何安全守卫，只免审批人环节。
        String fastPathDeniedReason = null;
        if (hasReports) {
            fastPathDeniedReason = resolveFastPathDeniedReason(submittedBy, reports);
        }

        boolean isFastPath = hasReports && fastPathDeniedReason == null;
        // 无报工 → 直通(原逻辑) | 快速撤回通过 → 直接执行 | 否则 → PENDING
        ReportReversalLog.ReversalStatus initialStatus;
        if (!hasReports || isFastPath) {
            initialStatus = ReportReversalLog.ReversalStatus.DONE;
        } else {
            initialStatus = ReportReversalLog.ReversalStatus.PENDING;
        }

        // 若退回审批, 在 reason 中附上诚实提示 (防呆: 告诉操作员为何需要审批)
        String effectiveReason = reason;
        if (initialStatus == ReportReversalLog.ReversalStatus.PENDING && fastPathDeniedReason != null) {
            effectiveReason = (reason == null || reason.isBlank())
                    ? fastPathDeniedReason
                    : reason + " | 需审批原因: " + fastPathDeniedReason;
        }

        ReportReversalLog log_ = ReportReversalLog.builder()
                .factoryId(factoryId)
                .batchId(batchId)
                .planId(batch.getProductionPlanId())
                .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                .submittedBy(submittedBy)
                .reason(effectiveReason)
                .status(initialStatus)
                .fastPath(isFastPath)
                .build();
        ReportReversalLog saved = reversalLogRepo.save(log_);

        // 无报工 或 快速撤回 → 直通执行 (DONE)
        if (!hasReports || isFastPath) {
            executeReversal(saved.getId(), factoryId);
        }

        log.info("[SP2] submitReversal batchId={} status={} fastPath={} logId={}",
                batchId, initialStatus, isFastPath, saved.getId());
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

        // ①.5 复位该批次工序任务 (BUG-1 联动修复的撤回路径):
        // OUTPUT 报工提交现在会把 WorkProcessTask 置 COMPLETED(终态) — 整单撤回软删了全部
        // YIELD 报工后, 若任务留在 COMPLETED, RN 任务列表不再显示该道 → 无法重报, 撤回流程被打断。
        // 故: COMPLETED → PENDING + 清 completedBy/completedAt; 所有任务 actualQuantity 复位 null
        // (其派生源 YIELD 报工已全部软删)。SKIPPED/CANCELLED 是独立决策, 不动。
        List<WorkProcessTask> batchTasks = workProcessTaskRepository
                .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(factoryId, batchId);
        for (WorkProcessTask t : batchTasks) {
            if (WorkProcessTask.Status.COMPLETED == t.getStatus()) {
                t.setStatus(WorkProcessTask.Status.PENDING);
                t.setCompletedBy(null);
                t.setCompletedAt(null);
            }
            t.setActualQuantity(null);
        }
        if (!batchTasks.isEmpty()) {
            workProcessTaskRepository.saveAll(batchTasks);
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

        // ③.5 断点2 修复: 清回填的 SalesOrderItem.costUnitPrice (置 null), 让重报能重新回填.
        // 范围: 本批次 productTypeId 在对应计划全部关联 SO (sourceOrderIds, 兼容遗留主 SO) 的行项目.
        // 在同一 @Transactional 内执行 (禁 fail-soft — 撤回是已有事务, 清字段失败应整体回滚).
        // repo 未注入 (老 context) → 跳过 (旧行为). 详见字段注释.
        clearBackfilledCostUnitPrice(factoryId, batchId, log_.getPlanId());

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
        // W6 #27: 4 眼原则 — 审批人不能与提交人相同 (防自批漏洞)。
        // 撤回是高风险写操作 (软删报工 + 回滚库存均价), 提交人自己批准 = 无监督。
        // 参考 ArApServiceImpl 4-eyes 范式 (审批人 ≠ 提交人)。
        if (log_.getSubmittedBy() != null && log_.getSubmittedBy().equals(approvedBy)) {
            throw new BusinessException(403, "审批人不能与提交人相同 (4 眼原则)")
                    .withCode("SELF_APPROVAL_FORBIDDEN")
                    .withHint("请使用其他主管账号审批此撤回申请")
                    .withHintTarget("审批人");
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
        Long submitter = log_.getSubmittedBy();
        log_.setApprovedBy(approvedBy);
        log_.setApprovedAt(LocalDateTime.now());
        log_.setStatus(ReportReversalLog.ReversalStatus.REJECTED);
        if (reason != null && !reason.isBlank()) {
            log_.setReason(log_.getReason() + " | 拒绝原因: " + reason);
        }
        reversalLogRepo.save(log_);
        log.info("[SP2] rejectReversal logId={} approvedBy={}", logId, approvedBy);

        // W6 #27: 驳回后通知申请人 (App 内通知)。fail-soft — 通知失败不回滚驳回事务
        // (reversal-log 已置 REJECTED 是核心结果, 通知只是 side-effect)。审批人自己驳回则不通知。
        if (submitter != null && !submitter.equals(approvedBy)) {
            try {
                String reasonText = (reason != null && !reason.isBlank()) ? reason : "未填写";
                notificationService.sendNotification(
                        log_.getFactoryId(),
                        submitter,
                        "报工撤回申请被驳回",
                        String.format("您对批次 %d 提交的报工撤回申请已被驳回。驳回原因: %s",
                                log_.getBatchId(), reasonText),
                        NotificationType.WARNING,
                        "REVERSAL",
                        String.valueOf(logId));
            } catch (Exception e) {
                log.warn("[SP2] rejectReversal 通知申请人失败 logId={} submitter={}", logId, submitter, e);
            }
        }
    }

    @Override
    public List<ReportReversalLog> listReversals(String factoryId, String status) {
        List<ReportReversalLog> logs;
        if (status == null || status.isBlank()) {
            // 返回所有状态 — 使用 no-status 方法, 避免传 null 给 enum 参数
            logs = reversalLogRepo.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId);
        } else {
            ReportReversalLog.ReversalStatus statusEnum;
            try {
                statusEnum = ReportReversalLog.ReversalStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "无效的状态值: " + status + "，合法值: PENDING/APPROVED/DONE/REJECTED");
            }
            logs = reversalLogRepo
                    .findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId, statusEnum);
        }
        // BUG-4 修复: 批量加载用户姓名 (一次 IN query, 禁止 N+1)。
        // 收集所有非 null userId，去重后一次性 findAllById，构建 id→User map 填充 @Transient 姓名字段。
        if (!logs.isEmpty()) {
            List<Long> userIds = logs.stream()
                    .flatMap(l -> Stream.of(l.getSubmittedBy(), l.getApprovedBy()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            if (!userIds.isEmpty()) {
                Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
                for (ReportReversalLog l : logs) {
                    if (l.getSubmittedBy() != null) {
                        User u = userMap.get(l.getSubmittedBy());
                        l.setSubmittedByName(u != null ? u.getFullName() : null);
                    }
                    if (l.getApprovedBy() != null) {
                        User u = userMap.get(l.getApprovedBy());
                        l.setApprovedByName(u != null ? u.getFullName() : null);
                    }
                }
            }
        }
        return logs;
    }

    // ==================== 私有辅助 ====================

    /**
     * 断点2 修复 (2026-06-11): 撤回时清回填的 {@code SalesOrderItem.costUnitPrice}.
     *
     * <p><b>为什么必须清</b>: {@link com.cretas.aims.event.listener.OrderCostBackfillListener}
     * 是 write-once (仅 costUnitPrice == null 时写). 撤回若不清此字段, 重报新成本事件
     * 因字段非 null 被跳过 → SO 永远挂着被撤回报工的旧成本, 不可自愈. 清 null 后,
     * 重报触发的 ProductionCostUpdatedEvent 会重新回填新成本 → 自愈链复活.
     *
     * <p><b>范围</b>: 仅清本批次 productTypeId 在对应计划全部关联 SO (sourceOrderIds,
     * 与断点1 回填同源) 的行项目 — 不误伤同 SO 其他产品的成本.
     *
     * <p><b>事务/异常</b>: 在 {@link #executeReversal} 的单一 @Transactional 内执行,
     * 不 try/catch 吞异常 (禁 fail-soft, 失败应整体回滚). repo 未注入 → 跳过 (向后兼容).
     *
     * @param factoryId 工厂ID
     * @param batchId   被撤回的生产批次ID (取其 productTypeId)
     * @param planId    撤回日志记录的计划ID (取其 sourceOrderIds)
     */
    private void clearBackfilledCostUnitPrice(String factoryId, Long batchId, String planId) {
        if (productionPlanRepository == null || salesOrderItemRepository == null) {
            log.debug("[SP2] clearBackfilledCostUnitPrice: repo 未注入, 跳过 batchId={}", batchId);
            return;
        }
        if (planId == null) {
            return;
        }

        // 批次 → productTypeId
        ProductionBatch batch = batchRepo.findByIdAndFactoryId(batchId, factoryId).orElse(null);
        if (batch == null || batch.getProductTypeId() == null) {
            log.debug("[SP2] clearBackfilledCostUnitPrice: 批次或 productTypeId 缺失, 跳过 batchId={}", batchId);
            return;
        }
        String productTypeId = batch.getProductTypeId();

        // 计划 → 关联 SO 列表 (与断点1 回填同源: 优先 sourceOrderIds, 兼容遗留主 SO)
        ProductionPlan plan = productionPlanRepository.findById(planId).orElse(null);
        if (plan == null) {
            return;
        }
        List<String> targetOrderIds = new ArrayList<>();
        List<String> srcIds = plan.getSourceOrderIds();
        if (srcIds != null && !srcIds.isEmpty()) {
            for (String id : srcIds) {
                if (id != null && !targetOrderIds.contains(id)) {
                    targetOrderIds.add(id);
                }
            }
        }
        if (targetOrderIds.isEmpty() && plan.getSourceOrderId() != null) {
            targetOrderIds.add(plan.getSourceOrderId());
        }
        if (targetOrderIds.isEmpty()) {
            return;
        }

        int cleared = 0;
        for (String orderId : targetOrderIds) {
            List<SalesOrderItem> items = salesOrderItemRepository.findBySalesOrderId(orderId);
            for (SalesOrderItem item : items) {
                if (productTypeId.equals(item.getProductTypeId()) && item.getCostUnitPrice() != null) {
                    item.setCostUnitPrice(null);
                    salesOrderItemRepository.save(item);
                    cleared++;
                }
            }
        }
        if (cleared > 0) {
            log.info("[SP2] 撤回清 costUnitPrice → salesOrderIds={}, productTypeId={}, 共{}行 (自愈链待重报回填)",
                    targetOrderIds, productTypeId, cleared);
        }
    }

    /**
     * SP12 快速撤回资格检查。
     *
     * <p>返回 null 表示满足快速撤回全部条件 (提交人本人 + 报工在时间窗口内)。
     * 返回非 null 字符串表示不满足快速撤回，内容是诚实的拒绝原因 (展示给操作员)。
     *
     * <p><b>安全边界</b>: 本方法只判断"是否需要人审"，不影响 G1/G2 守卫。
     * 调用前 G1/G2 守卫必须已通过。
     *
     * @param submittedBy 本次提交人 userId
     * @param reports     该批次的报工列表 (非空)
     * @return null = 快速撤回放行; 非 null = 原因说明，退回完整审批
     */
    public String resolveFastPathDeniedReason(Long submittedBy, List<ProductionReport> reports) {
        // 条件1: 本人提交。submittedBy 为 null 时 (理论上不应发生) 退回审批
        if (submittedBy == null) {
            return "提交人未知，需要主管审批";
        }

        // 条件2: 最新报工必须在快速撤回时间窗口内
        // 取所有报工中 createdAt 最晚的，作为"最后一笔报工时间"判断
        LocalDateTime latestReportTime = reports.stream()
                .map(r -> r.getCreatedAt())
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        if (latestReportTime == null) {
            // 无 createdAt 信息 → 无法判断时间窗口，保守退回审批
            return "报工时间信息缺失，需要主管审批";
        }

        long minutesSinceReport = Duration.between(latestReportTime, LocalDateTime.now()).toMinutes();
        if (minutesSinceReport > FAST_PATH_WINDOW_MINUTES) {
            return String.format("报工提交已超 %d 分钟 (当前已过 %d 分钟)，超出快速撤回时限，需要主管审批",
                    FAST_PATH_WINDOW_MINUTES, minutesSinceReport);
        }

        // 条件3: 所有报工的 workerId 须等于 submittedBy (本人只能快速撤回自己的报工)
        boolean allBySubmitter = reports.stream().allMatch(r ->
                submittedBy.equals(r.getWorkerId()));
        if (!allBySubmitter) {
            return "批次含有其他操作员的报工记录，需要主管审批";
        }

        return null; // 全部条件满足 → 快速撤回放行
    }

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
            sfi.setProducedQuantity(BigDecimal.ZERO);  // BUG-R1 fix: IN 净额归零
            sfi.setAvailableQuantity(BigDecimal.ZERO);
            sfi.setUnitCost(null);
            sfi.setAccumulatedCost(null);
            sfi.setStatus(SemiFinishedInventory.Status.DEPLETED);
        } else {
            sfi.setProducedQuantity(totalQty);  // BUG-R1 fix: 更新 IN 净额
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
