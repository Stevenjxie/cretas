package com.cretas.aims.service.impl;

import com.cretas.aims.dto.WorkProcessDTO;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.WorkProcessRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WorkProcessServiceImpl 单元测试
 *
 * 测试覆盖:
 * - UT-WP-01~02: 创建工序测试
 * - UT-WP-03: 查询活跃工序测试
 * - UT-WP-04: 按ID查询测试
 * - UT-WP-05: 更新工序测试
 * - UT-WP-06: 删除工序测试
 * - UT-WP-07: 工厂隔离测试
 *
 * @author Cretas Team
 * @since 2026-03-12
 */
@DisplayName("WorkProcessServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class WorkProcessServiceImplTest {

    @Mock
    private WorkProcessRepository workProcessRepository;

    @InjectMocks
    private WorkProcessServiceImpl service;

    @Captor
    private ArgumentCaptor<WorkProcess> workProcessCaptor;

    private static final String FACTORY_ID = "F001";
    private static final String WP_ID = "wp-1";

    private WorkProcess buildDefaultWorkProcess() {
        return WorkProcess.builder()
                .id(WP_ID)
                .factoryId(FACTORY_ID)
                .processName("炸制")
                .processCategory("加工")
                .unit("kg")
                .estimatedMinutes(30)
                .sortOrder(1)
                .isActive(true)
                .build();
    }

    private WorkProcessDTO buildDefaultCreateDTO() {
        return WorkProcessDTO.builder()
                .processName("炸制")
                .processCategory("加工")
                .estimatedMinutes(30)
                .sortOrder(1)
                .build();
    }

    // ==================== 创建工序测试 ====================

    @Nested
    @DisplayName("创建工序测试")
    class CreateTests {

        @Test
        @DisplayName("UT-WP-01: create() 成功创建包含所有字段，检查名称唯一性")
        void testCreateSuccessWithAllFields() {
            // Arrange
            WorkProcessDTO dto = buildDefaultCreateDTO();
            dto.setUnit("件");
            when(workProcessRepository.existsByFactoryIdAndProcessName(FACTORY_ID, "炸制"))
                    .thenReturn(false);
            when(workProcessRepository.save(any(WorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            WorkProcessDTO result = service.create(FACTORY_ID, dto);

            // Assert
            verify(workProcessRepository).existsByFactoryIdAndProcessName(FACTORY_ID, "炸制");
            verify(workProcessRepository).save(workProcessCaptor.capture());
            WorkProcess saved = workProcessCaptor.getValue();

            assertNotNull(saved.getId(), "应生成 UUID 作为 ID");
            assertEquals(36, saved.getId().length());
            assertEquals(FACTORY_ID, saved.getFactoryId());
            assertEquals("炸制", saved.getProcessName());
            assertEquals("加工", saved.getProcessCategory());
            assertEquals("件", saved.getUnit());
            assertEquals(30, saved.getEstimatedMinutes());
            assertEquals(1, saved.getSortOrder());
            assertTrue(saved.getIsActive(), "新创建的工序应为 active");

            assertEquals("炸制", result.getProcessName());
        }

        @Test
        @DisplayName("UT-WP-01b: create() 名称已存在时抛出 BusinessException")
        void testCreateDuplicateNameThrows() {
            // Arrange
            WorkProcessDTO dto = buildDefaultCreateDTO();
            when(workProcessRepository.existsByFactoryIdAndProcessName(FACTORY_ID, "炸制"))
                    .thenReturn(true);

            // Act & Assert
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.create(FACTORY_ID, dto));
            assertTrue(ex.getMessage().contains("工序名称已存在"));
            verify(workProcessRepository, never()).save(any());
        }

        @Test
        @DisplayName("UT-WP-02: create() unit 为 null 时默认为 'kg'")
        void testCreateNullUnitDefaultsToKg() {
            // Arrange
            WorkProcessDTO dto = buildDefaultCreateDTO();
            dto.setUnit(null);
            when(workProcessRepository.existsByFactoryIdAndProcessName(FACTORY_ID, "炸制"))
                    .thenReturn(false);
            when(workProcessRepository.save(any(WorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.create(FACTORY_ID, dto);

            // Assert
            verify(workProcessRepository).save(workProcessCaptor.capture());
            assertEquals("kg", workProcessCaptor.getValue().getUnit(), "unit 为 null 时应默认为 kg");
        }

        @Test
        @DisplayName("UT-WP-P0-3-a: create() 映射 4 个出成率配置字段")
        void testCreateMapsYieldConfigFields() {
            // Arrange
            WorkProcessDTO dto = buildDefaultCreateDTO();
            dto.setStandardYieldMin(new BigDecimal("0.30"));
            dto.setStandardYieldMax(new BigDecimal("0.60"));
            dto.setNeedsInput(false);
            dto.setOutputUnit("盒");
            when(workProcessRepository.existsByFactoryIdAndProcessName(FACTORY_ID, "炸制"))
                    .thenReturn(false);
            when(workProcessRepository.save(any(WorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            WorkProcessDTO result = service.create(FACTORY_ID, dto);

            // Assert
            verify(workProcessRepository).save(workProcessCaptor.capture());
            WorkProcess saved = workProcessCaptor.getValue();
            assertEquals(0, new BigDecimal("0.30").compareTo(saved.getStandardYieldMin()));
            assertEquals(0, new BigDecimal("0.60").compareTo(saved.getStandardYieldMax()));
            assertFalse(saved.getNeedsInput(), "needsInput=false 应被映射");
            assertEquals("盒", saved.getOutputUnit());
            assertEquals(0, new BigDecimal("0.60").compareTo(result.getStandardYieldMax()), "返回 DTO 含上限");
        }

        @Test
        @DisplayName("UT-WP-P0-3-b: create() min>=max 抛 BusinessException(400) hintTarget=standardYieldMax")
        void testCreateYieldMinGteMaxThrows() {
            // Arrange
            WorkProcessDTO dto = buildDefaultCreateDTO();
            dto.setStandardYieldMin(new BigDecimal("0.60"));
            dto.setStandardYieldMax(new BigDecimal("0.30"));
            when(workProcessRepository.existsByFactoryIdAndProcessName(FACTORY_ID, "炸制"))
                    .thenReturn(false);

            // Act & Assert
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.create(FACTORY_ID, dto));
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("下限必须小于上限"));
            assertEquals("standardYieldMax", ex.getHintTarget());
            verify(workProcessRepository, never()).save(any());
        }

        @Test
        @DisplayName("UT-WP-P0-3-c: create() 不传 needsInput → entity 默认 true")
        void testCreateDefaultsNeedsInputTrue() {
            // Arrange
            WorkProcessDTO dto = buildDefaultCreateDTO();
            // needsInput left null
            when(workProcessRepository.existsByFactoryIdAndProcessName(FACTORY_ID, "炸制"))
                    .thenReturn(false);
            when(workProcessRepository.save(any(WorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.create(FACTORY_ID, dto);

            // Assert
            verify(workProcessRepository).save(workProcessCaptor.capture());
            assertTrue(workProcessCaptor.getValue().getNeedsInput(), "needsInput 缺省应为 true");
        }
    }

    // ==================== 查询活跃工序测试 ====================

    @Nested
    @DisplayName("查询活跃工序测试")
    class ListActiveTests {

        @Test
        @DisplayName("UT-WP-03: listActive() 返回仅 isActive=true 且按 sortOrder 排序")
        void testListActiveReturnsActiveOrderedBySortOrder() {
            // Arrange
            WorkProcess wp1 = buildDefaultWorkProcess();
            wp1.setId("wp-1");
            wp1.setSortOrder(1);

            WorkProcess wp2 = WorkProcess.builder()
                    .id("wp-2")
                    .factoryId(FACTORY_ID)
                    .processName("包装")
                    .processCategory("后处理")
                    .unit("件")
                    .estimatedMinutes(15)
                    .sortOrder(2)
                    .isActive(true)
                    .build();

            when(workProcessRepository.findByFactoryIdAndIsActiveTrueOrderBySortOrderAsc(FACTORY_ID))
                    .thenReturn(List.of(wp1, wp2));

            // Act
            List<WorkProcessDTO> result = service.listActive(FACTORY_ID);

            // Assert
            verify(workProcessRepository).findByFactoryIdAndIsActiveTrueOrderBySortOrderAsc(FACTORY_ID);
            assertEquals(2, result.size());
            assertEquals("炸制", result.get(0).getProcessName());
            assertEquals("包装", result.get(1).getProcessName());
            assertTrue(result.get(0).getIsActive());
            assertTrue(result.get(1).getIsActive());
        }
    }

    // ==================== 按ID查询测试 ====================

    @Nested
    @DisplayName("按ID查询测试")
    class GetByIdTests {

        @Test
        @DisplayName("UT-WP-04a: getById() 找到时返回正确的 DTO")
        void testGetByIdFound() {
            // Arrange
            WorkProcess wp = buildDefaultWorkProcess();
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                    .thenReturn(Optional.of(wp));

            // Act
            WorkProcessDTO result = service.getById(FACTORY_ID, WP_ID);

            // Assert
            verify(workProcessRepository).findByFactoryIdAndId(FACTORY_ID, WP_ID);
            assertEquals(WP_ID, result.getId());
            assertEquals("炸制", result.getProcessName());
            assertEquals("加工", result.getProcessCategory());
            assertEquals("kg", result.getUnit());
            assertEquals(30, result.getEstimatedMinutes());
            assertEquals(1, result.getSortOrder());
            assertTrue(result.getIsActive());
        }

        @Test
        @DisplayName("UT-WP-04b: getById() 未找到时抛出 ResourceNotFoundException")
        void testGetByIdNotFound() {
            // Arrange
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, "nonexistent"))
                    .thenReturn(Optional.empty());

            // Act & Assert
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.getById(FACTORY_ID, "nonexistent"));
            assertTrue(ex.getMessage().contains("WorkProcess"));
        }
    }

    // ==================== 更新工序测试 ====================

    @Nested
    @DisplayName("更新工序测试")
    class UpdateTests {

        @Test
        @DisplayName("UT-WP-05: update() 部分更新保留未修改字段")
        void testPartialUpdatePreservesUnchangedFields() {
            // Arrange
            WorkProcess existing = buildDefaultWorkProcess();
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                    .thenReturn(Optional.of(existing));
            when(workProcessRepository.save(any(WorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            // Only update processName, leave others null
            WorkProcessDTO dto = WorkProcessDTO.builder()
                    .processName("蒸制")
                    .build();

            // Act
            WorkProcessDTO result = service.update(FACTORY_ID, WP_ID, dto);

            // Assert
            verify(workProcessRepository).save(workProcessCaptor.capture());
            WorkProcess saved = workProcessCaptor.getValue();
            assertEquals("蒸制", saved.getProcessName(), "processName 应被更新");
            assertEquals("加工", saved.getProcessCategory(), "processCategory 未传入应保持原值");
            assertEquals("kg", saved.getUnit(), "unit 未传入应保持原值");
            assertEquals(30, saved.getEstimatedMinutes(), "estimatedMinutes 未传入应保持原值");
            assertEquals(1, saved.getSortOrder(), "sortOrder 未传入应保持原值");
        }

        @Test
        @DisplayName("UT-WP-P0-3-d: update() 只传 standardYieldMax → 其余不动, 与已有 min 校验通过")
        void testUpdateYieldMaxOnly() {
            // Arrange — existing min=0.30, no max
            WorkProcess existing = buildDefaultWorkProcess();
            existing.setStandardYieldMin(new BigDecimal("0.30"));
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                    .thenReturn(Optional.of(existing));
            when(workProcessRepository.save(any(WorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            WorkProcessDTO dto = WorkProcessDTO.builder()
                    .standardYieldMax(new BigDecimal("0.60"))
                    .build();

            // Act
            service.update(FACTORY_ID, WP_ID, dto);

            // Assert
            verify(workProcessRepository).save(workProcessCaptor.capture());
            WorkProcess saved = workProcessCaptor.getValue();
            assertEquals(0, new BigDecimal("0.30").compareTo(saved.getStandardYieldMin()), "min 未传入保持原值");
            assertEquals(0, new BigDecimal("0.60").compareTo(saved.getStandardYieldMax()), "max 被更新");
        }

        @Test
        @DisplayName("UT-WP-P0-3-e: update() 新 max <= 已有 min → 400")
        void testUpdateYieldMaxLteExistingMinThrows() {
            // Arrange — existing min=0.60
            WorkProcess existing = buildDefaultWorkProcess();
            existing.setStandardYieldMin(new BigDecimal("0.60"));
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                    .thenReturn(Optional.of(existing));

            WorkProcessDTO dto = WorkProcessDTO.builder()
                    .standardYieldMax(new BigDecimal("0.30"))
                    .build();

            // Act & Assert
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.update(FACTORY_ID, WP_ID, dto));
            assertEquals(400, ex.getCode());
            verify(workProcessRepository, never()).save(any());
        }

        @Test
        @DisplayName("UT-WP-P0-3-f: toDTO 往返 — entity 4 字段全映射到 DTO")
        void testToDtoRoundtripYieldFields() {
            // Arrange
            WorkProcess existing = buildDefaultWorkProcess();
            existing.setStandardYieldMin(new BigDecimal("1.0000"));
            existing.setStandardYieldMax(new BigDecimal("1.3500"));
            existing.setNeedsInput(false);
            existing.setOutputUnit("份");
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                    .thenReturn(Optional.of(existing));

            // Act
            WorkProcessDTO result = service.getById(FACTORY_ID, WP_ID);

            // Assert
            assertEquals(0, new BigDecimal("1.0000").compareTo(result.getStandardYieldMin()));
            assertEquals(0, new BigDecimal("1.3500").compareTo(result.getStandardYieldMax()));
            assertFalse(result.getNeedsInput());
            assertEquals("份", result.getOutputUnit());
        }
    }

    // ==================== 删除工序测试 ====================

    @Nested
    @DisplayName("删除工序测试")
    class DeleteTests {

        @Test
        @DisplayName("UT-WP-06: delete() 调用 repository.delete()")
        void testDeleteCallsRepositoryDelete() {
            // Arrange
            WorkProcess existing = buildDefaultWorkProcess();
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                    .thenReturn(Optional.of(existing));

            // Act
            service.delete(FACTORY_ID, WP_ID);

            // Assert
            verify(workProcessRepository).findByFactoryIdAndId(FACTORY_ID, WP_ID);
            verify(workProcessRepository).delete(existing);
        }

        @Test
        @DisplayName("UT-WP-06b: delete() 不存在时抛出 ResourceNotFoundException")
        void testDeleteNotFoundThrows() {
            // Arrange
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, "nonexistent"))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThrows(ResourceNotFoundException.class,
                    () -> service.delete(FACTORY_ID, "nonexistent"));
            verify(workProcessRepository, never()).delete(any());
        }
    }

    // ==================== 工厂隔离测试 ====================

    @Nested
    @DisplayName("工厂隔离测试")
    class FactoryIsolationTests {

        @Test
        @DisplayName("UT-WP-07: 不同 factoryId 返回空结果")
        void testDifferentFactoryReturnsEmpty() {
            // Arrange
            String otherFactory = "F999";
            when(workProcessRepository.findByFactoryIdAndIsActiveTrueOrderBySortOrderAsc(otherFactory))
                    .thenReturn(Collections.emptyList());

            // Act
            List<WorkProcessDTO> result = service.listActive(otherFactory);

            // Assert
            verify(workProcessRepository).findByFactoryIdAndIsActiveTrueOrderBySortOrderAsc(otherFactory);
            assertTrue(result.isEmpty(), "不同工厂应返回空列表");
        }
    }

    // ==================== toggleStatus 与 updateSortOrder 额外测试 ====================

    @Nested
    @DisplayName("状态切换与排序更新测试")
    class ToggleAndSortTests {

        @Test
        @DisplayName("toggleStatus() 翻转 isActive 状态")
        void testToggleStatusFlipsActive() {
            // Arrange
            WorkProcess existing = buildDefaultWorkProcess();
            assertTrue(existing.getIsActive());
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                    .thenReturn(Optional.of(existing));
            when(workProcessRepository.save(any(WorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            WorkProcessDTO result = service.toggleStatus(FACTORY_ID, WP_ID);

            // Assert
            verify(workProcessRepository).save(workProcessCaptor.capture());
            assertFalse(workProcessCaptor.getValue().getIsActive(), "active 应从 true 翻转为 false");
            assertFalse(result.getIsActive());
        }

        @Test
        @DisplayName("updateSortOrder() 更新多个工序的排序")
        void testUpdateSortOrderMultipleItems() {
            // Arrange
            WorkProcess wp1 = buildDefaultWorkProcess();
            wp1.setId("wp-1");
            wp1.setSortOrder(1);

            WorkProcess wp2 = WorkProcess.builder()
                    .id("wp-2")
                    .factoryId(FACTORY_ID)
                    .processName("包装")
                    .sortOrder(2)
                    .isActive(true)
                    .build();

            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, "wp-1"))
                    .thenReturn(Optional.of(wp1));
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, "wp-2"))
                    .thenReturn(Optional.of(wp2));
            when(workProcessRepository.save(any(WorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

            List<WorkProcessDTO.SortOrderUpdate> updates = List.of(
                    WorkProcessDTO.SortOrderUpdate.builder().id("wp-1").sortOrder(3).build(),
                    WorkProcessDTO.SortOrderUpdate.builder().id("wp-2").sortOrder(1).build()
            );

            // Act
            service.updateSortOrder(FACTORY_ID, updates);

            // Assert
            verify(workProcessRepository, times(2)).save(any(WorkProcess.class));
            assertEquals(3, wp1.getSortOrder());
            assertEquals(1, wp2.getSortOrder());
        }
    }
}
