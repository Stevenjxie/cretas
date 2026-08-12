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
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.unit.UnitUsageScope;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
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
    private static final Long L1 = 1L;
    private static final Long L2 = 2L;
    private static final Long L3 = 3L;

    @Mock RawMaterialTypeRepository repository;
    @Mock MaterialBatchRepository batchRepository;
    @Mock ConversionRepository conversionRepository;
    @Mock MaterialPackagingHierarchyRepository packagingRepository;
    @Mock MaterialCodeSegmentRepository segmentRepository;
    @Mock ExcelUtil excelUtil;
    @Mock WorkflowUnitReviewService workflowUnitReviewService;
    @Mock UnitContractService unitContractService;

    private RawMaterialTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RawMaterialTypeServiceImpl(repository, batchRepository, conversionRepository,
                packagingRepository, segmentRepository, excelUtil, workflowUnitReviewService);
        ReflectionTestUtils.setField(service, "unitContractService", unitContractService);
        org.mockito.Mockito.lenient().when(unitContractService.normalize(any(), any()))
                .thenAnswer(invocation -> {
                    String raw = invocation.getArgument(1);
                    String code = raw == null || raw.isBlank() ? "kg" : raw.trim();
                    CanonicalUnit unit = new CanonicalUnit(
                            code, UnitDimension.MASS, code, BigDecimal.ONE, code, 3);
                    return new UnitNormalizationResult(raw, code, unit);
                });
        org.mockito.Mockito.lenient().when(unitContractService.supportsUsage(
                any(), any(), org.mockito.ArgumentMatchers.eq(UnitUsageScope.INVENTORY_QUANTITY)))
                .thenReturn(true);
        org.mockito.Mockito.lenient().when(unitContractService.storageUnit(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void nonPackagingCreateDefaultsUnitAndTaxAndKeepsReferencePrice() {
        stubCreate("原料");
        RawMaterialTypeDTO dto = baseCreateDto("  牛肉  ");
        dto.setUnit("  ");
        dto.setStorageType("frozen");
        dto.setTaxIncludedUnitPrice(new BigDecimal("113.00"));

        RawMaterialTypeDTO result = service.createMaterialType(FACTORY, dto);

        assertThat(result.getName()).isEqualTo("牛肉");
        assertThat(result.getUnit()).isEqualTo("kg");
        assertThat(result.getTaxRate()).isEqualTo(TaxRate.TAX_13);
        assertThat(result.getTaxIncludedUnitPrice()).isEqualByComparingTo("113.00");
        ArgumentCaptor<RawMaterialType> captor = ArgumentCaptor.forClass(RawMaterialType.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUnitPrice()).isEqualByComparingTo("100.0000");
        assertThat(captor.getValue().getStorageType()).isEqualTo("frozen");
    }

    @Test
    void packagingCreateSupportsChineseCategoryAndKeepsPriceButClearsStorage() {
        stubCreate("包材");
        RawMaterialTypeDTO dto = baseCreateDto("吸塑盒");
        dto.setCategory("包材");
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
    void createRejectsUnitOutsideInventoryUsageScope() {
        // Validation fails before code allocation/persistence, so only the
        // authoritative classification chain is required for this case.
        stubSegmentChain("原料");
        RawMaterialTypeDTO dto = baseCreateDto("温控原料");
        dto.setUnit("minute");
        when(unitContractService.supportsUsage(
                FACTORY, "minute", UnitUsageScope.INVENTORY_QUANTITY)).thenReturn(false);

        assertThatThrownBy(() -> service.createMaterialType(FACTORY, dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getHintTarget()).isEqualTo("unit"));
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
        dto.setCategory("原料");
        dto.setClassificationId(L3);
        dto.setName(" 牛肉 ");

        assertThatThrownBy(() -> service.updateMaterialType(FACTORY, "M-1", dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getHintTarget()).isEqualTo("name"));
        verify(repository, never()).save(any());
    }

    @Test
    void packagingToNonPackagingClearsHierarchyAndKeepsReferencePrice() {
        stubSegmentChain("原料");
        RawMaterialType existing = existing("PACKAGING");
        existing.setTaxRate(TaxRate.TAX_13);
        existing.setTaxIncludedUnitPrice(new BigDecimal("113.00"));
        existing.setUnitPrice(new BigDecimal("100.00"));
        when(repository.findById("M-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(RawMaterialType.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RawMaterialTypeDTO dto = new RawMaterialTypeDTO();
        dto.setCategory("原料");
        dto.setClassificationId(L3);

        RawMaterialTypeDTO result = service.updateMaterialType(FACTORY, "M-1", dto);

        assertThat(result.getCategory()).isEqualTo("原料");
        assertThat(result.getTaxIncludedUnitPrice()).isEqualByComparingTo("113.00");
        verify(packagingRepository).deleteByMaterialTypeId("M-1");
    }

    /**
     * 🔴 2026-08-11: updateMaterialType 以前**完全没有** setIsByproduct —— create 写、update 漏。
     * 后果不止是物料档案那个开关失灵: 画布副产 Cell 在没有副产物料时给的指引正是
     * 「去『仓库 → 物料档案』编辑该物料, 勾上这是副产后再回来选」, 用户照做,
     * 回来发现下拉还是空的, 因为那一勾从来没保存过 —— 一条**走不通的**指引。
     */
    @Test
    void updateHonorsIsByproductFlag() {
        stubSegmentChain("原料");
        RawMaterialType existing = existing("原料");
        existing.setIsByproduct(false);
        when(repository.findById("M-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(RawMaterialType.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RawMaterialTypeDTO dto = new RawMaterialTypeDTO();
        dto.setCategory("原料");
        dto.setClassificationId(L3);
        dto.setIsByproduct(true);

        RawMaterialTypeDTO result = service.updateMaterialType(FACTORY, "M-1", dto);

        assertThat(result.getIsByproduct()).isTrue();
        ArgumentCaptor<RawMaterialType> captor = ArgumentCaptor.forClass(RawMaterialType.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getIsByproduct()).isTrue();
    }

    /** null-tolerant: 不传这个字段就不许动它, 与相邻字段口径一致(别的编辑不该顺手清掉副产标记)。 */
    @Test
    void updateWithoutIsByproductKeepsExistingFlag() {
        stubSegmentChain("原料");
        RawMaterialType existing = existing("原料");
        existing.setIsByproduct(true);
        when(repository.findById("M-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(RawMaterialType.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RawMaterialTypeDTO dto = new RawMaterialTypeDTO();
        dto.setCategory("原料");
        dto.setClassificationId(L3);

        RawMaterialTypeDTO result = service.updateMaterialType(FACTORY, "M-1", dto);

        assertThat(result.getIsByproduct()).isTrue();
    }

    private RawMaterialTypeDTO baseCreateDto(String name) {
        RawMaterialTypeDTO dto = new RawMaterialTypeDTO();
        dto.setName(name);
        dto.setCategory("原料");
        dto.setClassificationId(L3);
        return dto;
    }

    private RawMaterialType existing(String category) {
        RawMaterialType material = new RawMaterialType();
        material.setId("M-1");
        material.setFactoryId(FACTORY);
        material.setCode("YL001");
        material.setClassificationSegmentId(L3);
        material.setName("旧名称");
        material.setCategory(category);
        material.setUnit("kg");
        material.setIsActive(true);
        material.setCreatedBy(1L);
        return material;
    }

    private void stubCreate(String l1Label) {
        stubSegmentChain(l1Label);
        String prefix = "包材".equals(l1Label) ? "BC" : "YL";
        when(repository.findCodesByFactoryIdAndCodePrefix(FACTORY, prefix)).thenReturn(java.util.List.of());
        when(repository.save(any(RawMaterialType.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubSegmentChain(String l1Label) {
        when(segmentRepository.findByIdAndFactoryId(L3, FACTORY))
                .thenReturn(Optional.of(segment((short) 3, L3, L2, "明细")));
        when(segmentRepository.findByIdAndFactoryId(L2, FACTORY))
                .thenReturn(Optional.of(segment((short) 2, L2, L1, "二级")));
        when(segmentRepository.findByIdAndFactoryId(L1, FACTORY))
                .thenReturn(Optional.of(segment((short) 1, L1, null, l1Label)));
    }

    private MaterialCodeSegment segment(short level, Long id, Long parent, String label) {
        MaterialCodeSegment segment = MaterialCodeSegment.builder()
                .factoryId(FACTORY)
                .level(level)
                .parentId(parent)
                .segmentLabel(label)
                .isActive(true)
                .build();
        segment.setId(id);
        return segment;
    }
}
