package com.cretas.aims.service.impl;

import com.cretas.aims.dto.WorkProcessDTO;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.WorkProcessService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkProcessServiceImpl implements WorkProcessService {

    private static final Logger log = LoggerFactory.getLogger(WorkProcessServiceImpl.class);
    private final WorkProcessRepository workProcessRepository;

    @Override
    @Transactional
    public WorkProcessDTO create(String factoryId, WorkProcessDTO dto) {
        log.info("Creating work process '{}' for factory: {}", dto.getProcessName(), factoryId);

        // C5 Step 1a: exact name block (existing behaviour)
        if (workProcessRepository.existsByFactoryIdAndProcessName(factoryId, dto.getProcessName())) {
            throw new BusinessException(409, "工序名称已存在: " + dto.getProcessName())
                    .withHint("请使用其他工序名称").withHintTarget("processName");
        }

        // C5 Step 1b: name + category + unit near-dup warning (block with 409 so the user is
        // aware and can choose to reuse the existing process instead of creating another copy).
        // Only fires when category and unit are both provided and all three match an existing one.
        String incomingUnit = dto.getUnit() != null ? dto.getUnit() : "kg";
        if (dto.getProcessCategory() != null && !dto.getProcessCategory().isBlank()) {
            List<WorkProcess> nearDups = workProcessRepository
                    .findByFactoryIdAndProcessNameAndProcessCategoryAndUnit(
                            factoryId, dto.getProcessName(), dto.getProcessCategory(), incomingUnit);
            if (!nearDups.isEmpty()) {
                // This branch is logically unreachable when exact-name check above fires first,
                // but may catch category/unit drift (same name, different category passed in earlier).
                // Keep as defence-in-depth; in practice exact-name check covers the common case.
                throw new BusinessException(409,
                        "已存在相同名称+类别+单位的工序: " + dto.getProcessName()
                                + "（" + dto.getProcessCategory() + "/" + incomingUnit + "）")
                        .withHint("请直接使用已有工序，或修改名称/类别/单位后重试")
                        .withHintTarget("processName");
            }
        }

        validateYieldRange(dto.getStandardYieldMin(), dto.getStandardYieldMax());

        WorkProcess entity = WorkProcess.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(factoryId)
                .processName(dto.getProcessName())
                .processCategory(dto.getProcessCategory())
                .description(dto.getDescription())
                .unit(dto.getUnit() != null ? dto.getUnit() : "kg")
                .estimatedMinutes(dto.getEstimatedMinutes())
                .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
                .isActive(true)
                .standardYieldMin(dto.getStandardYieldMin())
                .standardYieldMax(dto.getStandardYieldMax())
                .needsInput(dto.getNeedsInput() != null ? dto.getNeedsInput() : true)
                .outputUnit(dto.getOutputUnit())
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
        return workProcessRepository.findByFactoryIdAndIsActiveTrueOrderBySortOrderAsc(factoryId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
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

        if (dto.getProcessName() != null) entity.setProcessName(dto.getProcessName());
        if (dto.getProcessCategory() != null) entity.setProcessCategory(dto.getProcessCategory());
        if (dto.getUnit() != null) entity.setUnit(dto.getUnit());
        if (dto.getEstimatedMinutes() != null) entity.setEstimatedMinutes(dto.getEstimatedMinutes());
        if (dto.getDescription() != null) entity.setDescription(dto.getDescription());
        if (dto.getSortOrder() != null) entity.setSortOrder(dto.getSortOrder());

        // P0-3: 出成率配置 — if != null 模式无法清空已配值 (防误清取舍)
        // 跨字段校验取"合并后"值 (新值优先, 否则保留已配)
        BigDecimal effMin = dto.getStandardYieldMin() != null ? dto.getStandardYieldMin() : entity.getStandardYieldMin();
        BigDecimal effMax = dto.getStandardYieldMax() != null ? dto.getStandardYieldMax() : entity.getStandardYieldMax();
        validateYieldRange(effMin, effMax);
        if (dto.getStandardYieldMin() != null) entity.setStandardYieldMin(dto.getStandardYieldMin());
        if (dto.getStandardYieldMax() != null) entity.setStandardYieldMax(dto.getStandardYieldMax());
        if (dto.getNeedsInput() != null) entity.setNeedsInput(dto.getNeedsInput());
        if (dto.getOutputUnit() != null) entity.setOutputUnit(dto.getOutputUnit());
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
        log.info("Updating sort order for {} work processes in factory: {}", updates.size(), factoryId);
        for (WorkProcessDTO.SortOrderUpdate update : updates) {
            workProcessRepository.findByFactoryIdAndId(factoryId, update.getId())
                    .ifPresent(wp -> {
                        wp.setSortOrder(update.getSortOrder());
                        workProcessRepository.save(wp);
                    });
        }
    }

    /**
     * C5: Detect duplicate clusters in-memory.
     * Groups all processes for the factory by (processName, processCategory, unit) —
     * categories/units are normalised to empty-string so null and "" are treated the same.
     * Returns only groups with ≥ 2 members.
     */
    @Override
    public List<WorkProcessDTO.DuplicateGroup> detectDuplicates(String factoryId) {
        log.debug("Detecting duplicate work processes for factory: {}", factoryId);
        List<WorkProcess> all = workProcessRepository.findByFactoryId(factoryId);

        // Key = "processName|processCategory|unit" (null-safe)
        Map<String, List<WorkProcess>> grouped = new LinkedHashMap<>();
        for (WorkProcess wp : all) {
            String key = wp.getProcessName()
                    + "|" + Objects.toString(wp.getProcessCategory(), "")
                    + "|" + Objects.toString(wp.getUnit(), "");
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(wp);
        }

        List<WorkProcessDTO.DuplicateGroup> result = new ArrayList<>();
        for (List<WorkProcess> cluster : grouped.values()) {
            if (cluster.size() >= 2) {
                WorkProcess first = cluster.get(0);
                result.add(WorkProcessDTO.DuplicateGroup.builder()
                        .processName(first.getProcessName())
                        .processCategory(first.getProcessCategory())
                        .unit(first.getUnit())
                        .members(cluster.stream().map(this::toDTO).collect(Collectors.toList()))
                        .build());
            }
        }
        return result;
    }

    private WorkProcessDTO toDTO(WorkProcess entity) {
        return WorkProcessDTO.builder()
                .id(entity.getId())
                .processName(entity.getProcessName())
                .processCategory(entity.getProcessCategory())
                .description(entity.getDescription())
                .unit(entity.getUnit())
                .estimatedMinutes(entity.getEstimatedMinutes())
                .sortOrder(entity.getSortOrder())
                .isActive(entity.getIsActive())
                .standardYieldMin(entity.getStandardYieldMin())
                .standardYieldMax(entity.getStandardYieldMax())
                .needsInput(entity.getNeedsInput())
                .outputUnit(entity.getOutputUnit())
                .defaultOutputMaterialKind(entity.getDefaultOutputMaterialKind())
                .semiFinishedOutputCode(entity.getSemiFinishedOutputCode())
                .standardHourlyRate(entity.getStandardHourlyRate())
                .expectedByproducts(entity.getExpectedByproducts())
                .customFieldSchema(entity.getCustomFieldSchema())
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
