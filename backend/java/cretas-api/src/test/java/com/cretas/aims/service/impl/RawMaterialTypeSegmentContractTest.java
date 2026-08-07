package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.dto.material.MaterialCodePreviewDTO;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.TaxTreatment;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.service.material.MaterialBusinessCodeService;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RawMaterialTypeSegmentContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String L1 = "001";
    private static final String L2 = "001001";
    private static final String L3 = "0010010001";

    @Mock RawMaterialTypeRepository materialTypeRepository;
    @Mock MaterialBatchRepository materialBatchRepository;
    @Mock ConversionRepository conversionRepository;
    @Mock MaterialPackagingHierarchyRepository packagingRepository;
    @Mock MaterialCodeSegmentRepository segmentRepository;
    @Mock ExcelUtil excelUtil;
    @Mock WorkflowUnitReviewService workflowUnitReviewService;
    @Mock MaterialBusinessCodeService materialBusinessCodeService;

    private RawMaterialTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RawMaterialTypeServiceImpl(materialTypeRepository, materialBatchRepository,
                conversionRepository, packagingRepository, segmentRepository, excelUtil,
                workflowUnitReviewService);
        ReflectionTestUtils.setField(service, "materialBusinessCodeService", materialBusinessCodeService);
        lenient().when(materialBusinessCodeService.allocateBusinessCode(FACTORY_ID, L3))
                .thenReturn("RMSEA000001");
        // 默认「该工厂已配分段字典」—— 与本类全部既有用例断言的旧行为一致。
        // 只有点名验「无字典」那几条自己翻成 0。
        lenient().when(segmentRepository.countByFactoryIdAndLevel(FACTORY_ID, (short) 1))
                .thenReturn(3L);
    }

    /**
     * 🔴 2026-08-07 契约变更: 这条原名 createRequiresL3EvenWhenDictionaryIsEmpty ——
     * 断言「哪怕工厂没配分段字典也必须给 L3」。那条 fail-closed 让**没有字典的工厂
     * 根本建不了物料**, 而 16 位分类码在产品里已经是 legacy
     * (RawMaterialType#getLegacyClassificationCode)。
     *
     * <p>新契约按「该工厂有没有配分段字典」分流。⚠️ 注意如果只改实现不改这条用例,
     * 它会**照样绿**(无字典且没给料号 → 仍然 400 且不 save), 但它测的已经不是
     * 名字说的那件事了 —— 所以拆成下面三条各自点名的用例。</p>
     */
    @Test
    void createWithDictionaryStillRequiresL3() {
        // 有字典(setUp 默认) → 旧行为不变: 不给 L3 就拒绝

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createMaterialType(FACTORY_ID, validRequest(null)));

        assertEquals(400, ex.getCode());
        verify(materialTypeRepository, never()).save(any());
    }

    @Test
    void createWithoutDictionaryUsesUserSuppliedCodeAndSkipsBusinessCode() {
        // 显式翻成「无字典」→ 用户自己填料号, 不解 L1/L2/L3 链、不发 16 位码
        when(segmentRepository.countByFactoryIdAndLevel(FACTORY_ID, (short) 1)).thenReturn(0L);
        RawMaterialTypeDTO request = validRequest(null);
        request.setCode("YL052");
        when(materialTypeRepository.existsByFactoryIdAndCode(FACTORY_ID, "YL052")).thenReturn(false);
        when(materialTypeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RawMaterialTypeDTO result = service.createMaterialType(FACTORY_ID, request);

        assertEquals("YL052", result.getCode(), "用户料号必须被保留 —— 旧路径是无条件 setCode(generated) 覆盖掉的");
        assertEquals("原料", result.getCategory(), "无字典时类别取用户所选, 不再从 L1 段标签派生");
        assertNull(result.getPrimaryCode(), "无字典没有 L1 段码可挂");
        assertNull(result.getBusinessCode(),
                "business_code 前缀是 L3 的 base36, 没有字典就发不出来");
        assertEquals("YL052", result.getDisplayCode(), "展示码回落到用户料号");
        verify(materialBusinessCodeService, never()).allocateBusinessCode(any(), any());
        verify(segmentRepository, never()).lockByFactoryIdAndSegmentCode(any(), any());
    }

    @Test
    void createWithoutDictionaryRejectsMissingCode() {
        // 无字典时料号是唯一编码来源, 缺了必须当场说清楚, 而不是静默生成一个
        when(segmentRepository.countByFactoryIdAndLevel(FACTORY_ID, (short) 1)).thenReturn(0L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createMaterialType(FACTORY_ID, validRequest(null)));

        assertEquals(400, ex.getCode());
        assertEquals("code", ex.getHintTarget());
        verify(materialTypeRepository, never()).save(any());
    }

    @Test
    void createDerivesCategoryAndPrimaryCodeFromActiveParentChain() {
        stubValidChain();
        when(materialTypeRepository.findCodesByFactoryIdAndSegmentPrefix(FACTORY_ID, L3))
                .thenReturn(Collections.emptyList());
        when(materialTypeRepository.existsByFactoryIdAndCode(FACTORY_ID, L3 + "000001"))
                .thenReturn(false);
        when(materialTypeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RawMaterialTypeDTO result = service.createMaterialType(FACTORY_ID, validRequest(L3));

        assertEquals("原料", result.getCategory());
        assertEquals(L1, result.getPrimaryCode());
        assertEquals(L3 + "000001", result.getCode());
        assertEquals(L3 + "000001", result.getLegacyClassificationCode());
        assertEquals("RMSEA000001", result.getBusinessCode());
        assertEquals("RMSEA000001", result.getDisplayCode());
        assertFalse(result.isHistoricalCodeFallback());
    }

    @Test
    void createRejectsCategoryThatDoesNotMatchValidatedL1() {
        stubValidChain();
        RawMaterialTypeDTO request = validRequest(L3);
        request.setCategory("包材");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createMaterialType(FACTORY_ID, request));

        assertEquals(400, ex.getCode());
        assertEquals("category", ex.getHintTarget());
        verify(materialTypeRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicateNormalizedNameBeforeAllocatingCodes() {
        stubValidChain();
        when(materialTypeRepository.existsByFactoryIdAndNormalizedName(FACTORY_ID, "牛肉"))
                .thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createMaterialType(FACTORY_ID, validRequest(L3)));

        assertEquals(409, ex.getCode());
        verify(materialBusinessCodeService, never()).allocateBusinessCode(anyString(), anyString());
        verify(materialTypeRepository, never()).save(any());
    }

    @Test
    void previewAndCreateShareTheBusinessCodeResolver() {
        stubValidChain();
        when(materialTypeRepository.findCodesByFactoryIdAndSegmentPrefix(FACTORY_ID, L3))
                .thenReturn(Collections.emptyList());
        when(materialBusinessCodeService.previewBusinessCode(FACTORY_ID, L3))
                .thenReturn(new MaterialBusinessCodeService.BusinessCodePreview(
                        "M4NZN7000001", "M4NZN7", "SYSTEM_STABLE", L3));

        MaterialCodePreviewDTO preview = service.previewMaterialCodeContract(
                FACTORY_ID, "原料", L3);

        assertEquals(L3 + "000001", preview.getCode());
        assertEquals("M4NZN7000001", preview.getBusinessCode());
        assertEquals("SYSTEM_STABLE", preview.getBusinessCodePrefixSource());
        assertTrue(preview.getSelectable());
        verify(materialBusinessCodeService).previewBusinessCode(FACTORY_ID, L3);
    }

    @Test
    void createRejectsInactiveL3() {
        stubNode(L3, (short) 3, L2, "牛肉", false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createMaterialType(FACTORY_ID, validRequest(L3)));

        assertEquals(400, ex.getCode());
        verify(materialTypeRepository, never()).save(any());
    }

    @Test
    void createRejectsBrokenParentPrefix() {
        stubNode(L3, (short) 3, "009999", "牛肉", true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createMaterialType(FACTORY_ID, validRequest(L3)));

        assertEquals(400, ex.getCode());
        verify(materialTypeRepository, never()).save(any());
    }

    @Test
    void updateHistoricalNon16CodeRequiresExplicitL3Mapping() {
        RawMaterialType existing = existing("YL001");
        when(materialTypeRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateMaterialType(FACTORY_ID, existing.getId(),
                        RawMaterialTypeDTO.builder().name("新名称").build()));

        assertEquals(400, ex.getCode());
        verify(materialTypeRepository, never()).save(any());
    }

    @Test
    void updateExisting16CodeRevalidatesItsL3ChainAndDerivesCategory() {
        stubValidChain();
        RawMaterialType existing = existing(L3 + "000042");
        existing.setCategory("旧分类");
        when(materialTypeRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(materialTypeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RawMaterialTypeDTO result = service.updateMaterialType(FACTORY_ID, existing.getId(),
                RawMaterialTypeDTO.builder().name("新名称").build());

        assertEquals("原料", result.getCategory());
        assertEquals(L1, result.getPrimaryCode());
        assertEquals(L3 + "000042", result.getCode());
    }

    @Test
    void prefixOnlyFilterBindsKeywordAsTextInsteadOfNull() {
        PageRequest request = new PageRequest();
        request.setPage(1);
        request.setSize(20);
        when(materialTypeRepository.filterBySegmentPrefixAndKeyword(
                eq(FACTORY_ID), eq("002"), eq(""), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.filterMaterialTypes(FACTORY_ID, " 002 ", null, request);

        verify(materialTypeRepository).filterBySegmentPrefixAndKeyword(
                eq(FACTORY_ID), eq("002"), eq(""), any(Pageable.class));
    }

    @Test
    void keywordOnlyFilterBindsPrefixAsTextInsteadOfNull() {
        PageRequest request = new PageRequest();
        request.setPage(1);
        request.setSize(20);
        when(materialTypeRepository.filterBySegmentPrefixAndKeyword(
                eq(FACTORY_ID), eq(""), eq("chicken"), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.filterMaterialTypes(FACTORY_ID, null, " chicken ", request);

        verify(materialTypeRepository).filterBySegmentPrefixAndKeyword(
                eq(FACTORY_ID), eq(""), eq("chicken"), any(Pageable.class));
    }

    private RawMaterialTypeDTO validRequest(String segmentCode) {
        return RawMaterialTypeDTO.builder()
                .name("牛肉")
                .category("原料")
                .unit("kg")
                .taxTreatment(TaxTreatment.EXEMPT)
                .taxExemptionReason("自产免税")
                .taxIncludedUnitPrice(BigDecimal.ONE)
                .segmentCode(segmentCode)
                .build();
    }

    private RawMaterialType existing(String code) {
        RawMaterialType material = new RawMaterialType();
        material.setId("RMT-1");
        material.setFactoryId(FACTORY_ID);
        material.setCode(code);
        material.setName("旧名称");
        material.setCategory("旧分类");
        material.setUnit("kg");
        material.setTaxTreatment(TaxTreatment.EXEMPT);
        material.setTaxExemptionReason("自产免税");
        material.setTaxIncludedUnitPrice(BigDecimal.ONE);
        material.setCreatedBy(1L);
        material.setIsActive(true);
        material.setCreatedAt(LocalDateTime.now());
        material.setUpdatedAt(LocalDateTime.now());
        return material;
    }

    private void stubValidChain() {
        stubNode(L3, (short) 3, L2, "牛肉", true);
        stubNode(L2, (short) 2, L1, "肉类", true);
        stubNode(L1, (short) 1, null, "原料", true);
    }

    private void stubNode(String code, short level, String parent, String label, boolean active) {
        MaterialCodeSegment node = MaterialCodeSegment.builder()
                .factoryId(FACTORY_ID)
                .segmentCode(code)
                .level(level)
                .parentCode(parent)
                .segmentLabel(label)
                .isActive(active)
                .build();
        when(segmentRepository.findByFactoryIdAndSegmentCode(FACTORY_ID, code))
                .thenReturn(Optional.of(node));
        if (level == 3 && active) {
            lenient().when(segmentRepository.lockByFactoryIdAndSegmentCode(FACTORY_ID, code))
                    .thenReturn(Optional.of(node));
        }
    }
}
