package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.MaterialSuggestDTO;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.entity.RawMaterialType;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T159-B-codegen unit tests.
 *
 * <p>Covers:
 * <ol>
 *   <li>prefix mapping — 原料→YL, 肉类→RL, 包材→BC, unknown→WL, null→WL</li>
 *   <li>sequence increment (max numeric suffix +1, zero-pad 3 digits)</li>
 *   <li>sequence from 001 when no existing code with that prefix</li>
 *   <li>suggestFields returns multi-field DTO (incl. level1PerLevel2/level2Unit) when match found</li>
 *   <li>suggestFields returns all-null DTO when no match found</li>
 *   <li>create without code → auto-generates code and the created entity has it (roundtrip)</li>
 * </ol>
 *
 * @since 2026-06-08 T159-B-codegen
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("T159-B-codegen: 原料编码自动生成 + 多字段 suggest")
class RawMaterialTypeCodeGenTest {

    private static final String FACTORY_ID = "F001";

    @Mock
    private RawMaterialTypeRepository materialTypeRepository;
    @Mock
    private MaterialBatchRepository materialBatchRepository;
    @Mock
    private ConversionRepository conversionRepository;
    @Mock
    private MaterialPackagingHierarchyRepository packagingRepository;
    @Mock
    private MaterialCodeSegmentRepository materialCodeSegmentRepository;
    @Mock
    private ExcelUtil excelUtil;

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

    // =========================================================
    // 1. Prefix mapping
    // =========================================================

    @Nested
    @DisplayName("前缀映射")
    class PrefixMapping {

        @Test
        @DisplayName("原料 → YL")
        void category_原料_yieldsYL() {
            assertEquals("YL", RawMaterialTypeServiceImpl.getMaterialCategoryPrefix("原料"));
        }

        @Test
        @DisplayName("肉类 → RL")
        void category_肉类_yieldsRL() {
            assertEquals("RL", RawMaterialTypeServiceImpl.getMaterialCategoryPrefix("肉类"));
        }

        @Test
        @DisplayName("包材 → BC")
        void category_包材_yieldsBC() {
            assertEquals("BC", RawMaterialTypeServiceImpl.getMaterialCategoryPrefix("包材"));
        }

        @Test
        @DisplayName("未知类别 → WL")
        void category_unknown_yieldsWL() {
            assertEquals("WL", RawMaterialTypeServiceImpl.getMaterialCategoryPrefix("海水鱼"));
        }

        @Test
        @DisplayName("null → WL")
        void category_null_yieldsWL() {
            assertEquals("WL", RawMaterialTypeServiceImpl.getMaterialCategoryPrefix(null));
        }

        @Test
        @DisplayName("空字符串 → WL")
        void category_blank_yieldsWL() {
            assertEquals("WL", RawMaterialTypeServiceImpl.getMaterialCategoryPrefix("  "));
        }
    }

    // =========================================================
    // 2. Sequence increment
    // =========================================================

    @Nested
    @DisplayName("序列号生成")
    class SequenceGeneration {

        @Test
        @DisplayName("无同前缀记录 → 001")
        void noExistingCodes_startsAt001() {
            when(materialTypeRepository.findCodesByFactoryIdAndCodePrefix(FACTORY_ID, "YL"))
                    .thenReturn(Collections.emptyList());

            String code = service.generateNextCode(FACTORY_ID, "原料");
            assertEquals("YL001", code);
        }

        @Test
        @DisplayName("已有 YL001~YL005 → 生成 YL006")
        void existingCodesYL001to005_generates006() {
            when(materialTypeRepository.findCodesByFactoryIdAndCodePrefix(FACTORY_ID, "YL"))
                    .thenReturn(List.of("YL001", "YL003", "YL005", "YL002", "YL004"));

            String code = service.generateNextCode(FACTORY_ID, "原料");
            assertEquals("YL006", code);
        }

        @Test
        @DisplayName("最大序号 RL099 → 生成 RL100")
        void existingRLcodes_maxAt099_generates100() {
            when(materialTypeRepository.findCodesByFactoryIdAndCodePrefix(FACTORY_ID, "RL"))
                    .thenReturn(List.of("RL010", "RL099", "RL050"));

            String code = service.generateNextCode(FACTORY_ID, "肉类");
            assertEquals("RL100", code);
        }

        @Test
        @DisplayName("非数字后缀编码被忽略, 正常取数字后缀最大值")
        void nonNumericSuffixIgnored() {
            when(materialTypeRepository.findCodesByFactoryIdAndCodePrefix(FACTORY_ID, "BC"))
                    .thenReturn(List.of("BC001", "BC-SPECIAL", "BCXXX", "BC007"));

            String code = service.generateNextCode(FACTORY_ID, "包材");
            assertEquals("BC008", code);
        }

