package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.MaterialPackagingHierarchyDTO;
import com.cretas.aims.dto.material.MaterialPackagingSpecDTO;
import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.entity.material.MaterialPackagingSpec;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialPackagingSpecRepository;
import com.cretas.aims.service.MaterialPackagingHierarchyService;
import com.cretas.aims.service.unit.UnitContractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 原料计量/包装层级 Service 实现.
 *
 * @since 2026-05-06
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialPackagingHierarchyServiceImpl implements MaterialPackagingHierarchyService {

    private final MaterialPackagingHierarchyRepository repository;
    private final MaterialPackagingSpecRepository packagingSpecRepository;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final UnitContractService unitContractService;

    @Override
    @Transactional(readOnly = true)
    public List<MaterialPackagingHierarchyDTO> listByFactory(String factoryId) {
        return repository.findByFactoryId(factoryId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MaterialPackagingHierarchyDTO> getByMaterialTypeId(String factoryId, String materialTypeId) {
        return repository.findByMaterialTypeId(materialTypeId)
                .filter(e -> factoryId.equals(e.getFactoryId()))
                .map(this::toDTO);
    }

    @Override
    @Transactional
    public MaterialPackagingHierarchyDTO upsert(String factoryId, String materialTypeId,
                                                MaterialPackagingHierarchyDTO dto, Long createdBy) {
        // 校验原料归属本工厂
        com.cretas.aims.entity.RawMaterialType materialType = materialTypeRepository.findById(materialTypeId)
                .filter(m -> factoryId.equals(m.getFactoryId()))
                .orElseThrow(() -> new BusinessException(404, "原材料不存在或不属于此工厂"));
        if (dto.getPackagingSpecs() == null) {
            validateLevels(factoryId, materialType.getUnit(), dto);
        }
        List<MaterialPackagingSpecDTO> requestedSpecs = requestedSpecs(dto);
        validatePackagingSpecs(factoryId, materialType.getUnit(), requestedSpecs);
        projectLegacyHierarchy(dto, requestedSpecs);

        MaterialPackagingHierarchy entity = repository.findByMaterialTypeId(materialTypeId)
                .orElseGet(MaterialPackagingHierarchy::new);

        entity.setFactoryId(factoryId);
        entity.setMaterialTypeId(materialTypeId);
        entity.setLevel1Unit(dto.getLevel1Unit());
        entity.setLevel1PerLevel2(dto.getLevel1PerLevel2());
        entity.setLevel2Unit(dto.getLevel2Unit());
        entity.setLevel2PerLevel3(dto.getLevel2PerLevel3());
        entity.setLevel3Unit(dto.getLevel3Unit());
        entity.setNotes(dto.getNotes());
        if (entity.getCreatedBy() == null && createdBy != null) {
            entity.setCreatedBy(createdBy);
        }

        MaterialPackagingHierarchy saved = repository.save(entity);
        replacePackagingSpecs(factoryId, materialTypeId, requestedSpecs);
        log.info("upsert 包装层级: factoryId={}, materialTypeId={}, levels={}/{}/{}",
                factoryId, materialTypeId, saved.getLevel1Unit(), saved.getLevel2Unit(), saved.getLevel3Unit());
        return toDTO(saved);
    }

    @Override
    @Transactional
    public void deleteByMaterialTypeId(String factoryId, String materialTypeId) {
        List<MaterialPackagingSpec> specs = packagingSpecRepository
                .findByFactoryIdAndMaterialTypeIdOrderBySortOrderAscCreatedAtAsc(factoryId, materialTypeId);
        specs.forEach(MaterialPackagingSpec::softDelete);
        if (!specs.isEmpty()) {
            packagingSpecRepository.saveAll(specs);
        }
        repository.findByMaterialTypeId(materialTypeId)
                .filter(e -> factoryId.equals(e.getFactoryId()))
                .ifPresent(repository::delete);
        log.info("删除包装层级: factoryId={}, materialTypeId={}", factoryId, materialTypeId);
    }

    /**
     * 校验层级配对完整性.
     * DB CHECK 约束已经会兜底, 这里在 service 层早一步给出更友好的错误信息.
     */
    private void validateLevels(
            String factoryId, String materialBaseUnit, MaterialPackagingHierarchyDTO dto) {
        if (dto.getLevel1Unit() == null || dto.getLevel1Unit().trim().isEmpty()) {
            throw new BusinessException(400, "一级单位必填");
        }
        if (!sameUnit(factoryId, dto.getLevel1Unit(), materialBaseUnit)) {
            throw new BusinessException(400, "一级单位必须与原料库存基本单位一致")
                    .withHint("请返回原料基本信息确认库存单位，包装换算不能另设一套基本单位")
                    .withHintTarget("level1Unit");
        }

        boolean hasLevel2Unit = dto.getLevel2Unit() != null && !dto.getLevel2Unit().trim().isEmpty();
        boolean hasLevel2Qty = dto.getLevel1PerLevel2() != null
                && dto.getLevel1PerLevel2().compareTo(BigDecimal.ZERO) > 0;
        if (hasLevel2Unit != hasLevel2Qty) {
            throw new BusinessException(400, "二级单位和数量必须同时填写或同时清空");
        }

        boolean hasLevel3Unit = dto.getLevel3Unit() != null && !dto.getLevel3Unit().trim().isEmpty();
        boolean hasLevel3Qty = dto.getLevel2PerLevel3() != null
                && dto.getLevel2PerLevel3().compareTo(BigDecimal.ZERO) > 0;
        if (hasLevel3Unit != hasLevel3Qty) {
            throw new BusinessException(400, "三级单位和数量必须同时填写或同时清空");
        }
        if (hasLevel3Unit && !hasLevel2Unit) {
            throw new BusinessException(400, "必须先配置二级单位才能配置三级");
        }
        if (hasLevel2Unit && sameUnit(factoryId, dto.getLevel1Unit(), dto.getLevel2Unit())) {
            throw new BusinessException(400, "二级单位不能与库存基本单位相同")
                    .withHintTarget("level2Unit");
        }
        if (hasLevel3Unit && (sameUnit(factoryId, dto.getLevel2Unit(), dto.getLevel3Unit())
                || sameUnit(factoryId, dto.getLevel1Unit(), dto.getLevel3Unit()))) {
            throw new BusinessException(400, "三级单位不能与已有层级单位相同")
                    .withHintTarget("level3Unit");
        }
    }

    private List<MaterialPackagingSpecDTO> requestedSpecs(MaterialPackagingHierarchyDTO dto) {
        if (dto.getPackagingSpecs() != null) {
            return dto.getPackagingSpecs();
        }
        List<MaterialPackagingSpecDTO> specs = new ArrayList<>();
        BigDecimal level1PerLevel2 = positive(dto.getLevel1PerLevel2());
        if (hasText(dto.getLevel2Unit()) && level1PerLevel2 != null) {
            specs.add(new MaterialPackagingSpecDTO(
                    null, "默认包装", dto.getLevel2Unit(), dto.getLevel1Unit(),
                    level1PerLevel2, true, true, 0, null));
        }
        BigDecimal level2PerLevel3 = positive(dto.getLevel2PerLevel3());
        if (hasText(dto.getLevel3Unit()) && level1PerLevel2 != null && level2PerLevel3 != null) {
            specs.add(new MaterialPackagingSpecDTO(
                    null, "包装规格 2", dto.getLevel3Unit(), dto.getLevel1Unit(),
                    level1PerLevel2.multiply(level2PerLevel3), false, true, 1, null));
        }
        return specs;
    }

    private void validatePackagingSpecs(
            String factoryId, String materialBaseUnit, List<MaterialPackagingSpecDTO> specs) {
        Set<String> packageUnits = new HashSet<>();
        for (MaterialPackagingSpecDTO spec : specs) {
            if (spec == null || !hasText(spec.packageUnit()) || !hasText(spec.baseUnit())
                    || positive(spec.conversionFactor()) == null) {
                throw new BusinessException(400, "包装单位、库存基本单位和换算数量必须填写完整")
                        .withCode("MATERIAL_PACKAGING_SPEC_INCOMPLETE");
            }
            if (!sameUnit(factoryId, materialBaseUnit, spec.baseUnit())) {
                throw new BusinessException(400, "包装规格的基本单位必须与原料库存基本单位一致")
                        .withCode("MATERIAL_PACKAGING_SPEC_BASE_UNIT_MISMATCH")
                        .withHintTarget("baseUnit");
            }
            if (sameUnit(factoryId, spec.packageUnit(), spec.baseUnit())) {
                throw new BusinessException(400, "包装单位不能与库存基本单位相同")
                        .withCode("MATERIAL_PACKAGING_SPEC_UNIT_INVALID")
                        .withHintTarget("packageUnit");
            }
            String normalizedPackage = normalizedUnit(factoryId, spec.packageUnit());
            if (!packageUnits.add(normalizedPackage)) {
                throw new BusinessException(409, "同一种包装单位只能配置一条换算规则")
                        .withCode("MATERIAL_PACKAGING_SPEC_DUPLICATE")
                        .withHint("采购订单只记录包装单位，重复单位会导致换算不明确")
                        .withHintTarget("packageUnit");
            }
        }
    }

    private void projectLegacyHierarchy(
            MaterialPackagingHierarchyDTO dto, List<MaterialPackagingSpecDTO> specs) {
        MaterialPackagingSpecDTO first = specs.isEmpty() ? null : specs.get(0);
        MaterialPackagingSpecDTO second = specs.size() < 2 ? null : specs.get(1);
        dto.setLevel1PerLevel2(first == null ? null : first.conversionFactor());
        dto.setLevel2Unit(first == null ? null : first.packageUnit().trim());
        if (first == null || second == null) {
            dto.setLevel2PerLevel3(null);
            dto.setLevel3Unit(null);
            return;
        }
        dto.setLevel2PerLevel3(
                second.conversionFactor()
                        .divide(first.conversionFactor(), MathContext.DECIMAL128)
                        .setScale(4, RoundingMode.HALF_UP));
        dto.setLevel3Unit(second.packageUnit().trim());
    }

    private void replacePackagingSpecs(
            String factoryId, String materialTypeId, List<MaterialPackagingSpecDTO> requested) {
        List<MaterialPackagingSpec> existing = packagingSpecRepository
                .findByFactoryIdAndMaterialTypeIdOrderBySortOrderAscCreatedAtAsc(factoryId, materialTypeId);
        Map<String, MaterialPackagingSpec> existingById = new HashMap<>();
        Map<String, Long> existingVersions = new HashMap<>();
        for (MaterialPackagingSpec spec : existing) {
            existingById.put(spec.getId(), spec);
            existingVersions.put(spec.getId(), spec.getVersion());
            spec.setDefaultSpec(false);
        }
        if (!existing.isEmpty()) {
            packagingSpecRepository.saveAllAndFlush(existing);
        }

        Set<String> retainedIds = new HashSet<>();
        List<MaterialPackagingSpec> saved = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            MaterialPackagingSpecDTO request = requested.get(index);
            MaterialPackagingSpec entity;
            if (hasText(request.id())) {
                entity = existingById.get(request.id());
                if (entity == null) {
                    throw new BusinessException(400, "包装规格不存在或不属于当前原料")
                            .withCode("MATERIAL_PACKAGING_SPEC_INVALID");
                }
                if (request.version() != null
                        && !Objects.equals(request.version(), existingVersions.get(entity.getId()))) {
                    throw new BusinessException(409, "包装规格已被其他人修改，请刷新后重试")
                            .withCode("MATERIAL_PACKAGING_SPEC_VERSION_CONFLICT");
                }
                retainedIds.add(entity.getId());
            } else {
                entity = new MaterialPackagingSpec();
                entity.setVersion(0L);
            }
            entity.setFactoryId(factoryId);
            entity.setMaterialTypeId(materialTypeId);
            entity.setName(hasText(request.name())
                    ? request.name().trim()
                    : (index == 0 ? "默认包装" : "包装规格 " + (index + 1)));
            entity.setPackageUnit(request.packageUnit().trim());
            entity.setBaseUnit(request.baseUnit().trim());
            entity.setConversionFactor(request.conversionFactor());
            entity.setDefaultSpec(index == 0);
            entity.setActive(!Boolean.FALSE.equals(request.active()));
            entity.setSortOrder(index);
            saved.add(entity);
        }
        for (MaterialPackagingSpec spec : existing) {
            if (!retainedIds.contains(spec.getId())) {
                spec.softDelete();
            }
        }
        List<MaterialPackagingSpec> toPersist = new ArrayList<>(existing);
        saved.stream().filter(spec -> spec.getId() == null).forEach(toPersist::add);
        if (!toPersist.isEmpty()) {
            packagingSpecRepository.saveAllAndFlush(toPersist);
        }
    }

    private boolean sameUnit(String factoryId, String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.trim().equalsIgnoreCase(right.trim())) {
            return true;
        }
        return unitContractService != null && unitContractService.areEquivalent(factoryId, left, right);
    }

    private String normalizedUnit(String factoryId, String unit) {
        if (unitContractService != null) {
            com.cretas.aims.service.unit.UnitNormalizationResult normalized =
                    unitContractService.normalize(factoryId, unit);
            if (normalized != null && normalized.recognized()) {
                return normalized.code();
            }
        }
        return unit == null ? "" : unit.trim().toLowerCase();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private BigDecimal positive(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private List<MaterialPackagingSpecDTO> specsFor(MaterialPackagingHierarchy hierarchy) {
        List<MaterialPackagingSpecDTO> specs = packagingSpecRepository
                .findByFactoryIdAndMaterialTypeIdOrderBySortOrderAscCreatedAtAsc(
                        hierarchy.getFactoryId(), hierarchy.getMaterialTypeId())
                .stream()
                .map(this::toSpecDTO)
                .toList();
        if (!specs.isEmpty()) {
            return specs;
        }
        MaterialPackagingHierarchyDTO legacy = MaterialPackagingHierarchyDTO.builder()
                .level1Unit(hierarchy.getLevel1Unit())
                .level1PerLevel2(hierarchy.getLevel1PerLevel2())
                .level2Unit(hierarchy.getLevel2Unit())
                .level2PerLevel3(hierarchy.getLevel2PerLevel3())
                .level3Unit(hierarchy.getLevel3Unit())
                .build();
        return requestedSpecs(legacy);
    }

    private MaterialPackagingSpecDTO toSpecDTO(MaterialPackagingSpec entity) {
        return new MaterialPackagingSpecDTO(
                entity.getId(), entity.getName(), entity.getPackageUnit(), entity.getBaseUnit(),
                entity.getConversionFactor(), entity.getDefaultSpec(), entity.getActive(),
                entity.getSortOrder(), entity.getVersion());
    }

    private MaterialPackagingHierarchyDTO toDTO(MaterialPackagingHierarchy e) {
        return MaterialPackagingHierarchyDTO.builder()
                .id(e.getId())
                .factoryId(e.getFactoryId())
                .materialTypeId(e.getMaterialTypeId())
                .level1Unit(e.getLevel1Unit())
                .level1PerLevel2(e.getLevel1PerLevel2())
                .level2Unit(e.getLevel2Unit())
                .level2PerLevel3(e.getLevel2PerLevel3())
                .level3Unit(e.getLevel3Unit())
                .notes(e.getNotes())
                .packagingSpecs(specsFor(e))
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
