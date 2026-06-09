package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.RawMaterialType;
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
                excelUtil);
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

    // ─────────────────────────────────────────────────────────────
    // 1. primaryCode auto-extract from code prefix
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("primaryCode 自动提取")
    class PrimaryCodeAutoExtract {

        @Test
        @DisplayName("DTO 不传 primaryCode, code >= 3 位 → 自动提取前3位")
        void noPrimaryCodeInDto_autoExtractsFromCode() {
            when(materialTypeRepository.existsByFactoryIdAndCode(FACTORY_ID, "RL001")).thenReturn(false);

            ArgumentCaptor<RawMaterialType> captor = ArgumentCaptor.forClass(RawMaterialType.class);
            RawMaterialType saved = savedMaterial("M1", "RL001", "RL0");
            when(materialTypeRepository.save(captor.capture())).thenReturn(saved);

            RawMaterialTypeDTO dto = RawMaterialTypeDTO.builder()
                    .code("RL001")
                    .name("猪舌")
                    .unit("kg")
                    .category("肉类")
                    .primaryCode(null)
                    .build();

            service.createMaterialType(FACTORY_ID, dto);

            RawMaterialType entity = captor.getValue();
            assertEquals("RL0", entity.getPrimaryCode(),
                    "未传 primaryCode 时应从 code 前3位自动提取");
        }

        @Test
        @DisplayName("DTO 传 primaryCode → 优先使用 DTO 的值")
        void primaryCodeInDto_usesProvidedValue() {
            when(materialTypeRepository.existsByFactoryIdAndCode(FACTORY_ID, "0010010001000001")).thenReturn(false);

            ArgumentCaptor<RawMaterialType> captor = ArgumentCaptor.forClass(RawMaterialType.class);
            RawMaterialType saved = savedMaterial("M2", "0010010001000001", "001");
            when(materialTypeRepository.save(captor.capture())).thenReturn(saved);

            RawMaterialTypeDTO dto = RawMaterialTypeDTO.builder()
                    .code("0010010001000001")
                    .name("猪舌")
                    .unit("kg")
                    .category("肉类")
                    .primaryCode("001")
                    .build();

            service.createMaterialType(FACTORY_ID, dto);

            RawMaterialType entity = captor.getValue();
            assertEquals("001", entity.getPrimaryCode(), "应优先使用 DTO 传入的 primaryCode");
        }

        @Test
        @DisplayName("convertToDTO 正确 map primaryCode")
        void convertToDTO_mapsPrimaryCode() {
            RawMaterialType m = savedMaterial("M3", "001RL001", "001");
            when(materialTypeRepository.findByFactoryIdAndIsActive(FACTORY_ID, true))
                    .thenReturn(List.of(m));

            List<RawMaterialTypeDTO> dtos = service.getActiveMaterialTypes(FACTORY_ID);
            assertEquals(1, dtos.size());
            assertEquals("001", dtos.get(0).getPrimaryCode(), "convertToDTO 应 map primaryCode");
        }

        @Test
        @DisplayName("update: primaryCode 传 null → 不覆盖已有值 (null-guard)")
        void update_nullPrimaryCode_doesNotOverwriteExisting() {
            RawMaterialType existing = savedMaterial("M4", "RL001", "001");
            when(materialTypeRepository.findById("M4")).thenReturn(Optional.of(existing));

            ArgumentCaptor<RawMaterialType> captor = ArgumentCaptor.forClass(RawMaterialType.class);
            when(materialTypeRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            RawMaterialTypeDTO updateDto = RawMaterialTypeDTO.builder()
                    .name("猪舌更名")
                    .unit("kg")
                    .primaryCode(null)  // null — should NOT overwrite
                    .build();

            service.updateMaterialType(FACTORY_ID, "M4", updateDto);

            RawMaterialType saved = captor.getValue();
            assertEquals("001", saved.getPrimaryCode(), "null primaryCode update 不应覆盖已有值");
        }

        @Test
        @DisplayName("update: primaryCode 传新值 → 更新")
        void update_nonNullPrimaryCode_updatesValue() {
            RawMaterialType existing = savedMaterial("M5", "RL001", "001");
            when(materialTypeRepository.findById("M5")).thenReturn(Optional.of(existing));

            ArgumentCaptor<RawMaterialType> captor = ArgumentCaptor.forClass(RawMaterialType.class);
            when(materialTypeRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            RawMaterialTypeDTO updateDto = RawMaterialTypeDTO.builder()
                    .name("猪舌")
                    .unit("kg")
                    .primaryCode("002")  // new value
                    .build();

            service.updateMaterialType(FACTORY_ID, "M5", updateDto);

            RawMaterialType saved = captor.getValue();
            assertEquals("002", saved.getPrimaryCode(), "非 null primaryCode 应更新");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 16-digit code generator
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("16位编码生成器")
    class SixteenDigitCodeGen {

        @Test
        @DisplayName("工厂有字典 + segmentCode 10位 + 无已有16位码 → 000001 序号")
        void hasDictionary_noExistingCode_generates000001() {
            when(materialCodeSegmentRepository.countByFactoryIdAndLevel(FACTORY_ID, (short) 1))
                    .thenReturn(3L);
            when(materialTypeRepository.findCodesByFactoryIdAndSegmentPrefix(FACTORY_ID, "0010010001"))
                    .thenReturn(Collections.emptyList());

            String code = service.generateNextCode(FACTORY_ID, "肉类", "0010010001");
            assertEquals("0010010001000001", code);
        }

        @Test
        @DisplayName("已有码 0010010001000005 → 生成 0010010001000006")
        void existingCode000005_generates000006() {
            when(materialCodeSegmentRepository.countByFactoryIdAndLevel(FACTORY_ID, (short) 1))
                    .thenReturn(3L);
            when(materialTypeRepository.findCodesByFactoryIdAndSegmentPrefix(FACTORY_ID, "0010010001"))
                    .thenReturn(List.of("0010010001000003", "0010010001000005", "0010010001000001"));

            String code = service.generateNextCode(FACTORY_ID, "肉类", "0010010001");
            assertEquals("0010010001000006", code);
        }

        @Test
        @DisplayName("无字典 → fallback SP4 扁平方案")
        void noDictionary_fallbackToFlat() {
            when(materialCodeSegmentRepository.countByFactoryIdAndLevel(FACTORY_ID, (short) 1))
                    .thenReturn(0L);
            when(materialTypeRepository.findCodesByFactoryIdAndCodePrefix(FACTORY_ID, "RL"))
                    .thenReturn(Collections.emptyList());

            String code = service.generateNextCode(FACTORY_ID, "肉类", "0010010001");
            assertEquals("RL001", code);
        }

        @Test
        @DisplayName("segmentCode null → fallback SP4 扁平方案")
        void nullSegmentCode_fallbackToFlat() {
            when(materialTypeRepository.findCodesByFactoryIdAndCodePrefix(FACTORY_ID, "RL"))
                    .thenReturn(Collections.emptyList());

            String code = service.generateNextCode(FACTORY_ID, "肉类", null);
            assertEquals("RL001", code);
        }

        @Test
        @DisplayName("segmentCode 非10位 → fallback SP4")
        void shortSegmentCode_fallbackToFlat() {
            when(materialTypeRepository.findCodesByFactoryIdAndCodePrefix(FACTORY_ID, "RL"))
                    .thenReturn(Collections.emptyList());

            // 6-digit — should fallback
            String code = service.generateNextCode(FACTORY_ID, "肉类", "001001");
            assertEquals("RL001", code);
        }

        @Test
        @DisplayName("createMaterialType 传 segmentCode → 走16位路径生成 code")
        void createMaterialType_withSegmentCode_generates16DigitCode() {
            when(materialCodeSegmentRepository.countByFactoryIdAndLevel(FACTORY_ID, (short) 1))
                    .thenReturn(3L);
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
}
