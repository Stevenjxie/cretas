package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP8: RawMaterialType primaryCode auto-extract + 16-digit code generator + searchByCodePrefix.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SP8: RawMaterialType 编码系统")
class RawMaterialTypeSp8Test {

    private static final String FACTORY_ID = "F006";

    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private ConversionRepository conversionRepository;
    @Mock private MaterialPackagingHierarchyRepository packagingRepository;
    @Mock private MaterialCodeSegmentRepository materialCodeSegmentRepository;
    @Mock private ExcelUtil excelUtil;

    private RawMaterialTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RawMaterialTypeServiceImpl(
                materialTypeRepository,
                materialBatchRepository,
                conversionRepository,
                packagingRepository,
                materialCodeSegmentRepository,
                excelUtil,
                org.mockito.Mockito.mock(com.cretas.aims.service.workflow.WorkflowUnitReviewService.class));
    }

    private RawMaterialType savedMaterial(String id, String code, String primaryCode) {
        RawMaterialType m = new RawMaterialType();
        m.setId(id);
        m.setFactoryId(FACTORY_ID);
        m.setCode(code);
        m.setPrimaryCode(primaryCode);
        m.setName("测试物料");
        m.setUnit("kg");
        m.setCategory("肉类");
        m.setCreatedBy(1L);
        m.setIsActive(true);
        m.setCreatedAt(LocalDateTime.now());
        m.setUpdatedAt(LocalDateTime.now());
        return m;
    }

    /**
     * NOTE: {@code isActive} must be set explicitly — Lombok {@code @Builder.Default}
     * strips the field initializer, so {@code new MaterialCodeSegment()} leaves it null
     * and {@code requireSegment} would reject the node as inactive.
     */
    private static MaterialCodeSegment segment(short level, String code, String parentCode, String label) {
        MaterialCodeSegment s = new MaterialCodeSegment();
        s.setFactoryId(FACTORY_ID);
        s.setLevel(level);
        s.setSegmentCode(code);
        s.setParentCode(parentCode);
        s.setSegmentLabel(label);
        s.setIsActive(true);
        return s;
    }

    /**
     * Stub the full L1→L2→L3 dictionary chain for {@code 0010010001} (肉类 / 猪头 / 猪舌).
     *
     * <p>Since {@code 1f6e63b6bf "fix(material): enforce hierarchical 16-digit codes"},
     * create/update no longer probe {@code countByFactoryIdAndLevel} to decide whether
     * the 16-digit scheme is on — the scheme is unconditional and the service instead
     * walks the parent chain via {@code findByFactoryIdAndSegmentCode}. Tests that used
     * to stub the old dictionary-enabled counter must stub this chain instead.
     */
    private void stubSegmentChain() {
        when(materialCodeSegmentRepository.findByFactoryIdAndSegmentCode(FACTORY_ID, "0010010001"))
                .thenReturn(Optional.of(segment((short) 3, "0010010001", "001001", "猪舌")));
        when(materialCodeSegmentRepository.findByFactoryIdAndSegmentCode(FACTORY_ID, "001001"))
                .thenReturn(Optional.of(segment((short) 2, "001001", "001", "猪头")));
        when(materialCodeSegmentRepository.findByFactoryIdAndSegmentCode(FACTORY_ID, "001"))
                .thenReturn(Optional.of(segment((short) 1, "001", null, "肉类")));
    }

    /** L3 row lock taken before the sequence scan (create + backfill-on-update). */
    private void stubSegmentLock() {
        when(materialCodeSegmentRepository.lockByFactoryIdAndSegmentCode(FACTORY_ID, "0010010001"))
                .thenReturn(Optional.of(segment((short) 3, "0010010001", "001001", "猪舌")));
    }

    // ─────────────────────────────────────────────────────────────
    // 1. primaryCode auto-extract from code prefix
    // ─────────────────────────────────────────────────────────────

    /**
     * primaryCode 来源.
     *
     * <p>Originally these tests asserted the SP8 rule "primaryCode = code 前3位, DTO
     * 传值优先". {@code 1f6e63b6bf "fix(material): enforce hierarchical 16-digit codes"}
     * (and its follow-ups #1392 / #1545) replaced that with a single source of truth:
     * primaryCode is <b>always</b> the validated L1 ancestor's segmentCode, and any
     * wire-supplied {@code code}/{@code primaryCode} is ignored (create) or refused
     * (update). Assertions realigned to that contract.
     */
    @Nested
    @DisplayName("primaryCode 由 L1 分段派生")
    class PrimaryCodeDerivedFromSegment {

        @Test
        @DisplayName("create: primaryCode 取自 L1 分段, 手工 code 被忽略并改为生成的16位码")
        void create_primaryCodeComesFromL1Segment_manualCodeIgnored() {
            stubSegmentChain();
            stubSegmentLock();
            when(materialTypeRepository.findCodesByFactoryIdAndSegmentPrefix(FACTORY_ID, "0010010001"))
                    .thenReturn(Collections.emptyList());
            when(materialTypeRepository.existsByFactoryIdAndCode(FACTORY_ID, "0010010001000001"))
                    .thenReturn(false);

            ArgumentCaptor<RawMaterialType> captor = ArgumentCaptor.forClass(RawMaterialType.class);
            when(materialTypeRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            RawMaterialTypeDTO dto = RawMaterialTypeDTO.builder()
                    .code("RL001")          // legacy flat code on the wire — must NOT survive
                    .name("猪舌")
                    .unit("kg")
                    .category("肉类")
                    .segmentCode("0010010001")
                    .primaryCode(null)
                    .build();

            service.createMaterialType(FACTORY_ID, dto);

            RawMaterialType entity = captor.getValue();
            assertEquals("001", entity.getPrimaryCode(), "primaryCode 必须来自 L1 分段");
            assertEquals("0010010001000001", entity.getCode(), "手工 code 被生成的16位码替换");
        }

        @Test
        @DisplayName("create: DTO 传 primaryCode 也不采信 — L1 分段是唯一真源")
        void create_dtoPrimaryCodeIgnored_l1IsSingleSourceOfTruth() {
            stubSegmentChain();
            stubSegmentLock();
            when(materialTypeRepository.findCodesByFactoryIdAndSegmentPrefix(FACTORY_ID, "0010010001"))
                    .thenReturn(Collections.emptyList());
            when(materialTypeRepository.existsByFactoryIdAndCode(FACTORY_ID, "0010010001000001"))
                    .thenReturn(false);

            ArgumentCaptor<RawMaterialType> captor = ArgumentCaptor.forClass(RawMaterialType.class);
            when(materialTypeRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            RawMaterialTypeDTO dto = RawMaterialTypeDTO.builder()
                    .name("猪舌")
                    .unit("kg")
                    .category("肉类")
                    .segmentCode("0010010001")
                    .primaryCode("999")     // wire value — must be overridden by L1
                    .build();

            service.createMaterialType(FACTORY_ID, dto);

            assertEquals("001", captor.getValue().getPrimaryCode(),
                    "DTO 的 primaryCode 不应覆盖 L1 派生值");
        }

        @Test
        @DisplayName("create: 未选分段 → 400 拒绝 (不再回落扁平编码)")
        void create_withoutSegmentCode_rejected() {
            RawMaterialTypeDTO dto = RawMaterialTypeDTO.builder()
                    .code("RL001")
                    .name("猪舌")
                    .unit("kg")
                    .category("肉类")
                    .build();

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.createMaterialType(FACTORY_ID, dto));
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("必须选择有效的L3十位编码"), ex.getMessage());
            verify(materialTypeRepository, never()).save(any(RawMaterialType.class));
        }

        @Test
        @DisplayName("convertToDTO 正确 map primaryCode")
        void convertToDTO_mapsPrimaryCode() {
            RawMaterialType m = savedMaterial("M3", "0010010001000001", "001");
            when(materialTypeRepository.findByFactoryIdAndIsActive(FACTORY_ID, true))
                    .thenReturn(List.of(m));

            List<RawMaterialTypeDTO> dtos = service.getActiveMaterialTypes(FACTORY_ID);
            assertEquals(1, dtos.size());
            assertEquals("001", dtos.get(0).getPrimaryCode(), "convertToDTO 应 map primaryCode");
        }

        @Test
        @DisplayName("update: 分段由已有16位码前10位推断, DTO 的 primaryCode 被忽略")
        void update_primaryCodeReDerivedFromExistingCode_dtoValueIgnored() {
            RawMaterialType existing = savedMaterial("M4", "0010010001000001", "001");
            when(materialTypeRepository.findById("M4")).thenReturn(Optional.of(existing));
            stubSegmentChain();

            ArgumentCaptor<RawMaterialType> captor = ArgumentCaptor.forClass(RawMaterialType.class);
            when(materialTypeRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            RawMaterialTypeDTO updateDto = RawMaterialTypeDTO.builder()
                    .name("猪舌更名")
                    .unit("kg")
                    .primaryCode("002")   // wire value — must NOT win over the L1 ancestor
                    .build();

            service.updateMaterialType(FACTORY_ID, "M4", updateDto);

            RawMaterialType saved = captor.getValue();
            assertEquals("001", saved.getPrimaryCode(), "primaryCode 恒等于 L1 分段, 不接受手工改写");
            assertEquals("0010010001000001", saved.getCode(), "已是16位码 → 不重新生成");
        }

        @Test
        @DisplayName("update: 手工改 16 位 code → 400 拒绝")
        void update_manualCodeChange_rejected() {
            RawMaterialType existing = savedMaterial("M5", "0010010001000001", "001");
            when(materialTypeRepository.findById("M5")).thenReturn(Optional.of(existing));
            stubSegmentChain();

            RawMaterialTypeDTO updateDto = RawMaterialTypeDTO.builder()
                    .code("0010010001000009")
                    .name("猪舌")
                    .unit("kg")
                    .build();

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.updateMaterialType(FACTORY_ID, "M5", updateDto));
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("16位原料编码不可手工修改"), ex.getMessage());
            verify(materialTypeRepository, never()).save(any(RawMaterialType.class));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 16-digit code generator
    // ─────────────────────────────────────────────────────────────

    /**
     * 16位编码生成器.
     *
     * <p>These tests used to stub {@code countByFactoryIdAndLevel} as a "工厂是否启用分段
     * 字典" switch, because the 3-arg {@code generateNextCode} once fell back to the SP4
     * flat scheme when no dictionary existed. {@code 1f6e63b6bf "fix(material): enforce
     * hierarchical 16-digit codes"} deleted that switch — the 16-digit scheme is now
     * unconditional and an invalid segmentCode is always rejected. The dictionary stubs
     * therefore became dead ({@code UnnecessaryStubbingException}) and the fallback
     * expectation became wrong; both are corrected here rather than papered over with
     * {@code lenient()}.
     */
    @Nested
    @DisplayName("16位编码生成器")
    class SixteenDigitCodeGen {

        @Test
        @DisplayName("segmentCode 10位 + 无已有16位码 → 000001 序号")
        void noExistingCode_generates000001() {
            when(materialTypeRepository.findCodesByFactoryIdAndSegmentPrefix(FACTORY_ID, "0010010001"))
                    .thenReturn(Collections.emptyList());

            String code = service.generateNextCode(FACTORY_ID, "肉类", "0010010001");
            assertEquals("0010010001000001", code);
        }

        @Test
        @DisplayName("已有码 0010010001000005 → 生成 0010010001000006")
        void existingCode000005_generates000006() {
            when(materialTypeRepository.findCodesByFactoryIdAndSegmentPrefix(FACTORY_ID, "0010010001"))
                    .thenReturn(List.of("0010010001000003", "0010010001000005", "0010010001000001"));

            String code = service.generateNextCode(FACTORY_ID, "肉类", "0010010001");
            assertEquals("0010010001000006", code);
        }

        @Test
        @DisplayName("2-arg 遗留扁平生成器仍可用 (previewMaterialCode(factoryId, category) 走这条)")
        void legacyTwoArgGenerator_stillFlat() {
            when(materialTypeRepository.findCodesByFactoryIdAndCodePrefix(FACTORY_ID, "RL"))
                    .thenReturn(Collections.emptyList());

            // The 3-arg overload no longer falls back to this; only the 2-arg legacy
            // entry point (previewMaterialCode without a segment) still produces RL001.
            assertEquals("RL001", service.generateNextCode(FACTORY_ID, "肉类"));
        }

        @Test
        @DisplayName("segmentCode 缺失 -> 400 拒绝 (无条件, 不再看工厂是否配字典)")
        void nullSegmentCode_rejects() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.generateNextCode(FACTORY_ID, "肉类", null));
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("本工厂启用 16 位编码"));
            assertTrue(ex.getMessage().contains("请用分段选择器生成"));
            verifyNoInteractions(materialCodeSegmentRepository);
        }

        @Test
        @DisplayName("segmentCode 非10位 -> 400 拒绝")
        void shortSegmentCode_rejects() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.generateNextCode(FACTORY_ID, "肉类", "001001"));
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("本工厂启用 16 位编码"));
            assertTrue(ex.getMessage().contains("请用分段选择器生成"));
        }

        @Test
        @DisplayName("createMaterialType 传 segmentCode → 走16位路径生成 code")
        void createMaterialType_withSegmentCode_generates16DigitCode() {
            stubSegmentChain();
            stubSegmentLock();
            when(materialTypeRepository.findCodesByFactoryIdAndSegmentPrefix(FACTORY_ID, "0010010001"))
                    .thenReturn(Collections.emptyList());
            when(materialTypeRepository.existsByFactoryIdAndCode(FACTORY_ID, "0010010001000001"))
                    .thenReturn(false);

            ArgumentCaptor<RawMaterialType> captor = ArgumentCaptor.forClass(RawMaterialType.class);
            RawMaterialType saved = savedMaterial("M6", "0010010001000001", "001");
            when(materialTypeRepository.save(captor.capture())).thenReturn(saved);

            RawMaterialTypeDTO dto = RawMaterialTypeDTO.builder()
                    .name("猪舌")
                    .unit("kg")
                    .category("肉类")
                    .segmentCode("0010010001")
                    .build();

            service.createMaterialType(FACTORY_ID, dto);

            RawMaterialType entity = captor.getValue();
            assertEquals("0010010001000001", entity.getCode(),
                    "传入 segmentCode 时应走16位生成路径");
        }

        @Test
        @DisplayName("createMaterialType 只传扁平 code / 不选分段 -> 400 拒绝")
        void createMaterialType_manualFlatCodeRejected() {
            RawMaterialTypeDTO dto = RawMaterialTypeDTO.builder()
                    .code("RL001")
                    .name("猪舌")
                    .unit("kg")
                    .category("肉类")
                    .build();

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.createMaterialType(FACTORY_ID, dto));
            assertEquals(400, ex.getCode());
            // Message moved from strict16CodeException to invalidSegment when the
            // dictionary probe was removed: create now fails at the segment-chain
            // validation instead of at the code generator.
            assertTrue(ex.getMessage().contains("必须选择有效的L3十位编码"), ex.getMessage());
            verify(materialTypeRepository, never()).save(any(RawMaterialType.class));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2b. previewMaterialCode (3-arg: category + segmentCode)
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("previewMaterialCode(factoryId, category, segmentCode)")
    class PreviewMaterialCodeWithSegment {

        // Same 1f6e63b6bf change as SixteenDigitCodeGen: the "工厂是否启用分段字典" probe
        // (countByFactoryIdAndLevel) is gone from this path, so stubbing it was dead code.

        @Test
        @DisplayName("segmentCode 10位 + 无已有码 → 16位 000001")
        void segmentCode10Digit_returns16Digit() {
            when(materialTypeRepository.findCodesByFactoryIdAndSegmentPrefix(FACTORY_ID, "0010010001"))
                    .thenReturn(java.util.Collections.emptyList());

            String code = service.previewMaterialCode(FACTORY_ID, "肉类", "0010010001");
            assertEquals("0010010001000001", code);
        }

        @Test
        @DisplayName("segmentCode null -> 400 拒绝")
        void nullSegmentCode_rejects() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.previewMaterialCode(FACTORY_ID, "肉类", null));
            assertEquals(400, ex.getCode());
        }

        @Test
        @DisplayName("segmentCode 非10位 -> 400 拒绝")
        void shortSegmentCode_rejects() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.previewMaterialCode(FACTORY_ID, "包材", "001001"));
            assertEquals(400, ex.getCode());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 3. searchByCodePrefix
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("searchByCodePrefix")
    class SearchByCodePrefix {

        @Test
        @DisplayName("有匹配 → 返回 DTO 列表 (最多50条)")
        void matchFound_returnsDtoList() {
            RawMaterialType m1 = savedMaterial("M7", "001001", "001");
            RawMaterialType m2 = savedMaterial("M8", "001002", "001");
            when(materialTypeRepository.findByFactoryIdAndCodeStartingWith(
                    eq(FACTORY_ID), eq("001"), any(Pageable.class)))
                    .thenReturn(List.of(m1, m2));

            List<RawMaterialTypeDTO> results = service.searchByCodePrefix(FACTORY_ID, "001");
            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("无匹配 → 返回空 list")
        void noMatch_returnsEmpty() {
            when(materialTypeRepository.findByFactoryIdAndCodeStartingWith(
                    eq(FACTORY_ID), eq("999"), any(Pageable.class)))
                    .thenReturn(Collections.emptyList());

            List<RawMaterialTypeDTO> results = service.searchByCodePrefix(FACTORY_ID, "999");
            assertNotNull(results);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("空前缀 → 返回空 list (不查数据库)")
        void blankPrefix_returnsEmptyWithoutDbCall() {
            List<RawMaterialTypeDTO> results = service.searchByCodePrefix(FACTORY_ID, "  ");
            assertNotNull(results);
            assertTrue(results.isEmpty());
            verifyNoInteractions(materialTypeRepository);
        }

        @Test
        @DisplayName("null 前缀 → 返回空 list")
        void nullPrefix_returnsEmpty() {
            List<RawMaterialTypeDTO> results = service.searchByCodePrefix(FACTORY_ID, null);
            assertNotNull(results);
            assertTrue(results.isEmpty());
        }
    }

    @Nested
    @DisplayName("按主编码查询")
    class SearchByPrimaryCode {

        @Test
        @DisplayName("primaryCode=001 -> 返回同主编码物料")
        void byPrimaryCode_returnsMappedDtos() {
            RawMaterialType m1 = savedMaterial("M9", "0010010001000001", "001");
            RawMaterialType m2 = savedMaterial("M10", "0010020001000001", "001");
            when(materialTypeRepository.findByFactoryIdAndPrimaryCodeOrderByCodeAsc(FACTORY_ID, "001"))
                    .thenReturn(List.of(m1, m2));

            List<RawMaterialTypeDTO> results = service.getMaterialTypesByPrimaryCode(FACTORY_ID, "001");

            assertEquals(2, results.size());
            assertEquals("001", results.get(0).getPrimaryCode());
            assertEquals("001", results.get(1).getPrimaryCode());
        }

        @Test
        @DisplayName("primaryCode 非3位 -> 400 拒绝")
        void invalidPrimaryCode_rejects() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.getMaterialTypesByPrimaryCode(FACTORY_ID, "01"));
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("主编码"));
        }
    }
}
