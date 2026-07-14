package com.cretas.aims.service.unit.impl;

import com.cretas.aims.dto.unit.ProductUnitConversionDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.ProductUnitConversionService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProductUnitConversionServiceImpl implements ProductUnitConversionService {

    private final ProductUnitConversionRepository repository;
    private final ProductTypeRepository productTypeRepository;
    private final UnitContractService unitContractService;

    @Override
    @Transactional(readOnly = true)
    public List<ProductUnitConversionDTO> list(String factoryId, String productTypeId) {
        requireProduct(factoryId, productTypeId);
        return repository.findByFactoryIdAndProductTypeIdOrderByCreatedAtAsc(factoryId, productTypeId)
                .stream().map(entity -> toDto(factoryId, entity)).toList();
    }

    @Override
    @Transactional
    public ProductUnitConversionDTO create(
            String factoryId, String productTypeId, ProductUnitConversionDTO request) {
        ProductType product = requireProduct(factoryId, productTypeId);
        ProductUnitConversion entity = new ProductUnitConversion();
        entity.setFactoryId(factoryId);
        entity.setProductTypeId(productTypeId);
        apply(factoryId, entity, request);
        entity = repository.saveAndFlush(entity);
        validateGraph(factoryId, productTypeId);
        if (isLegacyNetContent(product, entity) && isEffectiveAt(entity, LocalDateTime.now())) {
            refreshLegacyNetContent(product);
        }
        return toDto(factoryId, entity);
    }

    @Override
    @Transactional
    public ProductUnitConversionDTO update(
            String factoryId, String productTypeId, String id, ProductUnitConversionDTO request) {
        ProductType product = requireProduct(factoryId, productTypeId);
        ProductUnitConversion entity = requireConversion(factoryId, productTypeId, id);
        requireVersion(request.version(), entity.getVersion());
        boolean previouslyProjected = isLegacyNetContent(product, entity)
                && isEffectiveAt(entity, LocalDateTime.now());
        apply(factoryId, entity, request);
        entity = repository.saveAndFlush(entity);
        validateGraph(factoryId, productTypeId);
        if (previouslyProjected
                || (isLegacyNetContent(product, entity) && isEffectiveAt(entity, LocalDateTime.now()))) {
            refreshLegacyNetContent(product);
        }
        return toDto(factoryId, entity);
    }

    @Override
    @Transactional
    public void delete(String factoryId, String productTypeId, String id, Long version) {
        ProductType product = requireProduct(factoryId, productTypeId);
        ProductUnitConversion entity = requireConversion(factoryId, productTypeId, id);
        requireVersion(version, entity.getVersion());
        boolean previouslyProjected = isLegacyNetContent(product, entity)
                && isEffectiveAt(entity, LocalDateTime.now());
        entity.softDelete();
        repository.saveAndFlush(entity);
        validateGraph(factoryId, productTypeId);
        if (previouslyProjected) refreshLegacyNetContent(product);
    }

    private void apply(
            String factoryId, ProductUnitConversion entity, ProductUnitConversionDTO request) {
        if (request == null || request.factor() == null || request.factor().signum() <= 0) {
            throw new BusinessException(400, "换算系数必须大于 0").withCode("INVALID_UNIT_FACTOR");
        }
        UnitNormalizationResult from = unitContractService.normalize(factoryId, request.fromUnitCode());
        UnitNormalizationResult to = unitContractService.normalize(factoryId, request.toUnitCode());
        if (!from.recognized() || !to.recognized()) {
            throw new BusinessException(400, "换算关系包含未知或冲突单位").withCode("UNKNOWN_UNIT");
        }
        if (from.code().equals(to.code())) {
            throw new BusinessException(400, "源单位和目标单位不能相同").withCode("IDENTITY_RELATION");
        }
        if (request.effectiveTo() != null && request.effectiveFrom() != null
                && !request.effectiveTo().isAfter(request.effectiveFrom())) {
            throw new BusinessException(400, "失效时间必须晚于生效时间").withCode("INVALID_EFFECTIVE_RANGE");
        }
        entity.setFromUnitCode(from.code());
        entity.setToUnitCode(to.code());
        entity.setFactor(request.factor());
        entity.setSourceType(request.sourceType() == null
                ? ProductUnitConversion.SourceType.MANUAL : request.sourceType());
        entity.setPrimarySalesConversion(Boolean.TRUE.equals(request.primarySalesConversion()));
        entity.setEffectiveFrom(request.effectiveFrom() == null ? LocalDateTime.now() : request.effectiveFrom());
        entity.setEffectiveTo(request.effectiveTo());
    }

    private void validateGraph(String factoryId, String productTypeId) {
        List<ProductUnitConversion> relations = repository
                .findByFactoryIdAndProductTypeIdOrderByCreatedAtAsc(factoryId, productTypeId);
        Set<LocalDateTime> boundaries = new LinkedHashSet<>();
        boundaries.add(LocalDateTime.now());
        for (ProductUnitConversion relation : relations) {
            if (relation.getEffectiveFrom() != null) boundaries.add(relation.getEffectiveFrom());
            if (relation.getEffectiveTo() != null) boundaries.add(relation.getEffectiveTo());
        }
        for (LocalDateTime boundary : boundaries) {
            List<String> errors = unitContractService.validateConversionGraph(factoryId, productTypeId, boundary);
            if (!errors.isEmpty()) {
                throw new BusinessException(409, String.join("；", errors))
                        .withCode("INCONSISTENT_UNIT_GRAPH");
            }
        }
    }

    private ProductType requireProduct(String factoryId, String productTypeId) {
        return productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "产品不存在或不属于当前工厂")
                        .withCode("PRODUCT_NOT_FOUND"));
    }

    private ProductUnitConversion requireConversion(
            String factoryId, String productTypeId, String id) {
        return repository.findByIdAndFactoryIdAndProductTypeId(id, factoryId, productTypeId)
                .orElseThrow(() -> new BusinessException(404, "产品单位换算关系不存在")
                        .withCode("UNIT_CONVERSION_NOT_FOUND"));
    }

    private void requireVersion(Long submitted, Long current) {
        if (submitted == null || !submitted.equals(current)) {
            throw new BusinessException(409, "单位换算关系已被其他用户修改，请刷新后重试")
                    .withCode("STALE_UNIT_CONVERSION");
        }
    }

    private void refreshLegacyNetContent(ProductType product) {
        LocalDateTime now = LocalDateTime.now();
        List<ProductUnitConversion> candidates = repository
                .findEffectiveByFactoryIdAndProductTypeIdAt(product.getFactoryId(), product.getId(), now)
                .stream()
                .filter(entity -> isLegacyNetContent(product, entity))
                .toList();
        BigDecimal projected = candidates.size() == 1 ? candidates.get(0).getFactor() : null;
        if ((product.getGramsPerUnit() == null && projected == null)
                || (product.getGramsPerUnit() != null && projected != null
                && product.getGramsPerUnit().compareTo(projected) == 0)) {
            return;
        }
        product.setGramsPerUnit(projected);
        productTypeRepository.save(product);
    }

    private boolean isLegacyNetContent(ProductType product, ProductUnitConversion entity) {
        if (entity.getSourceType() != ProductUnitConversion.SourceType.NET_CONTENT
                || !"g".equals(entity.getToUnitCode())) {
            return false;
        }
        UnitNormalizationResult productUnit = unitContractService.normalize(product.getFactoryId(), product.getUnit());
        return productUnit.recognized()
                && List.of("pcs", "portion", "box").contains(productUnit.code())
                && productUnit.code().equals(entity.getFromUnitCode());
    }

    private boolean isEffectiveAt(ProductUnitConversion entity, LocalDateTime at) {
        return entity.getDeletedAt() == null
                && entity.getEffectiveFrom() != null
                && !entity.getEffectiveFrom().isAfter(at)
                && (entity.getEffectiveTo() == null || entity.getEffectiveTo().isAfter(at));
    }

    private ProductUnitConversionDTO toDto(String factoryId, ProductUnitConversion entity) {
        CanonicalUnit from = unitContractService.describe(factoryId, entity.getFromUnitCode()).orElse(null);
        CanonicalUnit to = unitContractService.describe(factoryId, entity.getToUnitCode()).orElse(null);
        return new ProductUnitConversionDTO(
                entity.getId(), entity.getProductTypeId(), entity.getFromUnitCode(),
                from == null ? entity.getFromUnitCode() : from.displayName(),
                from == null ? null : from.dimension(),
                entity.getToUnitCode(),
                to == null ? entity.getToUnitCode() : to.displayName(),
                to == null ? null : to.dimension(),
                entity.getFactor(), entity.getSourceType(), entity.getPrimarySalesConversion(),
                entity.getEffectiveFrom(), entity.getEffectiveTo(), entity.getVersion());
    }
}
