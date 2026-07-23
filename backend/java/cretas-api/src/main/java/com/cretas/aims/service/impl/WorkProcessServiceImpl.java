package com.cretas.aims.service.impl;

import com.cretas.aims.dto.WorkProcessDTO;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.WorkProcessGovernanceAudit;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.WorkProcessGovernanceAuditRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.bom.BomProcessInjectionConfigRepository;
import com.cretas.aims.service.WorkProcessService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkProcessServiceImpl implements WorkProcessService {

    private static final Logger log = LoggerFactory.getLogger(WorkProcessServiceImpl.class);
    private final WorkProcessRepository workProcessRepository;
    private final WorkProcessGovernanceAuditRepository governanceAuditRepository;
    private final ProductWorkProcessRepository productWorkProcessRepository;
    private final ProductProcessWorkflowRepository workflowRepository;
    private final WorkProcessTaskRepository workProcessTaskRepository;
    private final ObjectMapper objectMapper;
    /** 孤儿守卫 (2026-07-13): 删工序前检查是否被调料配方引用。 */
    private final BomSeasoningItemRepository bomSeasoningItemRepository;
    private final BomProcessInjectionConfigRepository bomProcessInjectionConfigRepository;

    @Override
    @Transactional
    public WorkProcessDTO create(String factoryId, WorkProcessDTO dto) {
        log.info("Creating work process '{}' for factory: {}", dto.getProcessName(), factoryId);
        String category = validateCategory(
                factoryId, dto.getProcessCategory(), Boolean.TRUE.equals(dto.getCreateCategory()));

        // C5 Step 1a: exact name block (existing behaviour)
        if (workProcessRepository.existsByFactoryIdAndProcessName(factoryId, dto.getProcessName())) {
            throw new BusinessException(409, "工序名称已存在: " + dto.getProcessName())
                    .withHint("请使用其他工序名称").withHintTarget("processName");
        }

        validateYieldRange(dto.getStandardYieldMin(), dto.getStandardYieldMax());

        WorkProcess entity = WorkProcess.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(factoryId)
                .processName(dto.getProcessName())
                .processCategory(category)
                .description(dto.getDescription())
                .unit("unitless")
                .estimatedMinutes(dto.getEstimatedMinutes())
                .sortOrder(0)
                .isActive(true)
                .standardYieldMin(dto.getStandardYieldMin())
                .standardYieldMax(dto.getStandardYieldMax())
                .needsInput(dto.getNeedsInput() != null ? dto.getNeedsInput() : true)
                .outputUnit(null)
                .defaultOutputMaterialKind(dto.getDefaultOutputMaterialKind() != null
                        ? dto.getDefaultOutputMaterialKind()
                        : WorkProcessOutputMaterialKind.SEMI_FINISHED)
                .semiFinishedOutputCode(blankToNull(dto.getSemiFinishedOutputCode()))
                .standardHourlyRate(dto.getStandardHourlyRate())
                .expectedByproducts(dto.getExpectedByproducts())
                .customFieldSchema(dto.getCustomFieldSchema())
                .build();

        WorkProcess saved = workProcessRepository.save(entity);
        return toDTO(saved);
    }

    @Override
    @Transactional
    public WorkProcessDTO updateOutputMaterialKind(
            String factoryId,
            String id,
            WorkProcessOutputMaterialKind outputMaterialKind) {
        WorkProcess entity = workProcessRepository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkProcess", "id", id));
        entity.setDefaultOutputMaterialKind(outputMaterialKind);
        if (outputMaterialKind == WorkProcessOutputMaterialKind.FINISHED_GOOD) {
            entity.setSemiFinishedOutputCode(null);
        }
        log.info("Updated work process {} output material kind to {} for factory {}",
                id, outputMaterialKind, factoryId);
        return toDTO(workProcessRepository.save(entity));
    }

    @Override
    public PageResponse<WorkProcessDTO> list(String factoryId, Pageable pageable) {
        log.debug("Listing work processes for factory: {}", factoryId);
        Page<WorkProcess> page = workProcessRepository.findByFactoryId(factoryId, pageable);
        List<WorkProcessDTO> content = page.getContent().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        return PageResponse.of(content, page.getNumber() + 1, page.getSize(), page.getTotalElements());
    }

    @Override
    public List<WorkProcessDTO> listActive(String factoryId) {
        log.debug("Listing active work processes for factory: {}", factoryId);
        return workProcessRepository.findByFactoryIdAndIsActiveTrueAndMergedIntoIdIsNullOrderByProcessNameAsc(factoryId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> listCategories(String factoryId) {
        return workProcessRepository.findDistinctProcessCategories(factoryId);
    }

    @Override
    public WorkProcessDTO getById(String factoryId, String id) {
        WorkProcess entity = workProcessRepository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkProcess", "id", id));
        return toDTO(entity);
    }

    @Override
    @Transactional
    public WorkProcessDTO update(String factoryId, String id, WorkProcessDTO dto) {
        log.info("Updating work process {} for factory: {}", id, factoryId);
        WorkProcess entity = workProcessRepository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkProcess", "id", id));
        String category = validateCategory(
                factoryId,
                dto.getProcessCategory() != null ? dto.getProcessCategory() : entity.getProcessCategory(),
                Boolean.TRUE.equals(dto.getCreateCategory()));

        // audit Finding 4 修复: 改名时防重名 —— 调料配方按工序靠工序名跨模式(legacy/workflow)定位,
        // 两个同名工序会让报工成本读错工序的调料/锅序参数。create() 已有唯一性校验, update() 之前缺。
        if (dto.getProcessName() != null && !dto.getProcessName().equals(entity.getProcessName())) {
            boolean clash = workProcessRepository.findByFactoryIdAndProcessName(factoryId, dto.getProcessName())
                    .stream().anyMatch(w -> !id.equals(w.getId()));
            if (clash) {
                throw new BusinessException(409, "工序名称已存在: " + dto.getProcessName())
                        .withHint("请使用其他工序名称").withHintTarget("processName");
            }
        }
        if (dto.getProcessName() != null) entity.setProcessName(dto.getProcessName());
        entity.setProcessCategory(category);
        if (dto.getEstimatedMinutes() != null) entity.setEstimatedMinutes(dto.getEstimatedMinutes());
        if (dto.getDescription() != null) entity.setDescription(dto.getDescription());

        // P0-3: 出成率配置 — if != null 模式无法清空已配值 (防误清取舍)
        // 跨字段校验取"合并后"值 (新值优先, 否则保留已配)
        BigDecimal effMin = dto.getStandardYieldMin() != null ? dto.getStandardYieldMin() : entity.getStandardYieldMin();
        BigDecimal effMax = dto.getStandardYieldMax() != null ? dto.getStandardYieldMax() : entity.getStandardYieldMax();
        validateYieldRange(effMin, effMax);
        if (dto.getStandardYieldMin() != null) entity.setStandardYieldMin(dto.getStandardYieldMin());
        if (dto.getStandardYieldMax() != null) entity.setStandardYieldMax(dto.getStandardYieldMax());
        if (dto.getNeedsInput() != null) entity.setNeedsInput(dto.getNeedsInput());
        if (dto.getDefaultOutputMaterialKind() != null) {
            entity.setDefaultOutputMaterialKind(dto.getDefaultOutputMaterialKind());
        }
        if (dto.isSemiFinishedOutputCodeSpecified()) {
            entity.setSemiFinishedOutputCode(blankToNull(dto.getSemiFinishedOutputCode()));
        }
        if (dto.getStandardHourlyRate() != null) entity.setStandardHourlyRate(dto.getStandardHourlyRate());
        // expectedByproducts: null means "don't change"; explicit empty list means "clear"
        if (dto.getExpectedByproducts() != null) entity.setExpectedByproducts(dto.getExpectedByproducts());
        // customFieldSchema (G2): null means "don't change"; explicit empty list means "clear"
        if (dto.getCustomFieldSchema() != null) entity.setCustomFieldSchema(dto.getCustomFieldSchema());

        WorkProcess saved = workProcessRepository.save(entity);
        return toDTO(saved);
    }

    /** P0-3: 标准出成率区间跨字段校验 — min < max (两者皆非 null 时), 否则 400. */
    private void validateYieldRange(BigDecimal min, BigDecimal max) {
        if (min != null && max != null && min.compareTo(max) >= 0) {
            throw new BusinessException(400, "标准出成率下限必须小于上限")
                    .withHint("如焯水 0.30~0.60, 滚揉保水 1.00~1.35")
                    .withHintTarget("standardYieldMax");
        }
    }

    @Override
    @Transactional
    public void delete(String factoryId, String id) {
        log.info("Deleting work process {} for factory: {}", id, factoryId);
        WorkProcess entity = workProcessRepository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkProcess", "id", id));
        // 孤儿守卫 (re-audit 2026-07-13): 该工序若被调料配方引用, 删后配置成孤儿 → 调料成本静默变 0 无告警。
        // 删前阻断, 逼用户先清理调料配方。
        if (bomSeasoningItemRepository.existsByWorkProcessId(id)
                || bomProcessInjectionConfigRepository.existsByWorkProcessId(id)) {
            throw new BusinessException(409, "该工序已被调料配方引用，无法删除")
                    .withHint("请先在「生产 → BOM 配方 → 调料配方」移除引用该工序的调料配置")
                    .withHintTarget("id");
        }
        WorkProcessDTO.ReferenceStats references = referenceStats(factoryId, id);
        if (references.getSkuAssociationCount() > 0
                || references.getWorkflowVersionCount() > 0
                || references.getProductionTaskCount() > 0
                || workProcessRepository.existsByFactoryIdAndMergedIntoId(factoryId, id)) {
            throw new BusinessException(409, "该工序已有业务引用，不能删除")
                    .withHint("请停用或使用重复治理；已有流程和生产历史会继续保留原工序 ID")
                    .withHintTarget("id");
        }
        workProcessRepository.delete(entity);
    }

    @Override
    @Transactional
    public WorkProcessDTO toggleStatus(String factoryId, String id) {
        log.info("Toggling work process status {} for factory: {}", id, factoryId);
        WorkProcess entity = workProcessRepository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkProcess", "id", id));
        entity.setIsActive(!entity.getIsActive());
        WorkProcess saved = workProcessRepository.save(entity);
        return toDTO(saved);
    }

    @Override
    @Transactional
    public void updateSortOrder(String factoryId, List<WorkProcessDTO.SortOrderUpdate> updates) {
        log.info("Ignoring legacy work-process sort update for factory {}. Workflow step order is authoritative.",
                factoryId);
    }

    /**
     * C5: Detect duplicate clusters in-memory.
     * Groups future-selectable processes by normalized (processName, processCategory).
     * Measurement units belong to Workflow nodes/output objects and are deliberately absent here.
     * Returns only groups with ≥ 2 members.
     */
    @Override
    public List<WorkProcessDTO.DuplicateGroup> detectDuplicates(String factoryId) {
        log.debug("Detecting duplicate work processes for factory: {}", factoryId);
        List<WorkProcess> all = workProcessRepository.findByFactoryId(factoryId);

        // Key = "processName|processCategory" (null-safe)
        Map<String, List<WorkProcess>> grouped = new LinkedHashMap<>();
        for (WorkProcess wp : all) {
            if (!wp.isSelectableForNew()) {
                continue;
            }
            String key = normalize(wp.getProcessName())
                    + "|" + normalize(wp.getProcessCategory());
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(wp);
        }

        List<WorkProcessDTO.DuplicateGroup> result = new ArrayList<>();
        for (List<WorkProcess> cluster : grouped.values()) {
            if (cluster.size() >= 2) {
                WorkProcess first = cluster.get(0);
                result.add(WorkProcessDTO.DuplicateGroup.builder()
                        .processName(first.getProcessName())
                        .processCategory(first.getProcessCategory())
                        .members(cluster.stream().map(wp -> toDTO(wp, factoryId)).collect(Collectors.toList()))
                        .build());
            }
        }
        return result;
    }

    private String validateCategory(String factoryId, String rawCategory, boolean createCategory) {
        String category = rawCategory == null
                ? ""
                : java.text.Normalizer.normalize(rawCategory, java.text.Normalizer.Form.NFKC)
                        .trim()
                        .replaceAll("\\s+", " ");
        if (category.isEmpty()) {
            throw new BusinessException(400, "请选择工序类别")
                    .withHint("工序类别必须从工序类别字典中选择")
                    .withHintTarget("processCategory");
        }
        List<String> categories = workProcessRepository.findDistinctProcessCategories(factoryId);
        String normalizedCategory = normalize(category);
        Optional<String> existing = categories.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> normalize(candidate).equals(normalizedCategory))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        // Existing categories can only be extended through the explicit "create category" UI.
        // Normal writes remain fail-closed, so a caller cannot silently introduce free text.
        if (!categories.isEmpty() && !createCategory) {
            throw new BusinessException(400, "工序类别字典中不存在或不属于当前工厂: " + category)
                    .withHint("请从下拉中选择有效类别，或使用“创建新类别”")
                    .withHintTarget("processCategory");
        }
        return category;
    }

    @Override
    @Transactional
    public WorkProcessDTO.GovernanceResult governDuplicates(
            String factoryId,
            WorkProcessDTO.GovernanceRequest request) {
        if (request.getMode() == null) {
            throw new BusinessException(400, "请选择治理方式")
                    .withHintTarget("mode");
        }

        List<String> governedIds = request.getDuplicateProcessIds() == null
                ? List.of()
                : request.getDuplicateProcessIds().stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(id -> !id.isEmpty() && !id.equals(request.getMasterProcessId()))
                        .distinct()
                        .sorted()
                        .toList();
        if (governedIds.isEmpty()) {
            throw new BusinessException(400, "至少选择一条重复工序")
                    .withHintTarget("duplicateProcessIds");
        }

        var replay = governanceAuditRepository.findByFactoryIdAndIdempotencyKey(
                factoryId, request.getIdempotencyKey());
        if (replay.isPresent()) {
            WorkProcessGovernanceAudit audit = replay.get();
            String requestedIds = String.join(",", governedIds);
            if (!Objects.equals(audit.getMasterProcessId(), request.getMasterProcessId())
                    || audit.getMode() != request.getMode()
                    || !Objects.equals(audit.getGovernedProcessIds(), requestedIds)) {
                throw new BusinessException(409, "幂等键已被其他治理请求使用")
                        .withHintTarget("idempotencyKey");
            }
            return WorkProcessDTO.GovernanceResult.builder()
                    .masterProcessId(audit.getMasterProcessId())
                    .mode(audit.getMode())
                    .governedProcessIds(governedIds)
                    .replayed(true)
                    .build();
        }

        List<String> lockIds = new ArrayList<>(governedIds);
        lockIds.add(request.getMasterProcessId());
        lockIds = lockIds.stream().distinct().sorted().toList();
        List<WorkProcess> locked = workProcessRepository.lockByFactoryIdAndIdIn(factoryId, lockIds);
        if (locked.size() != lockIds.size()) {
            throw new BusinessException(404, "部分工序不存在或不属于当前工厂");
        }
        Map<String, WorkProcess> byId = locked.stream()
                .collect(Collectors.toMap(WorkProcess::getId, wp -> wp));
        WorkProcess master = byId.get(request.getMasterProcessId());
        if (master == null || master.getMergedIntoId() != null) {
            throw new BusinessException(409, "主工序必须是尚未合并的记录")
                    .withHintTarget("masterProcessId");
        }

        String expectedKey = duplicateKey(master);
        for (String duplicateId : governedIds) {
            WorkProcess duplicate = byId.get(duplicateId);
            if (!expectedKey.equals(duplicateKey(duplicate))) {
                throw new BusinessException(409, "所选记录不属于同一名称和类别的重复组")
                        .withHintTarget("duplicateProcessIds");
            }
            if (duplicate.getMergedIntoId() != null
                    && !duplicate.getMergedIntoId().equals(master.getId())) {
                throw new BusinessException(409, "重复工序已归并到其他主工序")
                        .withHintTarget("duplicateProcessIds");
            }
            if (request.getMode() == WorkProcessDTO.GovernanceMode.MERGE
                    && !sameCriticalConfiguration(master, duplicate)) {
                throw new BusinessException(409, "重复工序存在关键动作配置差异，不能自动合并")
                        .withHint("请核对产出类型、质检/自定义字段、预期副产物和模板参数")
                        .withHintTarget("duplicateProcessIds");
            }
        }

        String operator = currentOperator();
        LocalDateTime governedAt = LocalDateTime.now();
        for (String duplicateId : governedIds) {
            WorkProcess duplicate = byId.get(duplicateId);
            duplicate.setIsActive(false);
            duplicate.setGovernanceReason(blankToNull(request.getReason()));
            if (request.getMode() == WorkProcessDTO.GovernanceMode.MERGE) {
                duplicate.setMergedIntoId(master.getId());
                duplicate.setMergedAt(governedAt);
                duplicate.setMergedBy(operator);
            }
        }
        master.setIsActive(true);
        workProcessRepository.saveAll(locked);

        governanceAuditRepository.save(WorkProcessGovernanceAudit.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(factoryId)
                .idempotencyKey(request.getIdempotencyKey())
                .mode(request.getMode())
                .masterProcessId(master.getId())
                .governedProcessIds(String.join(",", governedIds))
                .operator(operator)
                .reason(blankToNull(request.getReason()))
                .build());

        return WorkProcessDTO.GovernanceResult.builder()
                .masterProcessId(master.getId())
                .mode(request.getMode())
                .governedProcessIds(governedIds)
                .replayed(false)
                .build();
    }

    private WorkProcessDTO.ReferenceStats referenceStats(String factoryId, String workProcessId) {
        long skuAssociations = productWorkProcessRepository == null
                ? 0L : productWorkProcessRepository.countByFactoryIdAndWorkProcessId(factoryId, workProcessId);
        long productionTasks = workProcessTaskRepository == null
                ? 0L : workProcessTaskRepository.countByFactoryIdAndWorkProcessId(factoryId, workProcessId);
        long workflowVersions = workflowRepository == null
                ? 0L : workflowRepository.findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc(factoryId)
                        .stream()
                        .filter(workflow -> workflowContains(workflow, workProcessId))
                        .count();
        return WorkProcessDTO.ReferenceStats.builder()
                .skuAssociationCount(skuAssociations)
                .workflowVersionCount(workflowVersions)
                .productionTaskCount(productionTasks)
                .build();
    }

    private boolean workflowContains(ProductProcessWorkflow workflow, String workProcessId) {
        if (workflow.getNodesJson() == null || workflow.getNodesJson().isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(workflow.getNodesJson());
            for (JsonNode node : root) {
                JsonNode data = node.path("data");
                if (workProcessId.equals(data.path("workProcessId").asText())
                        || workProcessId.equals(node.path("workProcessId").asText())) {
                    return true;
                }
            }
        } catch (Exception exception) {
            log.warn("Unable to inspect workflow {} for work-process references", workflow.getId(), exception);
        }
        return false;
    }

    private boolean sameCriticalConfiguration(WorkProcess left, WorkProcess right) {
        return Objects.equals(left.getProcessCategory(), right.getProcessCategory())
                && Objects.equals(left.getNeedsInput(), right.getNeedsInput())
                && Objects.equals(left.getDefaultOutputMaterialKind(), right.getDefaultOutputMaterialKind())
                && Objects.equals(left.getSemiFinishedOutputCode(), right.getSemiFinishedOutputCode())
                && Objects.equals(left.getExpectedByproducts(), right.getExpectedByproducts())
                && Objects.equals(left.getCustomFieldSchema(), right.getCustomFieldSchema())
                && Objects.equals(left.getStandardYieldMin(), right.getStandardYieldMin())
                && Objects.equals(left.getStandardYieldMax(), right.getStandardYieldMax())
                && Objects.equals(left.getStandardHourlyRate(), right.getStandardHourlyRate());
    }

    private String duplicateKey(WorkProcess workProcess) {
        return normalize(workProcess.getProcessName()) + "|" + normalize(workProcess.getProcessCategory());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String currentOperator() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null
                ? "system"
                : authentication.getName();
    }

    private WorkProcessDTO toDTO(WorkProcess entity) {
        return toDTO(entity, entity.getFactoryId());
    }

    private WorkProcessDTO toDTO(WorkProcess entity, String factoryId) {
        return WorkProcessDTO.builder()
                .id(entity.getId())
                .processName(entity.getProcessName())
                .processCategory(entity.getProcessCategory())
                .description(entity.getDescription())
                .estimatedMinutes(entity.getEstimatedMinutes())
                .isActive(entity.getIsActive())
                .standardYieldMin(entity.getStandardYieldMin())
                .standardYieldMax(entity.getStandardYieldMax())
                .needsInput(entity.getNeedsInput())
                .defaultOutputMaterialKind(entity.getDefaultOutputMaterialKind())
                .semiFinishedOutputCode(entity.getSemiFinishedOutputCode())
                .standardHourlyRate(entity.getStandardHourlyRate())
                .expectedByproducts(entity.getExpectedByproducts())
                .customFieldSchema(entity.getCustomFieldSchema())
                .mergedIntoId(entity.getMergedIntoId())
                .mergedAt(entity.getMergedAt())
                .mergedBy(entity.getMergedBy())
                .governanceReason(entity.getGovernanceReason())
                .lockVersion(entity.getLockVersion())
                .selectableForNew(entity.isSelectableForNew())
                .referenceStats(referenceStats(factoryId, entity.getId()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