        @Test
        @DisplayName("previewMaterialCode delegates to generateNextCode")
        void previewMaterialCode_delegatesCorrectly() {
            when(materialTypeRepository.findCodesByFactoryIdAndCodePrefix(FACTORY_ID, "YL"))
                    .thenReturn(List.of("YL001", "YL002"));

            String code = service.previewMaterialCode(FACTORY_ID, "原料");
            assertEquals("YL003", code);
        }
    }

    // =========================================================
    // 3. suggestFields: multi-field return
    // =========================================================

    @Nested
    @DisplayName("suggestFields 多字段建议")
    class SuggestFields {

        private RawMaterialType buildMatch(String id, String unit, String category, String storageType,
                                           Integer shelfLifeDays) {
            RawMaterialType m = new RawMaterialType();
            m.setId(id);
            m.setFactoryId(FACTORY_ID);
            m.setUnit(unit);
            m.setCategory(category);
            m.setStorageType(storageType);
            m.setShelfLifeDays(shelfLifeDays);
            m.setCode("YL001");
            m.setName("猪舌");
            m.setCreatedBy(1L);
            m.setIsActive(true);
            m.setCreatedAt(LocalDateTime.now());
            m.setUpdatedAt(LocalDateTime.now());
            return m;
        }

        @Test
        @DisplayName("找到匹配原料: 返回包含所有字段的 DTO")
        void matchFound_returnsAllFields() {
            RawMaterialType match = buildMatch("M001", "kg", "肉类", "FROZEN", 90);

            when(materialTypeRepository.findSimilarByNameAndCategory(eq(FACTORY_ID), eq("猪舌"),
                    eq("肉类"), any())).thenReturn(List.of(match));

            // packaging hierarchy for this material
            MaterialPackagingHierarchy pkg = new MaterialPackagingHierarchy();
            pkg.setLevel1PerLevel2(new BigDecimal("10.0000"));
            pkg.setLevel2Unit("箱");
            when(packagingRepository.findByMaterialTypeId("M001")).thenReturn(Optional.of(pkg));

            MaterialSuggestDTO dto = service.suggestFields(FACTORY_ID, "猪舌", "肉类");

            assertEquals("kg", dto.getUnit());
            assertEquals("肉类", dto.getCategory());
            assertEquals("FROZEN", dto.getStorageType());
            assertEquals(90, dto.getShelfLifeDays());
            assertEquals(new BigDecimal("10.0000"), dto.getLevel1PerLevel2());
            assertEquals("箱", dto.getLevel2Unit());
        }

        @Test
        @DisplayName("找到匹配但无包装层级: level1PerLevel2 / level2Unit 为 null")
        void matchFound_noPackaging_packagingFieldsNull() {
            RawMaterialType match = buildMatch("M002", "g", "肉类", "REFRIGERATED", 30);

            when(materialTypeRepository.findSimilarByNameAndCategory(eq(FACTORY_ID), eq("牛腱"),
                    isNull(), any())).thenReturn(List.of(match));
            when(packagingRepository.findByMaterialTypeId("M002")).thenReturn(Optional.empty());

            MaterialSuggestDTO dto = service.suggestFields(FACTORY_ID, "牛腱", null);

            assertEquals("g", dto.getUnit());
            assertNull(dto.getLevel1PerLevel2());
            assertNull(dto.getLevel2Unit());
        }

        @Test
        @DisplayName("无匹配: 返回全 null DTO (不抛异常)")
        void noMatch_returnsAllNullDto() {
            when(materialTypeRepository.findSimilarByNameAndCategory(anyString(), anyString(),
                    any(), any())).thenReturn(Collections.emptyList());

            MaterialSuggestDTO dto = service.suggestFields(FACTORY_ID, "完全不存在的原料XYZ", null);

            assertNotNull(dto, "DTO 本身不应为 null");
            assertNull(dto.getUnit());
            assertNull(dto.getCategory());
            assertNull(dto.getStorageType());
            assertNull(dto.getShelfLifeDays());
            assertNull(dto.getLevel1PerLevel2());
            assertNull(dto.getLevel2Unit());
            // packaging should never be called when no match
            verifyNoInteractions(packagingRepository);
        }

        @Test
        @DisplayName("name 为 null: 返回全 null DTO")
        void nullName_returnsAllNullDto() {
            MaterialSuggestDTO dto = service.suggestFields(FACTORY_ID, null, "肉类");
            assertNotNull(dto);
            assertNull(dto.getUnit());
            verifyNoInteractions(materialTypeRepository);
        }
    }

    // =========================================================
    // 4. create without code → auto-generates + roundtrip
    // =========================================================

    @Nested
    @DisplayName("createMaterialType 无 code → 自动生成 + 读回有 code")
    class CreateWithAutoCode {

