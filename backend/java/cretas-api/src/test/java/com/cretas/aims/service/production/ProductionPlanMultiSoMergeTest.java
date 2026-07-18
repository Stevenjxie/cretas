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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * SP5 多 SO 合并工单 — 建单时 sourceOrderIds 写入路径 + 向后兼容 + 额外 SO 验证.
 *
 * <p>覆盖:
 * <ol>
 *   <li>单 SO 建单(无 sourceOrderIds) → sourceOrderIds 自动填充为 [primarySoId]</li>
 *   <li>多 SO 合并建单 → sourceOrderIds 包含主+追加, 去重</li>
 *   <li>追加 SO 属于另一工厂 → 403 拒绝</li>
 *   <li>追加 SO 未财审 (CONFIRMED) → 409 拒绝</li>
 *   <li>sourceOrderIds 含主 SO 重复 → 去重后仍只有唯一一条</li>
 *   <li>sourceOrderIds 为空时主 SO 自动补填 → backward compat</li>
 * </ol>
 *
 * @since SP5 (2026-06-11)
 */
@DisplayName("SP5 多SO合并工单 — sourceOrderIds 写入路径")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanMultiSoMergeTest {

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_TYPE_ID = "1d7fbd73-8797-4933-83f1-46413a45992d";
    private static final String SO_PRIMARY_ID = "SO-SP5-0001";
    private static final String SO_EXTRA_ID   = "SO-SP5-0002";
    private static final Long SO_ITEM_ID      = 9101L;

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

        // mapper: copy key fields so service normalization can read them back
        lenient().when(productionPlanMapper.toEntity(any(CreateProductionPlanRequest.class), any(), any()))
                .thenAnswer(inv -> {
                    CreateProductionPlanRequest req = inv.getArgument(0);
                    ProductionPlan plan = new ProductionPlan();
                    plan.setId("PP-SP5-001");
                    plan.setFactoryId(FACTORY_ID);
                    plan.setPlanNumber("PP-2026-SP5-001");
                    plan.setProductTypeId(req.getProductTypeId());
                    plan.setPlannedQuantity(req.getPlannedQuantity());
                    plan.setSourceType(req.getSourceType());
                    plan.setSourceOrderId(req.getSourceOrderId());
                    plan.setProcessName(req.getProcessName());
                    // copy sourceOrderIds from request into entity (mapper behaviour)
                    if (req.getSourceOrderIds() != null) {
                        plan.setSourceOrderIds(new java.util.ArrayList<>(req.getSourceOrderIds()));
                    }
                    plan.setStatus(ProductionPlanStatus.PENDING);
                    return plan;
                });
        lenient().when(productionPlanRepository.save(any(ProductionPlan.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productionPlanMapper.toDTO(any(ProductionPlan.class)))
                .thenReturn(new ProductionPlanDTO());
        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());

        // primary SO item
        SalesOrderItem item = new SalesOrderItem();
        item.setId(SO_ITEM_ID);
        item.setSalesOrderId(SO_PRIMARY_ID);
        item.setProductTypeId(PRODUCT_TYPE_ID);
        lenient().when(salesOrderItemRepository.findById(SO_ITEM_ID)).thenReturn(Optional.of(item));
    }

    /** Helper: stub salesOrderRepository.findById for a given SO. */
    private void stubSo(String soId, String factoryId, SalesOrderStatus status) {
        SalesOrder so = new SalesOrder();
        so.setId(soId);
        so.setFactoryId(factoryId);
        so.setCustomerName("SP5 测试客户");
        so.setStatus(status);
        when(salesOrderRepository.findById(soId)).thenReturn(Optional.of(so));
    }

    /** Helper: base request with primary SO. */
    private CreateProductionPlanRequest baseRequest() {
        CreateProductionPlanRequest req = new CreateProductionPlanRequest();
        req.setSourceType(PlanSourceType.CUSTOMER_ORDER);
        req.setProductTypeId(PRODUCT_TYPE_ID);
        req.setPlannedQuantity(new BigDecimal("200"));
        req.setPlannedDate(LocalDate.now());
        req.setBatchDate(LocalDate.now());
        req.setSourceOrderId(SO_PRIMARY_ID);
        req.setSourceOrderItemId(String.valueOf(SO_ITEM_ID));
        req.setProcessName("分切");   // P1-4: CUSTOMER_ORDER 必须有工序名称
        return req;
    }

    private MockedStatic<TransactionSynchronizationManager> mockTxSync() {
        MockedStatic<TransactionSynchronizationManager> mock =
                Mockito.mockStatic(TransactionSynchronizationManager.class);
        mock.when(() -> TransactionSynchronizationManager.registerSynchronization(
                        any(TransactionSynchronization.class)))
                .then(inv -> null);
        return mock;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 1: 单SO(无sourceOrderIds)建单 → 自动填充 [primarySoId]
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("单SO建单(无extraSourceOrderIds) → sourceOrderIds自动补填为[primarySoId]")
    void singleSo_noSourceOrderIds_autofilled() {
        try (MockedStatic<TransactionSynchronizationManager> txSync = mockTxSync()) {
            stubSo(SO_PRIMARY_ID, FACTORY_ID, SalesOrderStatus.FINANCE_APPROVED);
            CreateProductionPlanRequest req = baseRequest();
            // 不传 sourceOrderIds

            // capture the saved plan via the save mock
            ProductionPlan[] captured = new ProductionPlan[1];
            when(productionPlanRepository.save(any(ProductionPlan.class)))
                    .thenAnswer(inv -> {
                        captured[0] = inv.getArgument(0);
                        return captured[0];
                    });

            service.createProductionPlan(FACTORY_ID, req, 1L);

            assertNotNull(captured[0]);
            List<String> ids = captured[0].getSourceOrderIds();
            assertNotNull(ids, "sourceOrderIds 不应为 null");
            assertEquals(1, ids.size(), "单SO建单 sourceOrderIds 应包含1个条目");
            assertEquals(SO_PRIMARY_ID, ids.get(0), "唯一条目应为 primarySoId");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2: 多SO合并建单 → sourceOrderIds 含主+追加
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("多SO合并建单 → sourceOrderIds包含主SO+追加SO,顺序正确")
    void multiSo_merge_sourceOrderIdsPopulated() {
        try (MockedStatic<TransactionSynchronizationManager> txSync = mockTxSync()) {
            stubSo(SO_PRIMARY_ID, FACTORY_ID, SalesOrderStatus.FINANCE_APPROVED);
            stubSo(SO_EXTRA_ID,   FACTORY_ID, SalesOrderStatus.FINANCE_APPROVED);

            CreateProductionPlanRequest req = baseRequest();
            req.setSourceOrderIds(Arrays.asList(SO_PRIMARY_ID, SO_EXTRA_ID));

            ProductionPlan[] captured = new ProductionPlan[1];
            when(productionPlanRepository.save(any(ProductionPlan.class)))
                    .thenAnswer(inv -> {
                        captured[0] = inv.getArgument(0);
                        return captured[0];
                    });

            service.createProductionPlan(FACTORY_ID, req, 1L);

            assertNotNull(captured[0]);
            List<String> ids = captured[0].getSourceOrderIds();
            assertNotNull(ids, "sourceOrderIds 不应为 null");
            assertEquals(2, ids.size(), "应包含主+追加共2条");
            assertEquals(SO_PRIMARY_ID, ids.get(0), "主SO应排第一");
            assertTrue(ids.contains(SO_EXTRA_ID), "追加SO应在列表中");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3: 追加SO属于另一工厂 → 403
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("追加SO属于另一工厂 → 403拒绝")
    void extraSo_wrongFactory_rejected403() {
        stubSo(SO_PRIMARY_ID, FACTORY_ID,     SalesOrderStatus.FINANCE_APPROVED);
        stubSo(SO_EXTRA_ID,   "OTHER_FACTORY", SalesOrderStatus.FINANCE_APPROVED);

        CreateProductionPlanRequest req = baseRequest();
        req.setSourceOrderIds(Arrays.asList(SO_PRIMARY_ID, SO_EXTRA_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createProductionPlan(FACTORY_ID, req, 1L));
        assertEquals(403, ex.getCode().intValue(),
                "跨工厂追加SO应返回403, 实际: " + ex.getCode() + " | " + ex.getMessage());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4: 追加SO未财审(CONFIRMED) → 409
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("追加SO未财审(CONFIRMED) → 409拒绝")
    void extraSo_notFinanceApproved_rejected409() {
        stubSo(SO_PRIMARY_ID, FACTORY_ID, SalesOrderStatus.FINANCE_APPROVED);
        stubSo(SO_EXTRA_ID,   FACTORY_ID, SalesOrderStatus.CONFIRMED);  // ← 未财审

        CreateProductionPlanRequest req = baseRequest();
        req.setSourceOrderIds(Arrays.asList(SO_PRIMARY_ID, SO_EXTRA_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createProductionPlan(FACTORY_ID, req, 1L));
        assertEquals(409, ex.getCode().intValue(),
                "追加未财审SO应返回409, 实际: " + ex.getCode() + " | " + ex.getMessage());
        assertTrue(ex.getMessage().contains("财务审核") || ex.getMessage().contains("财审"),
                "错误信息应提及财务审核: " + ex.getMessage());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 5: sourceOrderIds含主SO重复 → 去重后只有唯一
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("sourceOrderIds含主SO重复 → 去重后sourceOrderIds仅含唯一SO")
    void duplicatePrimarySoInSourceOrderIds_deduplicated() {
        try (MockedStatic<TransactionSynchronizationManager> txSync = mockTxSync()) {
            stubSo(SO_PRIMARY_ID, FACTORY_ID, SalesOrderStatus.FINANCE_APPROVED);

            CreateProductionPlanRequest req = baseRequest();
            // 传入主SO重复两次
            req.setSourceOrderIds(Arrays.asList(SO_PRIMARY_ID, SO_PRIMARY_ID));

            ProductionPlan[] captured = new ProductionPlan[1];
            when(productionPlanRepository.save(any(ProductionPlan.class)))
                    .thenAnswer(inv -> {
                        captured[0] = inv.getArgument(0);
                        return captured[0];
                    });

            service.createProductionPlan(FACTORY_ID, req, 1L);

            assertNotNull(captured[0]);
            List<String> ids = captured[0].getSourceOrderIds();
            assertNotNull(ids);
            assertEquals(1, ids.size(),
                    "重复的主SO应被去重, 期望1条, 实际: " + ids);
            assertEquals(SO_PRIMARY_ID, ids.get(0));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 6: sourceOrderIds为empty list → 主SO自动补填 (backward compat)
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("sourceOrderIds为空列表 → 主SO自动补填(向后兼容)")
    void emptySourceOrderIds_primarySoAutofilled() {
        try (MockedStatic<TransactionSynchronizationManager> txSync = mockTxSync()) {
            stubSo(SO_PRIMARY_ID, FACTORY_ID, SalesOrderStatus.FINANCE_APPROVED);

            CreateProductionPlanRequest req = baseRequest();
            req.setSourceOrderIds(Collections.emptyList());  // 显式传空

            ProductionPlan[] captured = new ProductionPlan[1];
            when(productionPlanRepository.save(any(ProductionPlan.class)))
                    .thenAnswer(inv -> {
                        captured[0] = inv.getArgument(0);
                        return captured[0];
                    });

            service.createProductionPlan(FACTORY_ID, req, 1L);

            assertNotNull(captured[0]);
            List<String> ids = captured[0].getSourceOrderIds();
            assertNotNull(ids, "空列表传入时 sourceOrderIds 仍应补填主SO");
            assertEquals(1, ids.size());
            assertEquals(SO_PRIMARY_ID, ids.get(0));
        }
    }
}
