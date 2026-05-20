package com.cretas.aims.service;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.WorkerDailyEfficiency;
import com.cretas.aims.repository.WorkerDailyEfficiencyRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Sprint 5 Track E (M-WAGE-INTEGRATION-1): 生产 → 工资 自动 trigger.
 *
 * <p><b>背景</b>: Round 12 §C.7 X3 finding — HJ 生产工序完工 auto-trigger 计件 record → 工资计算.
 * Cretas H-WAGE ship 完整 (PayrollRecord + WageCalculationService + PieceRateRule + WorkerDailyEfficiency)
 * 但 ProcessingServiceImpl.submitTeamBatchReport / workerCheckout 完工后 ⚠️ 不创建 WorkerDailyEfficiency
 * 记录 — 计件工资 record 是手动. F006 卤制品 计件场景痛点.
 *
 * <p><b>本服务</b>: 调用方 (ProcessingServiceImpl) 完工后 invoke {@link #recordPieceWage} —
 * 自动 upsert {@link WorkerDailyEfficiency} 行, 携带 sourceBatchId linkno 反查.
 *
 * <p><b>MVP slice (Sprint 5 W1)</b>:
 * <ul>
 *   <li>仅触发 efficiency 行 (基础数据), 计件工资 calculation 沿用 {@link WageCalculationService#calculatePieceRateWage}</li>
 *   <li>月底批量生成 PayrollRecord 沿用 {@link WageCalculationService#generateFactoryPayroll}</li>
 *   <li>frontend (我的工资 view "本月计件明细") defer Sprint 6</li>
 *   <li>WagePolicy 配置 page (admin UI) defer Sprint 6 — Cretas 已有 PieceRateRule entity, Sprint 5 仅打通 trigger 链路</li>
 * </ul>
 *
 * <p><b>幂等性 (per 防呆 Rule 4)</b>: 同 worker + 同日期 + 同 process stage → upsert (累加 piece count),
 * 不重复创建.
 *
 * <p><b>错误处理 (per concurrent-edit-safety + 不阻塞主流程模式)</b>: 任何异常仅 log warn, 不 throw,
 * 避免阻塞生产报工主流程 (与 BatchCompletedEvent / WIP 释放相同的防御模式).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WageRecordTriggerService {

    private final WorkerDailyEfficiencyRepository workerDailyEfficiencyRepository;
    private final UserRepository userRepository;

    /**
     * 记录工人计件产出 → trigger WorkerDailyEfficiency 行 (向后兼容签名).
     *
     * @param factoryId         工厂 ID
     * @param batch             生产批次 (来源 linkno)
     * @param workerId          工人 ID
     * @param pieceCount        本次完成件数 (合格 + 不合格)
     * @param goodCount         合格件数 (null 时默认 = pieceCount)
     * @return upsert 后的 efficiency 记录, 失败时返回空 Optional
     */
    @Transactional
    public Optional<WorkerDailyEfficiency> recordPieceWage(String factoryId, ProductionBatch batch,
                                                            Long workerId, int pieceCount, Integer goodCount) {
        return recordPieceWage(factoryId, batch, workerId, pieceCount, goodCount, 0);
    }

    /**
     * 记录工人计件产出 → trigger WorkerDailyEfficiency 行 (含工时累加).
     *
     * @param factoryId         工厂 ID
     * @param batch             生产批次 (来源 linkno)
     * @param workerId          工人 ID
     * @param pieceCount        本次完成件数 (合格 + 不合格)
     * @param goodCount         合格件数 (null 时默认 = pieceCount)
     * @param workMinutes       本次工时 (分钟, ≤0 时不累加工时, 仅累加件数)
     * @return upsert 后的 efficiency 记录, 失败时返回空 Optional
     */
    @Transactional
    public Optional<WorkerDailyEfficiency> recordPieceWage(String factoryId, ProductionBatch batch,
                                                            Long workerId, int pieceCount,
                                                            Integer goodCount, int workMinutes) {
        if (factoryId == null || factoryId.isBlank() || workerId == null || pieceCount <= 0) {
            log.debug("[WageRecordTrigger] 跳过: factoryId={}, workerId={}, pieceCount={}",
                    factoryId, workerId, pieceCount);
            return Optional.empty();
        }

        try {
            LocalDate today = LocalDate.now();
            String processStageType = batch != null ? batch.getProductTypeId() : null;

            // 1. 查 worker 姓名 (冗余存储以加速报表)
            String workerName = userRepository.findById(workerId)
                    .map(u -> u.getFullName() != null ? u.getFullName() : u.getUsername())
                    .orElse("未知工人");

            // 2. 幂等 upsert: 同 worker + 同日期 + 同 process stage → 累加 piece count
            Optional<WorkerDailyEfficiency> existing = workerDailyEfficiencyRepository
                    .findByFactoryIdAndWorkerIdAndWorkDateAndProcessStageType(
                            factoryId, workerId, today, processStageType);

            WorkerDailyEfficiency efficiency;
            if (existing.isPresent()) {
                efficiency = existing.get();
                int prevPieceCount = efficiency.getTotalPieceCount() != null ? efficiency.getTotalPieceCount() : 0;
                int prevGoodCount = efficiency.getQualifiedCount() != null ? efficiency.getQualifiedCount() : 0;
                int newPieceCount = prevPieceCount + pieceCount;
                int newGoodCount = prevGoodCount + (goodCount != null ? goodCount : pieceCount);
                efficiency.setTotalPieceCount(newPieceCount);
                efficiency.setQualifiedCount(newGoodCount);

                // 累加工时 (若 workMinutes > 0)
                if (workMinutes > 0) {
                    int prevWorkMinutes = efficiency.getEffectiveWorkMinutes() != null
                            ? efficiency.getEffectiveWorkMinutes() : 0;
                    efficiency.setEffectiveWorkMinutes(prevWorkMinutes + workMinutes);
                }

                // sourceBatchId: 已有行不覆盖 (保留首次来源 batch 作为 linkno)
                log.info("[WageRecordTrigger] upsert efficiency: id={}, worker={}, pieceCount: {}→{}",
                        efficiency.getId(), workerId, prevPieceCount, newPieceCount);
            } else {
                efficiency = WorkerDailyEfficiency.builder()
                        .factoryId(factoryId)
                        .workerId(workerId)
                        .workerName(workerName)
                        .workDate(today)
                        .processStageType(processStageType)
                        .productTypeId(processStageType)
                        .totalPieceCount(pieceCount)
                        .qualifiedCount(goodCount != null ? goodCount : pieceCount)
                        .defectCount(goodCount != null ? Math.max(0, pieceCount - goodCount) : 0)
                        .effectiveWorkMinutes(Math.max(0, workMinutes))
                        .sourceBatchId(batch != null ? batch.getId() : null)
                        .build();
                log.info("[WageRecordTrigger] 新增 efficiency: worker={}, sourceBatchId={}, pieceCount={}, workMinutes={}",
                        workerId, batch != null ? batch.getId() : null, pieceCount, workMinutes);
            }

            // 3. 保存 (efficiency.calculateEfficiencyMetrics() 在 @PrePersist/@PreUpdate 自动跑)
            return Optional.of(workerDailyEfficiencyRepository.save(efficiency));

        } catch (Exception e) {
            // 不阻塞主流程 (与 BatchCompletedEvent / WIP 释放相同的防御模式)
            log.warn("[WageRecordTrigger] 记录失败 (不影响生产报工主流程): factoryId={}, workerId={}, pieceCount={}, err={}",
                    factoryId, workerId, pieceCount, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 简化签名: 不传 batch 时仅记 piece count (例: AI 自动检测计件场景, 暂无 batch 关联).
     */
    @Transactional
    public Optional<WorkerDailyEfficiency> recordPieceWageWithoutBatch(String factoryId, Long workerId,
                                                                        int pieceCount, String processStageType) {
        if (factoryId == null || workerId == null || pieceCount <= 0) {
            return Optional.empty();
        }
        // 复用主路径, batch=null → sourceBatchId=null
        ProductionBatch placeholderBatch = null;
        Optional<WorkerDailyEfficiency> result = recordPieceWage(factoryId, placeholderBatch, workerId, pieceCount, null);
        // 手动设 processStageType (因为 batch=null 时主路径里 processStageType 取 batch.getProductTypeId() = null)
        if (result.isPresent() && processStageType != null) {
            WorkerDailyEfficiency r = result.get();
            if (r.getProcessStageType() == null) {
                r.setProcessStageType(processStageType);
                return Optional.of(workerDailyEfficiencyRepository.save(r));
            }
        }
        return result;
    }
}
