package com.cretas.aims.service.unit;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.dto.unit.ProductUnitConversionDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.unit.impl.ProductUnitConversionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductUnitConversionServiceTest {

    @Mock ProductUnitConversionRepository repository;
    @Mock ProductTypeRepository productTypeRepository;
    @Mock UnitContractService unitContractService;

    private ProductUnitConversionServiceImpl service;
    private ProductType product;

    @BeforeEach
    void setUp() {
        service = new ProductUnitConversionServiceImpl(repository, productTypeRepository, unitContractService);
        product = new ProductType();
        product.setId("P1");
        product.setFactoryId("F1");
        product.setUnit("件");
        lenient().when(productTypeRepository.findByIdAndFactoryId("P1", "F1"))
                .thenReturn(Optional.of(product));
    }

    @Test
    void createNetContentCanonicalizesAndWritesLegacyGrams() {
        canonical("件", "pcs", UnitDimension.COUNT);
        canonical("g", "g", UnitDimension.MASS);
        when(unitContractService.validateConversionGraph(eq("F1"), eq("P1"), any()))
                .thenReturn(List.of());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            ProductUnitConversion entity = invocation.getArgument(0);
            entity.setId("C1");
            entity.setVersion(0L);
            return entity;
        });

        ProductUnitConversionDTO result = service.create("F1", "P1", request(
                "件", "g", "200", ProductUnitConversion.SourceType.NET_CONTENT, null));

        assertEquals("pcs", result.fromUnitCode());
        assertEquals("g", result.toUnitCode());
        assertEquals(0, new BigDecimal("200").compareTo(product.getGramsPerUnit()));
        verify(productTypeRepository).save(product);
    }

    @Test
    void massPrimaryUnitNeverBackfillsCountAssumption() {
        product.setUnit("g");
        canonical("件", "pcs", UnitDimension.COUNT);
        canonical("g", "g", UnitDimension.MASS);
        when(unitContractService.validateConversionGraph(eq("F1"), eq("P1"), any()))
                .thenReturn(List.of());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            ProductUnitConversion entity = invocation.getArgument(0);
            entity.setId("C1");
            entity.setVersion(0L);
            return entity;
        });

        service.create("F1", "P1", request(
                "件", "g", "200", ProductUnitConversion.SourceType.NET_CONTENT, null));

        assertNull(product.getGramsPerUnit());
        verify(productTypeRepository, never()).save(product);
    }

    @Test
    void staleUpdateFailsClosed() {
        ProductUnitConversion entity = new ProductUnitConversion();
        entity.setId("C1");
        entity.setFactoryId("F1");
        entity.setProductTypeId("P1");
        entity.setVersion(4L);
        when(repository.findByIdAndFactoryIdAndProductTypeId("C1", "F1", "P1"))
                .thenReturn(Optional.of(entity));

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(
                "F1", "P1", "C1", request("pcs", "g", "200",
                        ProductUnitConversion.SourceType.NET_CONTENT, 3L)));

        assertEquals("STALE_UNIT_CONVERSION", error.getErrorCode());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void productTypeDtoTracksExplicitNullForLegacyFields() {
        ProductTypeDTO dto = new ProductTypeDTO();
        assertFalse(dto.isGramsPerUnitPresent());
        assertFalse(dto.isBoxConversionCoefficientPresent());
        dto.setGramsPerUnit(null);
        dto.setBoxConversionCoefficient(null);
        assertTrue(dto.isGramsPerUnitPresent());
        assertTrue(dto.isBoxConversionCoefficientPresent());
    }

    private void canonical(String raw, String code, UnitDimension dimension) {
        CanonicalUnit unit = new CanonicalUnit(code, dimension, code, BigDecimal.ONE, code, 2);
        when(unitContractService.normalize("F1", raw))
                .thenReturn(new UnitNormalizationResult(raw, code, unit));
        when(unitContractService.describe("F1", code)).thenReturn(Optional.of(unit));
    }

    private ProductUnitConversionDTO request(
            String from, String to, String factor, ProductUnitConversion.SourceType source, Long version) {
        return new ProductUnitConversionDTO(null, "P1", from, null, null, to, null, null,
                new BigDecimal(factor), source, false, LocalDateTime.now().minusMinutes(1), null, version);
    }
}
