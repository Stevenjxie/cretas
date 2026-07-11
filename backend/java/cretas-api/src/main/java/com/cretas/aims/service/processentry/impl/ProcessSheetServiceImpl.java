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
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.processentry.ProcessSheetRowChangeLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.WorkProcess;
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
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import com.cretas.aims.service.processentry.ProcessSheetService;
import com.cretas.aims.service.wip.WipInventoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
 *   <li>SP-E FK 防线: WIP 批 materialTypeId 必从原料或上游 WIP 派生 (空 → 400)</li>
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

        // 1.5 G2: 自定义字段 key 白名单校验 (WorkProcess.customFieldSchema 配置驱动)。
        //     覆盖 create + re-save 两条路径 (resaveRow 由本方法下方委托调用, 早于任何写入)。
        normalizeConfiguredUnits(factoryId, planId, req);
        validateCustomFields(factoryId, req);

        // 1.6 2B B3: workflow 批次行防呆校验 (产出类型/单位对齐 workflow 端口)。
        //     与 validateCustomFields 同置于 upsert 查重之前 → 覆盖 create + re-save 两条路径, 早于任何写入。
        //     legacy 计划 (无 workflow 批次) → getWorkflowSheetConfig 返回 null → 直接放行, 现有路径不变。
        validateWorkflowRowIfApplicable(factoryId, planId, req);

        // 2. upsert 键查重: 已存在 → 委托 re-save (Task 1.6 stub)
        Optional<ProcessSheetRow> existing = rowRepo
                .findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
                        factoryId, planId, req.getProcessCode(), req.getClientRowId());
        if (existing.isPresent()) {
            // TODO(Task 1.6): re-save = update-in-place 保 id (校验无下游消耗 + 重写边/报工)。
            return resaveRow(factoryId, planId, req, userId, existing.get());
        }

        List<String> warnings = new ArrayList<>();

        assertFinishedGoodsSourceAllowed(factoryId, req);
        assertExternalFeedUnitSupported(req);

        // 2B.2 多产出 (fan-out) 分解: 一次报工 N 个产出 → N 个单产出物料化, 共享投入按产出权重拆分,
        //   落 N 行 (clientRowId#i)。下方单产出路径完全不改 (F006 现有流不受影响); 下游小结/撤销/成本逐行复用。
        if (req.getOutputs() != null && req.getOutputs().size() > 1) {
            return saveMultiOutputRow(factoryId, planId, req, userId, warnings);
        }

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
            stampWorkflowTaskIfApplicable(factoryId, planId, req); // 2B B3 F3: 回写 workflow 任务进度 (fail-soft)
            // materialized=true: 产出已入 SFI 库 (下游可选); yieldRate 可算; 成本诚实 (投入未知 → null)。
            return buildResult(req, null, anchor, yieldRate(req), null, null, false, true, warnings);
        }

        // 4. SP-E FK 防线: WIP 批 material_type_id 必从原料或上游 WIP 派生 (空 → 400)
        String rawMaterialTypeId = resolveRawMaterialTypeId(req, edges);

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
                rawMaterialTypeId,
                userId);

        MaterializedBatch mat = clerkService.materializeBatch(ctx, List.of(step), edges, warnings);

        // 8. 写 process_sheet_rows (try/catch UK 冲突 → 409; 完整并发测在 Task 1.7)
        persistRow(factoryId, planId, req, mat.getProductionBatchId(), mat.getBatchNumber(), "SAVED");
        logChange(factoryId, planId, req, "CREATE", null, req, userId);
        stampWorkflowTaskIfApplicable(factoryId, planId, req); // 2B B3 F3: 回写 workflow 任务进度 (fail-soft)

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
    private void validateWorkflowRowIfApplicable(String factoryId, String planId, ProcessSheetRowRequest req) {
        if (workflowClerkSheetService == null) {
            return; // 单测/未注入
        }
        // DRAFT 行 (产出<=0) 尚无产出, 不校验产出类型/单位
        if (req.getOutputQuantity() == null || req.getOutputQuantity().signum() <= 0) {
            return;
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO config;
        try {
            config = workflowClerkSheetService.getWorkflowSheetConfig(factoryId, planId);
        } catch (BusinessException be) {
            throw be; // e.g. WORKFLOW_MULTI_OUTPUT_UNSUPPORTED — 必须 surface, 不静默降级
        } catch (Exception e) {
            log.warn("2B B3 workflow 行校验读取配置失败, 跳过校验 (不阻断保存): planId={}, err={}",
                    planId, e.getMessage());
            return;
        }
        if (config == null || config.getProcesses() == null) {
            return; // legacy 计划 — 不校验
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.ProcessDescriptor desc =
                config.getProcesses().stream()
                        .filter(p -> p.getProcessOrder() != null
                                && p.getProcessOrder().equals(req.getProcessOrder()))
                        .findFirst()
                        .orElse(null);
        if (desc == null || desc.getOutput() == null) {
            return; // 该道不在 workflow 图中 (不硬阻断; FE 只暴露 workflow 工序)
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor out = desc.getOutput();
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

        String expectUnit = out.getUnit();
        String actualUnit = req.getUnit();
        if (expectUnit != null && !expectUnit.isBlank()
                && actualUnit != null && !actualUnit.isBlank()
                && !expectUnit.equals(actualUnit)) {
            throw new BusinessException(409, "本工序【" + processName + "】产出单位应为「"
                    + expectUnit + "」，当前为「" + actualUnit + "」")
                    .withCode("WORKFLOW_ROW_OUTPUT_UNIT_MISMATCH")
                    .withHint("请按 Workflow 配置的单位录入产出数量");
        }
    }

    /**
     * 2B B3 (F3 回写): workflow 批次行产出后, 把对应 workflow 工序任务 (WorkProcessTask) 标记为 COMPLETED
     * 并回写实际产量, 使 workflow 运行时视图反映文员逐道录入的进度 (否则任务永远 PENDING)。
     *
     * <p><b>fail-soft</b>: 任何异常只记 warn, 绝不阻断已完成的行保存/物化/库存写入 (进度回写非关键路径)。
     * legacy 计划 / 未注入 / 找不到对应任务 → 静默跳过。仅在行确有产出 (SAVED / SAVED_SFI) 后调用。
     */
    private void stampWorkflowTaskIfApplicable(String factoryId, String planId, ProcessSheetRowRequest req) {
        if (workflowClerkSheetService == null || taskRepo == null) {
            return;
        }
        try {
            com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO config =
                    workflowClerkSheetService.getWorkflowSheetConfig(factoryId, planId);
            if (config == null || config.getProcesses() == null || config.getWorkflowInstanceId() == null) {
                return; // legacy 计划
            }
            com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.ProcessDescriptor desc =
                    config.getProcesses().stream()
                            .filter(p -> p.getProcessOrder() != null
                                    && p.getProcessOrder().equals(req.getProcessOrder()))
                            .findFirst()
                            .orElse(null);
            if (desc == null || desc.getWorkflowNodeId() == null) {
                return;
            }
            List<com.cretas.aims.entity.workprocess.WorkProcessTask> tasks =
                    taskRepo.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(
                            factoryId, config.getWorkflowInstanceId());
            com.cretas.aims.entity.workprocess.WorkProcessTask task = tasks.stream()
                    .filter(t -> desc.getWorkflowNodeId().equals(t.getWorkflowNodeId()))
                    .findFirst()
                    .orElse(null);
            if (task == null) {
                return;
            }
            if (req.getOutputQuantity() != null) {
                task.setActualQuantity(req.getOutputQuantity());
            }
            task.setStatus(com.cretas.aims.entity.workprocess.WorkProcessTask.Status.COMPLETED);
            taskRepo.save(task);
        } catch (Exception e) {
            log.warn("2B B3 workflow 任务进度回写失败 (不阻断保存): planId={}, processOrder={}, err={}",
                    planId, req.getProcessOrder(), e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2B.2 多产出 (fan-out) 分解 —— 一次报工 N 个产出 → N 个单产出物料化, 共享投入按权重拆分, 落 N 行
    // ─────────────────────────────────────────────────────────────

    private record OneOutputOutcome(Long batchId, String batchNumber, BigDecimal rowTotalCost) {}

    /**
     * 多产出报工分解 (🔒 keystone)。共享投入 (rawMaterialInputs/upstreamSources) 按各产出的分摊权重
     * (allocationWeight 全填则用之, 否则按 quantity) 拆成 N 份 (最后一份吃余数保证 Σ 精确无漂移),
     * 每份合成一条普通单产出行走 {@link #materializeOneOutput} 物化 → 落 N 行 (clientRowId#i)。
     *
     * <p>工序不处理成本分摊 (per Steve): 共享投入按<b>产出数量</b>自动拆分 (物理默认, 非成本分摊配置/字段),
     * 人工/调料仅计在首产出 (共享工序成本, 计一次不双计, 总额守恒)。下游小结/撤销逐行复用不变。
     */
    private ProcessSheetRowResult saveMultiOutputRow(String factoryId, String planId,
            ProcessSheetRowRequest req, Long userId, List<String> warnings) {
        List<ProcessSheetRowRequest.OutputLine> outs = req.getOutputs();

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
        }

        // 2. B3 逐产出对齐 workflow 端口 (workflow 计划; legacy/未注入 → 跳过)
        validateMultiOutputAgainstWorkflow(factoryId, planId, req);

        // 3. 共享投入按产出数量拆分 (物理默认, 不涉成本分摊配置)。同单位场景 (Steve 例子 A 全盒 / D 全 kg) 天然合理。
        List<BigDecimal> weights = outs.stream()
                .map(ProcessSheetRowRequest.OutputLine::getQuantity)
                .toList();
        BigDecimal totalWeight = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.signum() <= 0) {
            throw new BusinessException(400, "多产出数量合计必须大于0")
                    .withCode("PROCESS_SHEET_OUTPUT_QUANTITY_ZERO");
        }

        int n = outs.size();
        List<BigDecimal> rawTotals = req.getRawMaterialInputs() == null ? List.of()
                : req.getRawMaterialInputs().stream()
                        .map(ProcessSheetRowRequest.RawInput::getQuantity).toList();
        List<BigDecimal> upTotals = req.getUpstreamSources() == null ? List.of()
                : req.getUpstreamSources().stream()
                        .map(ProcessSheetRowRequest.UpstreamRef::getFeedQuantityKg).toList();
        List<List<BigDecimal>> rawSplit = splitAcrossOutputs(rawTotals, weights, totalWeight, n);
        List<List<BigDecimal>> upSplit = splitAcrossOutputs(upTotals, weights, totalWeight, n);

        // 4. 逐产出物化 (每份是一条带分数投入的普通单产出行)
        List<ProcessSheetRowResult.OutputResult> outputResults = new ArrayList<>(n);
        BigDecimal aggCost = BigDecimal.ZERO;
        boolean costKnown = true;
        for (int i = 0; i < n; i++) {
            ProcessSheetRowRequest.OutputLine o = outs.get(i);
            ProcessSheetRowRequest one = synthesizeOutputRequest(req, o, i, rawSplit, upSplit);
            // 人工/调料只计在首产出 (共享工序成本, 计一次; 其余产出只承担材料分摊)。总额守恒不双计。
            if (i != 0) {
                one.setLaborSegments(null);
                one.setSeasoningStep(false);
            }
            OneOutputOutcome outcome = materializeOneOutput(factoryId, planId, one, userId, warnings);
            logChange(factoryId, planId, one, "CREATE", null, one, userId);
            if (outcome.rowTotalCost() != null) {
                aggCost = aggCost.add(outcome.rowTotalCost());
            } else {
                costKnown = false; // 诚实 null: 任一产出成本未知 → 汇总标未知
            }
            ProcessSheetRowResult.OutputResult or = new ProcessSheetRowResult.OutputResult();
            or.setClientRowId(one.getClientRowId());
            or.setProductTypeId(o.getProductTypeId());
            or.setBatchId(outcome.batchId());
            or.setBatchNumber(outcome.batchNumber());
            or.setQuantity(o.getQuantity());
            or.setUnit(one.getUnit());
            or.setRowTotalCost(outcome.rowTotalCost());
            outputResults.add(or);
        }

        // 5. workflow 任务进度回写一次 (整道工序完成)
        stampWorkflowTaskIfApplicable(factoryId, planId, req);

        // 6. 汇总结果: 首产出批为代表 + 全部产出明细 (FE 重载过程单会拿到 N 行)
        ProcessSheetRowResult.OutputResult first = outputResults.get(0);
        ProcessSheetRowResult result = buildResult(req, first.getBatchId(), first.getBatchNumber(),
                null, costKnown ? aggCost : null, null, false, true, warnings);
        result.setOutputs(outputResults);
        return result;
    }

    /**
     * 单个产出物化 (仅供多产出分解调用; 单产出主路径不走此方法, 保持 F006 现有流一字不改)。
     * 复刻 saveRow 单产出的 pure-SFI / WIP-FG 两分支决策 + 物化 + 落行, 返回批次/成本。
     */
    private OneOutputOutcome materializeOneOutput(String factoryId, String planId,
            ProcessSheetRowRequest one, Long userId, List<String> warnings) {
        assertExternalFeedUnitSupported(one);
        List<ResolvedEdge> edges = resolveEdges(factoryId, planId, one);
        // 纯半成品(SFI)喂的非成品中间道 → 产出直接入 SFI 库 (同单产出路径 option F)
        if (!one.isFinished() && isPureStockFed(one)) {
            String anchor = postSfiOutput(factoryId, planId, one, warnings);
            persistRow(factoryId, planId, one, null, anchor, ProcessSheetRow.STATUS_SAVED_SFI);
            return new OneOutputOutcome(null, anchor, null);
        }
        String rawMaterialTypeId = resolveRawMaterialTypeId(one, edges);
        StepEntry step = buildStepEntry(factoryId, one);
        MaterializeContext ctx = new MaterializeContext(
                factoryId,
                one.isFinished() ? planId : null,
                one.getProductTypeId(),
                one.getBatchNumber(),
                one.isFinished(),
                clerkService.resolveLaborRate(factoryId, warnings),
                clerkService.resolveWarehouseId(factoryId, WarehouseCodes.WH_WKS, warnings),
                rawMaterialTypeId,
                userId);
        MaterializedBatch mat = clerkService.materializeBatch(ctx, List.of(step), edges, warnings);
        persistRow(factoryId, planId, one, mat.getProductionBatchId(), mat.getBatchNumber(), "SAVED");
        return new OneOutputOutcome(mat.getProductionBatchId(), mat.getBatchNumber(), mat.getRowTotalCost());
    }

    /**
     * 把每条投入总量按各产出权重拆成 N 份, 最后一份 = 总量 − 前面之和 (吃余数, 保证 Σ 精确无精度漂移)。
     * 返回 outer=每条投入, inner=该投入在各产出上的分量。
     */
    private List<List<BigDecimal>> splitAcrossOutputs(List<BigDecimal> totals,
            List<BigDecimal> weights, BigDecimal totalWeight, int n) {
        List<List<BigDecimal>> result = new ArrayList<>(totals.size());
        for (BigDecimal total : totals) {
            BigDecimal base = total == null ? BigDecimal.ZERO : total;
            List<BigDecimal> parts = new ArrayList<>(n);
            BigDecimal allocated = BigDecimal.ZERO;
            for (int i = 0; i < n; i++) {
                BigDecimal part;
                if (i == n - 1) {
                    part = base.subtract(allocated); // 余数, 保证 Σ == 原总量
                } else {
                    part = base.multiply(weights.get(i))
                            .divide(totalWeight, 6, java.math.RoundingMode.HALF_UP);
                    allocated = allocated.add(part);
                }
                parts.add(part);
            }
            result.add(parts);
        }
        return result;
    }

    /** 合成第 i 个产出的单产出行 (复制 base + 覆盖产出字段 + 使用拆分后的投入分量)。 */
    private ProcessSheetRowRequest synthesizeOutputRequest(ProcessSheetRowRequest base,
            ProcessSheetRowRequest.OutputLine o, int i,
            List<List<BigDecimal>> rawSplit, List<List<BigDecimal>> upSplit) {
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
        one.setProductWeight(o.getProductWeight());
        one.setPotCount(base.getPotCount());
        one.setSeasoningStep(base.isSeasoningStep());
        one.setLaborSegments(base.getLaborSegments());
        one.setCustomFields(base.getCustomFields());
        if (base.getRawMaterialInputs() != null) {
            List<ProcessSheetRowRequest.RawInput> raws = new ArrayList<>();
            for (int k = 0; k < base.getRawMaterialInputs().size(); k++) {
                ProcessSheetRowRequest.RawInput br = base.getRawMaterialInputs().get(k);
                ProcessSheetRowRequest.RawInput nr = new ProcessSheetRowRequest.RawInput();
                nr.setMaterialBatchId(br.getMaterialBatchId());
                nr.setQuantity(rawSplit.get(k).get(i));
                raws.add(nr);
            }
            one.setRawMaterialInputs(raws);
        }
        if (base.getUpstreamSources() != null) {
            List<ProcessSheetRowRequest.UpstreamRef> ups = new ArrayList<>();
            for (int m = 0; m < base.getUpstreamSources().size(); m++) {
                ProcessSheetRowRequest.UpstreamRef bu = base.getUpstreamSources().get(m);
                ProcessSheetRowRequest.UpstreamRef nu = new ProcessSheetRowRequest.UpstreamRef();
                nu.setSourceBatchNumber(bu.getSourceBatchNumber());
                nu.setFeedQuantityKg(upSplit.get(m).get(i));
                nu.setSemiFinished(bu.isSemiFinished());
                nu.setFinishedGoods(bu.isFinishedGoods());
                ups.add(nu);
            }
            one.setUpstreamSources(ups);
        }
        return one;
    }

    /**
     * 2B.2 B3: 多产出逐产出对齐 workflow 端口 (finished 类型 + 单位)。workflow 计划才校验;
     * legacy/未注入/该道不在图中 → 放行。产出按 workflowPortId 匹配端口 (缺则按序)。
     */
    private void validateMultiOutputAgainstWorkflow(String factoryId, String planId,
            ProcessSheetRowRequest req) {
        if (workflowClerkSheetService == null) {
            return;
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO config;
        try {
            config = workflowClerkSheetService.getWorkflowSheetConfig(factoryId, planId);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("2B.2 多产出 workflow 校验读取配置失败, 跳过 (不阻断): planId={}, err={}",
                    planId, e.getMessage());
            return;
        }
        if (config == null || config.getProcesses() == null) {
            return; // legacy
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.ProcessDescriptor desc =
                config.getProcesses().stream()
                        .filter(p -> p.getProcessOrder() != null
                                && p.getProcessOrder().equals(req.getProcessOrder()))
                        .findFirst()
                        .orElse(null);
        if (desc == null || desc.getOutputs() == null || desc.getOutputs().isEmpty()) {
            return; // 该道不在 workflow 图中 (不硬阻断)
        }
        Map<String, com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor> byPortId =
                new HashMap<>();
        for (com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor p : desc.getOutputs()) {
            if (p.getWorkflowPortId() != null) {
                byPortId.put(p.getWorkflowPortId(), p);
            }
        }
        List<ProcessSheetRowRequest.OutputLine> outs = req.getOutputs();
        for (int i = 0; i < outs.size(); i++) {
            ProcessSheetRowRequest.OutputLine o = outs.get(i);
            com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor port =
                    o.getWorkflowPortId() != null ? byPortId.get(o.getWorkflowPortId())
                            : (i < desc.getOutputs().size() ? desc.getOutputs().get(i) : null);
            if (port == null) {
                continue; // 无法定位端口 → 不硬阻断 (MVP, 同单产出 B3 宽松)
            }
            boolean expectFinished = Boolean.TRUE.equals(port.getFinished());
            if (o.isFinished() != expectFinished) {
                throw new BusinessException(409, "产出「"
                        + (port.getMaterialName() != null ? port.getMaterialName() : "")
                        + "」应为" + (expectFinished ? "成品" : "半成品") + ", 当前产出类型不符")
                        .withCode("WORKFLOW_ROW_OUTPUT_KIND_MISMATCH")
                        .withHint("请按 Workflow 配置的产出类型录入");
            }
            String expectUnit = port.getUnit();
            String actualUnit = firstNonBlank(o.getUnit(), req.getUnit());
            if (expectUnit != null && !expectUnit.isBlank()
                    && actualUnit != null && !actualUnit.isBlank()
                    && !expectUnit.equals(actualUnit)) {
                throw new BusinessException(409, "产出「"
                        + (port.getMaterialName() != null ? port.getMaterialName() : "")
                        + "」单位应为「" + expectUnit + "」, 当前为「" + actualUnit + "」")
                        .withCode("WORKFLOW_ROW_OUTPUT_UNIT_MISMATCH")
                        .withHint("请按 Workflow 配置的单位录入产出");
            }
        }
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
                                            ProcessSheetRow existing) {
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
                return buildResult(req, null, anchor, yieldRate(req), null, null, true, true, warnings);
            }
            // DRAFT → 物化: 像 create 一样新建批次 (DRAFT 之前无批, 无 id 可保)。
            String rawMaterialTypeId = resolveRawMaterialTypeId(req, edges);
            StepEntry step = buildStepEntry(factoryId, req);
            MaterializeContext ctx = new MaterializeContext(
                    factoryId,
                    req.isFinished() ? planId : null,
                    req.getProductTypeId(),
                    req.getBatchNumber(),
                    req.isFinished(),
                    clerkService.resolveLaborRate(factoryId, warnings),
                    clerkService.resolveWarehouseId(factoryId, WarehouseCodes.WH_WKS, warnings),
                    rawMaterialTypeId,
                    userId);
            MaterializedBatch mat = clerkService.materializeBatch(ctx, List.of(step), edges, warnings);
            updateRowInPlace(existing, req, mat.getProductionBatchId(), mat.getBatchNumber(), "SAVED");
            logChange(factoryId, planId, req, "UPDATE", beforeReq, req, userId);
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
        String rawMaterialTypeId = resolveRawMaterialTypeId(req, edges);
        StepEntry step = buildStepEntry(factoryId, req);
        MaterializeContext ctx = new MaterializeContext(
                factoryId,
                req.isFinished() ? planId : null,
                req.getProductTypeId(),
                existing.getBatchNumber(),  // 保留现有批次号
                req.isFinished(),
                clerkService.resolveLaborRate(factoryId, warnings),
                clerkService.resolveWarehouseId(factoryId, WarehouseCodes.WH_WKS, warnings),
                rawMaterialTypeId,
                userId);

        String existingWipMbId = wipOpt.map(MaterialBatch::getId).orElse(null);
        MaterializedBatch mat = clerkService.rematerializeInPlace(
                ctx, existing.getBatchId(), existingWipMbId, List.of(step), edges, warnings);

        // batchId/batchNumber 不变; 仅刷新 payload + status。
        updateRowInPlace(existing, req, existing.getBatchId(), existing.getBatchNumber(), "SAVED");
        logChange(factoryId, planId, req, "UPDATE", beforeReq, req, userId);
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

        for (ProcessSheetRow row : rows) {
            // 🔒 G3 防双扣 (同 resaveRow): 已小结入库的行不可删除。删除会逆向物化(软删已扣减的消耗边)
            // 而原料 usedQuantity 扣减从未反冲 → 账面超扣。完整撤销小结属 Phase 3。
            if (row.getInterimSettledAt() != null) {
                throw new BusinessException(409, "该行已小结入库,不可删除;如需更正请走撤销小结(功能开发中)")
                        .withCode("ROW_INTERIM_SETTLED")
                        .withHint("已小结入库的工序行不可删除,请通过撤销小结更正")
                        .withSeverity("BLOCKING");
            }
            if (row.getBatchId() != null) {
                // 查既有 WIP 产出批
                Optional<MaterialBatch> wipOpt = materialBatchRepo
                        .findByFactoryIdAndSourceDocTypeAndSourceDocId(
                                factoryId, "PRODUCTION_BATCH", row.getBatchId().toString());

                // 下游消耗守卫 (🔒): 谁消耗了本批的 WIP? 非空 → 拒绝
                if (wipOpt.isPresent()) {
                    List<MaterialConsumption> downstream = consumptionRepo
                            .findByFactoryIdAndBatchId(factoryId, wipOpt.get().getId());
                    if (!downstream.isEmpty()) {
                        throw new BusinessException(409,
                                "该批已被下游 " + downstream.size() + " 行消耗，请先删除下游行再改");
                    }
                }

                // 逆向物化 (软删边/报工/WIP/ProductionBatch)
                reverseMaterialization(factoryId, row.getBatchId(), wipOpt);
            } else if (ProcessSheetRow.STATUS_SAVED_SFI.equals(row.getRowStatus())) {
                // #1252 中段起步: SAVED_SFI 行 (batchId==null) 的产出已在保存时 SFI IN 入库 → 删除须冲销该入库
                //   (reverseClerkOutput; 下游已消耗则 409 SFI_DOWNSTREAM_CONSUMED 拒绝), 否则删行后 SFI 库存虚高 (幽灵)。
                reverseSfiOutput(factoryId, planId, tryDeserialize(row.getRowPayload()));
            }

            // SP-G P3: DELETE 操作记录 (before = 被删行 payload, after = null)。
            ProcessSheetRowRequest beforeReq = tryDeserialize(row.getRowPayload());
            logChange(factoryId, planId, beforeReq, "DELETE", beforeReq, null, userId,
                    row.getProcessCode(), row.getClientRowId());

            // 软删行本身
            row.softDelete();
            rowRepo.save(row);
        }
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

            result.add(ProcessSheetInventoryItem.builder()
                    .batchNumber(row.getBatchNumber())
                    .produced(produced)
                    .used(used)
                    .remaining(remaining)
                    .status(status)
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
                firstUnitByProductType.put(productTypeId, req.getUnit());
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

            BigDecimal input = req.getInputQuantity();
            BigDecimal stepYieldRate = null;
            if (input != null && input.compareTo(BigDecimal.ZERO) > 0) {
                stepYieldRate = produced
                        .multiply(BigDecimal.valueOf(100))
                        .divide(input, YIELD_SCALE, RoundingMode.HALF_UP);
            }

            String productTypeId = req.getProductTypeId();
            BigDecimal firstInput = productTypeId == null ? null : firstInputByProductType.get(productTypeId);
            String firstUnit = productTypeId == null ? null : firstUnitByProductType.get(productTypeId);
            BigDecimal gramsPerUnit = productTypeId == null ? null : gramsPerUnitByProductType.get(productTypeId);
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

            BigDecimal cumulativeDenominator = firstPositiveOrNull(
                    rowProvenance.inheritedRawEquivalentQuantity,
                    hasUpstreamSources(req) ? null : firstInput);
            BigDecimal cumulativeYieldRate = null;
            if (cumulativeDenominator != null && cumulativeDenominator.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal producedConverted = convertToFirstStepUnit(produced, unit, firstUnit, gramsPerUnit);
                if (producedConverted != null) {
                    cumulativeYieldRate = producedConverted
                            .multiply(BigDecimal.valueOf(100))
                            .divide(cumulativeDenominator, YIELD_SCALE, RoundingMode.HALF_UP);
                }
            }
            BigDecimal addedCost = rowTotalCost != null && rowProvenance.inheritedCost != null
                    ? rowTotalCost.subtract(rowProvenance.inheritedCost)
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
                assertSourceUnit(rawMb, requestInputUnit(req), "原料批次");
                ensureRawMaterialWarehouse(factoryId, planId, rawMb);
                edges.add(new ResolvedEdge(rawMb, nz(ri.getQuantity()), "RAW_MATERIAL"));
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
                    finishedGoodsBatchRepo.findByFactoryIdAndBatchNumber(factoryId, ur.getSourceBatchNumber())
                            .orElseThrow(() -> new BusinessException(409,
                                    "成品库存不存在: " + ur.getSourceBatchNumber())
                                    .withCode("FG_NOT_FOUND")
                                    .withHint("请重新选择仍有库存的成品批次")
                                    .withSeverity("BLOCKING")
                                    .withHintTarget(req.getProcessCode()));
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
                    wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                                    factoryId, ur.getSourceBatchNumber())
                            .orElseThrow(() -> new BusinessException(409,
                                    "半成品库存不存在: " + ur.getSourceBatchNumber())
                                    .withCode("SFI_NOT_FOUND")
                                    .withHint("请重新选择仍有库存的半成品批次")
                                    .withSeverity("BLOCKING")
                                    .withHintTarget(req.getProcessCode()));
                    continue;
                }
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
                assertSourceUnit(srcMb, requestInputUnit(req), "上游批次");
                edges.add(new ResolvedEdge(srcMb, nz(ur.getFeedQuantityKg()), "SEMI_FINISHED"));
            }
        }

        return edges;
    }

    private void normalizeConfiguredUnits(String factoryId, String planId, ProcessSheetRowRequest req) {
        Optional<ProductWorkProcess> configured = productWorkProcessRepo
                .findByFactoryIdAndProductTypeIdAndProcessOrder(
                        factoryId, req.getProductTypeId(), req.getProcessOrder());
        if (configured.isEmpty()) {
            // 2B (clerk-path workflow 联通): workflow 计划的产品(尤其成品)没有 legacy ProductWorkProcess 配置,
            // 单位以 workflow 端口投影为准。命中 workflow 描述符即用其投入/产出单位归一化(支持 kg→盒 双单位),
            // 不再因"未配置工序"拒绝。产出单位/类型另由 validateWorkflowRowIfApplicable(B3) 对齐端口。
            if (applyWorkflowConfiguredUnits(factoryId, planId, req)) {
                return;
            }
            // Legacy clients only sent one `unit` field. Preserve that established single-unit
            // contract, but do not allow a new dual-unit payload to bypass configuration.
            if (req.getInputUnit() == null && req.getOutputUnit() == null) {
                String legacyUnit = firstNonBlank(req.getUnit(), "kg");
                req.setInputUnit(legacyUnit);
                req.setOutputUnit(legacyUnit);
                req.setUnit(legacyUnit);
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
    }

    /**
     * 2B (clerk-path workflow 联通): workflow 计划的产品(尤其成品)没有 legacy ProductWorkProcess 配置,
     * 但报工单位需要以 workflow 端口投影为准 —— 例如成品包装工序 kg(半成品) → 盒(成品) 的换算行。
     *
     * <p>命中当前 processOrder 的 workflow 描述符即用其投入/产出端口单位归一化 req, 返回 {@code true}
     * (跳过"未配置工序"拒绝)。非 workflow 计划 / 该工序无描述符 → 返回 {@code false}, 交回 legacy/未配置
     * 分支处理。产出 kind/unit 另由 {@link #validateWorkflowRowIfApplicable} (B3) 对齐端口。
     */
    private boolean applyWorkflowConfiguredUnits(String factoryId, String planId, ProcessSheetRowRequest req) {
        if (workflowClerkSheetService == null || planId == null || req.getProcessOrder() == null) {
            return false;
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO config;
        try {
            config = workflowClerkSheetService.getWorkflowSheetConfig(factoryId, planId);
        } catch (BusinessException be) {
            // workflow 端口/SKU 失效等明确业务错误应上抛让文员看到, 不静默降级到 legacy。
            throw be;
        } catch (Exception e) {
            log.warn("[2B] applyWorkflowConfiguredUnits 读取 workflow 配置失败, 回落 legacy 分支: "
                    + "factory={} plan={} err={}", factoryId, planId, e.getMessage());
            return false;
        }
        if (config == null || config.getProcesses() == null) {
            return false; // 非 workflow 计划 → legacy 分支
        }
        com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.ProcessDescriptor descriptor = config.getProcesses().stream()
                .filter(p -> req.getProcessOrder().equals(p.getProcessOrder()))
                .findFirst()
                .orElse(null);
        if (descriptor == null || descriptor.getOutput() == null) {
            return false; // workflow 计划但本道工序无产出描述符 → legacy 分支
        }
        String outputUnit = firstNonBlank(descriptor.getOutput().getUnit(),
                descriptor.getPlannedUnit(), req.getOutputUnit(), req.getUnit(), "kg");
        String inputUnit = firstNonBlank(
                (descriptor.getInputs() != null && !descriptor.getInputs().isEmpty())
                        ? descriptor.getInputs().get(0).getUnit() : null,
                req.getInputUnit(), outputUnit);

        // 与 ProductWorkProcess 路径同语义: 请求单位若提供则必须匹配端口, 否则按端口单位归一化。
        assertConfiguredUnit(req.getInputUnit(), inputUnit, "投入");
        assertConfiguredUnit(req.getOutputUnit(), outputUnit, "产出");
        assertConfiguredUnit(req.getUnit(), outputUnit, "产出");

        req.setInputUnit(inputUnit);
        req.setOutputUnit(outputUnit);
        req.setUnit(outputUnit);
        return true;
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
        if (suppliedUnit == null || suppliedUnit.isBlank()
                || suppliedUnit.trim().equalsIgnoreCase(configuredUnit)) {
            return;
        }
        throw new BusinessException(409, "请求" + side + "单位为“" + suppliedUnit
                + "”，与工序配置单位“" + configuredUnit + "”不一致")
                .withCode("PROCESS_SHEET_UNIT_MISMATCH")
                .withHint("请刷新工序表后按配置单位录入；单位变更请在产品工序配置中维护")
                .withSeverity("BLOCKING")
                .withHintTarget("工序配置");
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
     * 逐工序表格的 RAW / 同计划 WIP 扣减没有通用跨单位换算表；若来源和本道投入单位不同，
     * 继续以数量相乘会把“只/袋”当 kg，直接污染库存与成本。因此这里明确阻断。
     * SFI/FG 来源沿用各自既有的严格换算/校验路径，不走这个 MaterialBatch 分支。
     */
    private void assertSourceUnit(MaterialBatch source, String expectedUnit, String sourceLabel) {
        String actualUnit = source == null ? null : source.getQuantityUnit();
        if (actualUnit == null || actualUnit.isBlank() || expectedUnit == null || expectedUnit.isBlank()
                || actualUnit.trim().equalsIgnoreCase(expectedUnit.trim())) {
            return;
        }
        throw new BusinessException(409, sourceLabel + "单位为“" + actualUnit + "”，不能按本道投入单位“"
                + expectedUnit + "”扣减")
                .withCode("PROCESS_SHEET_SOURCE_UNIT_MISMATCH")
                .withHint("请将工序投入单位设为来源批次单位，或先完成明确的单位换算")
                .withSeverity("BLOCKING")
                .withHintTarget("inputUnit");
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
     * SP-E FK 防线: WIP 产出 MaterialBatch.material_type_id 必须指向 raw_material_types。
     * 优先取首个 RAW 边的 materialTypeId; 否则取首个 SEMI 边上游 WIP 的 materialTypeId;
     * 仍为空 → 400 (H2 不会捕获 null FK, 代码层强制)。
     */
    private String resolveRawMaterialTypeId(ProcessSheetRowRequest req, List<ResolvedEdge> edges) {
        // 首个 RAW
        for (ResolvedEdge e : edges) {
            if ("RAW_MATERIAL".equals(e.getSourceType())
                    && e.getSourceBatch().getMaterialTypeId() != null) {
                return e.getSourceBatch().getMaterialTypeId();
            }
        }
        // 否则首个 SEMI 上游 WIP
        for (ResolvedEdge e : edges) {
            if ("SEMI_FINISHED".equals(e.getSourceType())
                    && e.getSourceBatch().getMaterialTypeId() != null) {
                return e.getSourceBatch().getMaterialTypeId();
            }
        }
        // 半成品库存(SFI)投料: SFI 只有 productType 维度, 无 raw_material_types FK,
        //   且 SFI 边已在 resolveEdges 跳过 → 无 RAW/in-plan-SEMI 边可派生 materialTypeId。
        //   成品道 (气调) 不物化 WIP MaterialBatch (materializeBatch 仅 !finished 时建 WIP), 此返回值不被使用
        //   → 返回 null (诚实, 无 raw lineage)。
        //   [option F] 非成品的「纯 SFI 中间道」已在 saveRow/resaveRow 上游拦截 (isPureSemiFinishedFed):
        //   不物化 WIP, 产出直接入 SFI, 根本不会走到这里。因此下面的 400 现在只作为防御, 仅对
        //   「非成品 + 含 SFI 投料 + 但混有无法派生 materialTypeId 的在制来源」这类残余不可解场景兜底。
        if (hasSemiFinishedUpstream(req) || hasFinishedGoodsUpstream(req)) {
            if (req.isFinished()) {
                return null;
            }
            throw new BusinessException(400,
                    "半成品(SFI)/成品(FG)库存投料无法确定物料类型: 该道混有无法派生物料类型的来源")
                    .withHint("请检查该道来源; 纯半成品/成品喂的中间道应不含其他无物料类型的在制来源")
                    .withHintTarget(req.getProcessCode());
        }
        throw new BusinessException(400, "无法确定原料类型，无法物化批次");
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

    /** yieldRate = output/input × 100 (scale 4, HALF_UP); input≤0 → null。 */
    private BigDecimal yieldRate(ProcessSheetRowRequest req) {
        BigDecimal output = req.getProductWeight() != null && req.getProductWeight().signum() > 0
                ? req.getProductWeight()
                : req.getOutputQuantity();
        if (output == null || req.getInputQuantity() == null || req.getInputQuantity().signum() <= 0) {
            return null;
        }
        return output.divide(req.getInputQuantity(), 4, RoundingMode.HALF_UP)
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
