package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.BomService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 生产计划「编辑」dead-stub 修复 (2026-07) — {@code PUT /production-plans/{planId}} 服务层
 * 守卫回归测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>PENDING 计划 → 可编辑, plannedDate 改到更近/更远的日期都成功写入 (含改到"今天",
 *       验证 Rule §3 的 @FutureOrPresent 分组切分在 service 层的实际效果, 不只是 DTO 层)。</li>
 *   <li>PREPARED (M-PREP-1 草稿态) 计划 → 同 PENDING 一视同仁可编辑。</li>
 *   <li>IN_PROGRESS 计划 → 409 拒绝 (生产已开始不可编辑)。</li>
 *   <li>已锁定 (isLocked=true) 计划 → 409 拒绝, 即使状态是 PENDING。</li>
 *   <li>跨工厂 → 403 拒绝。</li>
 * </ul>
 *
 * <p>用真实 {@link ProductionPlanMapper} 实例 (非 Mockito mock) — updateEntity 是本次修复
 * 的核心写入路径, 必须真的跑一遍字段拷贝逻辑, 而不是被 mock 空转。
 */
@DisplayName("ProductionPlan PUT /{planId} — 编辑守卫回归 (Issue: 更多→编辑 dead-stub)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanUpdateGuardTest {

    private static final String FACTORY_ID = "F001";
    private static final String OTHER_FACTORY_ID = "F999";
    private static final String PLAN_ID = "PP-2026-EDIT-001";
    private static final String PRODUCT_TYPE_ID = "PT-001";

    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ConversionRepository conversionRepository;
    @Mock private SchedulingService schedulingService;
    @Mock private ProductionLineRepository productionLineRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private BomService bomService;

    // 真实 mapper (不 mock) — updateEntity 的字段拷贝是本测试要验证的核心行为。
    private final ProductionPlanMapper productionPlanMapper = new ProductionPlanMapper();

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);
        when(conversionRepository.findByFactoryIdAndProductTypeId(any(), any())).thenReturn(java.util.List.of());
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ProductionPlan plan(ProductionPlanStatus status, LocalDate plannedDate, boolean locked) {
        ProductionPlan p = new ProductionPlan();
        p.setId(PLAN_ID);
        p.setFactoryId(FACTORY_ID);
        p.setProductTypeId(PRODUCT_TYPE_ID);
        p.setPlanNumber("PP-2026-EDIT-001");
        p.setPlannedQuantity(new BigDecimal("100"));
        p.setPlannedUnit("kg");
        p.setWorkflowSelectionMode(
                com.cretas.aims.entity.ProductionBatch.WorkflowSelectionMode.LEGACY);
        p.setPlannedDate(plannedDate);
        p.setStatus(status);
        p.setSourceType(PlanSourceType.MANUAL);
        p.setIsLocked(locked);
        return p;
    }

    private CreateProductionPlanRequest updateRequest(BigDecimal quantity, LocalDate plannedDate) {
        CreateProductionPlanRequest req = new CreateProductionPlanRequest();
        req.setProductTypeId(PRODUCT_TYPE_ID);
        req.setPlannedQuantity(quantity);
        req.setPlannedDate(plannedDate);
        req.setSourceType(PlanSourceType.MANUAL);
        return req;
    }

    @Test
    @DisplayName("PENDING 计划: plannedDate 改到更远的未来日期 → 成功")
    void update_pending_moveDateLater_succeeds() {
        ProductionPlan existing = plan(ProductionPlanStatus.PENDING, LocalDate.now().plusDays(1), false);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(existing));

        LocalDate newDate = LocalDate.now().plusDays(10);
        ProductionPlanDTO result = service.updateProductionPlan(FACTORY_ID, PLAN_ID,
                updateRequest(new BigDecimal("150"), newDate));

        assertEquals(newDate, result.getPlannedDate());
        assertEquals(0, new BigDecimal("150").compareTo(result.getPlannedQuantity()));
        assertEquals(newDate, existing.getPlannedDate());
    }

    @Test
    @DisplayName("PENDING 计划: plannedDate 改到今天(边界) → 成功 (六扇门软日期约束)")
    void update_pending_moveDateToToday_succeeds() {
        ProductionPlan existing = plan(ProductionPlanStatus.PENDING, LocalDate.now().plusDays(3), false);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(existing));

        LocalDate today = LocalDate.now();
        ProductionPlanDTO result = service.updateProductionPlan(FACTORY_ID, PLAN_ID,
                updateRequest(new BigDecimal("100"), today));

        assertEquals(today, result.getPlannedDate());
    }

    @Test
    @DisplayName("PREPARED (草稿态) 计划: 与 PENDING 一视同仁可编辑")
    void update_prepared_succeeds() {
        ProductionPlan existing = plan(ProductionPlanStatus.PREPARED, LocalDate.now().plusDays(1), false);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(existing));

        LocalDate newDate = LocalDate.now().plusDays(5);
        ProductionPlanDTO result = service.updateProductionPlan(FACTORY_ID, PLAN_ID,
                updateRequest(new BigDecimal("200"), newDate));

        assertEquals(newDate, result.getPlannedDate());
        assertEquals(ProductionPlanStatus.PREPARED, existing.getStatus());
    }

    @Test
    @DisplayName("IN_PROGRESS 计划 → 409 拒绝 (生产已开始不可编辑)")
    void update_inProgress_rejectedWith409() {
        ProductionPlan existing = plan(ProductionPlanStatus.IN_PROGRESS, LocalDate.now(), false);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.updateProductionPlan(FACTORY_ID, PLAN_ID,
                        updateRequest(new BigDecimal("100"), LocalDate.now().plusDays(1))));

        assertEquals(409, ex.getCode());
        assertEquals(ProductionPlanStatus.IN_PROGRESS, existing.getStatus());
    }

    @Test
    @DisplayName("COMPLETED 计划 → 409 拒绝")
    void update_completed_rejectedWith409() {
        ProductionPlan existing = plan(ProductionPlanStatus.COMPLETED, LocalDate.now(), false);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.updateProductionPlan(FACTORY_ID, PLAN_ID,
                        updateRequest(new BigDecimal("100"), LocalDate.now().plusDays(1))));

        assertEquals(409, ex.getCode());
    }

    @Test
    @DisplayName("已锁定的 PENDING 计划 → 409 拒绝 (isLocked=true 优先于状态判断)")
    void update_lockedPendingPlan_rejectedWith409() {
        ProductionPlan existing = plan(ProductionPlanStatus.PENDING, LocalDate.now().plusDays(1), true);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.updateProductionPlan(FACTORY_ID, PLAN_ID,
                        updateRequest(new BigDecimal("100"), LocalDate.now().plusDays(2))));

        assertEquals(409, ex.getCode());
        assertEquals("生产计划已锁定, 不可编辑", ex.getMessage());
    }

    @Test
    @DisplayName("跨工厂编辑 → 403 拒绝")
    void update_wrongFactory_rejectedWith403() {
        ProductionPlan existing = plan(ProductionPlanStatus.PENDING, LocalDate.now().plusDays(1), false);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.updateProductionPlan(OTHER_FACTORY_ID, PLAN_ID,
                        updateRequest(new BigDecimal("100"), LocalDate.now().plusDays(2))));

        assertEquals(403, ex.getCode());
    }
}
