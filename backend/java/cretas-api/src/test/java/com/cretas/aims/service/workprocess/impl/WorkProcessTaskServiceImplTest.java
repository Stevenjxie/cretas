package com.cretas.aims.service.workprocess.impl;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Mock
    private ProductionBatchRepository productionBatchRepository;

    @Mock
    private ProductTypeRepository productTypeRepository;

    @Mock
    private com.cretas.aims.repository.ProductionPlanRepository productionPlanRepository;

    @Mock
    private com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeService workflowRuntimeService;

    @InjectMocks
    private WorkProcessTaskServiceImpl service;

    /**
     * productionPlanRepository 是 @Autowired(required=false) 字段 (非 @RequiredArgsConstructor 构造器参数),
     * @InjectMocks 走构造器注入不会填充它 → 手动反射注入 (Fable 审计修复 问题2 的 retry 路径依赖它)。
     */
    @org.junit.jupiter.api.BeforeEach
    void injectFieldDeps() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "productionPlanRepository", productionPlanRepository);
        lenient().when(workflowRuntimeService.materializeIfActive(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(productionBatchRepository.findById(
                org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation -> {
            Long batchId = invocation.getArgument(0);
            return Optional.of(ProductionBatch.builder()
                    .id(batchId)
                    .factoryId(FACTORY_ID)
                    .unit("g")
                    .build());
        });
    }

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



    // ==================== Wave2: 可配置报工粒度 (reportingRequired) ====================




    // ==================== 计划级免工序报工 (六扇门 Wave2 升级 V20261017_01) ====================

    @Test
    @DisplayName("spawnTasks(skip=true): 配了工序但计划选免工序报工 → spawn 2 批次级哨兵任务 (领料+产出), 不 spawn 工序 task")
    void spawnTasks_skipTrue_spawnsTwoBatchLevelTasks() {
        String productTypeId = "PT-SKIP";
        Long batchId = 6001L;

        // 产品配了 3 道工序, 但计划级 skipProcessReporting=true → 应忽略工序模板走两点
        ProductWorkProcess p1 = ProductWorkProcess.builder()
                .id(50L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-a").processOrder(1).isActive(true).reportingRequired(true).build();
        ProductWorkProcess p2 = ProductWorkProcess.builder()
                .id(51L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-b").processOrder(2).isActive(true).reportingRequired(true).build();
        ProductWorkProcess p3 = ProductWorkProcess.builder()
                .id(52L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-c").processOrder(3).isActive(true).reportingRequired(true).build();

        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(FACTORY_ID, batchId, productTypeId)).thenReturn(false);
        lenient().when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, productTypeId))
                .thenReturn(List.of(p1, p2, p3));
        lenient().when(userRepository.findByIdIn(any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkProcessTask>> captor = ArgumentCaptor.forClass(List.class);
        when(taskRepository.saveAll(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.spawnTasks(FACTORY_ID, batchId, productTypeId, Boolean.TRUE, 700L, 800L);

        List<WorkProcessTask> saved = captor.getValue();
        assertEquals(2, saved.size(), "免工序报工 → 恰好 2 批次级任务 (领料+产出), 工序模板被忽略");
        WorkProcessTask material = saved.stream()
                .filter(t -> "__MATERIAL_INPUT__".equals(t.getWorkProcessId())).findFirst().orElse(null);
        WorkProcessTask output = saved.stream()
                .filter(t -> "__FINAL_OUTPUT__".equals(t.getWorkProcessId())).findFirst().orElse(null);
        assertNotNull(material, "必含领料报工哨兵任务");
        assertNotNull(output, "必含产出报工哨兵任务");
        assertEquals(0, material.getProcessOrder(), "领料 processOrder=0 (首)");
        assertEquals(9999, output.getProcessOrder(), "产出 processOrder=9999 (末, 保证 lastStep)");
        assertEquals(0L, material.getProductWorkProcessId(), "哨兵 PWP id = 0 (无模板, 列 NOT NULL 占位)");
        assertEquals(0L, output.getProductWorkProcessId(), "哨兵 PWP id = 0");
        // 不得 spawn 任何真实工序 task
        assertTrue(saved.stream().noneMatch(t ->
                        "wp-a".equals(t.getWorkProcessId()) || "wp-b".equals(t.getWorkProcessId())
                                || "wp-c".equals(t.getWorkProcessId())),
                "免工序报工模式不 spawn 工序 task (工序配置保留供溯源)");
    }




    @Test
    @DisplayName("spawnTasks(skip=true): 头尾责任人分别绑到领料/产出哨兵任务的 assignedTo")
    void spawnTasks_skipTrue_headTailResponsibles() {
        String productTypeId = "PT-RESP";
        Long batchId = 6005L;
        Long headId = 901L;   // 领料责任人
        Long tailId = 902L;   // 产出责任人

        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(FACTORY_ID, batchId, productTypeId)).thenReturn(false);
        lenient().when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, productTypeId))
                .thenReturn(List.of());
        lenient().when(userRepository.findByIdIn(any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkProcessTask>> captor = ArgumentCaptor.forClass(List.class);
        when(taskRepository.saveAll(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.spawnTasks(FACTORY_ID, batchId, productTypeId, Boolean.TRUE, headId, tailId);

        List<WorkProcessTask> saved = captor.getValue();
        WorkProcessTask material = saved.stream()
                .filter(t -> "__MATERIAL_INPUT__".equals(t.getWorkProcessId())).findFirst().orElseThrow();
        WorkProcessTask output = saved.stream()
                .filter(t -> "__FINAL_OUTPUT__".equals(t.getWorkProcessId())).findFirst().orElseThrow();
        assertEquals(headId, material.getAssignedTo(), "领料报工绑头责任人");
        assertEquals(tailId, output.getAssignedTo(), "产出报工绑尾责任人");
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

    // ==================== T157: batchNumber / productTypeName batch join ====================

    /** 辅助: 构造一个带 productTypeId + productionBatchId 的任务 (T157 join 用). */
    private WorkProcessTask makeTaskWithProduct(Long id, String wpId, Long batchId, String productTypeId) {
        return WorkProcessTask.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .productionBatchId(batchId)
                .productTypeId(productTypeId)
                .workProcessId(wpId)
                .processOrder(id.intValue())
                .status(WorkProcessTask.Status.PENDING)
                .build();
    }

    @Test
    @DisplayName("T157 listByBatch: 批次/产品存在 → DTO 透出 batchNumber + productTypeName (batch join, 无 N+1)")
    void listByBatch_exposesBatchNumberAndProductTypeName() {
        Long batchId = 400L;
        WorkProcessTask t1 = makeTaskWithProduct(30L, "wp-a", batchId, "PT-zst");
        WorkProcessTask t2 = makeTaskWithProduct(31L, "wp-b", batchId, "PT-zst");

        ProductionBatch batch = ProductionBatch.builder()
                .id(batchId)
                .batchNumber("ZST-20260608")
                .build();

        ProductType pt = new ProductType();
        pt.setId("PT-zst");
        pt.setName("叮咚猪舌");

        when(taskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of(t1, t2));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any()))
                .thenReturn(List.of());
        when(productionBatchRepository.findByIdIn(any())).thenReturn(List.of(batch));
        when(productTypeRepository.findByIdIn(any())).thenReturn(List.of(pt));

        List<WorkProcessTaskDTO> result = service.listByBatch(FACTORY_ID, batchId, null);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(d -> "ZST-20260608".equals(d.getBatchNumber())),
                "所有任务的 batchNumber 应透出 'ZST-20260608'");
        assertTrue(result.stream().allMatch(d -> "叮咚猪舌".equals(d.getProductTypeName())),
                "所有任务的 productTypeName 应透出 '叮咚猪舌'");
    }

    @Test
    @DisplayName("T157 listByBatch: 批次/产品缺失 (已删除) → batchNumber/productTypeName 为 null (禁假数据), 不 NPE")
    void listByBatch_nullWhenBatchOrProductMissing() {
        Long batchId = 401L;
        WorkProcessTask t1 = makeTaskWithProduct(40L, "wp-a", batchId, "PT-gone");

        when(taskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of(t1));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any()))
                .thenReturn(List.of());
        // batch / product 查不到 (空) → 模拟已删除
        when(productionBatchRepository.findByIdIn(any())).thenReturn(List.of());
        when(productTypeRepository.findByIdIn(any())).thenReturn(List.of());

        List<WorkProcessTaskDTO> result = service.listByBatch(FACTORY_ID, batchId, null);

        assertEquals(1, result.size());
        assertNull(result.get(0).getBatchNumber(), "批次缺失 → batchNumber 应 null (禁假数据)");
        assertNull(result.get(0).getProductTypeName(), "产品缺失 → productTypeName 应 null (禁假数据)");
    }

    @Test
    @DisplayName("T157 listByBatch: 跨产品 (掌中宝+猪舌) → 各自正确 productTypeName, 单次 join 无 N+1")
    void listByBatch_crossProductNamesResolveIndependently() {
        Long batchId = 402L;
        // 同批次理论同产品, 但 list 路径 (跨批次) 才会混产品; 这里用 listByBatch 验 map 解析正确性
        WorkProcessTask t1 = makeTaskWithProduct(50L, "wp-a", batchId, "PT-zzb");
        WorkProcessTask t2 = makeTaskWithProduct(51L, "wp-b", batchId, "PT-zzb");

        ProductionBatch batch = ProductionBatch.builder()
                .id(batchId)
                .batchNumber("ZZB-20260608")
                .build();
        ProductType ptZzb = new ProductType();
        ptZzb.setId("PT-zzb");
        ptZzb.setName("掌中宝");
        // map 里多放一个不相关产品, 验只取匹配的
        ProductType ptOther = new ProductType();
        ptOther.setId("PT-zst");
        ptOther.setName("叮咚猪舌");

        when(taskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of(t1, t2));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any()))
                .thenReturn(List.of());
        when(productionBatchRepository.findByIdIn(any())).thenReturn(List.of(batch));
        when(productTypeRepository.findByIdIn(any())).thenReturn(List.of(ptZzb, ptOther));

        List<WorkProcessTaskDTO> result = service.listByBatch(FACTORY_ID, batchId, null);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(d -> "掌中宝".equals(d.getProductTypeName())),
                "PT-zzb 任务应解析为 '掌中宝', 不被 map 中其他产品干扰");
    }

    // ==================== Fable 审计修复 (问题2): spawnTasksForBatch 尊重计划模式 ====================

    private ProductionBatch batchLinkedToPlan(Long batchId, String productTypeId, String planId) {
        return ProductionBatch.builder()
                .id(batchId)
                .factoryId(FACTORY_ID)
                .productTypeId(productTypeId)
                .productionPlanId(planId)
                .unit("g")
                .build();
    }

    private List<WorkProcess> configuredProcesses(List<String> ids) {
        return ids.stream()
                .map(id -> WorkProcess.builder()
                        .id(id)
                        .factoryId(FACTORY_ID)
                        .unit("g")
                        .outputUnit("g")
                        .build())
                .toList();
    }

    private com.cretas.aims.entity.ProductionPlan plan(String planId, Boolean skip, Long supervisorId) {
        com.cretas.aims.entity.ProductionPlan p = new com.cretas.aims.entity.ProductionPlan();
        p.setId(planId);
        p.setFactoryId(FACTORY_ID);
        p.setSkipProcessReporting(skip);
        p.setAssignedSupervisorId(supervisorId);
        return p;
    }

    @Test
    @DisplayName("spawnTasksForBatch: 计划 skip=true → retry spawn 走两点哨兵 (不退化为逐道)")
    void spawnTasksForBatch_planSkipTrue_spawnsTwoPoint() {
        String productTypeId = "PT-RETRY-SKIP";
        Long batchId = 7001L;
        String planId = "PLAN-RETRY-1";

        // 产品配了工序, 但计划级 skip=true → retry spawn 必须仍走两点 (与计划模式一致)
        ProductWorkProcess p1 = ProductWorkProcess.builder()
                .id(70L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-x").processOrder(1).isActive(true).reportingRequired(true).build();

        when(productionBatchRepository.findById(batchId))
                .thenReturn(Optional.of(batchLinkedToPlan(batchId, productTypeId, planId)));
        when(productionPlanRepository.findById(planId))
                .thenReturn(Optional.of(plan(planId, Boolean.TRUE, 900L)));
        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(FACTORY_ID, batchId, productTypeId)).thenReturn(false);
        lenient().when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, productTypeId))
                .thenReturn(List.of(p1));
        lenient().when(userRepository.findByIdIn(any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkProcessTask>> captor = ArgumentCaptor.forClass(List.class);
        when(taskRepository.saveAll(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.spawnTasksForBatch(FACTORY_ID, batchId, productTypeId);

        List<WorkProcessTask> saved = captor.getValue();
        assertEquals(2, saved.size(), "skip=true 计划 retry spawn → 恰好 2 批次级哨兵任务 (领料+产出)");
        WorkProcessTask material = saved.stream()
                .filter(t -> "__MATERIAL_INPUT__".equals(t.getWorkProcessId())).findFirst().orElseThrow();
        WorkProcessTask output = saved.stream()
                .filter(t -> "__FINAL_OUTPUT__".equals(t.getWorkProcessId())).findFirst().orElseThrow();
        // 头尾责任人 = 计划 assignedSupervisorId (一人兼)
        assertEquals(900L, material.getAssignedTo(), "领料责任人 = 计划主管");
        assertEquals(900L, output.getAssignedTo(), "产出责任人 = 计划主管 (一人兼)");
        assertTrue(saved.stream().noneMatch(t -> "wp-x".equals(t.getWorkProcessId())),
                "skip=true → 不 spawn 工序 task");
    }



    /**
     * 🔴 2026-08-09 (Steve 拍板): 老路(LEGACY)整条下架。
     *
     * <p>本类原有 12 条用例测的是「按 product_work_processes 工序模板逐道 spawn」
     * 与「模板为空退到批次级两点哨兵」—— 那两级回落已经删除, 用例随之删除, 不做保留。
     *
     * <p>替代断言在 ProductProcessWorkflowSpawnCompatibilityTest
     * #noActiveWorkflowFailsClosedInsteadOfFallingBackToTemplates: 画布物化不出任务时
     * 必须 409 WORKFLOW_REQUIRED, 而不是换一套规则继续跑。
     */
    // (被删用例: spawnTasks_allNotRequired_throws422, spawnTasks_missingLegacyUnit_failsClosed, spawnTasksForBatch_noPlanLink_fallsBackPerProcess, spawnTasksForBatch_planSkipFalse_spawnsPerProcess, spawnTasks_allowsAnotherSkuInSameBatch, spawnTasks_legacy3arg_isPerProcess, spawnTasks_middleNotRequired_skipsMiddle, spawnTasks_reportingRequiredDefault_spawnsAll, spawnTasks_setsAssignedToFromTemplate, spawnTasks_skipFalse_withProcesses_isPerProcess, spawnTasks_skipNull_treatedAsFalse, spawnTasks_zeroProcesses_forcesTwoPoint)
}
