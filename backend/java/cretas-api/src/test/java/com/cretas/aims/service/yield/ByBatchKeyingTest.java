package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.yield.impl.YieldReportServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SP-C Task 3d: by-batch keying 验证。
 *
 * <ul>
 *   <li>by-batch yield: {@link YieldReportService#getBatchYieldByNumber} 解析 batchNumber → 委托 getYield</li>
 *   <li>by-batch cost: {@link OrderCostBreakdownService#computeByBatch} 返回正确成本拆分</li>
 *   <li>跨租户安全: 工厂 A 的批次号用工厂 B 查 → 404 (findByFactoryIdAndBatchNumber factory-scoped)</li>
 *   <li>by-order 回归: extract-method 后 compute(by-order) 行为不变</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ByBatchKeyingTest {

    private static final String FACTORY_A = "FACTORY_A";
    private static final String FACTORY_B = "FACTORY_B";
    private static final String BATCH_NUMBER = "PB-20260622-00001";
    private static final String ORDER_ID = "SO-TEST-001";
    private static final Long BATCH_ID = 42L;

    // ==========================================
    // Section A: YieldReportService.getBatchYieldByNumber
    // Uses @InjectMocks YieldReportServiceImpl with all its mocked deps
    // ==========================================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class YieldByBatchTests {

        @Mock private ProductionReportRepository reportRepo;
        @Mock private com.cretas.aims.repository.workprocess.WorkProcessTaskRepository taskRepo;
        @Mock private com.cretas.aims.repository.WorkProcessRepository processRepo;
        @Mock private YieldCalculationService calcSvc;
        @Mock private com.cretas.aims.service.ProcessingService processingService;
        @Mock private com.cretas.aims.repository.FactorySettingsRepository factorySettingsRepo;
        @Mock private MaterialBatchRepository materialBatchRepo;
        @Mock private com.cretas.aims.repository.ProductTypeRepository productTypeRepository;
        @Mock private ProductionBatchRepository productionBatchRepository;
        @Mock private ProductionPlanRepository productionPlanRepository;
        @Mock private com.cretas.aims.repository.SemiFinishedInventoryRepository wipRepo;
        @Mock private com.cretas.aims.repository.lineage.BatchLineageEdgeRepository lineageEdgeRepo;
        @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
        @Mock private com.cretas.aims.repository.recipe.ProcessMaterialRecipeRepository recipeRepository;
        @Mock private com.cretas.aims.service.wip.WipInventoryService wipInventoryService;
        @Mock private com.cretas.aims.repository.ProductWorkProcessAssigneeRepository pwpAssigneeRepository;
        @Mock private com.cretas.aims.repository.ProductWorkProcessRepository productWorkProcessRepository;

        @InjectMocks
        private YieldReportServiceImpl yieldService;

        @Test
        @DisplayName("by-batch yield: findByFactoryIdAndBatchNumber(factoryId, batchNumber) factory-scoped → getYield called")
        void byBatchYieldResolvesFactoryScopedAndDelegates() {
            ProductionBatch pb = makeBatch(BATCH_ID, BATCH_NUMBER, FACTORY_A);
            BatchYieldDTO expected = simpleYield("500", new BigDecimal("200"));

            // factory-scoped lookup
            when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_A, BATCH_NUMBER))
                    .thenReturn(Optional.of(pb));
            // stub getYield sub-calls so it doesn't NPE
            when(reportRepo.findYieldReportsByBatch(eq(FACTORY_A), eq(BATCH_ID)))
                    .thenReturn(List.of());
            when(calcSvc.calculateBatchYield(any(), any()))
                    .thenReturn(expected);
            when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(eq(FACTORY_A), eq(BATCH_ID)))
                    .thenReturn(List.of());
            when(productionBatchRepository.findByIdAndFactoryId(eq(BATCH_ID), eq(FACTORY_A)))
                    .thenReturn(Optional.of(pb));

            BatchYieldDTO result = yieldService.getBatchYieldByNumber(FACTORY_A, BATCH_NUMBER);

            assertThat(result).isNotNull();
            assertThat(result.getTotalLaborCost()).isEqualByComparingTo("500");
        }

        @Test
        @DisplayName("by-batch yield: 批次不存在 (factory-scoped empty) → BusinessException 404")
        void byBatchYieldBatchNotFoundThrows404() {
            when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_A, "GHOST-BATCH"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> yieldService.getBatchYieldByNumber(FACTORY_A, "GHOST-BATCH"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("GHOST-BATCH");
        }
    }

    // ==========================================
    // Section B: OrderCostBreakdownService computeByBatch / compute / cross-tenant
    // Uses @InjectMocks OrderCostBreakdownService with mocked deps incl. mocked YieldReportService
    // ==========================================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class CostByBatchTests {

        @Mock private ProductionPlanRepository planRepository;
        @Mock private ProductionBatchRepository batchRepository;
        @Mock private MaterialConsumptionRepository consumptionRepository;
        @Mock private MaterialBatchRepository materialBatchRepository;
        @Mock private ProductionReportRepository productionReportRepository;
        @Mock private YieldReportService yieldReportService;

        @InjectMocks
        private OrderCostBreakdownService costService;

        @Test
        @DisplayName("by-batch cost: computeByBatch 返回正确成本拆分 (单源原料 + 人工 + 调料 + 包装 + 盒数/单盒成本)")
        void byBatchCostReturnsCorrectBreakdown() {
            ProductionBatch pb = makeBatch(BATCH_ID, BATCH_NUMBER, FACTORY_A);
            // 3 步: step0=首道(SKIP/原料), step1=调料(中间道→SEASONING), step2=包装(末道→PACKAGING)
            // resolveCostBucket: idx==0→SKIP, 0<idx<last→SEASONING, idx==last→PACKAGING
            BatchYieldDTO yieldDto = simpleYield("500",
                    BigDecimal.ZERO,           // step0 首道 → SKIP
                    new BigDecimal("200"),     // step1 中间 → SEASONING
                    new BigDecimal("300"));    // step2 末道  → PACKAGING

            when(batchRepository.findByFactoryIdAndBatchNumber(FACTORY_A, BATCH_NUMBER))
                    .thenReturn(Optional.of(pb));
            when(yieldReportService.getYield(FACTORY_A, BATCH_ID))
                    .thenReturn(yieldDto);
            when(consumptionRepository.findByProductionBatchIdAndFactoryId(BATCH_ID, FACTORY_A))
                    .thenReturn(List.of(makeConsumption("MB1", "50", "100", "5000")));
            when(materialBatchRepository.findByIdAndFactoryId("MB1", FACTORY_A))
                    .thenReturn(Optional.of(makeMaterialBatch("MB1", "原料批A")));

            OrderCostBreakdownDTO dto = costService.computeByBatch(FACTORY_A, BATCH_NUMBER, false);

            assertThat(dto.isHasData()).isTrue();
            assertThat(dto.getRawMaterialCost()).isEqualByComparingTo("5000");
            assertThat(dto.getSeasoningCost()).isEqualByComparingTo("200");
            assertThat(dto.getPackagingCost()).isEqualByComparingTo("300");
            assertThat(dto.getLaborCost()).isEqualByComparingTo("500");
            assertThat(dto.getTotalCost()).isEqualByComparingTo("6000");  // 5000+200+300+500
            assertThat(dto.getBoxCount()).isEqualTo(100);      // batch.quantity=100
            assertThat(dto.getPerBoxCost()).isEqualByComparingTo("60.00");
            // DTO.orderId 字段填 batchNumber (前端展示 label, by-batch 约定)
            assertThat(dto.getOrderId()).isEqualTo(BATCH_NUMBER);
        }

        @Test
        @DisplayName("跨租户安全: 工厂A的批次号用工厂B查 → 404 (findByFactoryIdAndBatchNumber 不穿租户)")
        void crossTenantBatchQueryReturns404() {
            // FACTORY_B 的 factory-scoped 查询找不到 FACTORY_A 的批次
            when(batchRepository.findByFactoryIdAndBatchNumber(FACTORY_B, BATCH_NUMBER))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> costService.computeByBatch(FACTORY_B, BATCH_NUMBER, false))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(BATCH_NUMBER);
        }

        @Test
        @DisplayName("by-order 回归: extract-method 后 compute(orderId) 行为不变")
        void byOrderRegressionAfterExtractMethod() {
            ProductionBatch pb = makeBatch(BATCH_ID, BATCH_NUMBER, FACTORY_A);
            // 3步: step0=SKIP, step1=SEASONING(中间), step2=PACKAGING(末道)
            BatchYieldDTO yieldDto = simpleYield("300",
                    BigDecimal.ZERO,           // step0 → SKIP
                    new BigDecimal("150"),     // step1 → SEASONING
                    new BigDecimal("100"));    // step2 → PACKAGING

            ProductionPlan plan = new ProductionPlan();
            plan.setId("PP-REGRTEST-001");

            when(planRepository.findByFactoryIdAndSourceOrderId(FACTORY_A, ORDER_ID))
                    .thenReturn(List.of(plan));
            when(batchRepository.findByFactoryIdAndProductionPlanIdIn(eq(FACTORY_A), any()))
                    .thenReturn(List.of(pb));
            when(yieldReportService.getYield(FACTORY_A, BATCH_ID))
                    .thenReturn(yieldDto);
            when(consumptionRepository.findByProductionBatchIdAndFactoryId(BATCH_ID, FACTORY_A))
                    .thenReturn(List.of(makeConsumption("MB2", "30", "80", "2400")));
            when(materialBatchRepository.findByIdAndFactoryId("MB2", FACTORY_A))
                    .thenReturn(Optional.of(makeMaterialBatch("MB2", "原料批B")));

            OrderCostBreakdownDTO dto = costService.compute(FACTORY_A, ORDER_ID, false);

            assertThat(dto.isHasData()).isTrue();
            assertThat(dto.getRawMaterialCost()).isEqualByComparingTo("2400");
            assertThat(dto.getSeasoningCost()).isEqualByComparingTo("150");
            assertThat(dto.getPackagingCost()).isEqualByComparingTo("100");
            assertThat(dto.getLaborCost()).isEqualByComparingTo("300");
            assertThat(dto.getTotalCost()).isEqualByComparingTo("2950");  // 2400+150+100+300
            // orderId 字段仍填 ORDER_ID (by-order 行为不变, 非 batchNumber)
            assertThat(dto.getOrderId()).isEqualTo(ORDER_ID);
        }
    }

    // ==========================================
    // Shared helpers
    // ==========================================

    private static ProductionBatch makeBatch(Long id, String batchNum, String factoryId) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setBatchNumber(batchNum);
        b.setFactoryId(factoryId);
        b.setQuantity(new BigDecimal("100"));
        return b;
    }

    private static BatchYieldDTO simpleYield(String labor, BigDecimal... stepMats) {
        StepYieldDTO[] steps = new StepYieldDTO[stepMats.length];
        for (int i = 0; i < stepMats.length; i++) {
            steps[i] = StepYieldDTO.builder()
                    .processOrder(i + 1)
                    .materialCost(stepMats[i])
                    .build();
        }
        return BatchYieldDTO.builder()
                .totalLaborCost(new BigDecimal(labor))
                .steps(List.of(steps))
                .build();
    }

    private static MaterialConsumption makeConsumption(String mbId, String qty, String unitPrice, String totalCost) {
        MaterialConsumption c = new MaterialConsumption();
        c.setFactoryId(FACTORY_A);
        c.setBatchId(mbId);
        c.setQuantity(new BigDecimal(qty));
        c.setUnitPrice(new BigDecimal(unitPrice));
        c.setTotalCost(new BigDecimal(totalCost));
        return c;
    }

    private static MaterialBatch makeMaterialBatch(String id, String name) {
        MaterialBatch m = new MaterialBatch();
        m.setId(id);
        m.setBatchNumber(name);
        m.setQuantityUnit("kg");
        return m;
    }
}
