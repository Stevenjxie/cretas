package com.cretas.aims.service.workprocess.impl;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * WorkProcessTaskServiceImpl 单元测试 (Phase D Task 0).
 *
 * 覆盖:
 *   - toDTO 透出 standardYieldMin / standardYieldMax (A7 标准出成率区间)
 *   - toDTO 透出 expectedByproducts (防呆 Rule 3: OUTPUT 阶段副产物预填)
 */
@DisplayName("WorkProcessTaskServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class WorkProcessTaskServiceImplTest {

    @Mock
    private WorkProcessTaskRepository taskRepository;

    @Mock
    private ProductWorkProcessRepository productWorkProcessRepository;

    @Mock
    private WorkProcessRepository workProcessRepository;

    @InjectMocks
    private WorkProcessTaskServiceImpl service;

    private static final String FACTORY_ID = "F001";
    private static final String WP_ID = "wp-1";
    private static final Long TASK_ID = 100L;

    @Test
    @DisplayName("getById: definition 配了 standardYieldMin/Max → DTO 透出区间")
    void getById_exposesStandardYieldRange() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(TASK_ID)
                .factoryId(FACTORY_ID)
                .workProcessId(WP_ID)
                .status(WorkProcessTask.Status.PENDING)
                .build();

        WorkProcess definition = WorkProcess.builder()
                .id(WP_ID)
                .factoryId(FACTORY_ID)
                .processName("炸制")
                .processCategory("加工")
                .standardYieldMin(new BigDecimal("0.85"))
                .standardYieldMax(new BigDecimal("0.95"))
                .build();

        when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                .thenReturn(Optional.of(task));
        when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                .thenReturn(Optional.of(definition));

        WorkProcessTaskDTO dto = service.getById(FACTORY_ID, TASK_ID);

        assertEquals(0, new BigDecimal("0.85").compareTo(dto.getStandardYieldMin()));
        assertEquals(0, new BigDecimal("0.95").compareTo(dto.getStandardYieldMax()));
    }

    @Test
    @DisplayName("getById: definition 未配区间 → DTO 字段 null (不校验)")
    void getById_nullStandardYieldWhenUnconfigured() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(TASK_ID)
                .factoryId(FACTORY_ID)
                .workProcessId(WP_ID)
                .status(WorkProcessTask.Status.PENDING)
                .build();

        WorkProcess definition = WorkProcess.builder()
                .id(WP_ID)
                .factoryId(FACTORY_ID)
                .processName("包装")
                .processCategory("包装")
                .build();

        when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                .thenReturn(Optional.of(task));
        when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                .thenReturn(Optional.of(definition));

        WorkProcessTaskDTO dto = service.getById(FACTORY_ID, TASK_ID);

        assertNull(dto.getStandardYieldMin());
        assertNull(dto.getStandardYieldMax());
    }

    // ==================== expectedByproducts 透传测试 ====================

    @Test
    @DisplayName("getById: definition 有 expectedByproducts → DTO 透出副产物列表")
    void getById_exposesExpectedByproducts() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(TASK_ID)
                .factoryId(FACTORY_ID)
                .workProcessId(WP_ID)
                .status(WorkProcessTask.Status.PENDING)
                .build();

        List<Map<String, Object>> byproducts = List.of(
                Map.of("name", "肥油", "unit", "kg", "defaultEnabled", true)
        );

        WorkProcess definition = WorkProcess.builder()
                .id(WP_ID)
                .factoryId(FACTORY_ID)
                .processName("修油")
                .processCategory("前处理")
                .expectedByproducts(byproducts)
                .build();

        when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                .thenReturn(Optional.of(task));
        when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                .thenReturn(Optional.of(definition));

        WorkProcessTaskDTO dto = service.getById(FACTORY_ID, TASK_ID);

        assertNotNull(dto.getExpectedByproducts(), "expectedByproducts 应透出非 null");
        assertEquals(1, dto.getExpectedByproducts().size());
        assertEquals("肥油", dto.getExpectedByproducts().get(0).get("name"));
        assertEquals("kg", dto.getExpectedByproducts().get(0).get("unit"));
        assertEquals(true, dto.getExpectedByproducts().get(0).get("defaultEnabled"));
    }

    @Test
    @DisplayName("getById: definition 无 expectedByproducts → DTO 字段 null (向后兼容)")
    void getById_nullExpectedByproductsWhenUnconfigured() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(TASK_ID)
                .factoryId(FACTORY_ID)
                .workProcessId(WP_ID)
                .status(WorkProcessTask.Status.PENDING)
                .build();

        WorkProcess definition = WorkProcess.builder()
                .id(WP_ID)
                .factoryId(FACTORY_ID)
                .processName("熟制")
                .processCategory("加工")
                .build();  // expectedByproducts not set → null

        when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                .thenReturn(Optional.of(task));
        when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                .thenReturn(Optional.of(definition));

        WorkProcessTaskDTO dto = service.getById(FACTORY_ID, TASK_ID);

        assertNull(dto.getExpectedByproducts(), "expectedByproducts null=无预填, 向后兼容不报错");
    }

    @Test
    @DisplayName("getById: definition 有多条副产物 → 全部透出")
    void getById_multipleByproductsAllTransmitted() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(TASK_ID)
                .factoryId(FACTORY_ID)
                .workProcessId(WP_ID)
                .status(WorkProcessTask.Status.PENDING)
                .build();

        List<Map<String, Object>> byproducts = List.of(
                Map.of("name", "油脂", "unit", "kg", "defaultEnabled", true),
                Map.of("name", "碎肉", "unit", "kg", "defaultEnabled", false)
        );

        WorkProcess definition = WorkProcess.builder()
                .id(WP_ID)
                .factoryId(FACTORY_ID)
                .processName("分割")
                .processCategory("加工")
                .expectedByproducts(byproducts)
                .build();

        when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                .thenReturn(Optional.of(task));
        when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                .thenReturn(Optional.of(definition));

        WorkProcessTaskDTO dto = service.getById(FACTORY_ID, TASK_ID);

        assertNotNull(dto.getExpectedByproducts());
        assertEquals(2, dto.getExpectedByproducts().size());
        assertEquals("油脂", dto.getExpectedByproducts().get(0).get("name"));
        assertEquals("碎肉", dto.getExpectedByproducts().get(1).get("name"));
        assertEquals(false, dto.getExpectedByproducts().get(1).get("defaultEnabled"),
                "defaultEnabled=false 也要透出, RN 据此决定是否预选");
    }

    @Test
    @DisplayName("getById: definition 为 null (工序已删除) → DTO expectedByproducts null, 不报 NPE")
    void getById_nullDefinitionNoNpe() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(TASK_ID)
                .factoryId(FACTORY_ID)
                .workProcessId(WP_ID)
                .status(WorkProcessTask.Status.PENDING)
                .build();

        when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                .thenReturn(Optional.of(task));
        when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                .thenReturn(Optional.empty());

        WorkProcessTaskDTO dto = service.getById(FACTORY_ID, TASK_ID);

        assertNull(dto.getExpectedByproducts(), "definition 为 null 时 expectedByproducts 应 null 不抛 NPE");
        assertNull(dto.getProcessName(), "processName 同样应为 null");
    }

    // ==================== M1: spawnTasks 按工序默认责任人写 assignedTo ====================

    @Test
    @DisplayName("spawnTasks: 工序模板有 responsibleWorkerId → 对应任务 assignedTo 携带责任人 id")
    void spawnTasks_setsAssignedToFromTemplate() {
        String productTypeId = "PT-001";
        Long batchId = 999L;
        String wpId1 = "wp-spawn-1";
        String wpId2 = "wp-spawn-2";

        // template1 有责任人 1615, template2 无责任人
        ProductWorkProcess template1 = ProductWorkProcess.builder()
                .id(10L)
                .factoryId(FACTORY_ID)
                .productTypeId(productTypeId)
                .workProcessId(wpId1)
                .processOrder(1)
                .isActive(true)
                .responsibleWorkerId(1615L)
                .build();

        ProductWorkProcess template2 = ProductWorkProcess.builder()
                .id(11L)
                .factoryId(FACTORY_ID)
                .productTypeId(productTypeId)
                .workProcessId(wpId2)
                .processOrder(2)
                .isActive(true)
                .responsibleWorkerId(null)
                .build();

        WorkProcess def1 = WorkProcess.builder()
                .id(wpId1)
                .factoryId(FACTORY_ID)
                .processName("滚揉")
                .build();

        WorkProcess def2 = WorkProcess.builder()
                .id(wpId2)
                .factoryId(FACTORY_ID)
                .processName("包装")
                .build();

        when(taskRepository.existsByFactoryIdAndProductionBatchId(FACTORY_ID, batchId))
                .thenReturn(false);
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, productTypeId))
                .thenReturn(List.of(template1, template2));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any()))
                .thenReturn(List.of(def1, def2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkProcessTask>> captor = ArgumentCaptor.forClass(List.class);
        when(taskRepository.saveAll(captor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        service.spawnTasks(FACTORY_ID, batchId, productTypeId);

        List<WorkProcessTask> saved = captor.getValue();
        assertEquals(2, saved.size(), "应 spawn 2 道工序任务");

        WorkProcessTask task1 = saved.stream()
                .filter(t -> wpId1.equals(t.getWorkProcessId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到 wpId1 的任务"));
        WorkProcessTask task2 = saved.stream()
                .filter(t -> wpId2.equals(t.getWorkProcessId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到 wpId2 的任务"));

        assertEquals(1615L, task1.getAssignedTo(),
                "template1 responsibleWorkerId=1615 → task1.assignedTo 应为 1615");
        assertNull(task2.getAssignedTo(),
                "template2 responsibleWorkerId=null → task2.assignedTo 应为 null");
    }
}
