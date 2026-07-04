package com.cretas.aims.service.yield.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionInterimSettlement;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionInterimSettlementRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.InterimSettleReversalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 撤销小结 (interim-settle reversal) 编排实现 — 见 {@link InterimSettleReversalService} Javadoc。
 *
 * <p><b>粒度 = per-session (一次小结)</b>, 不做 per-row。原因: 小结的 SFI IN 净结余是<b>跨行相消</b>算的
 * (同 session 内下游道消耗上游道产出 → 瞬态不入库), 单独撤一行会破坏兄弟行的净量核算。一次小结是自洽的最小逆转单元。
 * 前端「撤销小结」对一个「已小结」分组 (= 一次 session) 整体撤销。
 *
 * <p><b>逆转依据 = settle 写入 summary.reversalDetail 的逐笔明细</b> (drift-free, 尤其 SFI IN 移动均价成本
 * 不可事后重算 → 必须锚定 settle 当时记录的确切金额)。原料/行 用 postedAt 时间戳反查 (settle 打的戳 = 小结 postedAt)。
 *
 * <p><b>顺序</b>: 先逆转带下游守卫的动作 (SFI IN un-stock / FG un-create) —— 若下游已消耗即 loud-fail,
 * @Transactional 回滚, 后面的还回/清戳都不发生 (原子, 不留半撤)。再做无风险还回 (SFI/FG OUT restore + 原料 restore),
 * 最后清行戳 + 硬删小结记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterimSettleReversalServiceImpl implements InterimSettleReversalService {

    private final ProductionPlanRepository planRepository;
    private final ProductionInterimSettlementRepository settlementRepository;
    private final MaterialConsumptionRepository consumptionRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final ProcessSheetRowRepository rowRepository;
    private final WipInventoryService wipInventoryService;
    private final FinishedGoodsFeedService finishedGoodsFeedService;

    /**
     * 🔴🔒🔒 sister #4 防呆: 撤销小结前检测本计划领料单是否已关单。{@code @Autowired(required=false)} 兼容
     * 既有 7 参构造单测 (Lombok 仅纳入 final 字段, 非 final 不进构造器 → 纯 mock 测试不受影响), 缺失时降级
     * (不阻断, 与老行为一致 —— 纯原料/无领料闸的计划本无关单交互)。prod Spring 恒注入。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository requisitionRepository;

    @Override
    @Transactional
    public Map<String, Object> reverseInterimSettle(String factoryId, String planId,
                                                    Integer sessionSeq, Long userId) {
        ProductionPlan plan = planRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "生产计划不存在: " + planId));
        if (plan.getSourceType() != PlanSourceType.SAFETY_STOCK) {
            throw new BusinessException(400, "仅存货生产计划可撤销小结, 当前来源类型: " + plan.getSourceType())
                    .withHintTarget("撤销小结");
        }

        // 🔴🔒🔒 2026-07-04 sister #4 防呆 (关单后撤销小结 → 生产仓幻库存): 领料单关单 close() 时按
        //   「WKS 现存 = issued − 已小结实耗」把未消耗料退回原料仓 + drawDown 划平生产仓 WKS 批次 (归零)。
        //   若此后撤销小结, 步骤 ⑤ 会对消耗来源批次 (mc.batchId = 已被 close 划平的 WKS 批次) 还回
        //   usedQuantity → WKS 批次凭空出现正现存 = CLOSED 领料单上的幻库存 (料已实际退回原料仓/消耗, 生产仓
        //   却又冒出一份)。关单是领料料流的终态: 一旦关单, 该计划的小结不应再撤销。fail-fast (在任何库存变更前
        //   loud-block, 保持撤销原子性)。领料单已关单 → 该改的只能是新开领料 + 重新报工小结, 不是撤旧小结。
        //   无领料闸的计划 (F006 直接从原料仓消耗, 无 WKS 物化) → 无 CLOSED 领料单 → 不触发 (行为不变)。
        if (requisitionRepository != null) {
            boolean hasClosedRequisition = requisitionRepository
                    .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId)
                    .stream()
                    .anyMatch(r -> r.getStatus() == com.cretas.aims.entity.factory.FactoryMaterialRequisition.Status.CLOSED);
            if (hasClosedRequisition) {
                throw new BusinessException(409,
                        "撤销小结失败: 本生产计划的领料单已关单退料, 关单后不可再撤销小结 (避免生产仓幻库存)")
                        .withCode("INTERIM_REVERSE_REQUISITION_CLOSED")
                        .withHint("如需调整, 请新开领料单并重新报工小结; 已关单的领料料流不可逆")
                        .withHintTarget("撤销小结")
                        .withSeverity("BLOCKING");
            }
        }

        // ── 定位目标小结 (指定 seq / 默认最近一次); 已撤销 (硬删) → empty → 双撤幂等拒绝 ──
        ProductionInterimSettlement resolved = (sessionSeq != null
                ? settlementRepository.findByFactoryIdAndProductionPlanIdAndSessionSeqAndDeletedAtIsNull(
                        factoryId, planId, sessionSeq)
                : settlementRepository.findTopByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqDesc(
                        factoryId, planId))
                .orElseThrow(() -> settlementNotFound(sessionSeq));

        // 🔒 悲观写锁重读 (串行化并发双撤): 纯原料 (无 SFI/FG 入库) 的小结无 findForUpdate 锁点, 两并发撤销
        //   会双还原料 usedQuantity → 幽灵库存。此处取 PESSIMISTIC_WRITE 行锁: 败者阻塞至赢者提交 (已硬删该行)
        //   → 重读 empty → NOT_FOUND 拒绝。以 id 重读, 统一兼容 seq 指定 + findTop (最近一次) 两条入口。
        ProductionInterimSettlement settlement = settlementRepository
                .findByIdAndFactoryIdForUpdate(resolved.getId(), factoryId)
                .orElseThrow(() -> settlementNotFound(sessionSeq));

        int seq = settlement.getSessionSeq() == null ? 0 : settlement.getSessionSeq();
        LocalDateTime postedAt = settlement.getPostedAt();

        Map<String, Object> summary = settlement.getSummary();
        Map<String, Object> reversalDetail = summary == null ? null
                : asMap(summary.get("reversalDetail"));
        if (reversalDetail == null) {
            // 诚实: 上线前的旧小结无逐笔明细 → 无法安全自动逆转 (不猜)。
            throw new BusinessException(409, "第 " + seq + " 次小结在撤销功能上线前创建, 无法自动撤销 (请人工调整库存)")
                    .withCode("INTERIM_REVERSE_NO_DETAIL")
                    .withHint("该小结缺少可逆转的明细数据, 无法自动撤销")
                    .withHintTarget("撤销小结");
        }

        // 被逆转影响的半成品/成品批次号 (供治理层快照 → 半成品盘点撤销告警)。
        java.util.Set<String> affected = new java.util.LinkedHashSet<>();

        // ── ① SFI IN un-stock (带下游守卫, 先做 → 下游已消耗即 loud-fail 回滚) ──
        int sfiInReversed = 0;
        for (Map<String, Object> d : asList(reversalDetail.get("sfiIn"))) {
            String anchor = str(d.get("anchor"));
            BigDecimal qty = dec(d.get("qty"));
            BigDecimal totalCost = decOrNull(d.get("totalCost"));
            if (anchor != null && qty != null && qty.signum() > 0) {
                wipInventoryService.reverseClerkOutput(factoryId, anchor, qty, totalCost, userId);
                affected.add(anchor);
                sfiInReversed++;
            }
        }

        // ── ② FG un-create (带下游守卫) ──
        int fgCreatedReversed = 0;
        for (Map<String, Object> d : asList(reversalDetail.get("fgCreated"))) {
            String batchNumber = str(d.get("batchNumber"));
            BigDecimal qty = dec(d.get("qty"));
            if (batchNumber != null && qty != null && qty.signum() > 0) {
                finishedGoodsFeedService.reverseInterimCreate(factoryId, batchNumber, qty, userId);
                affected.add(batchNumber);
                fgCreatedReversed++;
            }
        }

        // ── ③ SFI OUT restore (严格投料 + priorStocked anchor, 还回被扣的上游半成品) ──
        int sfiOutRestored = 0;
        for (Map<String, Object> d : asList(reversalDetail.get("sfiOutStrict"))) {
            String batchNo = str(d.get("batchNo"));
            BigDecimal qty = dec(d.get("qty"));
            if (batchNo != null && qty != null && qty.signum() > 0) {
                wipInventoryService.restoreClerkSemi(factoryId, batchNo, qty, userId);
                affected.add(batchNo);
                sfiOutRestored++;
            }
        }
        for (Map<String, Object> d : asList(reversalDetail.get("sfiOutAnchor"))) {
            String anchor = str(d.get("anchor"));
            BigDecimal qty = dec(d.get("qty"));
            if (anchor != null && qty != null && qty.signum() > 0) {
                wipInventoryService.restoreClerkSemi(factoryId, anchor, qty, userId);
                affected.add(anchor);
                sfiOutRestored++;
            }
        }

        // ── ④ FG feed restore (还回被投料扣的成品) ──
        int fgFeedRestored = 0;
        for (Map<String, Object> d : asList(reversalDetail.get("fgFeed"))) {
            String batchNo = str(d.get("batchNo"));
            BigDecimal qty = dec(d.get("qty"));
            if (batchNo != null && qty != null && qty.signum() > 0) {
                finishedGoodsFeedService.restoreForFeed(factoryId, batchNo, qty, userId);
                affected.add(batchNo);
                fgFeedRestored++;
            }
        }

        // ── ⑤ 原料 restore (还回来源 MaterialBatch.usedQuantity + 清消耗行 interim_settled_at) ──
        //   🔴 关键 (bug fix 2026-07-03, mirror #1167 幻库存修复): 扣减侧 InterimSettleServiceImpl 按
        //   (factory, production_batch_id ∈ 本计划各道 batchId) 定位并 stamp 待扣减消耗 —— 因为逐工序首/中间道
        //   (finished=false) 写的 raw 消耗其 production_plan_id 故意为 null (防成本双计), 只有 production_batch_id
        //   恒有值。撤销侧必须用同一 key 反查, 否则原 findBy...ProductionPlanId... 永远漏掉这些 null-plan 在制道
        //   消耗行 → 其 usedQuantity 不还回 + interim_settled_at 戳清不掉 → 撤销后重新小结时它们已非未结 →
        //   永久幻扣减 (原料永久短缺, 保证盘点差异; rawRestored 计数亦偏低)。
        //   本计划各道 batchId = process_sheet_rows.batch_id (与扣减侧 findByFactoryIdAndPlanId 同源, 反查时行仍在);
        //   叠加 interim_settled_at = postedAt 精确锁定本次小结扣减的那批行 → 撤销集与扣减侧 deducted 集完全一致。
        List<Long> planBatchIds = rowRepository.findByFactoryIdAndPlanId(factoryId, planId).stream()
                .map(ProcessSheetRow::getBatchId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        List<MaterialConsumption> consumptions = planBatchIds.isEmpty()
                ? List.of()
                : consumptionRepository.findByFactoryIdAndProductionBatchIdInAndInterimSettledAt(
                        factoryId, planBatchIds, postedAt);
        int rawRestored = 0;
        for (MaterialConsumption mc : consumptions) {
            MaterialBatch src = materialBatchRepository
                    .findByIdAndFactoryIdForUpdate(mc.getBatchId(), factoryId)
                    .orElseThrow(() -> new BusinessException(404,
                            "消耗来源批次不存在或无权访问: " + mc.getBatchId()));
            BigDecimal newUsed = nz(src.getUsedQuantity()).subtract(nz(mc.getQuantity()));
            if (newUsed.signum() < 0) {
                newUsed = BigDecimal.ZERO; // 防御: 已用量不为负 (数据异常保护, 不静默放大)
            }
            src.setUsedQuantity(newUsed);
            // 还料后有余量 → 从 USED_UP 恢复 AVAILABLE (其它状态如 DEFECTIVE 不动)。
            if (MaterialBatchStatus.USED_UP.equals(src.getStatus())
                    && src.getCurrentQuantity() != null
                    && src.getCurrentQuantity().compareTo(BigDecimal.ZERO) > 0) {
                src.setStatus(MaterialBatchStatus.AVAILABLE);
            }
            materialBatchRepository.save(src);
            mc.setInterimSettledAt(null);
            consumptionRepository.save(mc);
            rawRestored++;
        }

        // ── ⑥ 行 un-stamp (清 interim_settled_at → 恢复未结, 可再编辑/删除/重新小结) ──
        List<ProcessSheetRow> rows = rowRepository
                .findByFactoryIdAndPlanIdAndInterimSettledAt(factoryId, planId, postedAt);
        int rowsUnstamped = 0;
        for (ProcessSheetRow row : rows) {
            row.setInterimSettledAt(null);
            rowRepository.save(row);
            rowsUnstamped++;
        }

        // ── ⑦ 硬删小结记录 (物理释放 session_seq, 使撤销后重新小结干净; 审计留痕在 SFI REVERSE 流水 + FG 调整日志) ──
        settlementRepository.hardDeleteById(settlement.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reversedSessionSeq", seq);
        result.put("sfiInReversed", sfiInReversed);
        result.put("fgCreatedReversed", fgCreatedReversed);
        result.put("sfiOutRestored", sfiOutRestored);
        result.put("fgFeedRestored", fgFeedRestored);
        result.put("rawRestored", rawRestored);
        result.put("rowsUnstamped", rowsUnstamped);
        result.put("affectedBatchNumbers", new java.util.ArrayList<>(affected));  // 供治理层盘点告警快照

        log.info("[interim-reverse] factory={}, plan={}, seq={}: SFI-in冲销 {}, FG-create冲销 {}, "
                        + "SFI-out还回 {}, FG-feed还回 {}, 原料还回 {}, 清行戳 {}",
                factoryId, planId, seq, sfiInReversed, fgCreatedReversed, sfiOutRestored,
                fgFeedRestored, rawRestored, rowsUnstamped);
        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers — reversalDetail 解析 (jsonb 回读: 量/成本存为字符串, 防数值精度漂移)
    // ─────────────────────────────────────────────────────────────

    private static BusinessException settlementNotFound(Integer sessionSeq) {
        return new BusinessException(409,
                sessionSeq != null ? "第 " + sessionSeq + " 次小结不存在或已撤销" : "无可撤销的小结")
                .withCode("INTERIM_SETTLE_NOT_FOUND")
                .withHint("该小结可能已被撤销, 请刷新后重试")
                .withHintTarget("撤销小结");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 量: 存为字符串 → new BigDecimal; 兼容旧数值型 (Number)。 */
    private static BigDecimal dec(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        if (o instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = String.valueOf(o);
        return s.isBlank() ? null : new BigDecimal(s);
    }

    /** 成本: 诚实 null (明确 null = 当时成本未知 → 撤销不减 accumulatedCost)。 */
    private static BigDecimal decOrNull(Object o) {
        return o == null ? null : dec(o);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
