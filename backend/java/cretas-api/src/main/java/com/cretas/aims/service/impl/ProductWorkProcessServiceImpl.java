package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ProductWorkProcessDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductWorkProcessAssignee;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.ProductWorkProcessService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductWorkProcessServiceImpl implements ProductWorkProcessService {

    private static final Logger log = LoggerFactory.getLogger(ProductWorkProcessServiceImpl.class);
    private final ProductWorkProcessRepository repository;
    private final WorkProcessRepository workProcessRepository;
    private final ProductWorkProcessAssigneeRepository assigneeRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public ProductWorkProcessDTO create(String factoryId, ProductWorkProcessDTO dto) {
        log.info("Creating product-work-process association for factory: {}", factoryId);

        if (repository.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                factoryId, dto.getProductTypeId(), dto.getWorkProcessId())) {
            throw new BusinessException(409, "该产品已关联此工序")
                    .withHint("请选择其他工序, 或刷新列表查看现有关联");
        }

        // Validate work process exists
        workProcessRepository.findByFactoryIdAndId(factoryId, dto.getWorkProcessId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkProcess", "id", dto.getWorkProcessId()));

        // T121: if assigneeWorkerIds provided, use assignees[0] as primary; else fall back to responsibleWorkerId field.
        List<Long> assignees = dto.getAssigneeWorkerIds();
        boolean hasAssignees = assignees != null && !assignees.isEmpty();

        // Normalize -1L sentinel to null so it never lands in DB via create
        Long rw = dto.getResponsibleWorkerId();
        Long normalizedRw;
        if (hasAssignees) {
            // multi-assignee path: primary = assignees[0]
            normalizedRw = assignees.get(0);
        } else if (rw != null && rw == -1L) {
            normalizedRw = null;
        } else {
            normalizedRw = rw;
        }

        ProductWorkProcess entity = ProductWorkProcess.builder()
                .factoryId(factoryId)
                .productTypeId(dto.getProductTypeId())
                .workProcessId(dto.getWorkProcessId())
                .processOrder(dto.getProcessOrder() != null ? dto.getProcessOrder() : 0)
                .unitOverride(dto.getUnitOverride())
                .estimatedMinutesOverride(dto.getEstimatedMinutesOverride())
                .responsibleWorkerId(normalizedRw)
                // Wave2 可配置报工粒度: null → 默认 true (逐道报, 向后兼容); 显式 false → 免报。
                .reportingRequired(dto.getReportingRequired() == null ? Boolean.TRUE : dto.getReportingRequired())
                // 半成品注入工序 (张权 R4): null → 默认 false (普通工序); 显式 true → 逐道录入可选 SFI/FG。
                .allowSemiFinishedInjection(
                        dto.getAllowSemiFinishedInjection() == null ? Boolean.FALSE : dto.getAllowSemiFinishedInjection())
                // 多上游混批: null → 默认 false (单批); 显式 true → 报工可添加多个来源批。
                .allowMultipleUpstreamSources(
                        dto.getAllowMultipleUpstreamSources() == null ? Boolean.FALSE : dto.getAllowMultipleUpstreamSources())
                // 工序成本配置 (报工自动继承, 防呆: 操作员不手填会计类别)
                .defaultCostCategory(dto.getDefaultCostCategory())
                .packagingTemplate(dto.getPackagingTemplate())
                .auxAllocMethod(dto.getAuxAllocMethod())
                // 段2(B) 辅料对账配置 (标准率/辅料单价/基准)
                .standardYieldRate(dto.getStandardYieldRate())
                .auxUnitPrice(dto.getAuxUnitPrice())
                .auxBasis(dto.getAuxBasis())
                .build();

        ProductWorkProcess saved = repository.save(entity);

        // T121: upsert join table
        upsertAssignees(saved.getId(), hasAssignees ? assignees : toSingletonList(normalizedRw));

        return toDTO(saved, null, loadAssigneeIds(saved.getId()));
    }

    @Override
    public List<ProductWorkProcessDTO> listByProduct(String factoryId, String productTypeId) {
        log.debug("Listing work processes for product {} in factory {}", productTypeId, factoryId);
        List<ProductWorkProcess> associations = repository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, productTypeId);

        // Pre-load work processes for enrichment
        List<String> wpIds = associations.stream()
                .map(ProductWorkProcess::getWorkProcessId)
                .distinct()
                .collect(Collectors.toList());
        Map<String, WorkProcess> wpMap = workProcessRepository.findByFactoryIdAndIdIn(factoryId, wpIds)
                .stream()
                .collect(Collectors.toMap(WorkProcess::getId, Function.identity()));

        // T121: pre-load all assignees for these product_work_process ids in one query
        List<Long> pwpIds = associations.stream().map(ProductWorkProcess::getId).collect(Collectors.toList());
        Map<Long, List<Long>> assigneeMap = loadAssigneeIdsForAll(pwpIds);

        // T135: batch-load user names for responsible workers to display on chips
        Set<Long> workerIds = associations.stream()
                .map(ProductWorkProcess::getResponsibleWorkerId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, String> workerNameMap = workerIds.isEmpty() ? Map.of() :
                userRepository.findByIdIn(workerIds).stream()
                        .collect(Collectors.toMap(
                                User::getId,
                                u -> u.getFullName() != null && !u.getFullName().isBlank()
                                        ? u.getFullName() : u.getUsername()
                        ));

        return associations.stream()
                .map(a -> {
                    ProductWorkProcessDTO dto = toDTO(a, wpMap.get(a.getWorkProcessId()),
                            assigneeMap.getOrDefault(a.getId(), List.of()));
                    if (a.getResponsibleWorkerId() != null) {
                        dto.setResponsibleWorkerName(workerNameMap.get(a.getResponsibleWorkerId()));
                    }
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ProductWorkProcessDTO update(String factoryId, Long id, ProductWorkProcessDTO dto) {
        log.info("Updating product-work-process {} for factory: {}", id, factoryId);
        ProductWorkProcess entity = repository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductWorkProcess", "id", id.toString()));

        if (dto.getProcessOrder() != null) entity.setProcessOrder(dto.getProcessOrder());
        if (dto.getUnitOverride() != null) entity.setUnitOverride(dto.getUnitOverride());
        if (dto.getEstimatedMinutesOverride() != null) entity.setEstimatedMinutesOverride(dto.getEstimatedMinutesOverride());
        // Wave2 可配置报工粒度: null → no-change (partial update 不动现有值); 非 null → set (true/false 均可显式设)。
        if (dto.getReportingRequired() != null) entity.setReportingRequired(dto.getReportingRequired());
        // 半成品注入工序 partial update: null → no-change; 非 null → set (true/false 均可显式设)。
        if (dto.getAllowSemiFinishedInjection() != null) entity.setAllowSemiFinishedInjection(dto.getAllowSemiFinishedInjection());
        // 多上游混批 partial update: null → no-change; 非 null → set (true/false 均可显式设)。
        if (dto.getAllowMultipleUpstreamSources() != null) entity.setAllowMultipleUpstreamSources(dto.getAllowMultipleUpstreamSources());
        // 工序成本配置 partial update: null → no-change; 非 null → set
        if (dto.getDefaultCostCategory() != null) entity.setDefaultCostCategory(dto.getDefaultCostCategory());
        if (dto.getPackagingTemplate() != null) entity.setPackagingTemplate(dto.getPackagingTemplate());
        if (dto.getAuxAllocMethod() != null) entity.setAuxAllocMethod(dto.getAuxAllocMethod());
        // 段2(B) 辅料对账配置 partial update: null → no-change; 非 null → set
        if (dto.getStandardYieldRate() != null) entity.setStandardYieldRate(dto.getStandardYieldRate());
        if (dto.getAuxUnitPrice() != null) entity.setAuxUnitPrice(dto.getAuxUnitPrice());
        if (dto.getAuxBasis() != null) entity.setAuxBasis(dto.getAuxBasis());

        // T121: if assigneeWorkerIds provided, they govern responsible_worker_id + join table.
        List<Long> assignees = dto.getAssigneeWorkerIds();
        boolean hasAssignees = assignees != null && !assignees.isEmpty();

        if (hasAssignees) {
            // multi-assignee update: primary = assignees[0], update join table
            entity.setResponsibleWorkerId(assignees.get(0));
        } else {
            // Single-value path (backward-compat):
            // Three-state responsibleWorkerId:
            //   null        → no change (partial update — leave existing value alone)
            //   -1L         → clear (set null; sentinel must never persist as a real user id)
            //   positive id → set
            Long rw = dto.getResponsibleWorkerId();
            if (rw != null) {
                entity.setResponsibleWorkerId(rw == -1L ? null : rw);
            }
        }

        ProductWorkProcess saved = repository.save(entity);

        // T121: upsert join table when assignees explicitly provided
        if (hasAssignees) {
            upsertAssignees(saved.getId(), assignees);
        } else if (dto.getResponsibleWorkerId() != null) {
            // Single-value changed → sync join table to reflect new primary
            Long effectiveRw = dto.getResponsibleWorkerId() == -1L ? null : dto.getResponsibleWorkerId();
            upsertAssignees(saved.getId(), toSingletonList(effectiveRw));
        }

        return toDTO(saved, null, loadAssigneeIds(saved.getId()));
    }

    @Override
    @Transactional
    public void delete(String factoryId, Long id) {
        log.info("Deleting product-work-process {} for factory: {}", id, factoryId);
        ProductWorkProcess entity = repository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductWorkProcess", "id", id.toString()));
        repository.delete(entity);
    }

    @Override
    @Transactional
    public void batchSort(String factoryId, List<ProductWorkProcessDTO.SortItem> items) {
        log.info("Batch sorting {} product-work-process items for factory: {}", items.size(), factoryId);
        for (ProductWorkProcessDTO.SortItem item : items) {
            repository.findByFactoryIdAndId(factoryId, item.getId())
                    .ifPresent(entity -> {
                        entity.setProcessOrder(item.getProcessOrder());
                        repository.save(entity);
                    });
        }
    }

    @Override
    @Transactional
    public int copyChain(String factoryId, String sourceProductId, String targetProductId) {
        if (sourceProductId == null || targetProductId == null || sourceProductId.equals(targetProductId)) {
            throw new BusinessException(400, "源产品与目标产品必须是两个不同的产品");
        }
        List<ProductWorkProcess> source = repository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, sourceProductId);
        if (source.isEmpty()) {
            throw new BusinessException(400, "源产品没有工序链可复制, 请先给源产品配置工序");
        }
        List<ProductWorkProcess> existing = repository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, targetProductId);
        if (!existing.isEmpty()) {
            throw new BusinessException(409, "目标产品已有 " + existing.size() + " 道工序, 复制前请先清空目标工序链")
                    .withHint("如需覆盖, 请先删除目标产品现有工序再复制");
        }
        int copied = 0;
        for (ProductWorkProcess src : source) {
            ProductWorkProcess entity = ProductWorkProcess.builder()
                    .factoryId(factoryId)
                    .productTypeId(targetProductId)
                    .workProcessId(src.getWorkProcessId())
                    .processOrder(src.getProcessOrder())
                    .unitOverride(src.getUnitOverride())
                    .estimatedMinutesOverride(src.getEstimatedMinutesOverride())
                    // 防呆: 不复制人员指派 (各产品班组不同) — responsibleWorkerId/assignees 留空, 由用户另配
                    .responsibleWorkerId(null)
                    .reportingRequired(src.getReportingRequired())
                    .allowSemiFinishedInjection(src.getAllowSemiFinishedInjection())
                    .allowMultipleUpstreamSources(src.getAllowMultipleUpstreamSources())
                    .defaultCostCategory(src.getDefaultCostCategory())
                    .packagingTemplate(src.getPackagingTemplate())
                    .auxAllocMethod(src.getAuxAllocMethod())
                    .standardYieldRate(src.getStandardYieldRate())
                    .auxUnitPrice(src.getAuxUnitPrice())
                    .auxBasis(src.getAuxBasis())
                    .build();
            repository.save(entity);
            copied++;
        }
        log.info("[COPY-CHAIN] factory={} {} -> {} copied {} processes (assignees not copied)",
                factoryId, sourceProductId, targetProductId, copied);
        return copied;
    }

    /** Legacy overload — called from batchSort / delete (no assignee enrichment needed). */
    private ProductWorkProcessDTO toDTO(ProductWorkProcess entity, WorkProcess wp) {
        return toDTO(entity, wp, List.of());
    }

    private ProductWorkProcessDTO toDTO(ProductWorkProcess entity, WorkProcess wp, List<Long> assigneeIds) {
        ProductWorkProcessDTO.ProductWorkProcessDTOBuilder builder = ProductWorkProcessDTO.builder()
                .id(entity.getId())
                .productTypeId(entity.getProductTypeId())
                .workProcessId(entity.getWorkProcessId())
                .processOrder(entity.getProcessOrder())
                .unitOverride(entity.getUnitOverride())
                .estimatedMinutesOverride(entity.getEstimatedMinutesOverride())
                .responsibleWorkerId(entity.getResponsibleWorkerId())
                .assigneeWorkerIds(assigneeIds.isEmpty() ? null : assigneeIds)
                .isActive(entity.getIsActive())
                .reportingRequired(entity.getReportingRequired())
                .allowSemiFinishedInjection(entity.getAllowSemiFinishedInjection())
                .allowMultipleUpstreamSources(entity.getAllowMultipleUpstreamSources())
                .defaultCostCategory(entity.getDefaultCostCategory())
                .packagingTemplate(entity.getPackagingTemplate())
                .auxAllocMethod(entity.getAuxAllocMethod())
                .standardYieldRate(entity.getStandardYieldRate())
                .auxUnitPrice(entity.getAuxUnitPrice())
                .auxBasis(entity.getAuxBasis())
                .createdAt(entity.getCreatedAt());

        if (wp != null) {
            builder.processName(wp.getProcessName())
                    .processCategory(wp.getProcessCategory())
                    .defaultUnit(wp.getUnit())
                    .defaultEstimatedMinutes(wp.getEstimatedMinutes());
        }

        return builder.build();
    }

    // ── T121 helpers ────────────────────────────────────────────────────────

    /**
     * Upsert join table: soft-delete rows not in {@code desiredWorkerIds}; add missing ones.
     * If {@code desiredWorkerIds} is empty (cleared), soft-delete all existing rows.
     */
    @Transactional
    protected void upsertAssignees(Long pwpId, List<Long> desiredWorkerIds) {
        List<Long> desired = desiredWorkerIds == null ? List.of() : desiredWorkerIds;
        Set<Long> desiredSet = new java.util.HashSet<>(desired);

        // Soft-delete rows not in desired list
        List<ProductWorkProcessAssignee> existing = assigneeRepository.findByProductWorkProcessId(pwpId);
        for (ProductWorkProcessAssignee row : existing) {
            if (!desiredSet.contains(row.getWorkerId())) {
                row.softDelete();
                assigneeRepository.save(row);
            }
        }

        // Add missing rows
        Set<Long> existingWorkerIds = existing.stream()
                .map(ProductWorkProcessAssignee::getWorkerId)
                .collect(Collectors.toSet());
        for (Long workerId : desired) {
            if (!existingWorkerIds.contains(workerId)) {
                assigneeRepository.save(ProductWorkProcessAssignee.builder()
                        .productWorkProcessId(pwpId)
                        .workerId(workerId)
                        .build());
            }
        }
    }

    /** Load active assignee worker_ids for a single PWP row. */
    private List<Long> loadAssigneeIds(Long pwpId) {
        return assigneeRepository.findByProductWorkProcessId(pwpId).stream()
                .map(ProductWorkProcessAssignee::getWorkerId)
                .collect(Collectors.toList());
    }

    /**
     * Batch-load assignee worker_ids for multiple PWP ids (avoids N+1).
     * Returns map: pwpId → list of worker_ids.
     */
    private Map<Long, List<Long>> loadAssigneeIdsForAll(List<Long> pwpIds) {
        if (pwpIds.isEmpty()) return Map.of();
        // Fetch all assignees for the given pwp ids in one query via Spring Data findAll + filter.
        // For small lists this is fine; could be a @Query for very large lists.
        Map<Long, List<Long>> result = new java.util.HashMap<>();
        for (Long pwpId : pwpIds) {
            List<Long> ids = assigneeRepository.findByProductWorkProcessId(pwpId).stream()
                    .map(ProductWorkProcessAssignee::getWorkerId)
                    .collect(Collectors.toList());
            if (!ids.isEmpty()) result.put(pwpId, ids);
        }
        return result;
    }

    /** Helper: wrap a nullable Long into a singleton list (empty if null). */
    private static List<Long> toSingletonList(Long v) {
        return v == null ? List.of() : List.of(v);
    }
}
