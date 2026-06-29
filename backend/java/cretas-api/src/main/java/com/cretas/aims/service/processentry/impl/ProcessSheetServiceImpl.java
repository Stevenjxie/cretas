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
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import com.cretas.aims.service.processentry.ProcessSheetService;
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

    @Autowired(required = false)
    private WarehouseResolver warehouseResolver;

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

        // 2. upsert 键查重: 已存在 → 委托 re-save (Task 1.6 stub)
        Optional<ProcessSheetRow> existing = rowRepo
                .findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
                        factoryId, planId, req.getProcessCode(), req.getClientRowId());
        if (existing.isPresent()) {
            // TODO(Task 1.6): re-save = update-in-place 保 id (校验无下游消耗 + 重写边/报工)。
            return resaveRow(factoryId, planId, req, userId, existing.get());
        }

        List<String> warnings = new ArrayList<>();

        // 3. 解析上游消耗边 (factory-scoped, 🔒)
        List<ResolvedEdge> edges = resolveEdges(factoryId, req);

        // 6. outputQuantity gate: <=0 → 存 DRAFT 行, 不物化 WIP 批
        if (req.getOutputQuantity() == null || req.getOutputQuantity().signum() <= 0) {
            persistRow(factoryId, planId, req, null, null, "DRAFT");
            logChange(factoryId, planId, req, "CREATE", null, req, userId);
            // (req, batchId, batchNumber, yieldRate, rowTotalCost, unitPrice, updated, materialized, warnings)
            return buildResult(req, null, null, null, null, null, false, false, warnings);
        }

        // 4. SP-E FK 防线: WIP 批 material_type_id 必从原料或上游 WIP 派生 (空 → 400)
        String rawMaterialTypeId = resolveRawMaterialTypeId(req, edges);

        // 5. 映射单个 StepEntry
        StepEntry step = buildStepEntry(req);

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

        // 9. 组装结果
        return buildResult(req, mat.getProductionBatchId(), mat.getBatchNumber(),
                yieldRate(req), mat.getRowTotalCost(),
                unitPrice(mat.getRowTotalCost(), req.getOutputQuantity()), false, true, warnings);
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
        List<String> warnings = new ArrayList<>();

        // SP-G P3: 捕获变更前 payload (在任何 updateRowInPlace 之前), 供 UPDATE diff 审计。
        ProcessSheetRowRequest beforeReq = tryDeserialize(existing.getRowPayload());

        // 与 create 同的 factory-scoped 上游/原料边解析 (🔒)
        List<ResolvedEdge> edges = resolveEdges(factoryId, req);
        BigDecimal newOutput = req.getOutputQuantity();
        boolean hasOutput = newOutput != null && newOutput.signum() > 0;

        // ── CASE A: 之前是 DRAFT (无既有批次) ─────────────────────────
        if (existing.getBatchId() == null) {
            if (!hasOutput) {
                // 仍是 DRAFT —— 仅更新行 payload, 保持 DRAFT, 不物化。
                updateRowInPlace(existing, req, null, null, "DRAFT");
                logChange(factoryId, planId, req, "UPDATE", beforeReq, req, userId);
                return buildResult(req, null, null, null, null, null, true, false, warnings);
            }
            // DRAFT → 物化: 像 create 一样新建批次 (DRAFT 之前无批, 无 id 可保)。
            String rawMaterialTypeId = resolveRawMaterialTypeId(req, edges);
            StepEntry step = buildStepEntry(req);
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
        StepEntry step = buildStepEntry(req);
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
                    .build());
        }
        return result;
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
                    .sourceBatchNumber(rowProvenance.sourceBatchNumber)
                    .feedQuantity(rowProvenance.feedQuantity)
                    .sourceProducedQuantity(rowProvenance.sourceProducedQuantity)
                    .sourceConsumedRatio(rowProvenance.sourceConsumedRatio)
                    .inheritedRawEquivalentQuantity(rowProvenance.inheritedRawEquivalentQuantity)
                    .inheritedCost(rowProvenance.inheritedCost)
                    .addedCost(addedCost)
                    .sourceBreakdowns(rowProvenance.sourceBreakdowns)
                    .processOrder(row.getProcessOrder())
                    .processName(resolveRowProcessName(req, row, nameByOrderByProduct))
                    .unit(unit)
                    .stepYieldRate(stepYieldRate)
                    .cumulativeYieldRate(cumulativeYieldRate)
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
                        deserializePayload(row.getRowPayload())))
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

    private List<ResolvedEdge> resolveEdges(String factoryId, ProcessSheetRowRequest req) {
        List<ResolvedEdge> edges = new ArrayList<>();

        // 原料边 (修油首道领料) — factory-scoped raw MaterialBatch
        if (req.getRawMaterialInputs() != null) {
            for (ProcessSheetRowRequest.RawInput ri : req.getRawMaterialInputs()) {
                MaterialBatch rawMb = materialBatchRepo
                        .findByIdAndFactoryId(ri.getMaterialBatchId(), factoryId)
                        .orElseThrow(() -> new BusinessException(404,
                                "原料批次不存在: " + ri.getMaterialBatchId()));
                ensureRawMaterialWarehouse(factoryId, rawMb);
                edges.add(new ResolvedEdge(rawMb, nz(ri.getQuantity()), "RAW_MATERIAL"));
            }
        }

        // 混锅上游边 (SEMI_FINISHED) — 经持久化 batchNumber 解析上游 WIP MaterialBatch
        if (req.getUpstreamSources() != null) {
            for (ProcessSheetRowRequest.UpstreamRef ur : req.getUpstreamSources()) {
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
                edges.add(new ResolvedEdge(srcMb, nz(ur.getFeedQuantityKg()), "SEMI_FINISHED"));
            }
        }

        return edges;
    }

    private void ensureRawMaterialWarehouse(String factoryId, MaterialBatch rawMb) {
        if (warehouseResolver == null) {
            return;
        }
        String rawWarehouseId = warehouseResolver.resolveLogisticsId(factoryId);
        if (rawWarehouseId == null || rawWarehouseId.isBlank()) {
            throw new BusinessException(500, "未配置原料仓/物流仓，不能保存生产领料")
                    .withCode("PRODUCTION_RAW_WAREHOUSE_NOT_CONFIGURED")
                    .withHint("请先维护工厂仓库配置")
                    .withHintTarget("原料批次");
        }
        String batchWarehouseId = rawMb != null ? rawMb.getWarehouseId() : null;
        if (batchWarehouseId == null || batchWarehouseId.isBlank() || !rawWarehouseId.equals(batchWarehouseId)) {
            throw new BusinessException(409, "生产逐道报工原料只能从原料仓/物流仓领用，不能从其他仓库扣减")
                    .withCode("PRODUCTION_RAW_WAREHOUSE_REQUIRED")
                    .withHint("请重新选择原料仓/物流仓批次后再保存")
                    .withHintTarget("原料批次");
        }
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
        throw new BusinessException(400, "无法确定原料类型，无法物化批次");
    }

    // ─────────────────────────────────────────────────────────────
    // Request → StepEntry mapping
    // ─────────────────────────────────────────────────────────────

    private StepEntry buildStepEntry(ProcessSheetRowRequest req) {
        StepEntry st = new StepEntry();
        st.setProcessOrder(req.getProcessOrder());
        st.setProcessName(req.getProcessName());
        st.setProcessDate(req.getProcessDate());  // 跨天: 该工序实际操作日 → 报工日期
        // isSeasoningStep 决定: seasoningStep=true → processCategory=SEASONING;
        // 否则设非空非 SEASONING 值, 关闭 (processCategory==null && potCount!=null) 的启发式回退,
        // 避免普通带锅工序被误判为调味。
        st.setProcessCategory(req.isSeasoningStep() ? "SEASONING" : "NORMAL");
        st.setInputQuantity(req.getInputQuantity());
        st.setOutputQuantity(req.getOutputQuantity());
        st.setUnit(req.getUnit() != null ? req.getUnit() : "kg");
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
            // UK (factory,plan,processCode,clientRowId) 冲突 — 并发双 POST。
            // 完整幂等读已有行测在 Task 1.7; 这里映射 409 + 整事务回滚 loser 的物化图。
            throw new BusinessException(409, "该行已存在 (并发提交)");
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
        BigDecimal output = req.getOutputQuantity();
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

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
