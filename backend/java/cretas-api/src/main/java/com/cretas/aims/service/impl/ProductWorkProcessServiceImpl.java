package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ProductWorkProcessDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.ProductWorkProcessService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductWorkProcessServiceImpl implements ProductWorkProcessService {

    private static final Logger log = LoggerFactory.getLogger(ProductWorkProcessServiceImpl.class);
    private final ProductWorkProcessRepository repository;
    private final WorkProcessRepository workProcessRepository;

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

        // Normalize -1L sentinel to null so it never lands in DB via create
        Long rw = dto.getResponsibleWorkerId();
        Long normalizedRw = (rw != null && rw == -1L) ? null : rw;

        ProductWorkProcess entity = ProductWorkProcess.builder()
                .factoryId(factoryId)
                .productTypeId(dto.getProductTypeId())
                .workProcessId(dto.getWorkProcessId())
                .processOrder(dto.getProcessOrder() != null ? dto.getProcessOrder() : 0)
                .unitOverride(dto.getUnitOverride())
                .estimatedMinutesOverride(dto.getEstimatedMinutesOverride())
                .responsibleWorkerId(normalizedRw)
                .build();

        ProductWorkProcess saved = repository.save(entity);
        return toDTO(saved, null);
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

        return associations.stream()
                .map(a -> toDTO(a, wpMap.get(a.getWorkProcessId())))
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

        // Three-state responsibleWorkerId:
        //   null        → no change (partial update — leave existing value alone)
        //   -1L         → clear (set null; sentinel must never persist as a real user id)
        //   positive id → set
        Long rw = dto.getResponsibleWorkerId();
        if (rw != null) {
            entity.setResponsibleWorkerId(rw == -1L ? null : rw);
        }

        ProductWorkProcess saved = repository.save(entity);
        return toDTO(saved, null);
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

    private ProductWorkProcessDTO toDTO(ProductWorkProcess entity, WorkProcess wp) {
        ProductWorkProcessDTO.ProductWorkProcessDTOBuilder builder = ProductWorkProcessDTO.builder()
                .id(entity.getId())
                .productTypeId(entity.getProductTypeId())
                .workProcessId(entity.getWorkProcessId())
                .processOrder(entity.getProcessOrder())
                .unitOverride(entity.getUnitOverride())
                .estimatedMinutesOverride(entity.getEstimatedMinutesOverride())
                .responsibleWorkerId(entity.getResponsibleWorkerId())
                .isActive(entity.getIsActive())
                .createdAt(entity.getCreatedAt());

        if (wp != null) {
            builder.processName(wp.getProcessName())
                    .processCategory(wp.getProcessCategory())
                    .defaultUnit(wp.getUnit())
                    .defaultEstimatedMinutes(wp.getEstimatedMinutes());
        }

        return builder.build();
    }
}
