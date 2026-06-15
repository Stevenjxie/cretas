package com.cretas.aims.service.material.impl;

import com.cretas.aims.dto.material.CreateManufacturerRequest;
import com.cretas.aims.dto.material.ManufacturerRegistryDTO;
import com.cretas.aims.dto.material.UpdateManufacturerRequest;
import com.cretas.aims.entity.material.ManufacturerRegistry;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.material.ManufacturerRegistryRepository;
import com.cretas.aims.service.material.ManufacturerRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ManufacturerRegistryServiceImpl implements ManufacturerRegistryService {

    private final ManufacturerRegistryRepository repository;

    @Override
    public List<ManufacturerRegistryDTO> list(String factoryId, boolean activeOnly) {
        List<ManufacturerRegistry> manufacturers = activeOnly
                ? repository.findByFactoryIdAndIsActiveTrueAndDeletedAtIsNullOrderByCodeAsc(factoryId)
                : repository.findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc(factoryId);
        return manufacturers.stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ManufacturerRegistryDTO create(String factoryId, CreateManufacturerRequest request) {
        String code = normalize(request.getCode());
        repository.findByFactoryIdAndCodeAndDeletedAtIsNull(factoryId, code)
                .ifPresent(existing -> {
                    throw duplicateCode(code, existing);
                });

        ManufacturerRegistry entity = new ManufacturerRegistry();
        entity.setId(UUID.randomUUID().toString());
        entity.setFactoryId(factoryId);
        entity.setCode(code);
        entity.setName(normalize(request.getName()));
        entity.setOriginPlace(normalizeNullable(request.getOriginPlace()));
        entity.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
        entity.setRemark(normalizeNullable(request.getRemark()));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return toDTO(repository.save(entity));
    }

    @Override
    @Transactional
    public ManufacturerRegistryDTO update(String factoryId, String id, UpdateManufacturerRequest request) {
        ManufacturerRegistry entity = loadOwned(factoryId, id);

        if (request.getCode() != null) {
            String code = normalize(request.getCode());
            if (!code.equals(entity.getCode())) {
                repository.findByFactoryIdAndCodeAndDeletedAtIsNull(factoryId, code)
                        .filter(existing -> !existing.getId().equals(id))
                        .ifPresent(existing -> {
                            throw duplicateCode(code, existing);
                        });
                entity.setCode(code);
            }
        }
        if (request.getName() != null) entity.setName(normalize(request.getName()));
        if (request.getOriginPlace() != null) entity.setOriginPlace(normalizeNullable(request.getOriginPlace()));
        if (request.getIsActive() != null) entity.setIsActive(request.getIsActive());
        if (request.getRemark() != null) entity.setRemark(normalizeNullable(request.getRemark()));
        entity.setUpdatedAt(LocalDateTime.now());
        return toDTO(repository.save(entity));
    }

    @Override
    @Transactional
    public void delete(String factoryId, String id) {
        ManufacturerRegistry entity = loadOwned(factoryId, id);
        entity.setDeletedAt(LocalDateTime.now());
        repository.save(entity);
    }

    private ManufacturerRegistry loadOwned(String factoryId, String id) {
        ManufacturerRegistry entity = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("厂商登记不存在: id=" + id));
        if (!factoryId.equals(entity.getFactoryId())) {
            throw new BusinessException(403, "无权操作其他工厂的厂商登记");
        }
        return entity;
    }

    private BusinessException duplicateCode(String code, ManufacturerRegistry existing) {
        return new BusinessException(409, "厂号编码 " + code + " 已存在：" + existing.getName()
                + " existingId=" + existing.getId());
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeNullable(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ManufacturerRegistryDTO toDTO(ManufacturerRegistry entity) {
        return ManufacturerRegistryDTO.builder()
                .id(entity.getId())
                .factoryId(entity.getFactoryId())
                .code(entity.getCode())
                .name(entity.getName())
                .originPlace(entity.getOriginPlace())
                .isActive(entity.getIsActive())
                .remark(entity.getRemark())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
