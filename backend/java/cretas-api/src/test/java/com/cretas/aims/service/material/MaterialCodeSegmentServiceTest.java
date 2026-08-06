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

        /**
         * 🔴 2026-08-06: 删除此前**一个守卫都没有**。客户 08-04 一次性删掉 226 个 L3 + 2 个 L2,
         * 而软删的行继续占着编码 → 重建时撞码。更隐蔽的是: 物料的分类归属建完之后界面上
         * 根本不再显示(级联只在新建时用), 所以删分类**当场没有任何症状**, 没有反馈回路。
         */
        @Test
        @DisplayName("🔴 该分类下还有在用物料 → 拒绝删除, 并指向「停用」")
        void delete_withMaterialsInUse_isRejectedAndPointsToDeactivate() {
            MaterialCodeSegment entity = buildSegment(31L, FACTORY_ID, (short) 3, "0010010115", "239厂牛腩排", "001001");
            when(repo.findById(31L)).thenReturn(Optional.of(entity));
            when(repo.countByFactoryIdAndParentCode(FACTORY_ID, "0010010115")).thenReturn(0L);
            when(materialTypeRepository.countActiveByFactoryIdAndSegmentPrefix(FACTORY_ID, "0010010115"))
                    .thenReturn(3L);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.delete(FACTORY_ID, 31L));

            assertEquals(409, ex.getCode());
            assertTrue(ex.getMessage().contains("3"), "要说清有几个物料在用, 实际: " + ex.getMessage());
            assertTrue(ex.getActionHint() != null && ex.getActionHint().contains("停用"),
                    "必须给出可行的替代动作, 实际 hint: " + ex.getActionHint());
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("该分类下还有未删除的下级 → 拒绝删除")
        void delete_withLiveChildren_isRejected() {
            MaterialCodeSegment entity = buildSegment(32L, FACTORY_ID, (short) 2, "001001", "牛肉部位", "001");
            when(repo.findById(32L)).thenReturn(Optional.of(entity));
            when(repo.countByFactoryIdAndParentCode(FACTORY_ID, "001001")).thenReturn(30L);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.delete(FACTORY_ID, 32L));

            assertEquals(409, ex.getCode());
            assertTrue(ex.getMessage().contains("30"), "实际: " + ex.getMessage());
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("无引用无下级 → 正常软删")
        void delete_withNoReferences_succeeds() {
            MaterialCodeSegment entity = buildSegment(33L, FACTORY_ID, (short) 3, "0010040009", "试验", "001004");
            when(repo.findById(33L)).thenReturn(Optional.of(entity));
            when(repo.countByFactoryIdAndParentCode(FACTORY_ID, "0010040009")).thenReturn(0L);
            when(materialTypeRepository.countActiveByFactoryIdAndSegmentPrefix(FACTORY_ID, "0010040009"))
                    .thenReturn(0L);
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.delete(FACTORY_ID, 33L);

            verify(repo, times(1)).save(argThat(s -> s.getDeletedAt() != null));
        }
    }

    @Nested
    @DisplayName("恢复已删除的分类")
    class Restore {

        /** 恢复比重建正确: 编码不变 → 历史物料的 16 位码仍然指得回它的分类。 */
        @Test
        @DisplayName("恢复只清 deletedAt, 编码/名称/归属原样回来")
        void restore_clearsDeletedAtOnly() {
            MaterialCodeSegment deleted = buildSegment(40L, FACTORY_ID, (short) 3, "0010010001", "218厂腹肉心谷饲100天", "001001");
            deleted.setDeletedAt(LocalDateTime.now());
            MaterialCodeSegment parent = buildSegment(41L, FACTORY_ID, (short) 2, "001001", "牛肉部位", "001");
            when(repo.findByIdIncludingDeleted(40L)).thenReturn(Optional.of(deleted));
            when(repo.findByFactoryIdAndSegmentCode(FACTORY_ID, "001001")).thenReturn(Optional.of(parent));
            when(repo.findByFactoryIdAndParentCodeOrderBySortOrderAscSegmentCodeAsc(FACTORY_ID, "001001"))
                    .thenReturn(Collections.emptyList());

            MaterialCodeSegmentDTO result = service.restore(FACTORY_ID, 40L);

            verify(repo, times(1)).restoreById(40L);
            assertEquals("0010010001", result.getSegmentCode(), "编码必须原样保留");
            assertEquals("218厂腹肉心谷饲100天", result.getSegmentLabel());
        }

        @Test
        @DisplayName("上级也被删了 → 拒绝, 并说出恢复顺序")
        void restore_withDeletedParent_isRejected() {
            MaterialCodeSegment deleted = buildSegment(42L, FACTORY_ID, (short) 3, "0010010001", "旧分类", "001001");
            deleted.setDeletedAt(LocalDateTime.now());
            when(repo.findByIdIncludingDeleted(42L)).thenReturn(Optional.of(deleted));
            when(repo.findByFactoryIdAndSegmentCode(FACTORY_ID, "001001")).thenReturn(Optional.empty());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.restore(FACTORY_ID, 42L));

            assertEquals(409, ex.getCode());
            assertTrue(ex.getMessage().contains("001001"), "实际: " + ex.getMessage());
            verify(repo, never()).restoreById(anyLong());
        }

        @Test
        @DisplayName("同名的活分类已经存在 → 拒绝恢复(不能制造同名兄弟)")
        void restore_whenNameTakenByLiveSibling_isRejected() {
            MaterialCodeSegment deleted = buildSegment(43L, FACTORY_ID, (short) 3, "0010010001", "菲力", "001001");
            deleted.setDeletedAt(LocalDateTime.now());
            MaterialCodeSegment parent = buildSegment(44L, FACTORY_ID, (short) 2, "001001", "牛肉部位", "001");
            when(repo.findByIdIncludingDeleted(43L)).thenReturn(Optional.of(deleted));
            when(repo.findByFactoryIdAndSegmentCode(FACTORY_ID, "001001")).thenReturn(Optional.of(parent));
            when(repo.existsByFactoryIdAndLevelAndParentCodeAndNormalizedLabelAndIdNot(
                    FACTORY_ID, (short) 3, "001001", "菲力", 43L)).thenReturn(true);

            assertThrows(BusinessException.class, () -> service.restore(FACTORY_ID, 43L));
            verify(repo, never()).restoreById(anyLong());
        }

        @Test
        @DisplayName("这条没被删过 → 400")
        void restore_notDeleted_throws400() {
            MaterialCodeSegment alive = buildSegment(45L, FACTORY_ID, (short) 1, "001", "原料", null);
            when(repo.findByIdIncludingDeleted(45L)).thenReturn(Optional.of(alive));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.restore(FACTORY_ID, 45L));
            assertEquals(400, ex.getCode());
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
            when(repo.existsBySegmentCodeIncludingDeleted(FACTORY_ID, "001001")).thenReturn(false);
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
            when(repo.existsBySegmentCodeIncludingDeleted(FACTORY_ID, "001")).thenReturn(true);
            when(repo.findLabelBySegmentCodeIncludingDeleted(FACTORY_ID, "001")).thenReturn(Optional.of("原料"));

            CreateMaterialCodeSegmentRequest req = CreateMaterialCodeSegmentRequest.builder()
                    .level((short) 1)
                    .segmentCode("001")
                    .segmentLabel("原料")
                    .build();

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.create(FACTORY_ID, req));
            assertEquals(409, ex.getCode());
        }

        /**
         * 🔴 2026-08-06 客户事故的直接回归。
         *
         * <p>六膳门把 L2 {@code 001001} 连同 30 个 L3 全软删了。前端按**活着的**子节点
         * 算 max+1 得到 {@code 0010010001} —— 而那个编码正被一条软删行占着。
         * 旧代码用 {@code existsByFactoryIdAndSegmentCode}(被实体的 @Where 挡住看不见软删行)
         * 做前置校验 → 放行 → INSERT 撞 {@code uk_mcs_factory_segment} → catch 里一律
         * 抛「同一父级下已存在同名分类」, 提示用户改名字。
         *
         * <p><b>改名字永远修不好编码冲突</b> —— 所以这里断言两件事:
         * 报的是**编码**冲突(不是重名), 且 hintTarget 指向 segmentCode。
         */
        @Test
        @DisplayName("🔴 编码被已软删的分类占着 -> 报编码冲突, 不能报成重名")
        void create_codeTakenBySoftDeletedRow_reportsCodeConflictNotDuplicateLabel() {
            MaterialCodeSegment parent = buildSegment(1L, FACTORY_ID, (short) 2, "001001", "牛肉部位", "001");
            when(repo.findByFactoryIdAndSegmentCode(FACTORY_ID, "001001")).thenReturn(Optional.of(parent));
            // 活着的兄弟一个都没有 —— 全被软删了, 所以重名检查通过
            when(repo.findByFactoryIdAndParentCodeOrderBySortOrderAscSegmentCodeAsc(FACTORY_ID, "001001"))
                    .thenReturn(Collections.emptyList());
            // 但编码被一条软删行占着
            when(repo.existsBySegmentCodeIncludingDeleted(FACTORY_ID, "0010010001")).thenReturn(true);
            when(repo.findLabelBySegmentCodeIncludingDeleted(FACTORY_ID, "0010010001"))
                    .thenReturn(Optional.of("218厂腹肉心谷饲100天"));

            CreateMaterialCodeSegmentRequest req = CreateMaterialCodeSegmentRequest.builder()
                    .level((short) 3)
                    .segmentCode("0010010001")
                    .segmentLabel("牛柳/菲力")
                    .parentCode("001001")
                    .build();

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.create(FACTORY_ID, req));

            assertEquals(409, ex.getCode());
            assertTrue(ex.getMessage().contains("0010010001"),
                    "必须点名是哪个编码被占了, 实际: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("218厂腹肉心谷饲100天"),
                    "必须点名被谁占着 —— 用户在界面上看不到那条已删除的分类, 实际: " + ex.getMessage());
            assertFalse(ex.getMessage().contains("同名分类"),
                    "⛔ 不能报成重名: 改名字修不好编码冲突, 实际: " + ex.getMessage());
            assertEquals("segmentCode", ex.getHintTarget());
        }

        /**
         * 分配口径必须与唯一约束口径一致 —— 只看活着的行就会分到被软删行占用的编码。
         * 这条正是把「前端 max+1」搬到服务端的理由。
         */
        @Test
        @DisplayName("🔴 分配下一个编码要跳过被软删行占用的号")
        void nextSegmentCode_skipsCodesHeldBySoftDeletedRows() {
            // 001001 下活着的子节点是 0 个, 但 0001..0003 被软删行占着
            when(repo.findSegmentCodesByParentIncludingDeleted(FACTORY_ID, "001001"))
                    .thenReturn(List.of("0010010001", "0010010002", "0010010003"));
            when(repo.existsBySegmentCodeIncludingDeleted(FACTORY_ID, "0010010004")).thenReturn(false);

            assertEquals("0010010004", service.nextSegmentCode(FACTORY_ID, (short) 3, "001001"),
                    "被软删行占着的 0001-0003 必须跳过 —— 旧的前端算法会返回 0001");
        }

        @Test
        @DisplayName("父级下一个子节点都没有时从 0001 起")
        void nextSegmentCode_emptyParent_startsAtOne() {
            when(repo.findSegmentCodesByParentIncludingDeleted(FACTORY_ID, "001009"))
                    .thenReturn(Collections.emptyList());
            when(repo.existsBySegmentCodeIncludingDeleted(FACTORY_ID, "0010090001")).thenReturn(false);

            assertEquals("0010090001", service.nextSegmentCode(FACTORY_ID, (short) 3, "001009"));
        }

        @Test
        @DisplayName("L1 取编码不需要父级")
        void nextSegmentCode_level1_needsNoParent() {
            when(repo.findSegmentCodesByParentIncludingDeleted(FACTORY_ID, null))
                    .thenReturn(List.of("001", "002", "003"));
            when(repo.existsBySegmentCodeIncludingDeleted(FACTORY_ID, "004")).thenReturn(false);

            assertEquals("004", service.nextSegmentCode(FACTORY_ID, (short) 1, null));
        }

        @Test
        @DisplayName("同一父级规范化同名分类 -> 409")
        void create_duplicateNormalizedLabelWithinParent_throws409() {
            MaterialCodeSegment parent = buildSegment(1L, FACTORY_ID, (short) 2,
                    "001001", "水产原料", "001");
            when(repo.findByFactoryIdAndSegmentCode(FACTORY_ID, "001001"))
                    .thenReturn(Optional.of(parent));
            when(repo.existsByFactoryIdAndLevelAndParentCodeAndNormalizedLabelAndIdNot(
                    FACTORY_ID, (short) 3, "001001", "鱼类原料", -1L))
                    .thenReturn(true);
            CreateMaterialCodeSegmentRequest req = CreateMaterialCodeSegmentRequest.builder()
                    .level((short) 3)
                    .segmentCode("0010010002")
                    .segmentLabel(" 鱼 类 原 料 ")
                    .parentCode("001001")
                    .build();

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.create(FACTORY_ID, req));

            assertEquals(409, ex.getCode());
            assertEquals("segmentLabel", ex.getHintTarget());
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("历史冲突 normalizedLabel 为空时仍按源名称规范化拒绝新重复")
        void create_conflictsWithQuarantinedLegacyLabel_throws409() {
            MaterialCodeSegment parent = buildSegment(1L, FACTORY_ID, (short) 2,
                    "001001", "水产原料", "001");
            MaterialCodeSegment legacy = buildSegment(2L, FACTORY_ID, (short) 3,
                    "0010010001", "Ｆｉｓｈ　原料", "001001");
            legacy.setNormalizedLabel(null);

            when(repo.findByFactoryIdAndSegmentCode(FACTORY_ID, "001001"))
                    .thenReturn(Optional.of(parent));
            when(repo.existsByFactoryIdAndLevelAndParentCodeAndNormalizedLabelAndIdNot(
                    FACTORY_ID, (short) 3, "001001", "fish原料", -1L))
                    .thenReturn(false);
            when(repo.findByFactoryIdAndParentCodeOrderBySortOrderAscSegmentCodeAsc(
                    FACTORY_ID, "001001"))
                    .thenReturn(List.of(legacy));

            CreateMaterialCodeSegmentRequest req = CreateMaterialCodeSegmentRequest.builder()
                    .level((short) 3)
                    .segmentCode("0010010002")
                    .segmentLabel(" Fish 原料 ")
                    .parentCode("001001")
                    .build();

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.create(FACTORY_ID, req));

            assertEquals(409, ex.getCode());
            assertEquals("segmentLabel", ex.getHintTarget());
            verify(repo, never()).save(any());
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
