package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ProcessTaskDTO;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.ProcessTask;
import com.cretas.aims.entity.enums.ProcessTaskStatus;
import com.cretas.aims.entity.rules.StateMachine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProcessTaskRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.StateMachineRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.ProcessTaskService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProcessTaskServiceImpl implements ProcessTaskService {

    private static final Logger log = LoggerFactory.getLogger(ProcessTaskServiceImpl.class);
    private final ProcessTaskRepository repository;
    private final StateMachineRepository stateMachineRepository;
    private final WorkProcessRepository workProcessRepository;
    private final ProductWorkProcessRepository productWorkProcessRepository;
    private final ProductTypeRepository productTypeRepository;
    private final ProductionReportRepository reportRepository;
    /** R8: 用于解析 BATCH- 路径下的 WorkProcessTask 关联. */
    private final WorkProcessTaskRepository workProcessTaskRepository;
    /** R8: 用于解析批次号 (batchNumber) 填入 ProcessTaskDTO. */
    private final ProductionBatchRepository productionBatchRepository;

    @Override
    @Transactional
    public ProcessTaskDTO create(String factoryId, ProcessTaskDTO dto) {
        log.info("Creating process task for factory: {}, product: {}, process: {}",
                factoryId, dto.getProductTypeId(), dto.getWorkProcessId());

        // Auto-bind workflowVersionId from the published PRODUCTION_WORKFLOW StateMachine
        Integer workflowVersionId = dto.getWorkflowVersionId();
        if (workflowVersionId == null) {
            workflowVersionId = stateMachineRepository
                    .findByFactoryIdAndEntityTypeAndPublishStatus(factoryId, "PRODUCTION_WORKFLOW", "published")
                    .map(StateMachine::getVersion)
                    .orElse(null);
            if (workflowVersionId != null) {
                log.info("Auto-bound workflowVersionId={} from published PRODUCTION_WORKFLOW", workflowVersionId);
            }
        }

        ProcessTask entity = ProcessTask.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(factoryId)
                .productionRunId(dto.getProductionRunId() != null ?
                        dto.getProductionRunId() : UUID.randomUUID().toString())
                .productTypeId(dto.getProductTypeId())
                .workProcessId(dto.getWorkProcessId())
                .sourceCustomerName(dto.getSourceCustomerName())
                .sourceDocType(dto.getSourceDocType() != null ? dto.getSourceDocType() : "MANUAL")
                .sourceDocId(dto.getSourceDocId())
                .workflowVersionId(workflowVersionId)
                .plannedQuantity(dto.getPlannedQuantity())
                .completedQuantity(BigDecimal.ZERO)
                .pendingQuantity(BigDecimal.ZERO)
                .unit(dto.getUnit() != null ? dto.getUnit() : "kg")
                .startDate(dto.getStartDate())
                .expectedEndDate(dto.getExpectedEndDate())
                .status(ProcessTaskStatus.PENDING)
                .createdBy(dto.getCreatedBy())
                .notes(dto.getNotes())
                .build();

        ProcessTask saved = repository.save(entity);
        return toDTO(saved);
    }

    @Override
    public List<ProcessTaskDTO> getActiveTasks(String factoryId) {
        log.debug("Getting active process tasks for factory: {}", factoryId);
        return repository.findActiveTasks(factoryId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public PageResponse<ProcessTaskDTO> list(String factoryId, String status,
                                              String productTypeId, Pageable pageable) {
        log.debug("Listing process tasks for factory: {}, status: {}, product: {}",
                factoryId, status, productTypeId);

        Page<ProcessTask> page;
        if (status != null && productTypeId != null) {
            page = repository.findByFactoryIdAndStatusAndProductTypeId(
                    factoryId, ProcessTaskStatus.valueOf(status), productTypeId, pageable);
        } else if (status != null) {
            page = repository.findByFactoryIdAndStatus(
                    factoryId, ProcessTaskStatus.valueOf(status), pageable);
        } else if (productTypeId != null) {
            page = repository.findByFactoryIdAndProductTypeId(factoryId, productTypeId, pageable);
        } else {
            page = repository.findByFactoryId(factoryId, pageable);
        }

        List<ProcessTaskDTO> content = page.getContent().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        return PageResponse.of(content, page.getNumber() + 1, page.getSize(), page.getTotalElements());
    }

    @Override
    public ProcessTaskDTO getById(String factoryId, String id) {
        ProcessTask entity = repository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("ProcessTask", "id", id));
        return toDTO(entity);
    }

    @Override
    @Transactional
    public ProcessTaskDTO updateStatus(String factoryId, String id,
                                        ProcessTaskDTO.StatusUpdateRequest request) {
        log.info("Updating process task {} status to {} for factory: {}", id, request.getStatus(), factoryId);
        ProcessTask entity = repository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("ProcessTask", "id", id));

        ProcessTaskStatus newStatus = ProcessTaskStatus.valueOf(request.getStatus());
        ProcessTaskStatus currentStatus = entity.getStatus();

        // Validate state transitions
        validateStatusTransition(currentStatus, newStatus, entity);

        if (newStatus == ProcessTaskStatus.COMPLETED || newStatus == ProcessTaskStatus.CLOSED) {
            // Terminal states - record for potential SUPPLEMENTING later
        }

        entity.setStatus(newStatus);
        if (request.getNotes() != null) {
            entity.setNotes(request.getNotes());
        }

        ProcessTask saved = repository.save(entity);
        return toDTO(saved);
    }

    @Override
    @Transactional
    public ProcessTaskDTO closeTask(String factoryId, String id, String notes) {
        log.info("Closing process task {} for factory: {}", id, factoryId);
        ProcessTask entity = repository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("ProcessTask", "id", id));

        if (entity.getStatus() == ProcessTaskStatus.CLOSED) {
            throw new BusinessException(409, "任务已关闭")
                    .withHint("请刷新任务列表查看最新状态");
        }
        if (entity.getStatus() == ProcessTaskStatus.SUPPLEMENTING) {
            throw new BusinessException(409, "任务正在补报中，请等待补报完成")
                    .withHint("请等待补报审批完成后再操作");
        }

        entity.setStatus(ProcessTaskStatus.CLOSED);
        if (notes != null) entity.setNotes(notes);

        ProcessTask saved = repository.save(entity);
        return toDTO(saved);
    }

    @Override
    public ProcessTaskDTO.TaskSummary getTaskSummary(String factoryId, String id) {
        ProcessTask entity = repository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("ProcessTask", "id", id));

        // Enrich names
        String pName = null;
        String prName = null;
        try {
            if (entity.getWorkProcessId() != null) {
                WorkProcess wp = workProcessRepository.findById(entity.getWorkProcessId()).orElse(null);
                if (wp != null) pName = wp.getProcessName();
            }
            if (entity.getProductTypeId() != null) {
                ProductType pt = productTypeRepository.findById(entity.getProductTypeId()).orElse(null);
                if (pt != null) prName = pt.getName();
            }
        } catch (Exception ignored) {}

        // P2-2: Populate totalWorkers/totalReports from report aggregates
        List<Map<String, Object>> workerSummaries = reportRepository
                .getWorkerSummaryByTaskId(entity.getId());
        int totalReports = workerSummaries.stream()
                .mapToInt(w -> ((Number) w.getOrDefault("report_count", 0)).intValue())
                .sum();

        return ProcessTaskDTO.TaskSummary.builder()
                .taskId(entity.getId())
                .processName(pName)
                .productName(prName)
                .plannedQuantity(entity.getPlannedQuantity())
                .completedQuantity(entity.getCompletedQuantity())
                .pendingQuantity(entity.getPendingQuantity())
                .inputQuantity(entity.getInputQuantity())
                .unit(entity.getUnit())
                .status(entity.getStatus().name())
                .totalWorkers(workerSummaries.size())
                .totalReports(totalReports)
                .build();
    }

    @Override
    public ProcessTaskDTO.RunOverview getRunOverview(String factoryId, String productionRunId) {
        List<ProcessTask> tasks = repository.findByFactoryIdAndProductionRunId(factoryId, productionRunId);
        if (tasks.isEmpty()) {
            throw new ResourceNotFoundException("ProductionRun", "productionRunId", productionRunId);
        }

        ProcessTask first = tasks.get(0);
        List<ProcessTaskDTO> taskDTOs = tasks.stream().map(this::toDTO).collect(Collectors.toList());

        // Calculate overall progress
        BigDecimal totalPlanned = tasks.stream()
                .map(ProcessTask::getPlannedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCompleted = tasks.stream()
                .map(ProcessTask::getCompletedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overallProgress = totalPlanned.compareTo(BigDecimal.ZERO) > 0
                ? totalCompleted.multiply(BigDecimal.valueOf(100)).divide(totalPlanned, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        int completedCount = (int) tasks.stream()
                .filter(t -> t.getStatus() == ProcessTaskStatus.COMPLETED || t.getStatus() == ProcessTaskStatus.CLOSED)
                .count();

        // P2-3: Populate productName from first task's productTypeId
        String runProductName = null;
        if (first.getProductTypeId() != null) {
            runProductName = ptCache.computeIfAbsent(first.getProductTypeId(),
                    id -> productTypeRepository.findById(id).orElse(null)) != null
                    ? ptCache.get(first.getProductTypeId()).getName() : null;
        }

        return ProcessTaskDTO.RunOverview.builder()
                .productionRunId(productionRunId)
                .productName(runProductName)
                .sourceCustomerName(first.getSourceCustomerName())
                .tasks(taskDTOs)
                .totalTasks(tasks.size())
                .completedTasks(completedCount)
                .overallProgress(overallProgress)
                .build();
    }

    private void validateStatusTransition(ProcessTaskStatus current, ProcessTaskStatus target, ProcessTask entity) {
        switch (current) {
            case PENDING:
                if (target != ProcessTaskStatus.IN_PROGRESS && target != ProcessTaskStatus.CLOSED) {
                    throw new BusinessException(409, "待开始的任务只能转为进行中或关闭")
                            .withHint("请刷新任务列表查看最新状态");
                }
                break;
            case IN_PROGRESS:
                if (target != ProcessTaskStatus.COMPLETED && target != ProcessTaskStatus.CLOSED) {
                    throw new BusinessException(409, "进行中的任务只能转为已完成或已关闭")
                            .withHint("请刷新任务列表查看最新状态");
                }
                break;
            case COMPLETED:
            case CLOSED:
                if (target != ProcessTaskStatus.SUPPLEMENTING) {
                    throw new BusinessException(409, "已完成/已关闭的任务只能转为补报中")
                            .withHint("请刷新任务列表查看最新状态");
                }
                entity.setPreviousTerminalStatus(current.name());
                break;
            case SUPPLEMENTING:
                // SUPPLEMENTING exits automatically when all supplements are approved
                throw new BusinessException(409, "补报中的任务状态由系统自动管理")
                        .withHint("请等待补报流程完成");
        }
    }

    @Override
    @Transactional
    public List<ProcessTaskDTO> generateFromProduct(String factoryId, String productTypeId,
                                                      Map<String, BigDecimal> plannedQuantities,
                                                      String sourceCustomerName, Long createdBy) {
        log.info("Generating process tasks from product {} for factory {}", productTypeId, factoryId);

        // Get ordered work processes for this product
        List<ProductWorkProcess> associations = productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, productTypeId);

        if (associations.isEmpty()) {
            throw new BusinessException(404, "该产品未关联任何工序，请先在产品-工序管理中配置")
                    .withHint("请前往「系统管理 → 产品-工序配置」配置工序关联");
        }

        // Auto-fill customer name from ProductType if not provided
        if (sourceCustomerName == null || sourceCustomerName.isBlank()) {
            ProductType pt = productTypeRepository.findById(productTypeId).orElse(null);
            if (pt != null && pt.getRelatedCustomer() != null) {
                sourceCustomerName = pt.getRelatedCustomer();
            }
        }

        // Shared productionRunId for all tasks in this batch
        String productionRunId = UUID.randomUUID().toString();

        // Auto-bind workflow version
        Integer workflowVersionId = stateMachineRepository
                .findByFactoryIdAndEntityTypeAndPublishStatus(factoryId, "PRODUCTION_WORKFLOW", "published")
                .map(StateMachine::getVersion)
                .orElse(null);

        List<ProcessTask> tasks = new java.util.ArrayList<>();
        for (ProductWorkProcess assoc : associations) {
            WorkProcess wp = workProcessRepository.findById(assoc.getWorkProcessId()).orElse(null);
            String unit = wp != null ? wp.getUnit() : "kg";

            // Get planned quantity: from map if provided, otherwise default 100
            BigDecimal plannedQty = BigDecimal.ZERO;
            if (plannedQuantities != null && !plannedQuantities.isEmpty()) {
                plannedQty = plannedQuantities.getOrDefault(assoc.getWorkProcessId(), BigDecimal.ZERO);
                if (plannedQty.compareTo(BigDecimal.ZERO) <= 0) {
                    log.info("Skipping work process {} — planned quantity is zero", assoc.getWorkProcessId());
                    continue;
                }
            } else {
                plannedQty = new BigDecimal("100");
            }

            ProcessTask task = ProcessTask.builder()
                    .id(UUID.randomUUID().toString())
                    .factoryId(factoryId)
                    .productionRunId(productionRunId)
                    .productTypeId(productTypeId)
                    .workProcessId(assoc.getWorkProcessId())
                    .sourceCustomerName(sourceCustomerName)
                    .sourceDocType("PLAN")
                    .workflowVersionId(workflowVersionId)
                    .plannedQuantity(plannedQty)
                    .completedQuantity(BigDecimal.ZERO)
                    .pendingQuantity(BigDecimal.ZERO)
                    .unit(unit)
                    .status(ProcessTaskStatus.PENDING)
                    .createdBy(createdBy)
                    .build();

            tasks.add(task);
        }

        List<ProcessTask> saved = repository.saveAll(tasks);
        log.info("Generated {} process tasks for product {} (runId={})", saved.size(), productTypeId, productionRunId);

        return saved.stream().map(this::toDTO).collect(Collectors.toList());
    }

    // P2-1: Cache for N+1 query fix — avoid repeated DB lookups for same workProcessId/productTypeId
    private final java.util.concurrent.ConcurrentHashMap<String, WorkProcess> wpCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, ProductType> ptCache = new java.util.concurrent.ConcurrentHashMap<>();

    private ProcessTaskDTO toDTO(ProcessTask entity) {
        BigDecimal estimated = entity.getCompletedQuantity().add(entity.getPendingQuantity());
        BigDecimal confirmed = entity.getCompletedQuantity();
        boolean targetReached = entity.getPlannedQuantity() != null
                && confirmed.compareTo(entity.getPlannedQuantity()) >= 0;

        // Enrich with names — uses session-level cache to avoid N+1 queries
        String processName = null;
        String processCategory = null;
        String productName = null;
        try {
            if (entity.getWorkProcessId() != null) {
                WorkProcess wp = wpCache.computeIfAbsent(entity.getWorkProcessId(),
                        id -> workProcessRepository.findById(id).orElse(null));
                if (wp != null) {
                    processName = wp.getProcessName();
                    processCategory = wp.getProcessCategory();
                }
            }
            if (entity.getProductTypeId() != null) {
                ProductType pt = ptCache.computeIfAbsent(entity.getProductTypeId(),
                        id -> productTypeRepository.findById(id).orElse(null));
                if (pt != null) {
                    productName = pt.getName();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enrich names for task {}: {}", entity.getId(), e.getMessage());
        }

        // R8: resolve batchId first, then look up WPT (single call, avoids redundant parse)
        Long batchId = resolveBatchId(entity);
        WorkProcessTask wpt = resolveWorkProcessTask(entity, batchId);
        Long workProcessTaskId = wpt != null ? wpt.getId() : null;
        Integer processOrder = wpt != null ? wpt.getProcessOrder() : null;

        // R8: resolve batchNumber from ProductionBatch
        String batchNumber = resolveBatchNumber(entity, batchId);

        return ProcessTaskDTO.builder()
                .id(entity.getId())
                .factoryId(entity.getFactoryId())
                .productionRunId(entity.getProductionRunId())
                .productTypeId(entity.getProductTypeId())
                .workProcessId(entity.getWorkProcessId())
                .processName(processName)
                .processCategory(processCategory)
                .productName(productName)
                .sourceCustomerName(entity.getSourceCustomerName())
                .sourceDocType(entity.getSourceDocType())
                .sourceDocId(entity.getSourceDocId())
                .workflowVersionId(entity.getWorkflowVersionId())
                .batchId(batchId)
                .batchNumber(batchNumber)
                .workProcessTaskId(workProcessTaskId)
                .processOrder(processOrder)
                .plannedQuantity(entity.getPlannedQuantity())
                .completedQuantity(entity.getCompletedQuantity())
                .pendingQuantity(entity.getPendingQuantity())
                .inputQuantity(entity.getInputQuantity())
                .unit(entity.getUnit())
                .inputUnit(entity.getUnit())
                .outputUnit(entity.getUnit())
                .startDate(entity.getStartDate())
                .expectedEndDate(entity.getExpectedEndDate())
                .status(entity.getStatus().name())
                .previousTerminalStatus(entity.getPreviousTerminalStatus())
                .createdBy(entity.getCreatedBy())
                .notes(entity.getNotes())
                .estimatedProgress(estimated)
                .confirmedProgress(confirmed)
                .targetReached(targetReached)
                .overdue(entity.getExpectedEndDate() != null
                        && entity.getExpectedEndDate().isBefore(java.time.LocalDate.now())
                        && entity.getStatus() != ProcessTaskStatus.COMPLETED
                        && entity.getStatus() != ProcessTaskStatus.CLOSED)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private Long resolveBatchId(ProcessTask entity) {
        if (!"BATCH".equals(entity.getSourceDocType()) || entity.getSourceDocId() == null) {
            return null;
        }
        try {
            return Long.valueOf(entity.getSourceDocId());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * R8 双栈合并: 解析 ProcessTask 对应的 WorkProcessTask.
     *
     * <p>支持两种关联路径:
     * <ol>
     *   <li><b>WPT- 前缀路径</b>: ProcessTask.id = "WPT-{wptId}", 直接从 id 解析.
     *       这是 {@code mirrorWorkProcessTasksForRn} 创建的镜像任务的标准路径.</li>
     *   <li><b>BATCH- 路径 (fallback)</b>: ProcessTask.productionRunId = "BATCH-{batchId}",
     *       通过 (factoryId, productionBatchId, workProcessId) 三键关联查找.
     *       用于非标准创建路径下但确实有对应 WPT 的任务.</li>
     * </ol>
     *
     * @param entity  ProcessTask 实体
     * @param batchId 已解析的 batchId (null = 不走 BATCH- 路径)
     * @return 对应的 WorkProcessTask, 未找到时返回 null (不抛异常)
     */
    private WorkProcessTask resolveWorkProcessTask(ProcessTask entity, Long batchId) {
        String id = entity.getId();
        // Path 1: WPT-{id} prefix (standard mirror path)
        if (id != null && id.startsWith("WPT-")) {
            try {
                Long wptId = Long.valueOf(id.substring(4));
                return workProcessTaskRepository.findByFactoryIdAndId(entity.getFactoryId(), wptId)
                        .orElse(null);
            } catch (NumberFormatException ignored) {
                // fall through to Path 2
            }
        }
        // Path 2: BATCH- productionRunId + workProcessId (fallback for non-standard tasks)
        if (batchId != null && entity.getWorkProcessId() != null) {
            return workProcessTaskRepository
                    .findByFactoryIdAndProductionBatchIdAndWorkProcessId(
                            entity.getFactoryId(), batchId, entity.getWorkProcessId())
                    .orElse(null);
        }
        return null;
    }

    /**
     * R8 双栈合并: 解析批次号 (batchNumber) 用于 RN 导航到 YieldStepReportScreen.
     *
     * <p>只在 batchId 已解析时查询, 否则返回 null (快速路径).
     * fail-soft: 查询失败不影响主流程.
     */
    private String resolveBatchNumber(ProcessTask entity, Long batchId) {
        if (batchId == null) {
            return null;
        }
        try {
            return productionBatchRepository.findById(batchId)
                    .map(b -> b.getBatchNumber())
                    .orElse(null);
        } catch (Exception e) {
            log.warn("R8: resolveBatchNumber failed for task {}, batchId={}: {}",
                    entity.getId(), batchId, e.getMessage());
            return null;
        }
    }
}
