package com.cretas.aims.service.unit.impl;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.unit.ProductSpecificationConversionSyncService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Keeps legacy SKU specification fields and the explicit conversion graph atomic. */
@Service
@RequiredArgsConstructor
public class ProductSpecificationConversionSyncServiceImpl
        implements ProductSpecificationConversionSyncService {

    private final ProductUnitConversionRepository repository;
    private final UnitContractService unitContractService;

    @Override
    @Transactional
    public boolean synchronize(ProductType product) {
        if (product == null || product.getId() == null || product.getFactoryId() == null) {
            return false;
        }
        UnitNormalizationResult base = unitContractService.normalize(
                product.getFactoryId(), product.getUnit());
        List<ProductUnitConversion> relations = repository
                .findByFactoryIdAndProductTypeIdOrderByCreatedAtAsc(
                        product.getFactoryId(), product.getId());

        boolean changed = synchronizeNetContent(product, base, relations);
        relations = repository.findByFactoryIdAndProductTypeIdOrderByCreatedAtAsc(
                product.getFactoryId(), product.getId());
        changed |= synchronizePackaging(product, base, relations);

        if (changed) {
            List<String> errors = unitContractService.validateConversionGraph(
                    product.getFactoryId(), product.getId(), LocalDateTime.now());
            if (!errors.isEmpty()) {
                throw new BusinessException(409, String.join("; ", errors))
                        .withCode("INCONSISTENT_UNIT_GRAPH");
            }
        }
        return changed;
    }

    private boolean synchronizeNetContent(
            ProductType product,
            UnitNormalizationResult base,
            List<ProductUnitConversion> relations) {
        boolean countLike = base.recognized()
                && (base.unit().dimension() == UnitDimension.COUNT
                || base.unit().dimension() == UnitDimension.PACKAGE);
        BigDecimal grams = positive(product.getGramsPerUnit());
        if (!countLike || grams == null || "g".equals(base.code())) {
            return retireOpenEnded(relations, ProductUnitConversion.SourceType.NET_CONTENT);
        }
        return upsert(product, relations, ProductUnitConversion.SourceType.NET_CONTENT,
                base.code(), "g", grams, true);
    }

    private boolean synchronizePackaging(
            ProductType product,
            UnitNormalizationResult base,
            List<ProductUnitConversion> relations) {
        UnitNormalizationResult level1 = unitContractService.normalize(
                product.getFactoryId(), product.getLevel1Unit());
        BigDecimal coefficient = positive(product.getBoxConversionCoefficient());
        if (!base.recognized() || !level1.recognized() || coefficient == null
                || Objects.equals(base.code(), level1.code())) {
            return retireOpenEnded(relations, ProductUnitConversion.SourceType.PACKAGING);
        }
        return upsert(product, relations, ProductUnitConversion.SourceType.PACKAGING,
                level1.code(), base.code(), coefficient, false);
    }

    private boolean upsert(
            ProductType product,
            List<ProductUnitConversion> relations,
            ProductUnitConversion.SourceType sourceType,
            String from,
            String to,
            BigDecimal factor,
            boolean primarySalesConversion) {
        ProductUnitConversion occupied = relations.stream()
                .filter(this::isOpenEnded)
                .filter(relation -> from.equals(relation.getFromUnitCode())
                        && to.equals(relation.getToUnitCode()))
                .findFirst().orElse(null);
        if (occupied != null && occupied.getSourceType() != sourceType) {
            throw new BusinessException(409,
                    "SKU 规格与现有手工单位换算冲突，请先核对并删除冲突换算")
                    .withCode("SKU_SPEC_CONVERSION_CONFLICT");
        }
        ProductUnitConversion exact = occupied;
        boolean changed = false;
        for (ProductUnitConversion relation : relations) {
            if (relation != exact && relation.getSourceType() == sourceType && isOpenEnded(relation)) {
                relation.softDelete();
                repository.saveAndFlush(relation);
                changed = true;
            }
        }
        if (exact == null) {
            exact = new ProductUnitConversion();
            exact.setFactoryId(product.getFactoryId());
            exact.setProductTypeId(product.getId());
            exact.setFromUnitCode(from);
            exact.setToUnitCode(to);
            exact.setEffectiveFrom(LocalDateTime.now());
            exact.setVersion(0L);
            changed = true;
        }
        if (exact.getFactor() == null || exact.getFactor().compareTo(factor) != 0
                || exact.getSourceType() != sourceType
                || !Objects.equals(exact.getPrimarySalesConversion(), primarySalesConversion)
                || exact.getEffectiveTo() != null) {
            exact.setFactor(factor);
            exact.setSourceType(sourceType);
            exact.setPrimarySalesConversion(primarySalesConversion);
            exact.setEffectiveTo(null);
            changed = true;
        }
        if (changed) repository.saveAndFlush(exact);
        return changed;
    }

    private boolean retireOpenEnded(
            List<ProductUnitConversion> relations,
            ProductUnitConversion.SourceType sourceType) {
        boolean changed = false;
        for (ProductUnitConversion relation : relations) {
            if (relation.getSourceType() == sourceType && isOpenEnded(relation)) {
                relation.softDelete();
                repository.saveAndFlush(relation);
                changed = true;
            }
        }
        return changed;
    }

    private boolean isOpenEnded(ProductUnitConversion relation) {
        return relation.getDeletedAt() == null && relation.getEffectiveTo() == null;
    }

    private BigDecimal positive(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }
}
