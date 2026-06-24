package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO.SourceCost;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.cretas.aims.dto.yield.OrderCostBreakdownDTO.ByproductLine;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO.PackagingItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 订单级单盒成本拆分 — 单一权威服务 (T1 谱系遍历 + T2 上游成本回溯 + T3 人工归集).
 *
 * <p>原料成本沿 {@link MaterialConsumption} 边回溯: 若上游 {@link MaterialBatch} 由某生产批次产出
 * ({@code source_doc_type='PRODUCTION_BATCH'}), 递归进其消耗 (多级); 否则该消耗即叶子, 成本=其
 * 实测 total_cost (= 该上游链累计成本)。混批按各批实测投料量×各自单价精确归集 (非按重量糊平均)。
 *
 * <p>人工/调料/包装来自该批 {@code ProductionReport} 逐道报工 (复用 {@link YieldReportService#getYield});
 * 首道(原料道)材料不计入 (原料由上游 traced 承载, 避免双计)。
 *
 * <p>价格脱敏: maskPrice 时金额字段全置 null, 仅保留投料量/重量占比 (红线: procurement:price:view)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCostBreakdownService {

    private final ProductionPlanRepository planRepository;
    private final ProductionBatchRepository batchRepository;
    private final MaterialConsumptionRepository consumptionRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final com.cretas.aims.repository.ProductionReportRepository productionReportRepository;
    private final YieldReportService yieldReportService;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MAX_DEPTH = 10;

    public OrderCostBreakdownDTO compute(String factoryId, String orderId, boolean maskPrice) {
        List<String> planIds = planRepository.findByFactoryIdAndSourceOrderId(factoryId, orderId).stream()
                .map(ProductionPlan::getId).filter(Objects::nonNull).collect(Collectors.toList());
        if (planIds.isEmpty()) {
            return empty(orderId, maskPrice);
        }
        List<ProductionBatch> batches = batchRepository.findByFactoryIdAndProductionPlanIdIn(factoryId, planIds);
        if (batches.isEmpty()) {
            return empty(orderId, maskPrice);
        }
        return computeForBatches(factoryId, orderId, batches, maskPrice);
    }

    /**
     * SP-C: 按批次号查单盒成本 (存货生产无订单号场景).
     * findByFactoryIdAndBatchNumber 是 factory-scoped — 跨租户安全。
     * DTO.orderId 字段填 batchNumber (前端仅作展示 label)。
     */
    public OrderCostBreakdownDTO computeByBatch(String factoryId, String batchNumber, boolean maskPrice) {
        ProductionBatch b = batchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                .orElseThrow(() -> new com.cretas.aims.exception.BusinessException(404, "生产批次不存在: " + batchNumber));
        return computeForBatches(factoryId, batchNumber, List.of(b), maskPrice);
    }

    /**
     * 内核: 已有 batches 列表 → 成本归集 → OrderCostBreakdownDTO.
     * by-order 与 by-batch 共用此方法; {@code label} 填入 DTO.orderId (展示用, by-order=orderId, by-batch=batchNumber).
     * <p>纯 extract-method: 无任何逻辑变更, by-order 行为零回归。
     */
    private OrderCostBreakdownDTO computeForBatches(String factoryId, String label,
                                                     List<ProductionBatch> batches, boolean maskPrice) {
        BigDecimal labor = BigDecimal.ZERO;
        BigDecimal seasoning = BigDecimal.ZERO;
        BigDecimal packaging = BigDecimal.ZERO;
        BigDecimal raw = BigDecimal.ZERO;
        int boxCount = 0;
        int sampleRetain = 0;             // 留样数 (不可售; Σ各道, 通常仅末道)
        BigDecimal waste = BigDecimal.ZERO; // 损耗/料头量 (仅展示; 已体现在出成率, 不二次扣成本)
        List<SourceCost> sources = new ArrayList<>();
        // 副产物按 名称|单位 归集 (跨批次跨道)
        LinkedHashMap<String, ByproductLine> byproductAcc = new LinkedHashMap<>();
        // AUDIT-002 包装明细按 名称 归集成本 (跨批次跨道)
        LinkedHashMap<String, BigDecimal> packagingAcc = new LinkedHashMap<>();
        // AUDIT-004 辅料按锅分摊明细 (按 锅号 去重)
        LinkedHashMap<String, OrderCostBreakdownDTO.AuxiliaryAllocation> auxAllocAcc = new LinkedHashMap<>();

        for (ProductionBatch b : batches) {
            BatchYieldDTO y = yieldReportService.getYield(factoryId, b.getId());
            if (y != null) {
                if (y.getTotalLaborCost() != null) {
                    labor = labor.add(y.getTotalLaborCost());
                }
                List<StepYieldDTO> steps = y.getSteps() == null ? List.of() : y.getSteps();
                for (int i = 0; i < steps.size(); i++) {
                    accumulateByproducts(byproductAcc, steps.get(i).getByproducts());
                    if (steps.get(i).getSampleRetainQuantity() != null) {
                        sampleRetain += steps.get(i).getSampleRetainQuantity();
                    }
                    if (steps.get(i).getWasteQuantity() != null) {
                        waste = waste.add(steps.get(i).getWasteQuantity());
                    }
                    accumulatePackaging(packagingAcc, steps.get(i).getPackagingDetail());
                    BigDecimal m = steps.get(i).getMaterialCost();
                    if (m == null) {
                        continue;
                    }
                    // CALC-003: 显式 costCategory 优先分类; null/未知 → 回退 step-index 启发式 (向后兼容)
                    String bucket = resolveCostBucket(steps.get(i).getCostCategory(), i, steps.size(), factoryId, label);
                    if ("PACKAGING".equals(bucket)) {
                        packaging = packaging.add(m);
                    } else if ("SEASONING".equals(bucket)) {
                        // AUDIT-004: 该道辅料若标了共享锅 → 按产出量分摊本批 share (替代报工 material_cost); 否则原样计
                        BigDecimal contribution = m;
                        StepYieldDTO step = steps.get(i);
                        if (step.getAuxPotNo() != null && step.getAuxPotTotalCost() != null) {
                            BigDecimal potOutput = nz(productionReportRepository.sumOutputByAuxPotNo(factoryId, step.getAuxPotNo()));
                            BigDecimal myOutput = nz(step.getTotalOutput());
                            BigDecimal share = potOutput.signum() > 0
                                    ? step.getAuxPotTotalCost().multiply(myOutput).divide(potOutput, 2, RoundingMode.HALF_UP)
                                    : step.getAuxPotTotalCost();
                            contribution = share;
                            auxAllocAcc.put(step.getAuxPotNo(), OrderCostBreakdownDTO.AuxiliaryAllocation.builder()
                                    .potNo(step.getAuxPotNo())
                                    .method(step.getAuxAllocMethod())
                                    .potTotalCost(step.getAuxPotTotalCost())
                                    .potTotalOutput(potOutput)
                                    .batchOutput(myOutput)
                                    .batchShare(share)
                                    .batchSharePct(potOutput.signum() > 0
                                            ? myOutput.multiply(HUNDRED).divide(potOutput, 1, RoundingMode.HALF_UP) : null)
                                    .build());
                        }
                        seasoning = seasoning.add(contribution);
                    }
                    // "SKIP" → 原料由上游 traced consumption 承载, 不计 (避免双计)
                }
            }
            if (b.getQuantity() != null) {
                boxCount += b.getQuantity().intValue();
            }
            for (MaterialConsumption c : consumptionRepository.findByProductionBatchIdAndFactoryId(b.getId(), factoryId)) {
                java.util.Set<Long> visited = new java.util.HashSet<>();
                visited.add(b.getId());   // 起点批次入环检测集
                BigDecimal[] leaf = traceCost(factoryId, c, 1, visited);
                BigDecimal cost = leaf[0];
                int depth = leaf[1].intValue();
                raw = raw.add(cost);
                MaterialBatch mb = c.getBatchId() == null ? null
                        : materialBatchRepository.findByIdAndFactoryId(c.getBatchId(), factoryId).orElse(null);
                sources.add(SourceCost.builder()
                        .batchId(c.getBatchId())
                        .batchName(mb != null ? mb.getBatchNumber() : c.getBatchId())
                        .quantity(c.getQuantity())
                        .unit(mb != null ? mb.getQuantityUnit() : "kg")
                        .unitPrice(c.getUnitPrice())
                        .cost(cost)
                        .depth(depth)
                        .build());

                // ★ SP-F ①b: 沿 WIP 链回溯上游 *生产批次* 的 人工/调料, 按与 traceCost 相同的
                //   consumedQty/upstreamReceiptQty 比例分摊累加。本批自身的人工/调料已由上方 getYield
                //   循环计入, 故只从上游(WIP 产出批)起累加, 不重复计本批。
                //   只有 clerk/逐工序录入链会产出 MaterialBatch(sourceDocType=PRODUCTION_BATCH)
                //   被 MaterialConsumption 消耗 —— 常规生产批不走此链, 故对常规批零影响 (见 traceCost javadoc)。
                java.util.Set<Long> chainVisited = new java.util.HashSet<>();
                chainVisited.add(b.getId());
                BigDecimal[] up = aggregateUpstreamLaborSeasoning(factoryId, c, 1, chainVisited);
                labor = labor.add(up[0]);
                seasoning = seasoning.add(up[1]);
            }
        }

        // 包装总额: 若无 PACKAGING materialCost 报工(packaging==0)但有包装明细 → 用明细总额。
        // 文员逐道录入(SP-F)的气调步只写 packaging_detail 明细不写 materialCost-PACKAGING;
        // M67 等有 materialCost-PACKAGING 的批 packaging>0 → 不重复加明细。严格测试 2026-06-24 抓到。
        if (packaging.signum() == 0 && !packagingAcc.isEmpty()) {
            packaging = packagingAcc.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        BigDecimal total = labor.add(seasoning).add(packaging).add(raw);
        BigDecimal perBox = boxCount > 0 ? total.divide(BigDecimal.valueOf(boxCount), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        List<ByproductLine> byproducts = new ArrayList<>(byproductAcc.values());
        BigDecimal byproductCredit = byproducts.stream().map(l -> nz(l.getValue())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netTotal = total.subtract(byproductCredit);
        BigDecimal netPerBox = boxCount > 0 ? netTotal.divide(BigDecimal.valueOf(boxCount), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // 可售成本: 留样产出但不可售 → 净成本摊到 (产出盒数 − 留样数), 每个售出盒真实承担留样成本。
        // 损耗/料头(waste)已体现在出成率(产出更低→盒数更少), 不二次扣, 仅展示。
        int sellableBoxCount = Math.max(0, boxCount - sampleRetain);
        BigDecimal sellablePerBox = sellableBoxCount > 0
                ? netTotal.divide(BigDecimal.valueOf(sellableBoxCount), 2, RoundingMode.HALF_UP) : netPerBox;

        // AUDIT-002 包装明细 (按名称归集成本; null=未拆)
        List<PackagingItem> packagingDetail = packagingAcc.isEmpty() ? null
                : packagingAcc.entrySet().stream()
                        .map(e -> PackagingItem.builder().name(e.getKey()).cost(e.getValue()).build())
                        .collect(Collectors.toList());

        // AUDIT-004 辅料按锅分摊明细 (null=无共享锅)
        List<OrderCostBreakdownDTO.AuxiliaryAllocation> auxiliaryAllocations =
                auxAllocAcc.isEmpty() ? null : new ArrayList<>(auxAllocAcc.values());

        BigDecimal totalQty = sources.stream().map(s -> nz(s.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (SourceCost s : sources) {
            s.setWeightSharePct(totalQty.signum() > 0
                    ? nz(s.getQuantity()).multiply(HUNDRED).divide(totalQty, 1, RoundingMode.HALF_UP) : null);
            s.setCostSharePct(raw.signum() > 0
                    ? nz(s.getCost()).multiply(HUNDRED).divide(raw, 1, RoundingMode.HALF_UP) : null);
        }

        OrderCostBreakdownDTO dto = OrderCostBreakdownDTO.builder()
                .orderId(label)
                .boxCount(boxCount)
                .hasData(true)
                .priceMasked(maskPrice)
                .rawMaterialCost(raw)
                .laborCost(labor)
                .seasoningCost(seasoning)
                .packagingCost(packaging)
                .totalCost(total)
                .perBoxCost(perBox)
                .byproductCredit(byproductCredit)
                .netTotalCost(netTotal)
                .netPerBoxCost(netPerBox)
                .byproducts(byproducts)
                .sampleRetainCount(sampleRetain)
                .wasteQuantity(waste.signum() > 0 ? waste : null)
                .sellableBoxCount(sellableBoxCount)
                .sellablePerBoxCost(sellablePerBox)
                .packagingDetail(packagingDetail)
                .auxiliaryAllocations(auxiliaryAllocations)
                .sources(sources)
                .build();
        if (maskPrice) {
            maskCosts(dto);
        }
        return dto;
    }

    /**
     * T1 递归回溯: 返回 [该消耗回溯到的成本, 谱系深度].
     * 上游批次由生产批次产出且有消耗 → 递归取其上游成本和 (回溯到更上游); 否则叶子 = consumption.totalCost。
     *
     * <p><b>共享上游按消耗比例分摊 (SP-B1):</b> 若上游批次被多个下游消耗, 每个下游只取上游总成本的
     * {@code consumedQty / upstreamReceiptQty} 份额, 防止双重计数。
     * <br>向后兼容论证: 既有单链 1:1 全量消耗时 consumedQty == upstreamReceiptQty → 比例=1 →
     * apportioned == upstreamSum, 与改前一致。只有「部分消耗/共享上游」才缩放。
     * <br>缺量兜底: consumedQty 或 upstreamReceiptQty 为 null/0 时退回 own
     * (consumption.totalCost = 写入时 qty×单价 的切片, 已是合理估值)。
     */
    private BigDecimal[] traceCost(String factoryId, MaterialConsumption c, int depth, java.util.Set<Long> visited) {
        BigDecimal own = nz(c.getTotalCost());
        if (c.getBatchId() == null) {
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};
        }
        if (depth >= MAX_DEPTH) {
            log.warn("[M67CostBreakdown] traceCost 达 MAX_DEPTH={} (factory={}, batchId={}) — 按叶子截断, 疑似超深链/环", MAX_DEPTH, factoryId, c.getBatchId());
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};
        }
        MaterialBatch mb = materialBatchRepository.findByIdAndFactoryId(c.getBatchId(), factoryId).orElse(null);
        if (mb == null || !"PRODUCTION_BATCH".equalsIgnoreCase(mb.getSourceDocType()) || mb.getSourceDocId() == null) {
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};   // 叶子 (原料/外购)
        }
        Long upstreamBatchId = parseLong(mb.getSourceDocId());
        if (upstreamBatchId == null) {
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};
        }
        if (!visited.add(upstreamBatchId)) {   // 环检测: 该上游批次已在当前回溯路径
            log.warn("[M67CostBreakdown] 检测到批次谱系环 (factory={}, upstreamBatchId={}) — 截断防重复计成本", factoryId, upstreamBatchId);
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};
        }
        List<MaterialConsumption> up = consumptionRepository.findByProductionBatchIdAndFactoryId(upstreamBatchId, factoryId);
        if (up.isEmpty()) {
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};
        }
        BigDecimal sum = BigDecimal.ZERO;
        int maxChildDepth = depth;
        for (MaterialConsumption u : up) {
            BigDecimal[] r = traceCost(factoryId, u, depth + 1, visited);
            sum = sum.add(r[0]);
            maxChildDepth = Math.max(maxChildDepth, r[1].intValue());
        }
        // ★ SP-B1: 按消耗比例分摊上游成本, 防共享上游双重计数。
        // 向后兼容(Opus 终审收紧): 仅当 consumedQty 与 upstreamReceiptQty 都可用时才分摊;
        // 1:1 全量消耗时 consumedQty==upstreamReceiptQty → 比例=1 → apportioned==sum (无变化)。
        // 缺量兜底**严格保留改前行为** (sum>0?sum:own), 不切换到 own —— 避免影响 receiptQuantity
        // 缺失的既有订单成本数据。新 SP-B1 物化的 WIP 批必带 receiptQuantity, 故分摊分支总能命中。
        // ★ 严格审计 2026-06-25 (Edge G): 真实 0 消耗 (consumedQty 显式 = 0, 非 null) →
        // 本批未从该上游源取料, 贡献 0 成本。**显式 0 必须早返回**, 否则下方 legacy 兜底
        // (sum>0?sum:own) + L 末 (apportioned>0?apportioned:own) 两处都会把它推成"全量", 造成
        // 混批 feed=0 的源被全额计入 → 原料虚高 (Edge G: g1投50+g2投0, 原料应 466 实得 932)。
        // 仅对**显式 0** 生效; consumedQty 为 null (缺量旧数据) 仍走下方 legacy 兜底, 零回归。
        if (c.getQuantity() != null && c.getQuantity().signum() == 0) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.valueOf(maxChildDepth)};
        }
        BigDecimal legacy = sum.signum() > 0 ? sum : own;
        BigDecimal consumedQty = nz(c.getQuantity());
        BigDecimal upstreamQty = nz(mb.getReceiptQuantity());
        BigDecimal apportioned;
        if (sum.signum() > 0 && consumedQty.signum() > 0 && upstreamQty.signum() > 0) {
            apportioned = sum.multiply(consumedQty)
                    .divide(upstreamQty, 4, java.math.RoundingMode.HALF_UP);
        } else {
            apportioned = legacy;   // 缺量数据 → 改前行为, 既有数据零回归
        }
        return new BigDecimal[]{apportioned.signum() > 0 ? apportioned : own, BigDecimal.valueOf(maxChildDepth)};
    }

    /**
     * SP-F ①b: 沿 WIP 链回溯, 累加上游 *生产批次* 的 人工/调料 成本, 按与 {@link #traceCost} 完全相同的
     * {@code consumedQty/upstreamReceiptQty} 比例分摊。返回 {@code [labor, seasoning]}。
     *
     * <p><b>仅 WIP 链 (clerk/逐工序录入) 受影响:</b> 只有当某消耗的上游 {@link MaterialBatch} 是
     * {@code sourceDocType='PRODUCTION_BATCH'} 且该生产批有自己的消耗时, 才递归进上游并累加其
     * 人工/调料。常规生产批次不产出此类 MaterialBatch 被消耗 (它们经成品库存/SemiFinishedInventory 流转,
     * 非 MaterialConsumption→MaterialBatch(PRODUCTION_BATCH) 链), 故 {@code aggregate} 在常规批上
     * 命中 leaf-分支立即返回 [0,0] —— 对常规批成本拆分零回归。
     *
     * <p>本批自身的人工/调料<b>不</b>在此累加 (已由 computeForBatches 的 getYield 主循环计入);
     * 此方法从上游 (depth≥2) 起累加, 避免双计。叶子/缺量/环/超深 → 返回 [0,0]。
     *
     * <p>分摊镜像 traceCost: 上游成本 sum × consumedQty/upstreamReceiptQty。1:1 全量消耗时比例=1。
     * 缺量 (consumedQty/upstreamReceiptQty 任一为 null/0) → 不分摊, 取上游全额 (与 traceCost 的
     * legacy 兜底一致, WIP 物化批必带 receiptQuantity 故分摊分支总命中)。
     */
    private BigDecimal[] aggregateUpstreamLaborSeasoning(String factoryId, MaterialConsumption c,
                                                         int depth, java.util.Set<Long> visited) {
        BigDecimal[] zero = {BigDecimal.ZERO, BigDecimal.ZERO};
        if (c.getBatchId() == null || depth >= MAX_DEPTH) {
            return zero;
        }
        MaterialBatch mb = materialBatchRepository.findByIdAndFactoryId(c.getBatchId(), factoryId).orElse(null);
        if (mb == null || !"PRODUCTION_BATCH".equalsIgnoreCase(mb.getSourceDocType()) || mb.getSourceDocId() == null) {
            return zero;   // 叶子 (原料/外购) — 无上游生产批, 无可累加的人工/调料
        }
        Long upstreamBatchId = parseLong(mb.getSourceDocId());
        if (upstreamBatchId == null || !visited.add(upstreamBatchId)) {
            return zero;   // 解析失败 或 环 → 截断
        }
        // 上游生产批自身的 人工/调料 (该批的 getYield)
        BigDecimal[] self = batchLaborSeasoning(factoryId, upstreamBatchId);
        BigDecimal labor = self[0];
        BigDecimal seasoning = self[1];
        // 再递归该上游批的消耗 → 更上游的 人工/调料
        for (MaterialConsumption u : consumptionRepository.findByProductionBatchIdAndFactoryId(upstreamBatchId, factoryId)) {
            BigDecimal[] r = aggregateUpstreamLaborSeasoning(factoryId, u, depth + 1, visited);
            labor = labor.add(r[0]);
            seasoning = seasoning.add(r[1]);
        }
        // ★ 按消耗比例分摊 (镜像 traceCost): 上游(含更上游)的 人工/调料 × consumedQty/upstreamReceiptQty。
        BigDecimal consumedQty = nz(c.getQuantity());
        BigDecimal upstreamQty = nz(mb.getReceiptQuantity());
        if (consumedQty.signum() > 0 && upstreamQty.signum() > 0) {
            labor = labor.multiply(consumedQty).divide(upstreamQty, 4, RoundingMode.HALF_UP);
            seasoning = seasoning.multiply(consumedQty).divide(upstreamQty, 4, RoundingMode.HALF_UP);
        }
        // 缺量 → 取上游全额 (不分摊), 与 traceCost legacy 兜底一致。
        return new BigDecimal[]{labor, seasoning};
    }

    /**
     * SP-F ①b: 计算单个生产批次 *自身* (不含上游链) 的 人工/调料 成本, 返回 {@code [labor, seasoning]}。
     *
     * <p>人工 = {@code getYield(batchId).totalLaborCost}。调料 = Σ 该批各道 SEASONING 桶 materialCost
     * (按 {@link #resolveCostBucket} 分类; 与 computeForBatches 主循环同口径, 含 AUDIT-004 共享锅分摊)。
     * 这是为 WIP 上游批准备的 —— clerk WIP 批的调料报工不带共享锅 (auxPotNo=null), 故走原样累加分支。
     */
    private BigDecimal[] batchLaborSeasoning(String factoryId, Long batchId) {
        BatchYieldDTO y = yieldReportService.getYield(factoryId, batchId);
        if (y == null) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        }
        BigDecimal labor = nz(y.getTotalLaborCost());
        BigDecimal seasoning = BigDecimal.ZERO;
        List<StepYieldDTO> steps = y.getSteps() == null ? List.of() : y.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            StepYieldDTO step = steps.get(i);
            BigDecimal m = step.getMaterialCost();
            if (m == null) {
                continue;
            }
            String bucket = resolveCostBucket(step.getCostCategory(), i, steps.size(), factoryId, "WIP:" + batchId);
            if (!"SEASONING".equals(bucket)) {
                continue;   // 只取 SEASONING 桶; PACKAGING/SKIP 不计 (包装由末道成品计, 原料由 traceCost 承载)
            }
            BigDecimal contribution = m;
            if (step.getAuxPotNo() != null && step.getAuxPotTotalCost() != null) {
                BigDecimal potOutput = nz(productionReportRepository.sumOutputByAuxPotNo(factoryId, step.getAuxPotNo()));
                BigDecimal myOutput = nz(step.getTotalOutput());
                contribution = potOutput.signum() > 0
                        ? step.getAuxPotTotalCost().multiply(myOutput).divide(potOutput, 2, RoundingMode.HALF_UP)
                        : step.getAuxPotTotalCost();
            }
            seasoning = seasoning.add(contribution);
        }
        return new BigDecimal[]{labor, seasoning};
    }

    /**
     * 归集副产物明细 (名称|单位 维度跨批次累加)。变现价值 = quantity×unitPrice;
     * 报工 jsonb 可直接录 value/totalValue (优先), 否则按 quantity×unitPrice; 单价缺失 → value=null (诚实, 不臆造)。
     */
    private void accumulateByproducts(LinkedHashMap<String, ByproductLine> acc, List<Map<String, Object>> raws) {
        if (raws == null) {
            return;
        }
        for (Map<String, Object> r : raws) {
            if (r == null) {
                continue;
            }
            String name = r.get("name") == null ? "副产物" : String.valueOf(r.get("name")).trim();
            String unit = r.get("unit") == null ? "" : String.valueOf(r.get("unit")).trim();
            BigDecimal qty = toBigDecimal(r.get("quantity"));
            BigDecimal unitPrice = toBigDecimal(r.get("unitPrice"));
            BigDecimal value = toBigDecimal(r.get("value"));
            if (value == null) {
                value = toBigDecimal(r.get("totalValue"));
            }
            if (value == null && qty != null && unitPrice != null) {
                value = qty.multiply(unitPrice);
            }
            String key = name + "|" + unit;
            ByproductLine line = acc.get(key);
            if (line == null) {
                line = ByproductLine.builder().name(name).unit(unit)
                        .quantity(BigDecimal.ZERO).build();
                acc.put(key, line);
            }
            if (qty != null) {
                line.setQuantity(nz(line.getQuantity()).add(qty));
            }
            if (value != null) {
                line.setValue(nz(line.getValue()).add(value));
            }
            // 归集后 unitPrice 由 value/quantity 反推 (混录多单价时取加权), 仅 value 有值时
            if (line.getValue() != null && line.getQuantity() != null && line.getQuantity().signum() > 0) {
                line.setUnitPrice(line.getValue().divide(line.getQuantity(), 4, RoundingMode.HALF_UP));
            }
        }
    }

    /**
     * AUDIT-002: 归集包装明细 (名称维度跨批次累加成本)。jsonb [{name, cost}]; cost 缺失则按 quantity×unitPrice。
     */
    private void accumulatePackaging(LinkedHashMap<String, BigDecimal> acc, List<Map<String, Object>> raws) {
        if (raws == null) {
            return;
        }
        for (Map<String, Object> r : raws) {
            if (r == null) {
                continue;
            }
            String name = r.get("name") == null ? "其他" : String.valueOf(r.get("name")).trim();
            BigDecimal cost = toBigDecimal(r.get("cost"));
            if (cost == null) {
                BigDecimal qty = toBigDecimal(r.get("quantity"));
                BigDecimal unitPrice = toBigDecimal(r.get("unitPrice"));
                if (qty != null && unitPrice != null) {
                    cost = qty.multiply(unitPrice);
                }
            }
            if (cost == null) {
                continue;   // 无成本信息的明细项跳过 (诚实, 不臆造)
            }
            acc.merge(name, cost, BigDecimal::add);
        }
    }

    /**
     * CALC-003: 解析本道材料成本归入哪个桶 — PACKAGING(包装) / SEASONING(调料) / SKIP(原料, 由上游 traced 承载不计)。
     * 显式 costCategory 优先 (分类不依赖工序顺序); null 或未知值 → 回退 step-index 启发式 (末道=包装, 中间道=调料, 首道=原料)。
     */
    private String resolveCostBucket(String costCategory, int idx, int stepCount, String factoryId, String orderId) {
        if (costCategory != null) {
            switch (costCategory.trim().toUpperCase()) {
                case "PACKAGING":
                    return "PACKAGING";
                case "SEASONING":
                case "AUXILIARY":
                case "OTHER":
                    return "SEASONING";
                case "RAW_MATERIAL":
                    return "SKIP";   // 原料由上游 traced consumption 承载, 不计
                default:
                    log.warn("[M67CostBreakdown] 未知 costCategory={} (factory={}, order={}) — 回退 step-index 启发式",
                            costCategory, factoryId, orderId);
                    // 落入下方启发式
            }
        }
        // 启发式 (向后兼容): 首道=原料(traced 承载, SKIP) > 末道=包装 > 中间道=调料。
        // 首道优先判断: 单工序批次(stepCount==1, 首道即末道)归 SKIP 而非 PACKAGING,
        // 否则其 materialCost 会与 traced consumption 的原料重复计 (单工序批次启发式双计 bug)。
        if (idx == 0) {
            return "SKIP";
        }
        if (idx == stepCount - 1) {
            return "PACKAGING";
        }
        return "SEASONING";
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal) {
            return (BigDecimal) o;
        }
        if (o instanceof Number) {
            return new BigDecimal(o.toString());
        }
        try {
            String s = o.toString().trim();
            return s.isEmpty() ? null : new BigDecimal(s);
        } catch (Exception e) {
            return null;
        }
    }

    private void maskCosts(OrderCostBreakdownDTO dto) {
        dto.setRawMaterialCost(null);
        dto.setLaborCost(null);
        dto.setSeasoningCost(null);
        dto.setPackagingCost(null);
        dto.setTotalCost(null);
        dto.setPerBoxCost(null);
        dto.setByproductCredit(null);
        dto.setNetTotalCost(null);
        dto.setNetPerBoxCost(null);
        dto.setSellablePerBoxCost(null);   // 成本派生; sampleRetainCount/sellableBoxCount/wasteQuantity 是物理量, 保留
        if (dto.getByproducts() != null) {
            for (ByproductLine l : dto.getByproducts()) {
                l.setUnitPrice(null);
                l.setValue(null);   // 价值/单价价格敏感; 保留 name/quantity/unit (物理量)
            }
        }
        if (dto.getSources() != null) {
            for (SourceCost s : dto.getSources()) {
                s.setUnitPrice(null);
                s.setCost(null);
                s.setCostSharePct(null);
            }
        }
        if (dto.getPackagingDetail() != null) {
            for (PackagingItem p : dto.getPackagingDetail()) {
                p.setCost(null);   // 成本敏感; 保留 name (包材项名是物理信息)
            }
        }
        if (dto.getAuxiliaryAllocations() != null) {
            for (OrderCostBreakdownDTO.AuxiliaryAllocation a : dto.getAuxiliaryAllocations()) {
                a.setPotTotalCost(null);
                a.setBatchShare(null);   // 金额敏感; 保留 potNo/method/产出量/占比 (物理量)
            }
        }
    }

    private OrderCostBreakdownDTO empty(String orderId, boolean maskPrice) {
        return OrderCostBreakdownDTO.builder()
                .orderId(orderId).boxCount(0).hasData(false).priceMasked(maskPrice)
                .sources(List.of()).byproducts(List.of()).build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
