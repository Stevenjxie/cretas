package com.cretas.aims.service.processentry.impl;

import com.cretas.aims.dto.processentry.MaterializeContext;
import com.cretas.aims.dto.processentry.MaterializedBatch;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.UpstreamSource;
import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.processentry.ProcessSheetRowHistoryView;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.dto.processentry.ProcessSheetRowView;
import com.cretas.aims.dto.processentry.ResolvedEdge;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.processentry.ProcessSheetRowChangeLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.event.WorkflowTaskProgressRequestedEvent;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowChangeLogRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import com.cretas.aims.service.processentry.ProcessSheetService;
import com.cretas.aims.service.processentry.ProductionStockAllocationService;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import com.cretas.aims.service.wip.WipInventoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SP-F Task 1.5 — 逐工序电子表格单行增量服务实现 (新建路径)。
 *
 * <p>复用 {@link ClerkProcessEntryService#materializeBatch} 写核心; caller 这边负责:
 * <ul>
 *   <li>跨租户守卫 (plan 归属 factory)</li>
 *   <li>factory-scoped 上游/原料边解析 (rawMaterialInputs → RAW; upstreamSources → SEMI via 持久化 batchNumber)</li>
 *   <li>WIP 身份防线: 产出批 materialTypeId 来自本行产出产品快照，投入边仅保留 provenance</li>
 *   <li>把请求映射为单个 StepEntry (含 multi-segment laborSegments)</li>
 *   <li>写/更新 process_sheet_rows 行追踪表</li>
 * </ul>
 *
 * <p>再次保存已存在的行 → 委托 {@link #resaveRow} (Task 1.6, 当前为 409 stub)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessSheetServiceImpl implements ProcessSheetService {

    private final ClerkProcessEntryService clerkService;
    private final ProcessSheetRowRepository rowRepo;
    private final MaterialBatchRepository materialBatchRepo;
    private final ProductionBatchRepository productionBatchRepo;
    private final MaterialConsumptionRepository consumptionRepo;
    private final ProductionReportRepository reportRepo;
    private final ProductionPlanRepository productionPlanRepository;
    private final ProcessSheetRowChangeLogRepository changeLogRepo;
    private final ObjectMapper objectMapper;
    // F006 双出成率 扩展依赖
    private final SemiFinishedInventoryRepository wipRepo;
    private final WorkProcessTaskRepository taskRepo;
    private final WorkProcessRepository processRepo;
    private final ProductWorkProcessRepository productWorkProcessRepo;
    private final ProductTypeRepository productTypeRepo;
    /** ①c 成品作投料来源 — 保存期 FG 投料存在性 loud-fail 校验 (禁止降级)。 */
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepo;
    /**
     * #1252 半成品注入工序 (中段起步): 纯外部库存 (SFI/FG) 喂的<b>非成品中间道</b>产出须在<b>保存时</b>即
     * 入常驻半成品库 (SFI IN, {@link WipInventoryService#postClerkOutput}), 使下游道能在小结前就选到本道产出
     * (否则产出只在小结入库 → 下游道保存期解析上游 SFI 时 SFI_NOT_FOUND → 从中段起步的后段链被阻断)。
     */
    private final WipInventoryService wipInventoryService;
    /** #1252 注入产出成本核算: ①c FG 投料的成本 (feedKg×每单位) + 折算 (盒⇄kg), 与小结 computeOutputUnitCost 同源。 */
    private final com.cretas.aims.service.inventory.FinishedGoodsFeedService finishedGoodsFeedService;

    @Autowired(required = false)
    private WarehouseResolver warehouseResolver;

    /**
     * Canonical unit and tenant alias contract. Optional only so focused unit tests can keep
     * constructing this service without a Spring context; built-in aliases still apply there.
     */
    @Autowired(required = false)
    private UnitContractService unitContractService;

    /**
     * ② Part B 生产领料单 Gate — 工厂级"报工前必须领料确认"开关读取 (required=false 兼容单测).
     * 无 settings 行 / 未注入 → 兜底 false = 报工照旧 (向后兼容安全默认)。
     */
    @Autowired(required = false)
    private com.cretas.aims.repository.FactorySettingsRepository factorySettingsRepository;

    /**
     * ② Part B Gate — 校验该生产计划是否已有仓管确认的领料单 (required=false 兼容单测).
     */
    @Autowired(required = false)
    private com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository requisitionRepository;

    /**
     * 2B B3 (clerk-path workflow 联通) — 若计划关联 workflow 批次, 用 workflow 端口投影校验本行产出 (防呆:
     * 产出类型 半成品/成品 与单位必须对齐 workflow 配置, 不对则 loud-fail)。required=false: 单测/未注入 → 跳过,
     * legacy (非 workflow) 计划 service 返回 null → 不校验, 现有 clerk 路径行为不变 (additive)。
     */
    @Autowired(required = false)
    private com.cretas.aims.service.workflow.WorkflowClerkSheetService workflowClerkSheetService;

    /** Workflow progress is projected only after the process-sheet transaction commits. */
    @Autowired(required = false)
    private ApplicationEventPublisher applicationEventPublisher;

    /** 新正式提交入口的生产库批次自动分摊器；旧 saveRow 路径不依赖它。 */
    @Autowired(required = false)
    private ProductionStockAllocationService productionStockAllocationService;

    @Autowired(required = false)
    private BomRecipeRepository bomRecipeRepository;

    @Autowired(required = false)
    private BomRecipeItemRepository bomRecipeItemRepository;

    @Autowired(required = false)
    private BomSeasoningItemRepository bomSeasoningItemRepository;

    @Override
    @Transactional
    public ProcessSheetRowResult saveDraft(String factoryId, String planId,
                                            ProcessSheetRowRequest req, Long userId) {
        assertAuthenticatedPlan(factoryId, planId, userId);
        // Drafts may be intentionally incomplete. Formal submission validates the
        // Workflow ports and derives every authoritative unit/weight before writing stock.
        validateCustomFields(factoryId, req);

        Optional<ProcessSheetRow> existing = rowRepo
                .findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
                        factoryId, planId, req.getProcessCode(), req.getClientRowId());
        ProcessSheetRowRequest before = existing.map(row -> tryDeserialize(row.getRowPayload())).orElse(null);
        if (existing.isPresent()) {
            ProcessSheetRow row = existing.get();
            if (row.getInterimSettledAt() != null
                    || ProcessSheetRow.SUBMISSION_SUBMITTED.equals(row.getSubmissionStatus())
                    || row.getBatchId() != null
                    || ProcessSheetRow.STATUS_SAVED_SFI.equals(row.getRowStatus())) {
                throw new BusinessException(409, "已正式提交或已物化的报工不能覆盖为草稿")
                        .withCode("PROCESS_SHEET_DRAFT_OVERWRITE_FORBIDDEN")
                        .withHint("请新建草稿；已提交记录需按既有撤销流程处理")
                        .withSeverity("BLOCKING");
            }
            updateRowInPlace(row, req, null, null, "DRAFT");
            row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_DRAFT);
            rowRepo.save(row);
            logChange(factoryId, planId, req, "UPDATE", before, req, userId);
        } else {
            persistRow(factoryId, planId, req, null, null, "DRAFT");
            ProcessSheetRow row = rowRepo
                    .findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
                            factoryId, planId, req.getProcessCode(), req.getClientRowId())
                    .orElseThrow(() -> new BusinessException(500, "草稿保存后未能读取工序行")
                            .withCode("PROCESS_SHEET_DRAFT_PERSIST_FAILED"));
            row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_DRAFT);
            rowRepo.save(row);
            logChange(factoryId, planId, req, "CREATE", null, req, userId);
        }

        ProcessSheetRowResult result = buildResult(
                req, null, null, null, null, null, existing.isPresent(), false, List.of());
        result.setSubmissionStatus(ProcessSheetRow.SUBMISSION_DRAFT);
        result.setInputAllocations(List.of());
        return result;
    }

    @Override
    @Transactional
    public ProcessSheetRowResult submitRow(String factoryId, String planId,
                                            ProcessSheetRowRequest req, Long userId) {
        assertAuthenticatedPlan(factoryId, planId, userId);
        if (req.getProcessDate() == null) {
            throw new BusinessException(400, "正式报工必须填写生产日期")
                    .withCode("PROCESS_SHEET_PROCESS_DATE_REQUIRED")
                    .withHint("草稿可以暂不填写，正式报工前请补充实际生产日期")
                    .withHintTarget("生产日期")
                    .withSeverity("BLOCKING");
        }
        if (req.getOutputs() != null && req.getOutputs().stream()
                .filter(Objects::nonNull)
                .anyMatch(output -> output.getQuantity() != null
                        && output.getQuantity().signum() < 0)) {
            throw new BusinessException(400, "多产出的产出数量不能为负数")
                    .withCode("PROCESS_SHEET_OUTPUT_QUANTITY_INVALID");
        }
        assertNoNegativeWorkflowInputs(req);
        boolean hasSingleOutput = req.getOutputQuantity() != null
                && req.getOutputQuantity().signum() > 0;
        boolean hasMultiOutput = req.getOutputs() != null
                && !req.getOutputs().isEmpty()
                && req.getOutputs().stream().anyMatch(output ->
                        output.getQuantity() != null && output.getQuantity().signum() > 0);
        if (!hasSingleOutput && !hasMultiOutput) {
            throw new BusinessException(400, "正式提交必须填写大于 0 的实际产出数量")
                    .withCode("PROCESS_SHEET_OUTPUT_REQUIRED")
                    .withHint("当前记录仍可保存为草稿，补充实际产出后再正式提交")
                    .withSeverity("BLOCKING")
                    .withHintTarget("实际生产");
        }
        assertPositiveDeclaredUpstreamFeeds(req);
        retainActualPositiveSelections(req);
        // 正式报工必须在任何库存分摊、持久化或成本动作前，按实际正数量端口集合完成组约束校验。
        // saveRow 仍允许不完整草稿，仅校验草稿中实际携带端口的归属、SKU、单位和数量合法性。
        validateWorkflowSubmissionSelections(factoryId, planId, req);

        Optional<ProcessSheetRow> existing = rowRepo
                .findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
                        factoryId, planId, req.getProcessCode(), req.getClientRowId());
        if (existing.isPresent()
                && ProcessSheetRow.SUBMISSION_SUBMITTED.equals(existing.get().getSubmissionStatus())) {
            throw new BusinessException(409, "该报工行已经正式提交")
                    .withCode("PROCESS_SHEET_ROW_ALREADY_SUBMITTED")
                    .withHint("请勿重复提交；如需更正请走撤销流程")
                    .withSeverity("BLOCKING");
        }
        if (req.getOutputs() != null && !req.getOutputs().isEmpty()) {
            boolean submittedGroupExists = rowRepo.findByFactoryIdAndPlanId(factoryId, planId).stream()
                    .filter(row -> row.getClientRowId() != null
                            && row.getClientRowId().startsWith(req.getClientRowId() + "#"))
                    .anyMatch(row -> ProcessSheetRow.SUBMISSION_SUBMITTED.equals(row.getSubmissionStatus()));
            if (submittedGroupExists) {
                throw new BusinessException(409, "该多产出报工组已经正式提交")
                        .withCode("PROCESS_SHEET_ROW_ALREADY_SUBMITTED")
                        .withHint("请勿重复提交；如需更正请走撤销流程")
                        .withSeverity("BLOCKING");
            }
        }

        List<ProductionStockAllocationService.PlannedAllocation> allocations = new ArrayList<>();
        if (req.getMaterialInputTotals() != null && !req.getMaterialInputTotals().isEmpty()) {
            if (productionStockAllocationService == null) {
                throw new BusinessException(500, "生产库自动分摊服务未启用，不能正式提交")
                        .withCode("PRODUCTION_STOCK_ALLOCATION_UNAVAILABLE")
                        .withHint("请联系管理员检查生产库分摊服务配置")
                        .withSeverity("BLOCKING");
            }
            allocations.addAll(productionStockAllocationService.plan(
                    factoryId, planId, req.getMaterialInputTotals()));
            req.setRawMaterialInputs(productionStockAllocationService.toRawInputs(allocations));
            if (req.getInputQuantity() == null) {
                // PlannedAllocation 永远是 kg；不能直接把 1000g + 2kg 相加成 1002。
                req.setInputQuantity(allocations.stream()
                        .map(ProductionStockAllocationService.PlannedAllocation::quantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
            }
            req.setInputUnit("kg");
        } else if (req.getRawMaterialInputs() != null && !req.getRawMaterialInputs().isEmpty()) {
            if (productionStockAllocationService == null) {
                throw new BusinessException(500, "生产库批次锁定服务未启用，不能正式提交")
                        .withCode("PRODUCTION_STOCK_ALLOCATION_UNAVAILABLE")
                        .withSeverity("BLOCKING");
            }
            allocations.addAll(productionStockAllocationService.planExplicit(
                    factoryId, planId, req.getRawMaterialInputs()));
            req.setRawMaterialInputs(productionStockAllocationService.toRawInputs(allocations));
            if (req.getInputQuantity() == null) {
                req.setInputQuantity(allocations.stream()
                        .map(ProductionStockAllocationService.PlannedAllocation::quantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
            }
            req.setInputUnit("kg");
        }

        boolean producesFinishedGoods = req.isFinished()
                || (req.getOutputs() != null && req.getOutputs().stream()
                        .anyMatch(ProcessSheetRowRequest.OutputLine::isFinished));
        List<ProductionStockAllocationService.AutomaticRequirement> automaticRequirements =
                buildAutomaticBomRequirements(factoryId, planId, req, producesFinishedGoods);
        if (!automaticRequirements.isEmpty() && productionStockAllocationService == null) {
            throw new BusinessException(500, "生产库自动分摊服务未启用，不能扣减包材/调料")
                    .withCode("PRODUCTION_STOCK_ALLOCATION_UNAVAILABLE")
                    .withHint("请联系管理员检查生产库分摊服务配置")
                    .withSeverity("BLOCKING");
        }
        if (!automaticRequirements.isEmpty()) {
            List<ProductionStockAllocationService.PlannedAllocation> automaticAllocations =
                    productionStockAllocationService.planNative(
                            factoryId, planId, automaticRequirements);
            allocations.addAll(automaticAllocations);
            List<ProcessSheetRowRequest.RawInput> allRawInputs =
                    new ArrayList<>(Optional.ofNullable(req.getRawMaterialInputs()).orElseGet(List::of));
            allRawInputs.addAll(productionStockAllocationService.toRawInputs(automaticAllocations));
            req.setRawMaterialInputs(allRawInputs);
        }
        if (producesFinishedGoods
                && (req.getInputQuantity() == null || req.getInputQuantity().signum() <= 0)) {
            throw new BusinessException(409, "成品报工缺少可追溯的实际投入量，不能正式提交")
                    .withCode("FINISHED_REPORT_INPUT_REQUIRED")
                    .withHint("请刷新上游库存后重试；系统无法确定投入时只能保存草稿")
                    .withSeverity("BLOCKING")
                    .withHintTarget("实际投入");
        }

        ProcessSheetRowResult result = saveRow(factoryId, planId, req, userId);
        List<ProcessSheetRow> submittedRows = resolveSubmittedRows(factoryId, planId, req, result);
        if (submittedRows.isEmpty()) {
            throw new BusinessException(500, "正式提交后未能读取工序行")
                    .withCode("PROCESS_SHEET_SUBMIT_PERSIST_FAILED");
        }
        for (ProcessSheetRow row : submittedRows) {
            row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
            rowRepo.save(row);
        }
        if (!allocations.isEmpty()) {
            productionStockAllocationService.persist(
                    factoryId, planId, submittedRows.get(0).getId(), userId, allocations);
        }
        result.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        result.setInputAllocations(productionStockAllocationService == null
                ? List.of()
                : productionStockAllocationService.toResult(allocations));
        return result;
    }

    private List<ProductionStockAllocationService.AutomaticRequirement> buildAutomaticBomRequirements(
            String factoryId,
            String planId,
            ProcessSheetRowRequest req,
            boolean producesFinishedGoods) {
        if (bomRecipeRepository == null || bomRecipeItemRepository == null) {
            return List.of();
        }
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new BusinessException(403, "无权访问该计划"));
        boolean workflowPlan = plan.getWorkflowSelectionMode()
                == com.cretas.aims.entity.ProductionBatch.WorkflowSelectionMode.WORKFLOW;
        Map<String, String> pinnedRecipes = Optional
                .ofNullable(plan.getSelectedBomRecipeIdsByProduct())
                .orElseGet(Map::of);
        if (workflowPlan && (plan.getSelectedWorkflowRevisionId() == null
                || plan.getSelectedBomFamilyId() == null
                || pinnedRecipes.isEmpty())) {
            throw new BusinessException(409, "Workflow plan is missing its pinned BOM family authority")
                    .withCode("PLAN_BOM_AUTHORITY_INCOMPLETE")
                    .withSeverity("BLOCKING");
        }
        if (plan.getSelectedBomRecipeId() == null || plan.getSelectedBomRecipeId().isBlank()) {
            return List.of(); // 历史未固定 BOM 的计划不猜测当前版本。
        }
        String pinnedRecipeId = plan.getSelectedBomRecipeId();
        BomRecipe recipe = bomRecipeRepository.findById(pinnedRecipeId)
                .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                .orElseThrow(() -> new BusinessException(409, "计划固定的 BOM 版本不存在")
                        .withCode("PINNED_BOM_NOT_FOUND")
                        .withSeverity("BLOCKING"));
        List<BomRecipe> pinnedFamily = workflowPlan
                ? resolvePinnedBomFamily(plan, pinnedRecipes)
                : resolvePinnedBomFamily(recipe);

        List<ProductionStockAllocationService.AutomaticRequirement> requirements = new ArrayList<>();
        if (producesFinishedGoods) {
            for (FinishedBomOutput reportedOutput : finishedBomOutputs(req)) {
                BomRecipe outputRecipe = resolvePinnedOutputRecipe(
                        pinnedFamily, reportedOutput.productTypeId());
                String reportedUnit = canonicalBomUnit(reportedOutput.unit());
                String bomOutputUnit = canonicalBomUnit(outputRecipe.getOutputUnit());
                if (!Objects.equals(reportedUnit, bomOutputUnit)) {
                    throw new BusinessException(409, "成品报工单位与计划固定 BOM 的产出单位不一致")
                            .withCode("BOM_OUTPUT_UNIT_MISMATCH")
                            .withHint("SKU " + reportedOutput.productTypeId()
                                    + "：报工单位 " + reportedUnit + "，BOM 单位 " + bomOutputUnit)
                            .withSeverity("BLOCKING");
                }
                if (outputRecipe.getOutputQuantityPerUnit() == null
                        || outputRecipe.getOutputQuantityPerUnit().signum() <= 0) {
                    throw new BusinessException(409, "计划固定 BOM 缺少有效的基准产出数量")
                            .withCode("BOM_OUTPUT_BASIS_INVALID")
                            .withSeverity("BLOCKING");
                }
                BigDecimal scale = reportedOutput.quantity().divide(
                        outputRecipe.getOutputQuantityPerUnit(), 12, RoundingMode.HALF_UP);
                for (BomRecipeItem item : bomRecipeItemRepository
                        .findByRecipeIdOrderBySortOrderAsc(outputRecipe.getId())) {
                    if (!"PACKAGING".equalsIgnoreCase(item.getMaterialCategory())
                            || Boolean.TRUE.equals(item.getIsOptional())) {
                        continue;
                    }
                    BigDecimal perBasis = item.calculateActualQuantity();
                    if (perBasis == null || perBasis.signum() <= 0) {
                        throw new BusinessException(409, "包装材料缺少有效用量: " + item.getMaterialName())
                                .withCode("PACKAGING_REQUIREMENT_INVALID")
                                .withSeverity("BLOCKING");
                    }
                    requirements.add(new ProductionStockAllocationService.AutomaticRequirement(
                            item.getMaterialTypeId(),
                            item.getMaterialName(),
                            normalizeQuantity(perBasis.multiply(scale)),
                            item.getUnit(),
                            "PACKAGING"));
                }
            }
        }

        if (bomSeasoningItemRepository == null) {
            return requirements;
        }
        List<BomSeasoningItem> seasoningItems =
                resolveProcessSeasoningItems(
                        factoryId,
                        planId,
                        recipe,
                        pinnedFamily,
                        plan.getSelectedWorkflowId() != null,
                        req);
        if (!seasoningItems.isEmpty()) {
            BigDecimal inputKg = reportingMassToKg(req.getInputQuantity(), requestInputUnit(req));
            List<BigDecimal> potRawKgs = req.getPotRawKgs() == null || req.getPotRawKgs().isEmpty()
                    ? (inputKg.signum() > 0 ? List.of(inputKg) : List.of())
                    : req.getPotRawKgs();
            BigDecimal totalPotKg = potRawKgs.stream().filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int potCount = potRawKgs.size();
            BigDecimal equalPotKg = potCount == 0 ? BigDecimal.ZERO
                    : totalPotKg.divide(BigDecimal.valueOf(potCount), 12, RoundingMode.HALF_UP);
            for (BomSeasoningItem item : seasoningItems) {
                if (item.getMaterialTypeId() == null || item.getDosagePerKgG() == null
                        || item.getDosagePerKgG().signum() <= 0) {
                    throw new BusinessException(409, "调料配置缺少物料或用量: " + item.getName())
                            .withCode("SEASONING_REQUIREMENT_INVALID")
                            .withSeverity("BLOCKING");
                }
                if (BomSeasoningItem.SECTION_COOKING.equals(item.getSection())
                        && !Boolean.TRUE.equals(item.getCountInSeasoning())) {
                    continue;
                }
                BigDecimal effectiveRawKg = BomSeasoningItem.SECTION_INJECTION.equals(item.getSection())
                        ? inputKg : totalPotKg;
                if (BomSeasoningItem.SECTION_COOKING.equals(item.getSection())
                        && item.getSubsequentPotRatio() != null && potCount > 0) {
                    BigDecimal factor = BigDecimal.ONE.add(
                            BigDecimal.valueOf(potCount - 1L).multiply(item.getSubsequentPotRatio()));
                    effectiveRawKg = equalPotKg.multiply(factor);
                }
                BigDecimal quantityKg = effectiveRawKg
                        .multiply(item.getDosagePerKgG())
                        .divide(new BigDecimal("1000"), 12, RoundingMode.HALF_UP);
                if (quantityKg.signum() > 0) {
                    requirements.add(new ProductionStockAllocationService.AutomaticRequirement(
                            item.getMaterialTypeId(),
                            item.getName(),
                            normalizeQuantity(quantityKg),
                            "kg",
                            "SEASONING"));
                }
            }
        }
        return requirements;
    }

    private BigDecimal normalizeQuantity(BigDecimal quantity) {
        BigDecimal normalized = quantity.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }

    private List<BomSeasoningItem> resolveProcessSeasoningItems(
            String factoryId,
            String planId,
            BomRecipe pinnedRecipe,
            List<BomRecipe> pinnedFamily,
            boolean pinnedWorkflow,
            ProcessSheetRowRequest req) {
        String workflowProcessNodeId = null;
        String workProcessId = null;
        if (pinnedWorkflow && workflowClerkSheetService != null && req.getProcessOrder() != null) {
            WorkflowClerkSheetConfigDTO config =
                    workflowClerkSheetService.getWorkflowSheetConfig(factoryId, planId);
            WorkflowClerkSheetConfigDTO.ProcessDescriptor descriptor =
                    config == null || config.getProcesses() == null ? null
                            : config.getProcesses().stream()
                            .filter(process -> Objects.equals(
                                    process.getProcessOrder(), req.getProcessOrder()))
                            .findFirst().orElse(null);
            workflowProcessNodeId = descriptor != null ? descriptor.getWorkflowNodeId() : null;
            if (workflowProcessNodeId == null || workflowProcessNodeId.isBlank()) {
                throw new BusinessException(409, "计划固定的 Workflow 无法解析当前工序调料")
                        .withCode("SEASONING_PROCESS_NODE_UNRESOLVED")
                        .withHint("请刷新报工页；若工序版本已变化，请重新创建生产计划")
                        .withSeverity("BLOCKING");
            }
        } else {
            WorkProcess process = resolveWorkProcess(
                    factoryId, req.getProductTypeId(), req.getProcessOrder());
            workProcessId = process == null ? null : process.getId();
        }

        Set<String> reportedProductIds = new LinkedHashSet<>(
                reportedFinishedProductIds(req));
        if (reportedProductIds.isEmpty()) {
            reportedProductIds.add(pinnedRecipe.getProductTypeId());
        }
        Set<String> reportedTerminalNodeIds = pinnedFamily.stream()
                .filter(member -> reportedProductIds.contains(member.getProductTypeId()))
                .map(BomRecipe::getTargetTerminalNodeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        LinkedHashMap<Long, BomSeasoningItem> applicable = new LinkedHashMap<>();
        for (BomRecipe member : pinnedFamily) {
            List<BomSeasoningItem> memberItems;
            if (workflowProcessNodeId != null) {
                memberItems = bomSeasoningItemRepository
                        .findByRecipeIdAndWorkflowProcessNodeIdOrderBySeqAsc(
                                member.getId(), workflowProcessNodeId);
            } else if (workProcessId != null) {
                memberItems = bomSeasoningItemRepository
                        .findByRecipeIdAndWorkProcessIdOrderBySeqAsc(member.getId(), workProcessId);
            } else {
                memberItems = List.of();
            }
            for (BomSeasoningItem item : memberItems) {
                if (seasoningAppliesToReportedOutputs(
                        item, member, reportedProductIds, reportedTerminalNodeIds)) {
                    applicable.putIfAbsent(item.getId(), item);
                }
            }
        }
        return List.copyOf(applicable.values());
    }

    private boolean seasoningAppliesToReportedOutputs(
            BomSeasoningItem item,
            BomRecipe owner,
            Set<String> reportedProductIds,
            Set<String> reportedTerminalNodeIds) {
        String scopeKey = item.getCostScopeKey();
        if (scopeKey != null && !scopeKey.isBlank()) {
            return java.util.Arrays.stream(scopeKey.split(","))
                    .map(String::trim)
                    .anyMatch(reportedTerminalNodeIds::contains);
        }
        if ("OUTPUT_GROUP".equals(item.getCostScope())) return false;
        if ("OUTPUT_EXCLUSIVE".equals(item.getCostScope())) {
            return reportedProductIds.contains(owner.getProductTypeId());
        }
        return true;
    }

    private Set<String> reportedFinishedProductIds(ProcessSheetRowRequest req) {
        if (req.getOutputs() != null && !req.getOutputs().isEmpty()) {
            return req.getOutputs().stream()
                    .filter(Objects::nonNull)
                    .filter(ProcessSheetRowRequest.OutputLine::isFinished)
                    .filter(output -> output.getQuantity() != null
                            && output.getQuantity().signum() > 0)
                    .map(ProcessSheetRowRequest.OutputLine::getProductTypeId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return req.isFinished() && req.getProductTypeId() != null
                ? Set.of(req.getProductTypeId()) : Set.of();
    }

    private List<FinishedBomOutput> finishedBomOutputs(ProcessSheetRowRequest req) {
        if (req.getOutputs() != null && !req.getOutputs().isEmpty()) {
            return req.getOutputs().stream()
                    .filter(Objects::nonNull)
                    .filter(ProcessSheetRowRequest.OutputLine::isFinished)
                    .filter(output -> output.getQuantity() != null
                            && output.getQuantity().signum() > 0)
                    .map(output -> new FinishedBomOutput(
                            output.getProductTypeId(),
                            output.getQuantity(),
                            firstNonBlank(output.getUnit(), req.getOutputUnit(), req.getUnit())))
                    .toList();
        }
        if (!req.isFinished() || req.getOutputQuantity() == null
                || req.getOutputQuantity().signum() <= 0) {
            return List.of();
        }
        return List.of(new FinishedBomOutput(
                req.getProductTypeId(),
                req.getOutputQuantity(),
                firstNonBlank(req.getOutputUnit(), req.getUnit())));
    }

    private List<BomRecipe> resolvePinnedBomFamily(
            ProductionPlan plan, Map<String, String> recipeIdsByProduct) {
        List<BomRecipe> family = bomRecipeRepository
                .findByFactoryIdAndBomFamilyIdOrderByProductTypeIdAscVersionDesc(
                        plan.getFactoryId(), plan.getSelectedBomFamilyId()).stream()
                .filter(member -> Objects.equals(
                        plan.getSelectedWorkflowRevisionId(), member.getWorkflowRevisionId()))
                .filter(member -> Objects.equals(
                        recipeIdsByProduct.get(member.getProductTypeId()), member.getId()))
                .filter(member -> Objects.equals(
                        plan.getSelectedBomVersionsByProduct().get(member.getProductTypeId()),
                        member.getVersion()))
                .toList();
        if (family.size() != recipeIdsByProduct.size()) {
            throw new BusinessException(409, "Pinned BOM family no longer matches the plan authority snapshot")
                    .withCode("PINNED_BOM_FAMILY_INVALID")
                    .withSeverity("BLOCKING");
        }
        return family;
    }

    private List<BomRecipe> resolvePinnedBomFamily(BomRecipe pinnedRecipe) {
        if (pinnedRecipe.getBomFamilyId() == null
                || pinnedRecipe.getBomFamilyId().isBlank()) {
            return List.of(pinnedRecipe);
        }
        List<BomRecipe> family = bomRecipeRepository
                .findByFactoryIdAndBomFamilyIdOrderByProductTypeIdAscVersionDesc(
                        pinnedRecipe.getFactoryId(), pinnedRecipe.getBomFamilyId()).stream()
                .filter(member -> Objects.equals(
                        pinnedRecipe.getWorkflowRevisionId(), member.getWorkflowRevisionId()))
                .toList();
        return family.isEmpty() ? List.of(pinnedRecipe) : family;
    }

    private BomRecipe resolvePinnedOutputRecipe(
            List<BomRecipe> family, String productTypeId) {
        List<BomRecipe> matches = family.stream()
                .filter(member -> Objects.equals(productTypeId, member.getProductTypeId()))
                .toList();
        if (matches.size() != 1) {
            throw new BusinessException(409,
                    matches.isEmpty()
                            ? "本次成品产出没有对应的计划固定 BOM"
                            : "本次成品产出匹配到多个计划固定 BOM")
                    .withCode(matches.isEmpty()
                            ? "PINNED_BOM_OUTPUT_RECIPE_MISSING"
                            : "PINNED_BOM_OUTPUT_RECIPE_AMBIGUOUS")
                    .withHint("SKU: " + productTypeId + "。请确保同一 Workflow 的每个成品 SKU 都有且只有一个 BOM")
                    .withSeverity("BLOCKING");
        }
        return matches.getFirst();
    }

    private record FinishedBomOutput(
            String productTypeId,
            BigDecimal quantity,
            String unit) { }

    private BigDecimal reportingMassToKg(BigDecimal quantity, String unit) {
        if (quantity == null || quantity.signum() <= 0) return BigDecimal.ZERO;
        String canonical = canonicalBomUnit(unit);
        if (canonical == null) return BigDecimal.ZERO;
        return switch (canonical) {
            case "kg" -> quantity;
            case "g" -> quantity.movePointLeft(3);
            default -> BigDecimal.ZERO;
        };
    }

    private String canonicalBomUnit(String unit) {
        if (unit == null || unit.isBlank()) return null;
        return switch (unit.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "公斤", "千克", "kg" -> "kg";
            case "克", "g" -> "g";
            case "盒", "box" -> "box";
            case "箱", "case" -> "case";
            case "片", "slice", "piece", "pcs", "个" -> "slice";
            default -> unit.trim().toLowerCase(java.util.Locale.ROOT);
        };
    }

    private void assertAuthenticatedPlan(String factoryId, String planId, Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "未登录，无法保存工序行");
        }
        if (productionPlanRepository.findByIdAndFactoryId(planId, factoryId).isEmpty()) {
            throw new BusinessException(403, "无权访问该计划");
        }
    }

    private List<ProcessSheetRow> resolveSubmittedRows(
            String factoryId,
            String planId,
            ProcessSheetRowRequest req,
            ProcessSheetRowResult result) {
        LinkedHashMap<Long, ProcessSheetRow> rows = new LinkedHashMap<>();
        if (result.getOutputs() != null) {
            for (ProcessSheetRowResult.OutputResult output : result.getOutputs()) {
                if (output.getClientRowId() == null) continue;
                rowRepo.findByFactoryIdAndPlanIdAndClientRowId(factoryId, planId, output.getClientRowId())
                        .forEach(row -> rows.put(row.getId(), row));
            }
        }
        if (rows.isEmpty()) {
            rowRepo.findByFactoryIdAndPlanIdAndClientRowId(factoryId, planId, req.getClientRowId())
                    .forEach(row -> rows.put(row.getId(), row));
        }
        return List.copyOf(rows.values());
    }

    private static void retainActualPositiveSelections(ProcessSheetRowRequest req) {
        if (req.getOutputs() != null) {
            req.setOutputs(req.getOutputs().stream()
                    .filter(Objects::nonNull)
                    .filter(output -> output.getQuantity() != null
                            && output.getQuantity().signum() > 0)
                    .toList());
        }
        if (req.getMaterialInputTotals() != null) {
            req.setMaterialInputTotals(req.getMaterialInputTotals().stream()
                    .filter(Objects::nonNull)
                    .filter(input -> input.getQuantity() != null
                            && input.getQuantity().signum() > 0)
                    .toList());
        }
        if (req.getRawMaterialInputs() != null) {
            req.setRawMaterialInputs(req.getRawMaterialInputs().stream()
                    .filter(Objects::nonNull)
                    .filter(input -> input.getQuantity() != null
                            && input.getQuantity().signum() > 0)
                    .toList());
        }
        if (req.getUpstreamSources() != null) {
            req.setUpstreamSources(req.getUpstreamSources().stream()
                    .filter(Objects::nonNull)
                    .filter(input -> input.getFeedQuantityKg() != null
                            && input.getFeedQuantityKg().signum() > 0)
                    .toList());
        }
    }

    private static void assertNoNegativeWorkflowInputs(ProcessSheetRowRequest req) {
        boolean negative = req.getMaterialInputTotals() != null
                && req.getMaterialInputTotals().stream().filter(Objects::nonNull)
                        .anyMatch(input -> input.getQuantity() != null
                                && input.getQuantity().signum() < 0);
        negative |= req.getRawMaterialInputs() != null
                && req.getRawMaterialInputs().stream().filter(Objects::nonNull)
                        .anyMatch(input -> input.getQuantity() != null
                                && input.getQuantity().signum() < 0);
        negative |= req.getUpstreamSources() != null
                && req.getUpstreamSources().stream().filter(Objects::nonNull)
                        .anyMatch(input -> input.getFeedQuantityKg() != null
                                && input.getFeedQuantityKg().signum() < 0);
        if (negative) {
            throw new BusinessException(400, "Workflow 投入端口数量不能为负数")
                    .withCode("PROCESS_SHEET_INPUT_QUANTITY_INVALID")
                    .withSeverity("BLOCKING");
        }
    }

    /**
     * 正式报工不能把已选择的上游来源以 0/null 投入静默过滤掉，否则会形成“有产出、无消耗”的幽灵成品。
     * 草稿仍允许未填完整；这里只在 submitRow 的正产出门禁之后执行，并早于任何库存或持久化动作。
     */
    private static void assertPositiveDeclaredUpstreamFeeds(ProcessSheetRowRequest req) {
        if (req.getUpstreamSources() == null || req.getUpstreamSources().isEmpty()) {
            return;
        }
        boolean invalid = req.getUpstreamSources().stream()
                .filter(Objects::nonNull)
                .anyMatch(source -> source.getSourceBatchNumber() == null
                        || source.getSourceBatchNumber().isBlank()
                        || source.getFeedQuantityKg() == null
                        || source.getFeedQuantityKg().signum() <= 0);
        if (invalid) {
            throw new BusinessException(400, "正式报工的每个上游来源都必须填写大于 0 的实际投入量")
                    .withCode("PROCESS_SHEET_UPSTREAM_INPUT_REQUIRED")
                    .withHint("请刷新上游库存并重新选择来源批次；投入量为 0 时只能保存草稿")
                    .withSeverity("BLOCKING")
                    .withHintTarget("实际投入");
        }
    }

    @Override
    @Transactional
    public ProcessSheetRowResult saveRow(String factoryId, String planId,
                                         ProcessSheetRowRequest req, Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "未登录，无法保存工序行 (userId 为 null)");
        }

        // 1. 跨租户守卫: plan 必须归属本 factory (🔒)
        if (productionPlanRepository.findByIdAndFactoryId(planId, factoryId).isEmpty()) {
            throw new BusinessException(403, "无权访问该计划");
        }

        // 2B.2 多产出分支 (前置于单产出校验/upsert): 多产出用自己的逐产出校验 + 分组 upsert/删除,
        //   顶层 finished/unit/outputQuantity 只是 @NotNull 占位, 不能拿去跑单产出 B3/单位归一化 (否则误 409)。
        if (req.getOutputs() != null && !req.getOutputs().isEmpty()) {
            return saveMultiOutputRow(factoryId, planId, req, userId);
        }

        // 1.5 G2: 自定义字段 key 白名单校验 (WorkProcess.customFieldSchema 配置驱动)。
        //     覆盖 create + re-save 两条路径 (resaveRow 由本方法下方委托调用, 早于任何写入)。
        normalizeConfiguredUnits(factoryId, planId, req);
        validateCustomFields(factoryId, req);

        // 1.6 2B B3: workflow 批次行防呆校验 (产出类型/单位对齐 workflow 端口)。
        //     与 validateCustomFields 同置于 upsert 查重之前 → 覆盖 create + re-save 两条路径, 早于任何写入。
        //     legacy 计划 (无 workflow 批次) → getWorkflowSheetConfig 返回 null → 直接放行, 现有路径不变。
        WorkflowClerkSheetConfigDTO workflowConfig =
                validateWorkflowRowIfApplicable(factoryId, planId, req);

        // Positive intermediate outputs must own a stable identity before source validation.
        // This keeps malformed output identity from being reported as an unrelated source conflict
        // and prevents stock-fed rows from reaching an SFI anchor with a blank product identity.
        String outputMaterialIdentity = null;
        if (!req.isFinished() && req.getOutputQuantity() != null && req.getOutputQuantity().signum() > 0) {
            outputMaterialIdentity = resolveOutputMaterialIdentity(req);
        }

        // 2. upsert 键查重: 已存在 → 委托 re-save (Task 1.6 stub)
        Optional<ProcessSheetRow> existing = rowRepo
                .findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
                        factoryId, planId, req.getProcessCode(), req.getClientRowId());
        if (existing.isPresent()) {
            // TODO(Task 1.6): re-save = update-in-place 保 id (校验无下游消耗 + 重写边/报工)。
            return resaveRow(factoryId, planId, req, userId, existing.get(), workflowConfig);
        }

        List<String> warnings = new ArrayList<>();

        assertFinishedGoodsSourceAllowed(factoryId, req);
        assertExternalFeedUnitSupported(req);

        // 3. 解析上游消耗边 (factory-scoped, 🔒)
        List<ResolvedEdge> edges = resolveEdges(factoryId, planId, req);

        // 6. outputQuantity gate: <=0 → 存 DRAFT 行, 不物化 WIP 批
        if (req.getOutputQuantity() == null || req.getOutputQuantity().signum() <= 0) {
            persistRow(factoryId, planId, req, null, null, "DRAFT");
            logChange(factoryId, planId, req, "CREATE", null, req, userId);
            // (req, batchId, batchNumber, yieldRate, rowTotalCost, unitPrice, updated, materialized, warnings)
            return buildResult(req, null, null, null, null, null, false, false, warnings);
        }

        // 6.5 option F: 纯半成品(SFI)喂的非成品中间道 —— 不物化 raw-lineage WIP MaterialBatch
        //   (SFI 只有 product-type 维度, 无 raw_material_types FK 可派生 material_type_id)。产出直接入
        //   半成品库(SFI), 停留在 product 维度; 输入 SFI 的扣减在小结走 consumeClerkSemiStrict
        //   (SFI 边已在 resolveEdges 跳过, 此处 edges 为空)。行以 batchNumber=SFI 锚 + rowStatus=SAVED_SFI
        //   持久化, 供小结 ③ SFI IN 定位过账 (见 InterimSettleServiceImpl)。
        //   成品道 (气调) 的纯 SFI 场景走原路径 (materializeBatch finished=true 不建 WIP → FG), 不入此分支。
        if (!req.isFinished() && isPureStockFed(req)) {
            // #1252 中段起步: 产出在<b>保存时</b>即入常驻半成品库 (SFI IN), 使下游道小结前即可选到本道产出
            //   (原实现只在小结入库 → 下游保存期 SFI_NOT_FOUND → 后段链阻断)。输入 SFI/FG 的扣减仍延迟到小结
            //   (consumeClerkSemiStrict / consumeForFeedStrict, 不变); 本道产出的 SFI IN 由此 postSfiOutput 承担,
            //   小结不再重复入库 (见 InterimSettleServiceImpl SFI IN 循环跳过 batchId==null 的 SAVED_SFI 行)。
            String anchor = postSfiOutput(factoryId, planId, req, warnings);
            persistRow(factoryId, planId, req, null, anchor, ProcessSheetRow.STATUS_SAVED_SFI);
            logChange(factoryId, planId, req, "CREATE", null, req, userId);
            stampWorkflowTaskIfApplicable(factoryId, planId, req, workflowConfig);
            // materialized=true: 产出已入 SFI 库 (下游可选); yieldRate 可算; 成本诚实 (投入未知 → null)。
            return buildResult(req, null, anchor, yieldRate(req), null, null, false, true, warnings);
        }

        // 4. WIP identity 来自本道产出产品；edges 仅保留完整投入 provenance。
        if (outputMaterialIdentity == null) {
            outputMaterialIdentity = resolveOutputMaterialIdentity(req);
        }

        // 5. 映射单个 StepEntry
        StepEntry step = buildStepEntry(factoryId, req);

        // 7. 物化
        MaterializeContext ctx = new MaterializeContext(
                factoryId,
                req.isFinished() ? planId : null,
                req.getProductTypeId(),
                req.getBatchNumber(),
                req.isFinished(),
                clerkService.resolveLaborRate(factoryId, warnings),
                clerkService.resolveWarehouseId(factoryId, WarehouseCodes.WH_WKS, warnings),
                outputMaterialIdentity,
                userId);

        MaterializedBatch mat = materializeSheetBatch(
                ctx, List.of(step), edges, warnings, workflowConfig);

        // 8. 写 process_sheet_rows (try/catch UK 冲突 → 409; 完整并发测在 Task 1.7)
        persistRow(factoryId, planId, req, mat.getProductionBatchId(), mat.getBatchNumber(), "SAVED");
        logChange(factoryId, planId, req, "CREATE", null, req, userId);
        stampWorkflowTaskIfApplicable(factoryId, planId, req, workflowConfig);

        // 9. 组装结果
        return buildResult(req, mat.getProductionBatchId(), mat.getBatchNumber(),
                yieldRate(req), mat.getRowTotalCost(),
                unitPrice(mat.getRowTotalCost(), req.getOutputQuantity()), false, true, warnings);
    }

    // ─────────────────────────────────────────────────────────────
    // 2B B3: workflow 批次行防呆校验 (clerk-path 报工联通)
    // ─────────────────────────────────────────────────────────────

    /**
     * 若该计划关联一个 workflow 批次, 用 workflow 端口投影校验本行产出对齐 (防呆, 禁止降级):
     * <ul>
     *   <li>产出类型 半成品/成品 必须与 workflow 输出端口一致 (finished 标志)</li>
     *   <li>产出单位 必须与端口单位一致 (两侧都有值时)</li>
     * </ul>
     * legacy 计划 (service 返回 null) / 未注入 (单测) / DRAFT 行 (无产出) → 放行, 现有路径不变。
     * 产出 SKU (productType) 的严格对齐留待 FE 产出 SKU 回填经 E2E 确认后再收紧, 避免误阻断 (MVP)。
     */
    private WorkflowClerkSheetConfigDTO validateWorkflowRowIfApplicable(
            String factoryId, String planId, ProcessSheetRowRequest req) {
        if (workflowClerkSheetService == null) {
            return null; // 单测/未注入
        }
        // DRAFT 行 (产出<=0) 尚无产出, 不校验产出类型/单位
        if (req.getOutputQuantity() == null || req.getOutputQuantity().signum() <= 0) {
            return null;
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO config;
        try {
            config = workflowClerkSheetService.getWorkflowSheetConfig(factoryId, planId);
        } catch (BusinessException be) {
            throw be; // e.g. WORKFLOW_MULTI_OUTPUT_UNSUPPORTED — 必须 surface, 不静默降级
        } catch (Exception e) {
            log.error("Workflow 产出校验配置读取失败: factory={}, plan={}", factoryId, planId, e);
            throw new BusinessException(409, "Workflow 运行时配置读取失败，不能校验报工产出")
                    .withCode("PROCESS_SHEET_WORKFLOW_CONFIG_UNAVAILABLE")
                    .withHint("请刷新后重试；若仍失败，请重新物化该生产批次的 Workflow")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        if (config == null) {
            return null; // 明确 legacy 计划 — 不校验
        }
        if (config.getProcesses() == null || config.getProcesses().isEmpty()) {
            throw new BusinessException(409, "Workflow 运行时没有可报工工序")
                    .withCode("PROCESS_SHEET_WORKFLOW_PROCESSES_MISSING")
                    .withHint("请重新物化该生产批次的 Workflow")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.ProcessDescriptor desc =
                config.getProcesses().stream()
                        .filter(p -> p.getProcessOrder() != null
                                && p.getProcessOrder().equals(req.getProcessOrder()))
                        .findFirst()
                        .orElse(null);
        if (desc == null) {
            throw new BusinessException(409, "请求工序不在该批次锁定的 Workflow 中")
                    .withCode("PROCESS_SHEET_WORKFLOW_PROCESS_NOT_FOUND")
                    .withHint("请刷新逐道录入页面，按 Workflow 中的工序报工")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        if (desc.getOutput() == null) {
            throw new BusinessException(409, "Workflow 工序缺少产出端口，不能报工")
                    .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_MISSING")
                    .withHint("请修复并重新发布 Workflow 后创建新批次")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor out =
                resolveWorkflowOutputPort(desc, req.getWorkflowPortId());
        req.setWorkflowPortId(out.getWorkflowPortId());
        if (req.getMaterialNodeId() == null) {
            req.setMaterialNodeId(out.getMaterialNodeId());
        }
        String processName = desc.getProcessName() != null ? desc.getProcessName() : "";
        String outputName = out.getMaterialName() != null ? out.getMaterialName() : "";

        boolean expectFinished = Boolean.TRUE.equals(out.getFinished());
        if (req.isFinished() != expectFinished) {
            throw new BusinessException(409, "本工序【" + processName + "】应产出"
                    + (expectFinished ? "成品" : "半成品") + "「" + outputName + "」，当前产出类型不符")
                    .withCode("WORKFLOW_ROW_OUTPUT_KIND_MISMATCH")
                    .withHint(expectFinished
                            ? "请在成品道录入产出，或回 Workflow 配置核对本道产出类型"
                            : "本道产出应为半成品，请勿按成品录入");
        }

        if (out.getSkuId() == null || out.getSkuId().isBlank()) {
            throw new BusinessException(409, "Workflow 产出端口缺少产品绑定，不能报工")
                    .withCode("WORKFLOW_ROW_OUTPUT_SKU_MISSING")
                    .withHint("请修复并重新发布 Workflow 后创建新批次")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        if (!out.getSkuId().equals(req.getProductTypeId())) {
            throw new BusinessException(409, "本工序【" + processName + "】产出产品与 Workflow 端口不一致")
                    .withCode("WORKFLOW_ROW_OUTPUT_SKU_MISMATCH")
                    .withHint("请刷新逐道录入页面后重新填写")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }

        String expectUnit = requireWorkflowPortUnit(out.getUnit(), "产出");
        String actualUnit = req.getUnit();
        if (actualUnit != null && !actualUnit.isBlank()
                && !expectUnit.equalsIgnoreCase(actualUnit.trim())) {
            throw new BusinessException(409, "本工序【" + processName + "】产出单位应为「"
                    + expectUnit + "」，当前为「" + actualUnit + "」")
                    .withCode("WORKFLOW_ROW_OUTPUT_UNIT_MISMATCH")
                    .withHint("请按 Workflow 配置的单位录入产出数量");
        }
        return config;
    }

    private static List<WorkflowClerkSheetConfigDTO.PortDescriptor> workflowOutputs(
            WorkflowClerkSheetConfigDTO.ProcessDescriptor descriptor) {
        if (descriptor.getOutputs() != null && !descriptor.getOutputs().isEmpty()) {
            return descriptor.getOutputs();
        }
        return descriptor.getOutput() == null ? List.of() : List.of(descriptor.getOutput());
    }

    private static WorkflowClerkSheetConfigDTO.PortDescriptor resolveWorkflowOutputPort(
            WorkflowClerkSheetConfigDTO.ProcessDescriptor descriptor,
            String workflowPortId) {
        List<WorkflowClerkSheetConfigDTO.PortDescriptor> outputs = workflowOutputs(descriptor);
        if (outputs.isEmpty()) {
            throw new BusinessException(409, "Workflow 工序缺少产出端口，不能报工")
                    .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_MISSING")
                    .withSeverity("BLOCKING");
        }
        if (workflowPortId == null || workflowPortId.isBlank()) {
            if (outputs.size() == 1) {
                return outputs.getFirst();
            }
            throw new BusinessException(409, "多产出工序必须使用逐产出结构报工")
                    .withCode("PROCESS_SHEET_WORKFLOW_MULTI_OUTPUT_REQUIRED")
                    .withSeverity("BLOCKING");
        }
        return outputs.stream()
                .filter(port -> workflowPortId.equals(port.getWorkflowPortId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(409,
                        "请求包含不属于该 Workflow 工序的产出端口")
                        .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_NOT_FOUND")
                        .withSeverity("BLOCKING"));
    }

    private static void validatePortSelections(
            List<WorkflowClerkSheetConfigDTO.PortDescriptor> ports,
            Set<String> selectedPortIds,
            String direction) {
        Map<String, List<WorkflowClerkSheetConfigDTO.PortDescriptor>> groups = new LinkedHashMap<>();
        for (WorkflowClerkSheetConfigDTO.PortDescriptor port : ports) {
            String groupId = port.getSelectionGroupId();
            if (groupId == null || groupId.isBlank()) {
                if (Boolean.TRUE.equals(port.getRequired())
                        && !selectedPortIds.contains(port.getWorkflowPortId())) {
                    throw missingRequiredPort(port, direction);
                }
                continue;
            }
            groups.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(port);
        }

        for (Map.Entry<String, List<WorkflowClerkSheetConfigDTO.PortDescriptor>> entry
                : groups.entrySet()) {
            List<WorkflowClerkSheetConfigDTO.PortDescriptor> groupPorts = entry.getValue();
            WorkflowClerkSheetConfigDTO.PortDescriptor snapshot = groupPorts.getFirst();
            assertConsistentSelectionGroupSnapshot(entry.getKey(), snapshot, groupPorts);
            long selectedCount = groupPorts.stream()
                    .filter(port -> selectedPortIds.contains(port.getWorkflowPortId()))
                    .count();
            int portCount = groupPorts.size();
            boolean valid = switch (snapshot.getSelectionGroupMode()) {
                case "ALL_REQUIRED" -> selectedCount == portCount;
                case "EXACTLY_ONE" -> selectedCount == 1;
                case "AT_LEAST_ONE" -> selectedCount >= 1 && selectedCount <= portCount;
                case "OPTIONAL" -> selectedCount <= portCount;
                default -> false;
            };
            if (!valid) {
                String label = snapshot.getSelectionGroupLabel();
                throw new BusinessException(409, "Workflow 端口选择组「" + label
                        + "」不满足 " + snapshot.getSelectionGroupMode() + " 规则")
                        .withCode("PROCESS_SHEET_WORKFLOW_SELECTION_GROUP_VIOLATION")
                        .withHint("请按端口选择组规则重新选择实际报工端口")
                        .withSeverity("BLOCKING");
            }
        }
    }

    private void validateWorkflowSubmissionSelections(
            String factoryId, String planId, ProcessSheetRowRequest req) {
        if (workflowClerkSheetService == null || req.getProcessOrder() == null) {
            return;
        }
        WorkflowClerkSheetConfigDTO config;
        try {
            config = workflowClerkSheetService.getWorkflowSheetConfig(factoryId, planId);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Workflow 正式报工选择组配置读取失败: factory={}, plan={}", factoryId, planId, e);
            throw new BusinessException(409, "Workflow 运行时配置读取失败，不能校验正式报工端口选择")
                    .withCode("PROCESS_SHEET_WORKFLOW_CONFIG_UNAVAILABLE")
                    .withSeverity("BLOCKING");
        }
        if (config == null) {
            return;
        }
        WorkflowClerkSheetConfigDTO.ProcessDescriptor descriptor = config.getProcesses() == null
                ? null
                : config.getProcesses().stream()
                        .filter(process -> req.getProcessOrder().equals(process.getProcessOrder()))
                        .findFirst()
                        .orElse(null);
        if (descriptor == null) {
            throw new BusinessException(409, "请求工序不在该批次锁定的 Workflow 中")
                    .withCode("PROCESS_SHEET_WORKFLOW_PROCESS_NOT_FOUND")
                    .withSeverity("BLOCKING");
        }

        applyWorkflowInputPorts(factoryId, descriptor, req, true);

        List<WorkflowClerkSheetConfigDTO.PortDescriptor> outputs = workflowOutputs(descriptor);
        Map<String, WorkflowClerkSheetConfigDTO.PortDescriptor> outputsById = new LinkedHashMap<>();
        for (WorkflowClerkSheetConfigDTO.PortDescriptor output : outputs) {
            if (output.getWorkflowPortId() == null || output.getWorkflowPortId().isBlank()) {
                throw new BusinessException(409, "Workflow 产出端口缺少稳定标识")
                        .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_ID_MISSING")
                        .withSeverity("BLOCKING");
            }
            if (outputsById.put(output.getWorkflowPortId(), output) != null) {
                throw new BusinessException(409, "Workflow 存在重复的产出端口标识")
                        .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_DUPLICATE")
                        .withSeverity("BLOCKING");
            }
        }

        Set<String> selectedOutputs = new LinkedHashSet<>();
        if (req.getOutputs() != null && !req.getOutputs().isEmpty()) {
            for (ProcessSheetRowRequest.OutputLine output : req.getOutputs()) {
                if (output == null || output.getQuantity() == null
                        || output.getQuantity().signum() <= 0) {
                    continue;
                }
                String portId = output.getWorkflowPortId();
                if (portId == null || portId.isBlank()) {
                    throw new BusinessException(409, "多产出报工必须指定 Workflow 产出端口")
                            .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_REQUIRED")
                            .withSeverity("BLOCKING");
                }
                if (!outputsById.containsKey(portId)) {
                    throw new BusinessException(409, "请求包含不属于该 Workflow 工序的产出端口")
                            .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_NOT_FOUND")
                            .withSeverity("BLOCKING");
                }
                if (!selectedOutputs.add(portId)) {
                    throw new BusinessException(409, "同一 Workflow 产出端口不能重复报工")
                            .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_REPEATED")
                            .withSeverity("BLOCKING");
                }
            }
        } else {
            WorkflowClerkSheetConfigDTO.PortDescriptor output =
                    resolveWorkflowOutputPort(descriptor, req.getWorkflowPortId());
            selectedOutputs.add(output.getWorkflowPortId());
        }
        validatePortSelections(outputs, selectedOutputs, "OUTPUT");
    }

    private static void assertConsistentSelectionGroupSnapshot(
            String groupId,
            WorkflowClerkSheetConfigDTO.PortDescriptor snapshot,
            List<WorkflowClerkSheetConfigDTO.PortDescriptor> groupPorts) {
        int portCount = groupPorts.size();
        boolean invalid = snapshot.getSelectionGroupLabel() == null
                || snapshot.getSelectionGroupLabel().isBlank()
                || snapshot.getSelectionGroupMode() == null
                || snapshot.getSelectionGroupMinSelections() == null
                || snapshot.getSelectionGroupMaxSelections() == null;
        for (WorkflowClerkSheetConfigDTO.PortDescriptor port : groupPorts) {
            invalid |= !Objects.equals(snapshot.getSelectionGroupLabel(), port.getSelectionGroupLabel())
                    || !Objects.equals(snapshot.getSelectionGroupMode(), port.getSelectionGroupMode())
                    || !Objects.equals(snapshot.getSelectionGroupMinSelections(),
                            port.getSelectionGroupMinSelections())
                    || !Objects.equals(snapshot.getSelectionGroupMaxSelections(),
                            port.getSelectionGroupMaxSelections());
        }
        if (invalid) {
            throw new BusinessException(409, "Workflow 端口选择组快照不完整或不一致: " + groupId)
                    .withCode("PROCESS_SHEET_WORKFLOW_SELECTION_GROUP_SNAPSHOT_INVALID")
                    .withSeverity("BLOCKING");
        }
        int min = snapshot.getSelectionGroupMinSelections();
        int max = snapshot.getSelectionGroupMaxSelections();
        boolean boundsValid = switch (snapshot.getSelectionGroupMode()) {
            case "ALL_REQUIRED" -> min == portCount && max == portCount;
            case "EXACTLY_ONE" -> min == 1 && max == 1;
            case "AT_LEAST_ONE" -> min == 1 && max == portCount;
            case "OPTIONAL" -> min == 0 && max == portCount;
            default -> false;
        };
        if (!boundsValid) {
            throw new BusinessException(409, "Workflow 端口选择组边界与模式不一致: " + groupId)
                    .withCode("PROCESS_SHEET_WORKFLOW_SELECTION_GROUP_SNAPSHOT_INVALID")
                    .withSeverity("BLOCKING");
        }
    }

    private static BusinessException missingRequiredPort(
            WorkflowClerkSheetConfigDTO.PortDescriptor port,
            String direction) {
        boolean input = "INPUT".equals(direction);
        return new BusinessException(409, "缺少 Workflow 必填" + (input ? "投入" : "产出") + "端口「"
                + (port.getMaterialName() != null
                        ? port.getMaterialName() : port.getWorkflowPortId()) + "」")
                .withCode(input
                        ? "PROCESS_SHEET_WORKFLOW_REQUIRED_INPUT_MISSING"
                        : "PROCESS_SHEET_WORKFLOW_REQUIRED_OUTPUT_MISSING")
                .withHint(input ? "请补全所有必填投入后再正式报工" : "请补全所有必填产出后再正式报工")
                .withSeverity("BLOCKING");
    }

    /**
     * 2B B3 (F3 回写): workflow 批次行产出后, 把对应 workflow 工序任务 (WorkProcessTask) 标记为 COMPLETED
     * 并回写实际产量, 使 workflow 运行时视图反映文员逐道录入的进度 (否则任务永远 PENDING)。
     *
     * <p><b>fail-soft</b>: 主事务只发布携带精确 Workflow 快照的事件；提交后再用独立事务回写任务进度。
     * 回写异常由监听器隔离，绝不反向回滚已保存的逐道行、物化批次或库存。
     * legacy 计划 / 未注入 / 找不到对应工序描述 → 静默跳过。仅在行确有产出后调用。
     */
    private void stampWorkflowTaskIfApplicable(
            String factoryId,
            String planId,
            ProcessSheetRowRequest req,
            WorkflowClerkSheetConfigDTO config) {
        if (applicationEventPublisher == null || config == null
                || config.getProcesses() == null || config.getWorkflowInstanceId() == null) {
            return;
        }
        WorkflowClerkSheetConfigDTO.ProcessDescriptor desc = config.getProcesses().stream()
                .filter(p -> p.getProcessOrder() != null
                        && p.getProcessOrder().equals(req.getProcessOrder()))
                .findFirst()
                .orElse(null);
        if (desc == null || desc.getWorkflowNodeId() == null) {
            return;
        }
        applicationEventPublisher.publishEvent(new WorkflowTaskProgressRequestedEvent(
                factoryId,
                planId,
                config.getWorkflowInstanceId(),
                desc.getWorkflowNodeId(),
                req.getProcessOrder(),
                req.getOutputQuantity()));
    }

    /**
     * Workflow plans already own one canonical runtime ProductionBatch. A finished process-sheet row must
     * materialize into that batch; creating another plan-linked batch makes the runtime snapshot ambiguous.
     */
    private MaterializedBatch materializeSheetBatch(
            MaterializeContext ctx,
            List<StepEntry> steps,
            List<ResolvedEdge> edges,
            List<String> warnings,
            WorkflowClerkSheetConfigDTO workflowConfig) {
        if (!ctx.isFinished() || workflowConfig == null || workflowConfig.getWorkflowBatchId() == null) {
            return clerkService.materializeBatch(ctx, steps, edges, warnings);
        }
        ProductionBatch runtimeBatch = productionBatchRepo
                .findByIdAndFactoryId(workflowConfig.getWorkflowBatchId(), ctx.getFactoryId())
                .orElseThrow(() -> new BusinessException(409, "Workflow 运行批次不存在，不能保存成品道报工")
                        .withCode("WORKFLOW_RUNTIME_BATCH_MISSING")
                        .withHint("请刷新后重试；若仍失败，请重新生成该计划的生产批次")
                        .withSeverity("BLOCKING")
                        .withHintTarget("Workflow"));
        if (!Objects.equals(runtimeBatch.getProductTypeId(), ctx.getProductTypeId())) {
            // A non-primary finished byproduct owns its own batch; only the Workflow primary product reuses runtime.
            return clerkService.materializeBatch(ctx, steps, edges, warnings);
        }
        if (!rowRepo.findByFactoryIdAndBatchId(ctx.getFactoryId(), runtimeBatch.getId()).isEmpty()) {
            throw new BusinessException(409, "Workflow 运行批次已关联其他逐道录入行")
                    .withCode("WORKFLOW_RUNTIME_BATCH_ALREADY_REPORTED")
                    .withHint("请刷新逐道录入页面，编辑已有成品道记录")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        ctx.setBatchNumber(runtimeBatch.getBatchNumber());
        return clerkService.rematerializeInPlace(
                ctx, runtimeBatch.getId(), null, steps, edges, warnings);
    }

    // ─────────────────────────────────────────────────────────────
    // 2B.2 多产出 (fan-out) 分解 —— 一次报工 N 个产出 → N 个单产出物料化, 共享投入按权重拆分, 落 N 行
    // ─────────────────────────────────────────────────────────────

    private record OneOutputOutcome(
            Long batchId,
            String batchNumber,
            BigDecimal rowTotalCost,
            String sfiAnchor,
            BigDecimal originalUnitCost) {}

    private record MultiOutputMaterialization(
            ProcessSheetRowRequest request,
            ProcessSheetRowRequest.OutputLine output,
            OneOutputOutcome outcome,
            ProcessSheetRowResult.OutputResult result) {}

    private record CostAllocationPlan(List<BigDecimal> ratios, String basis) {}

    private record CostBuckets(
            BigDecimal material,
            BigDecimal labor,
            BigDecimal equipment,
            BigDecimal other) {}

    /**
     * 多产出报工分解 (🔒 keystone)。input allocations (rawMaterialInputs/upstreamSources) + output lines
     * (req.outputs) 两组独立事实。首产出行 (base#0, carryInputs) 承载全部实际投入 → 一次全量扣减 (不拆分);
     * 其余产出行 (base#i) 仅按各自 output line 入库。血缘经同一份工序报工关联。工序不处理成本 (多产出行成本诚实 null)。
     *
     * <p>整组 (base#*) 生命周期: 单行删除级联整组 (防幻库存); 重存先删旧组再建 (见 deleteRow / 组前置删除)。
     */
    private ProcessSheetRowResult saveMultiOutputRow(String factoryId, String planId,
            ProcessSheetRowRequest req, Long userId) {
        List<String> warnings = new ArrayList<>();
        List<ProcessSheetRowRequest.OutputLine> outs = req.getOutputs().stream()
                .filter(Objects::nonNull)
                .filter(output -> output.getQuantity() != null
                        && output.getQuantity().signum() != 0)
                .toList();
        if (outs.isEmpty()) {
            throw new BusinessException(400, "多产出报工至少需要一个正数产出")
                    .withCode("PROCESS_SHEET_OUTPUT_REQUIRED");
        }
        req.setOutputs(outs);

        // 1. 逐产出校验 (禁止降级: 缺产品/量<=0 明确报错)
        for (ProcessSheetRowRequest.OutputLine o : outs) {
            if (o.getProductTypeId() == null || o.getProductTypeId().isBlank()) {
                throw new BusinessException(400, "多产出存在缺少产品的产出行")
                        .withCode("PROCESS_SHEET_OUTPUT_PRODUCT_REQUIRED")
                        .withHint("请为每个产出选择产品");
            }
            if (o.getQuantity() == null || o.getQuantity().signum() <= 0) {
                throw new BusinessException(400, "多产出的每条产出数量必须大于0")
                        .withCode("PROCESS_SHEET_OUTPUT_QUANTITY_INVALID")
                        .withHint("请填写各产出的产出数量");
            }
            validateOutputDetails(o);
        }

        // 1b. H3 loud-fail: 多产出报工必须有实际投入 (否则等于凭空产出 → 幻库存)。禁止降级。
        boolean hasRaw = req.getRawMaterialInputs() != null && !req.getRawMaterialInputs().isEmpty();
        boolean hasUpstream = req.getUpstreamSources() != null && !req.getUpstreamSources().isEmpty();
        if (!hasRaw && !hasUpstream) {
            throw new BusinessException(400, "多产出报工必须有实际投入 (原料或上游半成品), 不能凭空产出")
                    .withCode("PROCESS_SHEET_MULTI_OUTPUT_NO_INPUT")
                    .withHint("请先录入本道工序的投入 (领料或上游半成品)");
        }

        // 1c. 自定义字段校验 (单产出路径在前置做; 多产出这里补做一次, 同 process 同 schema)
        validateCustomFields(factoryId, req);

        // 2. 写入前先按 Workflow 快照逐产出对齐端口。WORKFLOW 模式任何异常都 fail-closed，
        // 不能删完旧组后才发现单位/端口错误。
        WorkflowClerkSheetConfigDTO workflowConfig =
                validateMultiOutputAgainstWorkflow(factoryId, planId, req);

        // 2b. 重存 (B2): base#* 组已存在 → 先删旧组 (级联反物化), 再建新组。整组无小结/无下游消耗才可删。
        deleteMultiOutputGroupIfPresent(factoryId, planId, req.getClientRowId(), userId);

        // 3. 分解 (per Steve — 不推断投入-产出比例, 不拆分投入):
        //    input allocations (req.rawMaterialInputs/upstreamSources) 作为独立事实, 由首产出行承载 → 一次全量扣减;
        //    每条 output line 各自入库 (其余产出行不带投入, 只产出)。所有产出行同 (plan, processCode, processOrder)
        //    归属同一份工序报工 → 血缘经该报工把每个产出批次关联到全部实际投入批次 (无按重量/数量的比例分配)。
        //    工序不处理成本 (人工/调料随投入落在首产出行, 计一次不双计)。
        int n = outs.size();
        List<ProcessSheetRowResult.OutputResult> outputResults = new ArrayList<>(n);
        List<MultiOutputMaterialization> materializations = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ProcessSheetRowRequest.OutputLine o = outs.get(i);
            boolean carryInputs = (i == 0); // 首产出行承载全部实际投入 + 人工/调料
            ProcessSheetRowRequest one = synthesizeOutputRequest(req, o, i, carryInputs);
            OneOutputOutcome outcome = materializeOneOutput(
                    factoryId, planId, one, userId, warnings, workflowConfig);
            logChange(factoryId, planId, one, "CREATE", null, one, userId);
            ProcessSheetRowResult.OutputResult or = new ProcessSheetRowResult.OutputResult();
            or.setClientRowId(one.getClientRowId());
            or.setWorkflowPortId(o.getWorkflowPortId());
            or.setMaterialNodeId(o.getMaterialNodeId());
            or.setProductTypeId(o.getProductTypeId());
            or.setBatchId(outcome.batchId());        // generatedBatchId
            or.setBatchNumber(outcome.batchNumber());
            or.setQuantity(o.getQuantity());
            or.setUnit(one.getUnit());
            or.setRowTotalCost(outcome.rowTotalCost());
            or.setYieldRate(yieldRate(one));
            or.setProcessDate(one.getProcessDate());
            or.setLaborSegments(one.getLaborSegments());
            or.setTotalLaborHours(one.getTotalLaborHours());
            or.setByproducts(one.getByproducts());
            outputResults.add(or);
            materializations.add(new MultiOutputMaterialization(one, o, outcome, or));
        }

        // 4. 成本只汇总一次，再按可追溯规则分到每个产出。副产回收不冲减库存批成本，
        //    仍由既有 OrderCostBreakdownService 按各产出 YIELD 报工单独冲减，避免重复计价。
        applyMultiOutputCostAllocation(factoryId, planId, materializations);

        // 5. workflow 任务进度回写一次 (整道工序完成)
        stampWorkflowTaskIfApplicable(factoryId, planId, req, workflowConfig);

        // 6. 汇总结果: 首产出批为代表 + 全部产出明细。
        ProcessSheetRowResult.OutputResult first = outputResults.get(0);
        ProcessSheetRowResult result = buildResult(req, first.getBatchId(), first.getBatchNumber(),
                null, first.getAllocatedCost(), unitPrice(first.getAllocatedCost(), first.getQuantity()),
                false, true, warnings);
        result.setOutputs(outputResults);
        return result;
    }

    /**
     * 单个产出物化 (仅供多产出分解调用; 单产出主路径不走此方法, 保持 F006 现有流一字不改)。
     * 复刻 saveRow 单产出的 pure-SFI / WIP-FG 两分支决策 + 物化 + 落行, 返回批次/成本。
     */
    private OneOutputOutcome materializeOneOutput(String factoryId, String planId,
            ProcessSheetRowRequest one, Long userId, List<String> warnings,
            WorkflowClerkSheetConfigDTO workflowConfig) {
        if (workflowConfig == null && one.isFinished()) {
            ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                    .orElseThrow(() -> new BusinessException(403, "无权访问该计划"));
            if (!Objects.equals(plan.getProductTypeId(), one.getProductTypeId())) {
                throw new BusinessException(409, "旧版计划没有附加成品 SKU 的单位快照")
                        .withCode("LEGACY_MULTI_OUTPUT_SNAPSHOT_MISSING")
                        .withHint("请使用已发布 Workflow 创建新计划后再报多产出")
                        .withSeverity("BLOCKING");
            }
            applyLegacyFinishedWeight(factoryId, planId, one);
        }
        assertExternalFeedUnitSupported(one);
        List<ResolvedEdge> edges = resolveEdges(factoryId, planId, one);
        // 半成品且 (纯 SFI 喂 或 无真实投入[非首产出行]) → 产出直接入 SFI 库 (product 维度, 无需 material_type_id)。
        //   多产出: 非首产出行不带投入 (edges 空), 半成品产出走此路径; 成品产出走下方 FG 路径。
        if (!one.isFinished() && (isPureStockFed(one) || edges.isEmpty())) {
            BigDecimal unitCost = computeInjectionOutputUnitCost(factoryId, one, warnings);
            String anchor = postSfiOutput(factoryId, planId, one, warnings);
            persistRow(factoryId, planId, one, null, anchor, ProcessSheetRow.STATUS_SAVED_SFI);
            BigDecimal totalCost = unitCost == null ? null : unitCost.multiply(one.getOutputQuantity());
            return new OneOutputOutcome(null, anchor, totalCost, anchor, unitCost);
        }
        // 成品不建 WIP → 无需 material_type_id; 半成品 identity 必须来自本道产出快照。
        String outputMaterialIdentity = one.isFinished() ? null : resolveOutputMaterialIdentity(one);
        StepEntry step = buildStepEntry(factoryId, one);
        MaterializeContext ctx = new MaterializeContext(
                factoryId,
                one.isFinished() ? planId : null,
                one.getProductTypeId(),
                one.getBatchNumber(),
                one.isFinished(),
                clerkService.resolveLaborRate(factoryId, warnings),
                clerkService.resolveWarehouseId(factoryId, WarehouseCodes.WH_WKS, warnings),
                outputMaterialIdentity,
                userId);
        MaterializedBatch mat = materializeSheetBatch(
                ctx, List.of(step), edges, warnings, workflowConfig);
        persistRow(factoryId, planId, one, mat.getProductionBatchId(), mat.getBatchNumber(), "SAVED");
        return new OneOutputOutcome(
                mat.getProductionBatchId(), mat.getBatchNumber(), mat.getRowTotalCost(), null, null);
    }

    /**
     * 合成第 i 个产出的单产出行 (复制 base + 覆盖产出字段)。
     *
     * <p>{@code carryInputs}=true (首产出行): 原样携带全部实际投入 (rawMaterialInputs/upstreamSources) +
     * 人工/调料 → 一次全量扣减 + 工时计一次。false (其余产出行): 不带投入, 仅产出入库 (投入已由首行承载,
     * 不重复扣减、不按比例拆分)。血缘经同一 (plan, processCode, processOrder) 报工关联。
     */
    private ProcessSheetRowRequest synthesizeOutputRequest(ProcessSheetRowRequest base,
            ProcessSheetRowRequest.OutputLine o, int i, boolean carryInputs) {
        ProcessSheetRowRequest one = new ProcessSheetRowRequest();
        one.setClientRowId(base.getClientRowId() + "#" + i);
        one.setProcessCode(base.getProcessCode());
        one.setProcessOrder(base.getProcessOrder());
        one.setProcessName(base.getProcessName());
        one.setProcessDate(base.getProcessDate());
        one.setProductTypeId(o.getProductTypeId());
        one.setBatchNumber(null); // 每产出独立系统批号
        one.setFinished(o.isFinished());
        one.setOutputQuantity(o.getQuantity());
        String outUnit = firstNonBlank(o.getUnit(), base.getOutputUnit(), base.getUnit());
        one.setUnit(outUnit);
        one.setOutputUnit(outUnit);
        one.setInputUnit(base.getInputUnit());
        // 每个产出都保存同一组总投入作为出成率分母；只有首行持有真实消费边。
        one.setInputQuantity(base.getInputQuantity());
        one.setProductWeight(o.getProductWeight());
        one.setCustomFields(base.getCustomFields());
        // 2B.2 标记 (整组删除/重存级联) + 产出端口身份 (供 FE 重载映射, Workflow 不碰成本)。
        one.setMultiOutputMember(Boolean.TRUE);
        one.setMultiOutputBaseRowId(base.getClientRowId());
        one.setWorkflowPortId(o.getWorkflowPortId());
        one.setMaterialNodeId(o.getMaterialNodeId());
        one.setSampleRetainQuantity(o.getSampleRetainQuantity());
        one.setInputLineageRawMaterialInputs(base.getRawMaterialInputs());
        one.setInputLineageUpstreamSources(base.getUpstreamSources());
        one.setMaterialInputTotals(base.getMaterialInputTotals());
        List<com.cretas.aims.dto.processentry.LaborSegment> outputLabor = o.getLaborSegments();
        List<com.cretas.aims.dto.processentry.ProcessChainEntryRequest.Byproduct> outputByproducts = o.getByproducts();
        // 旧客户端只在顶层传工时/副产：仍由首产出承载一次；新客户端逐产出字段各自落行。
        one.setLaborSegments(outputLabor != null ? outputLabor : (carryInputs ? base.getLaborSegments() : null));
        one.setByproducts(outputByproducts != null ? outputByproducts : (carryInputs ? base.getByproducts() : null));
        one.setTotalLaborHours(totalLaborHours(one.getLaborSegments()));
        if (carryInputs) {
            // 首产出行承载全部实际投入 (原样, 不拆分) + 人工/调料/逐锅原料/副产/留样/包装明细 (随投入落在首行, 计一次)。
            one.setPotCount(base.getPotCount());
            one.setPotRawKgs(base.getPotRawKgs());
            one.setSeasoningStep(base.isSeasoningStep());
            one.setRawMaterialInputs(base.getRawMaterialInputs());
            one.setUpstreamSources(base.getUpstreamSources());
            one.setPackagingDetail(base.getPackagingDetail());
        }
        return one;
    }

    private void validateOutputDetails(ProcessSheetRowRequest.OutputLine output) {
        if (output.getLaborSegments() != null) {
            for (com.cretas.aims.dto.processentry.LaborSegment segment : output.getLaborSegments()) {
                if (segment == null || segment.getStartTime() == null || segment.getStartTime().isBlank()
                        || segment.getEndTime() == null || segment.getEndTime().isBlank()
                        || segment.getWorkerCount() == null || segment.getWorkerCount() <= 0) {
                    throw new BusinessException(400, "每条产出的工时必须包含开始时间、结束时间和正数人数")
                            .withCode("PROCESS_SHEET_OUTPUT_LABOR_INVALID")
                            .withHintTarget("总工时");
                }
                // 解析失败必须明确报错，不能让成本静默按 0 工时计算。
                parseLaborTime(segment.getStartTime());
                parseLaborTime(segment.getEndTime());
            }
        }
        if (output.getByproducts() != null) {
            for (com.cretas.aims.dto.processentry.ProcessChainEntryRequest.Byproduct byproduct
                    : output.getByproducts()) {
                if (byproduct == null || byproduct.getName() == null || byproduct.getName().isBlank()
                        || byproduct.getQuantity() == null || byproduct.getQuantity().signum() < 0
                        || byproduct.getUnit() == null || byproduct.getUnit().isBlank()
                        || (byproduct.getUnitPrice() != null && byproduct.getUnitPrice().signum() < 0)) {
                    throw new BusinessException(400, "副产必须填写名称、非负数量、固定单位和非负回收单价")
                            .withCode("PROCESS_SHEET_OUTPUT_BYPRODUCT_INVALID")
                            .withHintTarget("副产");
                }
            }
        }
    }

    private static java.time.LocalTime parseLaborTime(String value) {
        try {
            return java.time.LocalTime.parse(value.trim());
        } catch (Exception e) {
            throw new BusinessException(400, "工时时间格式无效: " + value)
                    .withCode("PROCESS_SHEET_OUTPUT_LABOR_TIME_INVALID")
                    .withHint("请使用 HH:mm 格式")
                    .withHintTarget("总工时");
        }
    }

    private static BigDecimal totalLaborHours(
            List<com.cretas.aims.dto.processentry.LaborSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return BigDecimal.ZERO.setScale(4);
        }
        BigDecimal minutes = BigDecimal.ZERO;
        for (com.cretas.aims.dto.processentry.LaborSegment segment : segments) {
            java.time.LocalTime start = parseLaborTime(segment.getStartTime());
            java.time.LocalTime end = parseLaborTime(segment.getEndTime());
            long elapsed = java.time.Duration.between(start, end).toMinutes();
            if (elapsed < 0) elapsed += 24L * 60L;
            minutes = minutes.add(BigDecimal.valueOf(elapsed)
                    .multiply(BigDecimal.valueOf(segment.getWorkerCount())));
        }
        return minutes.divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
    }

    private void applyMultiOutputCostAllocation(
            String factoryId,
            String planId,
            List<MultiOutputMaterialization> materializations) {
        CostAllocationPlan plan = resolveCostAllocationPlan(materializations);
        BigDecimal jointCost = BigDecimal.ZERO;
        boolean costKnown = true;
        BigDecimal materialPool = BigDecimal.ZERO;
        BigDecimal laborPool = BigDecimal.ZERO;
        BigDecimal equipmentPool = BigDecimal.ZERO;
        BigDecimal otherPool = BigDecimal.ZERO;

        for (MultiOutputMaterialization item : materializations) {
            if (item.outcome().rowTotalCost() == null) {
                costKnown = false;
            } else {
                jointCost = jointCost.add(item.outcome().rowTotalCost());
            }
            if (item.outcome().batchId() != null) {
                ProductionBatch batch = productionBatchRepo
                        .findByIdAndFactoryId(item.outcome().batchId(), factoryId)
                        .orElseThrow(() -> new BusinessException(404, "多产出批次不存在或无权访问")
                                .withCode("PROCESS_SHEET_MULTI_OUTPUT_BATCH_NOT_FOUND"));
                materialPool = materialPool.add(nz(batch.getMaterialCost()));
                laborPool = laborPool.add(nz(batch.getLaborCost()));
                equipmentPool = equipmentPool.add(nz(batch.getEquipmentCost()));
                otherPool = otherPool.add(nz(batch.getOtherCost()));
            } else if (item.outcome().rowTotalCost() != null) {
                // SFI 余额只存总成本，无成本桶；归入 otherPool 保持 joint total 可精确分摊。
                otherPool = otherPool.add(item.outcome().rowTotalCost());
            }
        }

        List<BigDecimal> allocatedTotals = allocateMoney(costKnown ? jointCost : null, plan.ratios());
        List<BigDecimal> allocatedMaterial = allocateMoney(costKnown ? materialPool : null, plan.ratios());
        List<BigDecimal> allocatedLabor = allocateMoney(costKnown ? laborPool : null, plan.ratios());
        List<BigDecimal> allocatedEquipment = allocateMoney(costKnown ? equipmentPool : null, plan.ratios());
        List<BigDecimal> allocatedOther = allocateMoney(costKnown ? otherPool : null, plan.ratios());

        for (int i = 0; i < materializations.size(); i++) {
            MultiOutputMaterialization item = materializations.get(i);
            BigDecimal ratioPercent = plan.ratios().get(i)
                    .multiply(BigDecimal.valueOf(100)).setScale(6, RoundingMode.HALF_UP);
            BigDecimal allocated = allocatedTotals.get(i);
            ProcessSheetRowRequest request = item.request();
            request.setCostAllocationRatio(ratioPercent);
            request.setCostAllocationBasis(plan.basis());
            request.setAllocatedCost(allocated);
            item.result().setCostAllocationRatio(ratioPercent);
            item.result().setCostAllocationBasis(plan.basis());
            item.result().setAllocatedCost(allocated);
            item.result().setRowTotalCost(allocated);

            if (costKnown && item.outcome().batchId() != null) {
                ProductionBatch batch = productionBatchRepo
                        .findByIdAndFactoryId(item.outcome().batchId(), factoryId)
                        .orElseThrow(() -> new BusinessException(404, "多产出批次不存在或无权访问")
                                .withCode("PROCESS_SHEET_MULTI_OUTPUT_BATCH_NOT_FOUND"));
                CostBuckets buckets = reconcileCostBuckets(
                        allocated,
                        allocatedMaterial.get(i),
                        allocatedLabor.get(i),
                        allocatedEquipment.get(i),
                        allocatedOther.get(i));
                batch.setMaterialCost(buckets.material());
                batch.setLaborCost(buckets.labor().signum() == 0 ? null : buckets.labor());
                batch.setEquipmentCost(buckets.equipment().signum() == 0 ? null : buckets.equipment());
                batch.setOtherCost(buckets.other().signum() == 0 ? null : buckets.other());
                batch.setTotalCost(allocated);
                batch.setUnitCost(unitPrice(allocated, item.output().getQuantity()));
                productionBatchRepo.save(batch);
                materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                                factoryId, "PRODUCTION_BATCH", item.outcome().batchId().toString())
                        .ifPresent(wip -> {
                            wip.setUnitPrice(unitPrice(allocated, item.output().getQuantity()));
                            materialBatchRepo.save(wip);
                        });
            } else if (costKnown && item.outcome().sfiAnchor() != null) {
                BigDecimal qty = item.output().getQuantity();
                BigDecimal originalTotal = item.outcome().originalUnitCost() == null
                        ? null : item.outcome().originalUnitCost().multiply(qty);
                // 保存阶段已经写入 SFI；同一事务内先精确反冲，再按分摊成本重入，数量净变化为 0。
                wipInventoryService.reverseClerkOutput(
                        factoryId, item.outcome().sfiAnchor(), qty, originalTotal, null);
                wipInventoryService.postClerkOutput(
                        factoryId, item.outcome().sfiAnchor(), item.output().getProductTypeId(),
                        qty, item.request().getUnit(), unitPrice(allocated, qty),
                        null, item.request().getProcessOrder());
            }

            // 把后端裁定的比例/金额写回每个合成行 JSON，形成不依赖前端的审计快照。
            rowRepo.findByFactoryIdAndPlanIdAndClientRowId(factoryId, planId, request.getClientRowId())
                    .forEach(row -> {
                        row.setRowPayload(serializePayload(request));
                        rowRepo.save(row);
                    });
        }
    }

    private CostAllocationPlan resolveCostAllocationPlan(
            List<MultiOutputMaterialization> materializations) {
        boolean anyExplicit = materializations.stream()
                .anyMatch(item -> item.output().getCostAllocationRatio() != null);
        List<BigDecimal> weights = new ArrayList<>(materializations.size());
        String basis;
        if (anyExplicit) {
            basis = "EXPLICIT_RATIO";
            for (MultiOutputMaterialization item : materializations) {
                BigDecimal ratio = item.output().getCostAllocationRatio();
                if (ratio == null || ratio.signum() <= 0) {
                    throw new BusinessException(400, "使用显式成本比例时，每个产出都必须填写大于 0 的比例")
                            .withCode("PROCESS_SHEET_COST_RATIO_REQUIRED")
                            .withHintTarget("成本分摊");
                }
                weights.add(ratio);
            }
            BigDecimal total = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.subtract(BigDecimal.valueOf(100)).abs().compareTo(new BigDecimal("0.01")) > 0) {
                throw new BusinessException(400, "多产出成本分摊比例合计必须为 100%")
                        .withCode("PROCESS_SHEET_COST_RATIO_TOTAL_INVALID")
                        .withHint("当前合计 " + total.stripTrailingZeros().toPlainString() + "%")
                        .withHintTarget("成本分摊");
            }
        } else {
            List<BigDecimal> massWeights = materializations.stream()
                    .map(item -> outputMassKg(item.output()))
                    .toList();
            if (massWeights.stream().allMatch(Objects::nonNull)) {
                basis = "MASS";
                weights.addAll(massWeights);
            } else {
                Set<String> units = materializations.stream()
                        .map(item -> normalizeReportingUnit(item.output().getUnit()))
                        .collect(Collectors.toSet());
                if (units.size() != 1) {
                    throw new BusinessException(400, "不同计量维度的多产出必须填写成本分摊比例")
                            .withCode("PROCESS_SHEET_COST_RATIO_REQUIRED")
                            .withHint("同单位或可换算的 g/kg 会自动分摊；其他组合请填写各产出比例")
                            .withHintTarget("成本分摊");
                }
                basis = "QUANTITY";
                materializations.forEach(item -> weights.add(item.output().getQuantity()));
            }
        }
        BigDecimal totalWeight = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.signum() <= 0) {
            throw new BusinessException(400, "多产出成本分摊基数必须大于 0")
                    .withCode("PROCESS_SHEET_COST_ALLOCATION_BASE_INVALID");
        }
        List<BigDecimal> ratios = weights.stream()
                .map(weight -> weight.divide(totalWeight, 12, RoundingMode.HALF_UP))
                .toList();
        return new CostAllocationPlan(ratios, basis);
    }

    private static BigDecimal outputMassKg(ProcessSheetRowRequest.OutputLine output) {
        if (output.getProductWeight() != null && output.getProductWeight().signum() > 0) {
            return output.getProductWeight();
        }
        String unit = normalizeReportingUnit(output.getUnit());
        if ("kg".equals(unit)) return output.getQuantity();
        if ("g".equals(unit)) return output.getQuantity().movePointLeft(3);
        return null;
    }

    private static String normalizeReportingUnit(String unit) {
        if (unit == null) return "";
        String normalized = unit.trim().toLowerCase(java.util.Locale.ROOT);
        if ("千克".equals(normalized) || "公斤".equals(normalized)) return "kg";
        if ("克".equals(normalized)) return "g";
        return normalized;
    }

    private static List<BigDecimal> allocateMoney(BigDecimal total, List<BigDecimal> ratios) {
        if (total == null) {
            return java.util.Collections.nCopies(ratios.size(), null);
        }
        List<BigDecimal> result = new ArrayList<>(ratios.size());
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < ratios.size(); i++) {
            BigDecimal share = i == ratios.size() - 1
                    ? total.subtract(allocated)
                    : total.multiply(ratios.get(i)).setScale(2, RoundingMode.HALF_UP);
            result.add(share);
            allocated = allocated.add(share);
        }
        return result;
    }

    /**
     * 独立分配成本桶会产生分币尾差。这里按 other -> equipment -> labor -> material
     * 吸收负尾差，正尾差归 other，确保每个成本桶非负且行合计严格等于产出分摊总额。
     */
    private static CostBuckets reconcileCostBuckets(
            BigDecimal total,
            BigDecimal material,
            BigDecimal labor,
            BigDecimal equipment,
            BigDecimal other) {
        BigDecimal delta = total.subtract(material.add(labor).add(equipment).add(other));
        if (delta.signum() >= 0) {
            return new CostBuckets(material, labor, equipment, other.add(delta));
        }
        BigDecimal shortage = delta.negate();
        BigDecimal reduction = other.min(shortage);
        other = other.subtract(reduction);
        shortage = shortage.subtract(reduction);
        reduction = equipment.min(shortage);
        equipment = equipment.subtract(reduction);
        shortage = shortage.subtract(reduction);
        reduction = labor.min(shortage);
        labor = labor.subtract(reduction);
        shortage = shortage.subtract(reduction);
        reduction = material.min(shortage);
        material = material.subtract(reduction);
        shortage = shortage.subtract(reduction);
        if (shortage.signum() > 0) {
            throw new IllegalStateException("多产出成本桶尾差超过已分摊成本");
        }
        return new CostBuckets(material, labor, equipment, other);
    }

    /**
     * 2B.2 B1/B2: 若多产出组 (base#*) 已存在 → 级联删除整组 (供重存前清理)。整组任一行已小结/被下游消耗 →
     * deleteRow 抛 409 (禁止降级)。base#* 定位: 同 (factory, plan) 下 clientRowId == base 或以 base# 开头。
     */
    private void deleteMultiOutputGroupIfPresent(String factoryId, String planId,
            String baseClientRowId, Long userId) {
        List<ProcessSheetRow> group = rowRepo.findByFactoryIdAndPlanId(factoryId, planId).stream()
                .filter(r -> r.getClientRowId() != null
                        && (r.getClientRowId().equals(baseClientRowId)
                        || r.getClientRowId().startsWith(baseClientRowId + "#")))
                .toList();
        if (group.isEmpty()) {
            return;
        }
        // 逐行走 deleteOneRow 反物化 (含小结/下游消耗守卫 + SFI 冲销), 保证扣减/入库精确反冲。
        for (ProcessSheetRow r : group) {
            deleteOneRow(factoryId, planId, r, userId);
        }
    }

    /**
     * 2B.2 B3: 多产出逐产出对齐 workflow 端口 (finished 类型 + 单位)。
     * 只有明确 legacy 计划放行；WORKFLOW 模式必须按 workflowPortId 精确匹配，不按序号或请求单位兜底。
     */
    private WorkflowClerkSheetConfigDTO validateMultiOutputAgainstWorkflow(String factoryId, String planId,
            ProcessSheetRowRequest req) {
        if (workflowClerkSheetService == null) {
            return null;
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO config;
        try {
            config = workflowClerkSheetService.getWorkflowSheetConfig(factoryId, planId);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Workflow 多产出配置读取失败: factory={}, plan={}", factoryId, planId, e);
            throw new BusinessException(409, "Workflow 运行时配置读取失败，不能保存多产出报工")
                    .withCode("PROCESS_SHEET_WORKFLOW_CONFIG_UNAVAILABLE")
                    .withHint("请刷新后重试；若仍失败，请重新物化该生产批次的 Workflow")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        if (config == null) {
            return null; // 明确 legacy
        }
        if (config.getProcesses() == null || config.getProcesses().isEmpty()) {
            throw new BusinessException(409, "Workflow 运行时没有可报工工序")
                    .withCode("PROCESS_SHEET_WORKFLOW_PROCESSES_MISSING")
                    .withHint("请重新物化该生产批次的 Workflow")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.ProcessDescriptor desc =
                config.getProcesses().stream()
                        .filter(p -> p.getProcessOrder() != null
                                && p.getProcessOrder().equals(req.getProcessOrder()))
                        .findFirst()
                        .orElse(null);
        if (desc == null) {
            throw new BusinessException(409, "请求工序不在该批次锁定的 Workflow 中")
                    .withCode("PROCESS_SHEET_WORKFLOW_PROCESS_NOT_FOUND")
                    .withHint("请刷新逐道录入页面，按 Workflow 中的工序报工")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        if (desc.getOutputs() == null || desc.getOutputs().isEmpty()) {
            throw new BusinessException(409, "Workflow 工序缺少产出端口，不能报工")
                    .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_MISSING")
                    .withHint("请修复并重新发布 Workflow 后创建新批次")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        applyWorkflowInputPorts(factoryId, desc, req, false);

        Map<String, com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor> byPortId =
                new HashMap<>();
        for (com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor p : desc.getOutputs()) {
            if (p.getWorkflowPortId() == null || p.getWorkflowPortId().isBlank()) {
                throw new BusinessException(409, "Workflow 产出端口缺少稳定标识")
                        .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_ID_MISSING")
                        .withHint("请修复并重新发布 Workflow 后创建新批次")
                        .withSeverity("BLOCKING")
                        .withHintTarget("Workflow");
            }
            if (byPortId.put(p.getWorkflowPortId(), p) != null) {
                throw new BusinessException(409, "Workflow 存在重复的产出端口标识")
                        .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_DUPLICATE")
                        .withHint("请修复并重新发布 Workflow 后创建新批次")
                        .withSeverity("BLOCKING")
                        .withHintTarget("Workflow");
            }
        }
        List<ProcessSheetRowRequest.OutputLine> outs = req.getOutputs();
        java.util.Set<String> submittedPortIds = new java.util.HashSet<>();
        for (ProcessSheetRowRequest.OutputLine o : outs) {
            if (o.getWorkflowPortId() == null || o.getWorkflowPortId().isBlank()) {
                throw new BusinessException(409, "多产出报工必须指定 Workflow 产出端口")
                        .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_REQUIRED")
                        .withHint("请刷新逐道录入页面后重新填写")
                        .withSeverity("BLOCKING")
                        .withHintTarget("Workflow");
            }
            if (!submittedPortIds.add(o.getWorkflowPortId())) {
                throw new BusinessException(409, "同一 Workflow 产出端口不能重复报工")
                        .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_REPEATED")
                        .withHint("请合并重复的产出行")
                        .withSeverity("BLOCKING")
                        .withHintTarget("Workflow");
            }
            com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor port =
                    byPortId.get(o.getWorkflowPortId());
            if (port == null) {
                throw new BusinessException(409, "请求包含不属于该 Workflow 工序的产出端口")
                        .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_NOT_FOUND")
                        .withHint("请刷新逐道录入页面后重新填写")
                        .withSeverity("BLOCKING")
                        .withHintTarget("Workflow");
            }
            boolean expectFinished = Boolean.TRUE.equals(port.getFinished());
            if (o.isFinished() != expectFinished) {
                throw new BusinessException(409, "产出「"
                        + (port.getMaterialName() != null ? port.getMaterialName() : "")
                        + "」应为" + (expectFinished ? "成品" : "半成品") + ", 当前产出类型不符")
                        .withCode("WORKFLOW_ROW_OUTPUT_KIND_MISMATCH")
                        .withHint("请按 Workflow 配置的产出类型录入");
            }
            if (port.getSkuId() == null || port.getSkuId().isBlank()
                    || !port.getSkuId().equals(o.getProductTypeId())) {
                throw new BusinessException(409, "产出「"
                        + (port.getMaterialName() != null ? port.getMaterialName() : "")
                        + "」产品与 Workflow 端口绑定不一致")
                        .withCode("WORKFLOW_ROW_OUTPUT_SKU_MISMATCH")
                        .withHint("请刷新逐道录入页面后重新填写")
                        .withSeverity("BLOCKING")
                        .withHintTarget("Workflow");
            }
            String expectUnit = requireWorkflowPortUnit(port.getUnit(), "产出");
            String actualUnit = firstNonBlank(o.getUnit(), req.getUnit());
            if (!configuredUnitsEquivalent(factoryId, actualUnit, expectUnit)) {
                throw new BusinessException(409, "产出「"
                        + (port.getMaterialName() != null ? port.getMaterialName() : "")
                        + "」单位应为「" + expectUnit + "」, 当前为「" + actualUnit + "」")
                        .withCode("WORKFLOW_ROW_OUTPUT_UNIT_MISMATCH")
                        .withHint("请按 Workflow 配置的单位录入产出");
            }
            o.setUnit(expectUnit);
            applyAuthoritativeFinishedWeight(o, port);
        }
        // WorkProcessTask.actualQuantity 只记录主产出数量。禁止把不同单位的多产出直接求和
        // （例如 8件 + 500g = 508），主产出由 descriptor.output 明确定义。
        String primaryPortId = desc.getOutput() != null ? desc.getOutput().getWorkflowPortId() : null;
        ProcessSheetRowRequest.OutputLine primaryOutput = outs.stream()
                .filter(line -> primaryPortId != null && primaryPortId.equals(line.getWorkflowPortId()))
                .findFirst()
                .orElseGet(outs::getFirst);
        req.setOutputQuantity(primaryOutput.getQuantity());
        return config;
    }

    // ─────────────────────────────────────────────────────────────
    // G2: 自定义字段 key 白名单校验
    // ─────────────────────────────────────────────────────────────

    /**
     * G2 KEYSTONE (save-validate): 校验 {@code req.getCustomFields()} 的每个 key 都在该工序
     * {@link WorkProcess#getCustomFieldSchema()} 的已启用 (enabled=true) key 集合内。
     *
     * <p>禁止降级 (api-response-handling.md): 未知 key → 明确 400 + 指出具体 key + 工序名,
     * 不静默丢弃、不静默忽略。schema 本身缺失 (null / 无法解析该道对应的 WorkProcess) 视为
     * "该工序未开启自定义字段校验" —— 此时不拒绝任何 key (宽松兜底, 因为 schema=null 是本功能
     * 的默认/未配置状态, 拒绝会误伤未升级使用本功能的既有工序)。若请求根本没带 customFields,
     * 直接跳过 (最常见路径, 提前 return 避免不必要查询)。
     *
     * <p><b>F2(a)</b>: 判据是「key 是否在 schema 声明里」(无论 enabled 真假), 见
     * {@link ProcessCustomFieldValidation#checkKeys}。字段被 admin 禁用后仍在 schema 里 →
     * 该行历史存的禁用键再次提交不会被误挡, 只挡真正未知 key。
     */
    private void validateCustomFields(String factoryId, ProcessSheetRowRequest req) {
        Map<String, Object> customFields = req.getCustomFields();
        if (customFields == null || customFields.isEmpty()) {
            return;
        }
        if (req.getProductTypeId() == null || req.getProcessOrder() == null) {
            // 无法定位该道对应的 WorkProcess (缺 productTypeId/processOrder) —— 防御性放行,
            // 不因为定位信息缺失而拒绝写入 (这类缺失应由别处校验拦截, 不是本方法职责)。
            return;
        }
        WorkProcess wp = resolveWorkProcess(factoryId, req.getProductTypeId(), req.getProcessOrder());
        if (wp == null) {
            return; // 找不到对应工序配置 —— 无 schema 可校验, 放行
        }
        // F2(a) + F3: 共享判据 —— key 不在 schema (无论 enabled) → 诚实 400。
        ProcessCustomFieldValidation.checkKeys(wp.getCustomFieldSchema(), customFields.keySet(), wp.getProcessName());
    }

    /**
     * F2(b): 把 {@code prior} (上次已存 row_payload 反序列化) 里已存、本次 {@code req} 未提交的自定义键
     * merge 回 {@code req.customFields} —— 新提交同名键覆盖旧值, 旧有其它键保留。
     *
     * <p>动机: 字段被 admin 禁用后, 前端 buildRequest 只收 enabled 键 → 禁用键不再随请求提交;
     * 而 re-save 落库是 {@code serializePayload(req)} 整体覆盖 row_payload + 物化重写
     * ProductionReport.customFields —— 若不 merge, 该行历史录入的禁用键值 (如已录波美度=12.5) 会被
     * 静默销毁 (F2 真 bug)。merge 后, 未提交的旧键随 req 一并落库 + 物化 (row_payload 与
     * ProductionReport.customFields.clerkCustomFields 同源 req, 一致保留)。
     *
     * <p>取舍: 由此"未提交即保留"意味着 enabled 字段无法通过"提交空值/省略"来清空 (前端 buildRequest
     * 本就不发空值)。数据保全 (不静默丢失客户已录数据) 优先级高于"按省略清空", 与 F2 brief 一致。
     */
    private void mergeCustomFieldsFromPrior(ProcessSheetRowRequest req, ProcessSheetRowRequest prior) {
        Map<String, Object> priorFields = prior == null ? null : prior.getCustomFields();
        if (priorFields == null || priorFields.isEmpty()) {
            return; // 无既存自定义键 —— 无需 merge
        }
        Map<String, Object> merged = new LinkedHashMap<>(priorFields);
        if (req.getCustomFields() != null) {
            merged.putAll(req.getCustomFields()); // 新提交覆盖同名键
        }
        req.setCustomFields(merged);
    }

    /**
     * 解析 (factory, productTypeId, processOrder) → 该道对应的 {@link WorkProcess} (供 schema 读取)。
     * 找不到工序配置 / 未链接 workProcessId → null (caller 视为"无 schema", 放行)。
     */
    private WorkProcess resolveWorkProcess(String factoryId, String productTypeId, Integer processOrder) {
        List<ProductWorkProcess> pwps = productWorkProcessRepo
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, productTypeId);
        ProductWorkProcess pwp = pwps.stream()
                .filter(p -> processOrder.equals(p.getProcessOrder()))
                .findFirst()
                .orElse(null);
        if (pwp == null || pwp.getWorkProcessId() == null) {
            return null;
        }
        return processRepo.findById(pwp.getWorkProcessId()).orElse(null);
    }

    // ─────────────────────────────────────────────────────────────
    // Re-save stub (Task 1.6 fills this in)
    // ─────────────────────────────────────────────────────────────

    /**
     * 覆盖已有行 (update-in-place 保 id) —— SP-F Task 1.6。
     *
     * <p>跑在 {@code saveRow} 的 {@code @Transactional} 内, 所有软删 + 重写都在同一事务 (原子)。
     * 下游消耗守卫 (409) 在任何写入之前抛出。re-save 走 UPDATE 现有行, 不会撞 UK insert 冲突,
     * 故无需 {@code DataIntegrityViolationException} 处理。
     *
     * <p>三种情形:
     * <ul>
     *   <li><b>CASE A</b> (existing.batchId == null, 之前是 DRAFT): output≤0 仍 DRAFT; output&gt;0 则
     *       像 create 一样新建批次 (DRAFT 无既有批可保), 更新行为 SAVED。</li>
     *   <li><b>CASE B1</b> (existing.batchId != null, output≤0): 逆向物化为 DRAFT ——
     *       软删旧边/报工 + 软删 WIP/ProductionBatch + 行 batchId 置 null。</li>
     *   <li><b>CASE B2</b> (existing.batchId != null, output&gt;0): 软删旧边/报工 + 原地重物化 (保 id)。</li>
     * </ul>
     * CASE B 前先查下游消耗 (谁消耗了本批的 WIP); 非空 → 409 (🔒 成本图完整性)。
     */
    private ProcessSheetRowResult resaveRow(String factoryId, String planId,
                                            ProcessSheetRowRequest req, Long userId,
                                            ProcessSheetRow existing,
                                            WorkflowClerkSheetConfigDTO workflowConfig) {
        if (ProcessSheetRow.SUBMISSION_SUBMITTED.equals(existing.getSubmissionStatus())) {
            throw new BusinessException(409, "已正式提交的报工不能直接修改")
                    .withCode("PROCESS_SHEET_SUBMITTED_IMMUTABLE")
                    .withHint("如需更正请走撤销流程")
                    .withSeverity("BLOCKING");
        }
        // 🔒 G3 防双扣: 已小结入库的行不可直接编辑。
        // 否则 CASE B2 会软删旧消耗边 + 重建 interim_settled_at=NULL 的新边 (下次小结再次扣减原料,
        // 原扣减从未反冲 → usedQuantity 超扣), 且行仍带戳 → 更正后的产出永不重新过账。
        // 必须在任何软删/重物化 (消耗边/报工/WIP) 之前拦截, 避免部分变更。
        // 完整 反冲-重过账 (撤销小结) 属 Phase 3, 当前阶段先阻断。
        if (existing.getInterimSettledAt() != null) {
            throw new BusinessException(409, "该行已小结入库,不可直接修改;如需更正请走撤销小结(功能开发中)")
                    .withCode("ROW_INTERIM_SETTLED")
                    .withHint("已小结入库的工序行不可编辑,请通过撤销小结更正")
                    .withSeverity("BLOCKING")
                    .withHintTarget(req.getProcessCode());
        }

        List<String> warnings = new ArrayList<>();

        assertFinishedGoodsSourceAllowed(factoryId, req);

        // SP-G P3: 捕获变更前 payload (在任何 updateRowInPlace 之前), 供 UPDATE diff 审计。
        ProcessSheetRowRequest beforeReq = tryDeserialize(existing.getRowPayload());

        // F2(b): 自定义字段 merge (不整体覆盖) —— 字段被 admin 禁用后前端 buildRequest 不再发它,
        //   若整体覆盖 row_payload 会静默销毁该行已存的禁用键值。把 beforeReq 里已存、本次 req 未提交的
        //   自定义键 merge 回 req.customFields (新提交覆盖同名键), 再落库 + 物化 —— row_payload 与
        //   ProductionReport.customFields.clerkCustomFields 同源 (都从 merge 后的 req 派生), 一并保留。
        mergeCustomFieldsFromPrior(req, beforeReq);

        // 5988: 成品作投料来源门控 —— 该工序未开启 allowFinishedGoodsSource 时拒绝 FG-source 投料。
        assertFinishedGoodsSourceAllowed(factoryId, req);

        // 与 create 同的 factory-scoped 上游/原料边解析 (🔒)
        List<ResolvedEdge> edges = resolveEdges(factoryId, planId, req);
        BigDecimal newOutput = req.getOutputQuantity();
        boolean hasOutput = newOutput != null && newOutput.signum() > 0;

        // ── CASE A: 之前是 DRAFT (无既有批次) ─────────────────────────
        if (existing.getBatchId() == null) {
            // #1252 中段起步: 旧行若为 SAVED_SFI (保存时已 SFI IN 入库), 任何重存 (改产出/转 DRAFT/转物化)
            //   前先冲销旧 SFI IN (reverseClerkOutput; 下游已消耗则 409 SFI_DOWNSTREAM_CONSUMED 拒绝, 防超扣),
            //   再按新 req 重新入库 —— 避免重复 SFI IN 造幽灵库存。旧行 DRAFT (无入库) 则无冲销。
            if (ProcessSheetRow.STATUS_SAVED_SFI.equals(existing.getRowStatus())) {
                reverseSfiOutput(factoryId, planId, beforeReq);
            }
            if (!hasOutput) {
                // 仍是 DRAFT —— 仅更新行 payload, 保持 DRAFT, 不物化。
                updateRowInPlace(existing, req, null, null, "DRAFT");
                logChange(factoryId, planId, req, "UPDATE", beforeReq, req, userId);
                return buildResult(req, null, null, null, null, null, true, false, warnings);
            }
            // #1252 纯外部库存 (SFI/FG) 非成品道 (output>0) → 保存时即 SFI IN 入库 (同 create 路径);
            //   输入 SFI/FG 在小结扣减。产出入库使下游道小结前即可选到。
            if (!req.isFinished() && isPureStockFed(req)) {
                String anchor = postSfiOutput(factoryId, planId, req, warnings);
                updateRowInPlace(existing, req, null, anchor, ProcessSheetRow.STATUS_SAVED_SFI);
                logChange(factoryId, planId, req, "UPDATE", beforeReq, req, userId);
                stampWorkflowTaskIfApplicable(factoryId, planId, req, workflowConfig);
                return buildResult(req, null, anchor, yieldRate(req), null, null, true, true, warnings);
            }
            // DRAFT → 物化: legacy 计划新建批次；Workflow 成品道复用计划已有运行批次。
            String outputMaterialIdentity = resolveOutputMaterialIdentity(req);
            StepEntry step = buildStepEntry(factoryId, req);
            MaterializeContext ctx = new MaterializeContext(
                    factoryId,
                    req.isFinished() ? planId : null,
                    req.getProductTypeId(),
                    req.getBatchNumber(),
                    req.isFinished(),
                    clerkService.resolveLaborRate(factoryId, warnings),
                    clerkService.resolveWarehouseId(factoryId, WarehouseCodes.WH_WKS, warnings),
                    outputMaterialIdentity,
                    userId);
            MaterializedBatch mat = materializeSheetBatch(
                    ctx, List.of(step), edges, warnings, workflowConfig);
            updateRowInPlace(existing, req, mat.getProductionBatchId(), mat.getBatchNumber(), "SAVED");
            logChange(factoryId, planId, req, "UPDATE", beforeReq, req, userId);
            stampWorkflowTaskIfApplicable(factoryId, planId, req, workflowConfig);
            return buildResult(req, mat.getProductionBatchId(), mat.getBatchNumber(),
                    yieldRate(req), mat.getRowTotalCost(), unitPrice(mat.getRowTotalCost(), newOutput),
                    true, true, warnings);
        }

        // ── CASE B: 之前已物化 (existing.batchId != null) ────────────
        // 查既有 WIP 产出批 (可能为空: 之前 output=0 但 batchId 已设的边缘情形 → 防御处理)。
        Optional<MaterialBatch> wipOpt = materialBatchRepo
                .findByFactoryIdAndSourceDocTypeAndSourceDocId(
                        factoryId, "PRODUCTION_BATCH", existing.getBatchId().toString());

        // 下游消耗守卫 (🔒): 谁消耗了本批的 WIP? 非空 → 拒绝 (任何写入之前)。
        if (wipOpt.isPresent()) {
            List<MaterialConsumption> downstream = consumptionRepo
                    .findByFactoryIdAndBatchId(factoryId, wipOpt.get().getId());
            if (!downstream.isEmpty()) {
                throw new BusinessException(409,
                        "该批已被下游 " + downstream.size() + " 行消耗，请先删除下游行再改");
            }
        }

        // ── CASE B1: 新产出≤0 → 逆向物化为 DRAFT ─────────────────────
        if (!hasOutput) {
            // reverseMaterialization 含软删边/报工/WIP/ProductionBatch 的完整逆向
            reverseMaterialization(factoryId, existing.getBatchId(), wipOpt);
            updateRowInPlace(existing, req, null, null, "DRAFT");
            logChange(factoryId, planId, req, "UPDATE", beforeReq, req, userId);
            return buildResult(req, null, null, null, null, null, true, false, warnings);
        }

        // ── CASE B2: 新产出>0 → 原地重物化 (保 id) ───────────────────
        // B2 仅软删旧边/报工 (WIP + ProductionBatch 原地更新, 不软删): 先清旧消耗再重物化。
        consumptionRepo.softDeleteByFactoryIdAndProductionBatchId(factoryId, existing.getBatchId());
        reportRepo.softDeleteByFactoryIdAndBatchId(factoryId, existing.getBatchId());
        String outputMaterialIdentity = resolveOutputMaterialIdentity(req);
        StepEntry step = buildStepEntry(factoryId, req);
        MaterializeContext ctx = new MaterializeContext(
                factoryId,
                req.isFinished() ? planId : null,
                req.getProductTypeId(),
                existing.getBatchNumber(),  // 保留现有批次号
                req.isFinished(),
                clerkService.resolveLaborRate(factoryId, warnings),
                clerkService.resolveWarehouseId(factoryId, WarehouseCodes.WH_WKS, warnings),
                outputMaterialIdentity,
                userId);

        String existingWipMbId = wipOpt.map(MaterialBatch::getId).orElse(null);
        MaterializedBatch mat = clerkService.rematerializeInPlace(
                ctx, existing.getBatchId(), existingWipMbId, List.of(step), edges, warnings);

        // batchId/batchNumber 不变; 仅刷新 payload + status。
        updateRowInPlace(existing, req, existing.getBatchId(), existing.getBatchNumber(), "SAVED");
        logChange(factoryId, planId, req, "UPDATE", beforeReq, req, userId);
        stampWorkflowTaskIfApplicable(factoryId, planId, req, workflowConfig);
        return buildResult(req, existing.getBatchId(), existing.getBatchNumber(),
                yieldRate(req), mat.getRowTotalCost(), unitPrice(mat.getRowTotalCost(), newOutput),
                true, true, warnings);
    }

    // ─────────────────────────────────────────────────────────────
    // Delete row (Task 1.8)
    // ─────────────────────────────────────────────────────────────

    /**
     * SP-F Task 1.8: 删除一行。
     *
     * <p>finder 选择: 使用 (factory, plan, clientRowId) 三列查询而非携带 processCode。
     * 理由: delete 端点路径仅包含 clientRowId，强迫 caller 额外传 processCode 会增加 API 负担。
     * 实际上同一 plan 内 clientRowId 跨工序不重复，返回 1 条；若因数据异常返多条则全部删除。
     */
    @Override
    @Transactional
    public void deleteRow(String factoryId, String planId, String clientRowId, Long userId) {
        List<ProcessSheetRow> rows = rowRepo
                .findByFactoryIdAndPlanIdAndClientRowId(factoryId, planId, clientRowId);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "工序行不存在");
        }

        // 2B.2 B1: 目标若为多产出组成员 → 级联删除整组 (base#*)。防单行删除留幻库存 (投入只在 base#0,
        //   单删产出行 → 投入反冲但同组其它产出仍在 → 凭空库存)。整组任一行已小结/被下游消耗 → deleteOneRow 抛 409。
        String baseRowId = multiOutputBaseOf(rows.get(0));
        List<ProcessSheetRow> targets = (baseRowId != null)
                ? rowRepo.findByFactoryIdAndPlanId(factoryId, planId).stream()
                        .filter(r -> r.getClientRowId() != null
                                && (r.getClientRowId().equals(baseRowId)
                                || r.getClientRowId().startsWith(baseRowId + "#")))
                        .toList()
                : rows;
        for (ProcessSheetRow row : targets) {
            deleteOneRow(factoryId, planId, row, userId);
        }
    }

    /** 返回该行所属多产出组的 base clientRowId; 非多产出行返 null。 */
    private String multiOutputBaseOf(ProcessSheetRow row) {
        ProcessSheetRowRequest req = tryDeserialize(row.getRowPayload());
        if (req != null && Boolean.TRUE.equals(req.getMultiOutputMember())) {
            String base = req.getMultiOutputBaseRowId();
            if (base != null && !base.isBlank()) {
                return base;
            }
            String cid = row.getClientRowId();
            int idx = cid == null ? -1 : cid.lastIndexOf('#');
            return idx > 0 ? cid.substring(0, idx) : cid;
        }
        return null;
    }

    /** 单行删除内部体 (无级联; 供 deleteRow 级联 + deleteMultiOutputGroupIfPresent 复用)。 */
    private void deleteOneRow(String factoryId, String planId, ProcessSheetRow row, Long userId) {
        if (ProcessSheetRow.SUBMISSION_SUBMITTED.equals(row.getSubmissionStatus())) {
            throw new BusinessException(409, "已正式提交的报工不能直接删除")
                    .withCode("PROCESS_SHEET_SUBMITTED_IMMUTABLE")
                    .withHint("如需更正请走撤销流程")
                    .withSeverity("BLOCKING");
        }
        // 🔒 G3 防双扣: 已小结入库的行不可删除 (usedQuantity 扣减从未反冲 → 账面超扣)。完整撤销走撤销小结。
        if (row.getInterimSettledAt() != null) {
            throw new BusinessException(409, "该行已小结入库,不可删除;如需更正请走撤销小结(功能开发中)")
                    .withCode("ROW_INTERIM_SETTLED")
                    .withHint("已小结入库的工序行不可删除,请通过撤销小结更正")
                    .withSeverity("BLOCKING");
        }
        if (row.getBatchId() != null) {
            Optional<MaterialBatch> wipOpt = materialBatchRepo
                    .findByFactoryIdAndSourceDocTypeAndSourceDocId(
                            factoryId, "PRODUCTION_BATCH", row.getBatchId().toString());
            if (wipOpt.isPresent()) {
                List<MaterialConsumption> downstream = consumptionRepo
                        .findByFactoryIdAndBatchId(factoryId, wipOpt.get().getId());
                if (!downstream.isEmpty()) {
                    throw new BusinessException(409,
                            "该批已被下游 " + downstream.size() + " 行消耗，请先删除下游行再改");
                }
            }
            reverseMaterialization(factoryId, row.getBatchId(), wipOpt);
        } else if (ProcessSheetRow.STATUS_SAVED_SFI.equals(row.getRowStatus())) {
            reverseSfiOutput(factoryId, planId, tryDeserialize(row.getRowPayload()));
        }
        ProcessSheetRowRequest beforeReq = tryDeserialize(row.getRowPayload());
        logChange(factoryId, planId, beforeReq, "DELETE", beforeReq, null, userId,
                row.getProcessCode(), row.getClientRowId());
        row.softDelete();
        rowRepo.save(row);
    }

    // ─────────────────────────────────────────────────────────────
    // WIP 在制品库存读取 (Task 2.1)
    // ─────────────────────────────────────────────────────────────

    /**
     * SP-F Task 2.1: 读取指定工序的 WIP 在制品库存视图。
     *
     * <p>计划归属通过 rowRepo 的 (factory, plan) 双键隐式保证: 只有属于本 factory + planId
     * 的行才会被返回, 无需额外 productionPlanRepository 查询 (🔒 factory-scoped)。
     *
     * <p>used 的查询走 findByFactoryIdAndBatchId —— 该方法的 JPQL 含 factory 过滤且
     * MaterialConsumption @Where(deleted_at IS NULL) 自动排除软删边, 因此:
     * <ul>
     *   <li>跨租户 (其他 factory) 的消耗边不会混入 used (🔒)</li>
     *   <li>因 re-save/delete 软删的旧消耗边不计入 used (正确: 不会 double-count)</li>
     * </ul>
     */
    @Override
    public List<ProcessSheetInventoryItem> getInventory(String factoryId, String planId,
                                                        String processCode, Integer processOrder) {
        // SP-F role-mode fix: processOrder 非空 → 双键过滤 (隔离同 archetype 多工序);
        // null → code-only 回退 (向后兼容旧客户端)。
        List<ProcessSheetRow> rows = processOrder != null
                ? rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndProcessOrder(
                        factoryId, planId, processCode, processOrder)
                : rowRepo.findByFactoryIdAndPlanIdAndProcessCode(factoryId, planId, processCode);

        List<ProcessSheetInventoryItem> result = new ArrayList<>();
        for (ProcessSheetRow row : rows) {
            // 新契约仅正式提交产出可被下游选择；LEGACY 保持历史兼容。
            if (ProcessSheetRow.SUBMISSION_DRAFT.equals(row.getSubmissionStatus())) {
                continue;
            }
            // DRAFT 行 (batchId == null, outputQty <= 0 未物化) → 跳过
            if (row.getBatchId() == null) {
                continue;
            }

            // 找 WIP MaterialBatch (sourceDocType='PRODUCTION_BATCH', sourceDocId=batchId)
            Optional<MaterialBatch> wipOpt = materialBatchRepo
                    .findByFactoryIdAndSourceDocTypeAndSourceDocId(
                            factoryId, "PRODUCTION_BATCH", row.getBatchId().toString());
            if (wipOpt.isEmpty()) {
                // 防御: 物化行但无对应 WIP (异常数据 / 已逆向物化但行未软删) → 跳过
                continue;
            }
            MaterialBatch wip = wipOpt.get();

            BigDecimal produced = nz(wip.getReceiptQuantity());

            // Σ 下游 MaterialConsumption.quantity (factory-scoped 🔒, soft-deleted excluded by @Where)
            BigDecimal used = consumptionRepo
                    .findByFactoryIdAndBatchId(factoryId, wip.getId())
                    .stream()
                    .map(c -> nz(c.getQuantity()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal remaining = produced.subtract(used);
            String status = remaining.signum() <= 0 ? "DEPLETED" : "ACTIVE";
            ProcessSheetRowRequest storedRequest = parsePayloadQuiet(row.getRowPayload());
            String storedOutputUnit = storedRequest == null
                    ? wip.getQuantityUnit()
                    : firstNonBlank(storedRequest.getOutputUnit(), storedRequest.getUnit(), wip.getQuantityUnit());

            result.add(ProcessSheetInventoryItem.builder()
                    .batchNumber(row.getBatchNumber())
                    .productTypeId(storedRequest == null ? null : storedRequest.getProductTypeId())
                    .produced(produced)
                    .used(used)
                    .remaining(remaining)
                    .status(status)
                    .unit(storedOutputUnit)
                    .unitPrice(nz(wip.getUnitPrice()))
                    // ② 批次下拉补 品名 + 生产日期 (成本用 unitPrice)。品名从 row payload 的 productTypeId 反查。
                    .productTypeName(resolveProductTypeName(factoryId, row))
                    .productionDate(wip.getProductionDate())
                    .build());
        }
        return result;
    }

    /**
     * ② 从 process_sheet_row 的 payload 解析 productTypeId → 产品名称 (供投料下拉品名展示)。
     * 解析失败 / 无 productType → null (诚实, 不伪造)。
     */
    private String resolveProductTypeName(String factoryId, ProcessSheetRow row) {
        ProcessSheetRowRequest req = parsePayloadQuiet(row.getRowPayload());
        if (req == null || req.getProductTypeId() == null || req.getProductTypeId().isBlank()) {
            return null;
        }
        return productTypeRepo.findByIdAndFactoryId(req.getProductTypeId(), factoryId)
                .map(pt -> pt.getName())
                .orElse(null);
    }

    private ProcessSheetRowRequest parsePayloadQuiet(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, ProcessSheetRowRequest.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // F006 双出成率: 计划级 WIP 库存卡 (getInventoryYieldCard)
    // ─────────────────────────────────────────────────────────────

    private static final BigDecimal YIELD_SCALE_BD = new BigDecimal("0.0001"); // scale 4
    private static final int YIELD_SCALE = 4;

    @Override
    public List<ProcessSheetInventoryItem> getInventoryYieldCard(String factoryId, String planId) {
        List<ProcessSheetRow> sheetRows = rowRepo.findByFactoryIdAndPlanId(factoryId, planId);
        if (sheetRows != null && !sheetRows.isEmpty()) {
            return getInventoryYieldCardFromProcessSheetRows(factoryId, sheetRows);
        }

        // 1. 获取该计划所有生产批次
        List<ProductionBatch> batches = productionBatchRepo
                .findByFactoryIdAndProductionPlanId(factoryId, planId);
        if (batches.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> batchIds = batches.stream().map(ProductionBatch::getId).toList();

        // 2. 获取所有批次的 SemiFinishedInventory 行 (已按 batchId 组)
        List<SemiFinishedInventory> allWips = new ArrayList<>();
        for (Long bid : batchIds) {
            allWips.addAll(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, bid));
        }
        if (allWips.isEmpty()) {
            return new ArrayList<>();
        }

        // 3. 按 processOrder 升序排列 (null processOrder 排最后)
        allWips.sort(Comparator.comparingInt(w -> w.getProcessOrder() == null ? Integer.MAX_VALUE : w.getProcessOrder()));

        // 4. 回填 processName: taskId → workProcessId → processName
        Map<Long, String> processNameByTaskId = resolveProcessNames(factoryId, allWips);
        // 4b. 兜底图: WIP 未关联 task 时按 processOrder 反查真实工序名 (避免显示"工序N")
        //     productTypeId 优先取自批次(可靠); WIP 上可能为 null (逐道录入未回填) → 否则兜底图取不到
        String anyProductTypeId = batches.stream()
                .map(ProductionBatch::getProductTypeId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() -> allWips.stream()
                        .map(SemiFinishedInventory::getProductTypeId)
                        .filter(Objects::nonNull)
                        .findFirst().orElse(null));
        Map<Integer, String> processNameByOrder = resolveProcessNamesByOrder(factoryId, anyProductTypeId);

        // 5. 获取每个批次的首道 YIELD 报工 inputQuantity (用于 step1 的 stepYieldRate 分母)
        //    key = batchId, value = Σ inputQuantity of processOrder=min YIELD reports
        Map<Long, BigDecimal> firstStepInputByBatch = resolveFirstStepInputPerBatch(factoryId, batchIds);

        // 6. 构建输出: 按顺序处理 WIP 行, 维护"上一道产出"作为当道 step 投入
        //    注意: 同一 batchId 的 WIP 行形成链; 跨批次则各自独立链
        //    设计简化: 若 planId 只有 1 个 batch, 最干净; 多 batch 时各 batch 独立链
        Map<Long, BigDecimal> prevOutputByBatch = new HashMap<>();
        Map<Long, String> prevUnitByBatch = new HashMap<>();

        // 7. 获取折算系数 (从 ProductType.gramsPerUnit) — 取最后道有 productTypeId 的 WIP 的 gramsPerUnit
        //    注: 同一计划通常只有一个产品类型
        BigDecimal gramsPerUnit = resolveGramsPerUnit(factoryId, allWips);
        String firstStepUnit = allWips.isEmpty() ? null : allWips.get(0).getUnit();

        List<ProcessSheetInventoryItem> result = new ArrayList<>();
        for (SemiFinishedInventory wip : allWips) {
            BigDecimal produced = nz(wip.getProducedQuantity());
            BigDecimal consumed = nz(wip.getConsumedQuantity());
            BigDecimal available = nz(wip.getAvailableQuantity());
            String unit = wip.getUnit();
            Long batchId = wip.getBatchId();

            // stepYieldRate: 投入来源
            BigDecimal stepInput;
            if (!prevOutputByBatch.containsKey(batchId)) {
                // 首道: 投入来自 ProductionReport.inputQuantity (原料投入)
                stepInput = firstStepInputByBatch.get(batchId);
            } else {
                // 后续道: 投入 = 上一道 producedQuantity
                stepInput = prevOutputByBatch.get(batchId);
            }

            BigDecimal stepYieldRate = null;
            if (stepInput != null && stepInput.compareTo(BigDecimal.ZERO) > 0) {
                stepYieldRate = produced
                        .multiply(BigDecimal.valueOf(100))
                        .divide(stepInput, YIELD_SCALE, RoundingMode.HALF_UP);
            }

            // cumulativeYieldRate: 首道投入 (最小 processOrder WIP 的 stepInput)
            BigDecimal firstInput = firstStepInputByBatch.get(batchId);
            BigDecimal cumulativeYieldRate = null;
            if (firstInput != null && firstInput.compareTo(BigDecimal.ZERO) > 0) {
                // 折算: 若当前道单位 != 首道单位, 尝试折算
                BigDecimal producedConverted = convertToFirstStepUnit(produced, unit, firstStepUnit, gramsPerUnit);
                if (producedConverted != null) {
                    cumulativeYieldRate = producedConverted
                            .multiply(BigDecimal.valueOf(100))
                            .divide(firstInput, YIELD_SCALE, RoundingMode.HALF_UP);
                }
            }

            // 更新"上一道输出"
            prevOutputByBatch.put(batchId, produced);
            prevUnitByBatch.put(batchId, unit);

            String processName = processNameByTaskId.get(wip.getSourceWorkProcessTaskId());
            if (processName == null && wip.getProcessOrder() != null) {
                processName = processNameByOrder.get(wip.getProcessOrder()); // 兜底真实名, 否则前端显示"工序N"
            }

            result.add(ProcessSheetInventoryItem.builder()
                    .batchNumber(wip.getIntermediateBatchNo())
                    .produced(produced)
                    .used(consumed)
                    .remaining(available)
                    .status(wip.getStatus())
                    .unitPrice(wip.getUnitCost())
                    .rowTotalCost(wip.getUnitCost() == null || produced == null ? null
                            : wip.getUnitCost().multiply(produced).setScale(2, RoundingMode.HALF_UP)) // §9 口径铁律: 展示侧镜像持久化 scale-2, 防亚分噪音(1.9206→1.92)
                    .processOrder(wip.getProcessOrder())
                    .processName(processName)
                    .unit(unit)
                    .stepYieldRate(stepYieldRate)
                    .cumulativeYieldRate(cumulativeYieldRate)
                    .build());
        }
        return result;
    }

    /**
     * Clerk process-sheet rows materialize WIP as MaterialBatch(sourceDoc=PRODUCTION_BATCH).
     * They do not write SemiFinishedInventory and their CLERK_WIP ProductionBatch rows
     * intentionally have no productionPlanId, so the yield card must use process_sheet_rows
     * as the plan-scoped source of truth.
     */
    private List<ProcessSheetInventoryItem> getInventoryYieldCardFromProcessSheetRows(
            String factoryId, List<ProcessSheetRow> sheetRows) {
        List<ProcessSheetRow> savedRows = sheetRows.stream()
                .filter(r -> r.getBatchId() != null)
                .sorted(Comparator
                        .comparing((ProcessSheetRow r) -> r.getProcessOrder() == null ? Integer.MAX_VALUE : r.getProcessOrder()))
                .toList();
        if (savedRows.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, ProcessSheetRowRequest> requestByBatchId = new HashMap<>();
        Map<Long, ProductionBatch> productionBatchById = productionBatchRepo.findAllById(
                        savedRows.stream().map(ProcessSheetRow::getBatchId).toList())
                .stream()
                .collect(Collectors.toMap(ProductionBatch::getId, b -> b, (a, b) -> a));

        // 兜底真实工序名 (按 productTypeId → order → 真实名): req 未存 processName 时, 避免显示"工序N"
        Map<String, Map<Integer, String>> nameByOrderByProduct = new HashMap<>();
        Map<String, BigDecimal> firstInputByProductType = new HashMap<>();
        Map<String, String> firstUnitByProductType = new HashMap<>();
        Map<String, BigDecimal> gramsPerUnitByProductType = new HashMap<>();
        Map<String, Integer> minProcessOrderByProductType = new HashMap<>();
        for (ProcessSheetRow row : savedRows) {
            ProcessSheetRowRequest req = tryDeserialize(row.getRowPayload());
            if (req == null) continue;
            requestByBatchId.put(row.getBatchId(), req);
            String productTypeId = req.getProductTypeId();
            if (productTypeId == null) continue;
            nameByOrderByProduct.computeIfAbsent(productTypeId, pid -> resolveProcessNamesByOrder(factoryId, pid));
            if (row.getProcessOrder() != null) {
                minProcessOrderByProductType.merge(productTypeId, row.getProcessOrder(), Math::min);
            }
            if (firstInputByProductType.containsKey(productTypeId)) continue;
            BigDecimal input = req.getInputQuantity();
            if (input != null && input.compareTo(BigDecimal.ZERO) > 0) {
                firstInputByProductType.put(productTypeId, input);
                firstUnitByProductType.put(productTypeId, requestInputUnit(req));
                gramsPerUnitByProductType.put(productTypeId, productTypeRepo.findById(productTypeId)
                        .map(pt -> pt.getGramsPerUnit())
                        .orElse(null));
            }
        }

        List<ProcessSheetInventoryItem> result = new ArrayList<>();
        Map<String, ProcessSheetRowProvenance> provenanceByBatchNumber = new LinkedHashMap<>();
        for (ProcessSheetRow row : savedRows) {
            ProcessSheetRowRequest req = requestByBatchId.get(row.getBatchId());
            if (req == null) continue;

            Optional<MaterialBatch> wipOpt = materialBatchRepo
                    .findByFactoryIdAndSourceDocTypeAndSourceDocId(
                            factoryId, "PRODUCTION_BATCH", row.getBatchId().toString());
            MaterialBatch wip = wipOpt.orElse(null);
            ProductionBatch productionBatch = productionBatchById.get(row.getBatchId());
            BigDecimal produced = firstPositive(
                    wip == null ? null : wip.getReceiptQuantity(),
                    req.getOutputQuantity(),
                    productionBatch == null ? null : productionBatch.getActualQuantity(),
                    productionBatch == null ? null : productionBatch.getQuantity());
            BigDecimal used = wip == null ? BigDecimal.ZERO : consumptionRepo
                    .findByFactoryIdAndBatchId(factoryId, wip.getId())
                    .stream()
                    .map(c -> nz(c.getQuantity()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal remaining = produced.subtract(used);
            String status = wip == null && req.isFinished()
                    ? "COMPLETED"
                    : (remaining.signum() <= 0 ? "DEPLETED" : "ACTIVE");

            String productTypeId = req.getProductTypeId();
            BigDecimal gramsPerUnit = productTypeId == null ? null : gramsPerUnitByProductType.get(productTypeId);
            BigDecimal input = req.getInputQuantity();
            BigDecimal stepYieldRate = null;
            if (input != null && input.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal comparableOutput = convertProcessSheetOutputToUnit(
                        req, produced, firstNonBlank(req.getOutputUnit(), req.getUnit()),
                        requestInputUnit(req), gramsPerUnit);
                if (comparableOutput != null) {
                    stepYieldRate = comparableOutput
                            .multiply(BigDecimal.valueOf(100))
                            .divide(input, YIELD_SCALE, RoundingMode.HALF_UP);
                }
            }

            BigDecimal firstInput = productTypeId == null ? null : firstInputByProductType.get(productTypeId);
            String firstUnit = productTypeId == null ? null : firstUnitByProductType.get(productTypeId);
            String unit = req.getUnit() != null
                    ? req.getUnit()
                    : (wip != null ? wip.getQuantityUnit() : (productionBatch == null ? null : productionBatch.getUnit()));
            // rowTotalCost 优先取持久化 productionBatch.totalCost (已 setScale(2));
            // 回退路径 wip.unitPrice × produced 也 setScale(2), 保证参与 addedCost 相减的
            // rowTotalCost 始终为 scale-2, 与 inheritedCost(逐边 scale-2) 同标度, addedCost 无噪音.
            BigDecimal rowTotalCost = firstPositiveOrNull(
                    productionBatch == null ? null : productionBatch.getTotalCost(),
                    wip == null || wip.getUnitPrice() == null ? null
                            : wip.getUnitPrice().multiply(produced).setScale(2, RoundingMode.HALF_UP));
            BigDecimal unitPrice = firstPositiveOrNull(
                    wip == null ? null : wip.getUnitPrice(),
                    productionBatch == null ? null : productionBatch.getUnitCost());
            if (unitPrice == null && rowTotalCost != null && produced.compareTo(BigDecimal.ZERO) > 0) {
                unitPrice = rowTotalCost.divide(produced, 4, RoundingMode.HALF_UP);
            }
            BigDecimal rawEquivalentInput = isFirstProcessSheetRow(row, productTypeId, minProcessOrderByProductType)
                    ? input
                    : firstInput;
            ProcessSheetRowProvenance rowProvenance = resolveSheetRowProvenance(
                    req, rawEquivalentInput, provenanceByBatchNumber);
            if (rowProvenance.inheritedCost != null
                    && (rowTotalCost == null || rowTotalCost.compareTo(rowProvenance.inheritedCost) < 0)) {
                rowTotalCost = rowProvenance.inheritedCost.setScale(2, RoundingMode.HALF_UP);
            }
            if (rowTotalCost != null && produced.compareTo(BigDecimal.ZERO) > 0) {
                unitPrice = rowTotalCost.divide(produced, 4, RoundingMode.HALF_UP);
            }

            BigDecimal cumulativeDenominator = firstPositiveOrNull(
                    rowProvenance.inheritedRawEquivalentQuantity,
                    hasUpstreamSources(req) ? null : firstInput);
            BigDecimal cumulativeYieldRate = null;
            if (cumulativeDenominator != null && cumulativeDenominator.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal producedConverted = convertProcessSheetOutputToUnit(
                        req, produced, unit, firstUnit, gramsPerUnit);
                if (producedConverted != null) {
                    cumulativeYieldRate = producedConverted
                            .multiply(BigDecimal.valueOf(100))
                            .divide(cumulativeDenominator, YIELD_SCALE, RoundingMode.HALF_UP);
                }
            }
            BigDecimal addedCost = rowTotalCost != null && rowProvenance.inheritedCost != null
                    ? rowTotalCost.subtract(rowProvenance.inheritedCost).max(BigDecimal.ZERO)
                    : null;

            result.add(ProcessSheetInventoryItem.builder()
                    .batchNumber(row.getBatchNumber())
                    .produced(produced)
                    .used(used)
                    .remaining(remaining)
                    .status(status)
                    .unitPrice(unitPrice)
                    .rowTotalCost(rowTotalCost)
                    .inputQuantity(input)
                    .freshRawInput(sumFreshRawInputs(req))
                    .sourceBatchNumber(rowProvenance.sourceBatchNumber)
                    .feedQuantity(rowProvenance.feedQuantity)
                    .sourceProducedQuantity(rowProvenance.sourceProducedQuantity)
                    .sourceConsumedRatio(rowProvenance.sourceConsumedRatio)
                    .inheritedRawEquivalentQuantity(rowProvenance.inheritedRawEquivalentQuantity)
                    .inheritedCost(rowProvenance.inheritedCost)
                    .addedCost(addedCost)
                    .sourceBreakdowns(rowProvenance.sourceBreakdowns)
                    .processDate(req.getProcessDate())
                    .processOrder(row.getProcessOrder())
                    .processName(resolveRowProcessName(req, row, nameByOrderByProduct))
                    .unit(unit)
                    .stepYieldRate(stepYieldRate)
                    .cumulativeYieldRate(cumulativeYieldRate)
                    .productWeight(req.getProductWeight())
                    .build());
            if (row.getBatchNumber() != null) {
                provenanceByBatchNumber.put(row.getBatchNumber(), new ProcessSheetRowProvenance(
                        row.getBatchNumber(),
                        produced,
                        null,
                        null,
                        null,
                        rowProvenance.inheritedRawEquivalentQuantity,
                        rowTotalCost,
                        unitPrice,
                        null,
                        null));
            }
        }
        return result;
    }

    private boolean isFirstProcessSheetRow(
            ProcessSheetRow row,
            String productTypeId,
            Map<String, Integer> minProcessOrderByProductType) {
        if (productTypeId == null) {
            return true;
        }
        Integer minOrder = minProcessOrderByProductType.get(productTypeId);
        if (minOrder == null) {
            return true;
        }
        return row.getProcessOrder() != null && row.getProcessOrder().equals(minOrder);
    }

    private ProcessSheetRowProvenance resolveSheetRowProvenance(
            ProcessSheetRowRequest req,
            BigDecimal firstInput,
            Map<String, ProcessSheetRowProvenance> provenanceByBatchNumber) {
        if (!hasUpstreamSources(req)) {
            return new ProcessSheetRowProvenance(
                    null, null, null, null, null, firstInput, null, null, null, null);
        }

        List<ProcessSheetInventoryItem.SourceBreakdown> sourceBreakdowns = new ArrayList<>();
        List<String> sourceBatchNumbers = new ArrayList<>();
        BigDecimal totalFeed = BigDecimal.ZERO;
        BigDecimal totalSourceProduced = BigDecimal.ZERO;
        BigDecimal totalInheritedRaw = BigDecimal.ZERO;
        BigDecimal totalInheritedCost = BigDecimal.ZERO;
        boolean hasInheritedRaw = false;
        boolean hasInheritedCost = false;

        for (ProcessSheetRowRequest.UpstreamRef upstream : req.getUpstreamSources()) {
            String sourceBatchNumber = upstream.getSourceBatchNumber();
            BigDecimal feedQuantity = nz(upstream.getFeedQuantityKg());
            ProcessSheetRowProvenance source = provenanceByBatchNumber.get(sourceBatchNumber);
            if (source == null || source.produced == null || source.produced.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal consumedRatio = feedQuantity
                    .multiply(BigDecimal.valueOf(100))
                    .divide(source.produced, YIELD_SCALE, RoundingMode.HALF_UP);
            BigDecimal inheritedRaw = null;
            if (source.inheritedRawEquivalentQuantity != null
                    && source.inheritedRawEquivalentQuantity.compareTo(BigDecimal.ZERO) > 0) {
                inheritedRaw = source.inheritedRawEquivalentQuantity
                        .multiply(feedQuantity)
                        .divide(source.produced, YIELD_SCALE, RoundingMode.HALF_UP);
                totalInheritedRaw = totalInheritedRaw.add(inheritedRaw);
                hasInheritedRaw = true;
            }

            BigDecimal inheritedCost = resolveInheritedSourceCost(source, feedQuantity);
            if (inheritedCost != null) {
                totalInheritedCost = totalInheritedCost.add(inheritedCost);
                hasInheritedCost = true;
            }

            sourceBatchNumbers.add(sourceBatchNumber);
            totalFeed = totalFeed.add(feedQuantity);
            totalSourceProduced = totalSourceProduced.add(source.produced);
            sourceBreakdowns.add(ProcessSheetInventoryItem.SourceBreakdown.builder()
                    .sourceBatchNumber(sourceBatchNumber)
                    .feedQuantity(feedQuantity)
                    .sourceProducedQuantity(source.produced)
                    .sourceConsumedRatio(consumedRatio)
                    .inheritedRawEquivalentQuantity(inheritedRaw)
                    .inheritedCost(inheritedCost)
                    .build());
        }

        BigDecimal aggregateConsumedRatio = totalSourceProduced.compareTo(BigDecimal.ZERO) > 0
                ? totalFeed.multiply(BigDecimal.valueOf(100))
                        .divide(totalSourceProduced, YIELD_SCALE, RoundingMode.HALF_UP)
                : null;
        return new ProcessSheetRowProvenance(
                sourceBatchNumbers.isEmpty() ? null : String.join(", ", sourceBatchNumbers),
                null,
                totalFeed.compareTo(BigDecimal.ZERO) > 0 ? totalFeed : null,
                totalSourceProduced.compareTo(BigDecimal.ZERO) > 0 ? totalSourceProduced : null,
                aggregateConsumedRatio,
                hasInheritedRaw ? totalInheritedRaw : null,
                null,
                null,
                hasInheritedCost ? totalInheritedCost : null,
                sourceBreakdowns.isEmpty() ? null : sourceBreakdowns);
    }

    private BigDecimal resolveInheritedSourceCost(ProcessSheetRowProvenance source, BigDecimal feedQuantity) {
        // 必须与 ClerkProcessEntryServiceImpl.materializeBatch 的消耗边成本口径逐边对齐:
        //   edgeCost = unitPrice.multiply(feedKg).setScale(2, HALF_UP)
        // 持久化侧每条消耗边都 setScale(2), 而本展示侧若保留全精度, 会导致
        // inheritedCost(全精度) 与 rowTotalCost(=Σ scale-2 边成本, scale-2) 混标度相减,
        // addedCost 出现负数/亚分级舍入噪音 (e.g. 1.92 - 1.9206 = -0.0006), 污染"0成本排查".
        // 这里逐边 setScale(2, HALF_UP) → inheritedCost 与持久化消耗成本逐边相等,
        // addedCost = rowTotalCost - inheritedCost 即真实新增成本(人工/调料), 恒 >= 0, 无噪音.
        if (source.unitPrice != null) {
            return source.unitPrice.multiply(feedQuantity).setScale(2, RoundingMode.HALF_UP);
        }
        if (source.rowTotalCost != null && source.produced != null && source.produced.compareTo(BigDecimal.ZERO) > 0) {
            return source.rowTotalCost.multiply(feedQuantity)
                    .divide(source.produced, 2, RoundingMode.HALF_UP);
        }
        return null;
    }

    private boolean hasUpstreamSources(ProcessSheetRowRequest req) {
        return req.getUpstreamSources() != null && !req.getUpstreamSources().isEmpty();
    }

    /**
     * 本道<b>新鲜原料</b>投入量(kg) = Σ rawMaterialInputs.quantity, 不含 SFI/成品投料 (①d 双计修复)。
     *
     * <p>非 null (无领料 → 0), 供出成率分母只计新鲜原料 (被复用半成品前段由 lineage 单独接入, 避免双计)。
     */
    private BigDecimal sumFreshRawInputs(ProcessSheetRowRequest req) {
        if (req == null || req.getRawMaterialInputs() == null) {
            return BigDecimal.ZERO;
        }
        return req.getRawMaterialInputs().stream()
                .map(ProcessSheetRowRequest.RawInput::getQuantity)
                .map(ProcessSheetServiceImpl::nz)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static class ProcessSheetRowProvenance {
        private final String sourceBatchNumber;
        private final BigDecimal produced;
        private final BigDecimal feedQuantity;
        private final BigDecimal sourceProducedQuantity;
        private final BigDecimal sourceConsumedRatio;
        private final BigDecimal inheritedRawEquivalentQuantity;
        private final BigDecimal rowTotalCost;
        private final BigDecimal unitPrice;
        private final BigDecimal inheritedCost;
        private final List<ProcessSheetInventoryItem.SourceBreakdown> sourceBreakdowns;

        private ProcessSheetRowProvenance(
                String sourceBatchNumber,
                BigDecimal produced,
                BigDecimal feedQuantity,
                BigDecimal sourceProducedQuantity,
                BigDecimal sourceConsumedRatio,
                BigDecimal inheritedRawEquivalentQuantity,
                BigDecimal rowTotalCost,
                BigDecimal unitPrice,
                BigDecimal inheritedCost,
                List<ProcessSheetInventoryItem.SourceBreakdown> sourceBreakdowns) {
            this.sourceBatchNumber = sourceBatchNumber;
            this.produced = produced;
            this.feedQuantity = feedQuantity;
            this.sourceProducedQuantity = sourceProducedQuantity;
            this.sourceConsumedRatio = sourceConsumedRatio;
            this.inheritedRawEquivalentQuantity = inheritedRawEquivalentQuantity;
            this.rowTotalCost = rowTotalCost;
            this.unitPrice = unitPrice;
            this.inheritedCost = inheritedCost;
            this.sourceBreakdowns = sourceBreakdowns;
        }
    }

    private BigDecimal firstPositive(BigDecimal... values) {
        BigDecimal value = firstPositiveOrNull(values);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal firstPositiveOrNull(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
                return value;
            }
        }
        return null;
    }

    private String fallbackProcessName(ProcessSheetRow row) {
        return row.getProcessOrder() == null ? row.getProcessCode() : "工序" + row.getProcessOrder();
    }

    /**
     * 出成率卡行的工序名: req 存了名就用; 否则按 productTypeId+order 反查真实名 (拆包/修油/熟制…);
     * 都取不到才退到 "工序N"。避免逐道录入未存 processName 时整列显示乱码工序名。
     */
    private String resolveRowProcessName(ProcessSheetRowRequest req, ProcessSheetRow row,
                                         Map<String, Map<Integer, String>> nameByOrderByProduct) {
        if (req != null && req.getProcessName() != null && !req.getProcessName().isBlank()) {
            return req.getProcessName();
        }
        if (req != null && req.getProductTypeId() != null && row.getProcessOrder() != null) {
            Map<Integer, String> byOrder = nameByOrderByProduct.get(req.getProductTypeId());
            if (byOrder != null) {
                String name = byOrder.get(row.getProcessOrder());
                if (name != null) {
                    return name;
                }
            }
        }
        return fallbackProcessName(row);
    }

    /**
     * 解析每个批次首道工序 YIELD 报工的原料投入量 (Σ inputQuantity, processOrder = min).
     * key = batchId, value = 首道 Σ inputQuantity (null = 无报工).
     */
    private Map<Long, BigDecimal> resolveFirstStepInputPerBatch(String factoryId, List<Long> batchIds) {
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Long batchId : batchIds) {
            List<ProductionReport> reports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
            if (reports.isEmpty()) {
                result.put(batchId, null);
                continue;
            }
            // 找最小 processOrder
            int minOrder = reports.stream()
                    .mapToInt(r -> r.getProcessOrder() == null ? 0 : r.getProcessOrder())
                    .min().orElse(0);
            BigDecimal sumInput = reports.stream()
                    .filter(r -> (r.getProcessOrder() == null ? 0 : r.getProcessOrder()) == minOrder)
                    .map(r -> r.getInputQuantity() == null ? BigDecimal.ZERO : r.getInputQuantity())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            result.put(batchId, sumInput.compareTo(BigDecimal.ZERO) == 0 ? null : sumInput);
        }
        return result;
    }

    /** 回填 taskId → processName (避 N+1 查询). */
    private Map<Long, String> resolveProcessNames(String factoryId,
                                                   List<SemiFinishedInventory> wips) {
        Set<Long> taskIds = wips.stream()
                .map(SemiFinishedInventory::getSourceWorkProcessTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (taskIds.isEmpty()) {
            return new HashMap<>();
        }
        Map<Long, String> taskToProcessId = taskRepo.findByFactoryIdAndIdIn(factoryId, taskIds)
                .stream()
                .filter(t -> t.getWorkProcessId() != null)
                .collect(Collectors.toMap(WorkProcessTask::getId, WorkProcessTask::getWorkProcessId, (a, b) -> a));
        Map<String, String> pidToName = processRepo.findAllById(new java.util.HashSet<>(taskToProcessId.values()))
                .stream()
                .collect(Collectors.toMap(WorkProcess::getId, WorkProcess::getProcessName, (a, b) -> a));
        Map<Long, String> out = new HashMap<>();
        taskToProcessId.forEach((tid, pid) -> {
            String name = pidToName.get(pid);
            if (name != null) out.put(tid, name);
        });
        return out;
    }

    /**
     * 兜底: processOrder → 真实工序名 (来自 product-work-process 链).
     * 当 WIP 未关联 WorkProcessTask (如复制工序链新建的 SKU) 时, taskId→name 取不到, 出成率卡会显示"工序N";
     * 用本图按 processOrder 反查真实工序名 (拆包/修油/熟制…), 避免乱码工序名。
     */
    private Map<Integer, String> resolveProcessNamesByOrder(String factoryId, String productTypeId) {
        if (productTypeId == null) {
            return new HashMap<>();
        }
        List<ProductWorkProcess> pwps =
                productWorkProcessRepo.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, productTypeId);
        if (pwps.isEmpty()) {
            return new HashMap<>();
        }
        Set<String> wpIds = pwps.stream()
                .map(ProductWorkProcess::getWorkProcessId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> wpName = processRepo.findAllById(wpIds).stream()
                .collect(Collectors.toMap(WorkProcess::getId, WorkProcess::getProcessName, (a, b) -> a));
        Map<Integer, String> out = new HashMap<>();
        for (ProductWorkProcess pwp : pwps) {
            if (pwp.getProcessOrder() != null && pwp.getWorkProcessId() != null) {
                String name = wpName.get(pwp.getWorkProcessId());
                if (name != null) {
                    out.put(pwp.getProcessOrder(), name);
                }
            }
        }
        return out;
    }

    /**
     * 解析折算系数 gramsPerUnit (g/份): 取末道有 productTypeId 的 WIP 的 ProductType.gramsPerUnit.
     * null = 无法折算 (同单位无需折算; 或跨单位无系数).
     */
    private BigDecimal resolveGramsPerUnit(String factoryId, List<SemiFinishedInventory> wips) {
        // 从末道往前找有 productTypeId 的行
        for (int i = wips.size() - 1; i >= 0; i--) {
            String ptId = wips.get(i).getProductTypeId();
            if (ptId != null) {
                return productTypeRepo.findById(ptId)
                        .map(pt -> pt.getGramsPerUnit())
                        .orElse(null);
            }
        }
        return null;
    }

    /**
     * 将 producedQty 折算为首道单位.
     * 同单位: 直接返回 produced.
     * 跨单位 (份/盒 → kg): produced × gramsPerUnit / 1000.
     * gramsPerUnit 为 null: 返回 null (无法折算, cumulativeYieldRate 留 null).
     */
    private BigDecimal convertToFirstStepUnit(BigDecimal produced, String currentUnit,
                                               String firstUnit, BigDecimal gramsPerUnit) {
        if (produced == null) return BigDecimal.ZERO;
        if (currentUnit == null || firstUnit == null || currentUnit.equals(firstUnit)) {
            return produced;
        }
        // 跨单位: 需要 gramsPerUnit
        if (gramsPerUnit == null || gramsPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        // 份/盒 → kg: qty × gramsPerUnit / 1000
        return produced.multiply(gramsPerUnit)
                .divide(BigDecimal.valueOf(1000), YIELD_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal convertProcessSheetOutputToUnit(
            ProcessSheetRowRequest request,
            BigDecimal produced,
            String currentUnit,
            String targetUnit,
            BigDecimal gramsPerUnit) {
        BigDecimal productWeightKg = request == null ? null : request.getProductWeight();
        if (productWeightKg != null && productWeightKg.compareTo(BigDecimal.ZERO) > 0 && targetUnit != null) {
            String normalizedTarget = targetUnit.trim().toLowerCase(java.util.Locale.ROOT);
            if ("kg".equals(normalizedTarget) || "千克".equals(normalizedTarget) || "公斤".equals(normalizedTarget)) {
                return productWeightKg;
            }
            if ("g".equals(normalizedTarget) || "克".equals(normalizedTarget)) {
                return productWeightKg.multiply(BigDecimal.valueOf(1000));
            }
            if ("mg".equals(normalizedTarget) || "毫克".equals(normalizedTarget)) {
                return productWeightKg.multiply(BigDecimal.valueOf(1_000_000));
            }
        }
        return convertToFirstStepUnit(produced, currentUnit, targetUnit, gramsPerUnit);
    }

    // ─────────────────────────────────────────────────────────────
    // 已保存行列表读取 (Task 2.2)
    // ─────────────────────────────────────────────────────────────

    /**
     * SP-F Task 2.2: 读回指定工序下已保存的行列表。
     *
     * <p>查询 factory-scoped 🔒 (rowRepo 三键 factory+plan+processCode); 同时返回 SAVED 与 DRAFT 行。
     * row_payload 经 objectMapper 反序列化为 ProcessSheetRowRequest, 序列化失败 → 500。
     */
    @Override
    public List<ProcessSheetRowView> getRows(String factoryId, String planId,
                                             String processCode, Integer processOrder) {
        // SP-F role-mode fix: processOrder 非空 → 双键过滤; null → code-only 回退 (向后兼容)。
        List<ProcessSheetRow> rows = processOrder != null
                ? rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndProcessOrder(
                        factoryId, planId, processCode, processOrder)
                : rowRepo.findByFactoryIdAndPlanIdAndProcessCode(factoryId, planId, processCode);
        return rows.stream()
                .map(row -> new ProcessSheetRowView(
                    row.getClientRowId(),
                    row.getBatchNumber(),
                    row.getBatchId(),
                    row.getRowStatus(),
                    row.getSubmissionStatus(),
                    row.getBatchId() != null,
                    deserializePayload(row.getRowPayload()),
                    row.getInterimSettledAt()))
                .toList();
    }

    private ProcessSheetRowRequest deserializePayload(String json) {
        try {
            return objectMapper.readValue(json, ProcessSheetRowRequest.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "行数据反序列化失败: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // SP-G P3: 行级操作记录 (字段级 diff 审计)
    // ─────────────────────────────────────────────────────────────

    /**
     * SP-G P3: 读取某一行的操作记录时间线 (按创建时间倒序)。查询 factory-scoped 🔒。
     */
    @Override
    public List<ProcessSheetRowHistoryView> getRowHistory(String factoryId, String planId,
                                                          String processCode, String clientRowId) {
        return changeLogRepo
                .findByFactoryIdAndPlanIdAndProcessCodeAndClientRowIdOrderByCreatedAtDesc(
                        factoryId, planId, processCode, clientRowId)
                .stream()
                .map(log -> new ProcessSheetRowHistoryView(
                        log.getId(),
                        log.getOperation(),
                        log.getBeforeValue(),
                        log.getAfterValue(),
                        log.getDiffSummary(),
                        log.getOperatorId(),
                        log.getCreatedAt()))
                .toList();
    }

    /**
     * 写一条行级操作记录 (CREATE/UPDATE/DELETE)。processCode/clientRowId 取自请求体。
     * 用于 saveRow / resaveRow (req 即变更目标行)。
     */
    private void logChange(String factoryId, String planId, ProcessSheetRowRequest keyReq,
                           String operation, ProcessSheetRowRequest before,
                           ProcessSheetRowRequest after, Long userId) {
        logChange(factoryId, planId, keyReq, operation, before, after, userId,
                keyReq.getProcessCode(), keyReq.getClientRowId());
    }

    /**
     * 写一条行级操作记录, 显式指定 processCode/clientRowId (deleteRow 走此重载, key 取自被删行)。
     *
     * <p>before/after 各序列化为字段快照 Map; diffSummary 比对快照列出变更字段 ("字段: 旧→新")。
     * 审计失败不应阻断主写路径 —— 包 try/catch 仅记 warn。
     */
    private void logChange(String factoryId, String planId, ProcessSheetRowRequest keyReq,
                           String operation, ProcessSheetRowRequest before,
                           ProcessSheetRowRequest after, Long userId,
                           String processCode, String clientRowId) {
        try {
            Map<String, Object> beforeMap = snapshot(before);
            Map<String, Object> afterMap = snapshot(after);
            ProcessSheetRowChangeLog logEntry = ProcessSheetRowChangeLog.builder()
                    .factoryId(factoryId)
                    .planId(planId)
                    .processCode(processCode)
                    .clientRowId(clientRowId)
                    .operation(operation)
                    .beforeValue(beforeMap)
                    .afterValue(afterMap)
                    .diffSummary(buildDiffSummary(operation, beforeMap, afterMap))
                    .operatorId(userId)
                    .build();
            changeLogRepo.save(logEntry);
        } catch (Exception e) {
            // 操作记录是旁路审计, 失败不阻断主流程 (行已成功写入)。
            log.warn("写行级操作记录失败 (operation={}, plan={}, process={}, row={}): {}",
                    operation, planId, processCode, clientRowId, e.getMessage());
        }
    }

    /** payload → 字段快照 Map (null → null)。用 ObjectMapper 转 LinkedHashMap 保字段序。 */
    private Map<String, Object> snapshot(ProcessSheetRowRequest req) {
        if (req == null) {
            return null;
        }
        return objectMapper.convertValue(req, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    /** 容错反序列化 row_payload (审计前快照; 失败返 null, 不阻断主流程)。 */
    private ProcessSheetRowRequest tryDeserialize(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ProcessSheetRowRequest.class);
        } catch (JsonProcessingException e) {
            log.warn("操作记录 before 快照反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构造人类可读变更摘要。
     * <ul>
     *   <li>CREATE: "新建行"</li>
     *   <li>DELETE: "删除行"</li>
     *   <li>UPDATE: 对比 before/after 快照, 列出变更字段 "字段: 旧→新" (分号分隔); 无变更 → "(无字段变更)"</li>
     * </ul>
     */
    private String buildDiffSummary(String operation, Map<String, Object> before,
                                    Map<String, Object> after) {
        if ("CREATE".equals(operation)) {
            return "新建行";
        }
        if ("DELETE".equals(operation)) {
            return "删除行";
        }
        // UPDATE: 比对 before/after 快照的并集 key。
        Map<String, Object> b = before != null ? before : Map.of();
        Map<String, Object> a = after != null ? after : Map.of();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(b.keySet());
        keys.addAll(a.keySet());

        List<String> changes = new ArrayList<>();
        for (String key : keys) {
            Object bv = b.get(key);
            Object av = a.get(key);
            if (!Objects.equals(bv, av)) {
                changes.add(key + ": " + fmt(bv) + "→" + fmt(av));
            }
        }
        return changes.isEmpty() ? "(无字段变更)" : String.join("; ", changes);
    }

    /** 格式化 diff 值: null → "空", 其余 toString (集合/嵌套对象保留 JSON-ish 结构)。 */
    private String fmt(Object v) {
        return v == null ? "空" : String.valueOf(v);
    }

    /**
     * 逆向物化共用逻辑 (CASE B1 + deleteRow 共享): 软删消耗边 + 报工 + WIP MaterialBatch + ProductionBatch。
     * 调用前必须已完成下游消耗守卫检查 (有下游则不调用此方法)。
     *
     * @param factoryId 工厂 ID
     * @param batchId   ProductionBatch.id (非 null)
     * @param wipOpt    已查到的 WIP MaterialBatch (可能 absent: 物化异常的边缘情形)
     */
    private void reverseMaterialization(String factoryId, Long batchId,
                                        Optional<MaterialBatch> wipOpt) {
        // 软删消耗边 + 报工
        consumptionRepo.softDeleteByFactoryIdAndProductionBatchId(factoryId, batchId);
        reportRepo.softDeleteByFactoryIdAndBatchId(factoryId, batchId);

        // 软删 WIP MaterialBatch + ProductionBatch
        wipOpt.ifPresent(wip -> {
            wip.softDelete();
            materialBatchRepo.save(wip);
        });
        productionBatchRepo.findByIdAndFactoryId(batchId, factoryId)
                .ifPresent(pb -> {
                    pb.softDelete();
                    productionBatchRepo.save(pb);
                });
    }

    // ─────────────────────────────────────────────────────────────
    // Edge resolution (factory-scoped 🔒)
    // ─────────────────────────────────────────────────────────────

    private List<ResolvedEdge> resolveEdges(String factoryId, String planId, ProcessSheetRowRequest req) {
        List<ResolvedEdge> edges = new ArrayList<>();

        // 原料边 (修油首道领料) — factory-scoped raw MaterialBatch
        if (req.getRawMaterialInputs() != null) {
            for (ProcessSheetRowRequest.RawInput ri : req.getRawMaterialInputs()) {
                MaterialBatch rawMb = materialBatchRepo
                        .findByIdAndFactoryId(ri.getMaterialBatchId(), factoryId)
                        .orElseThrow(() -> new BusinessException(404,
                                "原料批次不存在: " + ri.getMaterialBatchId()));
                BigDecimal storageQuantity = convertReportingQuantityToStorage(
                        nz(ri.getQuantity()),
                        firstNonBlank(ri.getUnit(), requestInputUnit(req)),
                        rawMb.getQuantityUnit(),
                        "原料批次");
                ensureRawMaterialWarehouse(factoryId, planId, rawMb);
                edges.add(new ResolvedEdge(
                        rawMb,
                        storageQuantity,
                        firstNonBlank(ri.getSourceType(), "RAW_MATERIAL")));
            }
        }

        // 混锅上游边 (SEMI_FINISHED) — 经持久化 batchNumber 解析上游 WIP MaterialBatch
        if (req.getUpstreamSources() != null) {
            for (ProcessSheetRowRequest.UpstreamRef ur : req.getUpstreamSources()) {
                // ①c 成品库存(FG)投料 (成品作投料来源): 与 SFI 同理不解析为 in-plan WIP MaterialBatch,
                //   不写 MaterialConsumption。投料随 row_payload 持久化, FG 扣减在小结经
                //   FinishedGoodsFeedService.consumeForFeedStrict(batchNumber) 完成 (见 InterimSettleServiceImpl)。
                //   禁止降级 + 防呆: FG 引用必须指向真实存在的成品批次 (factory-scoped 🔒), 否则保存即 loud-fail。
                if (ur.isFinishedGoods()) {
                    com.cretas.aims.entity.inventory.FinishedGoodsBatch source =
                            finishedGoodsBatchRepo.findByFactoryIdAndBatchNumber(factoryId, ur.getSourceBatchNumber())
                            .orElseThrow(() -> new BusinessException(409,
                                    "成品库存不存在: " + ur.getSourceBatchNumber())
                                    .withCode("FG_NOT_FOUND")
                                    .withHint("请重新选择仍有库存的成品批次")
                                    .withSeverity("BLOCKING")
                                    .withHintTarget(req.getProcessCode()));
                    assertWorkflowSourceSku(ur.getSourceBatchNumber(), ur.getSkuId(), source.getProductTypeId());
                    continue;
                }
                // 半成品库存(SFI)投料 (半成品直接产成品): 不解析为 in-plan WIP MaterialBatch,
                //   不写 MaterialConsumption (material_consumptions.batch_id NOT NULL 只能持 MaterialBatch id,
                //   SFI 无对应 MaterialBatch)。投料随 row_payload 持久化, SFI 扣减在小结时经
                //   consumeClerkSemiStrict(intermediateBatchNo) 完成 (见 InterimSettleServiceImpl ② SFI OUT)。
                //   inputQuantity 仍由 buildStepEntry 记录, 出成率计算不受影响。
                if (ur.isSemiFinished()) {
                    // 禁止降级 + 防呆: SFI 引用必须指向真实存在的常驻半成品库存行 (factory-scoped 🔒),
                    //   否则保存即 loud-fail —— 不留到小结才静默 no-op 产 phantom 成品。
                    SemiFinishedInventory source = wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                                    factoryId, ur.getSourceBatchNumber())
                            .orElseThrow(() -> new BusinessException(409,
                                    "半成品库存不存在: " + ur.getSourceBatchNumber())
                                    .withCode("SFI_NOT_FOUND")
                                    .withHint("请重新选择仍有库存的半成品批次")
                                    .withSeverity("BLOCKING")
                                    .withHintTarget(req.getProcessCode()));
                    assertWorkflowSourceSku(ur.getSourceBatchNumber(), ur.getSkuId(), source.getProductTypeId());
                    continue;
                }
                assertWorkflowSourceSku(
                        ur.getSourceBatchNumber(),
                        ur.getSkuId(),
                        resolveInPlanSourceProductTypeId(factoryId, planId, ur.getSourceBatchNumber()));
                ProductionBatch pb = productionBatchRepo
                        .findByFactoryIdAndBatchNumber(factoryId, ur.getSourceBatchNumber())
                        .orElseThrow(() -> new BusinessException(409,
                                "上游批次 " + ur.getSourceBatchNumber() + " 不存在"));
                MaterialBatch srcMb = materialBatchRepo
                        .findByFactoryIdAndSourceDocTypeAndSourceDocId(
                                factoryId, "PRODUCTION_BATCH", pb.getId().toString())
                        .orElseThrow(() -> new BusinessException(409,
                                "上游批次 " + ur.getSourceBatchNumber() + " 尚未物化半成品 (无 WIP 库存)"));
                // 防御: 双重确认 factory 归属 (findBy* 已 factory-scoped, 这里二次断言)
                if (!factoryId.equals(srcMb.getFactoryId())) {
                    throw new BusinessException(403, "无权访问上游批次 " + ur.getSourceBatchNumber());
                }
                BigDecimal storageQuantity = convertReportingQuantityToStorage(
                        nz(ur.getFeedQuantityKg()), requestInputUnit(req), srcMb.getQuantityUnit(), "上游批次");
                BigDecimal resolvedUnitPrice = srcMb.getUnitPrice();
                if (resolvedUnitPrice == null
                        && pb.getTotalCost() != null && pb.getTotalCost().compareTo(BigDecimal.ZERO) > 0
                        && srcMb.getReceiptQuantity() != null
                        && srcMb.getReceiptQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    resolvedUnitPrice = pb.getTotalCost()
                            .divide(srcMb.getReceiptQuantity(), 4, RoundingMode.HALF_UP);
                }
                edges.add(new ResolvedEdge(srcMb, storageQuantity, "SEMI_FINISHED", resolvedUnitPrice));
            }
        }

        return edges;
    }

    /**
     * 普通 WIP 必须来自当前计划的已物化报工行；SKU 真值读取服务端 row payload，
     * 不能相信客户端 UpstreamRef.skuId。
     */
    private String resolveInPlanSourceProductTypeId(String factoryId, String planId, String sourceBatchNumber) {
        List<ProcessSheetRow> planRows = rowRepo.findByFactoryIdAndPlanId(factoryId, planId);
        ProcessSheetRow sourceRow = (planRows == null ? List.<ProcessSheetRow>of() : planRows).stream()
                .filter(row -> Objects.equals(sourceBatchNumber, row.getBatchNumber()))
                .filter(row -> !ProcessSheetRow.SUBMISSION_DRAFT.equals(row.getSubmissionStatus()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(409,
                        "上游批次 " + sourceBatchNumber + " 不是当前计划已报工的有效产出")
                        .withCode("PROCESS_SHEET_SOURCE_BATCH_INVALID")
                        .withHint("请重新选择当前计划中已正式报工的上游批次")
                        .withSeverity("BLOCKING"));
        ProcessSheetRowRequest sourceRequest = parsePayloadQuiet(sourceRow.getRowPayload());
        return sourceRequest == null ? null : sourceRequest.getProductTypeId();
    }

    private static void assertWorkflowSourceSku(
            String sourceBatchNumber, String expectedSku, String actualSku) {
        // 非 Workflow legacy 请求没有端口 SKU，保持既有兼容；Workflow 请求在前置端口校验后必有 expectedSku。
        if (expectedSku == null || expectedSku.isBlank()) {
            return;
        }
        if (!expectedSku.equals(actualSku)) {
            throw new BusinessException(409,
                    "来源批次 " + sourceBatchNumber + " 的实际 SKU "
                            + (actualSku == null || actualSku.isBlank() ? "<缺失>" : actualSku)
                            + " 与 Workflow 端口要求 SKU " + expectedSku + " 不一致")
                    .withCode("PROCESS_SHEET_SOURCE_SKU_MISMATCH")
                    .withHint("请重新选择与投入端口 SKU 一致的库存批次")
                    .withSeverity("BLOCKING")
                    .withHintTarget("来源批次");
        }
    }

    private void normalizeConfiguredUnits(String factoryId, String planId, ProcessSheetRowRequest req) {
        // Workflow 批次的运行时端口是唯一单位事实源。即使该产品仍保留 legacy
        // ProductWorkProcess，也不能让旧配置抢先覆盖批次已锁定的端口快照。
        if (applyWorkflowConfiguredUnits(factoryId, planId, req)) {
            return;
        }

        Optional<ProductWorkProcess> configured = productWorkProcessRepo
                .findByFactoryIdAndProductTypeIdAndProcessOrder(
                        factoryId, req.getProductTypeId(), req.getProcessOrder());
        if (configured.isEmpty()) {
            // Legacy clients only sent one `unit` field. Preserve that established single-unit
            // contract, but do not allow a new dual-unit payload to bypass configuration.
            if (req.getInputUnit() == null && req.getOutputUnit() == null) {
                String legacyUnit = firstNonBlank(req.getUnit(), "kg");
                req.setInputUnit(legacyUnit);
                req.setOutputUnit(legacyUnit);
                req.setUnit(legacyUnit);
                applyLegacyFinishedWeight(factoryId, planId, req);
                return;
            }
            throw new BusinessException(400, "产品未配置该工序，不能报工")
                    .withCode("PROCESS_SHEET_PROCESS_NOT_CONFIGURED")
                    .withHint("请先在产品工序配置中维护本道工序和单位")
                    .withHintTarget("工序配置");
        }
        ProductWorkProcess pwp = configured.get();
        WorkProcess process = processRepo.findByFactoryIdAndId(factoryId, pwp.getWorkProcessId())
                .orElseThrow(() -> new BusinessException(409, "工序配置不存在，不能报工")
                        .withCode("PROCESS_SHEET_WORK_PROCESS_NOT_FOUND")
                        .withHint("请检查产品工序配置后重试")
                        .withHintTarget("工序配置"));
        normalizeConfiguredUnits(req, pwp, process);
        applyLegacyFinishedWeight(factoryId, planId, req);
    }

    private void applyLegacyFinishedWeight(
            String factoryId, String planId, ProcessSheetRowRequest req) {
        if (!req.isFinished() || req.getOutputQuantity() == null) return;
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new BusinessException(403, "无权访问该计划"));
        String outputUnit = firstNonBlank(req.getOutputUnit(), req.getUnit(), plan.getPlannedUnit());
        if (plan.getPlannedUnit() != null
                && !plan.getPlannedUnit().equalsIgnoreCase(outputUnit)) {
            throw new BusinessException(409, "成品报工单位与计划快照不一致")
                    .withCode("PROCESS_SHEET_PLAN_UNIT_MISMATCH")
                    .withSeverity("BLOCKING");
        }
        req.setProductWeight(authoritativeFinishedWeight(
                req.getOutputQuantity(), outputUnit, plan.getPlannedNetWeightGrams()));
    }

    /**
     * 2B (clerk-path workflow 联通): workflow 计划的产品(尤其成品)没有 legacy ProductWorkProcess 配置,
     * 但报工单位需要以 workflow 端口投影为准 —— 例如成品包装工序 kg(半成品) → 盒(成品) 的换算行。
     *
     * <p>命中当前 processOrder 的 workflow 描述符即用其投入/产出端口单位归一化 req, 返回 {@code true}。
     * 只有服务明确返回 {@code null}（计划没有 WORKFLOW 批次）才返回 {@code false} 并进入 legacy。
     * WORKFLOW 批次的快照、工序或端口异常一律阻断，禁止静默降级。
     */
    private boolean applyWorkflowConfiguredUnits(String factoryId, String planId, ProcessSheetRowRequest req) {
        if (workflowClerkSheetService == null || planId == null || req.getProcessOrder() == null) {
            return false;
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO config;
        try {
            config = workflowClerkSheetService.getWorkflowSheetConfig(factoryId, planId);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Workflow 单位配置读取失败: factory={}, plan={}", factoryId, planId, e);
            throw new BusinessException(409, "Workflow 运行时配置读取失败，不能按旧工序配置报工")
                    .withCode("PROCESS_SHEET_WORKFLOW_CONFIG_UNAVAILABLE")
                    .withHint("请刷新后重试；若仍失败，请重新物化该生产批次的 Workflow")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        if (config == null) {
            return false; // 明确非 workflow 计划 → legacy 分支
        }
        if (config.getProcesses() == null || config.getProcesses().isEmpty()) {
            throw new BusinessException(409, "Workflow 运行时没有可报工工序")
                    .withCode("PROCESS_SHEET_WORKFLOW_PROCESSES_MISSING")
                    .withHint("请重新物化该生产批次的 Workflow")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.ProcessDescriptor descriptor = config.getProcesses().stream()
                .filter(p -> req.getProcessOrder().equals(p.getProcessOrder()))
                .findFirst()
                .orElse(null);
        if (descriptor == null) {
            throw new BusinessException(409, "请求工序不在该批次锁定的 Workflow 中")
                    .withCode("PROCESS_SHEET_WORKFLOW_PROCESS_NOT_FOUND")
                    .withHint("请刷新逐道录入页面，按 Workflow 中的工序报工")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        if (descriptor.getOutput() == null) {
            throw new BusinessException(409, "Workflow 工序缺少产出端口，不能报工")
                    .withCode("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_MISSING")
                    .withHint("请修复并重新发布 Workflow 后创建新批次")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        WorkflowClerkSheetConfigDTO.PortDescriptor selectedOutput =
                resolveWorkflowOutputPort(descriptor, req.getWorkflowPortId());
        req.setWorkflowPortId(selectedOutput.getWorkflowPortId());
        if (req.getMaterialNodeId() == null) {
            req.setMaterialNodeId(selectedOutput.getMaterialNodeId());
        }
        String outputUnit = requireWorkflowPortUnit(selectedOutput.getUnit(), "产出");

        // 与 ProductWorkProcess 路径同语义: 请求单位若提供则必须匹配端口, 否则按端口单位归一化。
        applyWorkflowInputPorts(factoryId, descriptor, req, false);
        assertConfiguredUnit(factoryId, req.getOutputUnit(), outputUnit, "产出");
        assertConfiguredUnit(factoryId, req.getUnit(), outputUnit, "产出");

        req.setOutputUnit(outputUnit);
        req.setUnit(outputUnit);
        applyAuthoritativeFinishedWeight(req, selectedOutput);
        return true;
    }

    private static void applyAuthoritativeFinishedWeight(
            ProcessSheetRowRequest req,
            com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor port) {
        if (!Boolean.TRUE.equals(port.getFinished()) || req.getOutputQuantity() == null) return;
        req.setProductWeight(authoritativeFinishedWeight(
                req.getOutputQuantity(), port.getUnit(), port.getGramsPerUnit()));
    }

    private static void applyAuthoritativeFinishedWeight(
            ProcessSheetRowRequest.OutputLine output,
            com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor port) {
        if (!Boolean.TRUE.equals(port.getFinished()) || output.getQuantity() == null) return;
        output.setProductWeight(authoritativeFinishedWeight(
                output.getQuantity(), port.getUnit(), port.getGramsPerUnit()));
    }

    private static BigDecimal authoritativeFinishedWeight(
            BigDecimal quantity, String unit, BigDecimal gramsPerUnit) {
        String normalized = unit == null ? "" : unit.trim().toLowerCase(java.util.Locale.ROOT);
        if ("kg".equals(normalized) || "千克".equals(normalized) || "公斤".equals(normalized)) {
            return quantity;
        }
        if ("g".equals(normalized) || "克".equals(normalized)) {
            return quantity.divide(BigDecimal.valueOf(1000));
        }
        if (gramsPerUnit == null || gramsPerUnit.signum() <= 0) {
            throw new BusinessException(409, "成品缺少单位净重快照，不能计算成品重量")
                    .withCode("FINISHED_SKU_NET_WEIGHT_SNAPSHOT_MISSING")
                    .withHint("请在 SKU 管理中补齐标准单位换算，并创建带完整快照的新计划")
                    .withSeverity("BLOCKING")
                    .withHintTarget("标准单位换算");
        }
        return quantity.multiply(gramsPerUnit).divide(BigDecimal.valueOf(1000));
    }

    private static String workflowInputUnit(
            com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.ProcessDescriptor descriptor) {
        if (descriptor.getInputs() == null || descriptor.getInputs().isEmpty()) {
            throw new BusinessException(409, "Workflow 工序缺少投入端口，不能确定投入单位")
                    .withCode("PROCESS_SHEET_WORKFLOW_INPUT_PORT_MISSING")
                    .withHint("请修复并重新发布 Workflow 后创建新批次")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        List<String> units = descriptor.getInputs().stream()
                .map(port -> requireWorkflowPortUnit(port.getUnit(), "投入"))
                .distinct()
                .toList();
        if (units.size() != 1) {
            throw new BusinessException(409, "Workflow 本道工序包含不同投入单位，当前逐道录入无法安全合并")
                    .withCode("PROCESS_SHEET_WORKFLOW_MIXED_INPUT_UNITS")
                    .withHint("请拆分工序，或将同一道工序的投入端口统一为相同单位")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        return units.get(0);
    }

    /**
     * Workflow 投入端口是单位/SKU 真值。单端口 legacy 请求可省略端口 id；多端口请求必须逐项携带
     * workflowPortId，且每个必填端口恰好出现。所有实际消费数量在内部按 kg 结算，g/kg 展示单位只在
     * 分配边界转换，绝不允许客户端覆盖端口单位。
     */
    private void applyWorkflowInputPorts(
            String factoryId,
            com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.ProcessDescriptor descriptor,
            ProcessSheetRowRequest req,
            boolean requireCompleteSelections) {
        List<com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor> ports =
                descriptor.getInputs();
        if (ports == null || ports.isEmpty()) {
            throw new BusinessException(409, "Workflow 工序缺少投入端口，不能确定投入单位")
                    .withCode("PROCESS_SHEET_WORKFLOW_INPUT_PORT_MISSING")
                    .withHint("请修复并重新发布 Workflow 后创建新批次")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        boolean hasStructuredInputs = (req.getMaterialInputTotals() != null && !req.getMaterialInputTotals().isEmpty())
                || (req.getRawMaterialInputs() != null && !req.getRawMaterialInputs().isEmpty())
                || (req.getUpstreamSources() != null && !req.getUpstreamSources().isEmpty());
        if (!hasStructuredInputs) {
            if (requireCompleteSelections) {
                validatePortSelections(ports, Set.of(), "INPUT");
            } else {
                // Draft/unit normalization can arrive before the operator selects concrete
                // ports. The workflow still owns the unit contract when every candidate
                // input uses the same reporting unit.
                req.setInputUnit(workflowInputUnit(descriptor));
            }
            return;
        }

        Map<String, com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor> byId =
                new LinkedHashMap<>();
        for (com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor port : ports) {
            if (port.getWorkflowPortId() == null || port.getWorkflowPortId().isBlank()) {
                throw new BusinessException(409, "Workflow 投入端口缺少稳定标识")
                        .withCode("PROCESS_SHEET_WORKFLOW_INPUT_PORT_ID_MISSING")
                        .withSeverity("BLOCKING");
            }
            byId.put(port.getWorkflowPortId(), port);
        }
        Set<String> submitted = new LinkedHashSet<>();
        if (req.getMaterialInputTotals() != null) {
            for (ProcessSheetRowRequest.MaterialInputTotal input : req.getMaterialInputTotals()) {
                com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor port =
                        resolveInputPort(byId, ports, input.getWorkflowPortId());
                resolveAllowedInputSku(port, input.getMaterialTypeId());
                String portUnit = requireWorkflowPortUnit(port.getUnit(), "投入");
                assertConfiguredUnit(factoryId, input.getUnit(), portUnit, "投入");
                assertNonNegativeWorkflowInput(input.getQuantity());
                input.setUnit(portUnit);
                input.setWorkflowPortId(port.getWorkflowPortId());
                if (input.getMaterialNodeId() == null) input.setMaterialNodeId(port.getMaterialNodeId());
                if (input.getQuantity() != null && input.getQuantity().signum() > 0) {
                    submitted.add(port.getWorkflowPortId());
                }
            }
        }
        if (req.getRawMaterialInputs() != null) {
            for (ProcessSheetRowRequest.RawInput input : req.getRawMaterialInputs()) {
                com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor port =
                        resolveInputPort(byId, ports, input.getWorkflowPortId());
                String selectedSkuId = resolveAllowedInputSku(port, input.getSkuId());
                assertNonNegativeWorkflowInput(input.getQuantity());
                input.setSkuId(selectedSkuId);
                input.setWorkflowPortId(port.getWorkflowPortId());
                if (input.getMaterialNodeId() == null) input.setMaterialNodeId(port.getMaterialNodeId());
                if (input.getQuantity() != null && input.getQuantity().signum() > 0) {
                    submitted.add(port.getWorkflowPortId());
                }
            }
        }
        if (req.getUpstreamSources() != null) {
            for (ProcessSheetRowRequest.UpstreamRef input : req.getUpstreamSources()) {
                com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor port =
                        resolveInputPort(byId, ports, input.getWorkflowPortId());
                String selectedSkuId = resolveAllowedInputSku(port, input.getSkuId());
                assertNonNegativeWorkflowInput(input.getFeedQuantityKg());
                input.setSkuId(selectedSkuId);
                input.setWorkflowPortId(port.getWorkflowPortId());
                if (input.getMaterialNodeId() == null) input.setMaterialNodeId(port.getMaterialNodeId());
                if (input.getFeedQuantityKg() != null && input.getFeedQuantityKg().signum() > 0) {
                    submitted.add(port.getWorkflowPortId());
                }
            }
        }
        if (requireCompleteSelections) {
            validatePortSelections(ports, submitted, "INPUT");
        }
        List<String> normalizedUnits = ports.stream()
                .filter(port -> submitted.contains(port.getWorkflowPortId()))
                .map(port -> normalizeReportingUnit(requireWorkflowPortUnit(port.getUnit(), "投入")))
                .distinct()
                .toList();
        if (normalizedUnits.isEmpty()) {
            return;
        }
        boolean allMass = normalizedUnits.stream().allMatch(unit -> "kg".equals(unit) || "g".equals(unit));
        if (normalizedUnits.size() == 1) {
            req.setInputUnit(requireWorkflowPortUnit(
                    ports.stream().filter(port -> submitted.contains(port.getWorkflowPortId())).findFirst().orElseThrow().getUnit(),
                    "投入"));
        } else if (allMass) {
            req.setInputUnit("kg"); // 组级 inputQuantity 的内部统一口径
        } else {
            throw new BusinessException(409, "不同物理维度的投入不能合并为一个组级投入总量")
                    .withCode("PROCESS_SHEET_WORKFLOW_MIXED_INPUT_DIMENSIONS")
                    .withHint("请拆分工序；g/kg 可自动换算，数量/长度/重量不可直接相加")
                    .withSeverity("BLOCKING");
        }
    }

    private static void assertNonNegativeWorkflowInput(BigDecimal quantity) {
        if (quantity != null && quantity.signum() < 0) {
            throw new BusinessException(400, "Workflow 投入端口数量不能为负数")
                    .withCode("PROCESS_SHEET_INPUT_QUANTITY_INVALID")
                    .withSeverity("BLOCKING");
        }
    }

    private static com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor resolveInputPort(
            Map<String, com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor> byId,
            List<com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor> ports,
            String workflowPortId) {
        if (workflowPortId == null || workflowPortId.isBlank()) {
            if (ports.size() == 1) return ports.getFirst();
            throw new BusinessException(409, "多投入报工必须逐项指定 Workflow 投入端口")
                    .withCode("PROCESS_SHEET_WORKFLOW_INPUT_PORT_REQUIRED")
                    .withHint("请刷新逐道录入页面后重新填写")
                    .withSeverity("BLOCKING");
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor port = byId.get(workflowPortId);
        if (port == null) {
            throw new BusinessException(409, "请求包含不属于该 Workflow 工序的投入端口")
                    .withCode("PROCESS_SHEET_WORKFLOW_INPUT_PORT_NOT_FOUND")
                    .withSeverity("BLOCKING");
        }
        return port;
    }

    private static String resolveAllowedInputSku(
            com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor port,
            String actualSkuId) {
        String selectedSkuId = actualSkuId == null || actualSkuId.isBlank()
                ? port.getSkuId()
                : actualSkuId;
        List<String> allowedSkuIds = port.getAllowedSkuIds() == null
                || port.getAllowedSkuIds().isEmpty()
                ? (port.getSkuId() == null ? List.of() : List.of(port.getSkuId()))
                : port.getAllowedSkuIds();
        if (selectedSkuId == null || !allowedSkuIds.contains(selectedSkuId)) {
            throw new BusinessException(409, "本次投入不属于计划固定 BOM 允许的主料或替代料")
                    .withCode("WORKFLOW_ROW_INPUT_SKU_NOT_AUTHORIZED")
                    .withHint("请刷新报工页面，并从当前工序显示的可用批次中选择")
                    .withSeverity("BLOCKING")
                    .withHintTarget("实际投入");
        }
        return selectedSkuId;
    }

    private static String requireWorkflowPortUnit(String unit, String side) {
        if (unit != null && !unit.isBlank()) {
            return unit.trim();
        }
        throw new BusinessException(409, "Workflow " + side + "端口缺少单位，不能报工")
                .withCode("PROCESS_SHEET_WORKFLOW_PORT_UNIT_MISSING")
                .withHint("请修复并重新发布 Workflow 后创建新批次")
                .withSeverity("BLOCKING")
                .withHintTarget("Workflow");
    }

    /**
     * The client may omit legacy unit fields, but it may not override product-process
     * and work-process configuration. This boundary runs before stock and cost writes.
     */
    static void normalizeConfiguredUnits(ProcessSheetRowRequest req,
                                         ProductWorkProcess productProcess,
                                         WorkProcess workProcess) {
        String inputUnit = firstNonBlank(productProcess.getUnitOverride(), workProcess.getUnit(), "kg");
        String outputUnit = firstNonBlank(workProcess.getOutputUnit(), inputUnit);

        assertConfiguredUnit(req.getInputUnit(), inputUnit, "投入");
        assertConfiguredUnit(req.getOutputUnit(), outputUnit, "产出");
        assertConfiguredUnit(req.getUnit(), outputUnit, "产出");

        req.setInputUnit(inputUnit);
        req.setOutputUnit(outputUnit);
        req.setUnit(outputUnit);
    }

    private static void assertConfiguredUnit(String suppliedUnit, String configuredUnit, String side) {
        if (builtInUnitsEquivalent(suppliedUnit, configuredUnit)) {
            return;
        }
        throw new BusinessException(409, "请求" + side + "单位为“" + suppliedUnit
                + "”，与工序配置单位“" + configuredUnit + "”不一致")
                .withCode("PROCESS_SHEET_UNIT_MISMATCH")
                .withHint("请刷新工序表后按配置单位录入；单位变更请在产品工序配置中维护")
                .withSeverity("BLOCKING")
                .withHintTarget("工序配置");
    }

    private void assertConfiguredUnit(
            String factoryId, String suppliedUnit, String configuredUnit, String side) {
        if (configuredUnitsEquivalent(factoryId, suppliedUnit, configuredUnit)) {
            return;
        }
        assertConfiguredUnit(suppliedUnit, configuredUnit, side);
    }

    private boolean configuredUnitsEquivalent(
            String factoryId, String suppliedUnit, String configuredUnit) {
        if (builtInUnitsEquivalent(suppliedUnit, configuredUnit)) {
            return true;
        }
        return suppliedUnit != null
                && !suppliedUnit.isBlank()
                && unitContractService != null
                && unitContractService.areEquivalent(factoryId, suppliedUnit, configuredUnit);
    }

    private static boolean builtInUnitsEquivalent(String suppliedUnit, String configuredUnit) {
        if (suppliedUnit == null || suppliedUnit.isBlank()) {
            return true;
        }
        if (configuredUnit != null && suppliedUnit.trim().equalsIgnoreCase(configuredUnit.trim())) {
            return true;
        }
        Optional<CanonicalUnit> supplied = UnitContractServiceImpl.describeBuiltIn(suppliedUnit);
        Optional<CanonicalUnit> configured = UnitContractServiceImpl.describeBuiltIn(configuredUnit);
        return supplied.isPresent()
                && configured.isPresent()
                && supplied.get().code().equals(configured.get().code());
    }

    /**
     * The existing SFI/FG services consume feedQuantityKg and only implement kg-to-count
     * conversion. Arbitrary count-unit feeds must not be silently treated as kilograms.
     */
    private static void assertExternalFeedUnitSupported(ProcessSheetRowRequest req) {
        if (req.getUpstreamSources() == null || req.getUpstreamSources().isEmpty()) {
            return;
        }
        boolean usesExternalStock = req.getUpstreamSources().stream()
                .anyMatch(ref -> ref.isSemiFinished() || ref.isFinishedGoods());
        if (usesExternalStock && !"kg".equalsIgnoreCase(requestInputUnit(req))) {
            throw new BusinessException(409, "常驻半成品/成品库存投料当前只支持 kg 投入单位")
                    .withCode("PROCESS_SHEET_EXTERNAL_FEED_UNIT_UNSUPPORTED")
                    .withHint("请先通过配置为 kg 投入单位的换算工序转换后，再使用半成品或成品库存投料")
                    .withSeverity("BLOCKING")
                    .withHintTarget("inputUnit");
        }
    }

    /**
     * g 与 kg 在库存扣减边界显式换算；其他单位不能猜测，否则会把“只/袋”当 kg，
     * 直接污染库存与成本。SFI/FG 来源沿用各自既有的严格换算/校验路径。
     */
    private static BigDecimal convertReportingQuantityToStorage(
            BigDecimal reportingQuantity,
            String reportingUnit,
            String storageUnit,
            String sourceLabel) {
        if (reportingQuantity == null) {
            return reportingQuantity;
        }
        if (reportingUnit == null || reportingUnit.isBlank()
                || storageUnit == null || storageUnit.isBlank()) {
            throw sourceUnitMismatch(reportingUnit, storageUnit, sourceLabel);
        }
        String reportingCode = massUnitCode(reportingUnit);
        String storageCode = massUnitCode(storageUnit);
        if (reportingCode.equals(storageCode)) {
            return reportingQuantity;
        }
        if ("kg".equals(reportingCode) && "g".equals(storageCode)) {
            return reportingQuantity.movePointRight(3);
        }
        if ("g".equals(reportingCode) && "kg".equals(storageCode)) {
            return reportingQuantity.movePointLeft(3);
        }
        throw sourceUnitMismatch(reportingUnit, storageUnit, sourceLabel);
    }

    private static BusinessException sourceUnitMismatch(
            String reportingUnit, String storageUnit, String sourceLabel) {
        return new BusinessException(409, sourceLabel + "存储单位为“" + storageUnit + "”，不能按报工单位“"
                + reportingUnit + "”扣减")
                .withCode("PROCESS_SHEET_SOURCE_UNIT_MISMATCH")
                .withHint("当前支持 g/kg 质量换算，以及盒/箱/片等同口径计数单位；其他单位请先配置确定的单位换算")
                .withSeverity("BLOCKING")
                .withHintTarget("inputUnit");
    }

    private static String massUnitCode(String unit) {
        String normalized = unit.trim().toLowerCase(java.util.Locale.ROOT);
        if ("kg".equals(normalized) || "千克".equals(normalized) || "公斤".equals(normalized)) {
            return "kg";
        }
        if ("g".equals(normalized) || "克".equals(normalized)) {
            return "g";
        }
        if ("box".equals(normalized) || "盒".equals(normalized)) {
            return "box";
        }
        if ("case".equals(normalized) || "箱".equals(normalized)) {
            return "case";
        }
        if ("slice".equals(normalized) || "piece".equals(normalized)
                || "pcs".equals(normalized) || "片".equals(normalized)
                || "个".equals(normalized)) {
            return "slice";
        }
        return normalized;
    }

    private static String requestInputUnit(ProcessSheetRowRequest req) {
        return firstNonBlank(req.getInputUnit(), req.getUnit(), "kg");
    }

    private void ensureRawMaterialWarehouse(String factoryId, String planId, MaterialBatch rawMb) {
        if (warehouseResolver == null) {
            return;
        }

        // ② Part B Gate (opt-in, 默认 OFF): 工厂开启"报工前必须领料确认"后, 报工前该计划必须有仓管确认的领料单
        //   覆盖被消耗物料 → 强制"仓管没确认领料，生产不能报工"料流。关闭 (默认) 时走下方 Part A 宽松校验, ZERO 行为变化。
        if (isRequisitionGateEnabled(factoryId)) {
            enforceRequisitionConfirmed(factoryId, planId, rawMb);
            return;
        }

        // 用途拆分 (2026-07-02): 生产报工原料来源走独立 resolveProductionRawWh (PRODUCTION_RAW_DEFAULT),
        // 未配置回退 WH-LOG = 现状。不再与采购入库 / 销售出货共用 resolveLogisticsId。
        String rawWarehouseId = warehouseResolver.resolveProductionRawWh(factoryId);
        if (rawWarehouseId == null || rawWarehouseId.isBlank()) {
            throw new BusinessException(500, "未配置原料仓/物流仓，不能保存生产领料")
                    .withCode("PRODUCTION_RAW_WAREHOUSE_NOT_CONFIGURED")
                    .withHint("请先维护工厂仓库配置")
                    .withHintTarget("原料批次");
        }
        // code/message 对齐修复 (2026-07-02): 文案说「原料仓/物流仓」但旧代码只认单一 resolveLogisticsId 仓。
        // 现放行 = 配置的生产领料默认仓 (resolveProductionRawWh, 默认 WH-LOG) 或 任意 RAW/LOGISTICS 类型仓库。
        // 严格更宽松: 旧行为 (batch 在 WH-LOG) 仍被第一分支命中 → 向后兼容, 不拒绝原先能通过的批次。
        // 2026-07-03: 生产领料把原料物理迁到生产仓 (WORKSHOP/WH-WKS) 后, 报工从生产仓消耗是合法料流,
        //   故也放行 WORKSHOP 类型仓库的批次 (更宽松, 不拒绝原先能通过的批次)。
        // 诚实-null 保留: batch 无仓 / 仓非 RAW/LOGISTICS/WORKSHOP → loud-fail 409。
        String batchWarehouseId = rawMb != null ? rawMb.getWarehouseId() : null;
        boolean accepted = batchWarehouseId != null && !batchWarehouseId.isBlank()
                && (rawWarehouseId.equals(batchWarehouseId)
                    || warehouseResolver.isRawOrLogisticsWarehouse(factoryId, batchWarehouseId)
                    || warehouseResolver.isWorkshopWarehouse(factoryId, batchWarehouseId));
        if (!accepted) {
            throw new BusinessException(409, "生产逐道报工原料只能从原料仓/物流仓/生产仓领用，不能从其他仓库扣减")
                    .withCode("PRODUCTION_RAW_WAREHOUSE_REQUIRED")
                    .withHint("请重新选择原料仓/物流仓/生产仓批次后再保存")
                    .withHintTarget("原料批次");
        }
    }

    /**
     * ② Part B Gate 开关读取。无 repo (单测) / 无 settings 行 → 兜底 false (报工照旧, 向后兼容安全默认)。
     */
    private boolean isRequisitionGateEnabled(String factoryId) {
        if (factorySettingsRepository == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(
                    factorySettingsRepository.findRequireRequisitionBeforeReportByFactoryId(factoryId));
        } catch (Exception e) {
            // 配置读取异常不应阻断报工 —— 兜底 false (安全默认: 不误伤正在报工的工厂)。
            log.warn("读取工厂 {} 领料 Gate 开关失败, 兜底为关闭: {}", factoryId, e.getMessage());
            return false;
        }
    }

    /**
     * ② Part B Gate (工厂已开启时): 校验该生产计划已有仓管确认的领料单覆盖被消耗物料。
     *
     * <p>诚实实现说明 (🔒 设计决策): {@code FactoryMaterialRequisitionServiceImpl.transferToFactory} 仅创建
     * <b>DRAFT</b> 状态的 InternalTransfer, 并 <b>不会</b>在此刻把 MaterialBatch 物理迁移到车间仓 (迁移发生在
     * 调拨单单独确认/签收时)。因此本 Gate <b>不做</b>"批次必须在车间仓"的物理仓校验 (会因 DRAFT 未迁移而误挡),
     * 而是校验"仓管已确认领料"这一业务事实 —— 该计划存在状态 ∈ {TRANSFERRED, ISSUED, IN_USE} 的领料单,
     * 且其明细覆盖被消耗物料 (issuedQty>0)。这如实实现客户"仓管没确认领料，生产不能报工"诉求, 不依赖不确定的物理迁移语义。
     *
     * <p>防呆: BLOCKING 错误必带明确下一步指引 (never dead-end)。
     */
    private void enforceRequisitionConfirmed(String factoryId, String planId, MaterialBatch rawMb) {
        String matTypeId = rawMb != null ? rawMb.getMaterialTypeId() : null;
        // rawMb 无 materialName 字段; 用 materialTypeId 作标签 (领料单明细里带真实名, 报工挡在批次层这里够用)。
        String matName = matTypeId != null ? matTypeId : "该原料";

        if (requisitionRepository == null || planId == null) {
            // 无 repo (单测环境) 或无计划上下文 → 无法校验领料单; Gate 已显式开启, 不静默放行 → BLOCKING。
            throw requisitionRequired(matName, "无法校验领料单 (缺少计划上下文)");
        }

        List<FactoryMaterialRequisition> reqs = requisitionRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId);

        boolean covered = reqs.stream()
                .filter(ProcessSheetServiceImpl::isRequisitionConfirmed)
                .flatMap(r -> r.getItems() != null ? r.getItems().stream() : java.util.stream.Stream.empty())
                .anyMatch(it -> matTypeId != null
                        && matTypeId.equals(it.getMaterialTypeId())
                        && it.getIssuedQty() != null
                        && it.getIssuedQty().compareTo(BigDecimal.ZERO) > 0);

        if (!covered) {
            boolean anyConfirmed = reqs.stream().anyMatch(ProcessSheetServiceImpl::isRequisitionConfirmed);
            String detail = anyConfirmed
                    ? "该计划领料单未覆盖原料「" + matName + "」(该料未被仓管拣货/调拨)"
                    : "该计划尚无仓管已确认的领料单";
            throw requisitionRequired(matName, detail);
        }
    }

    /** 领料单是否已被仓管确认 (拣货+调拨后状态): TRANSFERRED / ISSUED / IN_USE。 */
    private static boolean isRequisitionConfirmed(FactoryMaterialRequisition r) {
        return r.getStatus() == FactoryMaterialRequisition.Status.TRANSFERRED
                || r.getStatus() == FactoryMaterialRequisition.Status.ISSUED
                || r.getStatus() == FactoryMaterialRequisition.Status.IN_USE;
    }

    private BusinessException requisitionRequired(String matName, String detail) {
        return new BusinessException(409,
                "生产报工需先领料：" + detail + "。请先在该生产计划生成领料单，由仓管拣货确认并调拨到生产仓后再报工。")
                .withCode("PRODUCTION_REQUISITION_REQUIRED")
                .withHint("路径: 生产管理 → 物料需求单 → 按计划生成 → 备料 → 确认领料 → 调拨")
                .withHintTarget("原料批次")
                .withSeverity("BLOCKING");
    }

    /**
     * WIP 的物料身份属于本道产出，而不是任一投入来源。
     *
     * <p>Workflow 行的 {@code productTypeId} 已在端口校验中与产出 Cell 的稳定 SKU identity 对齐；
     * legacy 行同样把 {@code productTypeId} 作为该行产出对象。RAW/SEMI 边只承载完整投入 provenance，
     * 因此输入数量、顺序或首项都不得影响 WIP identity。缺少产出身份时 loud-fail，禁止回退猜首个入口。
     */
    private String resolveOutputMaterialIdentity(ProcessSheetRowRequest req) {
        String outputIdentity = req == null ? null : req.getProductTypeId();
        if (outputIdentity == null || outputIdentity.isBlank()) {
            throw new BusinessException(400, "本道产出缺少稳定物料身份，无法物化半成品批次")
                    .withCode("WIP_OUTPUT_MATERIAL_IDENTITY_REQUIRED")
                    .withHint("请刷新并按 Workflow 产出 Cell 重新选择半成品；旧流程请明确填写本道产出产品")
                    .withHintTarget(req != null ? req.getProcessCode() : "产出")
                    .withSeverity("BLOCKING");
        }
        return outputIdentity.trim();
    }

    /** 该行是否含 SFI(半成品库存)投料来源 (semiFinished=true)。 */
    private boolean hasSemiFinishedUpstream(ProcessSheetRowRequest req) {
        return req.getUpstreamSources() != null
                && req.getUpstreamSources().stream().anyMatch(ProcessSheetRowRequest.UpstreamRef::isSemiFinished);
    }

    /** ①c 该行是否含 FG(成品库存)投料来源 (finishedGoods=true)。 */
    private boolean hasFinishedGoodsUpstream(ProcessSheetRowRequest req) {
        return req.getUpstreamSources() != null
                && req.getUpstreamSources().stream().anyMatch(ProcessSheetRowRequest.UpstreamRef::isFinishedGoods);
    }

    private void assertFinishedGoodsSourceAllowed(String factoryId, ProcessSheetRowRequest req) {
        if (!hasFinishedGoodsUpstream(req)) {
            return;
        }
        boolean allowed = productWorkProcessRepo
                .findByFactoryIdAndProductTypeIdAndProcessOrder(factoryId, req.getProductTypeId(), req.getProcessOrder())
                .map(ProductWorkProcess::getAllowFinishedGoodsSource)
                .orElse(Boolean.FALSE);
        if (!allowed) {
            throw new BusinessException(409, "该工序未开启成品作来源, 不能选择成品库存批次投料")
                    .withCode("FINISHED_GOODS_SOURCE_NOT_ALLOWED")
                    .withHint("请先到产品-工序配置开启“成品源”, 再录入成品库存来源批")
                    .withHintTarget(req.getProcessCode());
        }
    }

    /**
     * option F (①c 扩展): 该行是否为「纯外部库存 (半成品SFI / 成品FG) 喂的中间道」——
     * 含 SFI/FG 投料, 且<b>所有</b>上游来源均为外部库存 (semiFinished 或 finishedGoods), 且<b>无</b>原料投入。
     *
     * <p>这类道无 raw lineage 无法派生 {@code material_type_id}, 故<b>不物化</b> WIP MaterialBatch;
     * 产出在小结直接入半成品库(SFI), 停留在 product-type 维度 (复用 SFI in/out)。FG 投料的扣减在小结经
     * {@code consumeForFeedStrict} 完成。注意: {@code allStock} 已排除「混有 in-plan WIP 上游」的情形;
     * 「原料+SFI/FG 混批」因 {@code hasRaw} 返 false (走原路径, 从 raw 派生 materialTypeId)。
     *
     * <p>成品道 (isFinished) 的纯 SFI/FG 场景不归此判定管辖 —— 调用方另行以 {@code !isFinished()} 限定。
     * ①c 成品(FG)作投料来源保持与 ③=F (纯 SFI 中间道) 一致: 非成品的纯 FG 道 = SAVED_SFI (产出入 SFI)。
     */
    private boolean isPureStockFed(ProcessSheetRowRequest req) {
        List<ProcessSheetRowRequest.UpstreamRef> ups = req.getUpstreamSources();
        if (ups == null || ups.isEmpty()) {
            return false;
        }
        boolean allStock = ups.stream().allMatch(u -> u.isSemiFinished() || u.isFinishedGoods());
        if (!allStock) {
            return false;
        }
        return req.getRawMaterialInputs() == null || req.getRawMaterialInputs().isEmpty();
    }

    // ─────────────────────────────────────────────────────────────
    // #1252 中段起步: 纯外部库存 (SFI/FG) 喂的非成品中间道 —— 保存时 SFI IN 入库
    // ─────────────────────────────────────────────────────────────

    /**
     * #1252: 把纯外部库存 (SFI/FG) 喂的非成品中间道产出<b>在保存时</b>即入常驻半成品库 (SFI IN)。
     *
     * <p>锚 = {@link WipInventoryService#clerkSemiAnchor}(planId, productTypeId) (与小结同一真源)。
     * 入库全产出量 (不做「净结余」扣减 —— 下游道对本产出的消耗走 SFI OUT / consumeClerkSemiStrict, 在小结完成,
     * 与此 IN 天然互抵, 净额一致)。故小结<b>不再</b>为 SAVED_SFI 行重复 SFI IN
     * (见 {@code InterimSettleServiceImpl} SFI IN 循环跳过 batchId==null 的 SAVED_SFI 行)。
     *
     * <p>成本: 现算本道产出单位成本 (与小结 {@code computeOutputUnitCost} 的 batchId==null 分支同口径), 诚实 null
     * (任一投入成本未知 / 调味道无法现算调料 → null, 绝不伪造 ¥0)。processOrder 落值供 picker 阶段可见性过滤。
     *
     * @return 入库锚 (= 行 batchNumber, 供下游道以 semiFinished 引用)。
     */
    private String postSfiOutput(String factoryId, String planId, ProcessSheetRowRequest req,
                                 List<String> warnings) {
        String anchor = WipInventoryService.clerkSemiAnchor(planId, req.getProductTypeId());
        BigDecimal outQty = req.getOutputQuantity();
        BigDecimal outUnitCost = computeInjectionOutputUnitCost(factoryId, req, warnings);
        // postClerkOutput: inQty≤0 → no-op (saveRow 上游 gate 已保证 output>0)。
        wipInventoryService.postClerkOutput(factoryId, anchor, req.getProductTypeId(),
                outQty, req.getUnit() != null ? req.getUnit() : "kg",
                outUnitCost, null, req.getProcessOrder());
        return anchor;
    }

    /**
     * #1252: 冲销一条 SAVED_SFI 行保存时的 SFI IN (供重存/删除时避免重复入库造幽灵库存)。
     *
     * <p>{@link WipInventoryService#reverseClerkOutput} 自带下游已消耗守卫: 该产出已被下游道消耗 → 抛
     * 409 {@code SFI_DOWNSTREAM_CONSUMED} (整事务回滚, 禁止降级不产负库存)。totalCost 按旧 payload 现算的
     * 单位成本 × 产出量 (与保存时 postSfiOutput 同算式) 反冲 accumulatedCost; 成本未知 (null) 则只冲量不冲成本。
     */
    private void reverseSfiOutput(String factoryId, String planId, ProcessSheetRowRequest beforeReq) {
        if (beforeReq == null || beforeReq.getOutputQuantity() == null
                || beforeReq.getOutputQuantity().signum() <= 0) {
            return; // 旧行无产出 (理论上 SAVED_SFI 必有 output>0, 防御性跳过)
        }
        String anchor = WipInventoryService.clerkSemiAnchor(planId, beforeReq.getProductTypeId());
        BigDecimal qty = beforeReq.getOutputQuantity();
        BigDecimal oldUnitCost = computeInjectionOutputUnitCost(factoryId, beforeReq, new ArrayList<>());
        BigDecimal totalCost = oldUnitCost == null ? null : oldUnitCost.multiply(qty);
        wipInventoryService.reverseClerkOutput(factoryId, anchor, qty, totalCost, null);
    }

    /** #1252 调味/熟制道正则 — 与 {@link #buildStepEntry} / InterimSettle isSeasoningRow 同源。 */
    private static final java.util.regex.Pattern SEASONING_NAME_PATTERN =
            java.util.regex.Pattern.compile(".*(熟|卤|煮|腌|注射|入味|调味).*");

    /**
     * #1252 注入产出单位成本 —— 镜像 {@code InterimSettleServiceImpl.computeOutputUnitCost} 的
     * <b>纯 SFI/FG 道 (batchId==null)</b> 分支 (禁止降级, 诚实 null):
     * <ul>
     *   <li>调味/熟制道 → null (SAVED_SFI 不物化 → 无 RecipeCostCalculator 现算调料桶, 不漏计成假数据)。</li>
     *   <li>base = 本道人工 ({@link ClerkProcessEntryService#computeLaborCost}(laborSegments, laborRate))。</li>
     *   <li>+ Σ 外部库存投料成本: feedInSourceUnit × 输入 SFI/FG unitCost (盒⇄kg 折算同扣减侧口径)。</li>
     *   <li>任一投入 unitCost / 折算 为 null → 整道产出成本 null (不当 ¥0 摊薄)。</li>
     * </ul>
     * outputQty≤0 → null (无分母, saveRow 上游 gate 已保证 >0)。
     */
    private BigDecimal computeInjectionOutputUnitCost(String factoryId, ProcessSheetRowRequest req,
                                                      List<String> warnings) {
        BigDecimal outputQty = req.getOutputQuantity();
        if (outputQty == null || outputQty.signum() <= 0) {
            return null;
        }
        // 调味/熟制道: 调料桶无法现算 → 诚实 null (禁止只算 labor 降级成非-null 假数据)。
        String name = req.getProcessName();
        if ((name == null || name.isBlank())
                && req.getProductTypeId() != null && req.getProcessOrder() != null) {
            name = resolveProcessNamesByOrder(factoryId, req.getProductTypeId()).get(req.getProcessOrder());
        }
        boolean seasoning = req.isSeasoningStep()
                || (name != null && SEASONING_NAME_PATTERN.matcher(name).matches());
        if (seasoning) {
            log.warn("[process-sheet] #1252 纯外部库存投料调味道 (process={}) 无法现算调料成本 → 产出成本诚实 null",
                    req.getProcessCode());
            return null;
        }
        BigDecimal laborRate = clerkService.resolveLaborRate(factoryId, warnings);
        BigDecimal baseTotal = nz(clerkService.computeLaborCost(req.getLaborSegments(), laborRate));

        BigDecimal stockFeedCost = BigDecimal.ZERO;
        if (req.getUpstreamSources() != null) {
            for (ProcessSheetRowRequest.UpstreamRef ref : req.getUpstreamSources()) {
                boolean semi = ref.isSemiFinished();
                boolean fg = ref.isFinishedGoods();
                if (!semi && !fg) {
                    continue; // isPureStockFed 保证全为外部库存, 防御性跳过
                }
                BigDecimal feed = nz(ref.getFeedQuantityKg());
                if (feed.signum() <= 0) {
                    continue;
                }
                BigDecimal feedInSourceUnit = fg
                        ? finishedGoodsFeedService.resolveFeedQtyInSourceUnit(factoryId, ref.getSourceBatchNumber(), feed)
                        : wipInventoryService.resolveSemiFeedQtyInSourceUnit(factoryId, ref.getSourceBatchNumber(), feed);
                if (feedInSourceUnit == null) {
                    return null; // 诚实 null: 盒装来源缺每盒克重 → 无法折算
                }
                BigDecimal inputUnitCost = fg
                        ? finishedGoodsFeedService.getFeedUnitCost(factoryId, ref.getSourceBatchNumber())
                        : wipInventoryService.getSemiUnitCost(factoryId, ref.getSourceBatchNumber());
                if (inputUnitCost == null) {
                    return null; // 诚实 null: 输入半成品/成品无成本 → 本道产出成本未知
                }
                stockFeedCost = stockFeedCost.add(feedInSourceUnit.multiply(inputUnitCost));
            }
        }
        return baseTotal.add(stockFeedCost).divide(outputQty, 4, RoundingMode.HALF_UP);
    }

    // ─────────────────────────────────────────────────────────────
    // Request → StepEntry mapping
    // ─────────────────────────────────────────────────────────────

    private StepEntry buildStepEntry(String factoryId, ProcessSheetRowRequest req) {
        StepEntry st = new StepEntry();
        st.setProcessOrder(req.getProcessOrder());
        // 解析真实工序名: req 未带时(前端不传)按 productTypeId+order 反查 product-work-process,
        // 否则 StepEntry.processName 恒 null → 调味道按名识别失效 (调料成本不流入)。
        String name = req.getProcessName();
        if ((name == null || name.isBlank()) && req.getProductTypeId() != null && req.getProcessOrder() != null) {
            name = resolveProcessNamesByOrder(factoryId, req.getProductTypeId()).get(req.getProcessOrder());
        }
        st.setProcessName(name);
        st.setProcessDate(req.getProcessDate());  // 跨天: 该工序实际操作日 → 报工日期
        // processCategory=SEASONING 决定调料成本是否计入。三个来源: ① 前端显式 isSeasoningStep
        // ② 工序名是熟制/卤制/注射等调味道(与 isSeasoningStep 警告同正则) —— F006 熟制道 processCategory
        // 是'加工'且 grid 无 potCount, 不按名识别则调料成本结构性恒 0。无配方时仍 0+warning, 故安全。
        boolean seasoning = req.isSeasoningStep()
                || (name != null && name.matches(".*(熟|卤|煮|腌|注射|入味|调味).*"));
        st.setProcessCategory(seasoning ? "SEASONING" : "NORMAL");
        st.setInputQuantity(req.getInputQuantity());
        st.setOutputQuantity(req.getOutputQuantity());
        st.setProductWeight(req.getProductWeight());
        String outputUnit = firstNonBlank(req.getOutputUnit(), req.getUnit(), "kg");
        st.setInputUnit(firstNonBlank(req.getInputUnit(), req.getUnit(), outputUnit));
        st.setOutputUnit(outputUnit);
        st.setUnit(outputUnit);
        st.setPotCount(req.getPotCount());
        st.setPotRawKgs(req.getPotRawKgs());
        // 多时段工时 (materializeBatch 优先用此求和)
        st.setLaborSegments(req.getLaborSegments());
        // 上游消耗已由 edges 解析; rawMaterialInputs 不传 (materializeBatch 只用 edges)。
        // 但 SP-D Fix 3 警告分支检查 st.getUpstreamSources(): 镜像 req.upstreamSources,
        // 使非调味混锅步骤正确触发 "调料成本未计入" 警告。
        if (req.getUpstreamSources() != null && !req.getUpstreamSources().isEmpty()) {
            List<UpstreamSource> mirror = new ArrayList<>();
            for (ProcessSheetRowRequest.UpstreamRef ur : req.getUpstreamSources()) {
                UpstreamSource us = new UpstreamSource();
                // sourceClientBatchKey 不被 materializeBatch 使用 (edges 已解析), 仅占位让列表非空。
                us.setSourceClientBatchKey(ur.getSourceBatchNumber());
                us.setFeedQuantityKg(ur.getFeedQuantityKg());
                mirror.add(us);
            }
            st.setUpstreamSources(mirror);
        }
        // SP-G G3a: 透传副产物/留样/包装明细 → materializeBatch 写 YIELD 报工
        st.setByproducts(req.getByproducts());
        st.setSampleRetainQuantity(req.getSampleRetainQuantity());
        st.setPackagingDetail(req.getPackagingDetail());
        // G2: 透传自定义字段值 → materializeBatch 写 YIELD 报工 (命名空间并入 ProductionReport.customFields)
        st.setCustomFields(req.getCustomFields());
        return st;
    }

    // ─────────────────────────────────────────────────────────────
    // process_sheet_rows persistence
    // ─────────────────────────────────────────────────────────────

    private void persistRow(String factoryId, String planId, ProcessSheetRowRequest req,
                            Long batchId, String batchNumber, String rowStatus) {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(factoryId);
        row.setPlanId(planId);
        row.setProcessCode(req.getProcessCode());
        // SP-F role-mode fix: 持久化链内唯一 processOrder, 供双键 (code, order) 查询。
        row.setProcessOrder(req.getProcessOrder());
        row.setClientRowId(req.getClientRowId());
        row.setBatchId(batchId);
        row.setBatchNumber(batchNumber);
        row.setRowPayload(serializePayload(req));
        row.setRowStatus(rowStatus);
        try {
            rowRepo.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            String detail = Optional.ofNullable(e.getMostSpecificCause())
                    .map(Throwable::getMessage)
                    .orElse(e.getMessage());
            if (detail != null && detail.contains("uk_sheet_row")) {
                // UK (factory,plan,processCode,clientRowId) 冲突 — 并发双 POST。
                // 完整幂等读已有行测在 Task 1.7; 这里映射 409 + 整事务回滚 loser 的物化图。
                throw new BusinessException(409, "该行已存在 (并发提交)")
                        .withCode("PROCESS_SHEET_ROW_DUPLICATE");
            }
            log.warn("process sheet row flush failed: factory={}, plan={}, process={}, clientRowId={}, detail={}",
                    factoryId, planId, req.getProcessCode(), req.getClientRowId(), detail, e);
            throw new BusinessException(409, "工序行保存失败，请检查上游批次、成本和库存数据")
                    .withCode("PROCESS_SHEET_ROW_INTEGRITY")
                    .withHint(detail)
                    .withSeverity("BLOCKING")
                    .withHintTarget(req.getProcessCode());
        }
    }

    /**
     * SP-F Task 1.6: 原地更新已存在的 process_sheet_rows 行 (保 row id)。
     * 用于 re-save —— 刷新 batchId/batchNumber/payload/status, 不 insert 新行 (不撞 UK)。
     */
    private void updateRowInPlace(ProcessSheetRow existing, ProcessSheetRowRequest req,
                                  Long batchId, String batchNumber, String rowStatus) {
        existing.setBatchId(batchId);
        existing.setBatchNumber(batchNumber);
        // SP-F role-mode fix: re-save 时同步 processOrder (回填历史 DRAFT 行 / 防御性保持一致)。
        existing.setProcessOrder(req.getProcessOrder());
        existing.setRowPayload(serializePayload(req));
        existing.setRowStatus(rowStatus);
        rowRepo.save(existing);
    }

    /**
     * yieldRate = 可比产出 / 可比投入 × 100。g/kg 统一转 kg；计数产出有 productWeight 时按 kg；
     * 同单位非质量可比；不同物理维度不制造虚假比例，返回 null。
     */
    private BigDecimal yieldRate(ProcessSheetRowRequest req) {
        BigDecimal input = req.getInputQuantity();
        BigDecimal output = req.getOutputQuantity();
        if (output == null || input == null || input.signum() <= 0) {
            return null;
        }
        String inputUnit = normalizeReportingUnit(requestInputUnit(req));
        String outputUnit = normalizeReportingUnit(firstNonBlank(req.getOutputUnit(), req.getUnit(), "kg"));
        if (req.getProductWeight() != null && req.getProductWeight().signum() > 0) {
            output = req.getProductWeight();
            outputUnit = "kg";
        }
        boolean inputMass = "kg".equals(inputUnit) || "g".equals(inputUnit);
        boolean outputMass = "kg".equals(outputUnit) || "g".equals(outputUnit);
        if (inputMass && outputMass) {
            if ("g".equals(inputUnit)) input = input.movePointLeft(3);
            if ("g".equals(outputUnit)) output = output.movePointLeft(3);
        } else if (!inputUnit.equals(outputUnit)) {
            return null;
        }
        return output.divide(input, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    /** unitPrice = rowTotalCost/output (scale 4, HALF_UP); output≤0 → null。 */
    private BigDecimal unitPrice(BigDecimal rowTotalCost, BigDecimal output) {
        if (rowTotalCost == null || output == null || output.signum() <= 0) {
            return null;
        }
        return rowTotalCost.divide(output, 4, RoundingMode.HALF_UP);
    }

    private String serializePayload(ProcessSheetRowRequest req) {
        try {
            return objectMapper.writeValueAsString(req);
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "行数据序列化失败: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Result assembly
    // ─────────────────────────────────────────────────────────────

    private ProcessSheetRowResult buildResult(ProcessSheetRowRequest req, Long batchId,
                                              String batchNumber, BigDecimal yieldRate,
                                              BigDecimal rowTotalCost, BigDecimal unitPrice,
                                              boolean updated, boolean materialized,
                                              List<String> warnings) {
        ProcessSheetRowResult r = new ProcessSheetRowResult();
        r.setClientRowId(req.getClientRowId());
        r.setBatchId(batchId);
        r.setBatchNumber(batchNumber);
        r.setYieldRate(yieldRate);
        r.setRowTotalCost(rowTotalCost);
        r.setUnitPrice(unitPrice);
        r.setUpdated(updated);
        r.setMaterialized(materialized);
        r.setWarnings(warnings);
        r.setSubmissionStatus(ProcessSheetRow.SUBMISSION_LEGACY);
        return r;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
