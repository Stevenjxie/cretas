package com.cretas.aims.service.workflow.impl;

import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.dto.bom.BomItemSubstituteDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import com.cretas.aims.service.workflow.WorkflowReportingUnitResolver;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 2B Task B2 — thin projection: workflow 批次快照 → clerk 过程单配置。
 *
 * <p>不做任何库存/成本写入; saveRow → materializeBatch → interim-settle 全部复用不变
 * (spec 2026-07-11-product-process-workflow-runtime-2b-clerk-implementation.md)。
 */
@Service
@RequiredArgsConstructor
public class WorkflowClerkSheetServiceImpl implements WorkflowClerkSheetService {

    private static final String RAW_MATERIAL = "RAW_MATERIAL";
    private static final String SEMI_FINISHED = "SEMI_FINISHED";
    private static final String FINISHED_GOOD = "FINISHED_GOOD";

    private final ProductionBatchRepository productionBatchRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final ProductionWorkflowInstanceRepository instanceRepository;
    private final WorkProcessTaskRepository taskRepository;
    private final WorkflowTaskPortRepository portRepository;
    private final WorkProcessRepository workProcessRepository;
    private final ProductWorkProcessRepository productWorkProcessRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final ProductTypeRepository productTypeRepository;
    private final BomRecipeRepository bomRecipeRepository;
    private final BomRecipeItemRepository bomRecipeItemRepository;
    private final BomItemSubstituteService substituteService;
    private final WorkflowReportingUnitResolver reportingUnitResolver;

