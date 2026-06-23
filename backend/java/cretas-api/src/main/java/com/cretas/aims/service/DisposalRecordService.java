package com.cretas.aims.service;

import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.DisposalRecordRepository;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 报废记录服务层实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisposalRecordService implements IDisposalRecordService {

    private final DisposalRecordRepository disposalRecordRepository;

    /** 报损审批通过时扣减原料批次库存 + 写 MaterialBatchAdjustment 留痕。 */
    private final MaterialBatchRepository materialBatchRepository;
    private final MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;

    /** 报损审批通过时扣减成品(生产)批次库存。 */
    private final ProductionBatchRepository productionBatchRepository;

    /**
     * R12: 成品报损扣减可售成品库存 (FinishedGoodsBatch) + 写调整日志留痕。
     * 镜像 SalesServiceImpl.adjustFinishedGoodsQuantity 的 SCRAP 调整语义 (减 producedQuantity)。
     */
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final FinishedGoodsAdjustmentLogRepository finishedGoodsAdjustmentLogRepository;

    /** SP12 §5.3: 可选工作流引擎（无配置时降级运行，不抛 NPE）。 */
    @Autowired(required = false)
    private WorkflowEngineService workflowEngine;

    /**
     * 创建报废记录
     */
    @Override
    @Transactional
    public DisposalRecord createDisposalRecord(DisposalRecord record) {
        // 设置默认值
        if (record.getDisposalDate() == null) {
            record.setDisposalDate(LocalDateTime.now());
        }
        if (record.getIsApproved() == null) {
            record.setIsApproved(false);
        }
        log.info("创建报废记录: 工厂={}, 类型={}", record.getFactoryId(), record.getDisposalType());
        return disposalRecordRepository.save(record);
    }

    /**
     * 多租户隔离守卫: 报废记录必须属于路径 factoryId, 否则 403。
     *
     * <p>修复 (审计 round2): 这些按 id 加载记录的写/读方法此前不校验记录归属工厂 →
     * JwtAuthInterceptor 只验用户拥有路径 factoryId, 但记录 id 可枚举(Long 自增) →
     * F006 用户可读/改/审批/删除/提交其它工厂的报废记录 (跨租户)。同
     * feedback_interceptor_excluded_path_factory_guard_gap。</p>
     */
    private void assertSameFactory(DisposalRecord record, String factoryId) {
        if (record.getFactoryId() == null || !record.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该报废记录")
                    .withHint("该记录不属于当前工厂");
        }
    }

    /**
     * 报废审批角色守卫 (F-BUG-5)。
     *
     * <p>转录硬约束: 仓库报损/报废须财务审批。此前 controller 仅 {@code @RequirePermission
     * ("warehouse:read_write")} → 仓管员可自批报废扣库存。改为强制财务/工厂管理员角色,
     * 对齐 {@link com.cretas.aims.service.factory.impl.FactoryStocktakeServiceImpl}。
     *
     * <p>角色码为小写 (JWT/request attribute "role" 携带 factory_super_admin 等)。
     */
    private static final java.util.Set<String> DISPOSAL_APPROVAL_ROLES = java.util.Set.of(
            "finance_manager", "factory_super_admin", "platform_super_admin", "platform_admin");

    private void assertApprovalRole(String requestRole) {
        String normalized = requestRole == null ? "" : requestRole.toLowerCase();
        if (!DISPOSAL_APPROVAL_ROLES.contains(normalized)) {
            throw new BusinessException(403,
                    "报废审批需要财务经理或工厂管理员角色，当前角色：" + requestRole)
                    .withHint("请联系财务经理 (finance_manager) 或工厂超管审批");
        }
    }

    /**
     * 审批报废记录 (通过 → APPROVED, <b>真实扣减库存 + 记损失金额</b>)。
     *
     * <p>转录硬约束 (6.9 行337 "报损成本增加" / 行520 "所有库存变动必须财务审批"):
     * 报损审批通过应真扣对应批次库存并写入实际损失金额 {@code actualLoss}。此前仅置
     * {@code approvalStatus=APPROVED} (前端 dialog 与 javadoc 声称的 "扣减库存" 是 aspirational)。
     *
     * <p>按关联批次类型分流扣减:
     * <ul>
     *   <li>{@code materialBatchId} 非空 (原料报损) → MaterialBatch 按可用量 currentQuantity
     *       (= receiptQuantity - usedQuantity - reservedQuantity) 把关, 增 usedQuantity 扣减
     *       (与正常领料 {@code MaterialBatchServiceImpl.useBatchQuantity} 一致) +
     *       写 MaterialBatchAdjustment 负数留痕, 单价取 batch.unitPrice。</li>
     *   <li>{@code finishedGoodsBatchId} 非空 (成品报损, R12) → FinishedGoodsBatch 按可用量
     *       getAvailableQuantity() (= producedQuantity - shippedQuantity - reservedQuantity) 把关,
     *       减 producedQuantity 扣减 (镜像 SalesServiceImpl.adjustFinishedGoodsQuantity 的 SCRAP
     *       调整语义) + 写 FinishedGoodsAdjustmentLog SCRAP 留痕, 单价取 batch.unitPrice, 扣空标 DEPLETED。</li>
     *   <li>{@code productionBatchId} 非空但无 {@code finishedGoodsBatchId} (旧数据) → H2 安全阻止
     *       (422): ProductionBatch.quantity 是产出额定值非可售库存, 无法可靠定位 FG 批次 →
     *       不扣错表, 抛 422 明确阻止 (向后兼容)。</li>
     * </ul>
     * 库存不足 → 409 阻止审批 (不允许扣成负)。幂等: 仅 PENDING 可审批, 扣库存只发生一次。
     *
     * <p>F-BUG-4: 审批人 {@code approverId} / {@code approverName} 由调用方 (controller)
     * 从 JWT token 取, 不再信任请求体。F-BUG-5: 强制财务/工厂管理员角色。
     */
    @Override
    @Transactional
    public DisposalRecord approveDisposal(String factoryId, Long id, Integer approverId,
            String approverName, String requestRole) {
        assertApprovalRole(requestRole);  // F-BUG-5: 财务角色守卫
        DisposalRecord record = disposalRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "报废记录不存在: " + id));
        assertSameFactory(record, factoryId);  // 多租户隔离

        // 幂等防重 (2026-06-12, Codex Gate2): 已审批/已拒绝 (终态) 不可重复操作.
        // 审批触发库存扣减, 重复 approve 会重复扣库存. 返 409.
        if (!"PENDING".equals(record.getStatus())) {
            throw new BusinessException(409, "报废记录已处理 (" + record.getStatus() + "), 请勿重复操作")
                    .withHint("查看记录状态").withHintTarget("status");
        }

        // 真实扣减库存 + 计算实际损失 (同一事务, 库存不足抛 409 阻止审批)
        BigDecimal actualLoss = applyDisposalToInventory(record, approverId, requestRole);
        record.setActualLoss(actualLoss);

        record.approve(approverId, approverName);
        log.info("审批报废记录: id={}, 审批人={} ({}), 实际损失={}", id, approverName, approverId, actualLoss);
        return disposalRecordRepository.save(record);
    }

    /**
     * 按关联批次类型扣减对应库存, 返回实际损失金额 (数量 × 批次单价)。
     *
     * <p>红线: 复用现有扣减写法 (WastageReportServiceImpl.applyWastageToInventory)。
     * 库存不足 → 409 阻止审批。无关联批次 (仅质检/返工关联, 无 material/production 批次)
     * → 不扣库存, actualLoss 取估损 estimatedLoss 兜底。
     */
    private BigDecimal applyDisposalToInventory(DisposalRecord record, Integer approverId,
            String requestRole) {
        BigDecimal qty = record.getDisposalQuantity();
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(422, "报损数量必须大于0")
                    .withHint("请核实报损数量").withHintTarget("disposalQuantity");
        }
        BigDecimal disposalQty = qty.setScale(2, RoundingMode.HALF_UP);

        // 原料报损 → 扣 MaterialBatch (按可用量 currentQuantity 把关, 增 usedQuantity 扣减) + 写 adjustment 负数
        if (record.getMaterialBatchId() != null && !record.getMaterialBatchId().isBlank()) {
            MaterialBatch batch = materialBatchRepository.findById(record.getMaterialBatchId())
                    .orElseThrow(() -> new BusinessException(404,
                            "报损批次不存在: " + record.getMaterialBatchId()));
            // 跨租户: 批次必须属于同工厂
            if (batch.getFactoryId() == null || !batch.getFactoryId().equals(record.getFactoryId())) {
                throw new BusinessException(403, "无权操作该批次")
                        .withHint("批次不属于当前工厂");
            }

            // H1 (审计 round2): 库存不足判定 + 扣减必须用真实可用量
            // currentQuantity = receiptQuantity - usedQuantity - reservedQuantity (MaterialBatch.getCurrentQuantity),
            // 不能只看 receiptQuantity —— 否则会扣进别人已领用(usedQuantity)/已预留(reservedQuantity)的量,
            // 把 currentQuantity 扣成负数(库存错乱). 扣减方式与正常领料一致(MaterialBatchServiceImpl.useBatchQuantity):
            // 增 usedQuantity 而非减 receiptQuantity, 使 currentQuantity 正确反映 + 与领料模型一致.
            // 注: WastageReportServiceImpl 此前有同款 receiptQuantity-only gate 隐患, 已修
            //     (applyWastageToInventory 改用 currentQuantity 把关 + 增 usedQuantity, 与本范式一致).
            BigDecimal available = batch.getCurrentQuantity() != null
                    ? batch.getCurrentQuantity() : BigDecimal.ZERO;
            if (available.subtract(disposalQty).signum() < 0) {
                throw new BusinessException(409,
                        "报损数量 " + disposalQty + " 超过批次可用库存 " + available + ", 无法报损")
                        .withHint("请核实报损数量，当前批次可用库存: " + available)
                        .withHintTarget("disposalQuantity");
            }

            BigDecimal usedBefore = batch.getUsedQuantity() != null
                    ? batch.getUsedQuantity() : BigDecimal.ZERO;
            BigDecimal usedAfter = usedBefore.add(disposalQty);
            BigDecimal availableAfter = available.subtract(disposalQty);

            // adjustment 留痕: before/after 取可用量(currentQuantity), 负数变更, 与扣减语义一致
            MaterialBatchAdjustment adj = new MaterialBatchAdjustment();
            adj.setId(UUID.randomUUID().toString());
            adj.setMaterialBatchId(batch.getId());
            adj.setAdjustmentType("DISPOSAL");
            adj.setQuantityBefore(available.setScale(2, RoundingMode.HALF_UP));
            adj.setAdjustmentQuantity(disposalQty.negate()); // 负数
            adj.setQuantityAfter(availableAfter.setScale(2, RoundingMode.HALF_UP));
            adj.setReason("报损审批 [id=" + record.getId() + "] 类型: " + record.getDisposalType()
                    + (record.getDisposalReason() != null ? " - " + record.getDisposalReason() : ""));
            adj.setAdjustmentTime(LocalDateTime.now());
            adj.setAdjustedBy(approverId != null ? approverId.longValue() : null);
            adj.setNotes("disposalRecordId=" + record.getId() + " approverRole=" + requestRole);
            materialBatchAdjustmentRepository.save(adj);

            batch.setUsedQuantity(usedAfter.setScale(2, RoundingMode.HALF_UP));
            batch.setLastUsedAt(LocalDateTime.now());
            materialBatchRepository.save(batch);

            return valuate(disposalQty, batch.getUnitPrice(), record.getEstimatedLoss());
        }

        // 成品报损 (R12) → 优先用 finishedGoodsBatchId 扣减 FinishedGoodsBatch 可售库存.
        //
        // 扣减方式: 镜像 SalesServiceImpl.adjustFinishedGoodsQuantity 的 SCRAP 调整语义 ——
        // 减 producedQuantity (而非加 shippedQuantity). 理由: shippedQuantity 语义是【已发货/已售】,
        // 报损品从未售出, 加 shipped 会虚增发货/销售分析口径; 减 producedQuantity = 成品库存"凭空消失"
        // 的正确语义 (同 adjust SCRAP), 且写 FinishedGoodsAdjustmentLog(referenceType=SCRAP) 可审计.
        // 可用量 getAvailableQuantity() = producedQuantity - shippedQuantity - reservedQuantity 把关,
        // 不足 → 409 (不扣成负, 不动已发/已预留部分). 扣空 → 标 DEPLETED.
        if (record.getFinishedGoodsBatchId() != null && !record.getFinishedGoodsBatchId().isBlank()) {
            FinishedGoodsBatch fgBatch = finishedGoodsBatchRepository
                    .findById(record.getFinishedGoodsBatchId())
                    .orElseThrow(() -> new BusinessException(404,
                            "报损成品批次不存在: " + record.getFinishedGoodsBatchId()));
            // 跨租户: 批次必须属于同工厂
            if (fgBatch.getFactoryId() == null || !fgBatch.getFactoryId().equals(record.getFactoryId())) {
                throw new BusinessException(403, "无权操作该成品批次")
                        .withHint("批次不属于当前工厂");
            }

            BigDecimal available = fgBatch.getAvailableQuantity() != null
                    ? fgBatch.getAvailableQuantity() : BigDecimal.ZERO;
            if (available.subtract(disposalQty).signum() < 0) {
                throw new BusinessException(409,
                        "报损数量 " + disposalQty + " 超过成品批次可用库存 " + available + ", 无法报损")
                        .withHint("请核实报损数量，当前成品批次可用库存: " + available)
                        .withHintTarget("disposalQuantity");
            }

            BigDecimal beforeProduced = fgBatch.getProducedQuantity() != null
                    ? fgBatch.getProducedQuantity() : BigDecimal.ZERO;
            BigDecimal afterProduced = beforeProduced.subtract(disposalQty);

            // 调整日志留痕 (referenceType=SCRAP, 负数变更, before/after 取 producedQuantity, 与扣减语义一致)
            FinishedGoodsAdjustmentLog logEntry = FinishedGoodsAdjustmentLog.builder()
                    .factoryId(fgBatch.getFactoryId())
                    .batchId(fgBatch.getId())
                    .adjustmentQuantity(disposalQty.negate()) // 负数
                    .beforeProduced(beforeProduced)
                    .afterProduced(afterProduced)
                    .reason("报损审批 [id=" + record.getId() + "] 类型: " + record.getDisposalType()
                            + (record.getDisposalReason() != null ? " - " + record.getDisposalReason() : ""))
                    .referenceType("SCRAP")
                    .operatorId(approverId != null ? approverId.longValue() : null)
                    .build();
            finishedGoodsAdjustmentLogRepository.save(logEntry);

            fgBatch.setProducedQuantity(afterProduced);
            if (fgBatch.isDepleted()) {
                fgBatch.setStatus(FinishedGoodsBatch.Status.DEPLETED);
            }
            finishedGoodsBatchRepository.save(fgBatch);

            return valuate(disposalQty, fgBatch.getUnitPrice(), record.getEstimatedLoss());
        }

        // 成品报损 → H2 (审计 round2): 仅有 productionBatchId 无 finishedGoodsBatchId → 安全阻止, 不扣错表.
        //
        // 此前扣 ProductionBatch.quantity, 但那是生产批次的【产出额定值】(创建时设, 从不被发货/销售扣减,
        // 还有 @Positive 校验, 扣到 0 触发 bean validation 失败). 真正可售成品库存在 FinishedGoodsBatch
        // (producedQuantity/shippedQuantity/reservedQuantity), 发货/调拨扣的是它.
        // 扣 ProductionBatch.quantity = 成品报损不减可售库存 + 污染生产分析(yield-rate 分母) + @Positive 风险.
        //
        // DisposalRecord 仅有 productionBatchId (→ ProductionBatch.id), 无 finishedGoodsBatchId 字段;
        // FinishedGoodsBatch 只经 productionPlanId(计划级, 一对多) 关联, 无法从单个 productionBatch 可靠定位
        // 唯一 FG 批次 (一个计划产出多个 FG 批次, 跨产品/仓库). 可靠定位需 schema 改 (加 finishedGoodsBatchId
        // 字段 + UI 选 FG 批次), 属更大 feature. 当前路径先安全阻止 —— 绝不扣错表污染生产分析.
        if (record.getProductionBatchId() != null && !record.getProductionBatchId().isBlank()) {
            // 校验生产批次存在 + 归属(明确错误信息), 但不扣其 quantity
            Long pbId;
            try {
                pbId = Long.valueOf(record.getProductionBatchId());
            } catch (NumberFormatException e) {
                throw new BusinessException(404,
                        "生产批次ID非法: " + record.getProductionBatchId());
            }
            ProductionBatch batch = productionBatchRepository.findById(pbId)
                    .orElseThrow(() -> new BusinessException(404,
                            "报损生产批次不存在: " + record.getProductionBatchId()));
            if (batch.getFactoryId() == null || !batch.getFactoryId().equals(record.getFactoryId())) {
                throw new BusinessException(403, "无权操作该生产批次")
                        .withHint("批次不属于当前工厂");
            }
            throw new BusinessException(422,
                    "成品报损暂不支持自动扣减库存, 请联系管理员")
                    .withHint("成品报损批次定位待完善 (需关联成品库存批次 FinishedGoodsBatch)")
                    .withHintTarget("productionBatchId");
        }

        // 无关联批次 (仅质检/返工关联) → 不扣库存, 取估损兜底
        log.info("报损记录 id={} 无关联批次, 不扣库存, actualLoss 取估损", record.getId());
        return record.getEstimatedLoss();
    }

    /**
     * 损失金额 = 数量 × 单价; 单价缺失 (含 @PriceSensitive 脱敏或未录) 时回退估损。
     */
    private BigDecimal valuate(BigDecimal qty, BigDecimal unitPrice, BigDecimal estimatedLoss) {
        if (unitPrice == null) {
            return estimatedLoss;
        }
        return qty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 拒绝报废记录 (→ REJECTED, <b>不</b>扣减库存)。
     *
     * <p>F-BUG-2: 此前无 reject 端点, 前端 "拒绝" 错误地走 approve → 反而批准+扣库存。
     * F-BUG-4/5: 审批人从 token 取 + 财务角色守卫。
     */
    @Override
    @Transactional
    public DisposalRecord rejectDisposal(String factoryId, Long id, Integer approverId,
            String approverName, String reason, String requestRole) {
        assertApprovalRole(requestRole);  // F-BUG-5: 财务角色守卫
        DisposalRecord record = disposalRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "报废记录不存在: " + id));
        assertSameFactory(record, factoryId);  // 多租户隔离

        if (!"PENDING".equals(record.getStatus())) {
            throw new BusinessException(409, "报废记录已处理 (" + record.getStatus() + "), 请勿重复操作")
                    .withHint("查看记录状态").withHintTarget("status");
        }

        record.reject(approverId, approverName, reason);
        log.info("拒绝报废记录: id={}, 审批人={} ({}), 原因={}", id, approverName, approverId, reason);
        return disposalRecordRepository.save(record);
    }

    /**
     * 根据ID获取报废记录 (按工厂过滤, 跨租户返空 → 404)
     */
    @Override
    public Optional<DisposalRecord> getById(String factoryId, Long id) {
        return disposalRecordRepository.findById(id)
                .filter(r -> factoryId != null && factoryId.equals(r.getFactoryId()));
    }

    /**
     * 分页查询工厂报废记录
     */
    @Override
    public Page<DisposalRecord> getByFactoryId(String factoryId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "disposalDate"));
        return disposalRecordRepository.findByFactoryId(factoryId, pageable);
    }

    /**
     * 按报废类型分页查询
     */
    @Override
    public Page<DisposalRecord> getByFactoryIdAndType(String factoryId, String disposalType, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "disposalDate"));
        return disposalRecordRepository.findByFactoryIdAndDisposalType(factoryId, disposalType, pageable);
    }

    /**
     * 获取待审批的报废记录
     */
    @Override
    public List<DisposalRecord> getPendingApprovals(String factoryId) {
        return disposalRecordRepository.findPendingApprovals(factoryId);
    }

    /**
     * 按日期范围查询
     */
    @Override
    public List<DisposalRecord> getByDateRange(String factoryId, LocalDateTime startDate, LocalDateTime endDate) {
        return disposalRecordRepository.findByDateRange(factoryId, startDate, endDate);
    }

    /**
     * 获取报废统计
     */
    @Override
    public Map<String, Object> getDisposalStats(String factoryId, LocalDateTime startDate, LocalDateTime endDate) {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("totalCount", disposalRecordRepository.countByFactoryId(factoryId));
        stats.put("pendingCount", disposalRecordRepository.countByFactoryIdAndIsApproved(factoryId, false));
        
        Double totalQuantity = disposalRecordRepository.calculateTotalDisposalQuantity(factoryId, startDate, endDate);
        stats.put("totalQuantity", totalQuantity != null ? totalQuantity : 0.0);
        
        Double totalLoss = disposalRecordRepository.calculateTotalLoss(factoryId, startDate, endDate);
        stats.put("totalLoss", totalLoss != null ? totalLoss : 0.0);
        
        Double recoveryValue = disposalRecordRepository.calculateTotalRecoveryValue(factoryId, startDate, endDate);
        stats.put("recoveryValue", recoveryValue != null ? recoveryValue : 0.0);
        
        Double netLoss = disposalRecordRepository.calculateNetLoss(factoryId, startDate, endDate);
        stats.put("netLoss", netLoss != null ? netLoss : 0.0);

        return stats;
    }

    /**
     * 按类型统计
     */
    @Override
    public List<Object[]> getStatsByType(String factoryId, LocalDateTime startDate, LocalDateTime endDate) {
        return disposalRecordRepository.getDisposalStatsByType(factoryId, startDate, endDate);
    }

    /**
     * 获取可回收报废记录
     */
    @Override
    public List<DisposalRecord> getRecyclableDisposals(String factoryId) {
        return disposalRecordRepository.findRecyclableDisposals(factoryId);
    }

    /**
     * 更新报废记录
     */
    @Override
    @Transactional
    public DisposalRecord updateDisposalRecord(String factoryId, Long id, DisposalRecord updateData) {
        DisposalRecord existing = disposalRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("报废记录不存在: " + id));
        assertSameFactory(existing, factoryId);  // 多租户隔离

        // 只能更新未审批的记录
        if (existing.getIsApproved()) {
            throw new IllegalStateException("已审批的记录不能修改");
        }

        if (updateData.getDisposalQuantity() != null) {
            existing.setDisposalQuantity(updateData.getDisposalQuantity());
        }
        if (updateData.getDisposalType() != null) {
            existing.setDisposalType(updateData.getDisposalType());
        }
        if (updateData.getDisposalReason() != null) {
            existing.setDisposalReason(updateData.getDisposalReason());
        }
        if (updateData.getDisposalMethod() != null) {
            existing.setDisposalMethod(updateData.getDisposalMethod());
        }
        if (updateData.getEstimatedLoss() != null) {
            existing.setEstimatedLoss(updateData.getEstimatedLoss());
        }
        if (updateData.getRecoveryValue() != null) {
            existing.setRecoveryValue(updateData.getRecoveryValue());
        }
        if (updateData.getEvidenceImages() != null) {
            existing.setEvidenceImages(updateData.getEvidenceImages());
        }
        if (updateData.getNotes() != null) {
            existing.setNotes(updateData.getNotes());
        }

        log.info("更新报废记录: id={}", id);
        return disposalRecordRepository.save(existing);
    }

    /**
     * 删除报废记录（软删除）
     */
    @Override
    @Transactional
    public void deleteDisposalRecord(String factoryId, Long id) {
        DisposalRecord record = disposalRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("报废记录不存在: " + id));
        assertSameFactory(record, factoryId);  // 多租户隔离

        if (record.getIsApproved()) {
            throw new IllegalStateException("已审批的记录不能删除");
        }

        record.softDelete();
        disposalRecordRepository.save(record);
        log.info("删除报废记录: id={}", id);
    }

    /**
     * SP12 §5.3: 提交报废记录至审批工作流。
     */
    @Override
    @Transactional
    public String submitForApproval(Long id, String factoryId, Long userId) {
        DisposalRecord record = disposalRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "报废记录不存在: " + id));
        assertSameFactory(record, factoryId);  // 多租户隔离 (此前 factoryId 仅用于 workflow context, 未校验归属)

        // 幂等防重：已在工作流中则 409
        if (record.getWorkflowInstanceId() != null) {
            throw new BusinessException(409, "报废记录已提交工作流: " + record.getWorkflowInstanceId());
        }

        String instanceId = null;
        if (workflowEngine != null) {
            try {
                Map<String, Object> context = Map.of(
                        "disposalId", id,
                        "factoryId", factoryId,
                        "disposalType", record.getDisposalType() != null ? record.getDisposalType() : ""
                );
                ApprovalWorkflowInstance instance = workflowEngine.startWorkflow(
                        factoryId, "DISPOSAL_APPROVAL", String.valueOf(id), context, userId);
                if (instance != null) {
                    instanceId = instance.getId();
                    record.setWorkflowInstanceId(instanceId);
                }
            } catch (Exception e) {
                log.warn("启动报废审批工作流失败 (id={}), 降级处理: {}", id, e.getMessage());
            }
        }

        disposalRecordRepository.save(record);
        log.info("报废记录已提交审批: id={}, instanceId={}", id, instanceId);
        return instanceId;
    }
}
