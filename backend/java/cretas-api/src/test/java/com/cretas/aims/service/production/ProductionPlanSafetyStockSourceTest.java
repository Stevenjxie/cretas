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
import org.mockito.ArgumentCaptor;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T127 — 存货生产(SAFETY_STOCK) 独立来源验证单测.
 *
 * <p>验证:
 * <ol>
 *   <li>SAFETY_STOCK 来源不绑 SO → 跳过 SO 校验, 创建计划成功, sourceOrderId 为空</li>
 *   <li>SAFETY_STOCK 来源不强制 processName/batchDate (仅 CUSTOMER_ORDER 强制)</li>
 *   <li>SAFETY_STOCK 来源持久化的计划 sourceOrderId 为 null</li>
 *   <li>CUSTOMER_ORDER 来源不传 sourceOrderItemId → 报 400 (回归: SO 校验仍有效)</li>
 * </ol>
 *
 * @author Cretas Team
 * @since 2026-06-07
 */
@DisplayName("ProductionPlan SAFETY_STOCK 来源 — 不绑 SO 且走完整报工链 (T127)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanSafetyStockSourceTest {

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_TYPE_ID = "1d7fbd73-8797-4933-83f1-46413a45992d"; // 掌中宝

    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
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
                productionPlanRepository, productionBatchRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);

        // Product type must exist
        when(productTypeRepository.existsById(PRODUCT_TYPE_ID)).thenReturn(true);

        // Mapper stub: return a minimal plan entity mirroring sourceOrderId from request
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
    }

    /**
     * Mock TransactionSynchronizationManager.registerSynchronization so the test can run
     * outside a real Spring transaction context.
     */
    private MockedStatic<TransactionSynchronizationManager> mockTxSync() {
        MockedStatic<TransactionSynchronizationManager> mock =
                Mockito.mockStatic(TransactionSynchronizationManager.class);
        // Allow registerSynchronization to be called without throwing
        mock.when(() -> TransactionSynchronizationManager.registerSynchronization(any(TransactionSynchronization.class)))
                .then(inv -> null);
        return mock;
    }

    // ── Test 1: SAFETY_STOCK 成功创建, 不触碰 SO 仓库 ──

    @Test
    @DisplayName("SAFETY_STOCK 来源: 无 sourceOrderId → 跳过 SO 校验, 创建成功")
    void safetyStock_noSalesOrder_createsPlanSuccessfully() {
        try (MockedStatic<TransactionSynchronizationManager> txSync = mockTxSync()) {
            CreateProductionPlanRequest req = safetyStockRequest();
            req.setSourceOrderId(null);
            req.setSourceOrderItemId(null);

            ProductionPlanDTO result = assertDoesNotThrow(
                    () -> service.createProductionPlan(FACTORY_ID, req, 1L),
                    "SAFETY_STOCK 来源不应因缺少 SO 而报错");

            assertNotNull(result, "应返回 DTO");

            // SalesOrderRepository 不应被查询
            verify(salesOrderRepository, never()).findById(any());
            verify(salesOrderItemRepository, never()).findById(any());
        }
    }

    // ── Test 2: SAFETY_STOCK 不强制 processName/batchDate ──

    @Test
    @DisplayName("SAFETY_STOCK 来源: 不填 processName 和 batchDate → 不报 400")
    void safetyStock_noProcessNameOrBatchDate_noValidationError() {
        try (MockedStatic<TransactionSynchronizationManager> txSync = mockTxSync()) {
            CreateProductionPlanRequest req = safetyStockRequest();
            req.setProcessName(null);
            req.setBatchDate(null);

            assertDoesNotThrow(
                    () -> service.createProductionPlan(FACTORY_ID, req, 1L),
                    "SAFETY_STOCK 不应强制 processName/batchDate");
        }
    }

    // ── Test 3: SAFETY_STOCK 保存的计划 sourceOrderId 为空 ──

    @Test
    @DisplayName("SAFETY_STOCK 来源: 持久化计划 sourceOrderId 为 null (无 SO 关联)")
    void safetyStock_savedPlan_sourceOrderIdIsNull() {
        try (MockedStatic<TransactionSynchronizationManager> txSync = mockTxSync()) {
            CreateProductionPlanRequest req = safetyStockRequest();

            service.createProductionPlan(FACTORY_ID, req, 1L);

            ArgumentCaptor<ProductionPlan> captor = ArgumentCaptor.forClass(ProductionPlan.class);
            verify(productionPlanRepository).save(captor.capture());
            assertNull(captor.getValue().getSourceOrderId(),
                    "存货生产计划不应有 sourceOrderId");
        }
    }

    // ── Test 4 (回归): CUSTOMER_ORDER 仍要求 sourceOrderItemId ──

    @Test
    @DisplayName("回归: CUSTOMER_ORDER 来源不传 sourceOrderItemId → 400 BusinessException")
    void customerOrder_missingSourceOrderItemId_throws400() {
        // No tx mock needed — exception is thrown before registerSynchronization
        CreateProductionPlanRequest req = safetyStockRequest();
        req.setSourceType(PlanSourceType.CUSTOMER_ORDER);
        req.setSourceOrderId(null);
        req.setSourceOrderItemId(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createProductionPlan(FACTORY_ID, req, 1L),
                "CUSTOMER_ORDER 缺 SO 行 ID 应抛 400");
        assertNotNull(ex.getMessage());
    }

    // ── Test 5 (新): SAFETY_STOCK + null plannedQuantity → 成功 ──

    @Test
    @DisplayName("SAFETY_STOCK 来源: plannedQuantity 为 null → 无需数量, 创建成功")
    void safetyStock_nullPlannedQuantity_succeeds() {
        try (MockedStatic<TransactionSynchronizationManager> txSync = mockTxSync()) {
            CreateProductionPlanRequest req = safetyStockRequest();
            req.setPlannedQuantity(null);

            ProductionPlanDTO result = assertDoesNotThrow(
                    () -> service.createProductionPlan(FACTORY_ID, req, 1L),
                    "存货生产(SAFETY_STOCK) 不传计划数量应成功");
            assertNotNull(result, "应返回 DTO");
        }
    }

    // ── Test 6 (回归): 非 SAFETY_STOCK 且 plannedQuantity 为 null → 400 ──

    @Test
    @DisplayName("回归: MANUAL 来源不传 plannedQuantity → 400 BusinessException '计划数量不能为空'")
    void manualSource_nullPlannedQuantity_throws400() {
        // No tx mock needed — exception is thrown before registerSynchronization
        CreateProductionPlanRequest req = safetyStockRequest();
        req.setSourceType(PlanSourceType.MANUAL);
        req.setPlannedQuantity(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createProductionPlan(FACTORY_ID, req, 1L),
                "非SAFETY_STOCK来源缺少 plannedQuantity 应抛 400");
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("计划数量"),
                "错误消息应含'计划数量', actual: " + ex.getMessage());
    }

    // ── Helper ──

    private CreateProductionPlanRequest safetyStockRequest() {
        CreateProductionPlanRequest req = new CreateProductionPlanRequest();
        req.setSourceType(PlanSourceType.SAFETY_STOCK);
        req.setProductTypeId(PRODUCT_TYPE_ID);
        req.setPlannedQuantity(new BigDecimal("246"));
        req.setPlannedDate(LocalDate.now());
        return req;
    }
}
