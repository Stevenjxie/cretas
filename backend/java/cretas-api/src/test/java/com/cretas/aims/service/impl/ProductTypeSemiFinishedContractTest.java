package com.cretas.aims.service.impl;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.unit.ProductSpecificationConversionSyncService;
import com.cretas.aims.service.workflow.WorkflowUnitReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductTypeSemiFinishedContractTest {

    @Mock ProductTypeRepository productTypeRepository;
    @Mock CustomerRepository customerRepository;
    @Mock WorkflowUnitReviewService workflowUnitReviewService;
    @Mock ProductSpecificationConversionSyncService conversionSyncService;
    @Mock ProductPackagingSpecService packagingSpecService;

    private ProductTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductTypeServiceImpl(productTypeRepository, new ObjectMapper(), customerRepository,
                workflowUnitReviewService, conversionSyncService, packagingSpecService);
    }

    @Test
    void createSemiFinishedPreservesEditableBasicUnitAndClearsFinishedGoodsSpecification() {
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ProductTypeDTO dto = ProductTypeDTO.builder()
                .code("SFI-001")
                .name("腌制羊排半成品")
                .productCategory(ProductCategory.SEMI_FINISHED)
                .unit("g")
                .gramsPerUnit(new BigDecimal("200"))
                .wipToFgYield(new BigDecimal("0.8"))
                .level1Unit("箱")
                .boxConversionCoefficient(BigDecimal.TEN)
                .specification("200g/袋 10袋/箱")
                .build();

        ProductTypeDTO result = service.createProductType("F006", dto);

        assertThat(result.getUnit()).isEqualTo("g");
        assertThat(result.getGramsPerUnit()).isNull();
        assertThat(result.getWipToFgYield()).isNull();
        assertThat(result.getLevel1Unit()).isNull();
        assertThat(result.getBoxConversionCoefficient()).isNull();
        assertThat(result.getSpecification()).isNull();
        verify(packagingSpecService).replace(any(ProductType.class), org.mockito.ArgumentMatchers.eq(java.util.List.of()));
    }

    @Test
    void editingSemiFinishedBasicUnitIsAllowed() {
        ProductType existing = new ProductType();
        existing.setId("SFI-LEGACY");
        existing.setFactoryId("F006");
        existing.setProductCategory(ProductCategory.SEMI_FINISHED);
        existing.setUnit("g");
        when(productTypeRepository.findById("SFI-LEGACY")).thenReturn(java.util.Optional.of(existing));
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductTypeDTO update = ProductTypeDTO.builder()
                .name("更新名称")
                .unit("只")
                .productCategory(ProductCategory.SEMI_FINISHED)
                .build();

        ProductTypeDTO result = service.updateProductType("F006", "SFI-LEGACY", update);

        assertThat(result.getUnit()).isEqualTo("只");
        assertThat(existing.getUnit()).isEqualTo("只");
    }

    @Test
    void changingExistingSkuBaseUnitRequiresControlledMigration() {
        ProductType existing = new ProductType();
        existing.setId("FG-001");
        existing.setFactoryId("F006");
        existing.setProductCategory(ProductCategory.FINISHED_PRODUCT);
        existing.setUnit("盒");
        when(productTypeRepository.findById("FG-001")).thenReturn(java.util.Optional.of(existing));

        ProductTypeDTO update = ProductTypeDTO.builder()
                .unit("件")
                .productCategory(ProductCategory.FINISHED_PRODUCT)
                .build();

        assertThatThrownBy(() -> service.updateProductType("F006", "FG-001", update))
                .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo("SKU_UNIT_MIGRATION_REQUIRED"));
        assertThat(existing.getUnit()).isEqualTo("盒");
    }
}
