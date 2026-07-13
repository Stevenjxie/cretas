package com.cretas.aims.service.workflow.impl;

import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final ProductionWorkflowInstanceRepository instanceRepository;
    private final WorkProcessTaskRepository taskRepository;
    private final WorkflowTaskPortRepository portRepository;
    private final WorkProcessRepository workProcessRepository;
    private final ProductWorkProcessRepository productWorkProcessRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final ProductTypeRepository productTypeRepository;

    @Override
    @Transactional(readOnly = true)
    public WorkflowClerkSheetConfigDTO getWorkflowSheetConfig(String factoryId, String planId) {
        ProductionBatch workflowBatch = findWorkflowBatch(factoryId, planId);
        if (workflowBatch == null) {
            return null;
        }

        ProductionWorkflowInstance instance = instanceRepository
                .findByFactoryIdAndProductionBatchId(factoryId, workflowBatch.getId())
                .orElse(null);
        if (instance == null) {
            // Batch is pinned to WORKFLOW mode but has not been materialized yet
            // (e.g. spawnTasks has not run) — nothing to project, fall back to legacy.
            return null;
        }

        List<WorkProcessTask> tasks = taskRepository
                .findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(factoryId, instance.getId());
        List<WorkflowTaskPort> ports = portRepository
                .findByFactoryIdAndWorkflowInstanceId(factoryId, instance.getId());

        Map<Long, List<WorkflowTaskPort>> portsByTask = new HashMap<>();
        for (WorkflowTaskPort port : ports) {
            portsByTask.computeIfAbsent(port.getTaskId(), ignored -> new ArrayList<>()).add(port);
        }

        List<WorkflowClerkSheetConfigDTO.ProcessDescriptor> processes = new ArrayList<>(tasks.size());
        for (WorkProcessTask task : tasks) {
            processes.add(buildDescriptor(
                    factoryId, task, portsByTask.getOrDefault(task.getId(), List.of())));
        }

        return WorkflowClerkSheetConfigDTO.builder()
                .workflowBatchId(workflowBatch.getId())
                .workflowInstanceId(instance.getId())
                .productTypeId(instance.getProductTypeId())
                .processes(processes)
                .build();
    }

    private ProductionBatch findWorkflowBatch(String factoryId, String planId) {
        return productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId).stream()
                .filter(batch -> batch.getWorkflowSelectionMode()
                        == ProductionBatch.WorkflowSelectionMode.WORKFLOW)
                .findFirst()
                .orElse(null);
    }

    private WorkflowClerkSheetConfigDTO.ProcessDescriptor buildDescriptor(
            String factoryId, WorkProcessTask task, List<WorkflowTaskPort> taskPorts) {
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
                outputs.stream().map(port -> toPortDescriptor(factoryId, port)).toList();

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
                .plannedUnit(task.getPlannedUnit())
                .allowMultipleUpstreamSources(upstreamInputCount > 1)
                .allowFinishedGoodsSource(allowFinishedGoodsSource)
                .customFieldSchema(workProcess != null ? workProcess.getCustomFieldSchema() : null)
                .inputs(inputs.stream().map(port -> toPortDescriptor(factoryId, port)).toList())
                .output(outputDescriptors.isEmpty() ? null : outputDescriptors.get(0)) // 向后兼容单产出 FE
                .outputs(outputDescriptors)
                .build();
    }

    private WorkflowClerkSheetConfigDTO.PortDescriptor toPortDescriptor(
            String factoryId, WorkflowTaskPort port) {
        SkuLookup lookup = resolveSku(factoryId, port.getMaterialKind(), port.getSkuId());
        String unit = (port.getUnit() != null && !port.getUnit().isBlank())
                ? port.getUnit() : lookup.unit();
        return WorkflowClerkSheetConfigDTO.PortDescriptor.builder()
                .workflowPortId(port.getWorkflowPortId())
                .materialNodeId(port.getMaterialNodeId())
                .materialKind(port.getMaterialKind())
                .skuId(port.getSkuId())
                .materialName(lookup.name())
                .unit(unit)
                .required(port.getRequired())
                .skuResolved(lookup.resolved())
                .finished(FINISHED_GOOD.equals(port.getMaterialKind()))
                .build();
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
