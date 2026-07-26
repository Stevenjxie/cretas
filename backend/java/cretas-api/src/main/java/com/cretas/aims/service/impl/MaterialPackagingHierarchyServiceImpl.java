package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.MaterialPackagingHierarchyDTO;
import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.MaterialPackagingHierarchyService;
import com.cretas.aims.service.unit.UnitContractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
        validateLevels(factoryId, materialType.getUnit(), dto);

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
        log.info("upsert 包装层级: factoryId={}, materialTypeId={}, levels={}/{}/{}",
                factoryId, materialTypeId, saved.getLevel1Unit(), saved.getLevel2Unit(), saved.getLevel3Unit());
        return toDTO(saved);
    }

    @Override
    @Transactional
    public void deleteByMaterialTypeId(String factoryId, String materialTypeId) {
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

    private boolean sameUnit(String factoryId, String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.trim().equalsIgnoreCase(right.trim())) {
            return true;
        }
        return unitContractService != null && unitContractService.areEquivalent(factoryId, left, right);
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
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
