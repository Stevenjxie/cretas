package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.service.workflow.WorkflowUnitReviewService;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RawMaterialTypeMasterDataGuardTest {

    private static final String FACTORY = "F006";
    private static final String L1 = "001";
    private static final String L2 = "001001";
    private static final String L3 = "0010010001";

    @Mock RawMaterialTypeRepository repository;
    @Mock MaterialBatchRepository batchRepository;
    @Mock ConversionRepository conversionRepository;
    @Mock MaterialPackagingHierarchyRepository packagingRepository;
    @Mock MaterialCodeSegmentRepository segmentRepository;
    @Mock ExcelUtil excelUtil;
    @Mock WorkflowUnitReviewService workflowUnitReviewService;

    private RawMaterialTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RawMaterialTypeServiceImpl(repository, batchRepository, conversionRepository,
                packagingRepository, segmentRepository, excelUtil, workflowUnitReviewService);
    }

    @Test
    void nonPackagingCreateDefaultsUnitAndTaxAndDropsReferencePrice() {
        stubCreate("原料");
        RawMaterialTypeDTO dto = baseCreateDto("  牛肉  ");
        dto.setUnit("  ");
        dto.setStorageType("frozen");
        dto.setTaxIncludedUnitPrice(new BigDecimal("113.00"));

        RawMaterialTypeDTO result = service.createMaterialType(FACTORY, dto);

        assertThat(result.getName()).isEqualTo("牛肉");
        assertThat(result.getUnit()).isEqualTo("kg");
        assertThat(result.getTaxRate()).isEqualTo(TaxRate.TAX_13);
        assertThat(result.getTaxIncludedUnitPrice()).isNull();
        ArgumentCaptor<RawMaterialType> captor = ArgumentCaptor.forClass(RawMaterialType.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUnitPrice()).isNull();
        assertThat(captor.getValue().getStorageType()).isEqualTo("frozen");
    }

    @Test
    void packagingCreateSupportsChineseCategoryAndKeepsPriceButClearsStorage() {
        stubCreate("包材");
        RawMaterialTypeDTO dto = baseCreateDto("吸塑盒");
        dto.setUnit("个");
        dto.setStorageType("dry");
        dto.setTaxIncludedUnitPrice(new BigDecimal("113.00"));

        RawMaterialTypeDTO result = service.createMaterialType(FACTORY, dto);

        assertThat(result.getCategory()).isEqualTo("包材");
        assertThat(result.getStorageType()).isNull();
        assertThat(result.getTaxRate()).isEqualTo(TaxRate.TAX_13);
        assertThat(result.getTaxIncludedUnitPrice()).isEqualByComparingTo("113.00");
    }

    @Test
    void createRejectsNormalizedDuplicateNameWithNameHint() {
        stubSegmentChain("原料");
        when(repository.existsByFactoryIdAndNormalizedName(FACTORY, "牛肉")).thenReturn(true);
        RawMaterialTypeDTO dto = baseCreateDto("  牛肉  ");

        assertThatThrownBy(() -> service.createMaterialType(FACTORY, dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(409);
                    assertThat(business.getHintTarget()).isEqualTo("name");
                });
        verify(repository, never()).save(any());
    }

    @Test
    void updateExcludesSelfForNameCheckAndRejectsOtherDuplicate() {
        stubSegmentChain("原料");
        RawMaterialType existing = existing("原料");
        when(repository.findById("M-1")).thenReturn(Optional.of(existing));
        when(repository.existsByFactoryIdAndNormalizedNameExcludingId(FACTORY, "牛肉", "M-1"))
                .thenReturn(true);
        RawMaterialTypeDTO dto = new RawMaterialTypeDTO();
        dto.setSegmentCode(L3);
        dto.setName(" 牛肉 ");

        assertThatThrownBy(() -> service.updateMaterialType(FACTORY, "M-1", dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getHintTarget()).isEqualTo("name"));
        verify(repository, never()).save(any());
    }

    @Test
    void packagingToNonPackagingClearsHierarchyAndReferencePrice() {
        stubSegmentChain("原料");
        RawMaterialType existing = existing("PACKAGING");
        existing.setTaxRate(TaxRate.TAX_13);
        existing.setTaxIncludedUnitPrice(new BigDecimal("113.00"));
        existing.setUnitPrice(new BigDecimal("100.00"));
        when(repository.findById("M-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(RawMaterialType.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RawMaterialTypeDTO dto = new RawMaterialTypeDTO();
        dto.setSegmentCode(L3);

        RawMaterialTypeDTO result = service.updateMaterialType(FACTORY, "M-1", dto);

        assertThat(result.getCategory()).isEqualTo("原料");
        assertThat(result.getTaxIncludedUnitPrice()).isNull();
        verify(packagingRepository).deleteByMaterialTypeId("M-1");
    }

    private RawMaterialTypeDTO baseCreateDto(String name) {
        RawMaterialTypeDTO dto = new RawMaterialTypeDTO();
        dto.setName(name);
        dto.setSegmentCode(L3);
        return dto;
    }

    private RawMaterialType existing(String category) {
        RawMaterialType material = new RawMaterialType();
        material.setId("M-1");
        material.setFactoryId(FACTORY);
        material.setCode(L3 + "000001");
        material.setName("旧名称");
        material.setCategory(category);
        material.setUnit("kg");
        material.setIsActive(true);
        material.setCreatedBy(1L);
        return material;
    }

    private void stubCreate(String l1Label) {
        stubSegmentChain(l1Label);
        when(segmentRepository.lockByFactoryIdAndSegmentCode(FACTORY, L3))
                .thenReturn(Optional.of(segment((short) 3, L3, L2, "明细")));
        when(repository.findCodesByFactoryIdAndSegmentPrefix(FACTORY, L3)).thenReturn(List.of());
        when(repository.existsByFactoryIdAndCode(FACTORY, L3 + "000001")).thenReturn(false);
        when(repository.save(any(RawMaterialType.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubSegmentChain(String l1Label) {
        when(segmentRepository.findByFactoryIdAndSegmentCode(FACTORY, L3))
                .thenReturn(Optional.of(segment((short) 3, L3, L2, "明细")));
        when(segmentRepository.findByFactoryIdAndSegmentCode(FACTORY, L2))
                .thenReturn(Optional.of(segment((short) 2, L2, L1, "二级")));
        when(segmentRepository.findByFactoryIdAndSegmentCode(FACTORY, L1))
                .thenReturn(Optional.of(segment((short) 1, L1, null, l1Label)));
    }

    private MaterialCodeSegment segment(short level, String code, String parent, String label) {
        return MaterialCodeSegment.builder()
                .factoryId(FACTORY)
                .level(level)
                .segmentCode(code)
                .parentCode(parent)
                .segmentLabel(label)
                .isActive(true)
                .build();
    }
}
