package com.cretas.aims.service.processentry.impl;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.BatchEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.RawInput;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.UpstreamSource;
import com.cretas.aims.dto.processentry.ProcessChainEntryResult;
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
            // 3. 建 ProductionBatch (IN_PROGRESS — 文员录入意味着生产已完成)
            ProductionBatch batch = createProductionBatch(factoryId, planId, be, operatorId);
            batchIdsByKey.put(be.getClientBatchKey(), batch.getId());
            batchNumbersByKey.put(be.getClientBatchKey(), batch.getBatchNumber());

            BigDecimal batchTotalCost = BigDecimal.ZERO;
            BigDecimal lastOutputQty = BigDecimal.ZERO;
            // Fix: WIP MaterialBatch must carry a raw_material_types FK, not a product_types id.
            // Capture the first available raw materialTypeId from this batch's lineage.
            String firstRawMaterialTypeId = null;

            for (StepEntry st : be.getSteps()) {
                // 4a. 原料消耗 (首道领料)
                if (st.getRawMaterialInputs() != null) {
                    for (RawInput ri : st.getRawMaterialInputs()) {
                        MaterialBatch rawMb = materialBatchRepo.findByIdAndFactoryId(
                                        ri.getMaterialBatchId(), factoryId)
                                .orElseThrow(() -> new BusinessException(404,
                                        "原料批次不存在: " + ri.getMaterialBatchId()));
                        if (firstRawMaterialTypeId == null && rawMb.getMaterialTypeId() != null) {
                            firstRawMaterialTypeId = rawMb.getMaterialTypeId();
                        }
                        BigDecimal price = nz(rawMb.getUnitPrice());
                        BigDecimal qty = nz(ri.getQuantity());
                        BigDecimal lineCost = price.multiply(qty).setScale(2, RoundingMode.HALF_UP);
                        writeConsumption(factoryId, planId, batch.getId(),
                                rawMb.getId(), rawMb.getMaterialTypeId(),
                                qty, price, lineCost, "RAW_MATERIAL", operatorId);
                        batchTotalCost = batchTotalCost.add(lineCost);
                        consumptionsWritten++;
                    }
                }

                // 4b. 混锅来源消耗 (SEMI_FINISHED): feedKg × 上游单价
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
                        BigDecimal feedKg = nz(us.getFeedQuantityKg());
                        BigDecimal upstreamUnitPrice = nz(srcMb.getUnitPrice());
                        BigDecimal edgeCost = upstreamUnitPrice.multiply(feedKg)
                                .setScale(2, RoundingMode.HALF_UP);
                        writeConsumption(factoryId, planId, batch.getId(),
                                srcMbId, srcMb.getMaterialTypeId(),
                                feedKg, upstreamUnitPrice, edgeCost, "SEMI_FINISHED", operatorId);
                        batchTotalCost = batchTotalCost.add(edgeCost);
                        consumptionsWritten++;
                    }
                }

                // 4c. 调料成本 (熟制道，使用 RecipeCostCalculator)
                // 写 ProductionReport 行 (costCategory=SEASONING)，让 OrderCostBreakdownService
                // 通过 yieldReportService.getYield → steps[i].materialCost 读取。
                // 禁止写 SEASONING_VIRTUAL MaterialConsumption：traceCost() 只递归真实 MaterialBatch，
                // 虚拟占位符会误入 raw 成本桶导致分桶错乱。
                if (isSeasoningStep(st)) {
                    BigDecimal seasoningCost = computeSeasoningCost(factoryId, be.getProductTypeId(), st, warnings);
                    if (seasoningCost.signum() > 0) {
                        writeSeasoningReport(factoryId, batch.getId(), st, seasoningCost, operatorId);
                        batchTotalCost = batchTotalCost.add(seasoningCost);
                    }
                } else if (st.getUpstreamSources() != null && !st.getUpstreamSources().isEmpty()) {
                    // SP-D Fix 3: 混锅/熟制工序未被识别为调料步骤时给出警告
                    // 防止 processCategory=SEASONING 未配置或 potCount 缺失导致调料成本静默丢失 (计入¥0).
                    String processName = st.getProcessName() != null ? st.getProcessName() : ("工序" + st.getProcessOrder());
                    warnings.add("熟制工序「" + processName + "」未识别为调味(缺 processCategory=SEASONING 或锅数)," +
                            " 调料成本未计入 — 请配置工序成本类别");
                }

                // 4d. 人工成本 (不写 MaterialConsumption，直接计入批次总成本)
                BigDecimal laborCost = computeLaborCost(st, laborRate);
                batchTotalCost = batchTotalCost.add(laborCost);

                // 追踪产出量 (取最后一道有产出的 step)
                if (st.getOutputQuantity() != null && st.getOutputQuantity().signum() > 0) {
                    lastOutputQty = st.getOutputQuantity();
                }
            }

            // 5. 半成品批产出 → MaterialBatch(priced, PRODUCTION_BATCH 来源)
            if (!be.isFinished() && lastOutputQty.signum() > 0) {
                BigDecimal wipUnitPrice = batchTotalCost.signum() > 0 && lastOutputQty.signum() > 0
                        ? batchTotalCost.divide(lastOutputQty, 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                String wipMbId = createWipMaterialBatch(
                        factoryId, batch, firstRawMaterialTypeId,
                        lastOutputQty, wipUnitPrice, wksWarehouseId, operatorId);
                wipMbIdByKey.put(be.getClientBatchKey(), wipMbId);
                wipMaterialized++;
            }
            if (be.isFinished()) {
                finishedBatchNumber = batch.getBatchNumber();
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
    // Batch creation
    // ─────────────────────────────────────────────────────────────

    private ProductionBatch createProductionBatch(String factoryId, String planId,
                                                   BatchEntry be, Long operatorId) {
        String batchNumber = be.getBatchNumber() != null && !be.getBatchNumber().isBlank()
                ? be.getBatchNumber()
                : generateBatchNumber(factoryId, be.isFinished());

        // Ensure uniqueness; DB UNIQUE(batch_number) 兜底并发
        if (batchRepo.existsByFactoryIdAndBatchNumber(factoryId, batchNumber)) {
            batchNumber = batchNumber + "-" + (System.currentTimeMillis() % 10000);
        }

        // Determine planned / actual quantity from last step output
        Optional<BigDecimal> lastOutputOpt = be.getSteps() == null ? Optional.empty()
                : be.getSteps().stream()
                .filter(s -> s.getOutputQuantity() != null && s.getOutputQuantity().signum() > 0)
                .reduce((a, b) -> b)  // last step
                .map(StepEntry::getOutputQuantity);
        BigDecimal qty;
        if (be.isFinished()) {
            // For FINISHED batches, missing output quantity is an error — not a silent fallback
            qty = lastOutputOpt.orElseThrow(() ->
                    new BusinessException(400, "成品批次无有效产出数量, 无法核算单盒成本"));
        } else {
            qty = lastOutputOpt.orElse(BigDecimal.ONE);
        }

        ProductionBatch batch = new ProductionBatch();
        batch.setFactoryId(factoryId);
        // Only link FINISHED batches to the plan so OrderCostBreakdownService.compute()
        // doesn't double-count WIP batch raw costs (WIP costs are already traced via
        // traceCost() when the finished batch's consumption is followed upstream).
        batch.setProductionPlanId(be.isFinished() ? planId : null);
        batch.setProductTypeId(be.getProductTypeId());
        batch.setBatchNumber(batchNumber);
        batch.setQuantity(qty);
        batch.setUnit(be.getSteps() == null ? "kg"
                : be.getSteps().stream().findFirst().map(StepEntry::getUnit).filter(u -> u != null).orElse("kg"));
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);  // 文员录入 = 生产进行中
        // SP-D Fix 1a: 区分 CLERK_WIP 与 REGULAR 批次
        // CLK-W- 前缀 = isFinished=false 中间批次, 不计入仪表盘; CLK-B- 前缀 = 成品批次.
        batch.setBatchType(be.isFinished() ? "REGULAR" : "CLERK_WIP");
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
        report.setReportDate(LocalDate.now());
        report.setCostCategory("SEASONING");
        report.setMaterialCost(seasoningCost);
        report.setProcessOrder(st.getProcessOrder());
        report.setOutputQuantity(st.getOutputQuantity());
        report.setInputQuantity(st.getInputQuantity());
        reportRepo.save(report);
    }

    // ─────────────────────────────────────────────────────────────
    // Seasoning cost (SP-A RecipeCostCalculator)
    // ─────────────────────────────────────────────────────────────

    private boolean isSeasoningStep(StepEntry st) {
        return "SEASONING".equals(st.getProcessCategory()) ||
               (st.getProcessCategory() == null && st.getPotCount() != null);
    }

    private BigDecimal computeSeasoningCost(String factoryId, String productTypeId,
                                             StepEntry st, List<String> warnings) {
        Optional<ProductRecipe> recipeOpt = recipeRepo
                .findByFactoryIdAndProductTypeIdAndStatus(factoryId, productTypeId, "ACTIVE");
        if (recipeOpt.isEmpty()) {
            warnings.add("产品 " + productTypeId + " 无有效调料配方(ACTIVE)，调料成本跳过");
            return BigDecimal.ZERO;
        }
        ProductRecipe recipe = recipeOpt.get();
        List<RecipeIngredient> ingredients = ingredientRepo.findByRecipeIdOrderBySeqAsc(recipe.getId());
        BigDecimal injectionRawKg = nz(st.getInputQuantity());
        List<BigDecimal> potRawKgs = buildPotRawKgs(st);
        SeasoningCost sc = RecipeCostCalculator.compute(recipe, ingredients, injectionRawKg, potRawKgs);
        return sc.getTotal();
    }

    private List<BigDecimal> buildPotRawKgs(StepEntry st) {
        if (st.getPotRawKgs() != null && !st.getPotRawKgs().isEmpty()) {
            return st.getPotRawKgs();
        }
        int n = st.getPotCount() != null && st.getPotCount() > 0 ? st.getPotCount() : 1;
        BigDecimal input = nz(st.getInputQuantity());
        BigDecimal each = input.divide(new BigDecimal(n), 4, RoundingMode.HALF_UP);
        List<BigDecimal> result = new ArrayList<>();
        for (int i = 0; i < n; i++) result.add(each);
        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // Labor cost calculation
    // ─────────────────────────────────────────────────────────────

    /**
     * SP-C: 从 factory_cost_settings 读工时单价; 未配置则回退 LABOR_RATE_DEFAULT 并记 warning.
     * null-tolerant: costSettingsRepository 为 null 时(测试 @InjectMocks 未注入)直接走 fallback.
     * public for testing — mirrors minutesBetween visibility pattern.
     */
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

    private String resolveWarehouseId(String factoryId, String code, List<String> warnings) {
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
