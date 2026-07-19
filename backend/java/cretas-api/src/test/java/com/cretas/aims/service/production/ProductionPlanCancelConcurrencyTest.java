package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 六扇门生产计划取消级联 + 并发守卫 (R1/R2/R6).
 *
 * <p>R1 — 取消计划必须按"批次"定向级联关任务, 不能按"产品类型"全关
 *   (同产品并行两批, 取消其一不能误关另一活跃批次的任务). 且级联的是
 *   新表 {@link WorkProcessTask} (CANCELLED), 不只旧表 ProcessTask.
 *
 * <p>R2 — IN_PROGRESS 且已有报工/已消耗 WIP 的计划禁止直接取消 (导向撤回审批流);
 *   IN_PROGRESS 无报工无 WIP (空批次) 与 PENDING 可安全直接取消.
 *
 * <p>R6 — createBatchFromPlan / startProduction 必须用悲观锁取计划串行化,
 *   并发双击不再双建批次 (第二个看到非 PENDING → 409).
 *
 * @since 2026-06-14
 */
@DisplayName("R1/R2/R6: 生产计划取消级联 + 并发守卫")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanCancelConcurrencyTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PP-2026-200";
    private static final String OTHER_PLAN_ID = "PP-2026-201";
    private static final String PRODUCT_TYPE_ID = "PT-PIG-TONGUE";

    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductionPlanMapper productionPlanMapper;
    @Mock private ConversionRepository conversionRepository;
    @Mock private SchedulingService schedulingService;
    @Mock private com.cretas.aims.repository.ProductionLineRepository productionLineRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private BomRecipeItemRepository bomRecipeItemRepository;
    @Mock private com.cretas.aims.service.workprocess.WorkProcessTaskService workProcessTaskService;

    // Field-injected (@Autowired(required=false)) — reflection-set per existing pattern.
    @Mock private WorkProcessTaskRepository workProcessTaskRepository;
    @Mock private ProductionReportRepository productionReportRepository;
    @Mock private com.cretas.aims.service.wip.WipInventoryService wipInventoryService;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);
        ReflectionTestUtils.setField(service, "bomRecipeItemRepository", bomRecipeItemRepository);
        ReflectionTestUtils.setField(service, "workProcessTaskRepository", workProcessTaskRepository);
        ReflectionTestUtils.setField(service, "productionReportRepository", productionReportRepository);
        ReflectionTestUtils.setField(service, "wipInventoryService", wipInventoryService);
        ReflectionTestUtils.setField(service, "workProcessTaskService", workProcessTaskService);

        lenient().when(productionPlanRepository.save(any(ProductionPlan.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productionBatchRepository.save(any(ProductionBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(workProcessTaskRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productTypeRepository.findById(PRODUCT_TYPE_ID)).thenReturn(Optional.empty());
        lenient().when(productionBatchRepository.existsByFactoryIdAndBatchNumber(any(), any()))
                .thenReturn(false);
    }

    private ProductionPlan plan(String id, ProductionPlanStatus status) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(FACTORY_ID);
        p.setProductTypeId(PRODUCT_TYPE_ID);
        p.setPlanNumber(id);
        p.setPlannedQuantity(new BigDecimal("100"));
        p.setStatus(status);
        return p;
    }

    private ProductionBatch batch(Long id, String planId, ProductionBatchStatus status) {
        ProductionBatch b = ProductionBatch.builder()
                .factoryId(FACTORY_ID)
                .batchNumber("PB-" + planId + "-" + id)
                .productionPlanId(planId)
                .productTypeId(PRODUCT_TYPE_ID)
                .status(status)
                .build();
        b.setId(id);
        return b;
    }

    private WorkProcessTask task(Long id, Long batchId, WorkProcessTask.Status status) {
        WorkProcessTask t = new WorkProcessTask();
        t.setId(id);
        t.setFactoryId(FACTORY_ID);
        t.setProductionBatchId(batchId);
        t.setProductTypeId(PRODUCT_TYPE_ID);
        t.setStatus(status);
        return t;
    }

    // ============================ R1 ============================

    @Test
    @DisplayName("R1: 取消 PENDING 计划只关该计划批次的 WorkProcessTask, 不碰同产品另一活跃批次")
    void cancel_cascadesByBatch_notByProductType() {
        // 本计划 PENDING (无批次场景下用 PENDING 直接取消路径); 但为验证级联范围,
        // 给本计划一个已建批次 (batch 10), 另一计划同产品有活跃批次 (batch 20).
        ProductionPlan target = plan(PLAN_ID, ProductionPlanStatus.PENDING);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(target));

        ProductionBatch myBatch = batch(10L, PLAN_ID, ProductionBatchStatus.IN_PROGRESS);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(myBatch));

        // 本批次的活跃任务
        WorkProcessTask myTask = task(101L, 10L, WorkProcessTask.Status.IN_PROGRESS);
        when(workProcessTaskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, 10L))
                .thenReturn(new ArrayList<>(List.of(myTask)));
        // 另一批次 (batch 20) 的任务绝不应被查询/关闭 — 不 stub, 用 verify never 保证.

        service.cancelProductionPlan(FACTORY_ID, PLAN_ID, "订单取消");

        assertEquals(ProductionPlanStatus.CANCELLED, target.getStatus());
        // 本批次任务被关 (CANCELLED)
        assertEquals(WorkProcessTask.Status.CANCELLED, myTask.getStatus());
        verify(workProcessTaskRepository, times(1))
                .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, 10L);
        // ⚠️ 另一批次 (20) 的任务从未被查询 → 不会被误关
        verify(workProcessTaskRepository, never())
                .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, 20L);
    }

    @Test
    @DisplayName("R1: 终态任务 (COMPLETED/SKIPPED/CANCELLED) 不被重置")
    void cancel_doesNotTouchTerminalTasks() {
        ProductionPlan target = plan(PLAN_ID, ProductionPlanStatus.PENDING);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(target));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(batch(10L, PLAN_ID, ProductionBatchStatus.IN_PROGRESS)));

        WorkProcessTask done = task(101L, 10L, WorkProcessTask.Status.COMPLETED);
        WorkProcessTask pending = task(102L, 10L, WorkProcessTask.Status.PENDING);
        when(workProcessTaskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, 10L))
                .thenReturn(new ArrayList<>(List.of(done, pending)));

        service.cancelProductionPlan(FACTORY_ID, PLAN_ID, "订单取消");

        assertEquals(WorkProcessTask.Status.COMPLETED, done.getStatus(), "终态 COMPLETED 不变");
        assertEquals(WorkProcessTask.Status.CANCELLED, pending.getStatus(), "活跃 PENDING → CANCELLED");
    }

    // ============================ R2 ============================

    @Test
    @DisplayName("R2: IN_PROGRESS 且有 YIELD 报工 → 拒绝直接取消, 导向撤回审批 (4 位一体 message)")
    void cancel_inProgressWithYield_rejectedToApproval() {
        ProductionPlan target = plan(PLAN_ID, ProductionPlanStatus.IN_PROGRESS);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(target));

        ProductionBatch myBatch = batch(10L, PLAN_ID, ProductionBatchStatus.IN_PROGRESS);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(myBatch));
        ProductionReport yield = new ProductionReport();
        yield.setId(900L);
        yield.setBatchId(10L);
        when(productionReportRepository.findYieldReportsByBatch(FACTORY_ID, 10L))
                .thenReturn(List.of(yield));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancelProductionPlan(FACTORY_ID, PLAN_ID, "订单取消"));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("报工") || ex.getMessage().contains("开工"),
                "message 具体说明已有报工/已开工: " + ex.getMessage());
        // next action 提示导向撤回审批
        assertTrue((ex.getActionHint() != null && ex.getActionHint().contains("撤回"))
                        || ex.getMessage().contains("撤回"),
                "含 next action 撤回审批: msg=" + ex.getMessage() + " hint=" + ex.getActionHint());

        // 计划状态不变 (未被置 CANCELLED), 不级联关任务
        assertEquals(ProductionPlanStatus.IN_PROGRESS, target.getStatus());
        verify(workProcessTaskRepository, never())
                .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(any(), any());
    }

    @Test
    @DisplayName("R2: IN_PROGRESS 但无报工无 WIP (空批次) → 安全直接取消, 批次置 CANCELLED + 复位任务")
    void cancel_inProgressEmptyBatch_safeDirectCancel() {
        ProductionPlan target = plan(PLAN_ID, ProductionPlanStatus.IN_PROGRESS);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(target));

        ProductionBatch myBatch = batch(10L, PLAN_ID, ProductionBatchStatus.IN_PROGRESS);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(myBatch));
        // 无 YIELD 报工
        when(productionReportRepository.findYieldReportsByBatch(FACTORY_ID, 10L))
                .thenReturn(Collections.emptyList());

        WorkProcessTask t = task(101L, 10L, WorkProcessTask.Status.IN_PROGRESS);
        when(workProcessTaskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, 10L))
                .thenReturn(new ArrayList<>(List.of(t)));

        service.cancelProductionPlan(FACTORY_ID, PLAN_ID, "订单取消");

        assertEquals(ProductionPlanStatus.CANCELLED, target.getStatus());
        // 空 IN_PROGRESS 批次被置 CANCELLED
        assertEquals(ProductionBatchStatus.CANCELLED, myBatch.getStatus());
        verify(productionBatchRepository, times(1)).save(myBatch);
        // 任务被级联 CANCELLED
        assertEquals(WorkProcessTask.Status.CANCELLED, t.getStatus());
    }

    @Test
    @DisplayName("修1: SECONDARY 计划 IN_PROGRESS 已扣 WIP 但无报工 → 反冲 WIP + 直接取消 (修正旧死路)")
    void cancel_secondaryInProgressConsumedWipNoYield_reversesAndCancels() {
        // 旧行为 (已被修正): 拒绝 + 导向报工撤回 → 死路 (撤回 no-op, WIP 永不还, 计划卡 IN_PROGRESS)。
        // 修1 新行为: 只开工扣 WIP 无报工 → 反冲还回 WIP + 直接置 CANCELLED, 不导向报工撤回。
        ProductionPlan target = plan(PLAN_ID, ProductionPlanStatus.IN_PROGRESS); // plannedQuantity=100
        target.setPlanSourceType("SECONDARY");
        target.setSecondarySourceWipId(555L);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(target));
        // 无批次 / 无 YIELD 报工 (只开工扣了 WIP)
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(Collections.emptyList());

        service.cancelProductionPlan(FACTORY_ID, PLAN_ID, "订单取消");

        // 反冲还回开工扣的 WIP (plannedQuantity=100)
        verify(wipInventoryService).reverseSecondaryDeduct(
                eq(555L), eq(new BigDecimal("100")), eq(FACTORY_ID), any());
        // 计划置 CANCELLED, 不卡 IN_PROGRESS
        assertEquals(ProductionPlanStatus.CANCELLED, target.getStatus());
    }

    @Test
    @DisplayName("修1: SECONDARY 计划 IN_PROGRESS 已有 YIELD 报工 → 仍拒绝直接取消, 导向报工撤回 (不反冲)")
    void cancel_secondaryInProgressWithYield_rejectedNoReverse() {
        ProductionPlan target = plan(PLAN_ID, ProductionPlanStatus.IN_PROGRESS);
        target.setPlanSourceType("SECONDARY");
        target.setSecondarySourceWipId(555L);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(target));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(batch(10L, PLAN_ID, ProductionBatchStatus.IN_PROGRESS)));
        ProductionReport yield = new ProductionReport();
        yield.setId(901L);
        yield.setBatchId(10L);
        when(productionReportRepository.findYieldReportsByBatch(FACTORY_ID, 10L))
                .thenReturn(List.of(yield));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancelProductionPlan(FACTORY_ID, PLAN_ID, "订单取消"));
        assertEquals(409, ex.getCode());
        assertEquals(ProductionPlanStatus.IN_PROGRESS, target.getStatus());
        // 有报工 → 走报工撤回流, 不反冲
        verify(wipInventoryService, never()).reverseSecondaryDeduct(any(), any(), any(), any());
    }

    @Test
    @DisplayName("requestCancelWithApproval 已废弃 — IN_PROGRESS 有数据也抛 409 USE_BATCH_REVERSAL 导向批次整单撤回")
    void requestCancel_inProgressWithData_deprecatedThrows() {
        // 方案 B (2026-06-14): 计划级 PRODUCTION_REVERSAL 死路已废弃, requestCancelWithApproval
        // 一律抛 409 USE_BATCH_REVERSAL 引导改走批次级整单撤回 (ReportReversalService)。
        // IN_PROGRESS+数据的取消已由 cancelProductionPlan 导向报工撤回流。
        ProductionPlan target = plan(PLAN_ID, ProductionPlanStatus.IN_PROGRESS);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(target));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requestCancelWithApproval(FACTORY_ID, PLAN_ID, "撤回", 7L));

        assertEquals(409, ex.getCode());
        assertEquals("USE_BATCH_REVERSAL", ex.getErrorCode());
        // 状态不变 — 不再进 PENDING_APPROVAL, 不启动死路 workflow
        assertEquals(ProductionPlanStatus.IN_PROGRESS, target.getStatus());
    }

    @Test
    @DisplayName("R2: requestCancelWithApproval 对 IN_PROGRESS 无数据计划拒绝 (无东西可撤回)")
    void requestCancel_inProgressNoData_rejected() {
        ProductionPlan target = plan(PLAN_ID, ProductionPlanStatus.IN_PROGRESS);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(target));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(Collections.emptyList());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requestCancelWithApproval(FACTORY_ID, PLAN_ID, "撤回", 7L));
        assertEquals(409, ex.getCode());
    }

    // ============================ R6 ============================

    @Test
    @DisplayName("R6: createBatchFromPlan 用悲观锁取计划 (findByIdForUpdate), 不用普通 findById")
    void createBatch_usesPessimisticLock() {
        ProductionPlan target = plan(PLAN_ID, ProductionPlanStatus.PENDING);
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(target));
        when(productionBatchRepository.save(any(ProductionBatch.class)))
                .thenAnswer(inv -> {
                    ProductionBatch b = inv.getArgument(0);
                    if (b.getId() == null) b.setId(1L);
                    return b;
                });
        when(bomRecipeItemRepository.findCurrentByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Collections.emptyList());

        service.createBatchFromPlan(FACTORY_ID, PLAN_ID);

        verify(productionPlanRepository, times(1)).findByIdForUpdate(PLAN_ID);
        verify(productionPlanRepository, never()).findById(PLAN_ID);
        assertEquals(ProductionPlanStatus.IN_PROGRESS, target.getStatus());
    }

    @Test
    @DisplayName("R6: 并发第二个 createBatchFromPlan — 计划已 IN_PROGRESS → 409, 不再建第二个批次")
    void createBatch_concurrentSecond_rejectsNoSecondBatch() {
        // 第二个请求拿锁后看到的计划已是 IN_PROGRESS
        ProductionPlan alreadyStarted = plan(PLAN_ID, ProductionPlanStatus.IN_PROGRESS);
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID))
                .thenReturn(Optional.of(alreadyStarted));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createBatchFromPlan(FACTORY_ID, PLAN_ID));
        assertEquals(409, ex.getCode());
        verify(productionBatchRepository, never()).save(any(ProductionBatch.class));
    }

    @Test
    @DisplayName("R6: startProduction 用悲观锁取计划; 并发第二个 → 409 不重复推进")
    void startProduction_concurrentSecond_rejected() {
        ProductionPlan alreadyStarted = plan(PLAN_ID, ProductionPlanStatus.IN_PROGRESS);
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID))
                .thenReturn(Optional.of(alreadyStarted));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.startProduction(FACTORY_ID, PLAN_ID));
        assertEquals(409, ex.getCode());
        // 普通 findById 不再被用于取计划状态判定
        verify(productionPlanRepository, never()).findById(PLAN_ID);
    }

    @Test
    @DisplayName("R6: startProduction 正常 PENDING → IN_PROGRESS (锁路径不破坏 happy path)")
    void startProduction_pending_happyPath() {
        ProductionPlan target = plan(PLAN_ID, ProductionPlanStatus.PENDING);
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(target));
        when(bomRecipeItemRepository.findCurrentByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Collections.emptyList());
        // toDTOWithConversionInfo 需要 mapper 返回非 null DTO (happy path 走到返回值)
        when(productionPlanMapper.toDTO(any(ProductionPlan.class)))
                .thenReturn(new com.cretas.aims.dto.production.ProductionPlanDTO());

        service.startProduction(FACTORY_ID, PLAN_ID);

        assertEquals(ProductionPlanStatus.IN_PROGRESS, target.getStatus());
        assertNotNull(target.getStartTime());
        verify(productionPlanRepository, atMostOnce()).findByIdForUpdate(PLAN_ID);
    }
}
