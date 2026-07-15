package com.cretas.aims.service.workflow.impl;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.workflow.ProductionWorkflowRuntimeDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.workflow.CompiledProductProcessWorkflow;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeCompiler;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ProductProcessWorkflowRuntimeServiceImpl
        implements ProductProcessWorkflowRuntimeService {

    private final FactoryRepository factoryRepository;
    private final ProductionBatchRepository batchRepository;
    private final ProductTypeRepository productTypeRepository;
    private final ProductProcessWorkflowRepository workflowRepository;
    private final ProductionWorkflowInstanceRepository instanceRepository;
    private final WorkProcessTaskRepository taskRepository;
    private final WorkflowTaskPortRepository portRepository;
    private final ProductProcessWorkflowRuntimeCompiler compiler;
    private final ObjectMapper objectMapper;
    private final ProductUnitConversionRepository conversionRepository;
    private final ProductProcessWorkflowUnitValidator unitValidator;
    private final UnitContractService unitContractService;

    @Autowired
    public ProductProcessWorkflowRuntimeServiceImpl(
            FactoryRepository factoryRepository,
            ProductionBatchRepository batchRepository,
            ProductTypeRepository productTypeRepository,
            ProductProcessWorkflowRepository workflowRepository,
            ProductionWorkflowInstanceRepository instanceRepository,
            WorkProcessTaskRepository taskRepository,
            WorkflowTaskPortRepository portRepository,
            ProductProcessWorkflowRuntimeCompiler compiler,
            ObjectMapper objectMapper,
            ProductUnitConversionRepository conversionRepository,
            ProductProcessWorkflowUnitValidator unitValidator,
            UnitContractService unitContractService) {
        this.factoryRepository = factoryRepository;
        this.batchRepository = batchRepository;
        this.productTypeRepository = productTypeRepository;
        this.workflowRepository = workflowRepository;
        this.instanceRepository = instanceRepository;
        this.taskRepository = taskRepository;
        this.portRepository = portRepository;
        this.compiler = compiler;
        this.objectMapper = objectMapper;
        this.conversionRepository = conversionRepository;
        this.unitValidator = unitValidator;
        this.unitContractService = unitContractService;
    }

    @Override
    @Transactional
    public Optional<List<WorkProcessTaskDTO>> materializeIfActive(
            String factoryId,
            Long productionBatchId,
            String productTypeId) {
        ProductionBatch batch = validateRuntimeScope(factoryId, productionBatchId, productTypeId);

        Optional<ProductionWorkflowInstance> existing =
                instanceRepository.findByFactoryIdAndProductionBatchId(
                        factoryId, productionBatchId);
        if (existing.isPresent()) {
            return Optional.of(loadTaskDtos(factoryId, existing.get().getId()));
        }

        if (batch.getWorkflowSelectionMode()
                != ProductionBatch.WorkflowSelectionMode.WORKFLOW) {
            return Optional.empty();
        }
        if (batch.getSelectedWorkflowId() == null
                || batch.getSelectedWorkflowVersion() == null) {
            throw conflict(
                    "WORKFLOW_BATCH_SELECTION_INVALID",
                    "The batch Workflow selection is incomplete");
        }
        ProductProcessWorkflow workflow = workflowRepository
                .lockByIdAndFactoryId(batch.getSelectedWorkflowId(), factoryId)
                .filter(candidate -> candidate.getStatus() == ProductProcessWorkflow.Status.PUBLISHED)
                .filter(candidate -> productTypeId.equals(candidate.getProductTypeId()))
                .filter(candidate -> batch.getSelectedWorkflowVersion()
                        .equals(candidate.getDefinitionVersion()))
                .orElseThrow(() -> conflict(
                        "WORKFLOW_BATCH_SELECTION_TARGET_INVALID",
                        "The batch no longer references the exact published factory/product version"));
        if (Boolean.TRUE.equals(workflow.getUnitReviewRequired())) {
            throw conflict(
                    "WORKFLOW_UNIT_REVIEW_REQUIRED",
                    "The selected Workflow unit contract changed and must be reviewed before new materialization");
        }
        // 2B.2: 多产出已放开 — 编译产出的全部 OUTPUT 端口都会被持久化 (下方 portsFor 循环),
        // clerk 报工侧按端口分解为 N 个单产出物料化 (原 B1 single-output guard 移除)。
        ProductProcessWorkflowDTO runtimeDefinition = toDefinition(workflow);
        unitValidator.validateForPublish(factoryId, runtimeDefinition);
        CompiledProductProcessWorkflow compiled = compiler.compile(runtimeDefinition);
        ProductionWorkflowInstance instance = instanceRepository.save(
                ProductionWorkflowInstance.create(
                        factoryId,
                        batch.getId(),
                        productTypeId,
                        workflow.getId(),
                        workflow.getDefinitionVersion(),
                        compiled.nodesJson(),
                        compiled.edgesJson(),
                        LocalDateTime.now()));

        List<WorkProcessTask> tasks = new ArrayList<>();
        for (CompiledProductProcessWorkflow.CompiledTask compiledTask
                : compiled.reportableTasks()) {
            WorkProcessTask task = taskRepository.save(WorkProcessTask.builder()
                    .factoryId(factoryId)
                    .productionBatchId(productionBatchId)
                    .productWorkProcessId(null)
                    .workflowInstanceId(instance.getId())
                    .workflowNodeId(compiledTask.workflowNodeId())
                    .workProcessId(compiledTask.workProcessId())
                    .productTypeId(productTypeId)
                    .processOrder(compiledTask.processOrder())
                    .status(WorkProcessTask.Status.PENDING)
                    .plannedUnit(compiledTask.plannedUnit())
                    .estimatedMinutes(compiledTask.estimatedMinutes())
                    .build());
            tasks.add(task);

            for (CompiledProductProcessWorkflow.CompiledPort compiledPort
                    : compiled.portsFor(compiledTask.workflowNodeId())) {
                portRepository.save(toPort(factoryId, instance.getId(), task.getId(), compiledPort));
            }
        }
        return Optional.of(tasks.stream().map(this::toTaskDTO).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionWorkflowRuntimeDTO getRuntime(String factoryId, Long productionBatchId) {
        ProductionWorkflowInstance instance = instanceRepository
                .findByFactoryIdAndProductionBatchId(factoryId, productionBatchId)
                .orElse(null);
        if (instance == null) {
            return null;
        }
        List<WorkProcessTask> tasks = taskRepository
                .findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(
                        factoryId, instance.getId());
        List<WorkflowTaskPort> ports = portRepository
                .findByFactoryIdAndWorkflowInstanceId(factoryId, instance.getId());

        Map<Long, List<WorkflowTaskPort>> portsByTask = new HashMap<>();
        for (WorkflowTaskPort port : ports) {
            portsByTask.computeIfAbsent(port.getTaskId(), ignored -> new ArrayList<>()).add(port);
        }
        portsByTask.values().forEach(taskPorts -> taskPorts.sort(
                Comparator.comparing(WorkflowTaskPort::getOrdinal)
                        .thenComparing(WorkflowTaskPort::getWorkflowPortId)));

        List<ProductionWorkflowRuntimeDTO.TaskRuntimeDTO> taskRuntime = tasks.stream()
                .map(task -> ProductionWorkflowRuntimeDTO.TaskRuntimeDTO.builder()
                        .task(toTaskDTO(task))
                        .ports(portsByTask.getOrDefault(task.getId(), List.of()).stream()
                                .map(this::toPortDTO)
                                .toList())
                        .build())
                .toList();
        return ProductionWorkflowRuntimeDTO.builder()
                .workflowInstanceId(instance.getId())
                .factoryId(instance.getFactoryId())
                .productionBatchId(instance.getProductionBatchId())
                .productTypeId(instance.getProductTypeId())
                .workflowId(instance.getWorkflowId())
                .definitionVersion(instance.getDefinitionVersion())
                .nodesJson(instance.getNodesJson())
                .edgesJson(instance.getEdgesJson())
                .status(instance.getStatus())
                .compiledAt(instance.getCompiledAt())
                .tasks(taskRuntime)
                .build();
    }

    private ProductionBatch validateRuntimeScope(
            String factoryId,
            Long productionBatchId,
            String productTypeId) {
        if (!factoryRepository.existsById(factoryId)) {
            throw notFound("WORKFLOW_RUNTIME_FACTORY_NOT_FOUND", "Factory was not found");
        }
        ProductionBatch batch = batchRepository.findByIdAndFactoryId(productionBatchId, factoryId)
                .orElseThrow(() -> notFound(
                        "WORKFLOW_RUNTIME_BATCH_NOT_FOUND",
                        "Production batch was not found in this factory"));
        productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .orElseThrow(() -> notFound(
                        "WORKFLOW_RUNTIME_PRODUCT_NOT_FOUND",
                        "Product was not found in this factory"));
        if (!productTypeId.equals(batch.getProductTypeId())) {
            throw conflict(
                    "WORKFLOW_RUNTIME_BATCH_PRODUCT_MISMATCH",
                    "Production batch does not belong to the requested product");
        }
        return batch;
    }

    private List<WorkProcessTaskDTO> loadTaskDtos(String factoryId, Long workflowInstanceId) {
        return taskRepository.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(
                        factoryId, workflowInstanceId).stream()
                .map(this::toTaskDTO)
                .toList();
    }

    private ProductProcessWorkflowDTO toDefinition(ProductProcessWorkflow entity) {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setId(entity.getId());
        definition.setFactoryId(entity.getFactoryId());
        definition.setProductTypeId(entity.getProductTypeId());
        definition.setSchemaVersion(entity.getSchemaVersion());
        definition.setStatus(entity.getStatus().name());
        definition.setVersion(entity.getDefinitionVersion());
        definition.setLockVersion(entity.getLockVersion());
        try {
            definition.setNodes(objectMapper.readValue(
                    entity.getNodesJson(),
                    new TypeReference<List<ProductProcessWorkflowDTO.Node>>() { }));
            definition.setEdges(objectMapper.readValue(
                    entity.getEdgesJson(),
                    new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() { }));
            definition.setViewport(objectMapper.readValue(
                    entity.getViewportJson(),
                    ProductProcessWorkflowDTO.Viewport.class));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(500, "Stored Workflow definition is invalid", exception)
                    .withCode("PRODUCT_PROCESS_WORKFLOW_DATA_INVALID")
                    .withHint("Repair the published Workflow definition before materializing a batch")
                    .withSeverity("error");
        }
        return definition;
    }

    private WorkflowTaskPort toPort(
            String factoryId,
            Long workflowInstanceId,
            Long taskId,
            CompiledProductProcessWorkflow.CompiledPort compiled) {
        WorkflowTaskPort port = new WorkflowTaskPort();
        port.setFactoryId(factoryId);
        port.setWorkflowInstanceId(workflowInstanceId);
        port.setTaskId(taskId);
        port.setWorkflowPortId(compiled.workflowPortId());
        port.setDirection(WorkflowTaskPort.Direction.valueOf(compiled.direction()));
        port.setOrdinal(compiled.ordinal());
        port.setMaterialNodeId(compiled.materialNodeId());
        port.setMaterialKind(compiled.materialKind());
        port.setSkuId(compiled.skuId());
        port.setUnit(compiled.unit());
        port.setUnitCode(compiled.unit());
        if ("FINISHED_GOOD".equals(compiled.materialKind())) {
            port.setNetWeightGramsSnapshot(productTypeRepository
                    .findByIdAndFactoryId(compiled.skuId(), factoryId)
                    .map(com.cretas.aims.entity.ProductType::getGramsPerUnit)
                    .orElse(null));
        }
        port.setMaterialPrimaryUnitCode(compiled.materialPrimaryUnitCode());
        port.setConversionRefId(compiled.conversionRefId());
        port.setConversionVersion(compiled.conversionVersion());
        ConversionSnapshot snapshot = resolveConversionSnapshot(factoryId, compiled);
        port.setConversionFromUnitCode(snapshot.fromUnitCode());
        port.setConversionToUnitCode(snapshot.toUnitCode());
        port.setConversionFactorSnapshot(snapshot.storedFactor());
        port.setPortToPrimaryFactorSnapshot(snapshot.portToPrimaryFactor());
        port.setRequired(compiled.required());
        port.setConversionMode(compiled.conversionMode());
        port.setConversionExpression(compiled.conversionExpression());
        return port;
    }

    private ConversionSnapshot resolveConversionSnapshot(
            String factoryId,
            CompiledProductProcessWorkflow.CompiledPort compiled) {
        if (compiled.conversionRefId() == null) {
            if (!compiled.unit().equals(compiled.materialPrimaryUnitCode())) {
                throw staleConversion("Workflow port unit differs from material primary unit without conversion");
            }
            return new ConversionSnapshot(null, null, null, BigDecimal.ONE);
        }
        var conversion = conversionRepository.findById(compiled.conversionRefId())
                .orElseThrow(() -> new BusinessException(409, "Workflow unit conversion no longer exists")
                        .withCode("WORKFLOW_PORT_UNIT_STALE")
                        .withHint("Clone and republish the Workflow with a current conversion")
                        .withSeverity("warning"));
        LocalDateTime now = LocalDateTime.now();
        if (compiled.conversionVersion() == null
                || !compiled.conversionVersion().equals(conversion.getVersion())
                || !factoryId.equals(conversion.getFactoryId())
                || !compiled.skuId().equals(conversion.getProductTypeId())
                || conversion.getEffectiveFrom().isAfter(now)
                || (conversion.getEffectiveTo() != null && !conversion.getEffectiveTo().isAfter(now))) {
            throw new BusinessException(409, "Workflow unit conversion version is stale")
                    .withCode("WORKFLOW_PORT_UNIT_STALE")
                    .withHint("Clone and republish the Workflow with the current conversion version")
                    .withSeverity("warning");
        }
        BigDecimal factor = conversion.getFactor();
        if (factor == null || factor.signum() <= 0) {
            throw staleConversion("Workflow unit conversion factor must be positive");
        }
        String from = canonicalConversionEndpoint(factoryId, conversion.getFromUnitCode());
        String to = canonicalConversionEndpoint(factoryId, conversion.getToUnitCode());
        BigDecimal oriented;
        if (compiled.unit().equals(from) && compiled.materialPrimaryUnitCode().equals(to)) {
            oriented = factor;
        } else if (compiled.unit().equals(to) && compiled.materialPrimaryUnitCode().equals(from)) {
            oriented = BigDecimal.ONE.divide(factor, 8, RoundingMode.HALF_UP);
        } else {
            throw staleConversion("Workflow unit conversion endpoints no longer match the port and material units");
        }
        return new ConversionSnapshot(from, to, factor, oriented);
    }

    private String canonicalConversionEndpoint(String factoryId, String rawUnit) {
        var normalized = unitContractService.normalize(factoryId, rawUnit);
        if (!normalized.recognized()) {
            throw staleConversion("Workflow unit conversion endpoint is unknown or ambiguous: " + rawUnit);
        }
        return normalized.code();
    }

    private BusinessException staleConversion(String message) {
        return new BusinessException(409, message)
                .withCode("WORKFLOW_PORT_UNIT_STALE")
                .withHint("Clone and republish the Workflow with the current conversion version")
                .withSeverity("warning");
    }

    private WorkProcessTaskDTO toTaskDTO(WorkProcessTask task) {
        return WorkProcessTaskDTO.builder()
                .id(task.getId())
                .factoryId(task.getFactoryId())
                .productionBatchId(task.getProductionBatchId())
                .productWorkProcessId(task.getProductWorkProcessId())
                .workflowInstanceId(task.getWorkflowInstanceId())
                .workflowNodeId(task.getWorkflowNodeId())
                .workProcessId(task.getWorkProcessId())
                .productTypeId(task.getProductTypeId())
                .processOrder(task.getProcessOrder())
                .status(task.getStatus())
                .plannedQuantity(task.getPlannedQuantity())
                .plannedUnit(task.getPlannedUnit())
                .plannedStartAt(task.getPlannedStartAt())
                .plannedEndAt(task.getPlannedEndAt())
                .estimatedMinutes(task.getEstimatedMinutes())
                .actualQuantity(task.getActualQuantity())
                .actualStartAt(task.getActualStartAt())
                .actualEndAt(task.getActualEndAt())
                .actualMinutes(task.getActualMinutes())
                .assignedTo(task.getAssignedTo())
                .completedBy(task.getCompletedBy())
                .completedAt(task.getCompletedAt())
                .notes(task.getNotes())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }

    private ProductionWorkflowRuntimeDTO.PortDTO toPortDTO(WorkflowTaskPort port) {
        return ProductionWorkflowRuntimeDTO.PortDTO.builder()
                .id(port.getId())
                .factoryId(port.getFactoryId())
                .workflowInstanceId(port.getWorkflowInstanceId())
                .taskId(port.getTaskId())
                .workflowPortId(port.getWorkflowPortId())
                .direction(port.getDirection())
                .ordinal(port.getOrdinal())
                .materialNodeId(port.getMaterialNodeId())
                .materialKind(port.getMaterialKind())
                .skuId(port.getSkuId())
                .unit(port.getUnit())
                .unitCode(port.getUnitCode())
                .materialPrimaryUnitCode(port.getMaterialPrimaryUnitCode())
                .conversionRefId(port.getConversionRefId())
                .conversionVersion(port.getConversionVersion())
                .conversionFromUnitCode(port.getConversionFromUnitCode())
                .conversionToUnitCode(port.getConversionToUnitCode())
                .conversionFactorSnapshot(port.getConversionFactorSnapshot())
                .portToPrimaryFactorSnapshot(port.getPortToPrimaryFactorSnapshot())
                .required(port.getRequired())
                .conversionMode(port.getConversionMode())
                .conversionExpression(port.getConversionExpression())
                .build();
    }

    private BusinessException notFound(String code, String message) {
        return new BusinessException(404, message)
                .withCode(code)
                .withHint("Verify the factory-scoped batch and product identifiers")
                .withSeverity("warning");
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(409, message)
                .withCode(code)
                .withHint("Refresh the activation and retry with the exact published version")
                .withSeverity("warning");
    }

    private record ConversionSnapshot(
            String fromUnitCode,
            String toUnitCode,
            BigDecimal storedFactor,
            BigDecimal portToPrimaryFactor) {
    }
}
