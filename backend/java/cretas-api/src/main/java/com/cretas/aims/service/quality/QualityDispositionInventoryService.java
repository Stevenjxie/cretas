package com.cretas.aims.service.quality;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionAction;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 质检处置 → 库存桥接服务 (2026-07-05).
 *
 * <p><b>问题</b>: {@code QualityDispositionController} 的「放行 (RELEASE)」原本只写
 * {@code DecisionAuditLog} (记录人的决策) 却<b>从不翻转库存状态</b> —— 一个 QC 失败被 #1229
 * 隔离 (DEFECTIVE) 的批次即使质检经理放行, 依旧停留在 DEFECTIVE 不可售, 放行变成 no-op。
 * 本服务把「人的处置决策」真正<b>执行到库存上</b>。
 *
 * <h3>批次解析 (只动 <b>本次质检</b> 隔离的批次, 不越权释放其他批次)</h3>
 * <ul>
 *   <li><b>成品</b> (镜像 #1229 反向): {@code QualityInspection.productionBatchId →
 *       ProductionBatch.productionPlanId → 该计划下 DEFECTIVE 的 FinishedGoodsBatch}。
 *       #1229 把该计划所有 AVAILABLE 成品置 DEFECTIVE, 放行则把该计划所有 DEFECTIVE 成品置回
 *       AVAILABLE (对称)。</li>
 *   <li><b>原料</b> (仅当质检 material-scoped): {@code QualityInspection.materialBatchId →
 *       MaterialBatch}。</li>
 * </ul>
 *
 * <h3>动作语义 (食品安全: 只有 RELEASE / CONDITIONAL_RELEASE 把批次放回可售)</h3>
 * <table border=1>
 *   <tr><th>动作</th><th>成品 (FG)</th><th>原料 (MaterialBatch)</th></tr>
 *   <tr><td>RELEASE / CONDITIONAL_RELEASE</td><td>DEFECTIVE → AVAILABLE</td><td>→ AVAILABLE</td></tr>
 *   <tr><td>SCRAP</td><td>保持 DEFECTIVE (FG 无 SCRAPPED 状态; 物理报废 + DisposalRecord
 *       走处置/报损 UI 人工流, 本服务不重复处理)</td><td>DEFECTIVE → SCRAPPED (终态)</td></tr>
 *   <tr><td>HOLD / SPECIAL_APPROVAL / REWORK</td><td>不动 (保持隔离)</td><td>不动</td></tr>
 * </table>
 *
 * <h3>审批门禁</h3>
 * 库存翻转只在<b>最终执行</b>时发生 —— 调用方 {@code QualityDispositionRuleServiceImpl.executeDisposition}
 * 仅在<b>直接执行分支</b> (非 approval-initiated 分支) 调用本服务; 未审批的申请 (applyDisposition 只建
 * PENDING 审计日志) 或被拒绝的审批 (approveDisposition approved=false 不调 executeDisposition) <b>不会</b>
 * 触发翻转。
 *
 * <h3>幂等 + 食安正确性</h3>
 * <ul>
 *   <li>成品只把 <b>DEFECTIVE</b> 翻回 AVAILABLE —— 重复审批时已是 AVAILABLE 的批次被跳过, 不会双翻。</li>
 *   <li>原料翻转经 {@link #evaluateMaterialFlip} 守卫: 源状态==目标 → 幂等跳过; 终态 → 拒绝。</li>
 *   <li>只解析<b>本次质检</b>关联的批次 (plan-scoped + material-scoped), 不越权释放兄弟批次。</li>
 * </ul>
 *
 * <p><b>已知诚实限制</b>: 成品 {@code status='DEFECTIVE'} 是「QC 隔离」与「销售退货入库」共用的单一字段
 * (见 {@link FinishedGoodsBatch.Status#DEFECTIVE})。若同一生产计划的某成品批次因<b>销售退货</b>而 DEFECTIVE,
 * 放行会把它一并翻回 AVAILABLE。这与 #1229 隔离本身的粒度一致 (#1229 也无法区分来源) —— 是共用字段的既有
 * 歧义, 非本桥接引入; 需精确区分须加来源标记列 (未来 schema 变更, 本次不扩范围)。
 *
 * @since 2026-07-05 (feat/quality-disposition-inventory-bridge)
 */
@Slf4j
@Service
public class QualityDispositionInventoryService {

    /**
     * 可翻转的原料<b>源</b>状态白名单 (single source of truth, {@link com.cretas.aims.ai.tool.impl.workdesk.ReleaseDecisionTool}
     * 共用)。DEPLETED / USED_UP / EXPIRED / SCRAPPED 等终态不在其中。
     * AVAILABLE / DEFECTIVE 作为可互转的源状态在 {@link #evaluateMaterialFlip} 中单独放行。
     */
    public static final Set<MaterialBatchStatus> RELEASABLE_FROM = Set.of(
            MaterialBatchStatus.INSPECTING,
            MaterialBatchStatus.FRESH,
            MaterialBatchStatus.FROZEN,
            MaterialBatchStatus.IN_STOCK,
            MaterialBatchStatus.RESERVED
    );

    /** 纯函数守卫的翻转判定结果 (无副作用)。 */
    public enum FlipDecision {
        /** 可翻转。 */
        FLIP,
        /** 源状态已是目标状态 (幂等跳过)。 */
        ALREADY_IN_TARGET,
        /** 源状态为终态, 不可翻转。 */
        INVALID_TERMINAL
    }

    @Autowired
    private MaterialBatchRepository materialBatchRepository;

    /**
     * 成品/生产批次 repo — {@code required=false} 镜像 {@code QualityInspectionServiceImpl}, 使未 wire 这些
     * bean 的单测不破坏; 生产/成品未关联时桥接 graceful no-op。
     */
    @Autowired(required = false)
    private ProductionBatchRepository productionBatchRepository;

    @Autowired(required = false)
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    // ==================== 共享纯函数守卫 (ReleaseDecisionTool 复用, 防漂移) ====================

    /**
     * 原料批次状态翻转的<b>纯函数</b>守卫 (无副作用, 不持久化)。判定给定当前状态能否翻到目标状态。
     *
     * <p>ReleaseDecisionTool (AI-chat 放行/退货) 与本桥接 (放行/退货/报废) 共用此判定, 保证白名单/终态/
     * 幂等逻辑<b>唯一实现</b>不漂移。持久化由各自 repo 负责 (职责分离)。
     *
     * @param current 当前状态 (可为 null)
     * @param target  目标状态 (AVAILABLE 放行 / DEFECTIVE 退货 / SCRAPPED 报废)
     */
    public static FlipDecision evaluateMaterialFlip(MaterialBatchStatus current, MaterialBatchStatus target) {
        if (current == target) {
            return FlipDecision.ALREADY_IN_TARGET;
        }
        // 终态守卫: 不在白名单, 且不是 AVAILABLE/DEFECTIVE 这两个可互转源 → 终态不可改
        if (current != null && !RELEASABLE_FROM.contains(current)
                && current != MaterialBatchStatus.AVAILABLE
                && current != MaterialBatchStatus.DEFECTIVE) {
            return FlipDecision.INVALID_TERMINAL;
        }
        return FlipDecision.FLIP;
    }

    // ==================== 桥接主入口 ====================

    /**
     * 把一次质检处置<b>最终执行</b>时的决策映射到库存翻转。只由
     * {@code QualityDispositionRuleServiceImpl.executeDisposition} 的直接执行分支调用 (非 approval-initiated)。
     *
     * @param inspection 质检记录 (提供 productionBatchId / materialBatchId 定位隔离批次)
     * @param action     已确定要执行的处置动作
     * @return 翻转结果 (供日志/审计, 不改变调用方原有 DispositionExecutionResult 结构)
     */
    @Transactional
    public InventoryDispositionResult applyDisposition(QualityInspection inspection, DispositionAction action) {
        if (inspection == null || action == null) {
            return InventoryDispositionResult.builder().action(action).build();
        }

        switch (action) {
            case RELEASE:
            case CONDITIONAL_RELEASE: {
                int fg = flipFinishedGoods(inspection, FinishedGoodsBatch.Status.DEFECTIVE,
                        FinishedGoodsBatch.Status.AVAILABLE);
                FlipDecision mat = flipMaterial(inspection, MaterialBatchStatus.AVAILABLE);
                InventoryDispositionResult r = InventoryDispositionResult.builder()
                        .action(action)
                        .finishedGoodsReleased(fg)
                        .materialFlip(mat)
                        .build();
                log.info("质检处置库存桥接 — {} inspectionId={} 放回可售: 成品 {} 个 DEFECTIVE→AVAILABLE, 原料={}",
                        action, inspection.getId(), fg, mat);
                return r;
            }
            case SCRAP: {
                // 成品无 SCRAPPED 状态 → 保持 DEFECTIVE (物理报废走处置/报损 UI 人工流, 不重复处理)。
                // 原料 DEFECTIVE→SCRAPPED (真实终态)。二者都<b>不</b>放回 AVAILABLE。
                FlipDecision mat = flipMaterial(inspection, MaterialBatchStatus.SCRAPPED);
                log.info("质检处置库存桥接 — SCRAP inspectionId={} 原料报废={} (成品保持 DEFECTIVE, 物理报废走处置 UI)",
                        inspection.getId(), mat);
                return InventoryDispositionResult.builder()
                        .action(action)
                        .materialFlip(mat)
                        .build();
            }
            default:
                // HOLD / SPECIAL_APPROVAL / REWORK — 库存保持隔离 (DEFECTIVE), 不翻转。
                log.debug("质检处置库存桥接 — {} inspectionId={} 无库存翻转 (保持隔离)", action, inspection.getId());
                return InventoryDispositionResult.builder().action(action).build();
        }
    }

    // ==================== 成品 (FG) — 镜像 #1229 反向 ====================

    /**
     * 把<b>本次质检</b>关联生产计划下、当前 {@code fromStatus} 的成品批次翻到 {@code toStatus}。
     * 只解析本 inspection 的 productionBatchId → plan → plan 的成品批次 (不越权释放兄弟计划)。
     * 幂等: 只翻 {@code fromStatus} 的, 已是 {@code toStatus} 的跳过。
     */
    private int flipFinishedGoods(QualityInspection inspection, String fromStatus, String toStatus) {
        if (finishedGoodsBatchRepository == null || productionBatchRepository == null) {
            return 0;
        }
        Long productionBatchId = inspection.getProductionBatchId();
        if (productionBatchId == null) {
            return 0;
        }
        ProductionBatch pb = productionBatchRepository.findById(productionBatchId).orElse(null);
        if (pb == null) {
            return 0;
        }
        String planId = pb.getProductionPlanId();
        if (planId == null || planId.isBlank()) {
            return 0;
        }
        var fgBatches = finishedGoodsBatchRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(inspection.getFactoryId(), planId);
        int flipped = 0;
        for (FinishedGoodsBatch fg : fgBatches) {
            if (fromStatus.equals(fg.getStatus())) {
                fg.setStatus(toStatus);
                finishedGoodsBatchRepository.save(fg);
                flipped++;
            }
        }
        return flipped;
    }

    // ==================== 原料 (MaterialBatch) — 与 ReleaseDecisionTool 共用守卫 ====================

    /**
     * 若质检是 material-scoped (materialBatchId 非空), 把该原料批次翻到 {@code target}。经共享纯函数守卫,
     * 幂等 + 终态保护。返回判定结果; 非 material-scoped 返回 null。
     */
    private FlipDecision flipMaterial(QualityInspection inspection, MaterialBatchStatus target) {
        String materialBatchId = inspection.getMaterialBatchId();
        if (materialBatchId == null || materialBatchId.isBlank()) {
            return null; // 非 material-scoped
        }
        MaterialBatch mb = materialBatchRepository
                .findByIdAndFactoryId(materialBatchId, inspection.getFactoryId())
                .orElse(null);
        if (mb == null) {
            log.warn("质检处置库存桥接 — materialBatchId={} 不存在或非本工厂 factory={}, 跳过原料翻转",
                    materialBatchId, inspection.getFactoryId());
            return null;
        }
        FlipDecision decision = evaluateMaterialFlip(mb.getStatus(), target);
        if (decision == FlipDecision.FLIP) {
            mb.setStatus(target);
            materialBatchRepository.save(mb);
        }
        return decision;
    }

    /** 桥接结果 (供调用方日志/审计)。 */
    @Data
    @Builder
    public static class InventoryDispositionResult {
        private DispositionAction action;
        /** 翻回 AVAILABLE 的成品批次数 (放行时)。 */
        private int finishedGoodsReleased;
        /** 原料翻转判定 (material-scoped 时非 null)。 */
        private FlipDecision materialFlip;
    }
}
