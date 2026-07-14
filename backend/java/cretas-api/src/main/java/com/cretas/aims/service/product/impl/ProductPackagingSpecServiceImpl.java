package com.cretas.aims.service.product.impl;

import com.cretas.aims.dto.producttype.ProductPackagingSpecDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.product.ProductPackagingSpec;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.product.ProductPackagingSpecRepository;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProductPackagingSpecServiceImpl implements ProductPackagingSpecService {

    private final ProductPackagingSpecRepository repository;
    private final ProductTypeRepository productTypeRepository;
    private final UnitContractService unitContractService;

    @Override
    @Transactional(readOnly = true)
    public List<ProductPackagingSpecDTO> list(String factoryId, String productTypeId) {
        productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "产品不存在或不属于当前工厂")
                        .withCode("PRODUCT_NOT_FOUND"));
        return repository.findByFactoryIdAndProductTypeIdOrderBySortOrderAscCreatedAtAsc(factoryId, productTypeId)
                .stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public List<ProductPackagingSpecDTO> replace(
            ProductType product, List<ProductPackagingSpecDTO> requestedSpecs) {
        if (product == null || product.getId() == null || product.getFactoryId() == null) {
            throw new BusinessException(400, "产品不存在，不能保存包装规格");
        }
        List<ProductPackagingSpecDTO> normalized = requestedSpecs == null ? List.of() : requestedSpecs;
        validateRequested(product, normalized);

        List<ProductPackagingSpec> existing = repository
                .findByFactoryIdAndProductTypeIdOrderBySortOrderAscCreatedAtAsc(
                        product.getFactoryId(), product.getId());
        Map<String, ProductPackagingSpec> existingById = new HashMap<>();
        Map<String, Long> existingVersions = new HashMap<>();
        for (ProductPackagingSpec spec : existing) {
            existingById.put(spec.getId(), spec);
            existingVersions.put(spec.getId(), spec.getVersion());
            // Demote first so the partial unique index never sees two active defaults.
            spec.setDefaultSpec(false);
        }
        if (!existing.isEmpty()) repository.saveAllAndFlush(existing);

        Set<String> retainedIds = new HashSet<>();
        List<ProductPackagingSpec> saved = new ArrayList<>();
        for (int index = 0; index < normalized.size(); index++) {
            ProductPackagingSpecDTO request = normalized.get(index);
            ProductPackagingSpec entity;
            if (request.id() != null && !request.id().isBlank()) {
                entity = existingById.get(request.id());
                if (entity == null) {
                    throw new BusinessException(400, "包装规格不存在或不属于当前产品")
                            .withCode("PACKAGING_SPEC_INVALID");
                }
                if (request.version() != null
                        && !Objects.equals(request.version(), existingVersions.get(entity.getId()))) {
                    throw new BusinessException(409, "包装规格已被其他人修改，请刷新后重试")
                            .withCode("PACKAGING_SPEC_VERSION_CONFLICT");
                }
                retainedIds.add(entity.getId());
            } else {
                entity = new ProductPackagingSpec();
                entity.setVersion(0L);
            }
            entity.setFactoryId(product.getFactoryId());
            entity.setProductTypeId(product.getId());
            entity.setName(defaultName(request, index));
            entity.setPackageUnit(request.packageUnit().trim());
            entity.setBaseUnit(request.baseUnit().trim());
            entity.setConversionFactor(request.conversionFactor());
            entity.setDefaultSpec(index == 0);
            entity.setActive(!Boolean.FALSE.equals(request.active()));
            entity.setSortOrder(index);
            saved.add(entity);
        }
        for (ProductPackagingSpec spec : existing) {
            if (!retainedIds.contains(spec.getId())) spec.softDelete();
        }
        List<ProductPackagingSpec> toPersist = new ArrayList<>(existing);
        for (ProductPackagingSpec spec : saved) {
            if (spec.getId() == null) toPersist.add(spec);
        }
        if (!toPersist.isEmpty()) repository.saveAllAndFlush(toPersist);
        projectDefault(product, saved.stream().filter(spec -> Boolean.TRUE.equals(spec.getActive())).toList());
        return saved.stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public void synchronizeLegacyDefault(ProductType product) {
        if (product == null || product.getId() == null) return;
        List<ProductPackagingSpec> specs = repository
                .findByFactoryIdAndProductTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                        product.getFactoryId(), product.getId());
        if (specs.size() > 1) return;
        BigDecimal factor = positive(product.getBoxConversionCoefficient());
        if (product.getLevel1Unit() == null || product.getLevel1Unit().isBlank()
                || product.getUnit() == null || product.getUnit().isBlank() || factor == null) {
            if (specs.size() == 1) {
                ProductPackagingSpec only = specs.get(0);
                only.softDelete();
                repository.save(only);
            }
            return;
        }
        ProductPackagingSpec spec = specs.isEmpty() ? new ProductPackagingSpec() : specs.get(0);
        spec.setFactoryId(product.getFactoryId());
        spec.setProductTypeId(product.getId());
        spec.setName("默认箱规");
        spec.setPackageUnit(product.getLevel1Unit().trim());
        spec.setBaseUnit(product.getUnit().trim());
        spec.setConversionFactor(factor);
        spec.setDefaultSpec(true);
        spec.setActive(true);
        spec.setSortOrder(0);
        if (spec.getVersion() == null) spec.setVersion(0L);
        repository.save(spec);
    }

    @Override
    @Transactional(readOnly = true)
    public PackagingSelection resolveSelection(
            String factoryId, String productTypeId, String transactionUnit, String packagingSpecId) {
        List<ProductPackagingSpec> active = repository
                .findByFactoryIdAndProductTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(factoryId, productTypeId);
        if (active.isEmpty() && (packagingSpecId == null || packagingSpecId.isBlank())) {
            return PackagingSelection.none();
        }

        String transactionCode = normalizedCode(factoryId, transactionUnit);
        if (packagingSpecId != null && !packagingSpecId.isBlank()) {
            ProductPackagingSpec selected = repository
                    .findByIdAndFactoryIdAndProductTypeIdAndActiveTrue(packagingSpecId, factoryId, productTypeId)
                    .orElseThrow(() -> new BusinessException(400, "所选包装规格不存在或已停用")
                            .withCode("PACKAGING_SPEC_INVALID")
                            .withHintTarget("packagingSpecId"));
            if (!Objects.equals(normalizedCode(factoryId, selected.getPackageUnit()), transactionCode)) {
                throw new BusinessException(400, "所选包装规格与当前单位不一致")
                        .withCode("PACKAGING_SPEC_UNIT_MISMATCH")
                        .withHintTarget("packagingSpecId");
            }
            long sameUnitCount = active.stream()
                    .filter(spec -> Objects.equals(
                            normalizedCode(factoryId, spec.getPackageUnit()), transactionCode))
                    .count();
            return new PackagingSelection(selected, sameUnitCount > 1);
        }
        List<ProductPackagingSpec> matching = active.stream()
                .filter(spec -> Objects.equals(normalizedCode(factoryId, spec.getPackageUnit()), transactionCode))
                .toList();
        if (matching.isEmpty()) return PackagingSelection.none();

        if (matching.size() > 1) {
            throw new BusinessException(400, "该产品有多个装箱规格，请选择本次使用的包装规格")
                    .withCode("PACKAGING_SPEC_REQUIRED")
                    .withHint("请选择例如“1箱=12盒”或“1箱=24盒”后再提交")
                    .withHintTarget("packagingSpecId");
        }
        return new PackagingSelection(matching.get(0), false);
    }

    private void validateRequested(ProductType product, List<ProductPackagingSpecDTO> specs) {
        Set<String> identities = new HashSet<>();
        for (ProductPackagingSpecDTO spec : specs) {
            if (spec == null || spec.packageUnit() == null || spec.packageUnit().isBlank()
                    || spec.baseUnit() == null || spec.baseUnit().isBlank()
                    || positive(spec.conversionFactor()) == null) {
                throw new BusinessException(400, "包装单位、基础单位和换算数量必须填写完整")
                        .withCode("PACKAGING_SPEC_INCOMPLETE");
            }
            String from = normalizedCode(product.getFactoryId(), spec.packageUnit());
            String to = normalizedCode(product.getFactoryId(), spec.baseUnit());
            if (from == null || to == null || from.equals(to)) {
                throw new BusinessException(400, "包装单位和基础单位必须是两个不同的有效单位")
                        .withCode("PACKAGING_SPEC_UNIT_INVALID");
            }
            String productBase = normalizedCode(product.getFactoryId(), product.getUnit());
            if (!Objects.equals(productBase, to)) {
                throw new BusinessException(400, "包装规格的基础单位必须与产品基本单位一致")
                        .withCode("PACKAGING_SPEC_BASE_UNIT_MISMATCH");
            }
            String identity = from + "->" + to + "@" + spec.conversionFactor().stripTrailingZeros();
            if (!identities.add(identity)) {
                throw new BusinessException(409, "存在重复的包装规格")
                        .withCode("PACKAGING_SPEC_DUPLICATE");
            }
        }
    }

    private void projectDefault(ProductType product, List<ProductPackagingSpec> active) {
        ProductPackagingSpec defaultSpec = active.stream()
                .filter(ProductPackagingSpec::getDefaultSpec)
                .findFirst().orElse(active.isEmpty() ? null : active.get(0));
        product.setLevel1Unit(defaultSpec == null ? null : defaultSpec.getPackageUnit());
        product.setBoxConversionCoefficient(defaultSpec == null ? null : defaultSpec.getConversionFactor());
        productTypeRepository.save(product);
    }

    private String normalizedCode(String factoryId, String unit) {
        UnitNormalizationResult result = unitContractService.normalize(factoryId, unit);
        return result.recognized() ? result.code() : null;
    }

    private String defaultName(ProductPackagingSpecDTO request, int index) {
        if (request.name() != null && !request.name().isBlank()) return request.name().trim();
        return index == 0 ? "默认箱规" : "箱规" + (index + 1);
    }

    private BigDecimal positive(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private ProductPackagingSpecDTO toDto(ProductPackagingSpec entity) {
        return new ProductPackagingSpecDTO(
                entity.getId(), entity.getName(), entity.getPackageUnit(), entity.getBaseUnit(),
                entity.getConversionFactor(), entity.getDefaultSpec(), entity.getActive(),
                entity.getSortOrder(), entity.getVersion());
    }
}
