package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.material.MaterialCodePreviewDTO;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.TaxTreatment;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RawMaterialTypeSegmentContractTest {

    private static final String FACTORY_ID = "F006";
    private static final Long L1 = 1L;
    private static final Long L2 = 2L;
    private static final Long L3 = 3L;

    @Mock RawMaterialTypeRepository materialTypeRepository;
    @Mock MaterialBatchRepository materialBatchRepository;
    @Mock ConversionRepository conversionRepository;
    @Mock MaterialPackagingHierarchyRepository packagingRepository;
    @Mock MaterialCodeSegmentRepository segmentRepository;
    @Mock ExcelUtil excelUtil;
    @Mock WorkflowUnitReviewService workflowUnitReviewService;

    private RawMaterialTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RawMaterialTypeServiceImpl(materialTypeRepository, materialBatchRepository,
                conversionRepository, packagingRepository, segmentRepository, excelUtil,
                workflowUnitReviewService);
        lenient().when(materialTypeRepository.findCodesByFactoryIdAndCodePrefix(anyString(), anyString()))
                .thenReturn(List.of());
        lenient().when(materialTypeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void activeDictionaryDoesNotForceClassificationAndSuggestsNextShortCode() {
        when(materialTypeRepository.findCodesByFactoryIdAndCodePrefix(FACTORY_ID, "YL"))
                .thenReturn(List.of("YL057", "YL065"));

        RawMaterialTypeDTO result = service.createMaterialType(FACTORY_ID, validRequest(null, null));

        assertEquals("YL066", result.getCode());
        assertEquals("YL066", result.getDisplayCode());
        assertNull(result.getClassificationId());
        assertTrue(result.getId().matches("RMT_[0-9a-f-]{36}"));
        verify(segmentRepository, never()).findByIdAndFactoryId(anyLong(), anyString());
    }

    @Test
    void optionalClassificationIsPersistedButNeverChangesSuppliedCode() {
        stubValidChain();
        RawMaterialTypeDTO request = validRequest(L3, "YL099");

        RawMaterialTypeDTO result = service.createMaterialType(FACTORY_ID, request);

        assertEquals("YL099", result.getCode());
        assertEquals(L3, result.getClassificationId());
        ArgumentCaptor<RawMaterialType> captor = ArgumentCaptor.forClass(RawMaterialType.class);
        verify(materialTypeRepository).save(captor.capture());
        assertEquals(L3, captor.getValue().getClassificationSegmentId());
    }

    @Test
    void optionalClassificationMustMatchBasicType() {
        stubValidChain();
        RawMaterialTypeDTO request = validRequest(L3, "BC001");
        request.setCategory("包材");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMaterialType(FACTORY_ID, request));

        assertEquals(400, error.getCode());
        assertEquals("category", error.getHintTarget());
        verify(materialTypeRepository, never()).save(any());
    }

    @Test
    void softDeletedCodeConflictNamesOccupyingMaterial() {
        RawMaterialTypeRepository.CodeConflictView conflict = mock(RawMaterialTypeRepository.CodeConflictView.class);
        when(conflict.getName()).thenReturn("旧牛肉");
        when(conflict.getDeletedAt()).thenReturn(LocalDateTime.of(2026, 8, 1, 1, 2));
        when(materialTypeRepository.findCodeConflictIncludingDeleted(FACTORY_ID, "YL065"))
                .thenReturn(Optional.of(conflict));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMaterialType(FACTORY_ID, validRequest(null, "YL065")));

        assertEquals(409, error.getCode());
        assertEquals("code", error.getHintTarget());
        assertTrue(error.getMessage().contains("料号冲突"));
        assertTrue(error.getMessage().contains("旧牛肉"));
        assertTrue(error.getMessage().contains("已删除物料"));
    }

    @Test
    void previewUsesBasicTypeAndClassificationIsOptional() {
        when(materialTypeRepository.findCodesByFactoryIdAndCodePrefix(FACTORY_ID, "YL"))
                .thenReturn(List.of("YL065"));

        MaterialCodePreviewDTO preview = service.previewMaterialCodeContract(FACTORY_ID, "原料", null);

        assertEquals("YL066", preview.getCode());
        assertNull(preview.getClassificationId());
        assertTrue(preview.getSelectable());
    }

    @Test
    void unknownBasicTypeFailsInsteadOfInventingDefaultPrefix() {
        RawMaterialTypeDTO request = validRequest(null, null);
        request.setCategory("未知类型");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMaterialType(FACTORY_ID, request));

        assertEquals(400, error.getCode());
        assertEquals("category", error.getHintTarget());
        verify(materialTypeRepository, never()).save(any());
    }

    @Test
    void updateShortCodeDoesNotRequireClassification() {
        RawMaterialType existing = existing("YL001");
        when(materialTypeRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        RawMaterialTypeDTO result = service.updateMaterialType(FACTORY_ID, existing.getId(),
                RawMaterialTypeDTO.builder().name("新名称").build());

        assertEquals("YL001", result.getCode());
        assertEquals("新名称", result.getName());
    }

    @Test
    void prefixOnlyFilterBindsKeywordAsTextInsteadOfNull() {
        PageRequest request = new PageRequest();
        request.setPage(1);
        request.setSize(20);
        when(materialTypeRepository.filterBySegmentPrefixAndKeyword(
                eq(FACTORY_ID), eq(L2), eq(""), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.filterMaterialTypes(FACTORY_ID, L2, null, request);

        verify(materialTypeRepository).filterBySegmentPrefixAndKeyword(
                eq(FACTORY_ID), eq(L2), eq(""), any(Pageable.class));
    }

    @Test
    void keywordOnlyFilterBindsPrefixAsTextInsteadOfNull() {
        PageRequest request = new PageRequest();
        request.setPage(1);
        request.setSize(20);
        when(materialTypeRepository.filterBySegmentPrefixAndKeyword(
                eq(FACTORY_ID), isNull(), eq("chicken"), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.filterMaterialTypes(FACTORY_ID, null, " chicken ", request);

        verify(materialTypeRepository).filterBySegmentPrefixAndKeyword(
                eq(FACTORY_ID), isNull(), eq("chicken"), any(Pageable.class));
    }

    private RawMaterialTypeDTO validRequest(Long classificationId, String code) {
        return RawMaterialTypeDTO.builder()
                .code(code)
                .name("牛肉")
                .category("原料")
                .unit("kg")
                .taxTreatment(TaxTreatment.EXEMPT)
                .taxExemptionReason("自产免税")
                .taxIncludedUnitPrice(BigDecimal.ONE)
                .classificationId(classificationId)
                .build();
    }

    private RawMaterialType existing(String code) {
        RawMaterialType material = new RawMaterialType();
        material.setId("RMT-1");
        material.setFactoryId(FACTORY_ID);
        material.setCode(code);
        material.setName("旧名称");
        material.setCategory("原料");
        material.setUnit("kg");
        material.setTaxTreatment(TaxTreatment.EXEMPT);
        material.setTaxExemptionReason("自产免税");
        material.setTaxIncludedUnitPrice(BigDecimal.ONE);
        material.setCreatedBy(1L);
        material.setIsActive(true);
        material.setIsByproduct(false);
        material.setCreatedAt(LocalDateTime.now());
        material.setUpdatedAt(LocalDateTime.now());
        return material;
    }

    private void stubValidChain() {
        stubNode(L3, (short) 3, L2, "牛肉", true);
        stubNode(L2, (short) 2, L1, "肉类", true);
        stubNode(L1, (short) 1, null, "原料", true);
    }

    private void stubNode(Long id, short level, Long parent, String label, boolean active) {
        MaterialCodeSegment node = MaterialCodeSegment.builder()
                .factoryId(FACTORY_ID)
                .level(level)
                .parentId(parent)
                .segmentLabel(label)
                .isActive(active)
                .build();
        node.setId(id);
        when(segmentRepository.findByIdAndFactoryId(id, FACTORY_ID))
                .thenReturn(Optional.of(node));
    }
}
