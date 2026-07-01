package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionInterimSettlement;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionInterimSettlementRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.InterimSettleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * G3 小结编排实现 — 见 {@link InterimSettleService} Javadoc。
 *
 * <h3>跨小结 SFI in/out 会计模型 (本实现锁定的口径)</h3>
 * SFI 半成品库以 per-(plan, productType) 运行余额行为锚: {@code CLK-SEMI-{planId8}-{productTypeId8}}。
 * <ul>
 *   <li><b>SFI IN</b> = 本次未结非成品道中, 其产出批次<b>未被同次小结内任何未结道消耗</b>的"终点"半成品。
 *       同次小结内被下游消耗的中间道产出 (如道1→道2→道3 链中的道1/道2) = 瞬态在制, <b>不入库</b>
 *       (这正是"避免同批瞬态 WIP 双重入库"的机制: 它们既不入 SFI 也不被 SFI 出库)。</li>
 *   <li><b>SFI OUT</b> = 未结道经 upstreamSources 消耗了某 batchNumber, 且该 batchNumber 属于
 *       <b>前序小结已入库</b>的半成品 (∈ 前序各次 summary.semiInBatchNumbers 并集) → 按 feedQuantityKg 出库。
 *       仅对真正入过库的半成品出库 (精确, 配合 consumeClerkSemi 的 not-below-zero 守卫双保险)。</li>
 * </ul>
 * 典型链: 小结#1 录道1-道3 (道1/道2 被同小结道2/道3 消耗 = 瞬态; 道3 终点 → SFI IN 60);
 * 小结#2 录道4 (成品, upstream→道3 feed 60) → SFI OUT 60 (道3 ∈ 前序 stocked) + FG 入库。
 *
 * <p>幂等双标记: material_consumptions.interim_settled_at (扣减侧) + process_sheet_rows.interim_settled_at
 * (产出侧)。重复点击 → 两侧均无未结行 → 全 no-op。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterimSettleServiceImpl implements InterimSettleService {

    private final ProductionPlanRepository planRepository;
    private final ProcessSheetRowRepository rowRepository;
    private final MaterialConsumptionRepository consumptionRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final ProductionInterimSettlementRepository settlementRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final ProductTypeRepository productTypeRepository;
    private final WipInventoryService wipInventoryService;
    private final WarehouseResolver warehouseResolver;
    private final ObjectMapper objectMapper;
    /** G3 成本传导: 读 materialized/finished 道的 ProductionBatch 成本 (原料+调料+人工)。 */
    private final ProductionBatchRepository batchRepository;
    /** G3 成本传导: 纯 SFI 中间道 (SAVED_SFI, 不物化) 的人工按 payload laborSegments 现算 + 工时单价解析。 */
    private final ClerkProcessEntryService clerkProcessEntryService;

    @Override
    @Transactional
    public Map<String, Object> interimSettle(String factoryId, String planId, Long userId) {
        ProductionPlan plan = planRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "生产计划不存在: " + planId));
        if (plan.getSourceType() != PlanSourceType.SAFETY_STOCK) {
            throw new BusinessException(400, "仅存货生产计划可小结, 当前来源类型: "
                    + plan.getSourceType())
                    .withHint("非存货生产计划请走「结单」")
                    .withHintTarget("小结");
        }

        LocalDateTime now = LocalDateTime.now();

        // session_seq = 上次 + 1 (首次 = 1)
        int sessionSeq = settlementRepository
                .findTopByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqDesc(factoryId, planId)
                .map(s -> (s.getSessionSeq() == null ? 0 : s.getSessionSeq()) + 1)
                .orElse(1);

        // 前序各次小结已入库的半成品 batchNumber 并集 (用于精确判定 SFI OUT 只扣真正入过库的)
        Set<String> priorStocked = collectPriorStockedBatchNumbers(factoryId, planId);

        // ── ① 扣原料 (会话幂等): 未结 material_consumptions → 来源 MaterialBatch 悲观锁扣减 ──
        List<MaterialConsumption> unposted = consumptionRepository
                .findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(planId, factoryId);
        BigDecimal deductedQuantity = BigDecimal.ZERO;
        int deductedCount = 0;
        for (MaterialConsumption mc : unposted) {
            MaterialBatch src = materialBatchRepository
                    .findByIdAndFactoryIdForUpdate(mc.getBatchId(), factoryId)
                    .orElseThrow(() -> new BusinessException(404,
                            "消耗来源批次不存在或无权访问: " + mc.getBatchId()));
            src.setUsedQuantity(nz(src.getUsedQuantity()).add(nz(mc.getQuantity())));
            src.setLastUsedAt(now);
            if (src.getCurrentQuantity() != null && src.getCurrentQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                src.setStatus(MaterialBatchStatus.USED_UP);
            }
            materialBatchRepository.save(src);
            mc.setInterimSettledAt(now);
            consumptionRepository.save(mc);
            deductedQuantity = deductedQuantity.add(nz(mc.getQuantity()));
            deductedCount++;
        }

        // ── 加载本计划全部 process_sheet_rows, 分未结/已结, 建索引 ──
        List<ProcessSheetRow> allRows = rowRepository.findByFactoryIdAndPlanId(factoryId, planId);
        Map<String, String> productTypeByBatchNumber = new HashMap<>();   // 所有已物化行 batchNumber → productTypeId
        List<UnsettledRow> unsettledRows = new ArrayList<>();
        Set<String> unsettledBatchNumbers = new HashSet<>();
        for (ProcessSheetRow row : allRows) {
            // option F: 纯半成品(SFI)喂的非成品中间道 (rowStatus=SAVED_SFI) 不物化 WIP → batchId==null,
            //   但 batchNumber=SFI 锚。它需参与 SFI in/out 结算 (产出入 SFI + 输入 SFI 扣减),
            //   不能当 DRAFT 跳过。普通 DRAFT (batchNumber==null) 与已物化行 (batchId!=null) 照常。
            boolean pureSfiOut = ProcessSheetRow.STATUS_SAVED_SFI.equals(row.getRowStatus());
            if ((row.getBatchId() == null && !pureSfiOut) || row.getBatchNumber() == null) {
                continue; // DRAFT / 未物化行 (无批次号) → 无产出可过账
            }
            ProcessSheetRowRequest req = parsePayload(row.getRowPayload());
            if (req == null) {
                continue;
            }
            if (req.getProductTypeId() != null) {
                productTypeByBatchNumber.put(row.getBatchNumber(), req.getProductTypeId());
            }
            if (row.getInterimSettledAt() == null) {
                unsettledRows.add(new UnsettledRow(row, req));
                unsettledBatchNumbers.add(row.getBatchNumber());
            }
        }

        // 同次小结内每个 batchNumber 被下游投入的累计量 (= 瞬态部分)。
        // SFI IN 只入"净结余 = 本道产出 − 同小结内被下游投入的量": 全消耗→净0不入(瞬态);
        // 部分消耗(产出60被投40)→净20入库(残留半成品), 不再整道误判瞬态而漏入。
        Map<String, BigDecimal> withinSessionFeedByBatchNo = new HashMap<>();
        for (UnsettledRow ur : unsettledRows) {
            if (ur.req.getUpstreamSources() == null) {
                continue;
            }
            for (ProcessSheetRowRequest.UpstreamRef ref : ur.req.getUpstreamSources()) {
                // SFI 投料 (semiFinished=true) 引用的是常驻外部半成品库存, 不是本小结的瞬态在制产出
                // → 不纳入"同小结内被下游投入"统计 (否则若外部 SFI 批号偶然与本计划在制批号相同会污染净结余)。
                if (ref.isSemiFinished()) {
                    continue;
                }
                String src = ref.getSourceBatchNumber();
                if (src != null && unsettledBatchNumbers.contains(src)) {
                    withinSessionFeedByBatchNo.merge(src, nz(ref.getFeedQuantityKg()), BigDecimal::add);
                }
            }
        }

        // ── ② SFI OUT: 未结道消耗半成品库存 → 出库扣减 ──
        //   两类来源, 不同失败语义:
        //   (A) SFI 投料 (semiFinished=true, 半成品直接产成品): sourceBatchNumber 即常驻 SFI 的
        //       intermediateBatchNo → consumeClerkSemiStrict(srcBatchNo) 严格扣减 (缺失/不足即抛,
        //       禁止降级 → 防 phantom/不足库存生产成品)。不依赖 priorStocked, 因为该 SFI 可能来自
        //       其它计划 / 文员直接入库。semiOutQuantity 累计的是 strict 返回的 *实际出库量*
        //       (= feed, 因不足即抛), summary 永不虚高。
        //   (B) 本计划前序小结已入库的半成品 (priorStocked, semiFinished=false): 经 per-(plan,productType)
        //       运行余额行锚 semiAnchor 出库, 沿用 consumeClerkSemi 的 not-below-zero 容忍 (行缺失 no-op /
        //       超扣 clamp) —— 这是与 SFI IN 净结余会计互校的有意双保险, 不改 (行为不变)。
        BigDecimal semiOutQuantity = BigDecimal.ZERO;
        for (UnsettledRow ur : unsettledRows) {
            if (ur.req.getUpstreamSources() == null) {
                continue;
            }
            for (ProcessSheetRowRequest.UpstreamRef ref : ur.req.getUpstreamSources()) {
                String srcBatchNo = ref.getSourceBatchNumber();
                BigDecimal feed = nz(ref.getFeedQuantityKg());
                // (A) SFI 投料: 严格扣减常驻 SFI 库存 (缺失/不足即抛, 整事务回滚 → 不产 phantom FG)。
                if (ref.isSemiFinished()) {
                    if (srcBatchNo != null && feed.signum() > 0) {
                        BigDecimal drawn = wipInventoryService
                                .consumeClerkSemiStrict(factoryId, srcBatchNo, feed);
                        semiOutQuantity = semiOutQuantity.add(nz(drawn)); // 实际出库量, 非 pre-clamp feed
                    }
                    continue; // SFI 投料不走 priorStocked anchor 路径 (避免重复扣减)
                }
                // (B) 前序小结已入库的半成品 → anchor 出库 (容忍版, 双保险)。
                if (srcBatchNo == null || !priorStocked.contains(srcBatchNo)) {
                    continue; // 只对前序已入库的半成品出库
                }
                String srcProductType = productTypeByBatchNumber.get(srcBatchNo);
                if (srcProductType == null) {
                    srcProductType = ur.req.getProductTypeId(); // 链内单品回退
                }
                if (feed.signum() > 0) {
                    wipInventoryService.consumeClerkSemi(factoryId,
                            semiAnchor(planId, srcProductType), feed);
                    semiOutQuantity = semiOutQuantity.add(feed);
                }
            }
        }

        // 工时单价 (纯 SFI 中间道人工现算用; 解析一次)。fallback warning 丢弃 (小结非录入路径, 不回传前端)。
        BigDecimal laborRate = clerkProcessEntryService.resolveLaborRate(factoryId, new ArrayList<>());

        // ── ③ SFI IN: 未结非成品终点道 (未被同小结消耗) 产出 → 入库 (带真实成本传导) ──
        List<String> semiInBatchNumbers = new ArrayList<>();
        BigDecimal semiInQuantity = BigDecimal.ZERO;
        for (UnsettledRow ur : unsettledRows) {
            if (ur.req.isFinished()) {
                continue; // 成品道走 FG, 不入 SFI
            }
            String batchNo = ur.row.getBatchNumber();
            BigDecimal outQty = nz(ur.req.getOutputQuantity());
            BigDecimal withinFeed = withinSessionFeedByBatchNo.getOrDefault(batchNo, BigDecimal.ZERO);
            BigDecimal net = outQty.subtract(withinFeed); // 净结余 = 产出 − 同小结内被下游投入
            if (net.signum() <= 0) {
                continue; // 全部被同小结下游消耗 → 瞬态在制, 不入库 (防双重入库)
            }
            // 🔴 成本传导: 本道产出单位成本 = (本道成本 + SFI 投料成本) / 本道全产出量 (非 net —
            //   net 与 withinFeed 同单价, withinFeed 部分的成本经在制 WIP 消耗边继承给下游道, 不双计)。
            //   诚实 null: 任一 SFI 投料 unitCost 未知 → outputUnitCost 为 null (postClerkOutput 不摊假成本)。
            BigDecimal outputUnitCost = computeOutputUnitCost(factoryId, ur, outQty, laborRate);
            wipInventoryService.postClerkOutput(factoryId,
                    semiAnchor(planId, ur.req.getProductTypeId()), ur.req.getProductTypeId(),
                    net, ur.req.getUnit(), outputUnitCost, null);
            semiInBatchNumbers.add(batchNo);
            semiInQuantity = semiInQuantity.add(net);
        }

        // ── ④ FG: 未结成品道产出 (优先成品重 productWeight) → 成品库 ──
        List<String> finishedBatchNumbers = new ArrayList<>();
        BigDecimal finishedQuantity = BigDecimal.ZERO;
        for (UnsettledRow ur : unsettledRows) {
            if (!ur.req.isFinished()) {
                continue;
            }
            BigDecimal productWeight = ur.req.getProductWeight();
            boolean useWeight = productWeight != null && productWeight.signum() > 0;
            BigDecimal qty = useWeight ? productWeight : nz(ur.req.getOutputQuantity());
            if (qty.signum() <= 0) {
                continue;
            }
            String unit = useWeight ? "kg" : (ur.req.getUnit() != null ? ur.req.getUnit() : "kg");
            // 🔴 成本传导: FG 单位成本 = (本道 ProductionBatch 成本[原料+调料+人工] + SFI 投料成本) / FG 入库量。
            //   SFI 投料成本不在 ProductionBatch.totalCost 内 (SFI 边不写 MaterialConsumption), 故此处补进。
            //   分母用 FG 实际入库量 qty (productWeight/outputQuantity), 与库存计量口径一致 (盒/kg 皆按本量摊)。
            //   诚实 null: 任一 SFI 投料 unitCost 未知 → fgUnitCost 为 null (成品成本未知, 不伪造 ¥0)。
            BigDecimal fgUnitCost = computeOutputUnitCost(factoryId, ur, qty, laborRate);
            FinishedGoodsBatch fg = createFinishedGoodsForInterim(
                    plan, ur.req.getProductTypeId(), qty, unit, sessionSeq, userId, fgUnitCost);
            finishedBatchNumbers.add(fg.getBatchNumber());
            finishedQuantity = finishedQuantity.add(qty);
        }

        // ── 打戳: 全部未结产出行标记已结 (产出侧幂等) ──
        for (UnsettledRow ur : unsettledRows) {
            ur.row.setInterimSettledAt(now);
            rowRepository.save(ur.row);
        }

        // ── 记录小结 ──
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sessionSeq", sessionSeq);
        summary.put("deductedConsumptionCount", deductedCount);
        summary.put("deductedQuantity", deductedQuantity);
        summary.put("semiInBatchNumbers", semiInBatchNumbers);
        summary.put("semiInQuantity", semiInQuantity);
        summary.put("semiOutQuantity", semiOutQuantity);
        summary.put("finishedGoodsBatchNumbers", finishedBatchNumbers);
        summary.put("finishedQuantity", finishedQuantity);

        ProductionInterimSettlement settlement = ProductionInterimSettlement.builder()
                .factoryId(factoryId)
                .productionPlanId(planId)
                .sessionSeq(sessionSeq)
                .postedAt(now)
                .postedBy(userId)
                .summary(summary)
                .build();
        try {
            // saveAndFlush: 让 uk_interim_plan_seq 唯一约束冲突在此同步抛出 (而非延迟到事务提交边界),
            // 使并发小结的 loser 得到友好 409 而非裸 500。整事务回滚 (数据安全)。
            settlementRepository.saveAndFlush(settlement);
        } catch (DataIntegrityViolationException dup) {
            throw new BusinessException(409, "小结并发提交,请稍后重试")
                    .withCode("INTERIM_SETTLE_CONCURRENT")
                    .withHint("另一笔小结正在提交,请稍后重试");
        }

        log.info("[interim-settle] factory={}, plan={}, seq={}: deducted {} consumptions ({}); "
                        + "SFI in {} ({}), SFI out {}, FG {} ({})",
                factoryId, planId, sessionSeq, deductedCount, deductedQuantity,
                semiInBatchNumbers.size(), semiInQuantity, semiOutQuantity,
                finishedBatchNumbers.size(), finishedQuantity);

        return summary;
    }

    // ─────────────────────────────────────────────────────────────
    // FG creation (no settlement dependency) — mirror createFinishedGoodsFromReceipt :2777-2832
    // ─────────────────────────────────────────────────────────────

    private FinishedGoodsBatch createFinishedGoodsForInterim(ProductionPlan plan, String productTypeId,
                                                             BigDecimal qty, String unit, int sessionSeq,
                                                             Long userId, BigDecimal unitCost) {
        if (productTypeId == null || productTypeId.isBlank()) {
            throw new BusinessException(409, "生产计划/工序行缺少产品类型, 不能生成成品库存")
                    .withHint("请先补全产品类型")
                    .withHintTarget("小结");
        }
        String batchNumber = finishedGoodsBatchNumber(plan, sessionSeq);
        // 幂等防护 (产出行标记已防重, 此处为 batchNumber 撞号二次保险)
        return finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(plan.getFactoryId(), batchNumber)
                .orElseGet(() -> {
                    ProductType productType = productTypeRepository
                            .findByIdAndFactoryId(productTypeId, plan.getFactoryId()).orElse(null);
                    FinishedGoodsBatch batch = new FinishedGoodsBatch();
                    batch.setFactoryId(plan.getFactoryId());
                    batch.setBatchNumber(batchNumber);
                    batch.setProductTypeId(productTypeId);
                    batch.setProductName(productType != null ? productType.getName() : null);
                    batch.setProducedQuantity(qty);
                    batch.setShippedQuantity(BigDecimal.ZERO);
                    batch.setReservedQuantity(BigDecimal.ZERO);
                    batch.setUnit(unit);
                    batch.setUnitPrice(productType != null ? productType.getUnitPrice() : null);
                    // 🔴 成本传导 (诚实 null): 成品成本 (含 SFI 投料). null = 未知, 不伪造 ¥0。区别于 unitPrice(售价)。
                    batch.setUnitCost(unitCost);
                    batch.setProductionDate(LocalDate.now());
                    int shelfLifeDays = productType != null && productType.getShelfLifeDays() != null
                            ? productType.getShelfLifeDays() : 180;
                    batch.setExpireDate(LocalDate.now().plusDays(shelfLifeDays));
                    batch.setStorageLocation("库存生产小结入库");
                    batch.setProductionPlanId(plan.getId());
                    batch.setWarehouseId(warehouseResolver.resolveWorkshopId(plan.getFactoryId()));
                    batch.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
                    batch.setCreatedBy(userId != null ? userId : 0L);
                    batch.setRemark("库存生产小结第 " + sessionSeq + " 次入库: " + plan.getPlanNumber());
                    return finishedGoodsBatchRepository.save(batch);
                });
    }

    /** FG-{planNumber}-S{seq}, ≤64 截断 (mirror finishedGoodsBatchNumber :2822-2832). */
    private String finishedGoodsBatchNumber(ProductionPlan plan, int sessionSeq) {
        String planNumber = plan.getPlanNumber() != null ? plan.getPlanNumber() : plan.getId();
        String raw = "FG-" + planNumber + "-S" + sessionSeq;
        if (raw.length() <= 64) {
            return raw;
        }
        String suffix = "-S" + sessionSeq;
        return raw.substring(0, 64 - suffix.length()) + suffix;
    }

    // ─────────────────────────────────────────────────────────────
    // 成本传导 (G3 SFI cost transmission) — 🔴 红线, 禁止降级 (诚实 null)
    // ─────────────────────────────────────────────────────────────

    /**
     * 计算一道产出的真实单位成本 (成本传导核心)。
     *
     * <pre>
     *   outputTotalCost = 本道 base 成本 + Σ(SFI 投料: feedQuantityKg × 输入 SFI.unitCost)
     *   outputUnitCost  = outputTotalCost / outputQty   (scale-4, HALF_UP)
     * </pre>
     *
     * <p>三桶来源 (与 materializeBatch 物化口径一致):
     * <ul>
     *   <li><b>base 成本</b>: <br>
     *     — 物化道 (batchId != null): {@code ProductionBatch.totalCost} —— 已含 <b>原料 + 调料 + 人工
     *       + 在制 WIP 继承</b> (materializeBatch 逐边写 MaterialConsumption + SEASONING/LABOR 报工累加)。
     *       SFI 投料<b>不</b>在其内 (SFI 边不写 MaterialConsumption, resolveEdges 跳过), 故下面单独补 SFI 桶。<br>
     *     — 纯 SFI 中间道 (batchId == null, SAVED_SFI, 不物化): base = <b>本道人工</b>
     *       (payload {@code laborSegments} 按 {@link ClerkProcessEntryService#computeLaborCost} 现算,
     *       与物化道人工字节一致)。此类道无原料 (纯 SFI 投料); 调料成本此路径不覆盖 (见 §限制)。</li>
     *   <li><b>SFI 投料桶</b>: 每条 {@code semiFinished=true} 上游, feedKg × 输入 SFI 的移动均价 unitCost
     *       ({@link WipInventoryService#getSemiUnitCost})。</li>
     * </ul>
     *
     * <p>🔴 <b>诚实 null 传播</b> (禁止降级):
     * <ul>
     *   <li>任一 SFI 投料的 {@code unitCost} 为 null (旧库存/未接通成本的半成品) → 返 <b>null</b>
     *       (整道产出成本未知, 不当 ¥0 摊薄稀释)。这是"成本传不动"的根因: 只有全部投入成本已知才产出成本。</li>
     *   <li>物化道 ProductionBatch 缺失 (数据异常) → null (诚实, 不猜)。</li>
     *   <li>outputQty ≤ 0 → null (无分母)。</li>
     * </ul>
     * base 内部沿用现有物化/报工口径 (料/工价 null 已在物化时 nz→0, ProductionBatch.totalCost 恒非 null) —
     * 与既有 upsertProducedWip/postSemiOutputLedger 诚实 null 纪律对齐: null=未知(传播), 0=已知无成本。
     *
     * <p><b>§限制</b>: 纯 SFI 中间道若为熟制/调味道, 其调料成本 (RecipeCostCalculator) 此路径未现算
     * (需锅序/配方, 属重算); 该类道产出成本会漏调料桶。物化的熟制道 (含原料) 走 ProductionBatch.totalCost
     * 已含调料, 不受影响。
     *
     * @param outputQty unitCost 分母: SFI IN = 本道全产出量 (与 ProductionBatch.totalCost 基准同量);
     *                  FG = FG 实际入库量 (productWeight/outputQuantity)。
     * @return 产出单位成本; 任一投入成本未知 → null (诚实, 不伪造 ¥0)。
     */
    private BigDecimal computeOutputUnitCost(String factoryId, UnsettledRow ur, BigDecimal outputQty,
                                             BigDecimal laborRate) {
        if (outputQty == null || outputQty.signum() <= 0) {
            return null;
        }
        BigDecimal baseTotal;
        if (ur.row.getBatchId() != null) {
            // 物化道: ProductionBatch.totalCost 含 原料+调料+人工 (+ 在制 WIP 继承); SFI 投料下面单独补。
            ProductionBatch pb = batchRepository.findByIdAndFactoryId(ur.row.getBatchId(), factoryId).orElse(null);
            if (pb == null || pb.getTotalCost() == null) {
                return null; // 诚实: 批次成本缺失 → 未知
            }
            baseTotal = pb.getTotalCost();
        } else {
            // 纯 SFI 中间道 (SAVED_SFI): base = 本道人工 (payload; 从未落库, 现算)。无 laborSegments → 0 (已知无人工)。
            baseTotal = nz(clerkProcessEntryService.computeLaborCost(ur.req.getLaborSegments(), laborRate));
        }

        // Σ SFI 投料成本 (feedKg × 输入 SFI.unitCost); 任一 unitCost null → 诚实 null。
        BigDecimal sfiFeedCost = BigDecimal.ZERO;
        if (ur.req.getUpstreamSources() != null) {
            for (ProcessSheetRowRequest.UpstreamRef ref : ur.req.getUpstreamSources()) {
                if (!ref.isSemiFinished()) {
                    continue; // 在制 WIP 投料的成本已在 ProductionBatch.totalCost (MaterialConsumption 边) 内
                }
                BigDecimal feed = nz(ref.getFeedQuantityKg());
                if (feed.signum() <= 0) {
                    continue;
                }
                BigDecimal inputUnitCost = wipInventoryService
                        .getSemiUnitCost(factoryId, ref.getSourceBatchNumber());
                if (inputUnitCost == null) {
                    return null; // 🔴 诚实 null: 输入半成品无成本 → 本道产出成本未知, 不伪造 ¥0
                }
                sfiFeedCost = sfiFeedCost.add(feed.multiply(inputUnitCost));
            }
        }

        return baseTotal.add(sfiFeedCost).divide(outputQty, 4, RoundingMode.HALF_UP);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * SFI 运行余额行锚: per-(plan,productType), planId/productTypeId 各取前 8, 总长 ≤64.
     *
     * <p>委托共享单一真源 {@link WipInventoryService#clerkSemiAnchor} —— 逐道保存 (option F 纯 SFI
     * 中间道产出定位) 与本处小结入库必须用同一锚, 集中派生防漂移。
     */
    private String semiAnchor(String planId, String productTypeId) {
        return WipInventoryService.clerkSemiAnchor(planId, productTypeId);
    }

    /** 前序各次小结 summary.semiInBatchNumbers 并集 (本次之前已入库的半成品 batchNumber). */
    private Set<String> collectPriorStockedBatchNumbers(String factoryId, String planId) {
        Set<String> out = new HashSet<>();
        for (ProductionInterimSettlement s : settlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqAsc(factoryId, planId)) {
            Map<String, Object> summary = s.getSummary();
            if (summary == null) {
                continue;
            }
            Object v = summary.get("semiInBatchNumbers");
            if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null) {
                        out.add(o.toString());
                    }
                }
            }
        }
        return out;
    }

    private ProcessSheetRowRequest parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, ProcessSheetRowRequest.class);
        } catch (Exception e) {
            log.warn("[interim-settle] 无法解析 process_sheet_row payload: {}", e.getMessage());
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 未结行 + 其反序列化 payload 的轻量配对. */
    private static final class UnsettledRow {
        final ProcessSheetRow row;
        final ProcessSheetRowRequest req;

        UnsettledRow(ProcessSheetRow row, ProcessSheetRowRequest req) {
            this.row = row;
            this.req = req;
        }
    }
}
