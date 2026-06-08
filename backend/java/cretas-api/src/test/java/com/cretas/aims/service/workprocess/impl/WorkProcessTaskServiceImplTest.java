package com.cretas.aims.service.workprocess.impl;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.UserRepository;
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

    @Mock
    private UserRepository userRepository;

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
        when(userRepository.findByIdIn(any())).thenReturn(List.of());  // T142: stub name lookup

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

    // ==================== Task 5: listByBatch 按小组长过滤 (M1/M2) ====================

    /** 辅助: 构造一个 WorkProcessTask, 设定 assignedTo. */
    private WorkProcessTask makeTask(Long id, String wpId, Long assignedTo) {
        return WorkProcessTask.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .productionBatchId(200L)
                .workProcessId(wpId)
                .processOrder(id.intValue())
                .status(WorkProcessTask.Status.PENDING)
                .assignedTo(assignedTo)
                .build();
    }

    @Test
    @DisplayName("listByBatch: 有已分配工序时, 按 assignedTo 过滤 (含 null 工序兜底)")
    void listByBatch_filtersToAssignee_whenAnyAssigned() {
        Long batchId = 200L;
        // 三道工序: 1615, 1616, null (未指派)
        WorkProcessTask t1 = makeTask(1L, "wp-a", 1615L);
        WorkProcessTask t2 = makeTask(2L, "wp-b", 1616L);
        WorkProcessTask t3 = makeTask(3L, "wp-c", null);

        when(taskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of(t1, t2, t3));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any()))
                .thenReturn(List.of());  // no definitions needed for filter logic
        when(userRepository.findByIdIn(any())).thenReturn(List.of());  // T142: stub name lookup

        List<WorkProcessTaskDTO> result = service.listByBatch(FACTORY_ID, batchId, 1615L);

        assertEquals(2, result.size(), "应返回 assignedTo=1615 的任务 + assignedTo=null 的任务");
        assertTrue(result.stream().noneMatch(dto -> Long.valueOf(1616L).equals(dto.getAssignedTo())),
                "assignedTo=1616 的任务不应出现在 1615 的视图中");
        assertTrue(result.stream().anyMatch(dto -> dto.getAssignedTo() == null),
                "assignedTo=null 的未指派任务应出现 (M1 全null兜底规则: 未指派工序不锁人)");
        assertTrue(result.stream().anyMatch(dto -> Long.valueOf(1615L).equals(dto.getAssignedTo())),
                "assignedTo=1615 的任务必须在结果中");
    }

    @Test
    @DisplayName("listByBatch: 全部工序 assignedTo=null 时, 无论传入 assignedTo 是谁 → 返回全部 (M1 全null兜底)")
    void listByBatch_returnsAll_whenAllNull() {
        Long batchId = 200L;
        // 三道工序全未指派 (老批次 / 未配置默认人)
        WorkProcessTask t1 = makeTask(1L, "wp-a", null);
        WorkProcessTask t2 = makeTask(2L, "wp-b", null);
        WorkProcessTask t3 = makeTask(3L, "wp-c", null);

        when(taskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of(t1, t2, t3));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any()))
                .thenReturn(List.of());
        // all assignedTo=null → loadAssigneeNames returns early (no ids to query), no stub needed

        // worker1 (userId=1311) 查自己的工序 → 全null应返回全部, 不锁死
        List<WorkProcessTaskDTO> result = service.listByBatch(FACTORY_ID, batchId, 1311L);

        assertEquals(3, result.size(), "全部 assignedTo=null 时, 任意 userId 查询应返回全部工序 (M1 全null兜底)");
    }

    @Test
    @DisplayName("listByBatch: assignedTo=null (主管视图) → 返回全部工序")
    void listByBatch_returnsAll_whenAssignedToNull() {
        Long batchId = 200L;
        // 混合: 部分已指派
        WorkProcessTask t1 = makeTask(1L, "wp-a", 1615L);
        WorkProcessTask t2 = makeTask(2L, "wp-b", 1616L);
        WorkProcessTask t3 = makeTask(3L, "wp-c", null);

        when(taskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of(t1, t2, t3));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any()))
                .thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of());

        // 主管不传 assignedTo (null) → 应看到全部工序
        List<WorkProcessTaskDTO> result = service.listByBatch(FACTORY_ID, batchId, null);

        assertEquals(3, result.size(), "assignedTo=null 表示主管/不过滤, 应返回全部工序");
    }

    // ==================== T142: assignedToName batch join ====================

    @Test
    @DisplayName("T142 listByBatch: assignedTo 有对应 User → assignedToName 透出真实姓名")
    void listByBatch_exposesAssignedToName() {
        Long batchId = 300L;
        WorkProcessTask t1 = makeTask(10L, "wp-x", 1615L);  // 有责任人
        WorkProcessTask t2 = makeTask(11L, "wp-y", null);   // 未指派

        User user1615 = new User();
        user1615.setId(1615L);
        user1615.setFullName("张权");
        user1615.setUsername("zhangquan");

        when(taskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of(t1, t2));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any()))
                .thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(user1615));

        List<WorkProcessTaskDTO> result = service.listByBatch(FACTORY_ID, batchId, null);

        assertEquals(2, result.size());
        WorkProcessTaskDTO dto1 = result.stream()
                .filter(d -> Long.valueOf(1615L).equals(d.getAssignedTo()))
                .findFirst().orElseThrow();
        assertEquals("张权", dto1.getAssignedToName(),
                "assignedTo=1615 → assignedToName 应为 fullName '张权'");

        WorkProcessTaskDTO dto2 = result.stream()
                .filter(d -> d.getAssignedTo() == null)
                .findFirst().orElseThrow();
        assertNull(dto2.getAssignedToName(), "assignedTo=null → assignedToName 应为 null");
    }

    @Test
    @DisplayName("T142 listByBatch: User 无 fullName → 使用 username 作为 assignedToName fallback")
    void listByBatch_fallsBackToUsernameWhenFullNameBlank() {
        Long batchId = 301L;
        WorkProcessTask t1 = makeTask(20L, "wp-z", 1700L);

        User user1700 = new User();
        user1700.setId(1700L);
        user1700.setFullName(null);   // no fullName
        user1700.setUsername("wangfang");

        when(taskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of(t1));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any()))
                .thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(user1700));

        List<WorkProcessTaskDTO> result = service.listByBatch(FACTORY_ID, batchId, null);

        assertEquals(1, result.size());
        assertEquals("wangfang", result.get(0).getAssignedToName(),
                "fullName=null → assignedToName 应 fallback 到 username");
    }
}
