package com.cretas.aims.service.processentry.impl;

import com.cretas.aims.dto.processentry.LaborSegment;
import com.cretas.aims.dto.processentry.MaterializeContext;
import com.cretas.aims.dto.processentry.MaterializedBatch;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.BatchEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.RawInput;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.UpstreamSource;
import com.cretas.aims.dto.processentry.ProcessChainEntryResult;
import com.cretas.aims.dto.processentry.ResolvedEdge;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProcessEntryIdempotency;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ReportMode;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.entity.recipe.ProductRecipe;
import com.cretas.aims.entity.recipe.RecipeIngredient;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.entity.config.FactoryCostSettings;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessEntryIdempotencyRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.config.FactoryCostSettingsRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.recipe.ProductRecipeRepository;
import com.cretas.aims.repository.recipe.RecipeIngredientRepository;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import com.cretas.aims.service.recipe.RecipeCostCalculator;
import com.cretas.aims.service.recipe.SeasoningCost;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 文员逐道录入物化编排 — SP-B1 Task 3.
 *
 * <h3>核心算法</h3>
 * <ol>
 *   <li>拓扑排序: finished=false 批次先于 finished=true，保证混锅来源已物化。</li>
 *   <li>逐批逐道写 MaterialConsumption (原料/调料/混锅)，成本 = qty × 上游单价。</li>
 *   <li>半成品批产出 → MaterialBatch(sourceDocType=PRODUCTION_BATCH, sourceDocId=batchId)，
 *       供 OrderCostBreakdownService.traceCost 按比例回溯。</li>
 *   <li>幂等: 同 (factoryId, planId, idempotencyKey) 命中 → 返回缓存结果，不重复写入。</li>
 * </ol>
 *
 * <h3>与 submitReport 的关系</h3>
 * submitReport 要求 workProcessTaskId (WorkProcess 基础设施) 且批次须 IN_PROGRESS —— 这是
 * 操作员手机端报工的路径，文员录入走的是快捷录入路径，直接写 MaterialConsumption + ProductionBatch，
 * 不经过 WorkProcess 路由。ProductionBatch 被创建为 IN_PROGRESS 状态（文员录入意味着工作已完成）。
 *
 * <h3>warehouseId</h3>
 * MaterialBatch.warehouseId NOT NULL —— 半成品批产出放 WH-WKS（车间仓）；
 * 若工厂无 WH-WKS，退回第一个可用仓库 id。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClerkProcessEntryServiceImpl implements ClerkProcessEntryService {

    // ¥26/工时 兜底 (当 factory_cost_settings 未配置时使用)
    private static final BigDecimal LABOR_RATE_DEFAULT = new BigDecimal("26");

    private final ProductionBatchRepository batchRepo;
    private final MaterialBatchRepository materialBatchRepo;
    private final MaterialConsumptionRepository consumptionRepo;
    private final ProcessEntryIdempotencyRepository idempotencyRepo;
    private final FactoryWarehouseRepository warehouseRepo;
    private final ProductRecipeRepository recipeRepo;
    private final RecipeIngredientRepository ingredientRepo;
    /** BOM 统管配方+锅序 (2026-06-24): 调料读路径优先 BOM. null-tolerant (@InjectMocks 未注入时回退 product_recipes). */
    private final BomRecipeRepository bomRecipeRepo;
    private final BomSeasoningItemRepository bomSeasoningItemRepo;
    private final ProductionReportRepository reportRepo;
    private final ObjectMapper objectMapper;
    /** SP-C: 工时单价配置 repo; null-tolerant (兼容测试 @InjectMocks 未注入时走 fallback). */
    private final FactoryCostSettingsRepository costSettingsRepository;
    /** SP-D Fix 2: 跨租户守卫 — 验证 planId 归属 factoryId. null-tolerant (测试 @InjectMocks 未注入时 skip check). */
    private final ProductionPlanRepository planRepository;

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ProcessChainEntryResult recordChain(String factoryId, String planId,
                                               ProcessChainEntryRequest req, Long operatorId) {
        if (planId == null || planId.isBlank()) throw new BusinessException(400, "planId 必填");
        if (operatorId == null) {
            throw new BusinessException(401, "未登录，无法录入报工 (operatorId 为 null)");
        }

        // SP-D Fix 2: 跨租户守卫 — planId 必须归属本 factoryId
        // null-tolerant: planRepository 为 null 时 (测试 @InjectMocks 未注入) 跳过, 不 NPE.
        if (planRepository != null &&
                planRepository.findByIdAndFactoryId(planId, factoryId).isEmpty()) {
            throw new BusinessException(404, "生产计划不存在: " + planId);
        }

        // 1. 幂等检查
        Optional<ProcessEntryIdempotency> cached = idempotencyRepo
                .findByFactoryIdAndPlanIdAndIdempotencyKey(factoryId, planId, req.getIdempotencyKey());
        if (cached.isPresent()) {
            ProcessChainEntryResult result = deserializeResult(cached.get().getResultJson());
            result.setIdempotentReplay(true);
            return result;
        }

        // 2. 拓扑排序: WIP 批次(finished=false)先于成品批次(finished=true)
        List<BatchEntry> ordered = new ArrayList<>(req.getBatches());
        ordered.sort(Comparator.comparing(BatchEntry::isFinished)); // false(0) < true(1)

        Map<String, Long> batchIdsByKey = new LinkedHashMap<>();
        Map<String, String> batchNumbersByKey = new LinkedHashMap<>();
        // clientBatchKey → WIP 产出 MaterialBatch.id (供下游混锅消耗)
        Map<String, String> wipMbIdByKey = new HashMap<>();
        String finishedBatchNumber = null;
        int consumptionsWritten = 0;
        int wipMaterialized = 0;
        List<String> warnings = new ArrayList<>();

        // SP-C: 工时单价 — 每次 recordChain 只查一次配置 (避免 N+1)
        BigDecimal laborRate = resolveLaborRate(factoryId, warnings);

        // WH-WKS id for this factory (WIP 产出放车间仓)
        String wksWarehouseId = resolveWarehouseId(factoryId, WarehouseCodes.WH_WKS, warnings);

        for (BatchEntry be : ordered) {
            // 3. 上游边解析 (CALLER 职责) —— recordChain 用 in-memory wipMbIdByKey map 解析混锅来源.
            //    新的逐行 caller 将改用 PERSISTED 批次号解析, 故 resolution 留在 caller, 不进 materializeBatch.
            //    保留拓扑预排序 (WIP 先于成品), 使 map 按序填充, 混锅来源已物化.
            List<ResolvedEdge> edges = new ArrayList<>();
            // Fix: WIP MaterialBatch must carry a raw_material_types FK, not a product_types id.
            // Capture the first available raw materialTypeId from this batch's lineage (step order).
            String firstRawMaterialTypeId = null;

            for (StepEntry st : be.getSteps()) {
                // 4a. 原料边 (首道领料) —— 抓取 factory-scoped raw MaterialBatch.
                if (st.getRawMaterialInputs() != null) {
                    for (RawInput ri : st.getRawMaterialInputs()) {
                        MaterialBatch rawMb = materialBatchRepo.findByIdAndFactoryId(
                                        ri.getMaterialBatchId(), factoryId)
                                .orElseThrow(() -> new BusinessException(404,
                                        "原料批次不存在: " + ri.getMaterialBatchId()));
                        if (firstRawMaterialTypeId == null && rawMb.getMaterialTypeId() != null) {
                            firstRawMaterialTypeId = rawMb.getMaterialTypeId();
                        }
                        edges.add(new ResolvedEdge(rawMb, nz(ri.getQuantity()), "RAW_MATERIAL"));
                    }
                }

                // 4b. 混锅来源边 (SEMI_FINISHED) —— 经 in-memory map 解析上游 WIP MaterialBatch.
                if (st.getUpstreamSources() != null) {
                    for (UpstreamSource us : st.getUpstreamSources()) {
                        String srcKey = us.getSourceClientBatchKey();
                        String srcMbId = wipMbIdByKey.get(srcKey);
                        if (srcMbId == null) {
                            throw new BusinessException(400,
                                    "混锅来源批次尚未录入: clientBatchKey=" + srcKey
                                            + "。请确保上游半成品批次(finished=false)排在前面");
                        }
                        MaterialBatch srcMb = materialBatchRepo.findByIdAndFactoryId(srcMbId, factoryId)
                                .orElseThrow(() -> new BusinessException(404,
                                        "WIP 批次 MaterialBatch 不存在: " + srcMbId));
                        if (firstRawMaterialTypeId == null && srcMb.getMaterialTypeId() != null) {
                            firstRawMaterialTypeId = srcMb.getMaterialTypeId();
                        }
                        edges.add(new ResolvedEdge(srcMb, nz(us.getFeedQuantityKg()), "SEMI_FINISHED"));
                    }
                }
            }

            // 4. 物化 WRITE 逻辑 (共享 seam) —— 建批 + 写消耗 + 调料/人工 + WIP 产出.
            MaterializeContext ctx = new MaterializeContext(
                    factoryId, be.isFinished() ? planId : null, be.getProductTypeId(),
                    be.getBatchNumber(), be.isFinished(), laborRate, wksWarehouseId,
                    firstRawMaterialTypeId, operatorId);

            MaterializedBatch mat = materializeBatch(ctx, be.getSteps(), edges, warnings);

            batchIdsByKey.put(be.getClientBatchKey(), mat.getProductionBatchId());
            batchNumbersByKey.put(be.getClientBatchKey(), mat.getBatchNumber());
            consumptionsWritten += mat.getConsumptionsWritten();
            if (mat.getWipMaterialBatchId() != null) {
                wipMbIdByKey.put(be.getClientBatchKey(), mat.getWipMaterialBatchId());
                wipMaterialized++;
            }
            if (be.isFinished()) {
                finishedBatchNumber = mat.getBatchNumber();
            }
        }

        // 6. 构造结果
        ProcessChainEntryResult result = new ProcessChainEntryResult();
        result.setIdempotentReplay(false);
        result.setBatchIdsByKey(batchIdsByKey);
        result.setBatchNumbersByKey(batchNumbersByKey);
        result.setFinishedBatchNumber(finishedBatchNumber);
        result.setConsumptionsWritten(consumptionsWritten);
        result.setWipBatchesMaterialized(wipMaterialized);
        result.setWarnings(warnings);

        // 7. 幂等保存
        saveIdempotency(factoryId, planId, req.getIdempotencyKey(), result);

        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // Shared materialization seam (SP-F Task 1.3 KEYSTONE)
    // ─────────────────────────────────────────────────────────────

    /**
     * 物化单批 WRITE 逻辑 —— edges/ctx 全部由 caller 预解析 (上游/仓库/工时单价 hoisted out)。
     * 见接口 Javadoc。recordChain 与未来逐行 caller 共享此方法; 后者用持久化批次号解析上游边。
     */
    @Override
    public MaterializedBatch materializeBatch(MaterializeContext ctx,
                                              List<StepEntry> steps,
                                              List<ResolvedEdge> edges,
                                              List<String> warnings) {
        // 1. 建 ProductionBatch (IN_PROGRESS — 文员录入意味着生产已完成)
        ProductionBatch batch = createProductionBatch(ctx, steps);

        BigDecimal batchTotalCost = BigDecimal.ZERO;
        int consumptionsWritten = 0;

        // 2. 写每条已解析上游消耗边 (RAW + SEMI_FINISHED); 成本 = feedKg × 上游单价.
        //    ⛔ 不访问任何 in-memory map —— edges 是唯一上游输入.
        for (ResolvedEdge e : edges) {
            MaterialBatch src = e.getSourceBatch();
            BigDecimal unitPrice = nz(src.getUnitPrice());
            BigDecimal qty = nz(e.getFeedQuantityKg());
            BigDecimal edgeCost = unitPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP);
            writeConsumption(ctx.getFactoryId(), ctx.getPlanId(), batch.getId(),
                    src.getId(), src.getMaterialTypeId(),
                    qty, unitPrice, edgeCost, e.getSourceType(), ctx.getUserId());
            batchTotalCost = batchTotalCost.add(edgeCost);
            consumptionsWritten++;
        }

        // 3. 调料 + 人工 + 产出量 —— 逐工序计算 (上游消耗已由 edges 替代).
        BigDecimal lastOutputQty = BigDecimal.ZERO;
        for (StepEntry st : steps) {
            // 3a. 调料成本 (熟制道，使用 RecipeCostCalculator)
            // 写 ProductionReport 行 (costCategory=SEASONING)，让 OrderCostBreakdownService
            // 通过 yieldReportService.getYield → steps[i].materialCost 读取。
            // 禁止写 SEASONING_VIRTUAL MaterialConsumption：traceCost() 只递归真实 MaterialBatch，
            // 虚拟占位符会误入 raw 成本桶导致分桶错乱。
            if (isSeasoningStep(st)) {
                BigDecimal seasoningCost = computeSeasoningCost(ctx.getFactoryId(), ctx.getProductTypeId(), st, warnings);
                if (seasoningCost.signum() > 0) {
                    writeSeasoningReport(ctx.getFactoryId(), batch.getId(), st, seasoningCost, ctx.getUserId());
                    batchTotalCost = batchTotalCost.add(seasoningCost);
                }
            } else if (st.getUpstreamSources() != null && !st.getUpstreamSources().isEmpty()) {
                // SP-D Fix 3: 混锅/熟制工序未被识别为调料步骤时给出警告
                // 防止 processCategory=SEASONING 未配置或 potCount 缺失导致调料成本静默丢失 (计入¥0).
                String processName = st.getProcessName() != null ? st.getProcessName() : ("工序" + st.getProcessOrder());
                warnings.add("工序「" + processName + "」有上游来源但未识别为调味步骤" +
                        "(缺 processCategory=SEASONING 或锅数)，调料成本未计入 — 请配置工序成本类别");
            }

            // 3b. 人工成本 (不写 MaterialConsumption，直接计入批次总成本)
            // SP-F: per-row caller 携带多时段 laborSegments → 求和; recordChain 永不设此字段 (null)
            // → 回退单段 (laborStartTime/laborEndTime/workerCount) 路径, recordChain labor 行为不变。
            BigDecimal laborCost = (st.getLaborSegments() != null && !st.getLaborSegments().isEmpty())
                    ? computeLaborCost(st.getLaborSegments(), ctx.getLaborRate())
                    : computeLaborCost(st, ctx.getLaborRate());
            batchTotalCost = batchTotalCost.add(laborCost);
            // SP-F ①a: 人工写一条 ProductionReport(costCategory=LABOR), 让 OrderCostBreakdownService
            // 经 getYield → totalLaborCost 读取。否则人工只折进 WIP unitPrice, 成本拆分里 laborCost=0。
            if (laborCost.signum() > 0) {
                writeLaborReport(ctx.getFactoryId(), batch.getId(), st, laborCost, ctx.getUserId());
            }

            // SP-G G3a: 副产物/留样/包装明细 写 YIELD 报工，让 YieldCalculationServiceImpl.getYield
            // 经 StepYieldDTO.byproducts/sampleRetainQuantity/packagingDetail 读取，
            // 进而 OrderCostBreakdownService.computeByBatch 消费包装明细成本分桶。
            if (hasAuxFields(st)) {
                writeYieldAuxReport(ctx.getFactoryId(), batch.getId(), st, ctx.getUserId());
            }

            // 追踪产出量 (取最后一道有产出的 step)
            if (st.getOutputQuantity() != null && st.getOutputQuantity().signum() > 0) {
                lastOutputQty = st.getOutputQuantity();
            }
        }

        // 4. 半成品批产出 → MaterialBatch(priced, PRODUCTION_BATCH 来源)
        String wipMbId = null;
        if (!ctx.isFinished() && lastOutputQty.signum() > 0) {
            BigDecimal wipUnitPrice = batchTotalCost.signum() > 0 && lastOutputQty.signum() > 0
                    ? batchTotalCost.divide(lastOutputQty, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            wipMbId = createWipMaterialBatch(
                    ctx.getFactoryId(), batch, ctx.getRawMaterialTypeId(),
                    lastOutputQty, wipUnitPrice, ctx.getWarehouseId(), ctx.getUserId());
        }

        return new MaterializedBatch(batch.getId(), batch.getBatchNumber(),
                wipMbId, batchTotalCost, consumptionsWritten);
    }

    /**
     * SP-F Task 1.6: 原地重物化 —— 见接口 Javadoc。
     *
     * <p>镜像 {@link #materializeBatch} 的成本计算 (消耗边 + 调料/人工)，但写入对象是
     * caller 传入的 {@code existingBatchId}，并<b>更新</b>现有 WIP MaterialBatch + ProductionBatch
     * (factory-scoped 加载)，而非新建。保 id 防止悬挂下游引用 (🔒 成本图完整性)。
     */
    @Override
    public MaterializedBatch rematerializeInPlace(MaterializeContext ctx, Long existingBatchId,
                                                  String existingWipMbId, List<StepEntry> steps,
                                                  List<ResolvedEdge> edges, List<String> warnings) {
        BigDecimal batchTotalCost = BigDecimal.ZERO;
        int consumptionsWritten = 0;

        // 1. 重写每条已解析上游消耗边 (RAW + SEMI_FINISHED); 成本 = feedKg × 上游单价.
        //    与 materializeBatch 同算式 (setScale(2,HALF_UP)), 写入 existingBatchId.
        for (ResolvedEdge e : edges) {
            MaterialBatch src = e.getSourceBatch();
            BigDecimal unitPrice = nz(src.getUnitPrice());
            BigDecimal qty = nz(e.getFeedQuantityKg());
            BigDecimal edgeCost = unitPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP);
            writeConsumption(ctx.getFactoryId(), ctx.getPlanId(), existingBatchId,
                    src.getId(), src.getMaterialTypeId(),
                    qty, unitPrice, edgeCost, e.getSourceType(), ctx.getUserId());
            batchTotalCost = batchTotalCost.add(edgeCost);
            consumptionsWritten++;
        }

        // 2. 调料 + 人工 + 产出量 —— 逐工序计算 (镜像 materializeBatch).
        BigDecimal lastOutputQty = BigDecimal.ZERO;
        for (StepEntry st : steps) {
            if (isSeasoningStep(st)) {
                BigDecimal seasoningCost = computeSeasoningCost(ctx.getFactoryId(), ctx.getProductTypeId(), st, warnings);
                if (seasoningCost.signum() > 0) {
                    writeSeasoningReport(ctx.getFactoryId(), existingBatchId, st, seasoningCost, ctx.getUserId());
                    batchTotalCost = batchTotalCost.add(seasoningCost);
                }
            } else if (st.getUpstreamSources() != null && !st.getUpstreamSources().isEmpty()) {
                String processName = st.getProcessName() != null ? st.getProcessName() : ("工序" + st.getProcessOrder());
                warnings.add("工序「" + processName + "」有上游来源但未识别为调味步骤" +
                        "(缺 processCategory=SEASONING 或锅数)，调料成本未计入 — 请配置工序成本类别");
            }

            BigDecimal laborCost = (st.getLaborSegments() != null && !st.getLaborSegments().isEmpty())
                    ? computeLaborCost(st.getLaborSegments(), ctx.getLaborRate())
                    : computeLaborCost(st, ctx.getLaborRate());
            batchTotalCost = batchTotalCost.add(laborCost);
            // SP-F ①a: 重物化也写人工 ProductionReport (镜像 materializeBatch)。
            // caller 已软删旧报工 (含旧人工行), 这里重新写入保持 getYield 可读。
            if (laborCost.signum() > 0) {
                writeLaborReport(ctx.getFactoryId(), existingBatchId, st, laborCost, ctx.getUserId());
            }

            // SP-G G3a: 重物化也写副产物/留样/包装明细 (镜像 materializeBatch)。
            if (hasAuxFields(st)) {
                writeYieldAuxReport(ctx.getFactoryId(), existingBatchId, st, ctx.getUserId());
            }

            if (st.getOutputQuantity() != null && st.getOutputQuantity().signum() > 0) {
                lastOutputQty = st.getOutputQuantity();
            }
        }

        // 3. 更新现有 WIP MaterialBatch (保 id) —— 重置 receiptQuantity + unitPrice.
        //    caller 保证 existingWipMbId 非空 (CASE B2: 之前 output>0 已物化 WIP)。
        //    factory-scoped 加载防跨租户 (🔒)。
        if (existingWipMbId != null) {
            MaterialBatch wip = materialBatchRepo.findByIdAndFactoryId(existingWipMbId, ctx.getFactoryId())
                    .orElseThrow(() -> new BusinessException(404,
                            "WIP 批次不存在或无权访问: " + existingWipMbId));
            wip.setReceiptQuantity(lastOutputQty);
            BigDecimal wipUnitPrice = (batchTotalCost.signum() > 0 && lastOutputQty.signum() > 0)
                    ? batchTotalCost.divide(lastOutputQty, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            wip.setUnitPrice(wipUnitPrice);
            materialBatchRepo.save(wip);
        }

        // 4. 更新 ProductionBatch.quantity (保 id) —— factory-scoped (🔒).
        ProductionBatch pb = batchRepo.findByIdAndFactoryId(existingBatchId, ctx.getFactoryId())
                .orElseThrow(() -> new BusinessException(404,
                        "生产批次不存在或无权访问: " + existingBatchId));
        if (lastOutputQty.signum() > 0) {
            pb.setQuantity(lastOutputQty);
        }
        batchRepo.save(pb);

        return new MaterializedBatch(existingBatchId, pb.getBatchNumber(),
                existingWipMbId, batchTotalCost, consumptionsWritten);
    }

    // ─────────────────────────────────────────────────────────────
    // Batch creation
    // ─────────────────────────────────────────────────────────────

    private ProductionBatch createProductionBatch(MaterializeContext ctx, List<StepEntry> steps) {
        String batchNumber = ctx.getBatchNumber() != null && !ctx.getBatchNumber().isBlank()
                ? ctx.getBatchNumber()
                : generateBatchNumber(ctx.getFactoryId(), ctx.isFinished());

        // Ensure uniqueness; DB UNIQUE(batch_number) 兜底并发
        if (batchRepo.existsByFactoryIdAndBatchNumber(ctx.getFactoryId(), batchNumber)) {
            batchNumber = batchNumber + "-" + (System.currentTimeMillis() % 10000);
        }

        // Determine planned / actual quantity from last step output
        Optional<BigDecimal> lastOutputOpt = steps == null ? Optional.empty()
                : steps.stream()
                .filter(s -> s.getOutputQuantity() != null && s.getOutputQuantity().signum() > 0)
                .reduce((a, b) -> b)  // last step
                .map(StepEntry::getOutputQuantity);
        BigDecimal qty;
        if (ctx.isFinished()) {
            // For FINISHED batches, missing output quantity is an error — not a silent fallback
            qty = lastOutputOpt.orElseThrow(() ->
                    new BusinessException(400, "成品批次无有效产出数量, 无法核算单盒成本"));
        } else {
            qty = lastOutputOpt.orElse(BigDecimal.ONE);
        }

        ProductionBatch batch = new ProductionBatch();
        batch.setFactoryId(ctx.getFactoryId());
        // Only link FINISHED batches to the plan so OrderCostBreakdownService.compute()
        // doesn't double-count WIP batch raw costs (WIP costs are already traced via
        // traceCost() when the finished batch's consumption is followed upstream).
        // NOTE: ctx.planId is already resolved by caller to (finished ? planId : null).
        batch.setProductionPlanId(ctx.getPlanId());
        batch.setProductTypeId(ctx.getProductTypeId());
        batch.setBatchNumber(batchNumber);
        batch.setQuantity(qty);
        batch.setUnit(steps == null ? "kg"
                : steps.stream().findFirst().map(StepEntry::getUnit).filter(u -> u != null).orElse("kg"));
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);  // 文员录入 = 生产进行中
        // SP-D Fix 1a: 区分 CLERK_WIP 与 REGULAR 批次
        // CLK-W- 前缀 = isFinished=false 中间批次, 不计入仪表盘; CLK-B- 前缀 = 成品批次.
        batch.setBatchType(ctx.isFinished() ? "REGULAR" : "CLERK_WIP");
        batch.setCreatedAt(LocalDateTime.now());

        return batchRepo.save(batch);
    }

    private String generateBatchNumber(String factoryId, boolean finished) {
        String prefix = finished ? "CLK-B-" : "CLK-W-";
        String date = LocalDate.now().toString().replace("-", "");
        return prefix + date + "-" + (System.currentTimeMillis() % 100000);
    }

    // ─────────────────────────────────────────────────────────────
    // WIP MaterialBatch materialization
    // ─────────────────────────────────────────────────────────────

    private String createWipMaterialBatch(String factoryId, ProductionBatch batch,
                                           String rawMaterialTypeId, BigDecimal outputQty,
                                           BigDecimal unitPrice, String warehouseId,
                                           Long operatorId) {
        String mbId = UUID.randomUUID().toString();
        String mbNumber = "WIP-" + batch.getBatchNumber();
        // Ensure uniqueness
        if (materialBatchRepo.findByBatchNumber(mbNumber).isPresent()) {
            mbNumber = mbNumber + "-" + (System.currentTimeMillis() % 10000);
        }

        MaterialBatch mb = new MaterialBatch();
        mb.setId(mbId);
        mb.setFactoryId(factoryId);
        mb.setBatchNumber(mbNumber);
        // rawMaterialTypeId is the FK to raw_material_types (nullable when no raw lineage).
        // Previously this was set to productTypeId which is a product_types FK — an invalid reference.
        mb.setMaterialTypeId(rawMaterialTypeId);
        mb.setWarehouseId(warehouseId);
        mb.setReceiptQuantity(outputQty);
        mb.setQuantityUnit("kg");
        mb.setUsedQuantity(BigDecimal.ZERO);
        mb.setReservedQuantity(BigDecimal.ZERO);
        mb.setUnitPrice(unitPrice);
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setSourceDocType("PRODUCTION_BATCH");
        mb.setSourceDocId(String.valueOf(batch.getId()));
        mb.setCreatedBy(operatorId);
        mb.setReceiptDate(LocalDate.now());

        materialBatchRepo.save(mb);
        return mbId;
    }

    // ─────────────────────────────────────────────────────────────
    // MaterialConsumption write
    // ─────────────────────────────────────────────────────────────

    private void writeConsumption(String factoryId, String planId, Long batchId,
                                   String upstreamBatchId, String materialTypeId,
                                   BigDecimal qty, BigDecimal unitPrice, BigDecimal totalCost,
                                   String sourceType, Long operatorId) {
        MaterialConsumption c = new MaterialConsumption();
        c.setFactoryId(factoryId);
        c.setProductionPlanId(planId);
        c.setProductionBatchId(batchId);
        c.setBatchId(upstreamBatchId);
        c.setMaterialTypeId(materialTypeId);
        c.setQuantity(qty);
        c.setUnitPrice(unitPrice);
        c.setTotalCost(totalCost);
        c.setSourceType(sourceType);
        c.setConsumptionTime(LocalDateTime.now());
        c.setConsumedAt(LocalDateTime.now());
        c.setRecordedBy(operatorId);
        consumptionRepo.save(c);
    }

    // ─────────────────────────────────────────────────────────────
    // Seasoning ProductionReport write
    // ─────────────────────────────────────────────────────────────

    /**
     * 将调料成本写入 ProductionReport (costCategory=SEASONING)。
     * OrderCostBreakdownService.compute() 经 yieldReportService.getYield → steps[i].materialCost
     * 路径读取，resolveCostBucket 对 costCategory=SEASONING 返回 "SEASONING" 桶。
     */
    // getYield() 按 batchId 聚合, 不过滤 workProcessTaskId, 故无 task 的调料报工可见 (T1 验证)
    private void writeSeasoningReport(String factoryId, Long productionBatchId,
                                       StepEntry st, BigDecimal seasoningCost, Long operatorId) {
        ProductionReport report = new ProductionReport();
        report.setFactoryId(factoryId);
        report.setBatchId(productionBatchId);
        report.setWorkerId(operatorId);
        report.setReportType("YIELD");
        report.setReportMode(ReportMode.MODE_1);
        report.setReportDate(resolveReportDate(st));
        report.setCostCategory("SEASONING");
        report.setMaterialCost(seasoningCost);
        report.setProcessOrder(st.getProcessOrder());
        report.setOutputQuantity(st.getOutputQuantity());
        report.setInputQuantity(st.getInputQuantity());
        reportRepo.save(report);
    }

    /**
     * SP-F ①a: 将本道人工成本写入 ProductionReport (costCategory=LABOR, laborCost 字段)。
     *
     * <p>镜像 {@link #writeSeasoningReport}, 但成本落 {@code laborCost} 而非 {@code materialCost} ——
     * OrderCostBreakdownService 经 {@code yieldReportService.getYield → BatchYieldDTO.totalLaborCost}
     * 读取 (该聚合 Σ 所有道 ProductionReport.laborCost)。materialCost 留 null, 不进 resolveCostBucket
     * 的材料分桶 (避免误计入原料/调料/包装)。
     *
     * <p>文员录入无 workProcessTaskId —— getYield 的 calculateSteps 按 taskId(null) 分组, 本批
     * 全部人工/调料报工归一组, totalLaborCost Σ 全组 laborCost, 正确。
     */
    private void writeLaborReport(String factoryId, Long productionBatchId,
                                   StepEntry st, BigDecimal laborCost, Long operatorId) {
        ProductionReport report = new ProductionReport();
        report.setFactoryId(factoryId);
        report.setBatchId(productionBatchId);
        report.setWorkerId(operatorId);
        report.setReportType("YIELD");
        report.setReportMode(ReportMode.MODE_1);
        report.setReportDate(resolveReportDate(st));
        report.setCostCategory("LABOR");
        report.setLaborCost(laborCost);
        report.setProcessOrder(st.getProcessOrder());
        report.setOutputQuantity(st.getOutputQuantity());
        report.setInputQuantity(st.getInputQuantity());
        reportRepo.save(report);
    }

    // ─────────────────────────────────────────────────────────────
    // SP-G G3a: Byproducts / SampleRetain / PackagingDetail YIELD report
    // ─────────────────────────────────────────────────────────────

    private boolean hasAuxFields(StepEntry st) {
        return (st.getByproducts() != null && !st.getByproducts().isEmpty())
                || st.getSampleRetainQuantity() != null
                || (st.getPackagingDetail() != null && !st.getPackagingDetail().isEmpty());
    }

    /**
     * SP-G G3a: 将副产物/留样/包装明细写入 ProductionReport (reportType=YIELD, costCategory=null)，
     * 让 YieldCalculationServiceImpl.getYield → StepYieldDTO 读取，
     * 进而 OrderCostBreakdownService.computeByBatch 消费。
     *
     * <p>packagingDetail 模板继承 (从 ProductWorkProcess 继承) 仅在 YieldReportServiceImpl 操作员
     * 手机端报工路径中执行 (需要 workProcessTaskId + pwpConfig 查找)。文员逐道录入无 WorkProcess
     * 基础设施，故降级：仅在 req 显式提供 packagingDetail 时写入，不尝试模板继承。
     * 若将来需要继承，应在 SP-G 后续子项中为 ClerkProcessEntryService 引入 ProductWorkProcess 查找。
     */
    private void writeYieldAuxReport(String factoryId, Long productionBatchId,
                                     StepEntry st, Long operatorId) {
        ProductionReport report = new ProductionReport();
        report.setFactoryId(factoryId);
        report.setBatchId(productionBatchId);
        report.setWorkerId(operatorId);
        report.setReportType("YIELD");
        report.setReportMode(ReportMode.MODE_1);
        report.setReportDate(resolveReportDate(st));
        report.setProcessOrder(st.getProcessOrder());
        // ⛔ 不设 output/input: YieldCalculationServiceImpl.getYield 对同 task(文员录入 task=null
        // → 全批一组) Σ 所有 report 的 output/input (L115/L123)。本辅助报工只承载副产/留样/包装明细
        // (getYield L160-179 独立读取, 不依赖 output), 设 output 会与 seasoning/labor 报工的 output
        // 重复累加 → 虚高产出/盒数。故 output/input 留 null。
        if (st.getByproducts() != null && !st.getByproducts().isEmpty()) {
            report.setByproducts(toByproductMaps(st.getByproducts()));
        }
        report.setSampleRetainQuantity(st.getSampleRetainQuantity());
        if (st.getPackagingDetail() != null && !st.getPackagingDetail().isEmpty()) {
            report.setPackagingDetail(st.getPackagingDetail());
        }
        reportRepo.save(report);
    }

    /** 将 ProcessChainEntryRequest.Byproduct 列表转换为 jsonb-ready Map 列表 (mirror YieldReportServiceImpl). */
    private List<Map<String, Object>> toByproductMaps(List<ProcessChainEntryRequest.Byproduct> bps) {
        if (bps == null || bps.isEmpty()) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProcessChainEntryRequest.Byproduct b : bps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.getName());
            m.put("quantity", b.getQuantity());
            if (b.getUnit() != null) m.put("unit", b.getUnit());
            result.add(m);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // Seasoning cost (SP-A RecipeCostCalculator)
    // ─────────────────────────────────────────────────────────────

    private boolean isSeasoningStep(StepEntry st) {
        return "SEASONING".equals(st.getProcessCategory()) ||
               (st.getProcessCategory() == null && st.getPotCount() != null);
    }

    /**
     * 跨天: 报工日期取该工序实际操作日 ({@code st.processDate})；未填回退当天。
     * 让焯水/熟制等工序跨天时, 成本报工归到各自真实日期 (成本按日归集正确)。
     */
    private LocalDate resolveReportDate(StepEntry st) {
        return st.getProcessDate() != null ? st.getProcessDate() : LocalDate.now();
    }

    private BigDecimal computeSeasoningCost(String factoryId, String productTypeId,
                                             StepEntry st, List<String> warnings) {
        BigDecimal injectionRawKg = nz(st.getInputQuantity());
        List<BigDecimal> potRawKgs = buildPotRawKgs(st);

        // BOM 统管配方+锅序 (2026-06-24): 调料折叠进 BOM → 优先读 BOM 的锅序参数 + bom_seasoning_items.
        // 算法一字不改 (RecipeCostCalculator 同源), 仅换数据源. null-tolerant: @InjectMocks 测试未注入 bom repo
        // 时跳过, 走下方 product_recipes 兼容路径 (灰度期未迁移 SKU 也走兼容路径, 保证零回归).
        //
        // 读 is_current BOM (任意 status, 含 DRAFT) — 与迁移目标 + 调料配方 tab + BomRecipe 的
        // "定义即生效无需激活仪式" 一致 (BomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrue).
        // 不按 ACTIVE 收窄: 迁移逐字拷贝 product_recipes → 同 SKU BOM 成本与旧 ACTIVE 配方逐分一致
        // (U8 cutover 逐 SKU 0-diff 验证兜底), 故选 is_current 不影响零回归; ACTIVE-gating 是可选的更
        // 保守语义 (audit Issue 4), 如需收窄改 findBy...IsCurrentTrueAndStatus(ACTIVE) — 留 Steve 决策.
        boolean bomExistedButEmptySeasoning = false;
        if (bomRecipeRepo != null && bomSeasoningItemRepo != null) {
            Optional<BomRecipe> bomOpt = bomRecipeRepo
                    .findByFactoryIdAndProductTypeIdAndIsCurrentTrue(factoryId, productTypeId);
            if (bomOpt.isPresent()) {
                List<BomSeasoningItem> bomSeasoning =
                        bomSeasoningItemRepo.findByRecipeIdOrderBySeqAsc(bomOpt.get().getId());
                if (!bomSeasoning.isEmpty()) {
                    SeasoningCost sc = RecipeCostCalculator.compute(
                            bomOpt.get().getSubsequentPotRatio(), bomSeasoning, injectionRawKg, potRawKgs);
                    return sc.getTotal();
                }
                // BOM 已建但无调料明细 — 记下, 仅当下方 legacy 也无配方 (真 0 成本) 时才区分性 warning,
                // 避免灰度期 (有原辅料 BOM, 调料尚走 legacy) 每次报工误报 (audit R4 silent-degrade).
                bomExistedButEmptySeasoning = true;
            }
        } else {
            // 仅 @InjectMocks 测试进此分支; prod 注入后恒非 null. 留 breadcrumb 防未来 wiring 回归静默退化 (audit R4).
            log.warn("[SEASONING-COST] BOM repos 不可用, 回退 product_recipes (factory={}, sku={})",
                    factoryId, productTypeId);
        }

        // 兼容/回退路径: 旧 product_recipes (灰度未迁移 SKU). cleanup 删 product_recipes 后移除本段.
        Optional<ProductRecipe> recipeOpt = recipeRepo
                .findByFactoryIdAndProductTypeIdAndStatus(factoryId, productTypeId, "ACTIVE");
        if (recipeOpt.isEmpty()) {
            // 防呆 (U7): 非静默 0 — 明确 warning + 指向设置位置 (fool-proof Rule 5). 区分两种 0 来源 (audit R4):
            if (bomExistedButEmptySeasoning) {
                warnings.add("产品 " + productTypeId + " 的 BOM 已建但无调料明细，调料成本暂记 0；"
                        + "疑似迁移遗漏或配方未填，请核对「生产 → BOM 配方 → 调料配方」。");
            } else {
                warnings.add("产品 " + productTypeId + " 未设置调料配方，调料成本暂记 0；"
                        + "请在「生产 → BOM 配方 → 调料配方」tab 为该产品设置注射/熟制配方后重新核算。");
            }
            return BigDecimal.ZERO;
        }
        ProductRecipe recipe = recipeOpt.get();
        List<RecipeIngredient> ingredients = ingredientRepo.findByRecipeIdOrderBySeqAsc(recipe.getId());
        SeasoningCost sc = RecipeCostCalculator.compute(recipe, ingredients, injectionRawKg, potRawKgs);
        return sc.getTotal();
    }

    private List<BigDecimal> buildPotRawKgs(StepEntry st) {
        int n = st.getPotCount() != null && st.getPotCount() > 0 ? st.getPotCount() : 1;
        // SP-F Fix 1: N > 1 时必须逐锅填写原料投入量，不允许静默等分。
        // 沉默等分会在各锅原料量不同时（这是 N>1 的典型场景）产生错误的调料成本。
        if (n > 1) {
            List<BigDecimal> supplied = st.getPotRawKgs();
            if (supplied == null || supplied.isEmpty() || supplied.size() != n) {
                throw new BusinessException(400,
                        "N 锅生产必须逐锅填写原料投入量 (锅数=" + n + ", 已填=" +
                                (supplied == null ? 0 : supplied.size()) + ")");
            }
            return supplied;
        }
        // N == 1 (单锅或未指定锅数): 沿用原逻辑 — 有 potRawKgs 用 potRawKgs，否则用整批投入量。
        if (st.getPotRawKgs() != null && !st.getPotRawKgs().isEmpty()) {
            return st.getPotRawKgs();
        }
        BigDecimal input = nz(st.getInputQuantity());
        return List.of(input);
    }

    // ─────────────────────────────────────────────────────────────
    // Labor cost calculation
    // ─────────────────────────────────────────────────────────────

    /**
     * SP-C: 从 factory_cost_settings 读工时单价; 未配置则回退 LABOR_RATE_DEFAULT 并记 warning.
     * null-tolerant: costSettingsRepository 为 null 时(测试 @InjectMocks 未注入)直接走 fallback.
     * public for testing — mirrors minutesBetween visibility pattern.
     * SP-F: 提升到接口 (ClerkProcessEntryService) 供 ProcessSheetService 复用。
     */
    @Override
    public BigDecimal resolveLaborRate(String factoryId, List<String> warnings) {
        if (costSettingsRepository == null) {
            return LABOR_RATE_DEFAULT;   // 测试环境 @InjectMocks 未注入 repo → 静默 fallback, 不 NPE
        }
        return costSettingsRepository.findByFactoryId(factoryId)
                .map(FactoryCostSettings::getLaborHourlyRate)
                .filter(r -> r != null && r.signum() > 0)
                .orElseGet(() -> {
                    warnings.add("工时单价未配置, 暂用默认 ¥" + LABOR_RATE_DEFAULT + "/工时, 请在工厂成本设置中配置");
                    return LABOR_RATE_DEFAULT;
                });
    }

    private BigDecimal computeLaborCost(StepEntry st, BigDecimal laborRate) {
        if (st.getLaborStartTime() == null || st.getLaborEndTime() == null
                || st.getWorkerCount() == null || st.getWorkerCount() <= 0) {
            return BigDecimal.ZERO;
        }
        int minutes = minutesBetween(st.getLaborStartTime(), st.getLaborEndTime());
        if (minutes <= 0) return BigDecimal.ZERO;
        BigDecimal workerHours = new BigDecimal(minutes)
                .divide(new BigDecimal("60"), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal(st.getWorkerCount()));
        return workerHours.multiply(laborRate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * SP-F Task 1.4: 多段工时求和版本。
     *
     * <p>每段: (end-start 分钟 / 60) × workerCount → 小时数 (scale 4, HALF_UP)。
     * 所有段求和后 × rate, 最终 setScale(2, HALF_UP)。
     * 与单段方法的舍入形状完全一致 (per-segment divide then multiply)。
     *
     * <p>public, 供测试及未来 ProcessSheetServiceImpl 调用。
     */
    public BigDecimal computeLaborCost(List<LaborSegment> segs, BigDecimal rate) {
        if (segs == null || segs.isEmpty()) return BigDecimal.ZERO;
        BigDecimal hours = segs.stream()
                .map(s -> {
                    int minutes = minutesBetween(s.getStartTime(), s.getEndTime());
                    if (minutes <= 0) return BigDecimal.ZERO;
                    int workers = s.getWorkerCount() == null ? 0 : s.getWorkerCount();
                    if (workers <= 0) return BigDecimal.ZERO;
                    return new BigDecimal(minutes)
                            .divide(new BigDecimal("60"), 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal(workers));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return hours.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /** Parse "HH:mm" → integer minutes. Handles overnight spans (end < start → +24h). */
    public int minutesBetween(String hhmmStart, String hhmmEnd) {
        try {
            int[] s = parseHHMM(hhmmStart);
            int[] e = parseHHMM(hhmmEnd);
            int startMin = s[0] * 60 + s[1];
            int endMin = e[0] * 60 + e[1];
            if (endMin < startMin) endMin += 24 * 60;
            return endMin - startMin;
        } catch (Exception ex) {
            log.warn("无法解析人工时间段: start={}, end={}", hhmmStart, hhmmEnd);
            return 0;
        }
    }

    private int[] parseHHMM(String hhmm) {
        String[] parts = hhmm.trim().split(":");
        return new int[]{ Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
    }

    // ─────────────────────────────────────────────────────────────
    // Warehouse lookup
    // ─────────────────────────────────────────────────────────────

    /**
     * SP-F: 提升到接口 (ClerkProcessEntryService) 供 ProcessSheetService 复用 (单一真相)。
     */
    @Override
    public String resolveWarehouseId(String factoryId, String code, List<String> warnings) {
        return warehouseRepo.findByFactoryIdAndCodeAndDeletedAtIsNull(factoryId, code)
                .map(w -> w.getId())
                .orElseGet(() -> {
                    // Fallback: any warehouse in this factory (factory-scoped, no cross-tenant scan)
                    return warehouseRepo.findFirstByFactoryIdAndDeletedAtIsNull(factoryId)
                            .map(w -> {
                                warnings.add("工厂 " + factoryId + " 无 " + code + " 仓库，使用第一个可用仓库");
                                return w.getId();
                            })
                            .orElseGet(() -> {
                                warnings.add("工厂 " + factoryId + " 无任何仓库，warehouseId 使用 placeholder");
                                return "WH-DEFAULT";
                            });
                });
    }

    // ─────────────────────────────────────────────────────────────
    // Idempotency store/load
    // ─────────────────────────────────────────────────────────────

    private void saveIdempotency(String factoryId, String planId,
                                  String idempotencyKey, ProcessChainEntryResult result) {
        try {
            Map<String, Object> json = objectMapper.convertValue(result, new TypeReference<>() {});
            ProcessEntryIdempotency record = new ProcessEntryIdempotency();
            record.setFactoryId(factoryId);
            record.setPlanId(planId);
            record.setIdempotencyKey(idempotencyKey);
            record.setResultJson(json);
            record.setCreatedAt(LocalDateTime.now());
            idempotencyRepo.save(record);
        } catch (Exception e) {
            log.warn("幂等结果保存失败 (非致命，下次同 key 会重新录入): {}", e.getMessage());
        }
    }

    private ProcessChainEntryResult deserializeResult(Map<String, Object> json) {
        try {
            return objectMapper.convertValue(json, ProcessChainEntryResult.class);
        } catch (Exception e) {
            log.warn("幂等结果反序列化失败，重新录入: {}", e.getMessage());
            return new ProcessChainEntryResult();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
