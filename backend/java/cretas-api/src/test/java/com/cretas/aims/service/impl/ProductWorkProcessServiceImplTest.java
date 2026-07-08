package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ProductWorkProcessDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.entity.ProductWorkProcessAssignee;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ProductWorkProcessServiceImpl 单元测试
 *
 * 测试覆盖:
 * - UT-PWP-01: 创建产品-工序关联测试
 * - UT-PWP-02: 按产品查询工序列表（含工序数据富化）
 * - UT-PWP-03: 批量排序测试
 * - UT-PWP-04: 删除关联测试
 *
 * @author Cretas Team
 * @since 2026-03-12
 */
@DisplayName("ProductWorkProcessServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class ProductWorkProcessServiceImplTest {

    @Mock
    private ProductWorkProcessRepository repository;

    @Mock
    private WorkProcessRepository workProcessRepository;

    @Mock
    private ProductWorkProcessAssigneeRepository assigneeRepository;

    @InjectMocks
    private ProductWorkProcessServiceImpl service;

    @Captor
    private ArgumentCaptor<ProductWorkProcess> entityCaptor;

    @Captor
    private ArgumentCaptor<ProductWorkProcessAssignee> assigneeCaptor;

    private static final String FACTORY_ID = "F001";

    @BeforeEach
    void setUp() {
        // T121: default stub — no existing assignees (向后兼容旧测试).
        // lenient() 防止 UnnecessaryStubbingException: 某些旧测试不触发 join 表路径。
        lenient().when(assigneeRepository.findByProductWorkProcessId(any())).thenReturn(List.of());
        lenient().when(assigneeRepository.save(any(ProductWorkProcessAssignee.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }
    private static final String PRODUCT_TYPE_ID = "pt-1";
    private static final String WORK_PROCESS_ID = "wp-1";

    private ProductWorkProcess buildDefaultAssociation() {
        return ProductWorkProcess.builder()
                .id(1L)
                .factoryId(FACTORY_ID)
                .productTypeId(PRODUCT_TYPE_ID)
                .workProcessId(WORK_PROCESS_ID)
                .processOrder(1)
                .build();
    }

    private WorkProcess buildDefaultWorkProcess() {
        return WorkProcess.builder()
                .id(WORK_PROCESS_ID)
                .factoryId(FACTORY_ID)
                .processName("炸制")
                .processCategory("加工")
                .unit("kg")
                .estimatedMinutes(30)
                .sortOrder(1)
                .isActive(true)
                .build();
    }

    // ==================== 创建关联测试 ====================

    @Nested
    @DisplayName("创建产品-工序关联测试")
    class CreateTests {

        @Test
        @DisplayName("UT-PWP-01a: create() 成功 — 工序存在且无重复")
        void testCreateSuccessWithValidWorkProcess() {
            // Arrange
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId(WORK_PROCESS_ID)
                    .processOrder(1)
                    .build();

            when(repository.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                    FACTORY_ID, PRODUCT_TYPE_ID, WORK_PROCESS_ID))
                    .thenReturn(false);
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WORK_PROCESS_ID))
                    .thenReturn(Optional.of(buildDefaultWorkProcess()));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> {
                ProductWorkProcess arg = inv.getArgument(0);
                arg.setId(1L); // simulate DB-generated ID
                return arg;
            });

            // Act
            ProductWorkProcessDTO result = service.create(FACTORY_ID, dto);

            // Assert
            verify(repository).existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                    FACTORY_ID, PRODUCT_TYPE_ID, WORK_PROCESS_ID);
            verify(workProcessRepository).findByFactoryIdAndId(FACTORY_ID, WORK_PROCESS_ID);
            verify(repository).save(entityCaptor.capture());

            ProductWorkProcess saved = entityCaptor.getValue();
            assertEquals(FACTORY_ID, saved.getFactoryId());
            assertEquals(PRODUCT_TYPE_ID, saved.getProductTypeId());
            assertEquals(WORK_PROCESS_ID, saved.getWorkProcessId());
            assertEquals(1, saved.getProcessOrder());
            assertNotNull(result.getId());
        }

        @Test
        @DisplayName("UT-PWP-01b: create() 重复关联抛出 BusinessException")
        void testCreateDuplicateThrows() {
            // Arrange
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId(WORK_PROCESS_ID)
                    .build();

            when(repository.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                    FACTORY_ID, PRODUCT_TYPE_ID, WORK_PROCESS_ID))
                    .thenReturn(true);

            // Act & Assert
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.create(FACTORY_ID, dto));
            assertEquals("该产品已关联此工序", ex.getMessage());
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("UT-PWP-01c: create() 工序不存在抛出 ResourceNotFoundException")
        void testCreateWorkProcessNotFoundThrows() {
            // Arrange
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId("nonexistent-wp")
                    .build();

            when(repository.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                    FACTORY_ID, PRODUCT_TYPE_ID, "nonexistent-wp"))
                    .thenReturn(false);
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, "nonexistent-wp"))
                    .thenReturn(Optional.empty());

            // Act & Assert
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.create(FACTORY_ID, dto));
            assertTrue(ex.getMessage().contains("WorkProcess"));
        }

        @Test
        @DisplayName("UT-PWP-01d: create() processOrder 为 null 时默认为 0")
        void testCreateNullProcessOrderDefaultsToZero() {
            // Arrange
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId(WORK_PROCESS_ID)
                    .processOrder(null)
                    .build();

            when(repository.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                    FACTORY_ID, PRODUCT_TYPE_ID, WORK_PROCESS_ID))
                    .thenReturn(false);
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WORK_PROCESS_ID))
                    .thenReturn(Optional.of(buildDefaultWorkProcess()));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.create(FACTORY_ID, dto);

            // Assert
            verify(repository).save(entityCaptor.capture());
            assertEquals(0, entityCaptor.getValue().getProcessOrder(),
                    "processOrder 为 null 时应默认为 0");
        }

        // ===== Wave2: reportingRequired DTO 往返 =====

        @Test
        @DisplayName("UT-PWP-01e: create() reportingRequired 省略 (null) → 默认 true (逐道报, 向后兼容)")
        void testCreateReportingRequiredDefaultsTrue() {
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId(WORK_PROCESS_ID)
                    .processOrder(1)
                    .build();   // reportingRequired omitted

            when(repository.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                    FACTORY_ID, PRODUCT_TYPE_ID, WORK_PROCESS_ID)).thenReturn(false);
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WORK_PROCESS_ID))
                    .thenReturn(Optional.of(buildDefaultWorkProcess()));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductWorkProcessDTO result = service.create(FACTORY_ID, dto);

            verify(repository).save(entityCaptor.capture());
            assertEquals(Boolean.TRUE, entityCaptor.getValue().getReportingRequired(),
                    "省略 reportingRequired → 持久化 true");
            assertEquals(Boolean.TRUE, result.getReportingRequired(),
                    "toDTO 应回填 reportingRequired=true");
        }

        @Test
        @DisplayName("UT-PWP-01f: create() reportingRequired=false → 免报工序持久化 false + toDTO 回填")
        void testCreateReportingRequiredFalse() {
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId(WORK_PROCESS_ID)
                    .processOrder(2)
                    .reportingRequired(false)
                    .build();

            when(repository.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                    FACTORY_ID, PRODUCT_TYPE_ID, WORK_PROCESS_ID)).thenReturn(false);
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WORK_PROCESS_ID))
                    .thenReturn(Optional.of(buildDefaultWorkProcess()));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductWorkProcessDTO result = service.create(FACTORY_ID, dto);

            verify(repository).save(entityCaptor.capture());
            assertEquals(Boolean.FALSE, entityCaptor.getValue().getReportingRequired(),
                    "显式 false → 持久化 false (免报)");
            assertEquals(Boolean.FALSE, result.getReportingRequired());
        }

        // ===== 张权 R4: allowSemiFinishedInjection DTO 往返 =====

        @Test
        @DisplayName("UT-PWP-01g: create() allowSemiFinishedInjection 省略 (null) → 默认 false (普通工序)")
        void testCreateAllowInjectionDefaultsFalse() {
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId(WORK_PROCESS_ID)
                    .processOrder(1)
                    .build();   // allowSemiFinishedInjection omitted

            when(repository.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                    FACTORY_ID, PRODUCT_TYPE_ID, WORK_PROCESS_ID)).thenReturn(false);
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WORK_PROCESS_ID))
                    .thenReturn(Optional.of(buildDefaultWorkProcess()));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductWorkProcessDTO result = service.create(FACTORY_ID, dto);

            verify(repository).save(entityCaptor.capture());
            assertEquals(Boolean.FALSE, entityCaptor.getValue().getAllowSemiFinishedInjection(),
                    "省略 allowSemiFinishedInjection → 持久化 false (普通工序)");
            assertEquals(Boolean.FALSE, result.getAllowSemiFinishedInjection(),
                    "toDTO 应回填 allowSemiFinishedInjection=false");
        }

        @Test
        @DisplayName("UT-PWP-01h: create() allowSemiFinishedInjection=true → 半成品注入工序持久化 true + toDTO 回填")
        void testCreateAllowInjectionTrue() {
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId(WORK_PROCESS_ID)
                    .processOrder(2)
                    .allowSemiFinishedInjection(true)
                    .build();

            when(repository.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                    FACTORY_ID, PRODUCT_TYPE_ID, WORK_PROCESS_ID)).thenReturn(false);
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WORK_PROCESS_ID))
                    .thenReturn(Optional.of(buildDefaultWorkProcess()));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductWorkProcessDTO result = service.create(FACTORY_ID, dto);

            verify(repository).save(entityCaptor.capture());
            assertEquals(Boolean.TRUE, entityCaptor.getValue().getAllowSemiFinishedInjection(),
                    "显式 true → 持久化 true (半成品注入工序)");
            assertEquals(Boolean.TRUE, result.getAllowSemiFinishedInjection());
        }

        @Test
        @DisplayName("UT-PWP-01i: create() allowMultipleUpstreamSources=true → 混批工序持久化 true + toDTO 回填")
        void testCreateAllowMultipleUpstreamSourcesTrue() {
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId(WORK_PROCESS_ID)
                    .processOrder(3)
                    .allowMultipleUpstreamSources(true)
                    .build();

            when(repository.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                    FACTORY_ID, PRODUCT_TYPE_ID, WORK_PROCESS_ID)).thenReturn(false);
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WORK_PROCESS_ID))
                    .thenReturn(Optional.of(buildDefaultWorkProcess()));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductWorkProcessDTO result = service.create(FACTORY_ID, dto);

            verify(repository).save(entityCaptor.capture());
            assertEquals(Boolean.TRUE, entityCaptor.getValue().getAllowMultipleUpstreamSources(),
                    "显式 true → 持久化 true (允许多上游混批)");
            assertEquals(Boolean.TRUE, result.getAllowMultipleUpstreamSources());
        }
    }

    @Nested
    @DisplayName("Wave2 reportingRequired update no-change/set")
    class ReportingRequiredUpdateTests {

        @Test
        @DisplayName("UT-PWP-03e: update() reportingRequired=null → no-change (保留现有 true)")
        void testUpdateReportingRequiredNullNoChange() {
            ProductWorkProcess existing = buildDefaultAssociation();
            existing.setReportingRequired(true);
            when(repository.findByFactoryIdAndId(FACTORY_ID, 1L)).thenReturn(Optional.of(existing));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .processOrder(5)
                    .build();   // reportingRequired omitted

            service.update(FACTORY_ID, 1L, dto);

            verify(repository).save(entityCaptor.capture());
            assertEquals(Boolean.TRUE, entityCaptor.getValue().getReportingRequired(),
                    "reportingRequired 省略 → 不动现有值 (partial update no-change)");
        }

        @Test
        @DisplayName("UT-PWP-03f: update() reportingRequired=false → 把现有 true 改为 false (六扇门标免报)")
        void testUpdateReportingRequiredToFalse() {
            ProductWorkProcess existing = buildDefaultAssociation();
            existing.setReportingRequired(true);
            when(repository.findByFactoryIdAndId(FACTORY_ID, 1L)).thenReturn(Optional.of(existing));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .reportingRequired(false)
                    .build();

            ProductWorkProcessDTO result = service.update(FACTORY_ID, 1L, dto);

            verify(repository).save(entityCaptor.capture());
            assertEquals(Boolean.FALSE, entityCaptor.getValue().getReportingRequired(),
                    "显式 false → 设为免报");
            assertEquals(Boolean.FALSE, result.getReportingRequired());
        }

        @Test
        @DisplayName("UT-PWP-03g: update() allowSemiFinishedInjection=null → no-change (保留现有 true)")
        void testUpdateAllowInjectionNullNoChange() {
            ProductWorkProcess existing = buildDefaultAssociation();
            existing.setAllowSemiFinishedInjection(true);
            when(repository.findByFactoryIdAndId(FACTORY_ID, 1L)).thenReturn(Optional.of(existing));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .processOrder(5)
                    .build();   // allowSemiFinishedInjection omitted

            service.update(FACTORY_ID, 1L, dto);

            verify(repository).save(entityCaptor.capture());
            assertEquals(Boolean.TRUE, entityCaptor.getValue().getAllowSemiFinishedInjection(),
                    "allowSemiFinishedInjection 省略 → 不动现有值 (partial update no-change)");
        }

        @Test
        @DisplayName("UT-PWP-03h: update() allowSemiFinishedInjection=true → 把现有 false 标为半成品注入工序")
        void testUpdateAllowInjectionToTrue() {
            ProductWorkProcess existing = buildDefaultAssociation();
            existing.setAllowSemiFinishedInjection(false);
            when(repository.findByFactoryIdAndId(FACTORY_ID, 1L)).thenReturn(Optional.of(existing));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .allowSemiFinishedInjection(true)
                    .build();

            ProductWorkProcessDTO result = service.update(FACTORY_ID, 1L, dto);

            verify(repository).save(entityCaptor.capture());
            assertEquals(Boolean.TRUE, entityCaptor.getValue().getAllowSemiFinishedInjection(),
                    "显式 true → 设为半成品注入工序");
            assertEquals(Boolean.TRUE, result.getAllowSemiFinishedInjection());
        }
    }

    // ==================== 按产品查询工序列表测试 ====================

    @Nested
    @DisplayName("按产品查询工序列表测试")
    class ListByProductTests {

        @Test
        @DisplayName("UT-PWP-02: listByProduct() 按 processOrder 排序并富化 WorkProcess 数据")
        void testListByProductEnrichedWithWorkProcessData() {
            // Arrange
            ProductWorkProcess assoc1 = ProductWorkProcess.builder()
                    .id(1L)
                    .factoryId(FACTORY_ID)
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId("wp-1")
                    .processOrder(1)
                    .build();

            ProductWorkProcess assoc2 = ProductWorkProcess.builder()
                    .id(2L)
                    .factoryId(FACTORY_ID)
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId("wp-2")
                    .processOrder(2)
                    .unitOverride("件")
                    .estimatedMinutesOverride(45)
                    .build();

            WorkProcess wp1 = WorkProcess.builder()
                    .id("wp-1")
                    .factoryId(FACTORY_ID)
                    .processName("炸制")
                    .processCategory("加工")
                    .unit("kg")
                    .estimatedMinutes(30)
                    .build();

            WorkProcess wp2 = WorkProcess.builder()
                    .id("wp-2")
                    .factoryId(FACTORY_ID)
                    .processName("包装")
                    .processCategory("后处理")
                    .unit("件")
                    .estimatedMinutes(15)
                    .build();

            when(repository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, PRODUCT_TYPE_ID))
                    .thenReturn(List.of(assoc1, assoc2));
            when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), anyList()))
                    .thenReturn(List.of(wp1, wp2));

            // Act
            List<ProductWorkProcessDTO> result = service.listByProduct(FACTORY_ID, PRODUCT_TYPE_ID);

            // Assert
            verify(repository).findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, PRODUCT_TYPE_ID);
            verify(workProcessRepository).findByFactoryIdAndIdIn(eq(FACTORY_ID), anyList());

            assertEquals(2, result.size());

            // First item: enriched with wp1 data
            ProductWorkProcessDTO first = result.get(0);
            assertEquals(1L, first.getId());
            assertEquals("wp-1", first.getWorkProcessId());
            assertEquals(1, first.getProcessOrder());
            assertEquals("炸制", first.getProcessName(), "应从 WorkProcess 富化 processName");
            assertEquals("加工", first.getProcessCategory(), "应从 WorkProcess 富化 processCategory");
            assertEquals("kg", first.getDefaultUnit(), "应从 WorkProcess 富化 defaultUnit");
            assertEquals(30, first.getDefaultEstimatedMinutes(), "应从 WorkProcess 富化 defaultEstimatedMinutes");

            // Second item: has overrides + enriched with wp2
            ProductWorkProcessDTO second = result.get(1);
            assertEquals(2L, second.getId());
            assertEquals("wp-2", second.getWorkProcessId());
            assertEquals(2, second.getProcessOrder());
            assertEquals("包装", second.getProcessName());
            assertEquals("件", second.getUnitOverride(), "unitOverride 应保留");
            assertEquals(45, second.getEstimatedMinutesOverride(), "estimatedMinutesOverride 应保留");
        }

        @Test
        @DisplayName("UT-PWP-02b: listByProduct() 空关联返回空列表，不查询 WorkProcess")
        void testListByProductEmptyReturnsEmpty() {
            // Arrange
            when(repository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, PRODUCT_TYPE_ID))
                    .thenReturn(Collections.emptyList());
            when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), anyList()))
                    .thenReturn(Collections.emptyList());

            // Act
            List<ProductWorkProcessDTO> result = service.listByProduct(FACTORY_ID, PRODUCT_TYPE_ID);

            // Assert
            assertTrue(result.isEmpty());
        }
    }

    // ==================== 批量排序测试 ====================

    @Nested
    @DisplayName("批量排序测试")
    class BatchSortTests {

        @Test
        @DisplayName("UT-PWP-03: batchSort() 更新多个关联的 processOrder")
        void testBatchSortUpdatesProcessOrder() {
            // Arrange
            ProductWorkProcess assoc1 = buildDefaultAssociation();
            assoc1.setId(1L);
            assoc1.setProcessOrder(1);

            ProductWorkProcess assoc2 = ProductWorkProcess.builder()
                    .id(2L)
                    .factoryId(FACTORY_ID)
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId("wp-2")
                    .processOrder(2)
                    .build();

            when(repository.findByFactoryIdAndId(FACTORY_ID, 1L))
                    .thenReturn(Optional.of(assoc1));
            when(repository.findByFactoryIdAndId(FACTORY_ID, 2L))
                    .thenReturn(Optional.of(assoc2));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            List<ProductWorkProcessDTO.SortItem> items = List.of(
                    ProductWorkProcessDTO.SortItem.builder().id(1L).processOrder(3).build(),
                    ProductWorkProcessDTO.SortItem.builder().id(2L).processOrder(1).build()
            );

            // Act
            service.batchSort(FACTORY_ID, items);

            // Assert
            verify(repository, times(2)).save(any(ProductWorkProcess.class));
            assertEquals(3, assoc1.getProcessOrder(), "assoc1 processOrder 应更新为 3");
            assertEquals(1, assoc2.getProcessOrder(), "assoc2 processOrder 应更新为 1");
        }

        @Test
        @DisplayName("UT-PWP-03b: batchSort() 不存在的 ID 静默跳过")
        void testBatchSortSkipsMissingIds() {
            // Arrange
            when(repository.findByFactoryIdAndId(FACTORY_ID, 999L))
                    .thenReturn(Optional.empty());

            List<ProductWorkProcessDTO.SortItem> items = List.of(
                    ProductWorkProcessDTO.SortItem.builder().id(999L).processOrder(1).build()
            );

            // Act — should not throw
            service.batchSort(FACTORY_ID, items);

            // Assert
            verify(repository).findByFactoryIdAndId(FACTORY_ID, 999L);
            verify(repository, never()).save(any());
        }
    }

    // ==================== 删除关联测试 ====================

    @Nested
    @DisplayName("删除关联测试")
    class DeleteTests {

        @Test
        @DisplayName("UT-PWP-04a: delete() 成功删除关联")
        void testDeleteSuccess() {
            // Arrange
            ProductWorkProcess existing = buildDefaultAssociation();
            when(repository.findByFactoryIdAndId(FACTORY_ID, 1L))
                    .thenReturn(Optional.of(existing));

            // Act
            service.delete(FACTORY_ID, 1L);

            // Assert
            verify(repository).findByFactoryIdAndId(FACTORY_ID, 1L);
            verify(repository).delete(existing);
        }

        @Test
        @DisplayName("UT-PWP-04b: delete() 不存在时抛出 ResourceNotFoundException")
        void testDeleteNotFoundThrows() {
            // Arrange
            when(repository.findByFactoryIdAndId(FACTORY_ID, 999L))
                    .thenReturn(Optional.empty());

            // Act & Assert
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.delete(FACTORY_ID, 999L));
            assertTrue(ex.getMessage().contains("ProductWorkProcess"));
            verify(repository, never()).delete(any());
        }
    }

    // ==================== 更新关联测试 ====================

    @Nested
    @DisplayName("更新关联测试")
    class UpdateTests {

        @Test
        @DisplayName("update() 部分更新保留未修改字段")
        void testPartialUpdatePreservesUnchangedFields() {
            // Arrange
            ProductWorkProcess existing = buildDefaultAssociation();
            existing.setUnitOverride("件");
            existing.setEstimatedMinutesOverride(45);
            when(repository.findByFactoryIdAndId(FACTORY_ID, 1L))
                    .thenReturn(Optional.of(existing));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            // Only update processOrder — responsibleWorkerId null means no change
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .processOrder(5)
                    .build();

            // Act
            ProductWorkProcessDTO result = service.update(FACTORY_ID, 1L, dto);

            // Assert
            verify(repository).save(entityCaptor.capture());
            ProductWorkProcess saved = entityCaptor.getValue();
            assertEquals(5, saved.getProcessOrder(), "processOrder 应被更新为 5");
            assertEquals("件", saved.getUnitOverride(), "unitOverride 未传入应保持原值");
            assertEquals(45, saved.getEstimatedMinutesOverride(), "estimatedMinutesOverride 未传入应保持原值");
            assertNull(saved.getResponsibleWorkerId(), "responsibleWorkerId 未传入应保持原值 null");
        }

        @Test
        @DisplayName("UT-PWP-05a: update() 设置 responsibleWorkerId — 实体和返回 DTO 均含新值")
        void testUpdateSetsResponsibleWorkerId() {
            // Arrange
            ProductWorkProcess existing = buildDefaultAssociation();
            // initially no responsible worker
            when(repository.findByFactoryIdAndId(FACTORY_ID, 1L))
                    .thenReturn(Optional.of(existing));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .responsibleWorkerId(1616L)
                    .build();

            // Act
            ProductWorkProcessDTO result = service.update(FACTORY_ID, 1L, dto);

            // Assert — entityCaptor confirms DB value; result confirms toDTO mapping
            verify(repository).save(entityCaptor.capture());
            assertEquals(1616L, entityCaptor.getValue().getResponsibleWorkerId(),
                    "entity 应持久化 responsibleWorkerId=1616");
            assertEquals(1616L, result.getResponsibleWorkerId(),
                    "返回 DTO 应映射 responsibleWorkerId=1616");
        }

        @Test
        @DisplayName("UT-PWP-05b: update() responsibleWorkerId=-1 应清空 (置 null)")
        void testUpdateMinusOneClearsResponsibleWorkerId() {
            // Arrange — entity already has a responsible worker assigned
            ProductWorkProcess existing = buildDefaultAssociation();
            existing.setResponsibleWorkerId(1616L);
            when(repository.findByFactoryIdAndId(FACTORY_ID, 1L))
                    .thenReturn(Optional.of(existing));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .responsibleWorkerId(-1L)
                    .build();

            // Act
            ProductWorkProcessDTO result = service.update(FACTORY_ID, 1L, dto);

            // Assert — sentinel -1 must never persist; entity and DTO must both be null
            verify(repository).save(entityCaptor.capture());
            assertNull(entityCaptor.getValue().getResponsibleWorkerId(),
                    "sentinel -1 应将 responsibleWorkerId 清空为 null");
            assertNull(result.getResponsibleWorkerId(),
                    "返回 DTO 的 responsibleWorkerId 应为 null");
        }

        @Test
        @DisplayName("UT-PWP-05c: create() 传入 responsibleWorkerId 应在实体中保留")
        void testCreatePersistsResponsibleWorkerId() {
            // Arrange
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId(WORK_PROCESS_ID)
                    .processOrder(1)
                    .responsibleWorkerId(1617L)
                    .build();

            when(repository.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                    FACTORY_ID, PRODUCT_TYPE_ID, WORK_PROCESS_ID))
                    .thenReturn(false);
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WORK_PROCESS_ID))
                    .thenReturn(Optional.of(buildDefaultWorkProcess()));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> {
                ProductWorkProcess arg = inv.getArgument(0);
                arg.setId(10L);
                return arg;
            });

            // Act
            ProductWorkProcessDTO result = service.create(FACTORY_ID, dto);

            // Assert
            verify(repository).save(entityCaptor.capture());
            assertEquals(1617L, entityCaptor.getValue().getResponsibleWorkerId(),
                    "create 应持久化 responsibleWorkerId=1617");
            assertEquals(1617L, result.getResponsibleWorkerId(),
                    "返回 DTO 应映射 responsibleWorkerId=1617");
        }

        @Test
        @DisplayName("UT-PWP-05d: create() 传入 responsibleWorkerId=-1 哨兵应被规范化为 null")
        void testCreateMinusOneSentinelNormalizedToNull() {
            // Arrange — defensive: sentinel -1 must never land in DB via create either
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId(WORK_PROCESS_ID)
                    .responsibleWorkerId(-1L)
                    .build();

            when(repository.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                    FACTORY_ID, PRODUCT_TYPE_ID, WORK_PROCESS_ID))
                    .thenReturn(false);
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WORK_PROCESS_ID))
                    .thenReturn(Optional.of(buildDefaultWorkProcess()));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.create(FACTORY_ID, dto);

            // Assert
            verify(repository).save(entityCaptor.capture());
            assertNull(entityCaptor.getValue().getResponsibleWorkerId(),
                    "create 时哨兵 -1 应被规范化为 null, 不得持久化到 DB");
        }
    }

    // ==================== T121 多人负责测试 ====================

    @Nested
    @DisplayName("T121 多人负责 — upsert join 表测试")
    class MultiAssigneeTests {

        private void stubCreateHappyPath() {
            when(repository.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                    FACTORY_ID, PRODUCT_TYPE_ID, WORK_PROCESS_ID)).thenReturn(false);
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WORK_PROCESS_ID))
                    .thenReturn(Optional.of(buildDefaultWorkProcess()));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> {
                ProductWorkProcess arg = inv.getArgument(0);
                arg.setId(20L);
                return arg;
            });
        }

        @Test
        @DisplayName("UT-T121-01: create() 传入 assigneeWorkerIds → join 表被 upsert, primary=assignees[0]")
        void create_withAssigneeList_upsertsJoinTable() {
            // Arrange
            stubCreateHappyPath();
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId(WORK_PROCESS_ID)
                    .assigneeWorkerIds(List.of(1001L, 1002L, 1003L))
                    .build();

            // Act
            ProductWorkProcessDTO result = service.create(FACTORY_ID, dto);

            // Assert — join table insertions: 3 workers
            verify(assigneeRepository, times(3)).save(assigneeCaptor.capture());
            List<ProductWorkProcessAssignee> saved = assigneeCaptor.getAllValues();
            List<Long> savedWorkerIds = saved.stream()
                    .map(ProductWorkProcessAssignee::getWorkerId)
                    .toList();
            assertTrue(savedWorkerIds.containsAll(List.of(1001L, 1002L, 1003L)),
                    "join 表应插入 3 个 worker");

            // primary (responsible_worker_id) = assignees[0]
            verify(repository).save(entityCaptor.capture());
            assertEquals(1001L, entityCaptor.getValue().getResponsibleWorkerId(),
                    "primary = assigneeWorkerIds[0] = 1001");
        }

        @Test
        @DisplayName("UT-T121-02: create() assigneeWorkerIds=null → 回退到 responsibleWorkerId 单值语义")
        void create_noAssigneeList_fallsBackToSingleValue() {
            // Arrange
            stubCreateHappyPath();
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId(WORK_PROCESS_ID)
                    .responsibleWorkerId(999L)
                    .build();

            // Act
            service.create(FACTORY_ID, dto);

            // Assert — join table gets singleton insert (backfill logic)
            verify(assigneeRepository, times(1)).save(assigneeCaptor.capture());
            assertEquals(999L, assigneeCaptor.getValue().getWorkerId(),
                    "单值语义时 join 表插入 responsibleWorkerId");
        }

        @Test
        @DisplayName("UT-T121-03: update() 传入 assigneeWorkerIds — join 表更新, 旧行软删除")
        void update_withNewAssigneeList_softDeletesOldAndAddsNew() {
            // Arrange — existing PWP with one assignee (worker 1001)
            ProductWorkProcess existing = buildDefaultAssociation();
            existing.setId(30L);
            existing.setResponsibleWorkerId(1001L);
            when(repository.findByFactoryIdAndId(FACTORY_ID, 30L))
                    .thenReturn(Optional.of(existing));
            when(repository.save(any(ProductWorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            // Existing join-table row for worker 1001
            ProductWorkProcessAssignee existingRow = new ProductWorkProcessAssignee();
            existingRow.setId(5L);
            existingRow.setProductWorkProcessId(30L);
            existingRow.setWorkerId(1001L);
            when(assigneeRepository.findByProductWorkProcessId(30L))
                    .thenReturn(List.of(existingRow));

            // Update: replace with [1002, 1003]
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .assigneeWorkerIds(List.of(1002L, 1003L))
                    .build();

            // Act
            service.update(FACTORY_ID, 30L, dto);

            // Assert — worker 1001 row should be soft-deleted (save called on it with deletedAt set)
            verify(assigneeRepository, atLeastOnce()).save(
                    argThat((ProductWorkProcessAssignee a) ->
                            a.getWorkerId().equals(1001L) && a.getDeletedAt() != null));
            // Workers 1002 and 1003 should be inserted
            verify(assigneeRepository, atLeastOnce()).save(
                    argThat((ProductWorkProcessAssignee a) ->
                            a.getWorkerId().equals(1002L) && a.getDeletedAt() == null));
            verify(assigneeRepository, atLeastOnce()).save(
                    argThat((ProductWorkProcessAssignee a) ->
                            a.getWorkerId().equals(1003L) && a.getDeletedAt() == null));
        }

        @Test
        @DisplayName("UT-T121-04: create() assigneeWorkerIds=empty list → 无 join 行插入")
        void create_emptyAssigneeList_noJoinTableInserts() {
            // Arrange
            stubCreateHappyPath();
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(PRODUCT_TYPE_ID)
                    .workProcessId(WORK_PROCESS_ID)
                    .assigneeWorkerIds(List.of())    // empty → fall back to single-value
                    .build();

            // Act
            service.create(FACTORY_ID, dto);

            // Assert — empty list treated as "no multi-assignee", no insertions since no rw either
            verify(assigneeRepository, never()).save(any());
        }
    }
}
