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
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ReportMode;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.entity.config.FactoryCostSettings;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessEntryIdempotencyRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.config.FactoryCostSettingsRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.constant.SeasoningProcessCategory;
import com.cretas.aims.entity.bom.BomProcessInjectionConfig;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.bom.BomProcessInjectionConfigRepository;
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
    /** BOM 配方是调料成本唯一真值。 */
    private final BomRecipeRepository bomRecipeRepo;
    private final BomSeasoningItemRepository bomSeasoningItemRepo;
    private final ProductionReportRepository reportRepo;
    private final ObjectMapper objectMapper;
    /** SP-C: 工时单价配置 repo; null-tolerant (兼容测试 @InjectMocks 未注入时走 fallback). */
    private final FactoryCostSettingsRepository costSettingsRepository;
    /** SP-D Fix 2: 跨租户守卫 — 验证 planId 归属 factoryId. null-tolerant (测试 @InjectMocks 未注入时 skip check). */
    private final ProductionPlanRepository planRepository;
    /** headed-audit 修复 (2026-07-03): 批次创建时解析产品名称. null-tolerant (测试 @InjectMocks 未注入时 skip). */
    private final ProductTypeRepository productTypeRepository;
    /**
     * F3: 逐工序自定义字段 schema 校验 —— chain 路径 (recordChain) 复用与 sheet 路径同一诚实-400 契约。
     * null-tolerant (测试 @InjectMocks 未注入时 skip 校验; 校验只在 step.customFields 非空时触发, 极少查库)。
     */
    private final ProductWorkProcessRepository productWorkProcessRepository;
    private final WorkProcessRepository workProcessRepository;
    /** 每道注射工序的绝对注射量配置。 */
    private final BomProcessInjectionConfigRepository bomProcessInjectionConfigRepository;

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

        // WIP identity 属于各 BatchEntry 的产出对象。先于任何写入 fail-fast，禁止从后续 RAW/SEMI
        // provenance 中猜 first；这样同一组投入无论顺序如何都不会改变 B/D 等产出身份。
        for (BatchEntry be : ordered) {
            if (!be.isFinished()) {
                requireOutputMaterialIdentity(be.getProductTypeId(), be.getClientBatchKey());
            }
        }

        // 2.5 F3: 自定义字段 schema 校验 (fail-fast, 在任何物化写入之前) —— chain 路径此前无校验,
        //   任意 key 静默落库违背诚实-400 契约。逐批逐道校验 step.customFields (仅非空时查库)。
        for (BatchEntry be : ordered) {
            if (be.getSteps() == null) continue;
            for (StepEntry st : be.getSteps()) {
                validateStepCustomFields(factoryId, be.getProductTypeId(), st);
            }
        }

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

            for (StepEntry st : be.getSteps()) {
                // 4a. 原料边 (首道领料) —— 抓取 factory-scoped raw MaterialBatch.
                if (st.getRawMaterialInputs() != null) {
                    for (RawInput ri : st.getRawMaterialInputs()) {
                        MaterialBatch rawMb = materialBatchRepo.findByIdAndFactoryId(
                                        ri.getMaterialBatchId(), factoryId)
                                .orElseThrow(() -> new BusinessException(404,
                                        "原料批次不存在: " + ri.getMaterialBatchId()));
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
                        edges.add(new ResolvedEdge(srcMb, nz(us.getFeedQuantityKg()), "SEMI_FINISHED"));
                    }
                }
            }

            // 4. 物化 WRITE 逻辑 (共享 seam) —— 建批 + 写消耗 + 调料/人工 + WIP 产出.
            MaterializeContext ctx = new MaterializeContext(
                    factoryId, be.isFinished() ? planId : null, be.getProductTypeId(),
                    be.getBatchNumber(), be.isFinished(), laborRate, wksWarehouseId,
                    be.isFinished() ? null
                            : requireOutputMaterialIdentity(be.getProductTypeId(), be.getClientBatchKey()),
                    operatorId);

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

        BigDecimal batchMaterialCost = BigDecimal.ZERO;
        BigDecimal batchLaborCost = BigDecimal.ZERO;
        BigDecimal batchTotalCost = BigDecimal.ZERO;
        int consumptionsWritten = 0;
        BigDecimal firstInputQty = firstPositiveInput(steps);
        // 🔒 honest-null: 任一消耗源 unitPrice==null (成本未知, 非 0) → 批次 ROLL-UP 成本不可知。
        //   区分 null(未知) vs 0(真免费): 仅 null 触发诚实置 null; genuinely-free 源 (unitPrice=0) 不触发。
        boolean anyUncosted = false;
        boolean actualSeasoningCosted = edges.stream()
                .anyMatch(edge -> "SEASONING".equals(edge.getSourceType()));

        // 2. 写每条已解析上游消耗边 (RAW + SEMI_FINISHED); 成本 = feedKg × 上游单价.
        //    ⛔ 不访问任何 in-memory map —— edges 是唯一上游输入.
        for (ResolvedEdge e : edges) {
            MaterialBatch src = e.getSourceBatch();
            BigDecimal resolvedUnitPrice = edgeUnitPrice(e);
            if (resolvedUnitPrice == null) {
                anyUncosted = true;  // 未计价源: edgeCost 仍写 0 (consumption 行), 但 ROLL-UP 诚实 null
            }
            BigDecimal unitPrice = nz(resolvedUnitPrice);
            BigDecimal qty = nz(e.getFeedQuantityKg());
            BigDecimal edgeCost = unitPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP);
            writeConsumption(ctx.getFactoryId(), ctx.getPlanId(), batch.getId(),
                    src.getId(), inventoryIdentity(src),
                    qty, unitPrice, edgeCost, e.getSourceType(), ctx.getUserId());
            batchMaterialCost = batchMaterialCost.add(edgeCost);
            batchTotalCost = batchTotalCost.add(edgeCost);
            consumptionsWritten++;
        }

        // 3. 调料 + 人工 + 产出量 —— 逐工序计算 (上游消耗已由 edges 替代).
        BigDecimal lastOutputQty = BigDecimal.ZERO;
        BigDecimal lastYieldOutputQty = BigDecimal.ZERO;
        String lastOutputUnit = "kg";
        for (StepEntry st : steps) {
            // 3a. 调料成本 (熟制道，使用 RecipeCostCalculator)
            // 写 ProductionReport 行 (costCategory=SEASONING)，让 OrderCostBreakdownService
            // 通过 yieldReportService.getYield → steps[i].materialCost 读取。
            // 禁止写 SEASONING_VIRTUAL MaterialConsumption：traceCost() 只递归真实 MaterialBatch，
            // 虚拟占位符会误入 raw 成本桶导致分桶错乱。
            // 同道产出/投入只能由一条报工承载 (见 writeLaborReport): 调料报工已写 output 时, 人工报工不再写。
            boolean stepOutputWrittenBySeasoning = false;
            if (!actualSeasoningCosted
                    && isSeasoningStep(ctx.getFactoryId(), ctx.getProductTypeId(), st)) {
                BigDecimal seasoningCost = computeSeasoningCost(ctx.getFactoryId(), ctx.getProductTypeId(), st, warnings);
                if (seasoningCost.signum() > 0) {
                    writeSeasoningReport(ctx.getFactoryId(), batch.getId(), st, seasoningCost, ctx.getUserId());
                    stepOutputWrittenBySeasoning = true;
                    batchMaterialCost = batchMaterialCost.add(seasoningCost);
                    batchTotalCost = batchTotalCost.add(seasoningCost);
                }
            } else if (!actualSeasoningCosted
                    && st.getUpstreamSources() != null && !st.getUpstreamSources().isEmpty()) {
                // SP-D Fix 3: 混锅/熟制工序未被识别为调料步骤时给出警告
                // 防止 processCategory=SEASONING 未配置或 potCount 缺失导致调料成本静默丢失 (计入¥0).
                // 2026-06-29 修: 只对"看起来像熟制/调味"的工序(名含 熟/卤/煮/腌/注射/入味/调味)警告,
                //   否则每个有上游的中间道(修油/滚揉/焯水)都误报"调料成本未计入", 满屏噪音且误导。
                String processName = st.getProcessName() != null ? st.getProcessName() : ("工序" + st.getProcessOrder());
                if (processName != null && processName.matches(".*(熟|卤|煮|腌|注射|入味|调味).*")) {
                    warnings.add("工序「" + processName + "」有上游来源但未识别为调味步骤" +
                            "(缺 processCategory=SEASONING 或锅数)，调料成本未计入 — 请配置工序成本类别");
                }
            }

            // 3b. 人工成本 (不写 MaterialConsumption，直接计入批次总成本)
            // SP-F: per-row caller 携带多时段 laborSegments → 求和; recordChain 永不设此字段 (null)
            // → 回退单段 (laborStartTime/laborEndTime/workerCount) 路径, recordChain labor 行为不变。
            BigDecimal laborCost = (st.getLaborSegments() != null && !st.getLaborSegments().isEmpty())
                    ? computeLaborCost(st.getLaborSegments(), ctx.getLaborRate())
                    : computeLaborCost(st, ctx.getLaborRate());
            batchLaborCost = batchLaborCost.add(laborCost);
            batchTotalCost = batchTotalCost.add(laborCost);
            // SP-F ①a: 人工写一条 ProductionReport(costCategory=LABOR), 让 OrderCostBreakdownService
            // 经 getYield → totalLaborCost 读取。否则人工只折进 WIP unitPrice, 成本拆分里 laborCost=0。
            // carryQuantities: 调料报工没写 output 时由人工报工承载 (纯人工道); 已写则 false (防 2× 双计)。
            if (laborCost.signum() > 0) {
                writeLaborReport(ctx.getFactoryId(), batch.getId(), st, laborCost, ctx.getUserId(),
                        !stepOutputWrittenBySeasoning);
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
                lastYieldOutputQty = yieldOutputQuantity(st);
                lastOutputUnit = outputUnitOf(st);
            }
        }

        // 4. 半成品批产出 → MaterialBatch(priced, PRODUCTION_BATCH 来源)
        String wipMbId = null;
        if (!ctx.isFinished() && lastOutputQty.signum() > 0) {
            // 🔒 honest-null: 未计价源存在 → WIP 单价不可知 (null), 供下游复用时其 getUnitPrice()==null
            //   再次触发 anyUncosted, 诚实链完整传播 (不把未知成本以 0 假造进复用批单价)。
            BigDecimal wipUnitPrice = anyUncosted ? null
                    : (batchTotalCost.signum() > 0 && lastOutputQty.signum() > 0
                    ? batchTotalCost.divide(lastOutputQty, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            wipMbId = createWipMaterialBatch(
                    ctx.getFactoryId(), batch,
                    requireOutputMaterialIdentity(ctx.getRawMaterialTypeId(), batch.getBatchNumber()),
                    lastOutputQty, lastOutputUnit, wipUnitPrice, ctx.getWarehouseId(), ctx.getUserId());
        }
        applyBatchCostSummary(batch, batchMaterialCost, batchLaborCost, batchTotalCost,
                firstInputQty, lastOutputQty, lastYieldOutputQty, anyUncosted);

        return new MaterializedBatch(batch.getId(), batch.getBatchNumber(),
                wipMbId, anyUncosted ? null : batchTotalCost, consumptionsWritten);
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
        BigDecimal batchMaterialCost = BigDecimal.ZERO;
        BigDecimal batchLaborCost = BigDecimal.ZERO;
        BigDecimal batchTotalCost = BigDecimal.ZERO;
        int consumptionsWritten = 0;
        BigDecimal firstInputQty = firstPositiveInput(steps);
        BigDecimal lastYieldOutputQty = BigDecimal.ZERO;
        // 🔒 honest-null: 镜像 materializeBatch — 任一消耗源 unitPrice==null → ROLL-UP 成本诚实 null。
        boolean anyUncosted = false;
        boolean actualSeasoningCosted = edges.stream()
                .anyMatch(edge -> "SEASONING".equals(edge.getSourceType()));

        // 1. 重写每条已解析上游消耗边 (RAW + SEMI_FINISHED); 成本 = feedKg × 上游单价.
        //    与 materializeBatch 同算式 (setScale(2,HALF_UP)), 写入 existingBatchId.
        for (ResolvedEdge e : edges) {
            MaterialBatch src = e.getSourceBatch();
            BigDecimal resolvedUnitPrice = edgeUnitPrice(e);
            if (resolvedUnitPrice == null) {
                anyUncosted = true;  // 未计价源: edgeCost 仍写 0, 但 ROLL-UP 诚实 null
            }
            BigDecimal unitPrice = nz(resolvedUnitPrice);
            BigDecimal qty = nz(e.getFeedQuantityKg());
            BigDecimal edgeCost = unitPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP);
            writeConsumption(ctx.getFactoryId(), ctx.getPlanId(), existingBatchId,
                    src.getId(), inventoryIdentity(src),
                    qty, unitPrice, edgeCost, e.getSourceType(), ctx.getUserId());
            batchMaterialCost = batchMaterialCost.add(edgeCost);
            batchTotalCost = batchTotalCost.add(edgeCost);
            consumptionsWritten++;
        }

        // 2. 调料 + 人工 + 产出量 —— 逐工序计算 (镜像 materializeBatch).
        BigDecimal lastOutputQty = BigDecimal.ZERO;
        for (StepEntry st : steps) {
            // 同道产出/投入只能由一条报工承载 (镜像 materializeBatch): 调料报工已写 output 时人工报工不再写。
            boolean stepOutputWrittenBySeasoning = false;
            if (!actualSeasoningCosted
                    && isSeasoningStep(ctx.getFactoryId(), ctx.getProductTypeId(), st)) {
                BigDecimal seasoningCost = computeSeasoningCost(ctx.getFactoryId(), ctx.getProductTypeId(), st, warnings);
                if (seasoningCost.signum() > 0) {
                    writeSeasoningReport(ctx.getFactoryId(), existingBatchId, st, seasoningCost, ctx.getUserId());
                    stepOutputWrittenBySeasoning = true;
                    batchMaterialCost = batchMaterialCost.add(seasoningCost);
                    batchTotalCost = batchTotalCost.add(seasoningCost);
                }
            } else if (!actualSeasoningCosted
                    && st.getUpstreamSources() != null && !st.getUpstreamSources().isEmpty()) {
                // 2026-06-29 修(审计补): rematerializeInPlace 路径 — 首版 fix 漏改的孪生 (审计抓到)。
                //   同 materializeBatch: 只对熟制/调味道警告, 否则 re-save 时每个有上游的中间道误报。
                String processName = st.getProcessName() != null ? st.getProcessName() : ("工序" + st.getProcessOrder());
                if (processName != null && processName.matches(".*(熟|卤|煮|腌|注射|入味|调味).*")) {
                    warnings.add("工序「" + processName + "」有上游来源但未识别为调味步骤" +
                            "(缺 processCategory=SEASONING 或锅数)，调料成本未计入 — 请配置工序成本类别");
                }
            }

            BigDecimal laborCost = (st.getLaborSegments() != null && !st.getLaborSegments().isEmpty())
                    ? computeLaborCost(st.getLaborSegments(), ctx.getLaborRate())
                    : computeLaborCost(st, ctx.getLaborRate());
            batchLaborCost = batchLaborCost.add(laborCost);
            batchTotalCost = batchTotalCost.add(laborCost);
            // SP-F ①a: 重物化也写人工 ProductionReport (镜像 materializeBatch)。
            // caller 已软删旧报工 (含旧人工行), 这里重新写入保持 getYield 可读。
            // carryQuantities: 同 materializeBatch — 调料报工已写 output 则 false, 防同道 2× 双计。
            if (laborCost.signum() > 0) {
                writeLaborReport(ctx.getFactoryId(), existingBatchId, st, laborCost, ctx.getUserId(),
                        !stepOutputWrittenBySeasoning);
            }

            // SP-G G3a: 重物化也写副产物/留样/包装明细 (镜像 materializeBatch)。
            if (hasAuxFields(st)) {
                writeYieldAuxReport(ctx.getFactoryId(), existingBatchId, st, ctx.getUserId());
            }

            if (st.getOutputQuantity() != null && st.getOutputQuantity().signum() > 0) {
                lastOutputQty = st.getOutputQuantity();
                lastYieldOutputQty = yieldOutputQuantity(st);
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
            wip.setMaterialTypeId(null);
            wip.setProductTypeId(requireOwnedOutputProduct(
                    ctx.getFactoryId(), ctx.getRawMaterialTypeId(), wip.getBatchNumber()));
            // 🔒 honest-null: 镜像 materializeBatch — 未计价源存在 → WIP 单价诚实 null。
            BigDecimal wipUnitPrice = anyUncosted ? null
                    : ((batchTotalCost.signum() > 0 && lastOutputQty.signum() > 0)
                    ? batchTotalCost.divide(lastOutputQty, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            wip.setUnitPrice(wipUnitPrice);
            // 立即 flush，确保身份/FK 错误在写审计日志前暴露，避免污染 Hibernate Session。
            materialBatchRepo.saveAndFlush(wip);
        }

        // 4. 更新 ProductionBatch.quantity (保 id) —— factory-scoped (🔒).
        ProductionBatch pb = batchRepo.findByIdAndFactoryId(existingBatchId, ctx.getFactoryId())
                .orElseThrow(() -> new BusinessException(404,
                        "生产批次不存在或无权访问: " + existingBatchId));
        if (lastOutputQty.signum() > 0) {
            pb.setQuantity(lastOutputQty);
        }
        applyBatchCostSummary(pb, batchMaterialCost, batchLaborCost, batchTotalCost,
                firstInputQty, lastOutputQty, lastYieldOutputQty, anyUncosted);
        batchRepo.save(pb);

        return new MaterializedBatch(existingBatchId, pb.getBatchNumber(),
                existingWipMbId, anyUncosted ? null : batchTotalCost, consumptionsWritten);
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
        // headed-audit 修复 (2026-07-03): 文员逐道录入建的批次此前没写 productName/plannedQuantity,
        // 导致 生产批次 列表显示 GUID + 空数量列 (ProcessingServiceImpl.enrichBatchDisplayFields 是
        // 读时兜底, 这里把写时的根因也修掉, 保持与 ProcessingServiceImpl.createBatch /
        // ProductionPlanServiceImpl.createBatchFromPlan 同样"创建时就写对"的一致性)。
        // null-tolerant: productTypeRepository 为 null 时 (测试 @InjectMocks 未注入) 跳过, 不 NPE
        // (同文件既有 planRepository/costSettingsRepository null-tolerant 惯例)。
        if (productTypeRepository != null && ctx.getProductTypeId() != null) {
            productTypeRepository.findById(ctx.getProductTypeId())
                    .ifPresent(pt -> batch.setProductName(pt.getName()));
        }
        // plannedQuantity: FINISHED 批次已挂 planId → 取计划的真实计划数量;
        // WIP 批次 (无 planId 语义) 或计划已不存在 → 退回本批次的产出数量 qty。
        BigDecimal plannedQty = null;
        if (planRepository != null && ctx.getPlanId() != null) {
            plannedQty = planRepository.findById(ctx.getPlanId())
                    .map(com.cretas.aims.entity.ProductionPlan::getPlannedQuantity)
                    .orElse(null);
        }
        batch.setPlannedQuantity(plannedQty != null ? plannedQty : qty);
        // 母计划状态同步 (2026-07 修复): 逐工序录入建 FINISHED 批次时 (ctx.planId 非空) 只建了
        // ProductionBatch(IN_PROGRESS), 从未回写母 ProductionPlan.status → 计划永远卡在 PENDING,
        // 即便生产已实际进行/完成 (看板/统计按 production_plans.status 过滤 IN_PROGRESS/PENDING 因此口径错)。
        // 只做状态 + startTime 的最小 sync, 不调用 ProductionPlanServiceImpl.startProduction():
        // 后者除了状态翻转还有 (a) runConfiguredValidation("START") 前置审批门 (b) SP2 二次加工
        // WIP 半成品扣减副作用 —— 这两者在逐工序场景都不该重放: (a) 文员录入代表生产已完成的
        // 事后记录 (见本方法起始注释), 不该在事后补录时重新触发前置审批门; (b) 本方法已经通过
        // materializeBatch 的 edges (含 SEMI_FINISHED 上游) 按批次精确扣减了实际消耗的半成品库存,
        // 若再调用 startProduction 的 deductForSecondaryPlan 会对同一 SECONDARY 计划的 WIP 源
        // 二次扣减 (幽灵超扣)。因此只 sync 状态字段, 不搬前置 gate / 副作用。
        // factory-scoped 查找 (🔒 防跨租户写): 与 plannedQty 读取分开一次查询, 不改动上面既有读取行为。
        if (planRepository != null && ctx.getPlanId() != null) {
            planRepository.findByIdAndFactoryId(ctx.getPlanId(), ctx.getFactoryId())
                    .filter(p -> p.getStatus() == com.cretas.aims.entity.enums.ProductionPlanStatus.PENDING)
                    .ifPresent(p -> {
                        p.setStatus(com.cretas.aims.entity.enums.ProductionPlanStatus.IN_PROGRESS);
                        if (p.getStartTime() == null) {
                            p.setStartTime(LocalDateTime.now());
                        }
                        planRepository.save(p);
                    });
        }
        batch.setUnit(resolveBatchOutputUnit(steps));
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);  // 文员录入 = 生产进行中
        // SP-D Fix 1a: 区分 CLERK_WIP 与 REGULAR 批次
        // CLK-W- 前缀 = isFinished=false 中间批次, 不计入仪表盘; CLK-B- 前缀 = 成品批次.
        batch.setBatchType(ctx.isFinished() ? "REGULAR" : "CLERK_WIP");
        applyProductionWindow(batch, steps);
        batch.setCreatedAt(LocalDateTime.now());

        return batchRepo.save(batch);
    }

    /**
     * Transitional note: {@link MaterializeContext#getRawMaterialTypeId()} keeps its legacy field name,
     * but WIP materialization now carries the stable output product identity in that slot. Input batches
     * remain independent provenance edges and are never used as an identity fallback.
     */
    private static String requireOutputMaterialIdentity(String outputIdentity, String outputRef) {
        if (outputIdentity == null || outputIdentity.isBlank()) {
            throw new BusinessException(400, "半成品产出缺少稳定物料身份，无法物化 WIP")
                    .withCode("WIP_OUTPUT_MATERIAL_IDENTITY_REQUIRED")
                    .withHint("请为产出 Cell 绑定明确的半成品产品后重试")
                    .withHintTarget(outputRef != null ? outputRef : "WIP")
                    .withSeverity("BLOCKING");
        }
        return outputIdentity.trim();
    }

    /** 把逐产出工时时段投影到批次起止时间；跨午夜的结束时间自动顺延一天。 */
    private void applyProductionWindow(ProductionBatch batch, List<StepEntry> steps) {
        if (steps == null || steps.isEmpty()) return;
        StepEntry step = steps.stream()
                .filter(candidate -> candidate.getLaborSegments() != null
                        && !candidate.getLaborSegments().isEmpty())
                .reduce((left, right) -> right)
                .orElse(null);
        if (step == null) return;
        LocalDate date = step.getProcessDate() != null ? step.getProcessDate() : LocalDate.now();
        LocalDateTime earliest = null;
        LocalDateTime latest = null;
        for (LaborSegment segment : step.getLaborSegments()) {
            try {
                java.time.LocalTime startTime = java.time.LocalTime.parse(segment.getStartTime().trim());
                java.time.LocalTime endTime = java.time.LocalTime.parse(segment.getEndTime().trim());
                LocalDateTime start = date.atTime(startTime);
                LocalDateTime end = date.atTime(endTime);
                if (end.isBefore(start)) end = end.plusDays(1);
                if (earliest == null || start.isBefore(earliest)) earliest = start;
                if (latest == null || end.isAfter(latest)) latest = end;
            } catch (Exception e) {
                throw new BusinessException(400, "工时时间格式无效，无法保存批次起止时间")
                        .withCode("PROCESS_SHEET_OUTPUT_LABOR_TIME_INVALID")
                        .withHint("请使用 HH:mm 格式")
                        .withSeverity("BLOCKING");
            }
        }
        batch.setStartTime(earliest);
        batch.setEndTime(latest);
    }

    private BigDecimal firstPositiveInput(List<StepEntry> steps) {
        if (steps == null) {
            return BigDecimal.ZERO;
        }
        return steps.stream()
                .map(StepEntry::getInputQuantity)
                .filter(q -> q != null && q.signum() > 0)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal yieldOutputQuantity(StepEntry step) {
        if (step == null) {
            return BigDecimal.ZERO;
        }
        if (step.getProductWeight() != null && step.getProductWeight().signum() > 0) {
            return step.getProductWeight();
        }
        return nz(step.getOutputQuantity());
    }

    private void applyBatchCostSummary(ProductionBatch batch,
                                       BigDecimal materialCost,
                                       BigDecimal laborCost,
                                       BigDecimal totalCost,
                                       BigDecimal inputQty,
                                       BigDecimal outputQty,
                                       BigDecimal yieldOutputQty,
                                       boolean anyUncosted) {
        BigDecimal material = nz(materialCost);
        BigDecimal labor = nz(laborCost);
        BigDecimal total = nz(totalCost);
        batch.setMaterialCost(material.setScale(2, RoundingMode.HALF_UP));
        batch.setLaborCost(labor.signum() > 0 ? labor.setScale(2, RoundingMode.HALF_UP) : null);
        // 🔒 honest-null: 任一消耗源未计价 (unitPrice==null, 成本未知而非 0) → 批次总成本/单价不可知,
        // 写 null 而非 nz-求和后的低估值 (与纯 SFI 投料路径 ProcessSheetServiceImpl:151 「成本诚实 null」一致)。
        // materialCost/laborCost 仍写已知分量, 仅 ROLL-UP totalCost/unitCost 诚实置 null。
        if (anyUncosted) {
            batch.setTotalCost(null);
            batch.setUnitCost(null);
        } else {
            batch.setTotalCost(total.setScale(2, RoundingMode.HALF_UP));
            if (outputQty != null && outputQty.signum() > 0) {
                batch.setUnitCost(total.divide(outputQty, 4, RoundingMode.HALF_UP));
            } else {
                batch.setUnitCost(null);
            }
        }
        if (inputQty != null && inputQty.signum() > 0 && yieldOutputQty != null) {
            batch.setYieldRate(yieldOutputQty.multiply(new BigDecimal("100"))
                    .divide(inputQty, 2, RoundingMode.HALF_UP));
        } else {
            batch.setYieldRate(null);
        }
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
                                           String outputMaterialIdentity, BigDecimal outputQty,
                                           String outputUnit, BigDecimal unitPrice, String warehouseId,
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
        // WIP identity belongs to ProductType. Raw inputs remain in MaterialConsumption
        // provenance and must never be written into the raw_material_types FK column.
        mb.setMaterialTypeId(null);
        mb.setProductTypeId(requireOwnedOutputProduct(factoryId, outputMaterialIdentity, mbNumber));
        mb.setWarehouseId(warehouseId);
        mb.setReceiptQuantity(outputQty);
        mb.setQuantityUnit(outputUnit);
        mb.setUsedQuantity(BigDecimal.ZERO);
        mb.setReservedQuantity(BigDecimal.ZERO);
        mb.setUnitPrice(unitPrice);
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setSourceDocType("PRODUCTION_BATCH");
        mb.setSourceDocId(String.valueOf(batch.getId()));
        mb.setCreatedBy(operatorId);
        mb.setReceiptDate(LocalDate.now());

        // Flush before the caller writes the audit log. A persistence failure must surface as
        // the primary exception instead of poisoning the Session and being masked by logChange.
        materialBatchRepo.saveAndFlush(mb);
        return mbId;
    }

    private String inventoryIdentity(MaterialBatch batch) {
        if (batch.getMaterialTypeId() != null && !batch.getMaterialTypeId().isBlank()) {
            return batch.getMaterialTypeId();
        }
        if (batch.getProductTypeId() != null && !batch.getProductTypeId().isBlank()) {
            return batch.getProductTypeId();
        }
        throw new BusinessException(422,
                "库存批次缺少物料或产品身份，无法记录消耗: " + batch.getBatchNumber());
    }

    private String requireOwnedOutputProduct(String factoryId, String outputProductId, String batchNumber) {
        String productId = requireOutputMaterialIdentity(outputProductId, batchNumber);
        productTypeRepository.findByIdAndFactoryId(productId, factoryId)
                .orElseThrow(() -> new BusinessException(422,
                        "半成品产出不属于当前工厂或产品不存在: " + productId));
        return productId;
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
        report.setInputUnit(inputUnitOf(st));
        report.setOutputUnit(outputUnitOf(st));
        report.setCustomFields(processEntryCustomFields(st));
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
                                   StepEntry st, BigDecimal laborCost, Long operatorId,
                                   boolean carryQuantities) {
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
        // 2026-06-30: 产出/投入只能由本道**一条**报工承载。同道既有调料(SEASONING)又有人工时,
        // 产出/投入已由 writeSeasoningReport 写入 → 这里必须留 null, 否则
        // YieldCalculationServiceImpl.calculateSteps 同组 Σ 两条 output/input (L144/L152) →
        // 该道 getYield totalOutput/totalInput 虚高 2× (与 writeYieldAuxReport L682-685 同一防呆)。
        // carryQuantities=false 仅当本道已写调料报工; 纯人工道 (无调料报工) 仍 true, 产出由人工报工承载。
        if (carryQuantities) {
            report.setOutputQuantity(st.getOutputQuantity());
            report.setInputQuantity(st.getInputQuantity());
            report.setInputUnit(inputUnitOf(st));
            report.setOutputUnit(outputUnitOf(st));
        }
        report.setCustomFields(processEntryCustomFields(st));
        reportRepo.save(report);
    }

    // ─────────────────────────────────────────────────────────────
    // SP-G G3a: Byproducts / SampleRetain / PackagingDetail YIELD report
    // ─────────────────────────────────────────────────────────────

    private boolean hasAuxFields(StepEntry st) {
        return (st.getByproducts() != null && !st.getByproducts().isEmpty())
                || st.getSampleRetainQuantity() != null
                || (st.getPackagingDetail() != null && !st.getPackagingDetail().isEmpty())
                // G2: 自定义字段值非空也需写 YIELD 辅助报工 (否则 hasAuxFields=false → writeYieldAuxReport
                // 不被调用 → customFields 静默丢失, 即使该行没有副产/留样/包装明细)。
                || (st.getCustomFields() != null && !st.getCustomFields().isEmpty());
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
        report.setCustomFields(processEntryCustomFields(st));
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

    /**
     * F3: 校验 chain 路径单个 step 的自定义字段 key 白名单 (与 sheet 路径同一诚实-400 契约)。
     *
     * <p>只在 {@code st.getCustomFields()} 非空时查库解析 (factory, productTypeId, processOrder) →
     * WorkProcess.customFieldSchema, 再调共享 {@link ProcessCustomFieldValidation#checkKeys}。
     * null-tolerant: repos 未注入 (测试 @InjectMocks) / 缺定位信息 / 找不到工序配置 → skip (放行),
     * 与 sheet 路径 {@code ProcessSheetServiceImpl.validateCustomFields} 的兜底语义一致。
     */
    private void validateStepCustomFields(String factoryId, String productTypeId, StepEntry st) {
        Map<String, Object> cf = st.getCustomFields();
        if (cf == null || cf.isEmpty()) {
            return; // 无自定义字段 —— 最常见路径, 提前返回避免查库
        }
        if (productWorkProcessRepository == null || workProcessRepository == null) {
            return; // 测试 @InjectMocks 未注入这两个 repo —— 放行 (与其它 null-tolerant 依赖一致)
        }
        if (productTypeId == null || st.getProcessOrder() == null) {
            return; // 无法定位该道 WorkProcess —— 防御性放行
        }
        List<ProductWorkProcess> pwps = productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, productTypeId);
        ProductWorkProcess pwp = pwps.stream()
                .filter(p -> st.getProcessOrder().equals(p.getProcessOrder()))
                .findFirst()
                .orElse(null);
        if (pwp == null || pwp.getWorkProcessId() == null) {
            return; // 找不到对应工序配置 —— 无 schema 可校验, 放行
        }
        WorkProcess wp = workProcessRepository.findById(pwp.getWorkProcessId()).orElse(null);
        if (wp == null) {
            return;
        }
        ProcessCustomFieldValidation.checkKeys(wp.getCustomFieldSchema(), cf.keySet(), wp.getProcessName());
    }

    /** 将 ProcessChainEntryRequest.Byproduct 列表转换为 jsonb-ready Map 列表 (mirror YieldReportServiceImpl). */
    private Map<String, Object> processEntryCustomFields(StepEntry st) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("processEntryStepKey", processEntryStepKey(st));
        if (st.getProcessName() != null && !st.getProcessName().isBlank()) {
            fields.put("processEntryProcessName", st.getProcessName());
        }
        if (st.getProcessCategory() != null && !st.getProcessCategory().isBlank()) {
            fields.put("processEntryProcessCategory", st.getProcessCategory());
        }
        // G2 KEYSTONE (materialize-land): 用户自定义字段值 (波美度/添加剂量/备注等, WorkProcess
        // .customFieldSchema 配置驱动) 落 ProductionReport.customFields。命名空间隔离
        // ("clerkCustomFields" 子 key) —— 不能与上面的内部记账 key 拍平合并: processEntryStepKey
        // 被 YieldCalculationServiceImpl 读取用于按 (order|name|category) 去重分组, 直接
        // fields.putAll(st.getCustomFields()) 会有把用户字段名撞上未来新增内部 key 的风险,
        // 也让"哪些是内部记账 / 哪些是用户填的"混在一起不可辨识。
        if (st.getCustomFields() != null && !st.getCustomFields().isEmpty()) {
            fields.put("clerkCustomFields", st.getCustomFields());
        }
        return fields;
    }

    private String processEntryStepKey(StepEntry st) {
        return (st.getProcessOrder() == null ? 0 : st.getProcessOrder())
                + "|" + safeKeyPart(st.getProcessName())
                + "|" + safeKeyPart(st.getProcessCategory());
    }

    private String safeKeyPart(String value) {
        return value == null ? "" : value.trim();
    }

    private List<Map<String, Object>> toByproductMaps(List<ProcessChainEntryRequest.Byproduct> bps) {
        if (bps == null || bps.isEmpty()) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProcessChainEntryRequest.Byproduct b : bps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.getName());
            m.put("quantity", b.getQuantity());
            if (b.getUnit() != null) m.put("unit", b.getUnit());
            // 副产回收价 (¥/单位): 必须写进报工, 否则 computeByBatch 的 accumulateByproducts
            // 读不到 unitPrice → value=0 → 副产回收=0 (严格测试 2026-06-24 抓到)。
            if (b.getUnitPrice() != null) m.put("unitPrice", b.getUnitPrice());
            result.add(m);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // Seasoning cost (SP-A RecipeCostCalculator)
    // ─────────────────────────────────────────────────────────────

    /**
     * 调料配方按工序 (2026-07-13): 优先按工序的 WorkProcess.processCategory (熟制/注射) 识别调味步,
     * 跨 legacy/workflow 两模式都 work; 解析不到时回退原名字正则/SEASONING/potCount 兜底 (零回归)。
     */
    private boolean isSeasoningStep(String factoryId, String productTypeId, StepEntry st) {
        // audit Finding 1 修复: 仅当该步"真配了 per-工序 调料"才按新路径认调味步。
        // ⛔ 不能单凭工序类别=熟制/注射 就认 —— 那会让一个"被重新归类为熟制"的共享工序(未配调料)
        //    误触发下方旧整-SKU 回退, 把整个 SKU 调料成本再算一遍 → double-count(无配置即触发)。
        //    有配置的步 → computePerProcessSeasoningCost 返非 null 独占核算, 不碰旧回退。
        if (hasPerProcessSeasoningConfig(factoryId, productTypeId, st)) {
            return true;
        }
        return isSeasoningStep(st);
    }

    /**
     * 该报工步是否已配 per-工序 调料或注射绝对量。
     * 决定是否走工序绑定路径；未配置时只允许读取同一 BOM 下的整 SKU 调料绑定。
     */
    private boolean hasPerProcessSeasoningConfig(String factoryId, String productTypeId, StepEntry st) {
        if (bomRecipeRepo == null || bomSeasoningItemRepo == null || bomProcessInjectionConfigRepository == null) {
            return false;
        }
        WorkProcess wp = resolveStepWorkProcess(factoryId, productTypeId, st);
        if (wp == null || wp.getId() == null) return false;
        Optional<BomRecipe> bomOpt = bomRecipeRepo
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrue(factoryId, productTypeId);
        if (bomOpt.isEmpty()) return false;
        String recipeId = bomOpt.get().getId();
        String wpId = wp.getId();
        if (!bomSeasoningItemRepo.findByRecipeIdAndWorkProcessIdOrderBySeqAsc(recipeId, wpId).isEmpty()) {
            return true;
        }
        return bomProcessInjectionConfigRepository
                .findByRecipeIdAndWorkProcessIdAndDeletedAtIsNull(recipeId, wpId).isPresent();
    }

    /**
     * 跨模式定位报工步对应的 WorkProcess: 优先按工序名 (legacy+workflow 通用), 名字不唯一时按
     * processOrder (legacy 链) 消歧; 名字缺失回退 processOrder。解析不到返 null。
     */
    private WorkProcess resolveStepWorkProcess(String factoryId, String productTypeId, StepEntry st) {
        if (workProcessRepository == null) return null;
        String pn = st.getProcessName();
        if (pn != null && !pn.isBlank()) {
            List<WorkProcess> byName = workProcessRepository.findByFactoryIdAndProcessName(factoryId, pn.trim());
            if (byName.size() == 1) return byName.get(0);
            if (byName.size() > 1) {
                WorkProcess viaOrder = resolveViaProductWorkProcess(factoryId, productTypeId, st);
                if (viaOrder != null) {
                    for (WorkProcess w : byName) {
                        if (w.getId() != null && w.getId().equals(viaOrder.getId())) return viaOrder;
                    }
                }
                return byName.get(0);
            }
        }
        return resolveViaProductWorkProcess(factoryId, productTypeId, st);
    }

    private WorkProcess resolveViaProductWorkProcess(String factoryId, String productTypeId, StepEntry st) {
        if (productWorkProcessRepository == null || workProcessRepository == null) return null;
        if (productTypeId == null || st.getProcessOrder() == null) return null;
        List<ProductWorkProcess> pwps = productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, productTypeId);
        ProductWorkProcess pwp = pwps.stream()
                .filter(p -> st.getProcessOrder().equals(p.getProcessOrder()))
                .findFirst().orElse(null);
        if (pwp == null || pwp.getWorkProcessId() == null) return null;
        return workProcessRepository.findById(pwp.getWorkProcessId()).orElse(null);
    }

    private boolean isSeasoningStep(StepEntry st) {
        if ("SEASONING".equals(st.getProcessCategory())) {
            return true;
        }
        if (st.getProcessCategory() == null && st.getPotCount() != null) {
            return true;
        }
        // 2026-06-30: 熟制/卤制/注射等道按工序名识别为调味道 (与"未识别为调味"警告同正则)。
        //   实测 F006 熟制道 processCategory='加工' 且熟制 grid 无 potCount 字段 → 此前 isSeasoningStep
        //   恒 false → 调料成本结构性恒 0 (配了调料配方也不流入)。按名识别后让配方调料成本自动流入,
        //   无需每个 熟制 process 手动配 processCategory=SEASONING。
        //   安全: 无配方时 computeSeasoningCost 仍返 0 + warning (无成本变化);
        //   buildPotRawKgs 单锅=整批投入 → dosage×投料量 (投料-based) 计算正确。
        String pn = st.getProcessName();
        return pn != null && pn.matches(".*(熟|卤|煮|腌|注射|入味|调味).*");
    }

    /**
     * 跨天: 报工日期取该工序实际操作日 ({@code st.processDate})；未填回退当天。
     * 让焯水/熟制等工序跨天时, 成本报工归到各自真实日期 (成本按日归集正确)。
     */
    private LocalDate resolveReportDate(StepEntry st) {
        return st.getProcessDate() != null ? st.getProcessDate() : LocalDate.now();
    }

    /**
     * 调料配方按工序 (2026-07-13) 的成本核算. 返 null 表示"该步不走 per-工序 路径"
     * (repos 未注入 / 解析不到工序 / 该工序未配 per-工序 调料) → 调用方落原整-SKU 逻辑, 零回归。
     *
     * <p>注射工序: 成本 = 配置的绝对注射量(kg) × 注射内容(INJECTION 段)每kg单价 (Steve 2026-07-13 绝对量口径)。
     * 熟制工序: 成本 = 逐锅原料 × 熟制/kg × (第一锅×1, 后续×该工序第二锅比例)。
     * 各工序只读自己 workProcessId 名下的调料明细 → 天然不跨工序重复计。
     */
    private BigDecimal computePerProcessSeasoningCost(String factoryId, String productTypeId,
                                                      StepEntry st, List<BigDecimal> potRawKgs,
                                                      List<String> warnings) {
        if (bomRecipeRepo == null || bomSeasoningItemRepo == null || bomProcessInjectionConfigRepository == null) {
            return null; // 仅供不完整 Mockito fixture；Spring 运行时依赖均为必注入。
        }
        WorkProcess wp = resolveStepWorkProcess(factoryId, productTypeId, st);
        if (wp == null || wp.getId() == null) return null;
        Optional<BomRecipe> bomOpt = bomRecipeRepo
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrue(factoryId, productTypeId);
        if (bomOpt.isEmpty()) return null;
        String recipeId = bomOpt.get().getId();
        String wpId = wp.getId();

        List<BomSeasoningItem> lines = bomSeasoningItemRepo
                .findByRecipeIdAndWorkProcessIdOrderBySeqAsc(recipeId, wpId);
        if (SeasoningProcessCategory.INJECTION.equals(wp.getProcessCategory())) {
            Optional<BomProcessInjectionConfig> configOpt = bomProcessInjectionConfigRepository
                    .findByRecipeIdAndWorkProcessIdAndDeletedAtIsNull(recipeId, wpId);
            if (lines.isEmpty() && configOpt.isEmpty()) {
                return null;
            }
            // 注射: 绝对注射量 × 注射内容每kg单价; potRawKgs=null → 熟制段成本 0
            BigDecimal injectionAmountKg = configOpt
                    .map(BomProcessInjectionConfig::getInjectionAmountKg).orElse(null);
            if (injectionAmountKg == null) {
                // audit Finding 3 修复: 配了注射内容但没填注射量 → 不静默 0, 明确 warning 指向配置位置
                String pn = st.getProcessName() == null ? String.valueOf(st.getProcessOrder()) : st.getProcessName();
                warnings.add("注射工序「" + pn + "」已配注射内容但未填注射量(kg)，注射调料成本暂记 0；"
                        + "请在「生产 → BOM 配方 → 调料配方」补该工序注射量。");
                return BigDecimal.ZERO;
            }
            SeasoningCost sc = RecipeCostCalculator.compute(lines, injectionAmountKg, null);
            return sc.getTotal();
        }
        if (lines.isEmpty()) {
            return null;
        }
        // 熟制只读每条调料 binding 的锅序比例；不存在 process/header fallback。
        SeasoningCost sc = RecipeCostCalculator.computeBindingPotRules(lines, potRawKgs);
        return sc.getTotal();
    }

    private BigDecimal computeSeasoningCost(String factoryId, String productTypeId,
                                             StepEntry st, List<String> warnings) {
        BigDecimal injectionRawKg = nz(st.getInputQuantity());
        List<BigDecimal> potRawKgs = buildPotRawKgs(st);

        // 调料配方按工序：仅当该报工步能解析到工序且该工序已配置时按工序计算。
        BigDecimal perProcess = computePerProcessSeasoningCost(factoryId, productTypeId, st, potRawKgs, warnings);
        if (perProcess != null) {
            return perProcess;
        }

        // 整 SKU 调料也只读当前 BOM；缺失配置时明确告警，不再读取旧表。
        if (bomRecipeRepo != null && bomSeasoningItemRepo != null) {
            Optional<BomRecipe> bomOpt = bomRecipeRepo
                    .findByFactoryIdAndProductTypeIdAndIsCurrentTrue(factoryId, productTypeId);
            if (bomOpt.isPresent()) {
                // 整-SKU 路径只取未绑定工序的明细；按工序明细由上方路径独占核算。
                List<BomSeasoningItem> bomSeasoning =
                        bomSeasoningItemRepo.findByRecipeIdAndWorkProcessIdIsNullOrderBySeqAsc(bomOpt.get().getId());
                if (!bomSeasoning.isEmpty()) {
                    SeasoningCost sc = RecipeCostCalculator.compute(
                            bomSeasoning, injectionRawKg, potRawKgs);
                    return sc.getTotal();
                }
                warnings.add("产品 " + productTypeId + " 的当前 BOM 无调料明细，调料成本暂记 0；"
                        + "请核对「生产 → BOM 配方 → 调料配方」。");
                return BigDecimal.ZERO;
            }
        } else {
            log.error("[SEASONING-COST] BOM repositories unavailable (factory={}, sku={})",
                    factoryId, productTypeId);
            warnings.add("BOM 调料配置服务不可用，调料成本暂记 0；请联系管理员检查服务配置。");
            return BigDecimal.ZERO;
        }

        warnings.add("产品 " + productTypeId + " 未设置当前 BOM 调料配方，调料成本暂记 0；"
                + "请在「生产 → BOM 配方 → 调料配方」完成配置后重新核算。");
        return BigDecimal.ZERO;
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
                    warnings.add("工时单价未配置, 本批人工成本按默认 " + LABOR_RATE_DEFAULT
                            + " 元/工时计入; 如需覆盖请在工厂成本设置中配置工时单价");
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
    @Override
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

    private static String inputUnitOf(StepEntry step) {
        return firstNonBlank(step.getInputUnit(), step.getUnit(), "kg");
    }

    private static String outputUnitOf(StepEntry step) {
        return firstNonBlank(step.getOutputUnit(), step.getUnit(), "kg");
    }

    private static String resolveBatchOutputUnit(List<StepEntry> steps) {
        if (steps != null) {
            for (int i = steps.size() - 1; i >= 0; i--) {
                StepEntry step = steps.get(i);
                if (step != null) {
                    String unit = firstNonBlank(step.getOutputUnit(), step.getUnit());
                    if (unit != null) return unit;
                }
            }
        }
        return "kg";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static BigDecimal edgeUnitPrice(ResolvedEdge edge) {
        return edge.getResolvedUnitPrice() != null
                ? edge.getResolvedUnitPrice()
                : edge.getSourceBatch().getUnitPrice();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
