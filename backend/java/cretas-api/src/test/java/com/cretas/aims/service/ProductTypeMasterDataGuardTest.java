package com.cretas.aims.service;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.impl.ProductTypeServiceImpl;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.unit.ProductSpecificationConversionSyncService;
import com.cretas.aims.service.workflow.WorkflowUnitReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductTypeMasterDataGuardTest {

    @Mock ProductTypeRepository repository;
    @Mock CustomerRepository customerRepository;
    @Mock WorkflowUnitReviewService workflowUnitReviewService;
    @Mock ProductSpecificationConversionSyncService conversionSyncService;
    @Mock ProductPackagingSpecService packagingSpecService;

    private ProductTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductTypeServiceImpl(repository, new ObjectMapper(), customerRepository,
                workflowUnitReviewService, conversionSyncService, packagingSpecService);
    }

    @Test
    void automaticCreateAndPreviewShareMaxSuffixLogicAfterManualHighCode() {
        when(repository.existsByFactoryIdAndNormalizedName("F006", "测试成品")).thenReturn(false);
        when(repository.findCodesByFactoryIdAndGeneratedPrefix("F006", "CPF006"))
                .thenReturn(List.of("CPF0060003", "CPF0060149"));
        when(repository.save(any(ProductType.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductTypeDTO dto = ProductTypeDTO.builder()
                .name("  测试成品  ")
                .unit("盒")
                .productCategory("FINISHED_PRODUCT")
                .build();

        ProductTypeDTO created = service.createProductType("F006", dto);
        String preview = service.previewGeneratedCode("F006", "FINISHED_PRODUCT", null, null);

        assertThat(created.getCode()).isEqualTo("CPF0060150");
        assertThat(created.getName()).isEqualTo("测试成品");
        assertThat(preview).isEqualTo("CPF0060150");
    }

    @Test
    void createRejectsTrimmedCaseInsensitiveDuplicateNameAcrossCategories() {
        when(repository.existsByFactoryIdAndNormalizedName("F006", "同名产品")).thenReturn(true);
        ProductTypeDTO dto = ProductTypeDTO.builder()
                .code("MANUAL-001")
                .name("  同名产品  ")
                .unit("盒")
                .productCategory("SEMI_FINISHED")
                .build();

        assertThatThrownBy(() -> service.createProductType("F006", dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(409);
                    assertThat(business.getHintTarget()).isEqualTo("name");
                });
        verify(repository, never()).save(any());
    }

    @Test
    void updateRejectsDuplicateNameWhileExcludingCurrentProduct() {
        ProductType existing = new ProductType();
        existing.setId("P-1");
        existing.setFactoryId("F006");
        existing.setCode("CPF0060001");
        existing.setName("旧名称");
        existing.setUnit("盒");
        when(repository.findById("P-1")).thenReturn(Optional.of(existing));
        when(repository.existsByFactoryIdAndNormalizedNameExcludingId("F006", "新名称", "P-1"))
                .thenReturn(true);

        ProductTypeDTO dto = ProductTypeDTO.builder().name(" 新名称 ").unit("盒").build();

        assertThatThrownBy(() -> service.updateProductType("F006", "P-1", dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(409);
                    assertThat(business.getHintTarget()).isEqualTo("name");
                });
        verify(repository, never()).save(any());
    }
}
