package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
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
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.production.ProductionPlanMaterialAdvisoryDTO;
import com.cretas.aims.mapper.ProductionPlanMapper;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * PR #289 §B3 (2026-05-10 客户对接) — 开始生产前的原料库存校验单测.
 *
 * <p>测试覆盖:
 * <ul>
 *   <li>库存充足 → 开始成功</li>
 *   <li>单原料库存不足 → BusinessException 列明缺口</li>
 *   <li>多原料缺口 → BusinessException 列出全部</li>
 *   <li>库存恰好等于需求 → 通过 (边界严格不阻断)</li>
 *   <li>无 BOM 配置 → skip 校验, 允许开始</li>
 *   <li>plan.plannedQuantity 为空 → skip 校验, 允许开始</li>
 *   <li>已 IN_PROGRESS 状态 → 状态校验先报错 (库存校验未被触发)</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-05-10
 */
@DisplayName("ProductionPlan B3 开始生产 — 库存校验单测")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanStartValidationTest {

    private static final String FACTORY_ID = "F001";
    private static final String PLAN_ID = "PP-2026-001";
    private static final String PRODUCT_TYPE_ID = "PT-001";

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
                salesOrderRepository, salesOrderItemRepository, bomService);

        // toDTOWithConversionInfo 的依赖默认 stub — 成功 path 才会用上
        ProductionPlanDTO emptyDto = new ProductionPlanDTO();
        lenient().when(productionPlanMapper.toDTO(any(ProductionPlan.class))).thenReturn(emptyDto);
        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
    }

    /** 构造一个最简 PENDING 计划. */
    private ProductionPlan pendingPlan(BigDecimal plannedQuantity) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setProductTypeId(PRODUCT_TYPE_ID);
        plan.setPlanNumber("PP-2026-001");
        plan.setPlannedQuantity(plannedQuantity);
        plan.setStatus(ProductionPlanStatus.PENDING);
        return plan;
    }

    /** 构造一个 BOM item (yieldRate=100% → actualQuantity == standardQuantity). */
    private BomItem bomItem(String materialTypeId, String materialName,
                            BigDecimal standardQuantity, String unit) {
        return BomItem.builder()
                .factoryId(FACTORY_ID)
                .productTypeId(PRODUCT_TYPE_ID)
                .materialTypeId(materialTypeId)
                .materialName(materialName)
                .standardQuantity(standardQuantity)
                .yieldRate(new BigDecimal("100.00"))
                .unit(unit)
                .build();
    }

    @Test
    @DisplayName("库存充足 → 开始成功")
    void startProduction_stockSufficient_succeeds() {
        ProductionPlan plan = pendingPlan(new BigDecimal("100"));
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1 个原料: 单位用量 2, 计划数量 100 → 需求 200; 可用 500 → 充足
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(bomItem("MT-A", "面粉", new BigDecimal("2"), "kg")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY_ID, "MT-A"))
                .thenReturn(new BigDecimal("500"));

        ProductionPlanDTO result = service.startProduction(FACTORY_ID, PLAN_ID);
        assertNotNull(result);
        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus());
        assertNotNull(plan.getStartTime());
    }

    @Test
    @DisplayName("库存恰好等于需求 → 开始成功 (边界)")
    void startProduction_stockExactlyEqual_succeeds() {
        ProductionPlan plan = pendingPlan(new BigDecimal("100"));
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        // 需求 200, 可用 200 → 边界恰好 (不阻断)
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(bomItem("MT-A", "面粉", new BigDecimal("2"), "kg")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY_ID, "MT-A"))
                .thenReturn(new BigDecimal("200"));

        assertDoesNotThrow(() -> service.startProduction(FACTORY_ID, PLAN_ID));
        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus());
    }

    @Test
    @DisplayName("单原料库存不足 → BusinessException 包含原料名 / 需求 / 可用 / 缺口")
    void startProduction_oneMaterialShort_throws() {
        ProductionPlan plan = pendingPlan(new BigDecimal("100"));
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        // 需求 200, 可用 50 → 缺口 150
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(bomItem("MT-A", "面粉", new BigDecimal("2"), "kg")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY_ID, "MT-A"))
                .thenReturn(new BigDecimal("50"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.startProduction(FACTORY_ID, PLAN_ID));
        assertEquals(409, ex.getCode());
        String msg = ex.getMessage();
        assertTrue(msg.contains("原料库存不足"), "应提示原料库存不足: " + msg);
        assertTrue(msg.contains("面粉"), "应包含原料名: " + msg);
        assertTrue(msg.contains("200"), "应包含需求量 200: " + msg);
        assertTrue(msg.contains("50"), "应包含可用量 50: " + msg);
        assertTrue(msg.contains("150"), "应包含缺口 150: " + msg);
        assertTrue(msg.contains("kg"), "应包含单位 kg: " + msg);
        assertEquals("请先采购或调拨原料后再开始生产", ex.getActionHint());
        // 状态未推进
        assertEquals(ProductionPlanStatus.PENDING, plan.getStatus());
    }

    @Test
    @DisplayName("多原料缺口 → BusinessException 列出全部")
    void startProduction_multipleShortages_listsAll() {
        ProductionPlan plan = pendingPlan(new BigDecimal("100"));
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Arrays.asList(
                        bomItem("MT-A", "面粉", new BigDecimal("2"), "kg"),    // 需求 200
                        bomItem("MT-B", "盐", new BigDecimal("0.5"), "kg"),   // 需求 50
                        bomItem("MT-C", "糖", new BigDecimal("1"), "kg")      // 需求 100
                ));
        // MT-A 充足, MT-B/MT-C 缺
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY_ID, "MT-A"))
                .thenReturn(new BigDecimal("300"));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY_ID, "MT-B"))
                .thenReturn(new BigDecimal("10"));   // 缺口 40
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY_ID, "MT-C"))
                .thenReturn(BigDecimal.ZERO);        // 缺口 100

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.startProduction(FACTORY_ID, PLAN_ID));
        String msg = ex.getMessage();
        assertTrue(msg.contains("盐"), "应包含盐: " + msg);
        assertTrue(msg.contains("糖"), "应包含糖: " + msg);
        // MT-A 充足不应出现在错误信息里
        assertTrue(!msg.contains("面粉"), "不应包含充足的面粉: " + msg);
        assertEquals(ProductionPlanStatus.PENDING, plan.getStatus());
    }

    @Test
    @DisplayName("可用库存 null → 视为 0, 触发缺口")
    void startProduction_nullAvailable_treatedAsZero() {
        ProductionPlan plan = pendingPlan(new BigDecimal("100"));
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(bomItem("MT-A", "面粉", new BigDecimal("2"), "kg")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY_ID, "MT-A"))
                .thenReturn(null);  // 仓库无任何批次

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.startProduction(FACTORY_ID, PLAN_ID));
        assertTrue(ex.getMessage().contains("面粉"));
        assertTrue(ex.getMessage().contains("200"));  // 全部需求成为缺口
    }

    @Test
    @DisplayName("无 BOM 配置 → skip 校验, 允许开始")
    void startProduction_noBom_skipsValidation() {
        ProductionPlan plan = pendingPlan(new BigDecimal("100"));
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> service.startProduction(FACTORY_ID, PLAN_ID));
        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus());
    }

    @Test
    @DisplayName("plan.plannedQuantity 为空 → skip 校验, 允许开始")
    void startProduction_nullPlannedQuantity_skipsValidation() {
        ProductionPlan plan = pendingPlan(null);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> service.startProduction(FACTORY_ID, PLAN_ID));
        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus());
    }

    @Test
    @DisplayName("非 PENDING 状态 → 状态校验先报错 (不进入库存校验)")
    void startProduction_alreadyInProgress_statusCheckThrowsFirst() {
        ProductionPlan plan = pendingPlan(new BigDecimal("100"));
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.startProduction(FACTORY_ID, PLAN_ID));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("只能开始待处理"));
    }

    @Test
    @DisplayName("yieldRate 影响 actualQuantity → 需求量随之放大")
    void startProduction_yieldRateScalesRequirement() {
        ProductionPlan plan = pendingPlan(new BigDecimal("100"));
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        // 出成率 50% → actualQuantity = standardQuantity / 0.5 = 4
        // 计划数量 100 → 需求 400
        BomItem item = bomItem("MT-A", "面粉", new BigDecimal("2"), "kg");
        item.setYieldRate(new BigDecimal("50.00"));

        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(item));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY_ID, "MT-A"))
                .thenReturn(new BigDecimal("300"));  // < 400 → 缺口 100

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.startProduction(FACTORY_ID, PLAN_ID));
        String msg = ex.getMessage();
        assertTrue(msg.contains("400"), "yieldRate 50% 把 standardQuantity 2 放大到 actualQuantity 4, 需求 400: " + msg);
    }
    @Test
    @DisplayName("N1c: material advisory reports shortage without changing plan status")
    void materialAdvisory_shortage_returnsWarningWithoutBlocking() {
        ProductionPlan plan = pendingPlan(new BigDecimal("100"));
        when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        when(bomService.getBomItemsByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(bomItem("MT-A", "flour", new BigDecimal("2"), "kg")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY_ID, "MT-A"))
                .thenReturn(new BigDecimal("50"));

        ProductionPlanMaterialAdvisoryDTO advisory = service.getMaterialAdvisory(FACTORY_ID, PLAN_ID);

        assertTrue(advisory.isHasWarning());
        assertEquals(1, advisory.getWarnings().size());
        ProductionPlanMaterialAdvisoryDTO.Item warning = advisory.getWarnings().get(0);
        assertEquals("MT-A", warning.getMaterialTypeId());
        assertTrue(new BigDecimal("200").compareTo(warning.getRequiredQuantity()) == 0);
        assertTrue(new BigDecimal("50").compareTo(warning.getAvailableQuantity()) == 0);
        assertTrue(new BigDecimal("150").compareTo(warning.getShortageQuantity()) == 0);
        assertTrue(advisory.getMessage().contains("flour"));
        assertEquals(ProductionPlanStatus.PENDING, plan.getStatus());
    }
}
