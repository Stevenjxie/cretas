package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionInterimSettlement;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionInterimSettlementRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
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
    /** ①c 成品作投料来源 — FG 严格扣减 (loud-fail) + 成本传导读取。与 SFI 投料平行。 */
    private final FinishedGoodsFeedService finishedGoodsFeedService;
    private final WarehouseResolver warehouseResolver;
    private final ObjectMapper objectMapper;
    /** G3 成本传导: 读 materialized/finished 道的 ProductionBatch 成本 (原料+调料+人工)。 */
    private final ProductionBatchRepository batchRepository;
    /** G3 成本传导: 纯 SFI 中间道 (SAVED_SFI, 不物化) 的人工按 payload laborSegments 现算 + 工时单价解析。 */
    private final ClerkProcessEntryService clerkProcessEntryService;
    /** Fix1 (#7): 纯 SFI 调味道判定 — 工序名缺失时按 productTypeId+processOrder 反查真实工序名 (与 buildStepEntry 同口径)。 */
    private final ProductWorkProcessRepository productWorkProcessRepository;
    private final WorkProcessRepository workProcessRepository;

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

        // ── 加载本计划全部 process_sheet_rows (扣减侧 + 产出侧结算共用) ──
        List<ProcessSheetRow> allRows = rowRepository.findByFactoryIdAndPlanId(factoryId, planId);

        // ── ① 扣原料 (会话幂等): 未结 material_consumptions → 来源 MaterialBatch 悲观锁扣减 ──
        //   🔴 关键 (bug fix 2026-07-02 幻库存): 逐工序首/中间道 (finished=false) 写的 raw 消耗其
        //   production_plan_id 故意为 null (防成本双计), 但 production_batch_id (per-道 WIP ProductionBatch id)
        //   恒有值。故按 (factory, production_batch_id ∈ 本计划各道 batchId) 定位待扣减消耗, 而非按
        //   production_plan_id (会漏掉 null-plan 在制道消耗 → 原料零扣减: 产成品却不减原料 = 幻库存)。
        //   本计划各道 batchId = process_sheet_rows.batch_id (物化后的 ProductionBatch.id); 纯 SFI 中间道
        //   (SAVED_SFI, batchId==null) 无 raw 消耗, 自然不在集合内。成品道 batchId 亦纳入, 与既有行为对称。
        List<Long> planBatchIds = allRows.stream()
                .map(ProcessSheetRow::getBatchId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        List<MaterialConsumption> unposted = planBatchIds.isEmpty()
                ? List.of()
                : consumptionRepository
                        .findByFactoryIdAndProductionBatchIdInAndInterimSettledAtIsNull(factoryId, planBatchIds);
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

        // ── 分未结/已结, 建索引 (allRows 已在扣减前加载) ──
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
                // SFI 投料 (semiFinished=true) / FG 投料 (finishedGoods=true) 引用的是常驻外部半成品/成品库存,
                // 不是本小结的瞬态在制产出 → 不纳入"同小结内被下游投入"统计 (否则若外部批号偶然与本计划在制批号
                // 相同会污染净结余)。
                if (ref.isSemiFinished() || ref.isFinishedGoods()) {
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
        BigDecimal finishedGoodsOutQuantity = BigDecimal.ZERO;
        // 撤销小结 (reversal) 明细收集: 逐笔记录本次小结的库存动作, 存入 summary.reversalDetail,
        //   使「撤销小结」能精确逆转每笔 (尤其 SFI IN 移动均价成本不可事后重算 → 必须锚定当时的确切金额)。
        List<Map<String, Object>> sfiOutStrictDetail = new ArrayList<>();  // consumeClerkSemiStrict → restoreClerkSemi
        List<Map<String, Object>> sfiOutAnchorDetail = new ArrayList<>();  // consumeClerkSemi(priorStocked) → restoreClerkSemi
        List<Map<String, Object>> fgFeedDetail = new ArrayList<>();        // consumeForFeedStrict → restoreForFeed
        for (UnsettledRow ur : unsettledRows) {
            if (ur.req.getUpstreamSources() == null) {
                continue;
            }
            for (ProcessSheetRowRequest.UpstreamRef ref : ur.req.getUpstreamSources()) {
                String srcBatchNo = ref.getSourceBatchNumber();
                BigDecimal feed = nz(ref.getFeedQuantityKg());
                // (C) ①c FG 投料 (成品作投料来源): 严格扣减常驻成品库存 (缺失/不足即抛 FG_NOT_FOUND/FG_INSUFFICIENT,
                //   整事务回滚 → 禁止降级, 不产 phantom)。不走 SFI/priorStocked anchor 路径。
                if (ref.isFinishedGoods()) {
                    if (srcBatchNo != null && feed.signum() > 0) {
                        // 投料量 feedQuantityKg 语义为 kg → 传 "kg"; 与 FG 批次 unit(盒/托) 不一致则 loud-fail。
                        BigDecimal drawn = finishedGoodsFeedService
                                .consumeForFeedStrict(factoryId, srcBatchNo, feed, "kg");
                        finishedGoodsOutQuantity = finishedGoodsOutQuantity.add(nz(drawn));
                        fgFeedDetail.add(qtyDetail("batchNo", srcBatchNo, nz(drawn)));
                    }
                    continue;
                }
                // (A) SFI 投料: 严格扣减常驻 SFI 库存 (缺失/不足即抛, 整事务回滚 → 不产 phantom FG)。
                if (ref.isSemiFinished()) {
                    if (srcBatchNo != null && feed.signum() > 0) {
                        BigDecimal drawn = wipInventoryService
                                .consumeClerkSemiStrict(factoryId, srcBatchNo, feed);
                        semiOutQuantity = semiOutQuantity.add(nz(drawn)); // 实际出库量, 非 pre-clamp feed
                        sfiOutStrictDetail.add(qtyDetail("batchNo", srcBatchNo, nz(drawn)));
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
                    String outAnchor = semiAnchor(planId, srcProductType);
                    wipInventoryService.consumeClerkSemi(factoryId, outAnchor, feed);
                    semiOutQuantity = semiOutQuantity.add(feed);
                    sfiOutAnchorDetail.add(qtyDetail("anchor", outAnchor, feed));
                }
            }
        }

        // 工时单价 (纯 SFI 中间道人工现算用; 解析一次)。fallback warning 丢弃 (小结非录入路径, 不回传前端)。
        BigDecimal laborRate = clerkProcessEntryService.resolveLaborRate(factoryId, new ArrayList<>());

        // ── ③ SFI IN: 未结非成品终点道 (未被同小结消耗) 产出 → 入库 (带真实成本传导) ──
        List<String> semiInBatchNumbers = new ArrayList<>();
        BigDecimal semiInQuantity = BigDecimal.ZERO;
        // 撤销明细: per-anchor 累计净入库量 + 确切成本 (totalCost = outputUnitCost×net, null=当时未知) →
        //   撤销时 reverseClerkOutput 逆转 (移动均价成本不可事后重算, 必须锚定此值)。
        Map<String, BigDecimal> sfiInQtyByAnchor = new LinkedHashMap<>();
        Map<String, BigDecimal> sfiInCostByAnchor = new LinkedHashMap<>();
        Set<String> sfiInCostKnownAnchors = new HashSet<>();
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
            // Fix2 (#1, option b — 文档化窄拓扑): 物化道同时 (a) 吃常驻 SFI 投料 且 (b) 被同小结内下游在制道
            //   部分消耗时, withinFeed 切片经在制 WIP MaterialBatch.unitPrice (= totalCost/outQty, 保存期已固化,
            //   不含 SFI 投料成本) 继承给下游 → 该切片的 SFI 投料成本对下游静默漏计 (低估)。net 切片本身入库成本正确。
            //   保存期已持久化的 MaterialConsumption 边无法在小结回改 (edge 成本 = feedKg×WIP单价 保存时已写), 且
            //   回改会牵动整条下游成本图 (invasive) → 采 option b: 告警记录, 不静默。窄拓扑, 实务少见。
            if (withinFeed.signum() > 0 && hasSemiFinishedFeed(ur.req)) {
                log.warn("[interim-settle] 混合拓扑成本基差 (#1): 物化道 batchNo={} 同时吃 SFI 投料且被同小结下游消耗 "
                                + "{}kg (>net 部分), 被消耗切片经在制 WIP 单价继承 → 其 SFI 投料成本对下游漏计 (低估). "
                                + "入 SFI 的 net 切片成本正确. 精确核算请拆分录入或经 SFI 中转",
                        batchNo, withinFeed.stripTrailingZeros().toPlainString());
            }
            // 🔴 成本传导: 本道产出单位成本 = (本道成本 + SFI 投料成本) / 本道全产出量 (非 net —
            //   net 与 withinFeed 同单价, withinFeed 部分的成本经在制 WIP 消耗边继承给下游道, 不双计)。
            //   诚实 null: 任一 SFI 投料 unitCost 未知 → outputUnitCost 为 null (postClerkOutput 不摊假成本)。
            BigDecimal outputUnitCost = computeOutputUnitCost(factoryId, ur, outQty, laborRate);
            String inAnchor = semiAnchor(planId, ur.req.getProductTypeId());
            // processOrder: 产出此 SFI 的道序 (持久化行 ur.row) → 传给锚, picker 阶段可见性过滤依赖它。
            //   锚跨多道/多次小结 → postClerkOutput 内取 MIN (最早道)。历史行 processOrder 可空, 容忍。
            wipInventoryService.postClerkOutput(factoryId,
                    inAnchor, ur.req.getProductTypeId(),
                    net, ur.req.getUnit(), outputUnitCost, null, ur.row.getProcessOrder());
            semiInBatchNumbers.add(batchNo);
            semiInQuantity = semiInQuantity.add(net);
            // 撤销明细: 按锚累计净量 + 确切成本 (mirror postClerkOutput 内 totalCost = inUnitCost×inQty)。
            //   任一入库段成本已知 → 该锚 totalCost 累加; 段成本 null 不加 (与 accumulatedCost 语义一致)。
            sfiInQtyByAnchor.merge(inAnchor, net, BigDecimal::add);
            if (outputUnitCost != null) {
                sfiInCostByAnchor.merge(inAnchor, outputUnitCost.multiply(net), BigDecimal::add);
                sfiInCostKnownAnchors.add(inAnchor);
            }
        }

        // ── ④ FG: 未结成品道产出 (优先成品重 productWeight) → 成品库 ──
        List<String> finishedBatchNumbers = new ArrayList<>();
        BigDecimal finishedQuantity = BigDecimal.ZERO;
        // 撤销明细: 每个成品批次实际入库量 (dedupe by batchNumber — createFinishedGoodsForInterim 对同一
        //   session batchNumber orElseGet 只首行入库, 故只记首次 = 批次实际 producedQuantity) → reverseInterimCreate 逆转。
        List<Map<String, Object>> fgCreatedDetail = new ArrayList<>();
        Set<String> fgCreatedSeen = new HashSet<>();
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
            // dedupe: 同 session 多成品道共享 batchNumber, createFinishedGoodsForInterim 只首行实际入库 →
            //   撤销明细只记首次 (= 批次 producedQuantity), 避免 reverseInterimCreate 重复扣。
            if (fgCreatedSeen.add(fg.getBatchNumber())) {
                fgCreatedDetail.add(qtyDetail("batchNumber", fg.getBatchNumber(), qty));
            }
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
        summary.put("finishedGoodsOutQuantity", finishedGoodsOutQuantity);   // ①c FG 投料扣减量
        summary.put("finishedGoodsBatchNumbers", finishedBatchNumbers);
        summary.put("finishedQuantity", finishedQuantity);

        // ── 撤销小结 (reversal) 明细: 逐笔库存动作, 供「撤销小结」精确逆转 (数量存字符串防 JSON 数值精度漂移) ──
        List<Map<String, Object>> sfiInDetail = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : sfiInQtyByAnchor.entrySet()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("anchor", e.getKey());
            d.put("qty", e.getValue().toPlainString());
            // totalCost 诚实 null: 该锚无任何成本已知段 → null (撤销时不减 accumulatedCost)。
            d.put("totalCost", sfiInCostKnownAnchors.contains(e.getKey())
                    ? sfiInCostByAnchor.get(e.getKey()).toPlainString() : null);
            sfiInDetail.add(d);
        }
        Map<String, Object> reversalDetail = new LinkedHashMap<>();
        reversalDetail.put("sfiIn", sfiInDetail);
        reversalDetail.put("fgCreated", fgCreatedDetail);
        reversalDetail.put("sfiOutStrict", sfiOutStrictDetail);
        reversalDetail.put("sfiOutAnchor", sfiOutAnchorDetail);
        reversalDetail.put("fgFeed", fgFeedDetail);
        summary.put("reversalDetail", reversalDetail);

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
        var existing = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumber(plan.getFactoryId(), batchNumber);
        if (existing.isPresent()) {
            FinishedGoodsBatch batch = existing.get();
            // 🔒🔒 2026-07-03 撤销→重新小结 (bug #3, 确定性 100% 失败修复):
            //   「撤销小结」把该 batchNumber 的 FG 冲销为 producedQuantity=0 + status=REVERSED (审计尸体,
            //   不硬删批次行), 并硬删小结记录释放 session_seq。重新小结复用同 seq → 生成同 batchNumber。
            //   旧代码 findByFactoryIdAndBatchNumber 无状态过滤命中 REVERSED 尸体 → orElseGet 不触发 →
            //   数量不入 (但下方 summary.finishedQuantity 仍累加) → 假报产量, FG 实为 0/REVERSED → 发货无货。
            //   此处显式"复活": 把尸体重置为本次小结的真实产量 + AVAILABLE + 可售。REVERSED 尸体必然
            //   shipped/reserved/produced 皆 0 (reverseInterimCreate 的下游守卫: 已发货/预留即 loud-fail
            //   不会置 REVERSED), 故重置安全。复活后的批次进本次 summary.reversalDetail.fgCreated →
            //   未来可再次撤销 (可逆性保持)。
            if (batch.getStatus() == FinishedGoodsBatch.Status.REVERSED) {
                ProductType productType = productTypeRepository
                        .findByIdAndFactoryId(productTypeId, plan.getFactoryId()).orElse(null);
                populateInterimFinishedGoods(batch, plan, productType, productTypeId, qty, unit, userId, unitCost);
                batch.setRemark("库存生产小结第 " + sessionSeq + " 次入库(撤销后重做): " + plan.getPlanNumber());
                return finishedGoodsBatchRepository.save(batch);
            }
            // 非 REVERSED (同次小结多成品道去重命中 / 真实已产 AVAILABLE/DEPLETED) → 原样返回 (幂等, 不覆盖真实数据)。
            return batch;
        }
        ProductType productType = productTypeRepository
                .findByIdAndFactoryId(productTypeId, plan.getFactoryId()).orElse(null);
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setFactoryId(plan.getFactoryId());
        batch.setBatchNumber(batchNumber);
        populateInterimFinishedGoods(batch, plan, productType, productTypeId, qty, unit, userId, unitCost);
        batch.setRemark("库存生产小结第 " + sessionSeq + " 次入库: " + plan.getPlanNumber());
        return finishedGoodsBatchRepository.save(batch);
    }

    /**
     * 填充/重置小结成品批次的业务字段 (不含 factoryId/batchNumber/remark — 由调用方设置)。
     * 新建 (create) 与撤销后复活 (reset REVERSED 尸体) 共用, 保证两路径字段一致。
     */
    private void populateInterimFinishedGoods(FinishedGoodsBatch batch, ProductionPlan plan, ProductType productType,
                                              String productTypeId, BigDecimal qty, String unit,
                                              Long userId, BigDecimal unitCost) {
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
     * <p><b>纯 SFI 调味道 (Fix1 #7, 禁止降级)</b>: 纯 SFI 中间道若为熟制/调味道, 其调料成本
     * (RecipeCostCalculator) 此路径无法现算 (需锅序/配方, 属重算)。此时**不得**只算 labor 降级成非-null 假数据 —
     * 直接返回 <b>null</b> (诚实, 调料桶未知 = 整道成本未知), 同 null-SFI-feed 输入处理。物化的熟制道 (含原料)
     * 走 ProductionBatch.totalCost 已含调料, 不受影响。判定见 {@link #isSeasoningRow}。
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
            // 纯 SFI 中间道 (SAVED_SFI, 不物化 → RecipeCostCalculator 从未运行, 本道调料成本无处可算)。
            //   🔴 Fix1 (#7): 若本道是调味/熟制道 → 调料桶未知, 绝不可只算 labor 降级成非-null 假数据 (违 禁止降级)。
            //     诚实 null (与 null-SFI-feed 输入同处理)。非调味道 (切/分/拆包 等) 无调料桶 → base = 本道人工 (现算)。
            if (isSeasoningRow(factoryId, ur.req)) {
                log.warn("[interim-settle] 纯半成品调味道 (batchNo={}, process={}) 无法现算调料成本 "
                                + "(SAVED_SFI 不物化 → 无 RecipeCostCalculator) → 产出成本诚实 null (不漏调料桶=禁止降级); "
                                + "如需成本核算请改走物化录入路径",
                        ur.row.getBatchNumber(), ur.req.getProcessCode());
                return null;
            }
            baseTotal = nz(clerkProcessEntryService.computeLaborCost(ur.req.getLaborSegments(), laborRate));
        }

        // Σ 外部库存投料成本 (feedKg × 输入 SFI/FG.unitCost); 任一 unitCost null → 诚实 null。
        //   SFI 投料 (semiFinished) 读 SFI 移动均价; FG 投料 (finishedGoods, ①c) 读成品 unitCost。
        //   两者都不在 ProductionBatch.totalCost 内 (投料边不写 MaterialConsumption), 故此处单独补进。
        BigDecimal stockFeedCost = BigDecimal.ZERO;
        if (ur.req.getUpstreamSources() != null) {
            for (ProcessSheetRowRequest.UpstreamRef ref : ur.req.getUpstreamSources()) {
                boolean semi = ref.isSemiFinished();
                boolean fg = ref.isFinishedGoods();
                if (!semi && !fg) {
                    continue; // 在制 WIP 投料的成本已在 ProductionBatch.totalCost (MaterialConsumption 边) 内
                }
                BigDecimal feed = nz(ref.getFeedQuantityKg());
                if (feed.signum() <= 0) {
                    continue;
                }
                // 盒⇄kg 折算: 计数单位来源 (盒装成品/半成品) 把 kg 投料折算为原生单位数量 (盒数), 成本 = 盒数 × 每盒 unitCost;
                //   kg 源 → feed 原值 × 每kg unitCost (行为不变)。与扣减侧 consumeForFeedStrict/consumeClerkSemiStrict 折算同源。
                //   🔴 诚实 null: 盒装来源缺每盒克重 → 无法折算 → 整道产出成本未知 (禁止臆造)。
                BigDecimal feedInSourceUnit = fg
                        ? finishedGoodsFeedService.resolveFeedQtyInSourceUnit(factoryId, ref.getSourceBatchNumber(), feed)
                        : wipInventoryService.resolveSemiFeedQtyInSourceUnit(factoryId, ref.getSourceBatchNumber(), feed);
                if (feedInSourceUnit == null) {
                    return null; // 🔴 诚实 null: 盒装来源缺每盒克重折算 → 本道产出成本未知, 不伪造
                }
                BigDecimal inputUnitCost = fg
                        ? finishedGoodsFeedService.getFeedUnitCost(factoryId, ref.getSourceBatchNumber())
                        : wipInventoryService.getSemiUnitCost(factoryId, ref.getSourceBatchNumber());
                if (inputUnitCost == null) {
                    return null; // 🔴 诚实 null: 输入半成品/成品无成本 → 本道产出成本未知, 不伪造 ¥0
                }
                stockFeedCost = stockFeedCost.add(feedInSourceUnit.multiply(inputUnitCost));
            }
        }

        return baseTotal.add(stockFeedCost).divide(outputQty, 4, RoundingMode.HALF_UP);
    }

    /** 调味道工序名正则 — 与 {@code ProcessSheetServiceImpl.buildStepEntry} / materializeBatch 警告分支同源, 不另造。 */
    private static final java.util.regex.Pattern SEASONING_NAME_PATTERN =
            java.util.regex.Pattern.compile(".*(熟|卤|煮|腌|注射|入味|调味).*");

    /**
     * Fix1 (#7): 判定一行是否"调味/熟制道" —— 与 {@code buildStepEntry} 决定 processCategory=SEASONING 完全同口径:
     * {@code req.isSeasoningStep()} 标志为真, 或工序名匹配 {@link #SEASONING_NAME_PATTERN}。
     *
     * <p>工序名解析与 buildStepEntry 一致: payload {@code processName} 优先; 前端逐道录入通常不传 processName,
     * 故缺失时按 {@code (productTypeId, processOrder)} 反查真实工序名 (product-work-process 链), 避免漏判。
     */
    private boolean isSeasoningRow(String factoryId, ProcessSheetRowRequest req) {
        if (req.isSeasoningStep()) {
            return true;
        }
        String name = req.getProcessName();
        if ((name == null || name.isBlank())
                && req.getProductTypeId() != null && req.getProcessOrder() != null) {
            name = resolveProcessName(factoryId, req.getProductTypeId(), req.getProcessOrder());
        }
        return name != null && SEASONING_NAME_PATTERN.matcher(name).matches();
    }

    /** 按 (productTypeId, processOrder) 反查真实工序名 (product-work-process → work_process.processName); 取不到 → null。 */
    private String resolveProcessName(String factoryId, String productTypeId, Integer processOrder) {
        for (ProductWorkProcess pwp : productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, productTypeId)) {
            if (processOrder.equals(pwp.getProcessOrder()) && pwp.getWorkProcessId() != null) {
                return workProcessRepository.findById(pwp.getWorkProcessId())
                        .map(WorkProcess::getProcessName).orElse(null);
            }
        }
        return null;
    }

    /** 本行是否含常驻外部库存投料 (SFI semiFinished=true 或 FG finishedGoods=true 上游) —
     *  这类投料成本不在 ProductionBatch.totalCost 内, 混合拓扑下对下游可能漏计 (见调用处 #1 告警)。 */
    private boolean hasSemiFinishedFeed(ProcessSheetRowRequest req) {
        return req.getUpstreamSources() != null
                && req.getUpstreamSources().stream()
                        .anyMatch(u -> u.isSemiFinished() || u.isFinishedGoods());
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

    /** 撤销明细条目: {keyName: id, "qty": qty.toPlainString()} —— 量存字符串防 jsonb 数值精度漂移。 */
    private static Map<String, Object> qtyDetail(String keyName, String id, BigDecimal qty) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put(keyName, id);
        d.put("qty", nz(qty).toPlainString());
        return d;
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
