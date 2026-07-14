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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductUnitConversionServiceTest {

    @Mock ProductUnitConversionRepository repository;
    @Mock ProductTypeRepository productTypeRepository;
    @Mock UnitContractService unitContractService;
    @Mock com.cretas.aims.service.workflow.WorkflowUnitReviewService workflowUnitReviewService;

    private ProductUnitConversionServiceImpl service;
    private ProductType product;
    private AtomicReference<ProductUnitConversion> lastSaved;

    @BeforeEach
    void setUp() {
        service = new ProductUnitConversionServiceImpl(
                repository, productTypeRepository, unitContractService, workflowUnitReviewService);
        product = new ProductType();
        product.setId("P1");
        product.setFactoryId("F1");
        product.setUnit("件");
        lastSaved = new AtomicReference<>();
        lenient().when(productTypeRepository.findByIdAndFactoryId("P1", "F1"))
                .thenReturn(Optional.of(product));
        lenient().when(repository.findByFactoryIdAndProductTypeIdOrderByCreatedAtAsc("F1", "P1"))
                .thenAnswer(invocation -> lastSaved.get() == null ? List.of() : List.of(lastSaved.get()));
        lenient().when(repository.findEffectiveByFactoryIdAndProductTypeIdAt(eq("F1"), eq("P1"), any()))
                .thenAnswer(invocation -> lastSaved.get() == null ? List.of() : List.of(lastSaved.get()));
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            ProductUnitConversion entity = invocation.getArgument(0);
            if (entity.getId() == null) entity.setId("C1");
            if (entity.getVersion() == null) entity.setVersion(0L);
            lastSaved.set(entity);
            return entity;
        });
    }

    @Test
    void createNetContentCanonicalizesAndWritesLegacyGrams() {
        canonical("件", "pcs", UnitDimension.COUNT);
        canonical("g", "g", UnitDimension.MASS);
        when(unitContractService.validateConversionGraph(eq("F1"), eq("P1"), any()))
                .thenReturn(List.of());

        ProductUnitConversionDTO result = service.create("F1", "P1", request(
                "件", "g", "200", ProductUnitConversion.SourceType.NET_CONTENT, null));

        assertEquals("pcs", result.fromUnitCode());
        assertEquals("g", result.toUnitCode());
        assertEquals(0, new BigDecimal("200").compareTo(product.getGramsPerUnit()));
        verify(productTypeRepository).save(product);
        verify(workflowUnitReviewService).markPublishedWorkflowsForReview("F1");
    }

    @Test
    void massPrimaryUnitNeverBackfillsCountAssumption() {
        product.setUnit("g");
        canonical("件", "pcs", UnitDimension.COUNT);
        canonical("g", "g", UnitDimension.MASS);
        when(unitContractService.validateConversionGraph(eq("F1"), eq("P1"), any()))
                .thenReturn(List.of());

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
    void futureNetContentDoesNotChangeCurrentLegacyGrams() {
        product.setGramsPerUnit(new BigDecimal("200"));
        canonical("件", "pcs", UnitDimension.COUNT);
        canonical("g", "g", UnitDimension.MASS);
        when(unitContractService.validateConversionGraph(eq("F1"), eq("P1"), any()))
                .thenReturn(List.of());
        LocalDateTime tomorrow = LocalDateTime.now().plusDays(1);
        ProductUnitConversionDTO future = new ProductUnitConversionDTO(
                null, "P1", "件", null, null, "g", null, null,
                new BigDecimal("250"), ProductUnitConversion.SourceType.NET_CONTENT,
                false, tomorrow, null, null);

        service.create("F1", "P1", future);

        assertEquals(0, new BigDecimal("200").compareTo(product.getGramsPerUnit()));
        verify(unitContractService).validateConversionGraph("F1", "P1", tomorrow);
        verify(productTypeRepository, never()).save(product);
    }

    @Test
    void updateAwayFromNetContentClearsLegacyProjection() {
        product.setGramsPerUnit(new BigDecimal("200"));
        canonical("件", "pcs", UnitDimension.COUNT);
        canonical("pcs", "pcs", UnitDimension.COUNT);
        canonical("g", "g", UnitDimension.MASS);
        ProductUnitConversion existing = new ProductUnitConversion();
        existing.setId("C1");
        existing.setFactoryId("F1");
        existing.setProductTypeId("P1");
        existing.setFromUnitCode("pcs");
        existing.setToUnitCode("g");
        existing.setFactor(new BigDecimal("200"));
        existing.setSourceType(ProductUnitConversion.SourceType.NET_CONTENT);
        existing.setEffectiveFrom(LocalDateTime.now().minusDays(1));
        existing.setVersion(4L);
        when(repository.findByIdAndFactoryIdAndProductTypeId("C1", "F1", "P1"))
                .thenReturn(Optional.of(existing));
        when(unitContractService.validateConversionGraph(eq("F1"), eq("P1"), any()))
                .thenReturn(List.of());

        service.update("F1", "P1", "C1", request(
                "pcs", "g", "200", ProductUnitConversion.SourceType.MANUAL, 4L));

        assertNull(product.getGramsPerUnit());
        verify(productTypeRepository).save(product);
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
