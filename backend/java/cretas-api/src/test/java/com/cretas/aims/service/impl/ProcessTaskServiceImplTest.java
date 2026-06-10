package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ProcessTaskDTO;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.ProcessTask;
import com.cretas.aims.entity.enums.ProcessTaskStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProcessTaskRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.StateMachineRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ProcessTaskServiceImpl 单元测试
 *
 * 测试覆盖:
 * - UT-PT-01~03: 创建任务测试
 * - UT-PT-04~05: 列表查询测试
 * - UT-PT-06~07: 按ID查询测试
 * - UT-PT-08~12: 状态转换测试
 * - UT-PT-13~15: 关闭任务测试
 * - UT-PT-16~18: 汇总与概览测试
 * - UT-PT-19~20: 计算字段与工厂隔离测试
 *
 * @author Cretas Team
 * @since 2026-03-12
 */
@DisplayName("ProcessTaskServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class ProcessTaskServiceImplTest {

    @Mock
    private ProcessTaskRepository repository;

    @Mock
    private StateMachineRepository stateMachineRepository;

    @Mock
    private WorkProcessRepository workProcessRepository;

    @Mock
    private ProductWorkProcessRepository productWorkProcessRepository;

    @Mock
    private ProductTypeRepository productTypeRepository;

    @Mock
    private ProductionReportRepository reportRepository;

    @Mock
    private WorkProcessTaskRepository workProcessTaskRepository;

    @Mock
    private ProductionBatchRepository productionBatchRepository;

    @InjectMocks
    private ProcessTaskServiceImpl service;

    @Captor
    private ArgumentCaptor<ProcessTask> taskCaptor;

    private static final String FACTORY_ID = "F001";
    private static final String TASK_ID = "task-1";
    private static final String RUN_ID = "run-1";
    private static final String PRODUCT_TYPE_ID = "pt-1";
    private static final String WORK_PROCESS_ID = "wp-1";

    private ProcessTask buildDefaultTask() {
        return ProcessTask.builder()
                .id(TASK_ID)
                .factoryId(FACTORY_ID)
                .productionRunId(RUN_ID)
                .productTypeId(PRODUCT_TYPE_ID)
                .workProcessId(WORK_PROCESS_ID)
                .plannedQuantity(new BigDecimal("100"))
                .completedQuantity(BigDecimal.ZERO)
                .pendingQuantity(BigDecimal.ZERO)
                .unit("kg")
                .status(ProcessTaskStatus.PENDING)
                .sourceDocType("MANUAL")
                .createdBy(1L)
                .build();
    }

    private ProcessTaskDTO buildDefaultCreateDTO() {
        return ProcessTaskDTO.builder()
                .productTypeId(PRODUCT_TYPE_ID)
                .workProcessId(WORK_PROCESS_ID)
                .plannedQuantity(new BigDecimal("100"))
                .createdBy(1L)
                .build();
    }

    // ==================== 创建任务测试 ====================

    @Nested
    @DisplayName("创建任务测试")
    class CreateTaskTests {

        @Test
        @DisplayName("UT-PT-01: create() 设置 status=PENDING, completedQty=0, pendingQty=0, 生成 UUID")
        void testCreateSetsDefaultsAndGeneratesUUID() {
            // Arrange
            ProcessTaskDTO dto = buildDefaultCreateDTO();
            dto.setProductionRunId(RUN_ID);
            when(repository.save(any(ProcessTask.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            ProcessTaskDTO result = service.create(FACTORY_ID, dto);

            // Assert
            verify(repository).save(taskCaptor.capture());
            ProcessTask saved = taskCaptor.getValue();

            assertNotNull(saved.getId(), "应生成 UUID 作为 ID");
            assertEquals(36, saved.getId().length(), "UUID 应为 36 字符");
            assertEquals(FACTORY_ID, saved.getFactoryId());
            assertEquals(ProcessTaskStatus.PENDING, saved.getStatus());
            assertEquals(BigDecimal.ZERO, saved.getCompletedQuantity());
            assertEquals(BigDecimal.ZERO, saved.getPendingQuantity());
            assertEquals("PENDING", result.getStatus());
        }

        @Test
        @DisplayName("UT-PT-02: create() productionRunId 为 null 时自动生成")
        void testCreateGeneratesProductionRunIdWhenNull() {
            // Arrange
            ProcessTaskDTO dto = buildDefaultCreateDTO();
            dto.setProductionRunId(null); // explicitly null
            when(repository.save(any(ProcessTask.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.create(FACTORY_ID, dto);

            // Assert
            verify(repository).save(taskCaptor.capture());
            ProcessTask saved = taskCaptor.getValue();

            assertNotNull(saved.getProductionRunId(), "应自动生成 productionRunId");
            assertEquals(36, saved.getProductionRunId().length(), "自动生成的 productionRunId 应为 UUID 格式");
        }

        @Test
        @DisplayName("UT-PT-03: create() unit 和 sourceDocType 为 null 时使用默认值")
        void testCreateDefaultsUnitAndSourceDocType() {
            // Arrange
            ProcessTaskDTO dto = buildDefaultCreateDTO();
            dto.setUnit(null);
            dto.setSourceDocType(null);
            dto.setProductionRunId(RUN_ID);
            when(repository.save(any(ProcessTask.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.create(FACTORY_ID, dto);

            // Assert
            verify(repository).save(taskCaptor.capture());
            ProcessTask saved = taskCaptor.getValue();

            assertEquals("kg", saved.getUnit(), "unit 为 null 时应默认为 kg");
            assertEquals("MANUAL", saved.getSourceDocType(), "sourceDocType 为 null 时应默认为 MANUAL");
        }
    }

    // ==================== 列表查询测试 ====================

    @Nested
    @DisplayName("列表查询测试")
    class ListTaskTests {

        @Test
        @DisplayName("UT-PT-04: getActiveTasks() 调用 findActiveTasks 并返回 DTO 列表")
        void testGetActiveTasksCallsFindActiveTasks() {
            // Arrange
            ProcessTask task = buildDefaultTask();
            task.setStatus(ProcessTaskStatus.IN_PROGRESS);
            when(repository.findActiveTasks(FACTORY_ID)).thenReturn(List.of(task));

            // Act
            List<ProcessTaskDTO> result = service.getActiveTasks(FACTORY_ID);

            // Assert
            verify(repository).findActiveTasks(FACTORY_ID);
            assertEquals(1, result.size());
            assertEquals(TASK_ID, result.get(0).getId());
            assertEquals("IN_PROGRESS", result.get(0).getStatus());
        }

        @Test
        @DisplayName("UT-PT-05: list() 根据 4 种过滤组合分派到正确的 Repository 方法")
        void testListDispatchesToCorrectRepoMethod() {
            Pageable pageable = PageRequest.of(0, 20);
            ProcessTask task = buildDefaultTask();
            Page<ProcessTask> page = new PageImpl<>(List.of(task), pageable, 1);

            // Case 1: both null
            when(repository.findByFactoryId(eq(FACTORY_ID), eq(pageable))).thenReturn(page);
            PageResponse<ProcessTaskDTO> result1 = service.list(FACTORY_ID, null, null, pageable);
            verify(repository).findByFactoryId(FACTORY_ID, pageable);
            assertEquals(1, result1.getContent().size());

            // Case 2: status only
            when(repository.findByFactoryIdAndStatus(eq(FACTORY_ID), eq(ProcessTaskStatus.PENDING), eq(pageable)))
                    .thenReturn(page);
            PageResponse<ProcessTaskDTO> result2 = service.list(FACTORY_ID, "PENDING", null, pageable);
            verify(repository).findByFactoryIdAndStatus(FACTORY_ID, ProcessTaskStatus.PENDING, pageable);
            assertEquals(1, result2.getContent().size());

            // Case 3: productTypeId only
            when(repository.findByFactoryIdAndProductTypeId(eq(FACTORY_ID), eq(PRODUCT_TYPE_ID), eq(pageable)))
                    .thenReturn(page);
            PageResponse<ProcessTaskDTO> result3 = service.list(FACTORY_ID, null, PRODUCT_TYPE_ID, pageable);
            verify(repository).findByFactoryIdAndProductTypeId(FACTORY_ID, PRODUCT_TYPE_ID, pageable);
            assertEquals(1, result3.getContent().size());

            // Case 4: both set
            when(repository.findByFactoryIdAndStatusAndProductTypeId(
                    eq(FACTORY_ID), eq(ProcessTaskStatus.IN_PROGRESS), eq(PRODUCT_TYPE_ID), eq(pageable)))
                    .thenReturn(page);
            PageResponse<ProcessTaskDTO> result4 = service.list(FACTORY_ID, "IN_PROGRESS", PRODUCT_TYPE_ID, pageable);
            verify(repository).findByFactoryIdAndStatusAndProductTypeId(
                    FACTORY_ID, ProcessTaskStatus.IN_PROGRESS, PRODUCT_TYPE_ID, pageable);
            assertEquals(1, result4.getContent().size());
        }
    }

    // ==================== 按ID查询测试 ====================

    @Nested
    @DisplayName("按ID查询测试")
    class GetByIdTests {

        @Test
        @DisplayName("UT-PT-06: getById() 找到时返回 DTO 包含计算字段")
        void testGetByIdFoundReturnsDTOWithComputedFields() {
            // Arrange
            ProcessTask task = buildDefaultTask();
            task.setCompletedQuantity(new BigDecimal("60"));
            task.setPendingQuantity(new BigDecimal("10"));
            when(repository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));

            // Act
            ProcessTaskDTO result = service.getById(FACTORY_ID, TASK_ID);

            // Assert
            verify(repository).findByFactoryIdAndId(FACTORY_ID, TASK_ID);
            assertEquals(TASK_ID, result.getId());
            assertEquals(new BigDecimal("70"), result.getEstimatedProgress()); // 60 + 10
            assertEquals(new BigDecimal("60"), result.getConfirmedProgress());
            assertFalse(result.getTargetReached(), "60 < 100 所以目标未达成");
        }

        @Test
        @DisplayName("UT-PT-07: getById() 未找到时抛出 ResourceNotFoundException")
        void testGetByIdNotFoundThrowsException() {
            // Arrange
            when(repository.findByFactoryIdAndId(FACTORY_ID, "nonexistent"))
                    .thenReturn(Optional.empty());

            // Act & Assert
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.getById(FACTORY_ID, "nonexistent"));
            assertTrue(ex.getMessage().contains("ProcessTask"));
            assertTrue(ex.getMessage().contains("nonexistent"));
        }
    }

    // ==================== 状态转换测试 ====================

    @Nested
    @DisplayName("状态转换测试")
    class StatusTransitionTests {

        @Test
        @DisplayName("UT-PT-08: updateStatus() PENDING -> IN_PROGRESS 有效转换")
        void testPendingToInProgressValid() {
            // Arrange
            ProcessTask task = buildDefaultTask();
            task.setStatus(ProcessTaskStatus.PENDING);
            when(repository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));
            when(repository.save(any(ProcessTask.class))).thenAnswer(inv -> inv.getArgument(0));

            ProcessTaskDTO.StatusUpdateRequest request = ProcessTaskDTO.StatusUpdateRequest.builder()
                    .status("IN_PROGRESS")
                    .build();

            // Act
            ProcessTaskDTO result = service.updateStatus(FACTORY_ID, TASK_ID, request);

            // Assert
            verify(repository).save(taskCaptor.capture());
            assertEquals(ProcessTaskStatus.IN_PROGRESS, taskCaptor.getValue().getStatus());
            assertEquals("IN_PROGRESS", result.getStatus());
        }

        @Test
        @DisplayName("UT-PT-09: updateStatus() PENDING -> COMPLETED 抛出 BusinessException")
        void testPendingToCompletedThrows() {
            // Arrange
            ProcessTask task = buildDefaultTask();
            task.setStatus(ProcessTaskStatus.PENDING);
            when(repository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));

            ProcessTaskDTO.StatusUpdateRequest request = ProcessTaskDTO.StatusUpdateRequest.builder()
                    .status("COMPLETED")
                    .build();

            // Act & Assert
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.updateStatus(FACTORY_ID, TASK_ID, request));
            assertTrue(ex.getMessage().contains("待开始的任务只能转为进行中或关闭"));
        }

        @Test
        @DisplayName("UT-PT-10: updateStatus() IN_PROGRESS -> COMPLETED 有效转换")
        void testInProgressToCompletedValid() {
            // Arrange
            ProcessTask task = buildDefaultTask();
            task.setStatus(ProcessTaskStatus.IN_PROGRESS);
            when(repository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));
            when(repository.save(any(ProcessTask.class))).thenAnswer(inv -> inv.getArgument(0));

            ProcessTaskDTO.StatusUpdateRequest request = ProcessTaskDTO.StatusUpdateRequest.builder()
                    .status("COMPLETED")
                    .notes("任务完成")
                    .build();

            // Act
            ProcessTaskDTO result = service.updateStatus(FACTORY_ID, TASK_ID, request);

            // Assert
            verify(repository).save(taskCaptor.capture());
            ProcessTask saved = taskCaptor.getValue();
            assertEquals(ProcessTaskStatus.COMPLETED, saved.getStatus());
            assertEquals("任务完成", saved.getNotes());
            assertEquals("COMPLETED", result.getStatus());
        }

        @Test
        @DisplayName("UT-PT-11: updateStatus() COMPLETED -> SUPPLEMENTING 保存 previousTerminalStatus")
        void testCompletedToSupplementingSavesPreviousStatus() {
            // Arrange
            ProcessTask task = buildDefaultTask();
            task.setStatus(ProcessTaskStatus.COMPLETED);
            when(repository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));
            when(repository.save(any(ProcessTask.class))).thenAnswer(inv -> inv.getArgument(0));

            ProcessTaskDTO.StatusUpdateRequest request = ProcessTaskDTO.StatusUpdateRequest.builder()
                    .status("SUPPLEMENTING")
                    .build();

            // Act
            ProcessTaskDTO result = service.updateStatus(FACTORY_ID, TASK_ID, request);

            // Assert
            verify(repository).save(taskCaptor.capture());
            ProcessTask saved = taskCaptor.getValue();
            assertEquals(ProcessTaskStatus.SUPPLEMENTING, saved.getStatus());
            assertEquals("COMPLETED", saved.getPreviousTerminalStatus());
            assertEquals("SUPPLEMENTING", result.getStatus());
        }

        @Test
        @DisplayName("UT-PT-12: updateStatus() CLOSED -> SUPPLEMENTING 保存 previousTerminalStatus")
        void testClosedToSupplementingSavesPreviousStatus() {
            // Arrange
            ProcessTask task = buildDefaultTask();
            task.setStatus(ProcessTaskStatus.CLOSED);
            when(repository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));
            when(repository.save(any(ProcessTask.class))).thenAnswer(inv -> inv.getArgument(0));

            ProcessTaskDTO.StatusUpdateRequest request = ProcessTaskDTO.StatusUpdateRequest.builder()
                    .status("SUPPLEMENTING")
                    .build();

            // Act
            ProcessTaskDTO result = service.updateStatus(FACTORY_ID, TASK_ID, request);

            // Assert
            verify(repository).save(taskCaptor.capture());
            ProcessTask saved = taskCaptor.getValue();
            assertEquals(ProcessTaskStatus.SUPPLEMENTING, saved.getStatus());
            assertEquals("CLOSED", saved.getPreviousTerminalStatus());
        }
    }

    // ==================== 关闭任务测试 ====================

    @Nested
    @DisplayName("关闭任务测试")
    class CloseTaskTests {

        @Test
        @DisplayName("UT-PT-13: closeTask() IN_PROGRESS -> CLOSED 成功")
        void testCloseInProgressTaskSucceeds() {
            // Arrange
            ProcessTask task = buildDefaultTask();
            task.setStatus(ProcessTaskStatus.IN_PROGRESS);
            when(repository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));
            when(repository.save(any(ProcessTask.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            ProcessTaskDTO result = service.closeTask(FACTORY_ID, TASK_ID, "手动关闭");

            // Assert
            verify(repository).save(taskCaptor.capture());
            ProcessTask saved = taskCaptor.getValue();
            assertEquals(ProcessTaskStatus.CLOSED, saved.getStatus());
            assertEquals("手动关闭", saved.getNotes());
            assertEquals("CLOSED", result.getStatus());
        }

        @Test
        @DisplayName("UT-PT-14: closeTask() 已 CLOSED 抛出 '任务已关闭'")
        void testCloseAlreadyClosedThrows() {
            // Arrange
            ProcessTask task = buildDefaultTask();
            task.setStatus(ProcessTaskStatus.CLOSED);
            when(repository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));

            // Act & Assert
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.closeTask(FACTORY_ID, TASK_ID, null));
            assertEquals("任务已关闭", ex.getMessage());
        }

        @Test
        @DisplayName("UT-PT-15: closeTask() SUPPLEMENTING 抛出 '任务正在补报中'")
        void testCloseSupplementingThrows() {
            // Arrange
            ProcessTask task = buildDefaultTask();
            task.setStatus(ProcessTaskStatus.SUPPLEMENTING);
            when(repository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));

            // Act & Assert
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.closeTask(FACTORY_ID, TASK_ID, null));
            assertTrue(ex.getMessage().contains("任务正在补报中"));
        }
    }

    // ==================== 汇总与概览测试 ====================

    @Nested
    @DisplayName("汇总与概览测试")
    class SummaryAndOverviewTests {

        @Test
        @DisplayName("UT-PT-16: getTaskSummary() 返回正确的 TaskSummary 含数量字段")
        void testGetTaskSummaryReturnsCorrectQuantities() {
            // Arrange
            ProcessTask task = buildDefaultTask();
            task.setCompletedQuantity(new BigDecimal("50"));
            task.setPendingQuantity(new BigDecimal("20"));
            when(repository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));

            // Act
            ProcessTaskDTO.TaskSummary summary = service.getTaskSummary(FACTORY_ID, TASK_ID);

            // Assert
            assertEquals(TASK_ID, summary.getTaskId());
            assertEquals(new BigDecimal("100"), summary.getPlannedQuantity());
            assertEquals(new BigDecimal("50"), summary.getCompletedQuantity());
            assertEquals(new BigDecimal("20"), summary.getPendingQuantity());
            assertEquals("kg", summary.getUnit());
            assertEquals("PENDING", summary.getStatus());
        }

        @Test
        @DisplayName("UT-PT-17: getRunOverview() 聚合任务列表并计算 overallProgress")
        void testGetRunOverviewAggregatesAndCalculatesProgress() {
            // Arrange
            ProcessTask task1 = buildDefaultTask();
            task1.setId("task-1");
            task1.setPlannedQuantity(new BigDecimal("100"));
            task1.setCompletedQuantity(new BigDecimal("80"));
            task1.setSourceCustomerName("客户A");

            ProcessTask task2 = ProcessTask.builder()
                    .id("task-2")
                    .factoryId(FACTORY_ID)
                    .productionRunId(RUN_ID)
                    .productTypeId("pt-2")
                    .workProcessId("wp-2")
                    .plannedQuantity(new BigDecimal("200"))
                    .completedQuantity(new BigDecimal("100"))
                    .pendingQuantity(BigDecimal.ZERO)
                    .unit("kg")
                    .status(ProcessTaskStatus.IN_PROGRESS)
                    .build();

            when(repository.findByFactoryIdAndProductionRunId(FACTORY_ID, RUN_ID))
                    .thenReturn(List.of(task1, task2));

            // Act
            ProcessTaskDTO.RunOverview overview = service.getRunOverview(FACTORY_ID, RUN_ID);

            // Assert
            assertEquals(RUN_ID, overview.getProductionRunId());
            assertEquals("客户A", overview.getSourceCustomerName());
            assertEquals(2, overview.getTasks().size());
            // overallProgress = (80+100) / (100+200) * 100 = 60.00
            BigDecimal expectedProgress = new BigDecimal("180")
                    .multiply(BigDecimal.valueOf(100))
                    .divide(new BigDecimal("300"), 2, RoundingMode.HALF_UP);
            assertEquals(expectedProgress, overview.getOverallProgress());
        }

        @Test
        @DisplayName("UT-PT-18: getRunOverview() 空任务列表抛出 ResourceNotFoundException")
        void testGetRunOverviewEmptyTasksThrows() {
            // Arrange
            when(repository.findByFactoryIdAndProductionRunId(FACTORY_ID, "nonexistent-run"))
                    .thenReturn(Collections.emptyList());

            // Act & Assert
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.getRunOverview(FACTORY_ID, "nonexistent-run"));
            assertTrue(ex.getMessage().contains("ProductionRun"));
        }
    }

    // ==================== 计算字段与工厂隔离测试 ====================

    @Nested
    @DisplayName("计算字段与工厂隔离测试")
    class ComputedFieldsAndIsolationTests {

        @Test
        @DisplayName("UT-PT-19: toDTO() completedQty >= plannedQty 时 targetReached=true")
        void testTargetReachedTrueWhenCompletedExceedsPlanned() {
            // Arrange
            ProcessTask task = buildDefaultTask();
            task.setPlannedQuantity(new BigDecimal("100"));
            task.setCompletedQuantity(new BigDecimal("105"));
            task.setPendingQuantity(BigDecimal.ZERO);
            when(repository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));

            // Act
            ProcessTaskDTO result = service.getById(FACTORY_ID, TASK_ID);

            // Assert
            assertTrue(result.getTargetReached(), "completedQty(105) >= plannedQty(100) 应为 true");
            assertEquals(new BigDecimal("105"), result.getEstimatedProgress());
            assertEquals(new BigDecimal("105"), result.getConfirmedProgress());
        }

        @Test
        @DisplayName("UT-PT-20: 工厂隔离 — 不同 factoryId 返回空结果")
        void testFactoryIsolationReturnEmpty() {
            // Arrange
            String otherFactory = "F999";
            when(repository.findActiveTasks(otherFactory)).thenReturn(Collections.emptyList());

            // Act
            List<ProcessTaskDTO> result = service.getActiveTasks(otherFactory);

            // Assert
            verify(repository).findActiveTasks(otherFactory);
            assertTrue(result.isEmpty(), "不同工厂应返回空列表");
        }
    }
}
