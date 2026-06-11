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
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
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
    private final ProductionBatchRepository productionBatchRepository;
    private final ProductTypeRepository productTypeRepository;

    /**
     * Fable 审计修复 (2026-06-11 — 问题2): retry spawn 路径 (HTTP / AI) 需读计划的 skipProcessReporting,
     * 才能与计划模式一致地 spawn (两点 or 逐道)。field 注入 (required=false) 避免改动构造器与既有单测 @InjectMocks 装配;
     * 不注入 / 查不到计划 → 兜底 false (逐道, 安全默认)。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.ProductionPlanRepository productionPlanRepository;

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

    // ==================== T157: batch batchNumber / productTypeName helpers ====================

    /**
     * 批量加载 productionBatchId → batchNumber 映射 (单次查询, 无 N+1).
     * 镜像 loadAssigneeNames 的 batch-resolve 模式。null/缺失批次不进 map → toDTO 取 null (禁假数据).
     */
    private Map<Long, String> loadBatchNumbers(List<WorkProcessTask> tasks) {
        Set<Long> ids = tasks.stream()
                .map(WorkProcessTask::getProductionBatchId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return productionBatchRepository.findByIdIn(ids).stream()
                .filter(b -> b.getBatchNumber() != null)
                .collect(Collectors.toMap(
                        com.cretas.aims.entity.ProductionBatch::getId,
                        com.cretas.aims.entity.ProductionBatch::getBatchNumber
                ));
    }

    /**
     * 批量加载 productTypeId → productTypeName 映射 (单次查询, 无 N+1).
     * null/缺失产品不进 map → toDTO 取 null (禁假数据).
     */
    private Map<String, String> loadProductTypeNames(List<WorkProcessTask> tasks) {
        Set<String> ids = tasks.stream()
                .map(WorkProcessTask::getProductTypeId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return productTypeRepository.findByIdIn(ids).stream()
                .filter(p -> p.getName() != null)
                .collect(Collectors.toMap(
                        com.cretas.aims.entity.ProductType::getId,
                        com.cretas.aims.entity.ProductType::getName
                ));
    }

    @Override
    @Transactional
    public List<WorkProcessTaskDTO> spawnTasks(
            String factoryId,
            Long productionBatchId,
            String productTypeId) {
        // 向后兼容: 旧 3-arg 入口委托新方法, skip=false (逐道). 现有 controller / Tool / 计划转批次
        // 调用方在未感知免工序报工时走原逐道路径, 行为完全不变。
        return spawnTasks(factoryId, productionBatchId, productTypeId, Boolean.FALSE, null, null);
    }

    @Override
    @Transactional
    public List<WorkProcessTaskDTO> spawnTasksForBatch(
            String factoryId,
            Long productionBatchId,
            String productTypeId) {
        // Fable 审计修复 (问题2): retry spawn 路径尊重计划模式。
        // 从批次解析其生产计划, 读 skipProcessReporting + 头尾责任人 (= assignedSupervisorId, 一人兼,
        // 与 createBatchFromPlan 主路径一致)。解析不到 → 兜底逐道 (false, 安全默认)。
        Boolean skip = Boolean.FALSE;
        Long responsibleId = null;
        try {
            if (productionBatchId != null && productionPlanRepository != null) {
                com.cretas.aims.entity.ProductionBatch batch =
                        productionBatchRepository.findById(productionBatchId).orElse(null);
                if (batch != null && batch.getProductionPlanId() != null
                        && !batch.getProductionPlanId().isBlank()) {
                    com.cretas.aims.entity.ProductionPlan plan =
                            productionPlanRepository.findById(batch.getProductionPlanId()).orElse(null);
                    if (plan != null) {
                        skip = Boolean.TRUE.equals(plan.getSkipProcessReporting());
                        responsibleId = plan.getAssignedSupervisorId();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("retry spawn 解析计划模式失败, 兜底逐道 (false): batchId={}, err={}",
                    productionBatchId, e.getMessage());
            skip = Boolean.FALSE;
            responsibleId = null;
        }
        log.info("retry spawn (计划模式感知): batchId={}, productTypeId={}, skipProcessReporting={}",
                productionBatchId, productTypeId, skip);
        return spawnTasks(factoryId, productionBatchId, productTypeId, skip, responsibleId, responsibleId);
    }

    @Override
    @Transactional
    public List<WorkProcessTaskDTO> spawnTasks(
            String factoryId,
            Long productionBatchId,
            String productTypeId,
            Boolean skipProcessReporting,
            Long materialResponsibleId,
            Long outputResponsibleId) {

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

        // 计划级免工序报工模式判定 (六扇门 Wave2 升级, V20261017_01):
        //   skip=true 显式选择 OR 产品未配任何工序 (工序 optional) → 走批次级两点报工 spawn。
        //   这两种情形都不再 422 阻塞 (旧逐道路径才在 0 工序时报 422)。
        boolean skip = Boolean.TRUE.equals(skipProcessReporting);
        if (skip || templates.isEmpty()) {
            return spawnBatchLevelTwoPointTasks(
                    factoryId, productionBatchId, productTypeId,
                    materialResponsibleId, outputResponsibleId, templates.isEmpty());
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
                // Wave2 可配置报工粒度: 跳过免报工序 (reportingRequired=false), 不生成报工任务。
                // null 视为 true (向后兼容: 老配置行无此字段时仍逐道报)。
                .filter(t -> !Boolean.FALSE.equals(t.getReportingRequired()))
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
                    "产品 " + productTypeId + " 工序配置全部禁用或全部免报, 无可 spawn 任务")
                    .withHint("请到'产品工序配置'启用并标记至少一道工序需报工 (建议至少保留领料 + 产出两道)");
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

    /**
     * 批次级两点报工 spawn (免工序报工模式, 六扇门 Wave2 升级 V20261017_01).
     *
     * <p>生成恰好 2 个批次级哨兵任务 (无 WorkProcess 定义, 无 PWP 模板):
     * <ol>
     *   <li>领料报工 task: work_process_id={@link WorkProcessTaskService#SENTINEL_MATERIAL_INPUT},
     *       process_order=0, assigned_to=materialResponsibleId。供操作员 INPUT 报工 (领料量+投入照)。</li>
     *   <li>产出报工 task: work_process_id={@link WorkProcessTaskService#SENTINEL_FINAL_OUTPUT},
     *       process_order={@link WorkProcessTaskService#SENTINEL_OUTPUT_ORDER} (末位),
     *       assigned_to=outputResponsibleId。供 OUTPUT 报工 (产出量+副产品+成品照)。</li>
     * </ol>
     *
     * <p>两点出成率/成本由现有 report-driven 链算 (calculateBatchYield):
     * cumulative=lastOutput(产出)/firstInput(领料); 人工不报 → laborCost null (诚实, 登下一期)。
     *
     * <p>product_work_process_id 用哨兵 {@link WorkProcessTaskService#SENTINEL_PWP_ID} (列 NOT NULL);
     * submitReport 的 T121 归属鉴权对哨兵 PWP 查 join 表返空 → 回退 assigned_to 校验 (安全)。
     *
     * @param zeroProcessFallback true=因产品 0 工序强制走两点 (日志区分); false=显式 skip 选择
     */
    private List<WorkProcessTaskDTO> spawnBatchLevelTwoPointTasks(
            String factoryId,
            Long productionBatchId,
            String productTypeId,
            Long materialResponsibleId,
            Long outputResponsibleId,
            boolean zeroProcessFallback) {

        LocalDateTime now = LocalDateTime.now();

        WorkProcessTask materialTask = WorkProcessTask.builder()
                .factoryId(factoryId)
                .productionBatchId(productionBatchId)
                .productWorkProcessId(WorkProcessTaskService.SENTINEL_PWP_ID)
                .workProcessId(WorkProcessTaskService.SENTINEL_MATERIAL_INPUT)
                .productTypeId(productTypeId)
                .processOrder(0)
                .status(Status.PENDING)
                .plannedUnit("kg")          // 领料量默认 kg (原料称重口径); OUTPUT 单位报工时自定
                .assignedTo(materialResponsibleId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        WorkProcessTask outputTask = WorkProcessTask.builder()
                .factoryId(factoryId)
                .productionBatchId(productionBatchId)
                .productWorkProcessId(WorkProcessTaskService.SENTINEL_PWP_ID)
                .workProcessId(WorkProcessTaskService.SENTINEL_FINAL_OUTPUT)
                .productTypeId(productTypeId)
                .processOrder(WorkProcessTaskService.SENTINEL_OUTPUT_ORDER)
                .status(Status.PENDING)
                .assignedTo(outputResponsibleId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        List<WorkProcessTask> saved = taskRepository.saveAll(List.of(materialTask, outputTask));
        log.info("批次 {} 免工序报工 spawn 2 批次级任务 (领料+产出, productType={}, zeroProcessFallback={})",
                productionBatchId, productTypeId, zeroProcessFallback);

        // 哨兵任务无 WorkProcess 定义 → toDTO definition=null; 核心 toDTO 已注入友好 processName (领料/产出报工)。
        Map<Long, String> nameMap = loadAssigneeNames(saved);
        return saved.stream()
                .map(t -> toDTO(t, null,
                        t.getAssignedTo() != null ? nameMap.get(t.getAssignedTo()) : null))
                .collect(Collectors.toList());
    }

    /** 哨兵 work_process_id → 友好报工名 (RN/web 展示); 非哨兵返 null。 */
    private String sentinelProcessName(String workProcessId) {
        if (WorkProcessTaskService.SENTINEL_MATERIAL_INPUT.equals(workProcessId)) return "领料报工";
        if (WorkProcessTaskService.SENTINEL_FINAL_OUTPUT.equals(workProcessId)) return "产出报工";
        return null;
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
        // T157: batch-load batchNumber / productTypeName (no N+1); null when not resolvable
        Map<Long, String> batchNumberMap = loadBatchNumbers(page.getContent());
        Map<String, String> productNameMap = loadProductTypeNames(page.getContent());

        List<WorkProcessTaskDTO> content = page.getContent().stream()
                .map(t -> toDTO(t, defs.get(t.getWorkProcessId()),
                        t.getAssignedTo() != null ? nameMap.get(t.getAssignedTo()) : null,
                        batchNumberMap, productNameMap))
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
        // T157: batch-load batchNumber / productTypeName (no N+1); null when not resolvable
        Map<Long, String> batchNumberMap = loadBatchNumbers(tasks);
        Map<String, String> productNameMap = loadProductTypeNames(tasks);
        return tasks.stream()
                .map(t -> toDTO(t, defs.get(t.getWorkProcessId()),
                        t.getAssignedTo() != null ? nameMap.get(t.getAssignedTo()) : null,
                        batchNumberMap, productNameMap))
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
     * T157 list-path overload — enriches batchNumber + productTypeName from pre-loaded maps (no N+1).
     * null when batch/product not resolvable (禁假数据).
     */
    private WorkProcessTaskDTO toDTO(WorkProcessTask task, WorkProcess definition, String assignedToName,
            Map<Long, String> batchNumberMap, Map<String, String> productNameMap) {
        WorkProcessTaskDTO dto = toDTO(task, definition, assignedToName);
        if (task.getProductionBatchId() != null && batchNumberMap != null) {
            dto.setBatchNumber(batchNumberMap.get(task.getProductionBatchId()));
        }
        if (task.getProductTypeId() != null && productNameMap != null) {
            dto.setProductTypeName(productNameMap.get(task.getProductTypeId()));
        }
        return dto;
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
        } else {
            // 免工序报工模式哨兵任务无 WorkProcess 定义 → 用友好报工名 (list/getById 路径也生效);
            // 标准出成/副产物预填留 null (诚实, 哨兵无标准)。非哨兵 workProcessId → null (不变)。
            String sentinelName = sentinelProcessName(task.getWorkProcessId());
            if (sentinelName != null) {
                dto.setProcessName(sentinelName);
            }
        }
        return dto;
    }
}
