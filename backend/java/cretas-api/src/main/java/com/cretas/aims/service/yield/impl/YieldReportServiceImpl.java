package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.FactorySettingsDTO;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.CostReconcileResult;
import com.cretas.aims.dto.yield.MaterialBatchRef;
import com.cretas.aims.dto.yield.MaterialInputRequest;
import com.cretas.aims.dto.yield.OrderYieldSummaryDTO;
import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.dto.yield.YieldLimitsDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.entity.recipe.ProcessMaterialRecipe;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.repository.recipe.ProcessMaterialRecipeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.CostReconcileService;
import com.cretas.aims.service.yield.YieldCalculationService;
import com.cretas.aims.service.yield.YieldReportService;
import com.cretas.aims.utils.ReportAuthGuard;
import com.cretas.aims.util.BackdateWindowValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cretas.aims.dto.yield.StepYieldDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class YieldReportServiceImpl implements YieldReportService {

    private static final String YIELD = "YIELD";
    private static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("0.30");
    private static final BigDecimal BD_60 = BigDecimal.valueOf(60);
    /** 守恒软校验阈值: |balance| / input > 15% → 告警 (非阻塞) */
    private static final BigDecimal BALANCE_WARN_THRESHOLD = new BigDecimal("0.15");

    private record BatchCloseReadiness(
            boolean ready,
            String message,
            int incompleteTaskCount,
            List<String> incompleteTaskSummary) {
    }

    private final ProductionReportRepository reportRepo;
    private final WorkProcessTaskRepository taskRepo;
    private final WorkProcessRepository processRepo;
    private final YieldCalculationService calcSvc;
    private final ProcessingService processingService;
    private final FactorySettingsRepository factorySettingsRepo;
    private final MaterialBatchRepository materialBatchRepository;
    private final ProductTypeRepository productTypeRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final SemiFinishedInventoryRepository wipRepo;
    private final BatchLineageEdgeRepository lineageEdgeRepo;
    private final ObjectMapper objectMapper;
    private final ProcessMaterialRecipeRepository recipeRepository;
    private final WipInventoryService wipInventoryService;
    private final ProductWorkProcessAssigneeRepository pwpAssigneeRepository;
    private final com.cretas.aims.repository.ProductWorkProcessRepository productWorkProcessRepository;
    private final CostReconcileService costReconcileService;

    /** C-074/C-075/X-10: 补录时效锁 (optional, fail-open 向后兼容). */
    @Autowired(required = false)
    private BackdateWindowValidator backdateWindowValidator;

    /**
     * A-F1/F2: 报工/领料前校验批次已开工。
     *
     * <p>六扇门需求 (requirements-catalog 行288/289): "开工前先看匹配工序和负责人再开工; 开工后批次工序
     * 自动下发到对应人手机APP, 登录账号后可选择报工" —— <b>开工(开始生产)是报工的前置步骤</b>。
     * {@code ProcessingServiceImpl.startProduction} 把批次置 IN_PROGRESS, 系统其它操作 (line 168/204/2393)
     * 都已校验 IN_PROGRESS, 唯独报工/领料漏了 → PLANNED 批次也能报工 (批次状态停在"计划中"但报工照过)。</p>
     *
     * <p>放行: IN_PROGRESS / PRODUCING / PAUSED。拦: PLANNED/PLANNING (尚未开工) + COMPLETED/CANCELLED (终态)。
     * 批次不存在 (旧数据/边界) → 不在此拦, 交下游处理 (向后兼容)。</p>
     */
    private void assertBatchStartedForReport(String factoryId, Long batchId, String action) {
        if (batchId == null) {
            return;
        }
        ProductionBatch pb;
        try {
            pb = productionBatchRepository.findByIdAndFactoryId(batchId, factoryId).orElse(null);
        } catch (RuntimeException e) {
            // fail-open: 读不到批次状态 (DB 瞬时故障) 不阻塞报工; report 真存不下时下游 save 会失败。
            // 与本服务既有 fail-soft 哲学一致 (rollup/lineage 写入失败也不阻塞)。
            log.warn("批次开工状态校验读取失败 (fail-open 放行): batchId={}, err={}", batchId, e.getMessage());
            return;
        }
        if (pb == null) {
            return;
        }
        ProductionBatchStatus st = pb.getStatus();
        if (st == ProductionBatchStatus.PLANNED || st == ProductionBatchStatus.PLANNING) {
            throw new BusinessException(409, "批次尚未开始生产，无法" + action)
                    .withHint("请先由主管在该批次点击「开始生产」后再" + action);
        }
        if (st == ProductionBatchStatus.COMPLETED || st == ProductionBatchStatus.CANCELLED) {
            throw new BusinessException(409, "批次已" + st.getDescription() + "，不可再" + action)
                    .withHint("如需调整请走撤回/重开流程");
        }
    }

    @Override
    @Transactional
    public Map<String, Object> submitReport(String factoryId, Long batchId, Long workerId, YieldReportRequest req) {
        // C-074/C-075/X-10: 补录时效锁 — 报工业务日期不得早于 T-maxDays (默认 T-2)
        if (backdateWindowValidator != null) {
            backdateWindowValidator.assertWithinWindow(req.getBusinessDate(), "报工");
        }
        if (req.getWorkProcessTaskId() == null) {
            throw new BusinessException(400, "缺少必填字段: workProcessTaskId")
                    .withHint("请选择工序任务").withHintTarget("workProcessTaskId");
        }
        // A-F1/F2: 批次须已开工 (开工→报工 是六扇门明确需求顺序)。
        assertBatchStartedForReport(factoryId, batchId, "报工");
        // 三阶段报工 (单元1): 阶段标记 INPUT/SEGMENT/OUTPUT; null = 旧式整合报工 (向后兼容)。
        String reportKind = normalizeReportKind(req.getReportKind());
        boolean isInput = "INPUT".equals(reportKind);
        boolean isSegment = "SEGMENT".equals(reportKind);
        boolean isOutput = "OUTPUT".equals(reportKind);
        boolean isLegacy = reportKind == null;
        boolean hasSourceWip = req.getSourceWipNo() != null && !req.getSourceWipNo().isBlank();
        // outputQuantity 必填仅在产出阶段 (OUTPUT) 或旧式整合报工 (legacy); INPUT/SEGMENT 阶段无产出。
        if ((isLegacy || isOutput) && req.getOutputQuantity() == null) {
            throw new BusinessException(400, "缺少必填字段: outputQuantity")
                    .withHint("请填写本道产出量").withHintTarget("outputQuantity");
        }
        WorkProcessTask t = taskRepo.findByFactoryIdAndId(factoryId, req.getWorkProcessTaskId())
                .orElseThrow(() -> new BusinessException(404, "工序任务不存在: " + req.getWorkProcessTaskId()));

        // 工序成本配置继承: 报工未传成本字段时, 从工序定义(ProductWorkProcess)继承默认值
        // (防呆: 操作员不手填会计类别/包装明细; 生产铺开一次性配置, 真实报工自动带出)。req 显式传值优先。
        // costCategory/auxAllocMethod 用"首个非 null"聚合(幂等), 任意阶段继承安全;
        // packagingDetail 是"跨报工拼接"(累加), 多阶段/多次报工各自继承会重复计 → 仅首条报工继承 (见下方 isFirstReportForTask 处)。
        String effCostCategory = req.getCostCategory();
        List<Map<String, Object>> effPackagingDetail = req.getPackagingDetail();
        String effAuxAllocMethod = req.getAuxAllocMethod();
        com.cretas.aims.entity.ProductWorkProcess pwpConfig = null;
        if (t.getProductWorkProcessId() != null
                && (effCostCategory == null || effPackagingDetail == null || effAuxAllocMethod == null)) {
            pwpConfig = productWorkProcessRepository
                    .findByFactoryIdAndId(factoryId, t.getProductWorkProcessId()).orElse(null);
            if (pwpConfig != null) {
                if (effCostCategory == null) effCostCategory = pwpConfig.getDefaultCostCategory();
                if (effAuxAllocMethod == null) effAuxAllocMethod = pwpConfig.getAuxAllocMethod();
                // packagingDetail 不在此继承; 延后到 isFirstReportForTask 已知处, 防多报工拼接重复计成本
            }
        }

        // T121 归属鉴权 — 操作员必须是工序 join 表中的负责人之一 (或兜底 responsible_worker_id); 主管可代报任意任务。
        boolean isSupervisor = ReportAuthGuard.isSupervisor(ReportAuthGuard.currentRole());
        if (t.getProductWorkProcessId() != null) {
            // T121: load multi-assignee set from join table (fallback to assigned_to for old tasks)
            java.util.List<Long> joinAssigneeIds = pwpAssigneeRepository
                    .findByProductWorkProcessId(t.getProductWorkProcessId())
                    .stream()
                    .map(a -> a.getWorkerId())
                    .collect(java.util.stream.Collectors.toList());
            // Determine the responsible_worker_id from the PWP row (task.assignedTo is the primary)
            ReportAuthGuard.assertCanReport(t.getAssignedTo(), joinAssigneeIds, workerId, isSupervisor);
        } else {
            // Legacy path: no productWorkProcessId → single-value check
            ReportAuthGuard.assertCanReport(t.getAssignedTo(), workerId, isSupervisor);
        }

        // 三阶段字段隔离 (防御: 防止误填的非本阶段字段污染同 task 跨报工累加)。
        // "生效值": 阶段决定哪些请求字段被采纳, 其余强制 null。legacy (null) 全采纳 (行为完全不变)。
        //   INPUT  : 留 input/inputUnit/materialBatchRefs/sourceWipNo/evidenceImages; output/segment/byproduct/waste/sample 强制 null。
        //   SEGMENT: 留 laborSegments(单段回退 workerCount/workMinutes)/evidenceImages; input/output/material/byproduct/waste/sample 强制 null。
        //   OUTPUT : 留 output/outputUnit/byproducts/waste/sample/evidenceImages; input/segment 强制 null。
        BigDecimal effInput = (isSegment || isOutput) ? null : req.getInputQuantity();
        String effInputUnit = (isSegment || isOutput) ? null : req.getInputUnit();
        BigDecimal effOutput = (isInput || isSegment) ? null : req.getOutputQuantity();
        String effOutputUnit = (isInput || isSegment) ? null : req.getOutputUnit();
        List<YieldReportRequest.LaborSegment> effSegs = (isInput || isOutput) ? null : req.getLaborSegments();
        List<MaterialBatchRef> effMaterialRefs = (isSegment || isOutput) ? null : req.getMaterialBatchRefs();
        BigDecimal effSourceWipQuantity = (isSegment || isOutput || !hasSourceWip) ? null : sourceWipQuantity(req, effInput);
        List<YieldReportRequest.Byproduct> effByproducts = (isInput || isSegment) ? null : req.getByproducts();
        BigDecimal effWaste = (isInput || isSegment) ? null : req.getWasteQuantity();
        Integer effSampleRetain = (isInput || isSegment) ? null : req.getSampleRetainQuantity();
        // 单段 workerCount/workMinutes (旧路径回退): SEGMENT/legacy 计工时; INPUT/OUTPUT 不计。
        Integer effReqWorkMinutes = (isInput || isOutput) ? null : req.getWorkMinutes();
        Integer effReqWorkerCount = (isInput || isOutput) ? null : req.getWorkerCount();

        // M3: targetWorkerId (代报) 仅主管可用; 操作员传则忽略, 强制为登录者。
        Long effectiveWorker = (isSupervisor && req.getTargetWorkerId() != null) ? req.getTargetWorkerId() : workerId;

        // — G7 部分领用防呆 (Rule 1): 报工带 sourceWipNo 时, 校验 inputQuantity ≤ 源 WIP 可申领余额 —
        // 可申领余额 = 库存余额 - 待审批占用; 校验在保存前, 通过 WipInventoryService 保持两条报工栈口径一致。
        // sourceWipNo=null → 走旧路径 (首道领原料 / 老批次, 向后兼容, 不查 WIP)。
        // 三阶段 (单元1): 领料发生在 INPUT 阶段 (或 legacy); SEGMENT/OUTPUT 阶段不消耗源 WIP (input 已被隔离为 null)。
        SemiFinishedInventory sourceWip = null;
        if ((isInput || isLegacy) && hasSourceWip) {
            sourceWip = wipInventoryService.validateSourceWip(
                    factoryId, req.getSourceWipNo(), effSourceWipQuantity, effInputUnit, null);
        }

        // 前置查该 task 已有 YIELD 报工: 决定是否首条 + 作双写求和基数
        List<ProductionReport> existingTaskReports = reportRepo.findYieldReportsByTask(factoryId, t.getId());
        boolean isFirstReportForTask = existingTaskReports.isEmpty();

        // 包装模板继承: 仅首条报工继承 (packagingDetail 跨报工拼接累加, 多阶段/多次报工各自继承会重复计成本)。
        // req 显式传值优先; 仅当本次未传且为该 task 首条报工时, 从工序定义继承一次。
        if (effPackagingDetail == null && isFirstReportForTask && pwpConfig != null) {
            effPackagingDetail = pwpConfig.getPackagingTemplate();
        }

        // — A.4/A.5: 逐道成本 (人工 + 材料), 诚实 null 传播 (缺输入则该项 null, 绝不默认 0) —
        // 人工成本依赖本道 WorkProcess.standardHourlyRate; sourceWip 已在上方解析 (G7 路径)。
        // 三阶段 (单元1): 材料成本算在 INPUT 阶段 (effMaterialRefs 仅 INPUT/legacy 非空), 人工成本算在 SEGMENT 阶段
        //   (effSegs/effReqWork* 仅 SEGMENT/legacy 非空); OUTPUT 阶段两者均 null (成本在 INPUT/SEGMENT 报工上)。
        WorkProcess costWp = processRepo.findById(t.getWorkProcessId()).orElse(null);
        BigDecimal hourlyRate = costWp == null ? null : costWp.getStandardHourlyRate();
        // 适配单元3: 优先多段工时 person-hours; 段为空退回单一 workerCount/workMinutes (back-compat)
        List<YieldReportRequest.LaborSegment> segs = effSegs;
        BigDecimal laborCost = computeLaborCost(segs, effReqWorkerCount, effReqWorkMinutes, hourlyRate);
        BigDecimal materialCost = computeMaterialCost(effMaterialRefs,
                sourceWip, effSourceWipQuantity, effInput,
                factoryId, t.getWorkProcessId(), effOutput);

        // 适配单元3: 多段工时时, totalWorkMinutes = Σ段时长, totalWorkers = MAX headcount (峰值, 修 M2);
        // 段为空时退回单一 req.getWorkMinutes()/getWorkerCount() (零回归)。
        Integer effectiveWorkMinutes = effReqWorkMinutes;
        Integer effectiveWorkers = effReqWorkerCount;
        boolean hasSegs = segs != null && !segs.isEmpty();
        if (hasSegs) {
            Integer sumMinutes = null;
            Integer maxHead = null;
            for (YieldReportRequest.LaborSegment s : segs) {
                Integer dur = segmentMinutes(s);
                if (dur != null) sumMinutes = (sumMinutes == null ? 0 : sumMinutes) + dur;
                if (s.getHeadcount() != null) {
                    maxHead = (maxHead == null ? s.getHeadcount() : Math.max(maxHead, s.getHeadcount()));
                }
            }
            effectiveWorkMinutes = sumMinutes;
            effectiveWorkers = maxHead;   // 峰值人数 (非 SUM) — 修 M2 inflation
        }

        ProductionReport r = ProductionReport.builder()
                .factoryId(factoryId).batchId(batchId).reportType(YIELD)
                .reportKind(reportKind)                   // 三阶段 (单元1): INPUT/SEGMENT/OUTPUT; null=旧式整合
                .workerId(effectiveWorker).reporterName(req.getReporterName())
                // C-074/C-075/X-10: 客户传 businessDate 时采用 (补录场景); null 则取今天 (正常实时)
                .reportDate(req.getBusinessDate() != null ? req.getBusinessDate() : LocalDate.now())
                .workProcessTaskId(t.getId()).processOrder(t.getProcessOrder())
                .productTypeId(t.getProductTypeId())
                .inputQuantity(effInput).inputUnit(effInputUnit)
                .outputQuantity(effOutput).outputUnit(effOutputUnit)
                .totalWorkMinutes(effectiveWorkMinutes)   // 适配单元3: 多段=Σ段时长, 单段=effReqWorkMinutes
                .totalWorkers(effectiveWorkers)           // 适配单元3: 多段=MAX headcount (修 M2), 单段=effReqWorkerCount
                .laborCost(laborCost)                 // A.4: 本道人工成本 (null=缺输入); 三阶段: 仅 SEGMENT/legacy
                .materialCost(materialCost)           // A.5: 本道材料成本 (null=无价); 三阶段: 仅 INPUT/legacy
                .sourceBatchRefs(req.getSourceBatchRefs())
                // A2b: 领料批次引用直接挂在报工单上 (不再单独调用 recordMaterialInput); 三阶段: 仅 INPUT/legacy
                .materialBatchRefs(toMaterialBatchRefMaps(effMaterialRefs))
                // 适配单元3: 传统报工证据/工时段/副产物/损耗/留样 (各报工携带自身明细, 聚合时合并)
                .photos(req.getEvidenceImages())          // 证据图片各阶段都可带 (按 reportKind 分组到 input/outputPhotos)
                .photoAnnotations(toPhotoAnnotationMaps(req.getPhotoAnnotations()))  // T161 per-photo annotation
                .laborSegments(toLaborSegmentMaps(segs))
                .byproducts(toByproductMaps(effByproducts))
                .wasteQuantity(effWaste)
                .sampleRetainQuantity(effSampleRetain)
                .costCategory(effCostCategory)         // CALC-003: 成本类别 (req优先, 否则继承工序配置; null=启发式)
                .packagingDetail(effPackagingDetail)   // AUDIT-002: 包装明细 (req优先, 否则继承工序模板)
                .auxPotNo(req.getAuxPotNo())                 // AUDIT-004: 共享锅(运行时锅号, 不继承)
                .auxPotTotalCost(req.getAuxPotTotalCost())
                .auxAllocMethod(effAuxAllocMethod)     // AUDIT-004: 分摊方式 (req优先, 否则继承工序配置)
                .customFields(buildYieldCustomFields(reportKind, sourceWip == null ? null : effSourceWipQuantity))
                // 工序批次号是任务级: 仅首条报工生成, 后续条 null (避免 uq_pr_intermediate_batch_no 冲突)
                .intermediateBatchNo(isFirstReportForTask ? generateBatchNo(t, batchId) : null)
                // G7: 本道领用的源 WIP 工序批次号 (向后兼容: null 走旧路径); 三阶段: 仅 INPUT/legacy 消耗
                .sourceWipNo((isInput || isLegacy) ? req.getSourceWipNo() : null)
                // ==================== SP1 双产出字段 ====================
                // null = 旧式报工 (向后兼容零回归); postApprovedOutput 按 outputKind 分支处理
                .outputKind(req.getOutputKind())
                .semiOutputQuantity(req.getSemiOutputQuantity())
                .semiOutputUnit(req.getSemiOutputUnit())
                .semiCode(req.getSemiCode())
                .status(ProductionReport.Status.SUBMITTED)
                .build();

        // carryover = 上道总产出 - 本道投入 (单批记录值, 不进库存); 三阶段: 仅 INPUT/legacy 有投入
        r.setCarryoverQuantity(computeCarryover(factoryId, batchId, t, effInput));

        // — 超收检查 (A4) —
        // 基准量 = 本道投入 × WorkProcess.standardYieldMax。
        // 三阶段 (单元1): 超收针对"产出"判定, 仅在有产出 (OUTPUT/legacy) 且本道有投入基准时检查。
        //   投入基准: OUTPUT 阶段本报工 effInput 为 null, 取该 task 历史 INPUT 报工的 Σ inputQuantity (跨阶段);
        //   legacy 直接用本报工 effInput。INPUT/SEGMENT 阶段无产出 → effOutput null → 跳过。
        BigDecimal inputQty = isOutput
                ? existingTaskReports.stream()
                        .map(ProductionReport::getInputQuantity)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                : effInput;
        if (effOutput != null && inputQty != null && inputQty.compareTo(BigDecimal.ZERO) > 0) {
            Optional<WorkProcess> wpOpt = processRepo.findById(t.getWorkProcessId());
            BigDecimal syMax = wpOpt.map(WorkProcess::getStandardYieldMax).orElse(null);
            if (syMax != null) {
                BigDecimal target = inputQty.multiply(syMax);
                if (target.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal tolerance = getToleranceForFactory(factoryId);
                    BigDecimal maxAllowed = target.multiply(BigDecimal.ONE.add(tolerance));

                    BigDecimal alreadyReported = existingTaskReports.stream()
                            .map(ProductionReport::getOutputQuantity)
                            .filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal cumulative = alreadyReported.add(effOutput);

                    if (cumulative.compareTo(maxAllowed) > 0) {
                        boolean force = Boolean.TRUE.equals(req.getForceSubmit());
                        if (!force) {
                            String unit = wpOpt.map(WorkProcess::getUnit).orElse("");
                            String actionHint = String.format(
                                    "已报 %.2f %s, 目标 %.2f %s (投入 %.2f × 标准上限 %.0f%%), 含 %.0f%% 超收容差最多可报 %.2f %s",
                                    alreadyReported, unit,
                                    target, unit,
                                    inputQty, syMax.multiply(BigDecimal.valueOf(100)),
                                    tolerance.multiply(BigDecimal.valueOf(100)),
                                    maxAllowed.setScale(2, RoundingMode.HALF_UP), unit);
                            throw new BusinessException(409, "产出量超过超收容差上限")
                                    .withCode("OVER_RECEIPT")
                                    .withHint(actionHint)
                                    .withSeverity("BLOCKING");
                        }
                        // forceSubmit=true: 记录告警, 正常保存
                        log.warn("[A4-超收] factory={} batch={} task={} target={} cumulative={} maxAllowed={} force=true",
                                factoryId, batchId, t.getId(), target, cumulative, maxAllowed);
                    }
                }
            }
        }

        ProductionReport saved = reportRepo.save(r);

        // A2b: 报工单保存后, 对每个关联批次独立检查自动结清 (order: save → settle); 三阶段: 仅 INPUT/legacy 有领料
        if (effMaterialRefs != null) {
            for (MaterialBatchRef ref : effMaterialRefs) {
                if (ref.getMaterialBatchId() != null) {
                    checkAndAutoSettle(factoryId, batchId, ref.getMaterialBatchId());
                }
            }
        }

        // M2 SP9: 每次报工后增量回写 ProductionBatch.laborCost = Σ YIELD 报工人工成本聚合。
        // fail-soft: 不影响主线报工。standard_hourly_rate 未配时 laborCost=null → null 诚实传播。
        rollupLaborCostToBatch(factoryId, batchId);

        // 双写 WorkProcessTask.actualQuantity = Σ该任务 YIELD output (权威=YIELD, 老字段保兼容)
        // 已有报工产出 + 本次 (显式加本次, 不依赖 JPQL flush 时序)
        BigDecimal taskTotal = existingTaskReports.stream()
                .map(ProductionReport::getOutputQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (saved.getOutputQuantity() != null) {
            taskTotal = taskTotal.add(saved.getOutputQuantity());
        }
        t.setActualQuantity(taskTotal);
        // 同单未完结续报 (部分产出/继续产出): OUTPUT/Legacy 报工后是否置任务 COMPLETED 由 markComplete 决定。
        //   markComplete == null  → 向后兼容: 立即 COMPLETED (历史行为, 零回归)。
        //   markComplete == TRUE  → 显式完工: 累加产出后 COMPLETED (出成率锁定)。
        //   markComplete == FALSE → 部分产出留单继续: 累加产出, 任务保持 IN_PROGRESS, 可后续再报。
        // 注意: 不引入内层 @Transactional, 直接 set 后 save, 与上面 actualQuantity 同一 save 合并。
        boolean markComplete = !Boolean.FALSE.equals(req.getMarkComplete());  // null/true → 完工; false → 续报
        boolean taskCompleted = false;
        if (isOutput || isLegacy) {
            if (markComplete) {
                t.setStatus(WorkProcessTask.Status.COMPLETED);
                t.setCompletedBy(effectiveWorker);
                t.setCompletedAt(LocalDateTime.now());
                taskCompleted = true;
            } else {
                // 部分产出: 保持 IN_PROGRESS 可续报 (兜底: 若任务为初始 PENDING, 报了产出说明已在生产, 推进到 IN_PROGRESS)。
                if (t.getStatus() == WorkProcessTask.Status.PENDING) {
                    t.setStatus(WorkProcessTask.Status.IN_PROGRESS);
                }
            }
        }
        taskRepo.save(t);

        // WIP 正式消耗/产出统一在 Web 审批通过后由 WipInventoryService.postApprovedOutput 过账。
        // PENDING 报工只作为待审批占用参与后续可领余额计算, 避免驳回时库存已被提前扣减。

        Map<String, Object> out = new HashMap<>();
        out.put("reportId", saved.getId());
        // 同单未完结续报: 回传任务是否已完工 + 累计产出 (供 RN "已产出 X, 可继续产出" 防呆提示)。
        // 仅在有产出阶段 (OUTPUT/legacy) 附带; INPUT/SEGMENT 不涉及完工语义。
        if (isOutput || isLegacy) {
            out.put("taskCompleted", taskCompleted);
            out.put("cumulativeOutput", taskTotal);  // Σ该任务全部 OUTPUT 产出 (含本次)
        }

        // yieldRate: 单报工内即时出成率 (需同报工带可比 input+output → 仅 legacy 整合报工有意义)。
        // 三阶段 INPUT/SEGMENT/OUTPUT 各只带单边量, 单报工 yieldRate 为 null (整道出成率经 calculateSteps 跨阶段算)。
        BigDecimal yieldRate = null;
        if (effInputUnit != null && effInputUnit.equals(effOutputUnit)
                && effInput != null && effInput.compareTo(BigDecimal.ZERO) > 0
                && effOutput != null) {
            yieldRate = effOutput.divide(effInput, 4, RoundingMode.HALF_UP);
        }
        out.put("yieldRate", yieldRate);
        String alert = yieldAlert(t.getWorkProcessId(), yieldRate);
        if (alert != null) out.put("alert", alert);
        // 适配单元3 (Part C): 守恒软校验 (非阻塞), 仅偏差 > 15% 且单位可比时附 balanceWarning。
        // legacy: 同一报工带 input+output, 直接校验。
        // F2 OUTPUT 阶段: 聚合该 task 所有 INPUT 报工的 Σ inputQuantity 对比本次产出 (跨阶段守恒)。
        if (isLegacy) {
            String balanceWarning = computeBalanceWarning(req);
            if (balanceWarning != null) out.put("balanceWarning", balanceWarning);
        } else if (isOutput) {
            // F2: Σ 该 task 历史 INPUT 报工的 inputQuantity (含本次 save 前的存量)
            BigDecimal sumInput = existingTaskReports.stream()
                    .filter(x -> "INPUT".equals(x.getReportKind()))
                    .map(ProductionReport::getInputQuantity)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // 取历史 INPUT 报工的 inputUnit (首个非 null)
            String sumInputUnit = existingTaskReports.stream()
                    .filter(x -> "INPUT".equals(x.getReportKind()))
                    .map(ProductionReport::getInputUnit)
                    .filter(Objects::nonNull)
                    .findFirst().orElse(null);
            String balanceWarning = computeBalanceWarningForOutput(
                    sumInput, effOutput, sumInputUnit, effOutputUnit,
                    effByproducts, effWaste, effSampleRetain);
            if (balanceWarning != null) out.put("balanceWarning", balanceWarning);
        }
        return out;
    }

    private String yieldAlert(String workProcessId, BigDecimal yieldRate) {
        if (yieldRate == null || workProcessId == null) return null;
        Optional<WorkProcess> wpOpt = processRepo.findById(workProcessId);
        if (wpOpt.isEmpty()) return null;
        WorkProcess wp = wpOpt.get();
        if (wp.getStandardYieldMin() != null && yieldRate.compareTo(wp.getStandardYieldMin()) < 0) return "BELOW_MIN";
        if (wp.getStandardYieldMax() != null && yieldRate.compareTo(wp.getStandardYieldMax()) > 0) return "ABOVE_MAX";
        return null;
    }

    /**
     * A4: 读取工厂级超收容差. 无配置或解析失败时返回默认 30%.
     * 使用 {@code findProductionSettingsByFactoryId} 投影查询 (仅读 TEXT 列, 不加载完整实体).
     * <p>注意: {@code BusinessException.withCode} 的 errorCode 通过 ApiResponse.errorWithCode 对外暴露,
     * 但 hintTarget 不经该路径传播.</p>
     */
    private BigDecimal getToleranceForFactory(String factoryId) {
        try {
            String json = factorySettingsRepo.findProductionSettingsByFactoryId(factoryId);
            if (json == null) return DEFAULT_TOLERANCE;
            FactorySettingsDTO.ProductionSettings ps =
                    objectMapper.readValue(json, FactorySettingsDTO.ProductionSettings.class);
            return ps.getYieldOverReceiptTolerance() != null
                    ? ps.getYieldOverReceiptTolerance()
                    : DEFAULT_TOLERANCE;
        } catch (Exception e) {
            log.warn("[A4] 读取容差设置失败, 使用默认 30%", e);
            return DEFAULT_TOLERANCE;
        }
    }

    @Override
    public YieldLimitsDTO getLimits(String factoryId, Long batchId, Long workProcessTaskId, BigDecimal inputQuantity) {
        WorkProcessTask t = taskRepo.findByFactoryIdAndId(factoryId, workProcessTaskId)
                .orElseThrow(() -> new BusinessException(404, "工序任务不存在: " + workProcessTaskId));

        String workProcessId = t.getWorkProcessId();
        Optional<WorkProcess> wpOpt = processRepo.findById(workProcessId);
        BigDecimal syMax = wpOpt.map(WorkProcess::getStandardYieldMax).orElse(null);
        String unit = wpOpt.map(WorkProcess::getUnit).orElse("");

        BigDecimal alreadyReported = reportRepo.findYieldReportsByTask(factoryId, t.getId()).stream()
                .map(ProductionReport::getOutputQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal toleranceRate = getToleranceForFactory(factoryId);

        // Compute target / maxAllowed / remaining only when we have a valid base
        BigDecimal targetQuantity = null;
        BigDecimal maxAllowed = null;
        BigDecimal remaining = null;
        String message;

        boolean hasInput = inputQuantity != null && inputQuantity.compareTo(BigDecimal.ZERO) > 0;
        if (!hasInput) {
            message = "未填投入量, 无超收告警";
        } else if (syMax == null) {
            message = "该工序未配置标准出成上限, 无超收告警";
        } else {
            targetQuantity = inputQuantity.multiply(syMax);
            if (targetQuantity.compareTo(BigDecimal.ZERO) > 0) {
                maxAllowed = targetQuantity.multiply(BigDecimal.ONE.add(toleranceRate))
                        .setScale(4, RoundingMode.HALF_UP);
                remaining = maxAllowed.subtract(alreadyReported);
                message = String.format("已报 %.2f %s / 目标 %.2f %s / 含 %.0f%% 超收容差最多可报 %.2f %s",
                        alreadyReported, unit,
                        targetQuantity, unit,
                        toleranceRate.multiply(BigDecimal.valueOf(100)),
                        maxAllowed, unit);
            } else {
                message = "投入量为零, 无超收告警";
            }
        }

        // G7 防呆 Rule 1: 本道可领的源 WIP 余额 = Σ 上道工序产出的 AVAILABLE WIP available_quantity。
        // 首道 (processOrder<=1 或上道无 WIP 行) → null (领原料, 不受 WIP 约束)。
        BigDecimal wipAvailable = resolveSourceWipAvailable(factoryId, batchId, t.getProcessOrder());
        // G7 跨单位防呆: 源 WIP 余额的真实单位 (= 上道 outputUnit), RN banner/:max 用它而非本道 unit。
        String wipAvailableUnit = resolveSourceWipUnit(factoryId, batchId, t.getProcessOrder());
        // G7 Wave 4: 上道恰有一笔可领 WIP → 回显其工序批次号, RN 报工直接带 sourceWipNo。
        String sourceWipNo = resolveSourceWipNo(factoryId, batchId, t.getProcessOrder());

        return YieldLimitsDTO.builder()
                .workProcessTaskId(workProcessTaskId)
                .targetQuantity(targetQuantity)
                .standardYieldMax(syMax)
                .unit(unit)
                .alreadyReported(alreadyReported)
                .toleranceRate(toleranceRate)
                .maxAllowed(maxAllowed)
                .remaining(remaining)
                .wipAvailable(wipAvailable)
                .wipAvailableUnit(wipAvailableUnit)
                .sourceWipNo(sourceWipNo)
                .message(message)
                .build();
    }

    /**
     * G7: 计算本道 (processOrder) 可领的源 WIP 余额 = 上道 (processOrder-1) 全部 AVAILABLE WIP 的
     * Σ available_quantity。供 RN 领用 input 的 {@code :max} 防呆。
     *
     * <p>首道 (processOrder=null 或 ≤1) 或上道无 WIP 行 → null (领原料, 不受 WIP 余额约束)。
     * 上道有 WIP 行但已领空 → 0。</p>
     */
    private BigDecimal resolveSourceWipAvailable(String factoryId, Long batchId, Integer processOrder) {
        if (processOrder == null || processOrder <= 1 || batchId == null) {
            return null;  // 首道领原料, 无源 WIP
        }
        int prevOrder = processOrder - 1;
        List<SemiFinishedInventory> wips =
                wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, batchId);
        List<SemiFinishedInventory> prev = wips.stream()
                .filter(w -> w.getProcessOrder() != null && w.getProcessOrder() == prevOrder)
                .filter(w -> !SemiFinishedInventory.Status.RETURNED.equals(w.getStatus()))
                .collect(Collectors.toList());
        if (prev.isEmpty()) {
            return null;  // 上道还没产出 WIP (例如本道是非 WIP 路径), 不约束
        }
        return prev.stream()
                .map(w -> nz(w.getAvailableQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * G7 跨单位防呆: 解析本道 (processOrder) 源 WIP 余额的单位 (= 上道 outputUnit)。
     * 取上道 (processOrder-1) 非 RETURNED WIP 行的首个非空单位 (同道工序产出单位一致)。
     *
     * <p>供 RN 报工 banner / input {@code :max} 用源 WIP 真实单位 (而非本道 WorkProcess.unit),
     * 避免跨单位 (kg→份) 场景下 :max 显示错误单位误导操作员。
     * 与 {@link #resolveSourceWipAvailable} 同口径过滤 (首道 / 上道无 WIP → null)。</p>
     */
    private String resolveSourceWipUnit(String factoryId, Long batchId, Integer processOrder) {
        if (processOrder == null || processOrder <= 1 || batchId == null) {
            return null;  // 首道领原料, 无源 WIP
        }
        int prevOrder = processOrder - 1;
        return wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, batchId).stream()
                .filter(w -> w.getProcessOrder() != null && w.getProcessOrder() == prevOrder)
                .filter(w -> !SemiFinishedInventory.Status.RETURNED.equals(w.getStatus()))
                .map(SemiFinishedInventory::getUnit)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * G7 Wave 4: 解析本道 (processOrder) 应领用的源 WIP 工序批次号。
     *
     * <p>仅当上道 (processOrder-1) <b>恰有一笔</b> AVAILABLE 且 available_quantity &gt; 0 的 WIP 行时,
     * 回显其 {@code intermediate_batch_no} (RN 报工 req 直接带它)。零笔或多笔 (歧义) → null:
     * 首道领原料无源 WIP; 多笔时不自动猜, 由前端经 {@code GET /wip} 显式选择。</p>
     */
    private String resolveSourceWipNo(String factoryId, Long batchId, Integer processOrder) {
        if (processOrder == null || processOrder <= 1 || batchId == null) {
            return null;  // 首道领原料, 无源 WIP
        }
        int prevOrder = processOrder - 1;
        List<SemiFinishedInventory> available = wipRepo
                .findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, batchId).stream()
                .filter(w -> w.getProcessOrder() != null && w.getProcessOrder() == prevOrder)
                .filter(w -> SemiFinishedInventory.Status.AVAILABLE.equals(w.getStatus()))
                .filter(w -> nz(w.getAvailableQuantity()).compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());
        if (available.size() != 1) {
            return null;  // 0 笔 (无可领) 或 多笔 (歧义, 前端经 GET /wip 显式选)
        }
        return available.get(0).getIntermediateBatchNo();
    }

    private BigDecimal computeCarryover(String factoryId, Long batchId, WorkProcessTask t, BigDecimal thisInput) {
        if (t.getProcessOrder() == null || t.getProcessOrder() <= 1 || thisInput == null) return null;
        List<ProductionReport> all = reportRepo.findYieldReportsByBatch(factoryId, batchId);
        BigDecimal prevOutput = all.stream()
                .filter(x -> x.getProcessOrder() != null && x.getProcessOrder() == t.getProcessOrder() - 1)
                .map(ProductionReport::getOutputQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (prevOutput.compareTo(BigDecimal.ZERO) == 0) return null;
        return prevOutput.subtract(thisInput);
    }

    private String generateBatchNo(WorkProcessTask t, Long batchId) {
        // {产品码}-B{批次}-S{工序序}-{taskId} 人易读 + 跨 SKU 唯一 (张权 A6)
        return String.format("%s-B%d-S%d-%d",
                t.getProductTypeId() == null ? "NA" : t.getProductTypeId(),
                batchId, t.getProcessOrder() == null ? 0 : t.getProcessOrder(), t.getId());
    }

    // ==================== G6/G7 WIP (Wave 2) ====================

    /**
     * G6: 本道产出 upsert 进 WIP 库存 (按 task 的工序批次号幂等)。
     *
     * <p>幂等键 = {@link #generateBatchNo} 的稳定派生 (task-level, 与首条报工生成的
     * {@code intermediate_batch_no} 一致)。后续条报工 {@code report.intermediateBatchNo} 为 null,
     * 故这里**重新派生**同一稳定键, 命中同一 WIP 行累加 {@code produced/available} (跨天天然支持)。</p>
     *
     * <p>溯源 {@code materialBatchRefs} 从产出报工继承 (首条建行时写, 后续累加不覆盖)。</p>
     */
    private void upsertProducedWip(String factoryId, Long batchId, WorkProcessTask t, ProductionReport saved) {
        // 默认成本滚动用本报工的 labor+material (legacy 整合报工: 成本与产出同报工)。
        upsertProducedWip(factoryId, batchId, t, saved, saved.getLaborCost(), saved.getMaterialCost());
    }

    /**
     * G6 (三阶段重载): 同上, 但 WIP 成本滚动用显式传入的 rollLaborCost/rollMaterialCost。
     *
     * <p>三阶段 OUTPUT 报工本身 labor/material 为 null (成本在 INPUT/SEGMENT 报工上), 调用方传整道汇总
     * (Σ INPUT materialCost + Σ SEGMENT laborCost); legacy 调用方传本报工成本 (行为不变)。</p>
     */
    private void upsertProducedWip(String factoryId, Long batchId, WorkProcessTask t, ProductionReport saved,
                                   BigDecimal rollLaborCost, BigDecimal rollMaterialCost) {
        String wipNo = generateBatchNo(t, batchId);
        BigDecimal out = saved.getOutputQuantity();
        SemiFinishedInventory wip = wipRepo
                .findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, wipNo)
                .orElse(null);
        if (wip == null) {
            wip = SemiFinishedInventory.builder()
                    .factoryId(factoryId)
                    .batchId(batchId)
                    .intermediateBatchNo(wipNo)
                    .sourceWorkProcessTaskId(t.getId())
                    .processOrder(t.getProcessOrder())
                    .productTypeId(t.getProductTypeId())
                    .producedQuantity(out)
                    .consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(out)
                    .unit(saved.getOutputUnit())
                    .status(SemiFinishedInventory.Status.AVAILABLE)
                    .materialBatchRefs(saved.getMaterialBatchRefs())  // 从产出报工继承溯源
                    .build();
        } else {
            // 跨天 / 同 task 多次报工: 累加产出与余额 (consumed 不变)
            BigDecimal produced = nz(wip.getProducedQuantity()).add(out);
            BigDecimal consumed = nz(wip.getConsumedQuantity());
            wip.setProducedQuantity(produced);
            wip.setAvailableQuantity(produced.subtract(consumed));
            // 重新累加产出后余额>0 → 回到 AVAILABLE (即便此前被领空 DEPLETED)
            if (wip.getAvailableQuantity().compareTo(BigDecimal.ZERO) > 0
                    && !SemiFinishedInventory.Status.RETURNED.equals(wip.getStatus())) {
                wip.setStatus(SemiFinishedInventory.Status.AVAILABLE);
            }
            if (wip.getUnit() == null) wip.setUnit(saved.getOutputUnit());
        }
        // — A.4/A.5: WIP 成本滚动 (必须在 producedQuantity 更新之后) —
        // accumulatedCost null-safe 累加本道 (labor + material); 跨天天然累加 (沿用已有 accumulatedCost)。
        // 三阶段 (单元1): OUTPUT 阶段传整道汇总成本 (本 OUTPUT 报工成本为 null); legacy 传本报工成本。
        // 全为 null (此前无成本 + 本道无成本) → 保持 null (诚实)。
        // unitCost = accumulatedCost / producedQuantity (scale 4 HALF_UP); 缺 accumulatedCost 或产量≤0 → null。
        wip.setAccumulatedCost(nullSafeAdd(
                wip.getAccumulatedCost(), rollLaborCost, rollMaterialCost));
        BigDecimal produced = wip.getProducedQuantity();
        if (wip.getAccumulatedCost() != null && produced != null && produced.signum() > 0) {
            wip.setUnitCost(wip.getAccumulatedCost().divide(produced, 4, RoundingMode.HALF_UP));
        } else {
            wip.setUnitCost(null);
        }
        wipRepo.save(wip);
    }

    /**
     * G7: 扣减源 WIP 余额 (已在调用前防呆校验 inputQuantity ≤ available)。
     *
     * <p>consumed += input; available = produced − consumed; 余额=0 → DEPLETED。
     * 乐观锁 (@Version, Wave1) 防并发超领: 同事务内扣减, 冲突时抛 OptimisticLockException 回滚。</p>
     *
     * <p>顺手写一条 PRODUCTION→PRODUCTION lineage 边 (副产物, 复用 closure trigger), fail-soft。</p>
     */
    private void consumeSourceWip(SemiFinishedInventory sourceWip, BigDecimal input,
                                  ProductionReport saved, WorkProcessTask t, Long workerId) {
        BigDecimal consumed = nz(sourceWip.getConsumedQuantity()).add(input);
        BigDecimal produced = nz(sourceWip.getProducedQuantity());
        sourceWip.setConsumedQuantity(consumed);
        sourceWip.setAvailableQuantity(produced.subtract(consumed));
        if (sourceWip.getAvailableQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            sourceWip.setStatus(SemiFinishedInventory.Status.DEPLETED);
        }
        wipRepo.save(sourceWip);

        // lineage 副产物: 源 WIP (上道) → 本道产出工序批次号 (PRODUCTION→PRODUCTION)。
        recordWipLineageEdge(saved.getFactoryId(), sourceWip, t, saved.getBatchId(), input, workerId);
    }

    /**
     * lineage 写入器 (最小实现, Wave 2 还 lineage 死骨架债)。
     *
     * <p>WIP 领用时写一条 PRODUCTION_BATCH → PRODUCTION_BATCH 有向边: source = 源 WIP 所在批次,
     * target = 本道所在批次 (同批工序流转)。插入触发 {@code fn_maintain_lineage_closure} 维护闭包。</p>
     *
     * <p><b>fail-soft</b>: lineage 是溯源副产物, 非库存权威源。写边失败不阻塞报工主线
     * (per brief: lineage 风险高可降级)。只记 WARN, 不抛。</p>
     */
    private void recordWipLineageEdge(String factoryId, SemiFinishedInventory sourceWip,
                                      WorkProcessTask t, Long batchId, BigDecimal qty, Long workerId) {
        try {
            BatchLineageEdge edge = new BatchLineageEdge();
            edge.setFactoryId(factoryId);
            // 同批工序间 WIP 领用流转 (源 WIP→本道); 文档化 edge_type 集合无 intra-batch 流转值,
            // 用 WIP_CONSUME 明示 (VARCHAR(30) 无 CHECK 约束)。
            edge.setEdgeType("WIP_CONSUME");
            edge.setSourceType("PRODUCTION_BATCH");
            edge.setSourceId(sourceWip.getBatchId() == null
                    ? String.valueOf(batchId) : String.valueOf(sourceWip.getBatchId()));
            edge.setTargetType("PRODUCTION_BATCH");
            edge.setTargetId(String.valueOf(batchId));
            edge.setQuantityUsed(qty);
            edge.setUnit(sourceWip.getUnit());
            edge.setEventTime(LocalDateTime.now());
            edge.setOperatorId(workerId);
            Map<String, Object> meta = new HashMap<>();
            meta.put("sourceWipNo", sourceWip.getIntermediateBatchNo());
            meta.put("targetWorkProcessTaskId", t.getId());
            meta.put("targetProcessOrder", t.getProcessOrder());
            edge.setMeta(meta);
            lineageEdgeRepo.save(edge);
        } catch (Exception e) {
            log.warn("[lineage] WIP 领用边写入失败 (fail-soft, 不阻塞报工): sourceWipNo={} batchId={} qty={}",
                    sourceWip.getIntermediateBatchNo(), batchId, qty, e);
        }
    }

    /** null-safe BigDecimal: null → ZERO. */
    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 三阶段报工 (单元1): 规范化 reportKind 入参。
     * <p>trim + 大写; 空白 → null (旧式整合报工); 非法值 (非 INPUT/SEGMENT/OUTPUT) → 400 拒绝
     * (防呆: 误传未知阶段不静默当 legacy 处理, 避免字段隔离失效)。</p>
     */
    private static String normalizeReportKind(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String k = raw.trim().toUpperCase();
        if (!"INPUT".equals(k) && !"SEGMENT".equals(k) && !"OUTPUT".equals(k)) {
            throw new BusinessException(400, "非法报工阶段: " + raw)
                    .withHint("reportKind 仅支持 INPUT/SEGMENT/OUTPUT (或留空走旧式整合报工)")
                    .withHintTarget("reportKind");
        }
        return k;
    }

    /**
     * null-safe 加和 (成本专用): 全部 null → null; 否则把 null 视为 0 求和。
     * <p>绝不默认 0 — 全无数据时保持 null 诚实显示"无成本数据"。</p>
     */
    private static BigDecimal nullSafeAdd(BigDecimal... vals) {
        BigDecimal sum = null;
        for (BigDecimal v : vals) {
            if (v != null) {
                sum = (sum == null ? BigDecimal.ZERO : sum).add(v);
            }
        }
        return sum;
    }

    // ==================== A.4/A.5 逐道成本计算 ====================

    /**
     * A.4 人工成本 = workerCount × (workMinutes / 60) × standardHourlyRate。
     *
     * <p>诚实 null 传播: workerCount / workMinutes / standardHourlyRate 任一为 null → 返回 null
     * (绝不默认 0)。final scale 2 ROUND_HALF_UP。</p>
     */
    private BigDecimal computeLaborCost(Integer workerCount, Integer workMinutes, BigDecimal hourlyRate) {
        if (workerCount == null || workMinutes == null || hourlyRate == null) {
            return null;
        }
        BigDecimal hours = BigDecimal.valueOf(workMinutes)
                .divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(workerCount).multiply(hours).multiply(hourlyRate)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 适配单元3: 多段工时人工成本 = Σ (段时长 × 段人数) person-min / 60 × rate。
     *
     * <p>优先用 {@code laborSegments} (张权 多段开工/收工); 段为空则退回单一
     * {@code workerCount}/{@code workMinutes} 旧路径 (back-compat, 零回归)。</p>
     *
     * <p>诚实 null 传播: rate==null → null (绝不默认 0)。段全部无效 (时长/人数缺失) 或 person-min=0
     * → null。 final scale 2 ROUND_HALF_UP。</p>
     */
    private BigDecimal computeLaborCost(List<YieldReportRequest.LaborSegment> segs,
                                        Integer workerCount, Integer workMinutes, BigDecimal rate) {
        if (rate == null) return null;
        if (segs != null && !segs.isEmpty()) {
            BigDecimal personMin = BigDecimal.ZERO;
            boolean any = false;
            for (YieldReportRequest.LaborSegment s : segs) {
                Integer dur = segmentMinutes(s);
                if (dur == null || s.getHeadcount() == null) continue;
                personMin = personMin.add(BigDecimal.valueOf((long) dur * s.getHeadcount()));
                any = true;
            }
            if (!any || personMin.signum() == 0) return null;
            return personMin.divide(BD_60, 6, RoundingMode.HALF_UP).multiply(rate)
                    .setScale(2, RoundingMode.HALF_UP);
        }
        // fallback: 单一 workerCount/workMinutes 旧路径 (保持既有行为)
        return computeLaborCost(workerCount, workMinutes, rate);
    }

    /**
     * 适配单元3: 解析单段工时分钟数 (startTime/endTime "HH:mm" 差)。
     *
     * <p>end &lt; start → 跨夜 (+1440)。任一端不可解析 / 为空 → null (整段不计入)。</p>
     */
    private static Integer segmentMinutes(YieldReportRequest.LaborSegment s) {
        if (s == null) return null;
        LocalTime start = parseHHmm(s.getStartTime());
        LocalTime end = parseHHmm(s.getEndTime());
        if (start == null || end == null) return null;
        int startMin = start.getHour() * 60 + start.getMinute();
        int endMin = end.getHour() * 60 + end.getMinute();
        int diff = endMin - startMin;
        if (diff < 0) diff += 1440;   // 跨夜
        return diff;
    }

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** 解析 "HH:mm"; 不可解析 → null (不抛, 让上层视为整段无效)。 */
    private static LocalTime parseHHmm(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return LocalTime.parse(v.trim(), HHMM);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 适配单元3: 把 List&lt;DTO&gt; 转 List&lt;Map&gt; 用于 jsonb 序列化 (镜像 toMaterialBatchRefMaps)。
     * null/empty → null (back-compat)。
     */
    private List<Map<String, Object>> toLaborSegmentMaps(List<YieldReportRequest.LaborSegment> segs) {
        if (segs == null || segs.isEmpty()) return null;
        return segs.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("startTime", s.getStartTime());
            m.put("endTime", s.getEndTime());
            m.put("headcount", s.getHeadcount());
            if (s.getNote() != null) m.put("note", s.getNote());
            if (s.getProcessedQuantity() != null) m.put("processedQuantity", s.getProcessedQuantity());
            if (s.getProcessedUnit() != null && !s.getProcessedUnit().isBlank()) {
                m.put("processedUnit", s.getProcessedUnit());
            }
            if (s.getStageOutputQuantity() != null) m.put("stageOutputQuantity", s.getStageOutputQuantity());
            if (s.getStageOutputUnit() != null && !s.getStageOutputUnit().isBlank()) {
                m.put("stageOutputUnit", s.getStageOutputUnit());
            }
            if (s.getSegmentWasteQuantity() != null) m.put("segmentWasteQuantity", s.getSegmentWasteQuantity());
            if (s.getSegmentWasteUnit() != null && !s.getSegmentWasteUnit().isBlank()) {
                m.put("segmentWasteUnit", s.getSegmentWasteUnit());
            }
            List<Map<String, Object>> byproductMaps = toByproductMaps(s.getByproducts());
            if (byproductMaps != null) m.put("byproducts", byproductMaps);
            return m;
        }).collect(Collectors.toList());
    }

    private Map<String, Object> buildYieldCustomFields(String reportKind, BigDecimal sourceWipQuantity) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reportStack", "YIELD");
        fields.put("wipPostingMode", "APPROVAL");
        if (reportKind != null) {
            fields.put("reportKind", reportKind);
        }
        if (sourceWipQuantity != null) {
            fields.put("sourceWipQuantity", sourceWipQuantity);
        }
        return fields;
    }

    private BigDecimal sourceWipQuantity(YieldReportRequest req, BigDecimal fallbackInputQuantity) {
        BigDecimal qty = req.getSourceWipQuantity() != null ? req.getSourceWipQuantity() : fallbackInputQuantity;
        if (qty != null && qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(409, "半成品领用数量必须大于 0")
                    .withCode("WIP_INPUT_REQUIRED")
                    .withHint("请填写实际领用的半成品数量")
                    .withSeverity("BLOCKING")
                    .withHintTarget("sourceWipQuantity");
        }
        if (qty != null && fallbackInputQuantity != null && qty.compareTo(fallbackInputQuantity) > 0) {
            throw new BusinessException(409, "半成品领用数量不能超过本次总投入量")
                    .withCode("WIP_INPUT_EXCEEDS_TOTAL")
                    .withHint("请核对原料领用量和半成品领用量；总投入量必须等于两者合计")
                    .withSeverity("BLOCKING")
                    .withHintTarget("sourceWipQuantity");
        }
        return qty;
    }

    /**
     * 适配单元3: 把 List&lt;Byproduct&gt; 转 List&lt;Map&gt; 用于 jsonb 序列化 (镜像 toMaterialBatchRefMaps)。
     * null/empty → null (back-compat)。
     */
    private List<Map<String, Object>> toByproductMaps(List<YieldReportRequest.Byproduct> bps) {
        if (bps == null || bps.isEmpty()) return null;
        return bps.stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.getName());
            m.put("quantity", b.getQuantity());
            if (b.getUnit() != null) m.put("unit", b.getUnit());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * T161: 把 List&lt;PhotoAnnotation&gt; 转 List&lt;Map&gt; 用于 jsonb 序列化。
     * null/empty → null (back-compat: 旧记录无标注)。
     */
    private List<Map<String, Object>> toPhotoAnnotationMaps(
            List<YieldReportRequest.PhotoAnnotation> annotations) {
        if (annotations == null || annotations.isEmpty()) return null;
        return annotations.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            if (a.getUrl() != null) m.put("url", a.getUrl());
            if (a.getLabel() != null) m.put("label", a.getLabel());
            if (a.getNote() != null) m.put("note", a.getNote());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * 适配单元3 守恒软校验 (Part C): 计算物料平衡告警 (非阻塞)。
     *
     * <p>balance = input − output − Σ副产物 − 损耗 (null 视 0)。仅当 input!=null 且 inputUnit==outputUnit
     * (单位可比) 且 input&gt;0 时计算。|balance|/input &gt; 15% → 返回告警串; 否则 null。
     * 跨单位 / input null / input≤0 → null (不告警)。</p>
     *
     * <p>per fool-proof 4 位一体: 含具体数字 + next-action ("请核对, 系统不阻塞")。</p>
     */
    private String computeBalanceWarning(YieldReportRequest req) {
        BigDecimal input = req.getInputQuantity();
        BigDecimal output = req.getOutputQuantity();
        if (input == null || input.compareTo(BigDecimal.ZERO) <= 0) return null;
        // 单位可比性: inputUnit == outputUnit (跨单位无法守恒比较)
        String inUnit = req.getInputUnit();
        String outUnit = req.getOutputUnit();
        if (inUnit == null || !inUnit.equals(outUnit)) return null;

        BigDecimal byproductSum = BigDecimal.ZERO;
        if (req.getByproducts() != null) {
            for (YieldReportRequest.Byproduct b : req.getByproducts()) {
                if (b != null && b.getQuantity() != null) byproductSum = byproductSum.add(b.getQuantity());
            }
        }
        BigDecimal waste = req.getWasteQuantity() == null ? BigDecimal.ZERO : req.getWasteQuantity();
        // F3: 留样也消耗投入物料, 必须减去 (Integer → kg 同单位处理, null 视 0)
        BigDecimal sample = req.getSampleRetainQuantity() == null
                ? BigDecimal.ZERO : new BigDecimal(req.getSampleRetainQuantity());
        BigDecimal balance = input.subtract(nz(output)).subtract(byproductSum).subtract(waste)
                .subtract(sample);

        BigDecimal deviation = balance.abs().divide(input, 6, RoundingMode.HALF_UP);
        if (deviation.compareTo(BALANCE_WARN_THRESHOLD) <= 0) return null;

        return String.format(
                "物料平衡偏差 %.0f%% (投入 %s, 产出 %s, 副产物 %s, 损耗 %s, 留样 %s) — 请核对, 系统不阻塞",
                deviation.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP),
                input.stripTrailingZeros().toPlainString(),
                nz(output).stripTrailingZeros().toPlainString(),
                byproductSum.stripTrailingZeros().toPlainString(),
                waste.stripTrailingZeros().toPlainString(),
                sample.stripTrailingZeros().toPlainString());
    }

    /**
     * F2 三阶段 OUTPUT 守恒软校验: 使用跨报工汇聚的投入量 (Σ INPUT 报工) 与本次 OUTPUT 产出比对。
     *
     * <p>参数语义与 {@link #computeBalanceWarning(YieldReportRequest)} 相同, 但 input/output/unit
     * 均为调用方计算后的聚合值 (不来自单一 req 对象)。</p>
     *
     * @param totalInput   该 task 历史所有 INPUT 报工 Σ inputQuantity (跨阶段汇聚)
     * @param totalOutput  本次 OUTPUT 报工 outputQuantity
     * @param inUnit       投入单位 (取历史 INPUT 报工的 inputUnit)
     * @param outUnit      产出单位 (本次 OUTPUT 报工的 outputUnit)
     * @param byproducts   副产物列表 (来自 OUTPUT 报工)
     * @param wasteQty     损耗 (来自 OUTPUT 报工)
     * @param sampleRetain 留样件数 (来自 OUTPUT 报工)
     */
    private String computeBalanceWarningForOutput(BigDecimal totalInput, BigDecimal totalOutput,
                                                  String inUnit, String outUnit,
                                                  List<YieldReportRequest.Byproduct> byproducts,
                                                  BigDecimal wasteQty, Integer sampleRetain) {
        if (totalInput == null || totalInput.compareTo(BigDecimal.ZERO) <= 0) return null;
        if (inUnit == null || !inUnit.equals(outUnit)) return null;  // 跨单位不可比

        BigDecimal byproductSum = BigDecimal.ZERO;
        if (byproducts != null) {
            for (YieldReportRequest.Byproduct b : byproducts) {
                if (b != null && b.getQuantity() != null) byproductSum = byproductSum.add(b.getQuantity());
            }
        }
        BigDecimal waste = wasteQty == null ? BigDecimal.ZERO : wasteQty;
        BigDecimal sample = sampleRetain == null ? BigDecimal.ZERO : new BigDecimal(sampleRetain);
        BigDecimal balance = totalInput.subtract(nz(totalOutput)).subtract(byproductSum)
                .subtract(waste).subtract(sample);

        BigDecimal deviation = balance.abs().divide(totalInput, 6, RoundingMode.HALF_UP);
        if (deviation.compareTo(BALANCE_WARN_THRESHOLD) <= 0) return null;

        return String.format(
                "物料平衡偏差 %.0f%% (投入 %s, 产出 %s, 副产物 %s, 损耗 %s, 留样 %s) — 请核对, 系统不阻塞",
                deviation.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP),
                totalInput.stripTrailingZeros().toPlainString(),
                nz(totalOutput).stripTrailingZeros().toPlainString(),
                byproductSum.stripTrailingZeros().toPlainString(),
                waste.stripTrailingZeros().toPlainString(),
                sample.stripTrailingZeros().toPlainString());
    }

    /**
     * A.5 材料成本 = Σ 本道领用:
     * <ul>
     *   <li>原料领用: Σ (ref.quantity × MaterialBatch.unitPrice) — 用每个 ref 自带的 quantity
     *       (MaterialBatchRef 携带 per-ref quantity, 无需按 inputQuantity 摊分)。</li>
     *   <li>半成品领用 (sourceWip 非 null): consumedQty (= 本道 inputQuantity) × sourceWip.unitCost。</li>
     * </ul>
     *
     * <p><b>诚实 null 传播</b>: 若所有计入项都无价 (每个 materialBatch.unitPrice 均 null 且无定价 WIP)
     * → 返回 null。若部分有价部分无价 → 仅求有价项之和 (无价项视为 0 贡献, 但因至少一项有价故非 null)。
     * final scale 2 ROUND_HALF_UP。</p>
     *
     * <p><b>不重复计材料</b>: 原料 ref 与 WIP 是同一道的两种领用来源 (首道领原料 / 后续道领 WIP),
     * 但本方法两路都累加 — 若某道既带原料 ref 又领 WIP (理论少见, e.g. 补料), 两者都是真实成本投入,
     * 应当都计入 (非重复计同一物料)。</p>
     */
    /**
     * P5 扩展版: 原有原料/WIP 成本基础上叠加调料 (SEASONING) 和包材 (PACKAGING) 配方成本。
     *
     * <p><b>叠加逻辑</b> (不与原有成本重复):
     * <ul>
     *   <li>原料 ref 成本 (领料折价) — 不变</li>
     *   <li>WIP 成本 (sourceWip.unitCost × inputQuantity) — 不变</li>
     *   <li>调料 (SEASONING): recipe.unitCost (元/kg投入) × inputQuantity — 新增</li>
     *   <li>包材 (PACKAGING): recipe.unitCost (元/盒产出) × outputQuantity — 新增</li>
     * </ul>
     *
     * <p><b>诚实 null 传播</b>: 任何来源只要有值就累加。全部无价 → null。
     * 调料/包材 recipe 未配置 (factoryId/workProcessId 为 null, 或无 active recipe) → 该项 0 贡献不影响其他项。
     */
    private BigDecimal computeMaterialCost(List<MaterialBatchRef> refs,
                                           SemiFinishedInventory sourceWip, BigDecimal sourceWipQuantity,
                                           BigDecimal recipeInputQuantity,
                                           String factoryId, String workProcessId, BigDecimal outputQuantity) {
        BigDecimal cost = null;  // null = 至今无任何有价项
        boolean unknownCost = false;
        // 1) 原料领用: 每个 ref 的 quantity × 批次 unitPrice (unitPrice 可能被脱敏为 null)
        if (refs != null) {
            for (MaterialBatchRef ref : refs) {
                if (ref == null || ref.getMaterialBatchId() == null || ref.getQuantity() == null) continue;
                if (ref.getQuantity().compareTo(BigDecimal.ZERO) <= 0) continue;
                MaterialBatch mb = materialBatchRepository.findById(ref.getMaterialBatchId()).orElse(null);
                if (mb == null || mb.getUnitPrice() == null) {
                    unknownCost = true;
                    continue;
                }
                BigDecimal line = ref.getQuantity().multiply(mb.getUnitPrice());
                cost = (cost == null ? BigDecimal.ZERO : cost).add(line);
            }
        }
        // 2) 半成品领用: consumedQty (= 本道 inputQuantity) × sourceWip.unitCost
        if (sourceWip != null && sourceWipQuantity != null) {
            if (sourceWip.getUnitCost() == null) {
                unknownCost = true;
            } else {
                BigDecimal line = sourceWipQuantity.multiply(sourceWip.getUnitCost());
                cost = (cost == null ? BigDecimal.ZERO : cost).add(line);
            }
        }
        // 3) P5: 调料/包材配方成本 (叠加, 不与原料/WIP 重复)
        if (factoryId != null && workProcessId != null) {
            List<ProcessMaterialRecipe> recipes =
                    recipeRepository.findActiveByFactoryIdAndWorkProcessId(factoryId, workProcessId);
            for (ProcessMaterialRecipe recipe : recipes) {
                if (recipe.getUnitCost() == null || !Boolean.TRUE.equals(recipe.getIsActive())) continue;
                BigDecimal line = null;
                if (recipe.getRecipeType() == ProcessMaterialRecipe.RecipeType.SEASONING
                        && recipeInputQuantity != null) {
                    // 调料: 元/kg投入 × 本道投入量
                    line = recipe.getUnitCost().multiply(recipeInputQuantity);
                } else if (recipe.getRecipeType() == ProcessMaterialRecipe.RecipeType.PACKAGING
                        && outputQuantity != null) {
                    // 包材: 元/盒产出 × 本道产出量 (盒)
                    line = recipe.getUnitCost().multiply(outputQuantity);
                }
                if (line != null) {
                    cost = (cost == null ? BigDecimal.ZERO : cost).add(line);
                }
            }
        }
        if (unknownCost) {
            return null;
        }
        return cost == null ? null : cost.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    @Transactional
    public Map<String, Object> recordMaterialInput(String factoryId, Long batchId, Long workerId, MaterialInputRequest req) {
        // F4 补录时效锁 — 领料业务日期不得早于 T-maxDays (对称 submitReport 的同一校验)
        if (backdateWindowValidator != null) {
            backdateWindowValidator.assertWithinWindow(req.getBusinessDate(), "领料");
        }
        if (req.getWorkProcessTaskId() == null) {
            throw new BusinessException(400, "缺少必填字段: workProcessTaskId")
                    .withHint("请选择首道工序任务").withHintTarget("workProcessTaskId");
        }
        // A-F1/F2: 批次须已开工 (开工→领料 是六扇门明确需求顺序)。
        assertBatchStartedForReport(factoryId, batchId, "领料");
        WorkProcessTask t = taskRepo.findByFactoryIdAndId(factoryId, req.getWorkProcessTaskId())
                .orElseThrow(() -> new BusinessException(404, "工序任务不存在: " + req.getWorkProcessTaskId()));

        // M3: 归属鉴权 — 操作员只能投料到自己被指派的工序任务; 主管不受限制。
        ReportAuthGuard.assertCanReport(t.getAssignedTo(), workerId,
                ReportAuthGuard.isSupervisor(ReportAuthGuard.currentRole()));

        ProductionReport r = ProductionReport.builder()
                .factoryId(factoryId).batchId(batchId).reportType(YIELD)
                .workerId(workerId).reportDate(LocalDate.now())
                .workProcessTaskId(t.getId()).processOrder(t.getProcessOrder())
                .productTypeId(t.getProductTypeId())
                .warehouseOutQuantity(req.getWarehouseOutQuantity())
                .feedInQuantity(req.getFeedInQuantity())
                .inputQuantity(req.getFeedInQuantity())   // 投料量落首道 input
                .inputUnit(req.getInputUnit())
                .materialBatchRefs(toMaterialBatchRefMaps(req.getMaterialBatchRefs()))  // A2b: 链接领料批次列表
                .status(ProductionReport.Status.SUBMITTED)
                .build();
        ProductionReport saved = reportRepo.save(r);

        // A2b: 保存后对每个关联批次独立检查自动结清
        if (req.getMaterialBatchRefs() != null) {
            for (MaterialBatchRef ref : req.getMaterialBatchRefs()) {
                if (ref.getMaterialBatchId() != null) {
                    checkAndAutoSettle(factoryId, batchId, ref.getMaterialBatchId());
                }
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("reportId", saved.getId());
        return out;
    }

    private BatchCloseReadiness batchCloseReadiness(String factoryId, Long batchId, BatchYieldDTO batchYield) {
        List<WorkProcessTask> tasks = Optional.ofNullable(
                taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(factoryId, batchId)
        ).orElse(List.of());
        if (!tasks.isEmpty()) {
            List<WorkProcessTask> incompleteTasks = tasks.stream()
                    .filter(task -> task.getStatus() != WorkProcessTask.Status.COMPLETED
                            && task.getStatus() != WorkProcessTask.Status.SKIPPED
                            && task.getStatus() != WorkProcessTask.Status.CANCELLED)
                    .toList();
            if (!incompleteTasks.isEmpty()) {
                List<String> summary = incompleteTasks.stream()
                        .limit(5)
                        .map(task -> String.format("%s/%s/%s",
                                task.getProductTypeId(),
                                task.getProcessOrder(),
                                task.getStatus()))
                        .toList();
                return new BatchCloseReadiness(
                        false,
                        String.format("还有 %d 个SKU/工序未完成，今日结清已保存，但不能关单",
                                incompleteTasks.size()),
                        incompleteTasks.size(),
                        summary);
            }
        }
        if (!Boolean.TRUE.equals(batchYield.getComplete())) {
            return new BatchCloseReadiness(
                    false,
                    "逐道报工未全部完成，今日结清已保存，但不能关单",
                    0,
                    List.of());
        }
        return new BatchCloseReadiness(true, null, 0, List.of());
    }

    /**
     * A2b: 将 List<MaterialBatchRef> 转为 List<Map<String,Object>> 用于 jsonb 序列化.
     * null/empty → null (backward-compatible: 仓管员不填则无自动结清路径).
     */
    private List<Map<String, Object>> toMaterialBatchRefMaps(List<MaterialBatchRef> refs) {
        if (refs == null || refs.isEmpty()) return null;
        return refs.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("materialBatchId", r.getMaterialBatchId());
            m.put("quantity", r.getQuantity());
            if (r.getUnit() != null) m.put("unit", r.getUnit());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * A2b: 检查触发批次是否 USED_UP, 若是则查找关联本批次 (batchId) 的未结清 YIELD 报工,
     * 对每条候选报工检查其 material_batch_refs 中全部 materialBatchId 是否均 USED_UP,
     * 全部满足才打 settled=true (all-or-nothing 语义).
     *
     * @return 本次实际结清的报工条数 (0 表示未结清任何报工)
     */
    private int checkAndAutoSettle(String factoryId, Long batchId, String materialBatchId) {
        // 1. 查触发批次状态
        Optional<MaterialBatch> batchOpt = materialBatchRepository.findById(materialBatchId);
        if (batchOpt.isEmpty()) return 0;
        MaterialBatch mb = batchOpt.get();
        // "原料用完" = status 是 USED_UP (权威信号). 排除 EXPIRED/DEFECTIVE/SCRAPPED 等 remaining=0 但非正常耗尽的状态.
        if (mb.getStatus() != MaterialBatchStatus.USED_UP) {
            return 0;  // 触发批次未用完，不触发
        }
        // 2. 找到 material_batch_refs 包含此 materialBatchId 的所有未结清 YIELD 报工
        // materialBatchId is a String (VARCHAR PK) → must be quoted in JSON
        String refJson = "[{\"materialBatchId\":\"" + materialBatchId + "\"}]";
        List<ProductionReport> candidates = reportRepo.findUnsettledYieldContainingMaterialBatch(
                factoryId, batchId, refJson);
        if (candidates.isEmpty()) return 0;
        // 3. 对每条候选报工: 检查其 material_batch_refs 中所有 materialBatchId 是否全部 USED_UP
        LocalDateTime now = LocalDateTime.now();
        List<ProductionReport> toSettle = new ArrayList<>();
        for (ProductionReport candidate : candidates) {
            if (allRefsUsedUp(candidate.getMaterialBatchRefs())) {
                candidate.setSettled(true);
                candidate.setSettledAt(now);
                toSettle.add(candidate);
            }
        }
        if (!toSettle.isEmpty()) {
            reportRepo.saveAll(toSettle);
            log.info("A2b 自动结清: factoryId={}, batchId={}, triggerMaterialBatchId={}, settledCount={}",
                    factoryId, batchId, materialBatchId, toSettle.size());
        }
        return toSettle.size();
    }

    /**
     * A2b: 检查 materialBatchRefs 列表中所有 materialBatchId 是否全部 USED_UP.
     * null/empty → false (无关联批次不触发自动结清).
     */
    private boolean allRefsUsedUp(List<Map<String, Object>> refs) {
        if (refs == null || refs.isEmpty()) return false;
        for (Map<String, Object> ref : refs) {
            Object mbIdObj = ref.get("materialBatchId");
            if (mbIdObj == null) continue;
            // materialBatchId is a String (VARCHAR PK); jsonb deserializes it as String directly.
            String mbId = mbIdObj.toString();
            Optional<MaterialBatch> mb = materialBatchRepository.findById(mbId);
            if (mb.isEmpty()) continue;  // 找不到批次视为忽略
            // 仅 USED_UP 视为"原料用完". EXPIRED/DEFECTIVE 等状态即使 remaining=0 也不触发结清.
            if (mb.get().getStatus() != MaterialBatchStatus.USED_UP) {
                return false;  // 至少一个非 USED_UP → 不结清
            }
        }
        return true;
    }

    @Override
    @Transactional
    public Map<String, Object> autoSettleByMaterialBatch(String factoryId, Long batchId, String materialBatchId) {
        int settled = checkAndAutoSettle(factoryId, batchId, materialBatchId);
        Map<String, Object> out = new HashMap<>();
        out.put("settledCount", settled);
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public BatchYieldDTO getYield(String factoryId, Long batchId) {
        List<ProductionReport> reports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
        // P0-2: 解析末道产品标准克重, 打通 kg↔份 折算 (跨单位且无克重时 cumulative 保持 null, 诚实)
        BigDecimal gramsPerUnit = resolveGramsPerUnit(factoryId, reports);
        BatchYieldDTO dto = calcSvc.calculateBatchYield(reports, gramsPerUnit);
        enrichProcessNames(factoryId, batchId, dto);
        // G8 Wave 3 (C): 进行中标注 — 算在制 WIP 总量 + 批次完工判定
        enrichInProgressAnnotation(factoryId, batchId, dto);
        return dto;
    }

    /**
     * SP-C: 按批次号查出成率 (存货生产无订单号场景).
     * findByFactoryIdAndBatchNumber 是 factory-scoped — 跨租户安全。
     */
    @Override
    @Transactional(readOnly = true)
    public BatchYieldDTO getBatchYieldByNumber(String factoryId, String batchNumber) {
        com.cretas.aims.entity.ProductionBatch batch = productionBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                .orElseThrow(() -> new BusinessException(404, "生产批次不存在: " + batchNumber));
        return getYield(factoryId, batch.getId());
    }

    /**
     * 段2(B): 按批次号辅料标准单价双锚点投料-产出对账。
     *
     * <p>标准侧 = (产品×工序) 配置的 standardYieldRate/auxUnitPrice/auxBasis;
     * 实际侧 = {@link #getYield} 逐道报工步骤 (已 enrich 工序名 + 跨单位折算)。
     * 折算系数取批次产品的 gramsPerUnit; 份数 N = 末道产出量; 阈值工厂可配 (默认 5%)。只读。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public CostReconcileResult getBatchReconcile(String factoryId, String batchNumber) {
        ProductionBatch batch = productionBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                .orElseThrow(() -> new BusinessException(404, "生产批次不存在: " + batchNumber));
        BatchYieldDTO yield = getYield(factoryId, batch.getId());   // steps: 工序名 + 逐道实际投入/产出

        List<ProductWorkProcess> configs = batch.getProductTypeId() == null
                ? java.util.Collections.emptyList()
                : productWorkProcessRepository
                        .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, batch.getProductTypeId());

        BigDecimal gramsPerUnit = batch.getProductTypeId() == null ? null
                : productTypeRepository.findByIdAndFactoryId(batch.getProductTypeId(), factoryId)
                        .map(ProductType::getGramsPerUnit).orElse(null);

        BigDecimal portionCount = yield.getLastStepOutput();   // N = 末道产出数量 (份/盒/kg)
        BigDecimal threshold = resolveAuxThreshold(factoryId);

        return costReconcileService.reconcile(yield.getSteps(), configs, gramsPerUnit, portionCount, threshold);
    }

    /**
     * 段2(B): 读取工厂级辅料多投预警阈值 (镜像 {@link #getToleranceForFactory})。
     * ProductionSettings.auxVarianceThreshold (JSON, 无需迁移); 无配置/解析失败 → 默认 5%。
     */
    private BigDecimal resolveAuxThreshold(String factoryId) {
        try {
            String json = factorySettingsRepo.findProductionSettingsByFactoryId(factoryId);
            if (json == null) return CostReconcileService.DEFAULT_THRESHOLD;
            FactorySettingsDTO.ProductionSettings ps =
                    objectMapper.readValue(json, FactorySettingsDTO.ProductionSettings.class);
            return ps.getAuxVarianceThreshold() != null
                    ? ps.getAuxVarianceThreshold()
                    : CostReconcileService.DEFAULT_THRESHOLD;
        } catch (Exception e) {
            log.warn("[辅料对账] 读取阈值设置失败, 使用默认 5%", e);
            return CostReconcileService.DEFAULT_THRESHOLD;
        }
    }

    /**
     * 单元 F (F006 REQ-21): 分订单出成率聚合。
     *
     * <p>orderId → 计划 (source_order_id) → 批次 (production_plan_id IN 计划ids) → 每批 {@link #getYield}。
     * 总投入/总产出/整体出成率仅在所有批次单位一致时计算 (诚实, 不混算不可比单位);
     * 成本 null-safe 累加 (全 null → null, 绝不默认 0)。无计划/批次 → 空 batches + batchCount 0。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public OrderYieldSummaryDTO getOrderYieldSummary(String factoryId, String orderId) {
        // 1) 订单 → 生产计划 ids
        List<String> planIds = productionPlanRepository
                .findByFactoryIdAndSourceOrderId(factoryId, orderId).stream()
                .map(com.cretas.aims.entity.ProductionPlan::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (planIds.isEmpty()) {
            return emptyOrderSummary(orderId);  // 诚实空态: 该订单无生产计划
        }

        // 2) 计划 ids → 批次 → 每批 getYield (复用逐道工序链 + 累计出成率)
        List<ProductionBatch> batches =
                productionBatchRepository.findByFactoryIdAndProductionPlanIdIn(factoryId, planIds);
        if (batches.isEmpty()) {
            return emptyOrderSummary(orderId);  // 诚实空态: 计划下无批次
        }
        List<BatchYieldDTO> batchYields = batches.stream()
                .map(b -> getYield(factoryId, b.getId()))
                .collect(Collectors.toList());

        // 3) 单位一致性判定 (首道投入单位 / 末道产出单位 各自全等才聚合)
        String firstInputUnit = commonUnit(batchYields, BatchYieldDTO::getFirstStepInputUnit);
        String lastOutputUnit = commonUnit(batchYields, BatchYieldDTO::getLastStepOutputUnit);

        BigDecimal totalFirstInput = firstInputUnit == null ? null
                : sumNonNull(batchYields, BatchYieldDTO::getFirstStepInput);
        BigDecimal totalLastOutput = lastOutputUnit == null ? null
                : sumNonNull(batchYields, BatchYieldDTO::getLastStepOutput);

        // 整体出成率 = 总产出 / 总投入 (scale 4 HALF_UP); 单位不可比或总投入 ≤ 0 → null
        BigDecimal overallYieldRate = null;
        if (totalFirstInput != null && totalLastOutput != null
                && totalFirstInput.signum() > 0) {
            overallYieldRate = totalLastOutput.divide(totalFirstInput, 4, RoundingMode.HALF_UP);
        }

        // 成本 null-safe 累加 (全 null → null, 绝不默认 0)
        BigDecimal totalLaborCost = sumNullSafe(batchYields, BatchYieldDTO::getTotalLaborCost);
        BigDecimal totalMaterialCost = sumNullSafe(batchYields, BatchYieldDTO::getTotalMaterialCost);
        BigDecimal totalCost = nullSafeAdd(totalLaborCost, totalMaterialCost);

        return OrderYieldSummaryDTO.builder()
                .orderId(orderId)
                .batches(batchYields)
                .totalFirstInput(totalFirstInput)
                .totalLastOutput(totalLastOutput)
                .overallYieldRate(overallYieldRate)
                .firstInputUnit(firstInputUnit)
                .lastOutputUnit(lastOutputUnit)
                .totalLaborCost(totalLaborCost)
                .totalMaterialCost(totalMaterialCost)
                .totalCost(totalCost)
                .batchCount(batchYields.size())
                .build();
    }

    /** 单元 F: 诚实空态 (无计划/批次) — 空 batches + batchCount 0 + 全 null 聚合。 */
    private OrderYieldSummaryDTO emptyOrderSummary(String orderId) {
        return OrderYieldSummaryDTO.builder()
                .orderId(orderId)
                .batches(new ArrayList<>())
                .batchCount(0)
                .build();
    }

    /**
     * 单元 F: 取所有批次某单位字段的共同值 — 全部非空且相等 → 该单位; 否则 null (不可比, 不混算)。
     * 任一为 null 或存在不同单位 → null。
     */
    private String commonUnit(List<BatchYieldDTO> batches,
                              java.util.function.Function<BatchYieldDTO, String> unitGetter) {
        String common = null;
        for (BatchYieldDTO b : batches) {
            String u = unitGetter.apply(b);
            if (u == null) return null;          // 缺单位 → 不可比
            if (common == null) {
                common = u;
            } else if (!common.equals(u)) {
                return null;                     // 单位不一致 → 不可比
            }
        }
        return common;
    }

    /** 单元 F: Σ 字段, 把 null 视为 0 (调用方已确认单位可比, totalInput/Output 必有值)。 */
    private BigDecimal sumNonNull(List<BatchYieldDTO> batches,
                                  java.util.function.Function<BatchYieldDTO, BigDecimal> getter) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BatchYieldDTO b : batches) {
            sum = sum.add(nz(getter.apply(b)));
        }
        return sum;
    }

    /** 单元 F: 成本 null-safe Σ — 全 null → null; 任一非 null → 该项视 0 求和 (绝不默认 0)。 */
    private BigDecimal sumNullSafe(List<BatchYieldDTO> batches,
                                   java.util.function.Function<BatchYieldDTO, BigDecimal> getter) {
        BigDecimal sum = null;
        for (BatchYieldDTO b : batches) {
            BigDecimal v = getter.apply(b);
            if (v != null) {
                sum = (sum == null ? BigDecimal.ZERO : sum).add(v);
            }
        }
        return sum;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WipRowDTO> listWip(String factoryId, Long batchId) {
        if (batchId == null) {
            return new ArrayList<>();
        }
        List<SemiFinishedInventory> wips =
                wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, batchId);
        if (wips.isEmpty()) {
            return new ArrayList<>();  // 诚实空态 (前端显空态 / 隐藏 WIP 区)
        }
        // 批量 join task→work_process→processName 回填 (避免 N+1; 查不到留 null, 前端 fallback)
        Set<Long> taskIds = wips.stream()
                .map(SemiFinishedInventory::getSourceWorkProcessTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> processNameByTask = new HashMap<>();
        if (!taskIds.isEmpty()) {
            Map<Long, String> taskToProcessId = taskRepo.findByFactoryIdAndIdIn(factoryId, taskIds).stream()
                    .filter(t -> t.getWorkProcessId() != null)
                    .collect(Collectors.toMap(WorkProcessTask::getId, WorkProcessTask::getWorkProcessId, (a, b) -> a));
            Map<String, String> processIdToName = processRepo.findAllById(new HashSet<>(taskToProcessId.values())).stream()
                    .collect(Collectors.toMap(WorkProcess::getId, WorkProcess::getProcessName, (a, b) -> a));
            taskToProcessId.forEach((tid, pid) -> {
                String name = processIdToName.get(pid);
                if (name != null) processNameByTask.put(tid, name);
            });
        }
        // 双出成率: 从 BatchYieldDTO steps 按 workProcessTaskId 匹配 stepYieldRate / cumulativeYieldRate。
        // getYield 已含 standardGramsPerUnit 折算 + calculateBatchYield 二次 pass 填 cumulativeYieldRate。
        // 查不到 step → null (诚实, 不臆造)。失败不影响 WIP 列表主功能。
        Map<Long, StepYieldDTO> stepByTaskId = new HashMap<>();
        try {
            BatchYieldDTO batchYieldForRates = getYield(factoryId, batchId);
            if (batchYieldForRates != null && batchYieldForRates.getSteps() != null) {
                for (StepYieldDTO step : batchYieldForRates.getSteps()) {
                    if (step.getWorkProcessTaskId() != null) {
                        stepByTaskId.put(step.getWorkProcessTaskId(), step);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("listWip: getYield failed for batchId={}, dual yield rates will be null: {}",
                    batchId, ex.getMessage());
        }

        return wips.stream()
                .sorted((a, b) -> {
                    int ao = a.getProcessOrder() == null ? Integer.MAX_VALUE : a.getProcessOrder();
                    int bo = b.getProcessOrder() == null ? Integer.MAX_VALUE : b.getProcessOrder();
                    return Integer.compare(ao, bo);
                })
                .map(w -> {
                    StepYieldDTO step = stepByTaskId.get(w.getSourceWorkProcessTaskId());
                    return WipRowDTO.builder()
                            .intermediateBatchNo(w.getIntermediateBatchNo())
                            .sourceWorkProcessTaskId(w.getSourceWorkProcessTaskId())
                            .processOrder(w.getProcessOrder())
                            .processName(processNameByTask.get(w.getSourceWorkProcessTaskId()))
                            .productTypeId(w.getProductTypeId())
                            .producedQuantity(w.getProducedQuantity())
                            .consumedQuantity(w.getConsumedQuantity())
                            .availableQuantity(w.getAvailableQuantity())
                            .unit(w.getUnit())
                            .status(w.getStatus())
                            .stepYieldRate(step != null ? step.getYieldRate() : null)
                            .cumulativeYieldRate(step != null ? step.getCumulativeYieldRate() : null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * G8 Wave 3 (C): 填充进行中标注字段 (展示层防呆, 拍板 A 主算法 + C 标注)。
     *
     * <p><b>inProgress 判定</b> = 批次未完工 (status ≠ COMPLETED/CANCELLED) <b>或</b> 仍有在制 WIP 余额
     * (Σ AVAILABLE.availableQuantity &gt; 0)。完工 (COMPLETED) 且 WIP 全清零 → inProgress=false, 数字锁定。</p>
     *
     * <p><b>wipInProgressQuantity</b> = Σ 该批次所有 AVAILABLE WIP 行的 available_quantity
     * (尚未变成成品的中间品总量)。inProgress=false 时为 ZERO。</p>
     *
     * <p><b>cumulativeYieldRate 不变</b>: 始终是 A 完工口径 (calc 算的末道÷首道)。本方法只加展示标注,
     * 不改算法 (per 设计章一 ★推荐)。asOfYieldRate 当前与 A 口径同源, 仅语义标注。</p>
     */
    private void enrichInProgressAnnotation(String factoryId, Long batchId, BatchYieldDTO dto) {
        if (batchId == null) {
            return;  // 无批次上下文 (理论不达; 防御): 不标注
        }
        // 1) 该批次在制 WIP 总量 = Σ AVAILABLE.available_quantity (RETURNED/DEPLETED 不计)
        List<SemiFinishedInventory> wips =
                wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, batchId);
        BigDecimal wipPending = BigDecimal.ZERO;
        String wipUnit = null;
        for (SemiFinishedInventory w : wips) {
            if (!SemiFinishedInventory.Status.AVAILABLE.equals(w.getStatus())) continue;
            BigDecimal avail = nz(w.getAvailableQuantity());
            if (avail.compareTo(BigDecimal.ZERO) <= 0) continue;
            wipPending = wipPending.add(avail);
            if (wipUnit == null && w.getUnit() != null) wipUnit = w.getUnit();
        }
        boolean hasWipPending = wipPending.compareTo(BigDecimal.ZERO) > 0;

        // 2) 批次完工判定: COMPLETED/CANCELLED 视为已完工 (终态), 其余 (PLANNED/IN_PROGRESS/PAUSED) 视为进行中
        ProductionBatch pb = productionBatchRepository.findByIdAndFactoryId(batchId, factoryId).orElse(null);
        boolean batchFinished = pb != null
                && (pb.getStatus() == ProductionBatchStatus.COMPLETED
                    || pb.getStatus() == ProductionBatchStatus.CANCELLED);

        // 进行中 = 批次未完工 OR 仍有在制 WIP 余额
        boolean inProgress = !batchFinished || hasWipPending;

        dto.setInProgress(inProgress);
        dto.setWipInProgressQuantity(inProgress ? wipPending : BigDecimal.ZERO);
        dto.setWipInProgressUnit(hasWipPending ? wipUnit : null);
        // asOfYieldRate: 进行中参考数, 当前与 A 口径同源 (末道总产出/首道总投入)
        dto.setAsOfYieldRate(dto.getCumulativeYieldRate());
    }

    /**
     * P0-2: 取末道报工的 productTypeId → ProductType.gramsPerUnit (份/盒→kg 折算系数).
     * reports 为空 / 无 productTypeId / 产品无克重 → null (calc 据此保持 cumulative=null, 不臆造).
     */
    private BigDecimal resolveGramsPerUnit(String factoryId, List<ProductionReport> reports) {
        if (reports == null || reports.isEmpty()) return null;
        // 同批同产品, 取任一 report 的 productTypeId 即可
        String productTypeId = reports.stream()
                .map(ProductionReport::getProductTypeId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (productTypeId == null) return null;
        return productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .map(pt -> pt.getGramsPerUnit())
                .orElse(null);
    }

    /** audit YIELD-4: 批量查 task→work_process→processName 回填 steps (避免 N+1). 查不到留 null, 前端 fallback. */
    private void enrichProcessNames(String factoryId, Long batchId, BatchYieldDTO dto) {
        if (dto.getSteps() == null || dto.getSteps().isEmpty()) {
            return;
        }
        // 1) 操作员报工 (有 taskId): taskId → WorkProcessTask → WorkProcess.processName
        Set<Long> taskIds = dto.getSteps().stream()
                .map(StepYieldDTO::getWorkProcessTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!taskIds.isEmpty()) {
            Map<Long, String> taskToProcessId = taskRepo.findByFactoryIdAndIdIn(factoryId, taskIds).stream()
                    .filter(t -> t.getWorkProcessId() != null)
                    .collect(Collectors.toMap(WorkProcessTask::getId, WorkProcessTask::getWorkProcessId, (a, b) -> a));
            Set<String> processIds = new HashSet<>(taskToProcessId.values());
            Map<String, String> processIdToName = processRepo.findAllById(processIds).stream()
                    .collect(Collectors.toMap(WorkProcess::getId, WorkProcess::getProcessName, (a, b) -> a));
            for (StepYieldDTO step : dto.getSteps()) {
                String pid = taskToProcessId.get(step.getWorkProcessTaskId());
                if (pid != null) {
                    step.setProcessName(processIdToName.get(pid));
                }
            }
        }
        // 2) 文员逐道录入 (task=null): 按产品工序配置 (ProductWorkProcess) processOrder → processName。
        //    Config-driven, 非硬编码。G0 后文员行 processOrder = ProductWorkProcess.processOrder (同源), 对齐安全。
        boolean anyMissing = dto.getSteps().stream().anyMatch(s -> s.getProcessName() == null);
        if (anyMissing && batchId != null) {
            productionBatchRepository.findByIdAndFactoryId(batchId, factoryId).ifPresent(pb -> {
                List<com.cretas.aims.entity.ProductWorkProcess> pwps = productWorkProcessRepository
                        .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, pb.getProductTypeId());
                // ProductWorkProcess 无 processName, 经 workProcessId join WorkProcess.processName (同 task 路径)
                Set<String> wpIds = pwps.stream()
                        .map(com.cretas.aims.entity.ProductWorkProcess::getWorkProcessId)
                        .filter(Objects::nonNull).collect(Collectors.toSet());
                Map<String, String> wpName = processRepo.findAllById(wpIds).stream()
                        .collect(Collectors.toMap(WorkProcess::getId, WorkProcess::getProcessName, (a, b) -> a));
                Map<Integer, String> orderToName = new HashMap<>();
                for (com.cretas.aims.entity.ProductWorkProcess p : pwps) {
                    if (p.getProcessOrder() != null && p.getWorkProcessId() != null) {
                        String name = wpName.get(p.getWorkProcessId());
                        if (name != null) orderToName.putIfAbsent(p.getProcessOrder(), name);
                    }
                }
                for (StepYieldDTO step : dto.getSteps()) {
                    if (step.getProcessName() == null && step.getProcessOrder() != null) {
                        step.setProcessName(orderToName.get(step.getProcessOrder()));
                    }
                }
            });
        }
    }

    /**
     * M2 SP9: 聚合该批次全部 YIELD 报工的人工成本, 回写到 ProductionBatch.laborCost。
     *
     * <p>语义: laborCost = Σ production_reports.labor_cost (reportType=YIELD, 未删除)。
     * 诚实 null 传播: 若所有报工的 laborCost 均为 null (standard_hourly_rate 未配) → 批次 laborCost 保持 null;
     * 若至少一笔有值 → 累加 (null 视 0 贡献, 最终 > 0 则写回)。
     * fail-soft: 任何异常仅记 WARN, 不阻塞调用方事务。</p>
     */
    private void rollupLaborCostToBatch(String factoryId, Long batchId) {
        try {
            List<ProductionReport> reports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
            BigDecimal total = null;
            for (ProductionReport rpt : reports) {
                if (rpt.getLaborCost() != null) {
                    total = (total == null ? BigDecimal.ZERO : total).add(rpt.getLaborCost());
                }
            }
            if (total == null) {
                // 全部 null: 保持现有批次 laborCost 不动 (可能来自其他路径)
                return;
            }
            ProductionBatch pb = productionBatchRepository.findByIdAndFactoryId(batchId, factoryId).orElse(null);
            if (pb == null) return;
            pb.setLaborCost(total);
            pb.calculateMetrics(); // 重算 totalCost
            productionBatchRepository.save(pb);
            log.debug("[M2] batch={} laborCost rollup → {}", batchId, total);
        } catch (Exception e) {
            log.warn("[M2] 批次人工成本回写失败 (fail-soft): factoryId={} batchId={} err={}",
                    factoryId, batchId, e.getMessage());
        }
    }

    @Override
    @Transactional
    public Map<String, Object> settleDay(String factoryId, Long batchId, Long workerId, LocalDate date, boolean triggerComplete) {
        LocalDate d = date != null ? date : LocalDate.now();
        List<ProductionReport> unsettled = reportRepo.findUnsettledYieldReports(factoryId, batchId, d);
        for (ProductionReport r : unsettled) {
            r.setSettled(true);
            r.setSettledAt(LocalDateTime.now());
        }
        reportRepo.saveAll(unsettled);

        // M2 SP9: 结清时也同步回写批次人工成本聚合 (确保完工口径准确)
        rollupLaborCostToBatch(factoryId, batchId);

        Map<String, Object> out = new HashMap<>();
        out.put("settledCount", unsettled.size());
        BatchYieldDTO batchYield = getYield(factoryId, batchId);
        out.put("batchYield", batchYield);

        boolean completed = false;
        String completeError = null;
        if (triggerComplete && batchYield.getLastStepOutput() != null
                && batchYield.getLastStepOutput().compareTo(BigDecimal.ZERO) > 0) {
            BatchCloseReadiness readiness = batchCloseReadiness(factoryId, batchId, batchYield);
            if (!readiness.ready()) {
                completeError = readiness.message();
                out.put("incompleteTaskCount", readiness.incompleteTaskCount());
                out.put("incompleteTaskSummary", readiness.incompleteTaskSummary());
                log.warn("[完工入库] settle-day 触发完工跳过 (结清已成功不回滚): batch={} reason={}",
                        batchId, completeError);
                out.put("completed", false);
                out.put("completeError", completeError);
                return out;
            }
            // P1-1: 完工前预校验批次状态. completeProduction 仅允许 IN_PROGRESS/PAUSED, 否则抛 409
            // → 会污染本结清事务(settled 标记随回滚丢失). 先查状态, 仅可完工才调, 否则 completed=false
            // + completeError 透传前端(诚实提示"批次未开始生产"), 不抛异常.
            ProductionBatch pb = productionBatchRepository.findByIdAndFactoryId(batchId, factoryId).orElse(null);
            boolean canComplete = pb != null
                    && (pb.getStatus() == ProductionBatchStatus.IN_PROGRESS
                        || pb.getStatus() == ProductionBatchStatus.PAUSED);
            if (canComplete) {
                BigDecimal lastOutput = batchYield.getLastStepOutput();
                // P0-2: 成品按"末道产出单位"(份/盒) 入库, 份数原值入库 (订单达成率"份对份")
                String finishedUnit = batchYield.getLastStepOutputUnit();
                processingService.completeProduction(factoryId, String.valueOf(batchId),
                        lastOutput, lastOutput, BigDecimal.ZERO, finishedUnit);
                completed = true;
            } else {
                completeError = pb == null
                        ? "批次不存在"
                        : ("批次状态 " + pb.getStatus() + " 不允许完工 (需先开始生产)");
                log.warn("[完工入库] settle-day 触发完工跳过 (结清已成功不回滚): batch={} reason={}",
                        batchId, completeError);
            }
        }
        out.put("completed", completed);
        if (completeError != null) out.put("completeError", completeError);

        // D3 (Wave 3): 完工时若该批次仍有在制半成品结余 (WIP available > 0), 加诚实提示 (不阻塞完工)。
        // per 设计章三表 + fool-proof Rule 5: 提示退回总仓建调拨单, 给 next-action, 不 dead-end。
        if (completed) {
            addWipRemainingHint(factoryId, batchId, out);
        }
        return out;
    }

    /**
     * D3 (Wave 3): 完工后若批次仍有在制 WIP 结余 → 加 {@code wipRemainingHint} 诚实提示 (不阻塞完工)。
     *
     * <p>per 设计章三 + fool-proof Rule 5 (dead-end 改导航): 提示"结余 Xkg 半成品待退回总仓",
     * 让用户知道还有未消耗的中间品该处理 (退回总仓建调拨单)。仅提示, 不自动建单、不拦截完工。</p>
     *
     * <p>fail-soft: 查询/拼装失败不影响完工结果, 只记 WARN。</p>
     */
    private void addWipRemainingHint(String factoryId, Long batchId, Map<String, Object> out) {
        try {
            List<SemiFinishedInventory> remaining = wipRepo.findRemainingWip(factoryId, batchId);
            if (remaining == null || remaining.isEmpty()) return;
            BigDecimal total = BigDecimal.ZERO;
            String unit = null;
            for (SemiFinishedInventory w : remaining) {
                BigDecimal avail = nz(w.getAvailableQuantity());
                if (avail.compareTo(BigDecimal.ZERO) <= 0) continue;
                total = total.add(avail);
                if (unit == null && w.getUnit() != null) unit = w.getUnit();
            }
            if (total.compareTo(BigDecimal.ZERO) <= 0) return;
            String u = unit == null ? "" : unit;
            out.put("wipRemaining", total);
            out.put("wipRemainingUnit", unit);
            out.put("wipRemainingHint", String.format(
                    "结余 %s %s 半成品待退回总仓 (建调拨单退库), 完工不受影响",
                    total.stripTrailingZeros().toPlainString(), u));
        } catch (Exception e) {
            log.warn("[D3] 余料退回提示拼装失败 (fail-soft, 不影响完工): batch={}", batchId, e);
        }
    }
}