        @Test
        @DisplayName("DTO.code 为 null → 生成 YL001 且保存实体包含该 code")
        void createWithNullCode_autoGeneratesCode_roundtrip() {
            // Given: no existing codes for prefix YL
            when(materialTypeRepository.findCodesByFactoryIdAndCodePrefix(FACTORY_ID, "YL"))
                    .thenReturn(Collections.emptyList());
            // No collision
            when(materialTypeRepository.existsByFactoryIdAndCode(FACTORY_ID, "YL001"))
                    .thenReturn(false);

            // Capture the entity passed to save
            ArgumentCaptor<RawMaterialType> captor = ArgumentCaptor.forClass(RawMaterialType.class);
            RawMaterialType saved = new RawMaterialType();
            saved.setId("RMT_1234");
            saved.setFactoryId(FACTORY_ID);
            saved.setCode("YL001");
            saved.setName("测试原料");
            saved.setUnit("kg");
            saved.setCategory("原料");
            saved.setCreatedBy(1L);
            saved.setIsActive(true);
            saved.setCreatedAt(LocalDateTime.now());
            saved.setUpdatedAt(LocalDateTime.now());
            when(materialTypeRepository.save(captor.capture())).thenReturn(saved);

            RawMaterialTypeDTO dto = RawMaterialTypeDTO.builder()
                    .name("测试原料")
                    .code(null)           // intentionally null — triggers auto-gen
                    .unit("kg")
                    .category("原料")
                    .build();

            RawMaterialTypeDTO result = service.createMaterialType(FACTORY_ID, dto);

            // The saved entity must have code YL001
            RawMaterialType capturedEntity = captor.getValue();
            assertEquals("YL001", capturedEntity.getCode(),
                    "保存到 repository 的实体应包含自动生成的编码 YL001");

            // The returned DTO must also have the code (roundtrip)
            assertEquals("YL001", result.getCode(),
                    "convertToDTO 后返回给调用方的 code 必须非 null (DTO往返完整)");
        }

        @Test
        @DisplayName("DTO.code 为空白 → 自动生成 RL001 (category=肉类)")
        void createWithBlankCode_autoGeneratesRL001() {
            when(materialTypeRepository.findCodesByFactoryIdAndCodePrefix(FACTORY_ID, "RL"))
                    .thenReturn(Collections.emptyList());
            when(materialTypeRepository.existsByFactoryIdAndCode(FACTORY_ID, "RL001"))
                    .thenReturn(false);

            RawMaterialType saved = new RawMaterialType();
            saved.setId("RMT_2");
            saved.setFactoryId(FACTORY_ID);
            saved.setCode("RL001");
            saved.setName("猪肉");
            saved.setUnit("kg");
            saved.setCategory("肉类");
            saved.setCreatedBy(1L);
            saved.setIsActive(true);
            saved.setCreatedAt(LocalDateTime.now());
            saved.setUpdatedAt(LocalDateTime.now());
            when(materialTypeRepository.save(any())).thenReturn(saved);

            RawMaterialTypeDTO dto = RawMaterialTypeDTO.builder()
                    .name("猪肉")
                    .code("  ")         // blank — triggers auto-gen
                    .unit("kg")
                    .category("肉类")
                    .build();

            RawMaterialTypeDTO result = service.createMaterialType(FACTORY_ID, dto);
            assertEquals("RL001", result.getCode());
        }

        @Test
        @DisplayName("DTO.code 手动提供 → 不走自动生成, 直接使用")
        void createWithExplicitCode_usesProvidedCode() {
            when(materialTypeRepository.existsByFactoryIdAndCode(FACTORY_ID, "BC999"))
                    .thenReturn(false);

            RawMaterialType saved = new RawMaterialType();
            saved.setId("RMT_3");
            saved.setFactoryId(FACTORY_ID);
            saved.setCode("BC999");
            saved.setName("吸塑盒");
            saved.setUnit("个");
            saved.setCategory("包材");
            saved.setCreatedBy(1L);
            saved.setIsActive(true);
            saved.setCreatedAt(LocalDateTime.now());
            saved.setUpdatedAt(LocalDateTime.now());
            when(materialTypeRepository.save(any())).thenReturn(saved);

            RawMaterialTypeDTO dto = RawMaterialTypeDTO.builder()
                    .name("吸塑盒")
                    .code("BC999")      // manually provided
                    .unit("个")
                    .category("包材")
                    .build();

            RawMaterialTypeDTO result = service.createMaterialType(FACTORY_ID, dto);
            assertEquals("BC999", result.getCode());
            // generateNextCode should NOT have been called
            verify(materialTypeRepository, never()).findCodesByFactoryIdAndCodePrefix(any(), any());
        }
    }
}
