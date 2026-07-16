package com.cretas.aims.service.material;

import com.cretas.aims.dto.material.CreateMaterialCodeSegmentRequest;
import com.cretas.aims.dto.material.MaterialCodeSegmentDTO;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.service.material.impl.MaterialCodeSegmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP8: MaterialCodeSegmentService unit tests.
 *
 * <p>Covers:
 * <ol>
 *   <li>getTree — L1→L2→L3 cascading assembly</li>
 *   <li>Factory isolation — cross-factory access rejected</li>
 *   <li>Soft delete — sets deletedAt not hard delete</li>
 *   <li>Parent validation — level 2/3 without parentCode rejected</li>
 *   <li>Duplicate code — 409 thrown</li>
 *   <li>hasSegmentDictionary — true when level-1 nodes exist</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SP8: MaterialCodeSegmentService")
class MaterialCodeSegmentServiceTest {

    private static final String FACTORY_ID = "F006";
    private static final String OTHER_FACTORY = "F001";

    @Mock
    private MaterialCodeSegmentRepository repo;

    @Mock
    private RawMaterialTypeRepository materialTypeRepository;

    private MaterialCodeSegmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MaterialCodeSegmentServiceImpl(repo, materialTypeRepository);
    }

    // ─── helpers ────────────────────────────────────────────────

    private MaterialCodeSegment buildSegment(Long id, String factoryId, short level,
                                              String code, String label, String parentCode) {
        MaterialCodeSegment s = new MaterialCodeSegment();
        s.setId(id);
        s.setFactoryId(factoryId);
        s.setLevel(level);
        s.setSegmentCode(code);
        s.setSegmentLabel(label);
        s.setParentCode(parentCode);
        s.setSortOrder(0);
        s.setIsActive(true);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        return s;
    }

    // ─────────────────────────────────────────────────────────────
    // 1. getTree: L1 → L2 → L3 cascading assembly
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getTree: 三级树形拼装")
    class GetTree {

        @Test
        @DisplayName("两个 L1 各带 L2, L2 各带 L3 → 树正确")
        void fullTree_assemblesCorrectly() {
            MaterialCodeSegment l1a = buildSegment(1L, FACTORY_ID, (short) 1, "001", "原料", null);
            MaterialCodeSegment l2a = buildSegment(2L, FACTORY_ID, (short) 2, "001001", "肉类", "001");
            MaterialCodeSegment l3a = buildSegment(3L, FACTORY_ID, (short) 3, "0010010001", "猪舌", "001001");

            when(repo.findByFactoryIdOrderBySortOrderAscSegmentCodeAsc(FACTORY_ID))
                    .thenReturn(List.of(l1a, l2a, l3a));

            List<MaterialCodeSegmentDTO> tree = service.getTree(FACTORY_ID);

            assertEquals(1, tree.size(), "1 个 L1 节点");
            assertEquals("001", tree.get(0).getSegmentCode());
            assertNotNull(tree.get(0).getChildren(), "L1 应有 L2 子节点");
            assertEquals(1, tree.get(0).getChildren().size());
            assertEquals("001001", tree.get(0).getChildren().get(0).getSegmentCode());
            assertNotNull(tree.get(0).getChildren().get(0).getChildren(), "L2 应有 L3 子节点");
            assertEquals("0010010001", tree.get(0).getChildren().get(0).getChildren().get(0).getSegmentCode());
        }

        @Test
        @DisplayName("无节点 → 返回空 list")
        void emptyFactory_returnsEmptyList() {
            when(repo.findByFactoryIdOrderBySortOrderAscSegmentCodeAsc(FACTORY_ID))
                    .thenReturn(Collections.emptyList());

            List<MaterialCodeSegmentDTO> tree = service.getTree(FACTORY_ID);
            assertNotNull(tree);
            assertTrue(tree.isEmpty());
        }

        @Test
        @DisplayName("L1 无子节点 → children 为 null")
        void l1WithoutChildren_childrenNull() {
            MaterialCodeSegment l1 = buildSegment(1L, FACTORY_ID, (short) 1, "002", "包材", null);
            when(repo.findByFactoryIdOrderBySortOrderAscSegmentCodeAsc(FACTORY_ID))
                    .thenReturn(List.of(l1));

            List<MaterialCodeSegmentDTO> tree = service.getTree(FACTORY_ID);
            assertEquals(1, tree.size());
            assertNull(tree.get(0).getChildren(), "无子节点时 children 应为 null");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. Factory isolation
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("工厂隔离")
    class FactoryIsolation {

        @Test
        @DisplayName("删除时跨工厂 → 403 BusinessException")
        void delete_crossFactory_throws403() {
            MaterialCodeSegment entity = buildSegment(10L, OTHER_FACTORY, (short) 1, "001", "原料", null);
            when(repo.findById(10L)).thenReturn(Optional.of(entity));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.delete(FACTORY_ID, 10L));
            assertEquals(403, ex.getCode());
        }

        @Test
        @DisplayName("更新时跨工厂 → 403 BusinessException")
        void update_crossFactory_throws403() {
            MaterialCodeSegment entity = buildSegment(20L, OTHER_FACTORY, (short) 1, "001", "原料", null);
            when(repo.findById(20L)).thenReturn(Optional.of(entity));

            CreateMaterialCodeSegmentRequest req = CreateMaterialCodeSegmentRequest.builder()
                    .level((short) 1)
                    .segmentCode("001")
                    .segmentLabel("原料")
                    .build();

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.update(FACTORY_ID, 20L, req));
            assertEquals(403, ex.getCode());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 3. Soft delete
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("软删除")
    class SoftDelete {

        @Test
        @DisplayName("delete 设置 deletedAt 而非 hard-delete")
        void delete_setsDeletedAt_notHardDelete() {
            MaterialCodeSegment entity = buildSegment(30L, FACTORY_ID, (short) 1, "001", "原料", null);
            when(repo.findById(30L)).thenReturn(Optional.of(entity));
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.delete(FACTORY_ID, 30L);

            verify(repo, never()).deleteById(anyLong());
            verify(repo, never()).delete(any());
            verify(repo, times(1)).save(argThat(s -> s.getDeletedAt() != null));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 4. Parent validation
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("父节点校验")
    class ParentValidation {

        @Test
        @DisplayName("创建 L2 不指定 parentCode → 400")
        void createL2_withoutParentCode_throws400() {
            CreateMaterialCodeSegmentRequest req = CreateMaterialCodeSegmentRequest.builder()
                    .level((short) 2)
                    .segmentCode("001001")
                    .segmentLabel("肉类")
                    .parentCode(null)
                    .build();
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.create(FACTORY_ID, req));
            assertEquals(400, ex.getCode());
        }

        @Test
        @DisplayName("创建 L2 指定不存在的 parentCode → ResourceNotFoundException")
        void createL2_parentNotFound_throws404() {
            CreateMaterialCodeSegmentRequest req = CreateMaterialCodeSegmentRequest.builder()
                    .level((short) 2)
                    .segmentCode("001001")
                    .segmentLabel("肉类")
                    .parentCode("099")
                    .build();
            when(repo.findByFactoryIdAndSegmentCode(FACTORY_ID, "099")).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> service.create(FACTORY_ID, req));
        }

        @Test
        @DisplayName("创建 L2 指定存在的 parentCode → 成功")
        void createL2_validParent_succeeds() {
            MaterialCodeSegment parent = buildSegment(1L, FACTORY_ID, (short) 1, "001", "原料", null);
            MaterialCodeSegment saved = buildSegment(5L, FACTORY_ID, (short) 2, "001001", "肉类", "001");

            CreateMaterialCodeSegmentRequest req = CreateMaterialCodeSegmentRequest.builder()
                    .level((short) 2)
                    .segmentCode("001001")
                    .segmentLabel("肉类")
                    .parentCode("001")
                    .build();
            when(repo.existsByFactoryIdAndSegmentCode(FACTORY_ID, "001001")).thenReturn(false);
            when(repo.findByFactoryIdAndSegmentCode(FACTORY_ID, "001")).thenReturn(Optional.of(parent));
            when(repo.save(any())).thenReturn(saved);

            MaterialCodeSegmentDTO result = service.create(FACTORY_ID, req);
            assertEquals("001001", result.getSegmentCode());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 5. Duplicate code
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("编码唯一性")
    class DuplicateCode {

        @Test
        @DisplayName("创建重复 segmentCode → 409")
        void create_duplicateCode_throws409() {
            when(repo.existsByFactoryIdAndSegmentCode(FACTORY_ID, "001")).thenReturn(true);

            CreateMaterialCodeSegmentRequest req = CreateMaterialCodeSegmentRequest.builder()
                    .level((short) 1)
                    .segmentCode("001")
                    .segmentLabel("原料")
                    .build();

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.create(FACTORY_ID, req));
            assertEquals(409, ex.getCode());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 6. hasSegmentDictionary
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("hasSegmentDictionary")
    class HasSegmentDictionary {

        @Test
        @DisplayName("有 L1 节点 → true")
        void hasL1Nodes_returnsTrue() {
            when(repo.countByFactoryIdAndLevel(FACTORY_ID, (short) 1)).thenReturn(3L);
            assertTrue(service.hasSegmentDictionary(FACTORY_ID));
        }

        @Test
        @DisplayName("无 L1 节点 → false")
        void noL1Nodes_returnsFalse() {
            when(repo.countByFactoryIdAndLevel(FACTORY_ID, (short) 1)).thenReturn(0L);
            assertFalse(service.hasSegmentDictionary(FACTORY_ID));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 7. generateCode (generate-code 端点)
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("generateCode: 16位编码预览")
    class GenerateCode {

        @Test
        @DisplayName("字典已配置 + 无已有16位码 → 返回 000001 序号")
        void hasDictionary_noExisting_returns000001() {
            when(repo.countByFactoryIdAndLevel(FACTORY_ID, (short) 1)).thenReturn(3L);
            when(materialTypeRepository.findCodesByFactoryIdAndSegmentPrefix(FACTORY_ID, "0010010001"))
                    .thenReturn(Collections.emptyList());

            String code = service.generateCode(FACTORY_ID, "001", "001001", "0010010001");
            assertEquals("0010010001000001", code);
        }

        @Test
        @DisplayName("字典已配置 + 已有 000005 → 返回 000006")
        void hasDictionary_existingCode000005_returns000006() {
            when(repo.countByFactoryIdAndLevel(FACTORY_ID, (short) 1)).thenReturn(3L);
            when(materialTypeRepository.findCodesByFactoryIdAndSegmentPrefix(FACTORY_ID, "0010010001"))
                    .thenReturn(List.of("0010010001000001", "0010010001000003", "0010010001000005"));

            String code = service.generateCode(FACTORY_ID, "001", "001001", "0010010001");
            assertEquals("0010010001000006", code);
        }

        @Test
        @DisplayName("字典未配置 → 返回 null")
        void noDictionary_returnsNull() {
            when(repo.countByFactoryIdAndLevel(FACTORY_ID, (short) 1)).thenReturn(0L);

            String code = service.generateCode(FACTORY_ID, "001", "001001", "0010010001");
            assertNull(code);
            verifyNoInteractions(materialTypeRepository);
        }

        @Test
        @DisplayName("l3 为 null → 返回 null (参数无效)")
        void nullL3_returnsNull() {
            when(repo.countByFactoryIdAndLevel(FACTORY_ID, (short) 1)).thenReturn(3L);

            String code = service.generateCode(FACTORY_ID, "001", "001001", null);
            assertNull(code);
            verifyNoInteractions(materialTypeRepository);
        }

        @Test
        @DisplayName("l3 非10位 → 返回 null (参数无效)")
        void shortL3_returnsNull() {
            when(repo.countByFactoryIdAndLevel(FACTORY_ID, (short) 1)).thenReturn(3L);

            // 6-digit l3 — invalid
            String code = service.generateCode(FACTORY_ID, "001", "001001", "001001");
            assertNull(code);
        }

        @Test
        @DisplayName("l3 含非数字 → 返回 null (参数无效)")
        void nonNumericL3_returnsNull() {
            when(repo.countByFactoryIdAndLevel(FACTORY_ID, (short) 1)).thenReturn(3L);

            String code = service.generateCode(FACTORY_ID, "001", "001001", "001001AB01");
            assertNull(code);
        }

        @Test
        @DisplayName("不同前缀的16位码不影响当前前缀序号")
        void differentPrefix_doesNotAffectSequence() {
            when(repo.countByFactoryIdAndLevel(FACTORY_ID, (short) 1)).thenReturn(3L);
            // Only codes with 0020020001 prefix present, querying 0010010001
            when(materialTypeRepository.findCodesByFactoryIdAndSegmentPrefix(FACTORY_ID, "0010010001"))
                    .thenReturn(Collections.emptyList());

            String code = service.generateCode(FACTORY_ID, "001", "001001", "0010010001");
            assertEquals("0010010001000001", code, "不同前缀不影响本前缀序号");
        }
    }
}