    @Override
    @Transactional(readOnly = true)
    public WorkflowClerkSheetConfigDTO getWorkflowSheetConfig(String factoryId, String planId) {
        WorkflowRuntimeSelection runtimeSelection = findWorkflowRuntime(factoryId, planId);
        if (runtimeSelection == null) {
            return null;
        }

        ProductionBatch workflowBatch = runtimeSelection.batch();
        ProductionWorkflowInstance instance = runtimeSelection.instance();
        if (instance == null) {
            throw new BusinessException(409, "生产批次已锁定 Workflow，但运行时快照尚未生成")
                    .withCode("WORKFLOW_RUNTIME_NOT_MATERIALIZED")
                    .withHint("请重新生成该批次的 Workflow 工序任务后再报工")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }

        List<WorkProcessTask> tasks = taskRepository
                .findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(factoryId, instance.getId());
        if (tasks == null || tasks.isEmpty()) {
            throw new BusinessException(409, "Workflow 运行时快照没有工序任务")
                    .withCode("WORKFLOW_RUNTIME_TASKS_MISSING")
                    .withHint("请重新生成该批次的 Workflow 工序任务后再报工")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        List<WorkflowTaskPort> ports = portRepository
                .findByFactoryIdAndWorkflowInstanceId(factoryId, instance.getId());
        Map<String, List<String>> allowedSkuIdsByPort =
                resolvePinnedBomInputCandidates(factoryId, planId);

        Map<Long, List<WorkflowTaskPort>> portsByTask = new HashMap<>();
        for (WorkflowTaskPort port : ports) {
            portsByTask.computeIfAbsent(port.getTaskId(), ignored -> new ArrayList<>()).add(port);
        }

        List<WorkflowClerkSheetConfigDTO.ProcessDescriptor> processes = new ArrayList<>(tasks.size());
        for (WorkProcessTask task : tasks) {
            processes.add(buildDescriptor(
                    factoryId,
                    task,
                    portsByTask.getOrDefault(task.getId(), List.of()),
                    allowedSkuIdsByPort));
        }

        return WorkflowClerkSheetConfigDTO.builder()
                .workflowBatchId(workflowBatch.getId())
                .workflowInstanceId(instance.getId())
                .productTypeId(instance.getProductTypeId())
                .processes(processes)
                .build();
    }

    private WorkflowRuntimeSelection findWorkflowRuntime(String factoryId, String planId) {
        List<ProductionBatch> workflowBatches = productionBatchRepository
                .findByFactoryIdAndProductionPlanId(factoryId, planId).stream()
                .filter(batch -> batch.getWorkflowSelectionMode()
                        == ProductionBatch.WorkflowSelectionMode.WORKFLOW)
                .toList();
        if (workflowBatches.isEmpty()) {
            return null;
        }

        long distinctWorkflowPins = workflowBatches.stream()
                .map(batch -> new WorkflowPin(
                        batch.getSelectedWorkflowId(), batch.getSelectedWorkflowVersion()))
                .distinct()
                .count();
        if (distinctWorkflowPins > 1) {
            throw new BusinessException(409, "该生产计划关联了多个 Workflow 批次，无法唯一确定报工快照")
                    .withCode("WORKFLOW_RUNTIME_BATCH_AMBIGUOUS")
                    .withHint("请从具体生产批次进入逐道报工，或清理重复批次")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }

        List<WorkflowRuntimeSelection> materializedRuntimes = workflowBatches.stream()
                .map(batch -> new WorkflowRuntimeSelection(
                        batch,
                        instanceRepository
                                .findByFactoryIdAndProductionBatchId(factoryId, batch.getId())
                                .orElse(null)))
                .filter(selection -> selection.instance() != null)
                .toList();
        if (materializedRuntimes.size() > 1) {
            throw new BusinessException(409, "该生产计划关联了多个 Workflow 运行时快照，无法唯一确定报工快照")
                    .withCode("WORKFLOW_RUNTIME_BATCH_AMBIGUOUS")
                    .withHint("请从具体生产批次进入逐道报工，或清理重复运行时")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        if (materializedRuntimes.size() == 1) {
            return materializedRuntimes.getFirst();
        }
        return new WorkflowRuntimeSelection(workflowBatches.getFirst(), null);
    }

    private record WorkflowPin(Long workflowId, Integer workflowVersion) {}

    private record WorkflowRuntimeSelection(
            ProductionBatch batch, ProductionWorkflowInstance instance) {}

    private WorkflowClerkSheetConfigDTO.ProcessDescriptor buildDescriptor(
            String factoryId,
            WorkProcessTask task,
            List<WorkflowTaskPort> taskPorts,
            Map<String, List<String>> allowedSkuIdsByPort) {
        boolean projectReportingUnits = task.getStatus() == null || !task.getStatus().isTerminal();
        List<WorkflowTaskPort> inputs = new ArrayList<>();
        List<WorkflowTaskPort> outputs = new ArrayList<>();
        for (WorkflowTaskPort port : taskPorts) {
            if (port.getDirection() == WorkflowTaskPort.Direction.INPUT) {
                inputs.add(port);
            } else {
                outputs.add(port);
            }
        }
        inputs.sort(Comparator.comparing(WorkflowTaskPort::getOrdinal)
                .thenComparing(WorkflowTaskPort::getWorkflowPortId));
        // 2B.2: 多产出支持 — 按 ordinal 排序投影全部产出端口 (不再抛 single-output guard)。
        outputs.sort(Comparator.comparing(WorkflowTaskPort::getOrdinal)
                .thenComparing(WorkflowTaskPort::getWorkflowPortId));
        List<WorkflowClerkSheetConfigDTO.PortDescriptor> outputDescriptors =
                outputs.stream()
                        .map(port -> toPortDescriptor(factoryId, port, projectReportingUnits))
                        .toList();
        if (outputDescriptors.isEmpty()) {
            throw new BusinessException(409, "Workflow 工序缺少产出端口")
                    .withCode("WORKFLOW_RUNTIME_OUTPUT_PORT_MISSING")
                    .withHint("请修复 Workflow 后为新批次重新生成运行时快照")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }

        WorkProcess workProcess = workProcessRepository
                .findByFactoryIdAndId(factoryId, task.getWorkProcessId())
                .orElse(null);
        ProductWorkProcess productWorkProcess = productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdAndWorkProcessId(
                        factoryId, task.getProductTypeId(), task.getWorkProcessId())
                .orElse(null);

        boolean allowFinishedGoodsSource = inputs.stream()
                .anyMatch(port -> FINISHED_GOOD.equals(port.getMaterialKind()));
        long upstreamInputCount = inputs.stream()
                .filter(port -> SEMI_FINISHED.equals(port.getMaterialKind())
                        || FINISHED_GOOD.equals(port.getMaterialKind()))
                .count();

        return WorkflowClerkSheetConfigDTO.ProcessDescriptor.builder()
                .workflowNodeId(task.getWorkflowNodeId())
                .workProcessId(task.getWorkProcessId())
                .processName(workProcess != null ? workProcess.getProcessName() : null)
                .processCategory(workProcess != null ? workProcess.getProcessCategory() : null)
                .defaultCostCategory(
                        productWorkProcess != null ? productWorkProcess.getDefaultCostCategory() : null)
                .processOrder(task.getProcessOrder())
                .plannedUnit(projectReportingUnits
                        ? outputDescriptors.getFirst().getUnit()
                        : task.getPlannedUnit())
                .allowMultipleUpstreamSources(upstreamInputCount > 1)
                .allowFinishedGoodsSource(allowFinishedGoodsSource)
                .customFieldSchema(workProcess != null ? workProcess.getCustomFieldSchema() : null)
                .inputs(inputs.stream()
                        .map(port -> toPortDescriptor(
                                factoryId, port, projectReportingUnits, allowedSkuIdsByPort))
                        .toList())
                .output(outputDescriptors.isEmpty() ? null : outputDescriptors.get(0)) // 向后兼容单产出 FE
                .outputs(outputDescriptors)
                .build();
    }

    private WorkflowClerkSheetConfigDTO.PortDescriptor toPortDescriptor(
            String factoryId, WorkflowTaskPort port, boolean projectReportingUnit) {
        return toPortDescriptor(factoryId, port, projectReportingUnit, Map.of());
    }

    private WorkflowClerkSheetConfigDTO.PortDescriptor toPortDescriptor(
            String factoryId,
            WorkflowTaskPort port,
            boolean projectReportingUnit,
            Map<String, List<String>> allowedSkuIdsByPort) {
        List<String> allowedSkuIds = allowedSkuIdsByPort.getOrDefault(
                port.getWorkflowPortId(),
                port.getSkuId() == null ? List.of() : List.of(port.getSkuId()));
        String effectiveSkuId = WorkflowTaskPort.Direction.INPUT.equals(port.getDirection())
                && !allowedSkuIds.isEmpty()
                ? allowedSkuIds.getFirst()
                : port.getSkuId();
        SkuLookup lookup = resolveSku(factoryId, port.getMaterialKind(), effectiveSkuId);
        String unit = port.getUnit();
        if (unit == null || unit.isBlank()) {
            throw new BusinessException(409, "Workflow 运行时端口缺少快照单位")
                    .withCode("WORKFLOW_RUNTIME_PORT_UNIT_MISSING")
                    .withHint("请修复 Workflow 后为新批次重新生成运行时快照")
                    .withSeverity("BLOCKING")
                    .withHintTarget("Workflow");
        }
        if (projectReportingUnit
                && (lookup.resolved() || !FINISHED_GOOD.equals(port.getMaterialKind()))) {
            unit = reportingUnitResolver.resolve(
                    factoryId, port.getMaterialKind(), effectiveSkuId, unit);
        }
        return WorkflowClerkSheetConfigDTO.PortDescriptor.builder()
                .workflowPortId(port.getWorkflowPortId())
                .materialNodeId(port.getMaterialNodeId())
                .materialKind(port.getMaterialKind())
                .skuId(effectiveSkuId)
                .allowedSkuIds(allowedSkuIds)
                .materialName(lookup.name())
                .unit(unit.trim())
                .gramsPerUnit(port.getNetWeightGramsSnapshot())
                .required(port.getRequired())
                .selectionGroupId(port.getSelectionGroupId())
                .selectionGroupLabel(port.getSelectionGroupLabel())
                .selectionGroupMode(port.getSelectionGroupMode())
                .selectionGroupMinSelections(port.getSelectionGroupMinSelections())
                .selectionGroupMaxSelections(port.getSelectionGroupMaxSelections())
                .skuResolved(lookup.resolved())
                .finished(FINISHED_GOOD.equals(port.getMaterialKind()))
                .build();
    }

    private Map<String, List<String>> resolvePinnedBomInputCandidates(
            String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new BusinessException(403, "无权访问该生产计划"));
        String recipeId = plan.getSelectedBomRecipeId();
        if (recipeId == null || recipeId.isBlank()) {
            return Map.of();
        }
        BomRecipe recipe = bomRecipeRepository.findById(recipeId)
                .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                .orElseThrow(() -> new BusinessException(409, "计划固定的 BOM 版本不存在")
                        .withCode("PINNED_BOM_NOT_FOUND")
                        .withSeverity("BLOCKING"));
        List<BomRecipeItem> items =
                bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(recipe.getId());
        Map<Long, List<BomItemSubstituteDTO>> substitutesByParent = new HashMap<>();
        for (BomItemSubstituteDTO substitute :
                substituteService.listByRecipe(factoryId, recipe.getId())) {
            if (substitute.getParentRecipeItemId() != null) {
                substitutesByParent.computeIfAbsent(
                        substitute.getParentRecipeItemId(), ignored -> new ArrayList<>())
                        .add(substitute);
            }
        }
        Map<String, List<String>> result = new HashMap<>();
        for (BomRecipeItem item : items) {
            String portId = item.getWorkflowInputPortId();
            if (portId == null || portId.isBlank()) {
                continue;
            }
            Set<String> candidates = new LinkedHashSet<>();
            candidates.add(item.getMaterialTypeId());
            for (BomItemSubstituteDTO substitute :
                    substitutesByParent.getOrDefault(item.getId(), List.of())) {
                if (substitute.getSubstituteMaterialTypeId() != null
                        && !substitute.getSubstituteMaterialTypeId().isBlank()) {
                    candidates.add(substitute.getSubstituteMaterialTypeId());
                }
            }
            result.put(portId, List.copyOf(candidates));
        }
        return Map.copyOf(result);
    }

    /**
     * skuId 解析: RAW_MATERIAL → {@link RawMaterialType}, SEMI_FINISHED/FINISHED_GOOD →
     * {@link ProductType}。找不到 (被删除) 时不崩, 返回 unresolved (fool-proof Rule 5)。
     */
    private SkuLookup resolveSku(String factoryId, String materialKind, String skuId) {
        if (skuId == null) {
            return SkuLookup.unresolved();
        }
        if (RAW_MATERIAL.equals(materialKind)) {
            return rawMaterialTypeRepository.findById(skuId)
                    .filter(type -> factoryId.equals(type.getFactoryId()))
                    .map(WorkflowClerkSheetServiceImpl::fromRawMaterial)
                    .orElse(SkuLookup.unresolved());
        }
        return productTypeRepository.findByIdAndFactoryId(skuId, factoryId)
                .map(WorkflowClerkSheetServiceImpl::fromProductType)
                .orElse(SkuLookup.unresolved());
    }

    private static SkuLookup fromRawMaterial(RawMaterialType type) {
        return new SkuLookup(type.getName(), type.getUnit(), true);
    }

    private static SkuLookup fromProductType(ProductType type) {
        return new SkuLookup(type.getName(), type.getUnit(), true);
    }

    private record SkuLookup(String name, String unit, boolean resolved) {
        static SkuLookup unresolved() {
            return new SkuLookup(null, null, false);
        }
    }
}
