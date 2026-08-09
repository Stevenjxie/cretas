package com.cretas.aims.service.material;

import com.cretas.aims.dto.material.CreateMaterialCodeSegmentRequest;
import com.cretas.aims.dto.material.MaterialCodeSegmentDTO;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.service.material.impl.MaterialCodeSegmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaterialCodeSegmentServiceTest {

    private static final String FACTORY = "F006";

    @Mock MaterialCodeSegmentRepository repository;
    @Mock RawMaterialTypeRepository materialRepository;
    MaterialCodeSegmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MaterialCodeSegmentServiceImpl(repository, materialRepository);
    }

    @Test
    void treeUsesGeneratedIdsAndParentIds() {
        MaterialCodeSegment l1 = node(1L, (short) 1, null, "原料");
        MaterialCodeSegment l2 = node(2L, (short) 2, 1L, "肉类");
        MaterialCodeSegment l3 = node(3L, (short) 3, 2L, "牛肉");
        when(repository.findByFactoryIdOrderBySortOrderAscIdAsc(FACTORY))
                .thenReturn(List.of(l1, l2, l3));

        List<MaterialCodeSegmentDTO> tree = service.getTree(FACTORY);

        assertEquals(1L, tree.get(0).getId());
        assertEquals(2L, tree.get(0).getChildren().get(0).getId());
        assertEquals(3L, tree.get(0).getChildren().get(0).getChildren().get(0).getId());
        assertEquals(2L, tree.get(0).getChildren().get(0).getChildren().get(0).getParentId());
    }

    @Test
    void createDoesNotAcceptOrAllocateAClassificationCode() {
        MaterialCodeSegment parent = node(2L, (short) 2, 1L, "肉类");
        when(repository.findByIdAndFactoryId(2L, FACTORY)).thenReturn(Optional.of(parent));
        when(repository.existsSiblingWithNormalizedLabel(eq(FACTORY), eq((short) 3), eq(2L), eq("牛腱"), anyLong()))
                .thenReturn(false);
        when(repository.findSiblings(FACTORY, 2L)).thenReturn(List.of());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            MaterialCodeSegment saved = invocation.getArgument(0);
            saved.setId(9L);
            return saved;
        });

        MaterialCodeSegmentDTO created = service.create(FACTORY, CreateMaterialCodeSegmentRequest.builder()
                .level((short) 3)
                .parentId(2L)
                .segmentLabel("牛腱")
                .build());

        assertEquals(9L, created.getId());
        assertEquals(2L, created.getParentId());
        assertEquals("牛腱", created.getSegmentLabel());
    }

    @Test
    void createRejectsMissingOrWrongLevelParent() {
        BusinessException missing = assertThrows(BusinessException.class, () -> service.create(
                FACTORY, CreateMaterialCodeSegmentRequest.builder()
                        .level((short) 3).segmentLabel("牛腱").build()));
        assertTrue(missing.getMessage().contains("上级"));

        when(repository.findByIdAndFactoryId(1L, FACTORY))
                .thenReturn(Optional.of(node(1L, (short) 1, null, "原料")));
        BusinessException wrong = assertThrows(BusinessException.class, () -> service.create(
                FACTORY, CreateMaterialCodeSegmentRequest.builder()
                        .level((short) 3).parentId(1L).segmentLabel("牛腱").build()));
        assertTrue(wrong.getMessage().contains("直属上级"));
    }

    @Test
    void updateRejectsChangingParentButAllowsPartialStatusUpdate() {
        MaterialCodeSegment existing = node(3L, (short) 3, 2L, "牛肉");
        when(repository.findByIdAndFactoryId(3L, FACTORY)).thenReturn(Optional.of(existing));

        BusinessException moved = assertThrows(BusinessException.class, () -> service.update(
                FACTORY, 3L, CreateMaterialCodeSegmentRequest.builder().parentId(8L).build()));
        assertTrue(moved.getMessage().contains("上级分类创建后不可修改"));

        when(repository.existsSiblingWithNormalizedLabel(FACTORY, (short) 3, 2L, "牛肉", 3L))
                .thenReturn(false);
        when(repository.findSiblings(FACTORY, 2L)).thenReturn(List.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);
        MaterialCodeSegmentDTO updated = service.update(
                FACTORY, 3L, CreateMaterialCodeSegmentRequest.builder().isActive(false).build());
        assertFalse(updated.getIsActive());
    }

    @Test
    void duplicateNameIsParentScopedAndExplicit() {
        when(repository.existsSiblingWithNormalizedLabel(eq(FACTORY), eq((short) 1), isNull(), eq("原料"), anyLong()))
                .thenReturn(true);

        BusinessException conflict = assertThrows(BusinessException.class, () -> service.create(
                FACTORY, CreateMaterialCodeSegmentRequest.builder()
                        .level((short) 1).segmentLabel("原料").build()));
        assertEquals(409, conflict.getCode());
        assertTrue(conflict.getMessage().contains("已存在"));
    }

    @Test
    void deleteIsBlockedByChildrenOrMaterialReferences() {
        MaterialCodeSegment node = node(3L, (short) 3, 2L, "牛肉");
        when(repository.findByIdAndFactoryId(3L, FACTORY)).thenReturn(Optional.of(node));
        when(repository.countByFactoryIdAndParentId(FACTORY, 3L)).thenReturn(1L);
        assertThrows(BusinessException.class, () -> service.delete(FACTORY, 3L));

        when(repository.countByFactoryIdAndParentId(FACTORY, 3L)).thenReturn(0L);
        when(materialRepository.countActiveByFactoryIdAndClassificationSegmentId(FACTORY, 3L))
                .thenReturn(2L);
        BusinessException referenced = assertThrows(BusinessException.class, () -> service.delete(FACTORY, 3L));
        assertTrue(referenced.getMessage().contains("2 个在用物料"));
    }

    @Test
    void restoreRequiresAnActiveParent() {
        MaterialCodeSegment deleted = node(3L, (short) 3, 2L, "牛肉");
        deleted.setDeletedAt(LocalDateTime.now());
        when(repository.findByIdIncludingDeleted(3L)).thenReturn(Optional.of(deleted));
        when(repository.findByIdAndFactoryId(2L, FACTORY)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class, () -> service.restore(FACTORY, 3L));
        assertTrue(error.getMessage().contains("先恢复上级"));
    }

    private MaterialCodeSegment node(Long id, short level, Long parentId, String label) {
        MaterialCodeSegment node = MaterialCodeSegment.builder()
                .factoryId(FACTORY)
                .level(level)
                .parentId(parentId)
                .segmentLabel(label)
                .normalizedLabel(label)
                .sortOrder(0)
                .isActive(true)
                .build();
        node.setId(id);
        return node;
    }
}
