package com.cretas.aims.service.unit;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.unit.impl.ProductSpecificationConversionSyncServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductSpecificationConversionSyncServiceTest {

    @Mock ProductUnitConversionRepository repository;
    @Mock UnitContractService unitContractService;

    private ProductSpecificationConversionSyncServiceImpl service;
    private ProductType product;
    private List<ProductUnitConversion> stored;

    @BeforeEach
    void setUp() {
        service = new ProductSpecificationConversionSyncServiceImpl(repository, unitContractService);
        product = new ProductType();
        product.setId("P1");
        product.setFactoryId("F1");
        product.setUnit("件");
        stored = new ArrayList<>();
        when(repository.findByFactoryIdAndProductTypeIdOrderByCreatedAtAsc("F1", "P1"))
                .thenAnswer(invocation -> new ArrayList<>(stored));
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            ProductUnitConversion relation = invocation.getArgument(0);
            if (!stored.contains(relation)) stored.add(relation);
            return relation;
        });
        lenient().when(unitContractService.validateConversionGraph(eq("F1"), eq("P1"), any()))
                .thenReturn(List.of());
    }

    @Test
    void projectsStandardWeightToExplicitNetContentRelation() {
        canonical("件", "pcs", UnitDimension.COUNT);
        canonical(null, null, null);
        product.setGramsPerUnit(new BigDecimal("200"));

        assertTrue(service.synchronize(product));

        ProductUnitConversion relation = saved(ProductUnitConversion.SourceType.NET_CONTENT);
        assertEquals("pcs", relation.getFromUnitCode());
        assertEquals("g", relation.getToUnitCode());
        assertEquals(0, new BigDecimal("200").compareTo(relation.getFactor()));
        assertTrue(relation.getPrimarySalesConversion());
    }

    @Test
    void projectsPackagingCoefficientFromOuterToBaseUnit() {
        canonical("件", "pcs", UnitDimension.COUNT);
        canonical("箱", "case", UnitDimension.PACKAGE);
        product.setLevel1Unit("箱");
        product.setBoxConversionCoefficient(new BigDecimal("20"));

        assertTrue(service.synchronize(product));

        ProductUnitConversion relation = saved(ProductUnitConversion.SourceType.PACKAGING);
        assertEquals("case", relation.getFromUnitCode());
        assertEquals("pcs", relation.getToUnitCode());
        assertEquals(0, new BigDecimal("20").compareTo(relation.getFactor()));
    }

    @Test
    void updatesExistingGeneratedRelationInsteadOfDuplicatingIt() {
        canonical("件", "pcs", UnitDimension.COUNT);
        canonical(null, null, null);
        ProductUnitConversion existing = relation(
                ProductUnitConversion.SourceType.NET_CONTENT, "pcs", "g", "180");
        stored.add(existing);
        product.setGramsPerUnit(new BigDecimal("200"));

        assertTrue(service.synchronize(product));

        assertEquals(1, stored.size());
        assertSame(existing, saved(ProductUnitConversion.SourceType.NET_CONTENT));
        assertEquals(0, new BigDecimal("200").compareTo(existing.getFactor()));
    }

    @Test
    void clearingStandardWeightRetiresItsGeneratedRelation() {
        canonical("件", "pcs", UnitDimension.COUNT);
        canonical(null, null, null);
        ProductUnitConversion existing = relation(
                ProductUnitConversion.SourceType.NET_CONTENT, "pcs", "g", "200");
        stored.add(existing);

        assertTrue(service.synchronize(product));

        assertNotNull(existing.getDeletedAt());
    }

    @Test
    void manualRelationOnSpecificationPairIsNeverOverwritten() {
        canonical("件", "pcs", UnitDimension.COUNT);
        canonical(null, null, null);
        ProductUnitConversion manual = relation(
                ProductUnitConversion.SourceType.MANUAL, "pcs", "g", "180");
        stored.add(manual);
        product.setGramsPerUnit(new BigDecimal("200"));

        com.cretas.aims.exception.BusinessException error = assertThrows(
                com.cretas.aims.exception.BusinessException.class,
                () -> service.synchronize(product));

        assertEquals("SKU_SPEC_CONVERSION_CONFLICT", error.getErrorCode());
        assertEquals(ProductUnitConversion.SourceType.MANUAL, manual.getSourceType());
        assertEquals(0, new BigDecimal("180").compareTo(manual.getFactor()));
    }

    @Test
    void massPrimaryUnitDoesNotCreateIdentityNetContentRelation() {
        product.setUnit("g");
        product.setGramsPerUnit(new BigDecimal("200"));
        canonical("g", "g", UnitDimension.MASS);
        canonical(null, null, null);

        assertFalse(service.synchronize(product));
        verify(repository, never()).saveAndFlush(any());
    }

    private void canonical(String raw, String code, UnitDimension dimension) {
        CanonicalUnit unit = code == null ? null
                : new CanonicalUnit(code, dimension, code, BigDecimal.ONE, code, 2);
        lenient().when(unitContractService.normalize("F1", raw))
                .thenReturn(new UnitNormalizationResult(raw, code, unit));
    }

    private ProductUnitConversion saved(ProductUnitConversion.SourceType sourceType) {
        return stored.stream().filter(row -> row.getSourceType() == sourceType
                && row.getDeletedAt() == null).findFirst().orElseThrow();
    }

    private ProductUnitConversion relation(
            ProductUnitConversion.SourceType sourceType,
            String from,
            String to,
            String factor) {
        ProductUnitConversion relation = new ProductUnitConversion();
        relation.setId("C" + (stored.size() + 1));
        relation.setFactoryId("F1");
        relation.setProductTypeId("P1");
        relation.setFromUnitCode(from);
        relation.setToUnitCode(to);
        relation.setFactor(new BigDecimal(factor));
        relation.setSourceType(sourceType);
        relation.setPrimarySalesConversion(false);
        return relation;
    }
}
