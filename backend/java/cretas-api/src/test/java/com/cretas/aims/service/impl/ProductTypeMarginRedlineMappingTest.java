package com.cretas.aims.service.impl;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductType SP5 margin redline mapping")
class ProductTypeMarginRedlineMappingTest {

    @Mock ProductTypeRepository productTypeRepository;
    @Mock CustomerRepository customerRepository;

    private ProductTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductTypeServiceImpl(productTypeRepository, new ObjectMapper(), customerRepository,
                org.mockito.Mockito.mock(com.cretas.aims.service.workflow.WorkflowUnitReviewService.class));
    }

    @Test
    @DisplayName("create maps standardCost and targetGrossMargin to DTO")
    void create_mapsMarginRedlineFields() {
        when(productTypeRepository.existsByFactoryIdAndCode(any(), any())).thenReturn(false);
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductTypeDTO dto = ProductTypeDTO.builder()
                .name("Margin product")
                .unit("box")
                .code("PT-MARGIN")
                .standardCost(new BigDecimal("12.3456"))
                .targetGrossMargin(new BigDecimal("0.2500"))
                .createdBy(1L)
                .build();

        ProductTypeDTO result = service.createProductType("F006", dto);

        assertEquals(0, new BigDecimal("12.3456").compareTo(result.getStandardCost()));
        assertEquals(0, new BigDecimal("0.2500").compareTo(result.getTargetGrossMargin()));
    }

    @Test
    @DisplayName("update leaves margin fields unchanged when keys are absent")
    void update_absentMarginRedlineFields_doNotChangeExistingValues() {
        ProductType existing = existingProduct();
        existing.setStandardCost(new BigDecimal("20.0000"));
        existing.setTargetGrossMargin(new BigDecimal("0.3000"));

        when(productTypeRepository.findById("pt-margin")).thenReturn(Optional.of(existing));
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductTypeDTO result = service.updateProductType("F006", "pt-margin", new ProductTypeDTO());

        assertEquals(0, new BigDecimal("20.0000").compareTo(result.getStandardCost()));
        assertEquals(0, new BigDecimal("0.3000").compareTo(result.getTargetGrossMargin()));
    }

    @Test
    @DisplayName("update clears margin fields when keys are present with null")
    void update_explicitNullMarginRedlineFields_clearExistingValues() {
        ProductType existing = existingProduct();
        existing.setStandardCost(new BigDecimal("20.0000"));
        existing.setTargetGrossMargin(new BigDecimal("0.3000"));

        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setStandardCost(null);
        dto.setTargetGrossMargin(null);

        when(productTypeRepository.findById("pt-margin")).thenReturn(Optional.of(existing));
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductTypeDTO result = service.updateProductType("F006", "pt-margin", dto);

        assertNull(result.getStandardCost());
        assertNull(result.getTargetGrossMargin());
    }

    private static ProductType existingProduct() {
        ProductType product = new ProductType();
        product.setId("pt-margin");
        product.setFactoryId("F006");
        product.setCode("PT-MARGIN");
        product.setName("Margin product");
        product.setUnit("box");
        product.setCreatedBy(1L);
        return product;
    }
}
