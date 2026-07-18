package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessTaskRepository;
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
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W2 财审闸门 — CUSTOMER_ORDER 源建生产计划必须 SO 已通过财务审核.
 *
 * <p>需求 (转录 C-1): "销售计划→下单→财务审批→流转车间→排产闭环".
 * 修复缺口: 之前从 CUSTOMER_ORDER 源建计划只校验 factoryId, 不查 so.getStatus(),
 * 任何 production:read_write 角色可对未审订单排产, 绕过财务.
 *
 * <p>验证:
 * <ol>
 *   <li>未审 SO (CONFIRMED) 建计划 → 拒绝 (409)</li>
 *   <li>FINANCE_APPROVED SO 建计划 → 放行</li>
 *   <li>非 CUSTOMER_ORDER 源 (SAFETY_STOCK) → 不受影响, 不查 SO 状态</li>
 *   <li>向后兼容路径 (仅传 sourceOrderId) 也强制财审闸门</li>
 *   <li>已进入后续态 (PROCESSING) 的 SO → 放行 (已审过)</li>
 *   <li>FINANCE_REJECTED / PENDING_FINANCE_REVIEW → 拒绝</li>
 * </ol>
 *
 * @since 2026-06-11
 */
@DisplayName("ProductionPlan 财审闸门 — CUSTOMER_ORDER 源必须 SO 财审通过 (W2)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanFinanceGateTest {

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_TYPE_ID = "1d7fbd73-8797-4933-83f1-46413a45992d";
    private static final String SO_ID = "SO-W2-0001";
    private static final Long SO_ITEM_ID = 9001L;

    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private ProcessTaskRepository processTaskRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductionPlanMapper productionPlanMapper;
    @Mock private ConversionRepository conversionRepository;
    @Mock private SchedulingService schedulingService;
    @Mock private ProductionLineRepository productionLineRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private BomService bomService;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository, processTaskRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);

        when(productTypeRepository.existsById(PRODUCT_TYPE_ID)).thenReturn(true);

        lenient().when(productionPlanMapper.toEntity(any(CreateProductionPlanRequest.class), any(), any()))
                .thenAnswer(inv -> {
                    CreateProductionPlanRequest req = inv.getArgument(0);
                    ProductionPlan plan = new ProductionPlan();
                    plan.setId("PP-STUB-001");
                    plan.setFactoryId(FACTORY_ID);
                    plan.setPlanNumber("PP-2026-001");
                    plan.setProductTypeId(req.getProductTypeId());
                    plan.setPlannedQuantity(req.getPlannedQuantity());
                    plan.setSourceType(req.getSourceType());
                    plan.setSourceOrderId(req.getSourceOrderId());
                    plan.setStatus(ProductionPlanStatus.PENDING);
                    return plan;
                });
        lenient().when(productionPlanRepository.save(any(ProductionPlan.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productionPlanMapper.toDTO(any(ProductionPlan.class)))
                .thenReturn(new ProductionPlanDTO());
        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());

        // SO item → SO wiring (status varies per test)
        SalesOrderItem item = new SalesOrderItem();
        item.setId(SO_ITEM_ID);
        item.setSalesOrderId(SO_ID);
        item.setProductTypeId(PRODUCT_TYPE_ID);
        lenient().when(salesOrderItemRepository.findById(SO_ITEM_ID)).thenReturn(Optional.of(item));
    }

    private MockedStatic<TransactionSynchronizationManager> mockTxSync() {
        MockedStatic<TransactionSynchronizationManager> mock =
                Mockito.mockStatic(TransactionSynchronizationManager.class);
        mock.when(() -> TransactionSynchronizationManager.registerSynchronization(any(TransactionSynchronization.class)))
                .then(inv -> null);
        return mock;
    }

    private void stubSalesOrder(SalesOrderStatus status) {
        SalesOrder so = new SalesOrder();
        so.setId(SO_ID);
        so.setFactoryId(FACTORY_ID);
        so.setCustomerName("叮咚好食光");
        so.setStatus(status);
        when(salesOrderRepository.findById(SO_ID)).thenReturn(Optional.of(so));
    }

    private CreateProductionPlanRequest customerOrderRequestByItem() {
        CreateProductionPlanRequest req = new CreateProductionPlanRequest();
        req.setSourceType(PlanSourceType.CUSTOMER_ORDER);
        req.setProductTypeId(PRODUCT_TYPE_ID);
        req.setPlannedQuantity(new BigDecimal("531"));
        req.setPlannedDate(LocalDate.now());
        req.setProcessName("分切");
        req.setBatchDate(LocalDate.now());
        req.setSourceOrderItemId(String.valueOf(SO_ITEM_ID));
        return req;
    }

    // ── Test 1: 未审 SO (CONFIRMED) → 拒绝 ──

    @Test
    @DisplayName("未审 SO (CONFIRMED) 经 sourceOrderItemId 建计划 → 409 拒绝")
    void confirmedSo_byItem_rejected() {
        stubSalesOrder(SalesOrderStatus.CONFIRMED);
        CreateProductionPlanRequest req = customerOrderRequestByItem();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createProductionPlan(FACTORY_ID, req, 1L));
        assertEquals(409, ex.getCode().intValue());
        assertTrue(ex.getMessage().contains("财务审核"),
                "提示应明确未通过财务审核, 实际: " + ex.getMessage());
        // 计划不应被保存
        verify(productionPlanRepository, never()).save(any());
    }

    // ── Test 2: FINANCE_APPROVED → 放行 ──

    @Test
    @DisplayName("FINANCE_APPROVED SO 建计划 → 放行")
    void financeApprovedSo_byItem_passes() {
        try (MockedStatic<TransactionSynchronizationManager> txSync = mockTxSync()) {
            stubSalesOrder(SalesOrderStatus.FINANCE_APPROVED);
            CreateProductionPlanRequest req = customerOrderRequestByItem();

            ProductionPlanDTO result = assertDoesNotThrow(
                    () -> service.createProductionPlan(FACTORY_ID, req, 1L));
            assertNotNull(result);
            verify(productionPlanRepository).save(any());
        }
    }

    // ── Test 3: 非 CUSTOMER_ORDER 源 → 不受影响 ──

    @Test
    @DisplayName("SAFETY_STOCK 源 → 不查 SO 状态, 不受财审闸门影响")
    void safetyStockSource_notAffected() {
        try (MockedStatic<TransactionSynchronizationManager> txSync = mockTxSync()) {
            CreateProductionPlanRequest req = new CreateProductionPlanRequest();
            req.setSourceType(PlanSourceType.SAFETY_STOCK);
            req.setProductTypeId(PRODUCT_TYPE_ID);
            req.setPlannedQuantity(new BigDecimal("246"));
            req.setPlannedDate(LocalDate.now());

            assertDoesNotThrow(() -> service.createProductionPlan(FACTORY_ID, req, 1L));
            verify(salesOrderRepository, never()).findById(any());
        }
    }

    // ── Test 4: 向后兼容路径 (仅 sourceOrderId) 也强制财审 ──

    @Test
    @DisplayName("向后兼容路径 (仅 sourceOrderId, 未审 SO) → 409 拒绝")
    void legacySourceOrderIdPath_unapproved_rejected() {
        stubSalesOrder(SalesOrderStatus.PENDING_FINANCE_REVIEW);
        CreateProductionPlanRequest req = new CreateProductionPlanRequest();
        req.setSourceType(PlanSourceType.CUSTOMER_ORDER);
        req.setProductTypeId(PRODUCT_TYPE_ID);
        req.setPlannedQuantity(new BigDecimal("531"));
        req.setPlannedDate(LocalDate.now());
        req.setProcessName("分切");
        req.setBatchDate(LocalDate.now());
        req.setSourceOrderId(SO_ID); // legacy: 只传 orderId, 无 itemId

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createProductionPlan(FACTORY_ID, req, 1L));
        assertEquals(409, ex.getCode().intValue());
        verify(productionPlanRepository, never()).save(any());
    }

    @Test
    @DisplayName("向后兼容路径 (仅 sourceOrderId, FINANCE_APPROVED) → 放行")
    void legacySourceOrderIdPath_approved_passes() {
        try (MockedStatic<TransactionSynchronizationManager> txSync = mockTxSync()) {
            stubSalesOrder(SalesOrderStatus.FINANCE_APPROVED);
            CreateProductionPlanRequest req = new CreateProductionPlanRequest();
            req.setSourceType(PlanSourceType.CUSTOMER_ORDER);
            req.setProductTypeId(PRODUCT_TYPE_ID);
            req.setPlannedQuantity(new BigDecimal("531"));
            req.setPlannedDate(LocalDate.now());
            req.setProcessName("分切");
            req.setBatchDate(LocalDate.now());
            req.setSourceOrderId(SO_ID);

            assertDoesNotThrow(() -> service.createProductionPlan(FACTORY_ID, req, 1L));
            verify(productionPlanRepository).save(any());
        }
    }

    // ── Test 5: 后续态 (PROCESSING) → 放行 ──

    @Test
    @DisplayName("已进入后续态 (PROCESSING) 的 SO → 放行 (已审过)")
    void processingSo_passes() {
        try (MockedStatic<TransactionSynchronizationManager> txSync = mockTxSync()) {
            stubSalesOrder(SalesOrderStatus.PROCESSING);
            CreateProductionPlanRequest req = customerOrderRequestByItem();

            assertDoesNotThrow(() -> service.createProductionPlan(FACTORY_ID, req, 1L));
            verify(productionPlanRepository).save(any());
        }
    }

    // ── Test 6: FINANCE_REJECTED → 拒绝 ──

    @Test
    @DisplayName("FINANCE_REJECTED SO → 409 拒绝")
    void financeRejectedSo_rejected() {
        stubSalesOrder(SalesOrderStatus.FINANCE_REJECTED);
        CreateProductionPlanRequest req = customerOrderRequestByItem();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createProductionPlan(FACTORY_ID, req, 1L));
        assertEquals(409, ex.getCode().intValue());
        verify(productionPlanRepository, never()).save(any());
    }
}
