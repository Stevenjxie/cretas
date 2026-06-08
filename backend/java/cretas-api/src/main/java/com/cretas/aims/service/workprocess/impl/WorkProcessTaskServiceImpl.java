package com.cretas.aims.service.workprocess.impl;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.entity.workprocess.WorkProcessTask.Status;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工序任务 Service 实现 (Track D2 — M-WP-1/2).
 *
 * <p>状态机由 {@link #assertTransition(Status, Status)} 集中校验, 非法转换抛
 * BusinessException 携带 actionHint, 前端 Alert 提示。
 */
@Service
@RequiredArgsConstructor
public class WorkProcessTaskServiceImpl implements WorkProcessTaskService {

    private static final Logger log = LoggerFactory.getLogger(WorkProcessTaskServiceImpl.class);

    private final WorkProcessTaskRepository taskRepository;
    private final ProductWorkProcessRepository productWorkProcessRepository;
    private final WorkProcessRepository workProcessRepository;
    private final UserRepository userRepository;

    // ==================== T142: batch assignedToName helper ====================

    /**
     * 批量加载 assignedTo user IDs → 姓名映射 (单次查询, 无 N+1).
     * 镜像 T135 ProductWorkProcessServiceImpl.listByProduct 中的 workerNameMap 模式。
     * fullName 优先, 无 fullName 时 fallback 到 username.
     */
    private Map<Long, String> loadAssigneeNames(List<WorkProcessTask> tasks) {
        Set<Long> ids = tasks.stream()
                .map(WorkProcessTask::getAssignedTo)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return userRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(
                        User::getId,
                        u -> u.getFullName() != null && !u.getFullName().isBlank()
                                ? u.getFullName() : u.getUsername()
                ));
    }

    @Override
    @Transactional
    public List<WorkProcessTaskDTO> spawnTasks(
            String factoryId,
            Long productionBatchId,
            String productTypeId) {

        if (productionBatchId == null) {
            throw new BusinessException(400, "productionBatchId 不能为空");
        }
        if (productTypeId == null || productTypeId.isBlank()) {
            throw new BusinessException(400, "productTypeId 不能为空")
                    .withHint("生产批次必须先绑定产品类型才能 spawn 工序任务");
        }

        if (taskRepository.existsByFactoryIdAndProductionBatchId(factoryId, productionBatchId)) {
            log.info("批次 {} 工序任务已 spawn, 跳过", productionBatchId);
            return listByBatch(factoryId, productionBatchId);
        }

        List<ProductWorkProcess> templates = productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, productTypeId);

        if (templates.isEmpty()) {
            throw new BusinessException(
                    422,
                    "产品 " + productTypeId + " 未配置任何工序")
                    .withHint("请先到'产品工序配置'页给该产品绑定工序")
                    .withHintTarget("产品工序配置");
        }

        // 一次性查所有 WorkProcess 定义, 用作 unit 默认值 fallback
        List<String> processIds = templates.stream()
                .map(ProductWorkProcess::getWorkProcessId)
                .distinct()
                .collect(Collectors.toList());
        Map<String, WorkProcess> definitions = workProcessRepository
                .findByFactoryIdAndIdIn(factoryId, processIds).stream()
                .collect(Collectors.toMap(WorkProcess::getId, wp -> wp));

        LocalDateTime now = LocalDateTime.now();
        List<WorkProcessTask> spawned = templates.stream()
                .filter(t -> Boolean.TRUE.equals(t.getIsActive()))
                .map(template -> {
                    WorkProcess def = definitions.get(template.getWorkProcessId());
                    String unit = template.getUnitOverride() != null
                            ? template.getUnitOverride()
                            : (def != null ? def.getUnit() : null);
                    Integer estMinutes = template.getEstimatedMinutesOverride() != null
                            ? template.getEstimatedMinutesOverride()
                            : (def != null ? def.getEstimatedMinutes() : null);

                    return WorkProcessTask.builder()
                            .factoryId(factoryId)
                            .productionBatchId(productionBatchId)
                            .productWorkProcessId(template.getId())
                            .workProcessId(template.getWorkProcessId())
                            .productTypeId(productTypeId)
                            .processOrder(template.getProcessOrder() != null ? template.getProcessOrder() : 0)
                            .status(Status.PENDING)
                            .plannedUnit(unit)
                            .estimatedMinutes(estMinutes)
                            .assignedTo(template.getResponsibleWorkerId())
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
                })
                .collect(Collectors.toList());

        if (spawned.isEmpty()) {
            throw new BusinessException(
                    422,
                    "产品 " + productTypeId + " 工序配置全部禁用, 无可 spawn 任务")
                    .withHint("请到'产品工序配置'启用至少一道工序");
        }

        List<WorkProcessTask> saved = taskRepository.saveAll(spawned);
        log.info("批次 {} 已 spawn {} 道工序任务 (productType={})",
                productionBatchId, saved.size(), productTypeId);

        // T142: batch-load assignee names (no N+1); null-safe lookup (assignedTo may be null)
        Map<Long, String> nameMap = loadAssigneeNames(saved);
        return saved.stream()
                .map(t -> toDTO(t, definitions.get(t.getWorkProcessId()),
                        t.getAssignedTo() != null ? nameMap.get(t.getAssignedTo()) : null))
                .collect(Collectors.toList());
    }

    @Override
    public PageResponse<WorkProcessTaskDTO> list(
            String factoryId,
            Status status,
            Long productionBatchId,
            Long assignedTo,
            Pageable pageable) {

        Page<WorkProcessTask> page = taskRepository.findByFilters(
                factoryId, status, productionBatchId, assignedTo, pageable);
        Map<String, WorkProcess> defs = loadDefinitionsForTasks(factoryId, page.getContent());
        // T142: batch-load assignee names (no N+1); null-safe lookup (assignedTo may be null)
        Map<Long, String> nameMap = loadAssigneeNames(page.getContent());

        List<WorkProcessTaskDTO> content = page.getContent().stream()
                .map(t -> toDTO(t, defs.get(t.getWorkProcessId()),
                        t.getAssignedTo() != null ? nameMap.get(t.getAssignedTo()) : null))
                .collect(Collectors.toList());
        return PageResponse.of(content, page.getNumber() + 1, page.getSize(), page.getTotalElements());
    }

    @Override
    public List<WorkProcessTaskDTO> listByBatch(String factoryId, Long productionBatchId, Long assignedTo) {
        List<WorkProcessTask> tasks = taskRepository
                .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(factoryId, productionBatchId);

        // M1 兜底: 该批工序全未指派 (assigned_to 全 null) → 不过滤, 返回全部
        // 防止未配默认责任人的老批次把任何人锁死
        boolean anyAssigned = tasks.stream().anyMatch(t -> t.getAssignedTo() != null);
        if (assignedTo != null && anyAssigned) {
            tasks = tasks.stream()
                    .filter(t -> t.getAssignedTo() == null || t.getAssignedTo().equals(assignedTo))
                    .collect(Collectors.toList());
        }
        // assignedTo == null → 主管视图, 不过滤 (返回全部)

        Map<String, WorkProcess> defs = loadDefinitionsForTasks(factoryId, tasks);
        // T142: batch-load assignee names (no N+1); null-safe lookup (assignedTo may be null)
        Map<Long, String> nameMap = loadAssigneeNames(tasks);
        return tasks.stream()
                .map(t -> toDTO(t, defs.get(t.getWorkProcessId()),
                        t.getAssignedTo() != null ? nameMap.get(t.getAssignedTo()) : null))
                .collect(Collectors.toList());
    }

    @Override
    public WorkProcessTaskDTO getById(String factoryId, Long id) {
        WorkProcessTask task = taskRepository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkProcessTask", "id", String.valueOf(id)));
        WorkProcess def = workProcessRepository
                .findByFactoryIdAndId(factoryId, task.getWorkProcessId())
                .orElse(null);
        return toDTO(task, def);
    }

    @Override
    @Transactional
    public WorkProcessTaskDTO start(String factoryId, Long id, Long operatorUserId) {
        WorkProcessTask task = loadOrThrow(factoryId, id);
        assertTransition(task.getStatus(), Status.IN_PROGRESS);

        task.setStatus(Status.IN_PROGRESS);
        task.setActualStartAt(LocalDateTime.now());
        if (task.getAssignedTo() == null && operatorUserId != null) {
            task.setAssignedTo(operatorUserId);
        }
        WorkProcessTask saved = taskRepository.save(task);
        log.info("工序任务 {} 开始 (operator={})", id, operatorUserId);
        return toDTO(saved, lookupDefinition(factoryId, saved.getWorkProcessId()));
    }

    @Override
    @Transactional
    public WorkProcessTaskDTO complete(
            String factoryId,
            Long id,
            Long operatorUserId,
            WorkProcessTaskDTO.CompleteRequest request) {

        if (request == null || request.getActualQuantity() == null) {
            throw new BusinessException(400, "actualQuantity 不能为空")
                    .withHint("请输入实际产量");
        }

        WorkProcessTask task = loadOrThrow(factoryId, id);
        assertTransition(task.getStatus(), Status.COMPLETED);

        LocalDateTime now = LocalDateTime.now();
        task.setStatus(Status.COMPLETED);
        task.setActualQuantity(request.getActualQuantity());
        task.setActualEndAt(now);
        task.setCompletedAt(now);
        task.setCompletedBy(operatorUserId);
        if (request.getNotes() != null) {
            task.setNotes(request.getNotes());
        }
        if (task.getActualStartAt() != null) {
            long minutes = Duration.between(task.getActualStartAt(), now).toMinutes();
            task.setActualMinutes((int) Math.max(0, minutes));
        }

        WorkProcessTask saved = taskRepository.save(task);
        log.info("工序任务 {} 完成 actualQuantity={} actualMinutes={}",
                id, request.getActualQuantity(), saved.getActualMinutes());
        return toDTO(saved, lookupDefinition(factoryId, saved.getWorkProcessId()));
    }

    @Override
    @Transactional
    public WorkProcessTaskDTO skip(
            String factoryId,
            Long id,
            Long operatorUserId,
            WorkProcessTaskDTO.SkipRequest request) {

        if (request == null || request.getNotes() == null || request.getNotes().isBlank()) {
            throw new BusinessException(400, "跳过工序必须填写原因")
                    .withHint("跳过工序需主管审批, 请填写原因 (notes)");
        }
        WorkProcessTask task = loadOrThrow(factoryId, id);
        assertTransition(task.getStatus(), Status.SKIPPED);

        task.setStatus(Status.SKIPPED);
        task.setNotes(request.getNotes());
        task.setCompletedBy(operatorUserId);
        task.setCompletedAt(LocalDateTime.now());

        WorkProcessTask saved = taskRepository.save(task);
        log.info("工序任务 {} 跳过 (operator={}, 原因={})", id, operatorUserId, request.getNotes());
        return toDTO(saved, lookupDefinition(factoryId, saved.getWorkProcessId()));
    }

    @Override
    @Transactional
    public WorkProcessTaskDTO updatePlan(
            String factoryId,
            Long id,
            WorkProcessTaskDTO.UpdatePlanRequest request) {

        if (request == null) {
            throw new BusinessException(400, "请求体不能为空");
        }
        WorkProcessTask task = loadOrThrow(factoryId, id);
        if (task.getStatus().isTerminal()) {
            throw new BusinessException(
                    409,
                    "工序任务已处于终态 (" + task.getStatus() + "), 不可修改计划")
                    .withHint("已完成 / 跳过 / 取消的任务无法再调整计划");
        }

        if (request.getPlannedStartAt() != null) task.setPlannedStartAt(request.getPlannedStartAt());
        if (request.getPlannedEndAt() != null) task.setPlannedEndAt(request.getPlannedEndAt());
        if (request.getPlannedQuantity() != null) task.setPlannedQuantity(request.getPlannedQuantity());
        if (request.getPlannedUnit() != null) task.setPlannedUnit(request.getPlannedUnit());
        if (request.getEstimatedMinutes() != null) task.setEstimatedMinutes(request.getEstimatedMinutes());
        if (request.getAssignedTo() != null) task.setAssignedTo(request.getAssignedTo());
        if (request.getNotes() != null) task.setNotes(request.getNotes());

        WorkProcessTask saved = taskRepository.save(task);
        return toDTO(saved, lookupDefinition(factoryId, saved.getWorkProcessId()));
    }

    @Override
    @Transactional
    public void delete(String factoryId, Long id) {
        WorkProcessTask task = loadOrThrow(factoryId, id);
        taskRepository.delete(task); // BaseEntity @SQLDelete → 软删
        log.info("工序任务 {} 软删", id);
    }

    // ==================== Helpers ====================

    private WorkProcessTask loadOrThrow(String factoryId, Long id) {
        return taskRepository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "WorkProcessTask", "id", String.valueOf(id)));
    }

    private WorkProcess lookupDefinition(String factoryId, String workProcessId) {
        return workProcessRepository.findByFactoryIdAndId(factoryId, workProcessId).orElse(null);
    }

    private Map<String, WorkProcess> loadDefinitionsForTasks(
            String factoryId, List<WorkProcessTask> tasks) {
        if (tasks.isEmpty()) return new HashMap<>();
        List<String> ids = tasks.stream()
                .map(WorkProcessTask::getWorkProcessId)
                .distinct()
                .collect(Collectors.toList());
        return workProcessRepository.findByFactoryIdAndIdIn(factoryId, ids).stream()
                .collect(Collectors.toMap(WorkProcess::getId, wp -> wp));
    }

    /**
     * 状态机校验. 非法转换抛 BusinessException + actionHint.
     */
    private void assertTransition(Status from, Status to) {
        boolean ok = switch (to) {
            case IN_PROGRESS -> from == Status.PENDING;
            case COMPLETED -> from == Status.IN_PROGRESS;
            case SKIPPED -> from == Status.PENDING || from == Status.IN_PROGRESS;
            case CANCELLED -> from == Status.PENDING || from == Status.IN_PROGRESS;
            case PENDING -> false; // 不可回退到初始态
        };
        if (!ok) {
            throw new BusinessException(
                    409,
                    "工序任务状态 " + from + " 不可转换为 " + to)
                    .withHint(actionHintFor(from, to));
        }
    }

    private String actionHintFor(Status from, Status to) {
        if (to == Status.IN_PROGRESS && from != Status.PENDING) {
            return from.isTerminal()
                    ? "任务已处于终态 (" + from + "), 不可重新开始"
                    : "请先确认上一道工序状态, 当前为 " + from;
        }
        if (to == Status.COMPLETED && from != Status.IN_PROGRESS) {
            return from == Status.PENDING ? "请先点击'开始'再完成" : "任务已处于 " + from + ", 不可完成";
        }
        return "非法状态转换 " + from + " → " + to;
    }

    /** Single-task overload used by mutating endpoints (start/complete/skip/updatePlan/getById). */
    private WorkProcessTaskDTO toDTO(WorkProcessTask task, WorkProcess definition) {
        // T142: single-task path → look up user name in isolation (1 user query max, never N+1 on lists).
        String assignedToName = null;
        if (task.getAssignedTo() != null) {
            assignedToName = userRepository.findByIdIn(List.of(task.getAssignedTo()))
                    .stream()
                    .findFirst()
                    .map(u -> u.getFullName() != null && !u.getFullName().isBlank()
                            ? u.getFullName() : u.getUsername())
                    .orElse(null);
        }
        return toDTO(task, definition, assignedToName);
    }

    /**
     * Core toDTO — used by list paths (passes pre-loaded nameMap to avoid N+1).
     *
     * @param assignedToName pre-resolved name (null = not assigned or not found)
     */
    private WorkProcessTaskDTO toDTO(WorkProcessTask task, WorkProcess definition, String assignedToName) {
        WorkProcessTaskDTO dto = WorkProcessTaskDTO.builder()
                .id(task.getId())
                .factoryId(task.getFactoryId())
                .productionBatchId(task.getProductionBatchId())
                .productWorkProcessId(task.getProductWorkProcessId())
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
                .assignedToName(assignedToName)  // T142: enriched name
                .completedBy(task.getCompletedBy())
                .completedAt(task.getCompletedAt())
                .notes(task.getNotes())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
        if (definition != null) {
            dto.setProcessName(definition.getProcessName());
            dto.setProcessCategory(definition.getProcessCategory());
            dto.setStandardYieldMin(definition.getStandardYieldMin());
            dto.setStandardYieldMax(definition.getStandardYieldMax());
            dto.setOutputUnit(definition.getOutputUnit());  // P0-2: 末道折份/盒
            dto.setExpectedByproducts(definition.getExpectedByproducts());  // 防呆 Rule 3: OUTPUT 阶段预填
        }
        return dto;
    }
}
