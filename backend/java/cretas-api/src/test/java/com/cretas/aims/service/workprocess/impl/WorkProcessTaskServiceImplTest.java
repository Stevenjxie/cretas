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

        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(FACTORY_ID, batchId, productTypeId))
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

    @Test
    @DisplayName("spawnTasks: 同一批次已存在其他 SKU 任务时仍允许当前 SKU spawn")
    void spawnTasks_allowsAnotherSkuInSameBatch() {
        String productTypeId = "PT-MIX-B";
        Long batchId = 999L;
        ProductWorkProcess template = ProductWorkProcess.builder()
                .id(12L)
                .factoryId(FACTORY_ID)
                .productTypeId(productTypeId)
                .workProcessId("wp-mix-b")
                .processOrder(1)
                .isActive(true)
                .reportingRequired(true)
                .build();

        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(
                FACTORY_ID, batchId, productTypeId)).thenReturn(false);
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, productTypeId))
                .thenReturn(List.of(template));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any())).thenReturn(List.of());
        lenient().when(userRepository.findByIdIn(any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkProcessTask>> captor = ArgumentCaptor.forClass(List.class);
        when(taskRepository.saveAll(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.spawnTasks(FACTORY_ID, batchId, productTypeId);

        List<WorkProcessTask> saved = captor.getValue();
        assertEquals(1, saved.size());
        assertEquals(productTypeId, saved.get(0).getProductTypeId());
        verify(taskRepository, never()).existsByFactoryIdAndProductionBatchId(FACTORY_ID, batchId);
    }

    // ==================== Wave2: 可配置报工粒度 (reportingRequired) ====================

    @Test
    @DisplayName("spawnTasks: reportingRequired 默认 (null/true) → 逐道全 spawn (向后兼容回归)")
    void spawnTasks_reportingRequiredDefault_spawnsAll() {
        String productTypeId = "PT-REQ";
        Long batchId = 5001L;

        // template1 reportingRequired=true (显式), template2 null (老配置行, 视为 true)
        ProductWorkProcess t1 = ProductWorkProcess.builder()
                .id(20L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-r1").processOrder(1).isActive(true)
                .reportingRequired(true).build();
        ProductWorkProcess t2 = ProductWorkProcess.builder()
                .id(21L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-r2").processOrder(2).isActive(true)
                .reportingRequired(null).build();   // 老行无字段 → 视为 true

        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(FACTORY_ID, batchId, productTypeId)).thenReturn(false);
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, productTypeId))
                .thenReturn(List.of(t1, t2));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any())).thenReturn(List.of());
        lenient().when(userRepository.findByIdIn(any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkProcessTask>> captor = ArgumentCaptor.forClass(List.class);
        when(taskRepository.saveAll(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.spawnTasks(FACTORY_ID, batchId, productTypeId);

        List<WorkProcessTask> saved = captor.getValue();
        assertEquals(2, saved.size(), "默认/true 全部 spawn (逐道报, 向后兼容)");
    }

    @Test
    @DisplayName("spawnTasks: 六扇门式中间免报 → 只 spawn 领料(首) + 产出(末), 中间 reportingRequired=false 跳过")
    void spawnTasks_middleNotRequired_skipsMiddle() {
        String productTypeId = "PT-LSM";
        Long batchId = 5002L;

        // 5 道: 领料(报) → 分切(免) → 焯水(免) → 气调(免) → 产出(报)
        ProductWorkProcess pickup = ProductWorkProcess.builder()
                .id(30L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-pickup").processOrder(1).isActive(true)
                .reportingRequired(true).build();
        ProductWorkProcess cut = ProductWorkProcess.builder()
                .id(31L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-cut").processOrder(2).isActive(true)
                .reportingRequired(false).build();
        ProductWorkProcess blanch = ProductWorkProcess.builder()
                .id(32L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-blanch").processOrder(3).isActive(true)
                .reportingRequired(false).build();
        ProductWorkProcess pack = ProductWorkProcess.builder()
                .id(33L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-pack").processOrder(4).isActive(true)
                .reportingRequired(false).build();
        ProductWorkProcess output = ProductWorkProcess.builder()
                .id(34L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-output").processOrder(5).isActive(true)
                .reportingRequired(true).build();

        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(FACTORY_ID, batchId, productTypeId)).thenReturn(false);
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, productTypeId))
                .thenReturn(List.of(pickup, cut, blanch, pack, output));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any())).thenReturn(List.of());
        lenient().when(userRepository.findByIdIn(any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkProcessTask>> captor = ArgumentCaptor.forClass(List.class);
        when(taskRepository.saveAll(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.spawnTasks(FACTORY_ID, batchId, productTypeId);

        List<WorkProcessTask> saved = captor.getValue();
        assertEquals(2, saved.size(), "只 spawn 领料 + 产出两道 (中间 3 道免报跳过)");
        assertTrue(saved.stream().anyMatch(t -> "wp-pickup".equals(t.getWorkProcessId())),
                "领料(首道)必须 spawn — 报投入两点之一");
        assertTrue(saved.stream().anyMatch(t -> "wp-output".equals(t.getWorkProcessId())),
                "产出(末道)必须 spawn — 报产出两点之一");
        assertTrue(saved.stream().noneMatch(t ->
                "wp-cut".equals(t.getWorkProcessId())
                        || "wp-blanch".equals(t.getWorkProcessId())
                        || "wp-pack".equals(t.getWorkProcessId())),
                "中间 3 道工序免报 → 不生成报工任务 (配置行仍在, 仅 spawn 跳过)");
    }

    @Test
    @DisplayName("spawnTasks: 全部工序免报 → 422 (诚实拒绝, 提示至少保留领料+产出)")
    void spawnTasks_allNotRequired_throws422() {
        String productTypeId = "PT-ALLOFF";
        Long batchId = 5003L;

        ProductWorkProcess a = ProductWorkProcess.builder()
                .id(40L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-x").processOrder(1).isActive(true)
                .reportingRequired(false).build();
        ProductWorkProcess b = ProductWorkProcess.builder()
                .id(41L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-y").processOrder(2).isActive(true)
                .reportingRequired(false).build();

        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(FACTORY_ID, batchId, productTypeId)).thenReturn(false);
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, productTypeId))
                .thenReturn(List.of(a, b));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any())).thenReturn(List.of());

        com.cretas.aims.exception.BusinessException ex = assertThrows(
                com.cretas.aims.exception.BusinessException.class,
                () -> service.spawnTasks(FACTORY_ID, batchId, productTypeId));
        assertEquals(Integer.valueOf(422), ex.getCode(), "全免报应 422 拒绝, 不静默产出空批次");
    }

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
        when(productWorkProcessRepository
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
    @DisplayName("spawnTasks(skip=false) 但产品 0 工序 → 工序 optional, 强制两点 spawn (不再 422 阻塞)")
    void spawnTasks_zeroProcesses_forcesTwoPoint() {
        String productTypeId = "PT-NOPROC";
        Long batchId = 6002L;

        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(FACTORY_ID, batchId, productTypeId)).thenReturn(false);
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, productTypeId))
                .thenReturn(List.of());   // 产品没配任何工序
        lenient().when(userRepository.findByIdIn(any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkProcessTask>> captor = ArgumentCaptor.forClass(List.class);
        when(taskRepository.saveAll(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // skip=false, 但产品 0 工序 → 不抛 422, 自动走两点 (工序 optional)
        List<WorkProcessTaskDTO> dtos = service.spawnTasks(
                FACTORY_ID, batchId, productTypeId, Boolean.FALSE, null, null);

        List<WorkProcessTask> saved = captor.getValue();
        assertEquals(2, saved.size(), "0 工序产品强制两点 spawn (领料+产出), 不 422");
        assertTrue(saved.stream().anyMatch(t -> "__MATERIAL_INPUT__".equals(t.getWorkProcessId())));
        assertTrue(saved.stream().anyMatch(t -> "__FINAL_OUTPUT__".equals(t.getWorkProcessId())));
        // DTO 友好 processName
        assertTrue(dtos.stream().anyMatch(d -> "领料报工".equals(d.getProcessName())),
                "领料任务 DTO 透出友好名 '领料报工'");
        assertTrue(dtos.stream().anyMatch(d -> "产出报工".equals(d.getProcessName())),
                "产出任务 DTO 透出友好名 '产出报工'");
    }

    @Test
    @DisplayName("spawnTasks(skip=false) 且产品配了工序 → 逐道 spawn (向后兼容零回归, 不出哨兵任务)")
    void spawnTasks_skipFalse_withProcesses_isPerProcess() {
        String productTypeId = "PT-PERPROC";
        Long batchId = 6003L;

        ProductWorkProcess p1 = ProductWorkProcess.builder()
                .id(60L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-1").processOrder(1).isActive(true).reportingRequired(true).build();
        ProductWorkProcess p2 = ProductWorkProcess.builder()
                .id(61L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-2").processOrder(2).isActive(true).reportingRequired(true).build();

        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(FACTORY_ID, batchId, productTypeId)).thenReturn(false);
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, productTypeId))
                .thenReturn(List.of(p1, p2));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any())).thenReturn(List.of());
        lenient().when(userRepository.findByIdIn(any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkProcessTask>> captor = ArgumentCaptor.forClass(List.class);
        when(taskRepository.saveAll(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.spawnTasks(FACTORY_ID, batchId, productTypeId, Boolean.FALSE, null, null);

        List<WorkProcessTask> saved = captor.getValue();
        assertEquals(2, saved.size(), "逐道: 按工序模板 spawn (零回归)");
        assertTrue(saved.stream().anyMatch(t -> "wp-1".equals(t.getWorkProcessId())));
        assertTrue(saved.stream().anyMatch(t -> "wp-2".equals(t.getWorkProcessId())));
        assertTrue(saved.stream().noneMatch(t ->
                        "__MATERIAL_INPUT__".equals(t.getWorkProcessId())
                                || "__FINAL_OUTPUT__".equals(t.getWorkProcessId())),
                "逐道模式不得出现批次级哨兵任务");
    }

    @Test
    @DisplayName("spawnTasks(3-arg legacy): 旧入口 → skip=false 逐道 (现有 controller/Tool 调用零回归)")
    void spawnTasks_legacy3arg_isPerProcess() {
        String productTypeId = "PT-LEGACY3";
        Long batchId = 6004L;

        ProductWorkProcess p1 = ProductWorkProcess.builder()
                .id(70L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-l1").processOrder(1).isActive(true).reportingRequired(true).build();

        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(FACTORY_ID, batchId, productTypeId)).thenReturn(false);
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, productTypeId))
                .thenReturn(List.of(p1));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any())).thenReturn(List.of());
        lenient().when(userRepository.findByIdIn(any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkProcessTask>> captor = ArgumentCaptor.forClass(List.class);
        when(taskRepository.saveAll(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.spawnTasks(FACTORY_ID, batchId, productTypeId);   // 旧 3-arg 入口

        List<WorkProcessTask> saved = captor.getValue();
        assertEquals(1, saved.size(), "旧 3-arg 委托 skip=false → 逐道");
        assertEquals("wp-l1", saved.get(0).getWorkProcessId());
    }

    @Test
    @DisplayName("spawnTasks(skip=true): 头尾责任人分别绑到领料/产出哨兵任务的 assignedTo")
    void spawnTasks_skipTrue_headTailResponsibles() {
        String productTypeId = "PT-RESP";
        Long batchId = 6005L;
        Long headId = 901L;   // 领料责任人
        Long tailId = 902L;   // 产出责任人

        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(FACTORY_ID, batchId, productTypeId)).thenReturn(false);
        when(productWorkProcessRepository
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

    @Test
    @DisplayName("spawnTasks(skip=null): null 视为 false → 逐道 (向后兼容)")
    void spawnTasks_skipNull_treatedAsFalse() {
        String productTypeId = "PT-NULLSKIP";
        Long batchId = 6006L;

        ProductWorkProcess p1 = ProductWorkProcess.builder()
                .id(80L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-n1").processOrder(1).isActive(true).reportingRequired(true).build();

        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(FACTORY_ID, batchId, productTypeId)).thenReturn(false);
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, productTypeId))
                .thenReturn(List.of(p1));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any())).thenReturn(List.of());
        lenient().when(userRepository.findByIdIn(any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkProcessTask>> captor = ArgumentCaptor.forClass(List.class);
        when(taskRepository.saveAll(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.spawnTasks(FACTORY_ID, batchId, productTypeId, null, null, null);

        List<WorkProcessTask> saved = captor.getValue();
        assertEquals(1, saved.size(), "skip=null 视为 false → 逐道");
        assertEquals("wp-n1", saved.get(0).getWorkProcessId());
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
                .build();
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

    @Test
    @DisplayName("spawnTasksForBatch: 计划 skip=false → retry spawn 走逐道 (其他工厂模式不被误伤)")
    void spawnTasksForBatch_planSkipFalse_spawnsPerProcess() {
        String productTypeId = "PT-RETRY-PERPROC";
        Long batchId = 7002L;
        String planId = "PLAN-RETRY-2";

        ProductWorkProcess p1 = ProductWorkProcess.builder()
                .id(71L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-y").processOrder(1).isActive(true).reportingRequired(true).build();
        ProductWorkProcess p2 = ProductWorkProcess.builder()
                .id(72L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-z").processOrder(2).isActive(true).reportingRequired(true).build();

        when(productionBatchRepository.findById(batchId))
                .thenReturn(Optional.of(batchLinkedToPlan(batchId, productTypeId, planId)));
        when(productionPlanRepository.findById(planId))
                .thenReturn(Optional.of(plan(planId, Boolean.FALSE, 901L)));
        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(FACTORY_ID, batchId, productTypeId)).thenReturn(false);
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, productTypeId))
                .thenReturn(List.of(p1, p2));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any())).thenReturn(List.of());
        lenient().when(userRepository.findByIdIn(any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkProcessTask>> captor = ArgumentCaptor.forClass(List.class);
        when(taskRepository.saveAll(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.spawnTasksForBatch(FACTORY_ID, batchId, productTypeId);

        List<WorkProcessTask> saved = captor.getValue();
        assertTrue(saved.stream().noneMatch(t ->
                        "__MATERIAL_INPUT__".equals(t.getWorkProcessId())
                                || "__FINAL_OUTPUT__".equals(t.getWorkProcessId())),
                "skip=false 计划 retry spawn → 逐道, 不出哨兵任务");
        assertTrue(saved.stream().anyMatch(t -> "wp-y".equals(t.getWorkProcessId())), "逐道 spawn 工序 wp-y");
        assertTrue(saved.stream().anyMatch(t -> "wp-z".equals(t.getWorkProcessId())), "逐道 spawn 工序 wp-z");
    }

    @Test
    @DisplayName("spawnTasksForBatch: 批次无关联计划 → 兜底逐道 (安全默认, 不误判两点)")
    void spawnTasksForBatch_noPlanLink_fallsBackPerProcess() {
        String productTypeId = "PT-RETRY-NOPLAN";
        Long batchId = 7003L;

        ProductWorkProcess p1 = ProductWorkProcess.builder()
                .id(73L).factoryId(FACTORY_ID).productTypeId(productTypeId)
                .workProcessId("wp-q").processOrder(1).isActive(true).reportingRequired(true).build();

        // 批次无 productionPlanId
        when(productionBatchRepository.findById(batchId))
                .thenReturn(Optional.of(batchLinkedToPlan(batchId, productTypeId, null)));
        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(FACTORY_ID, batchId, productTypeId)).thenReturn(false);
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, productTypeId))
                .thenReturn(List.of(p1));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq(FACTORY_ID), any())).thenReturn(List.of());
        lenient().when(userRepository.findByIdIn(any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkProcessTask>> captor = ArgumentCaptor.forClass(List.class);
        when(taskRepository.saveAll(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.spawnTasksForBatch(FACTORY_ID, batchId, productTypeId);

        List<WorkProcessTask> saved = captor.getValue();
        assertTrue(saved.stream().anyMatch(t -> "wp-q".equals(t.getWorkProcessId())),
                "无计划关联 → 兜底逐道 spawn 工序");
        assertTrue(saved.stream().noneMatch(t -> "__MATERIAL_INPUT__".equals(t.getWorkProcessId())),
                "无计划关联不误判两点");
    }
}
