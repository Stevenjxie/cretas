package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowChangeLogRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * F006 双出成率: ProcessSheetService.getInventoryYieldCard 单元测试 (Mockito, 无 Spring 上下文)。
 *
 * <p>测试目标:
 * <ol>
 *   <li>线性链三道: 拆包100→修油90→焯水80; stepYield 焯水=88.89%, cumulative=80%</li>
 *   <li>首道 step/cumulative 相等 (都 = 首道产出/首道原料投入)</li>
 *   <li>跨单位无 gramsPerUnit → cumulativeYieldRate=null</li>
 *   <li>首道 inputQuantity=0 → stepYieldRate=null, cumulativeYieldRate=null</li>
 *   <li>无生产批次 → 空列表</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProcessSheetYieldCardTest - F006 双出成率 getInventoryYieldCard")
class ProcessSheetYieldCardTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN-TEST-001";
    private static final long   BATCH_ID = 42L;

    @Mock private ClerkProcessEntryService clerkService;
    @Mock private ProcessSheetRowRepository rowRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;
    @Mock private MaterialConsumptionRepository consumptionRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProcessSheetRowChangeLogRepository changeLogRepo;
    @Mock private ObjectMapper objectMapper;
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository processRepo;
    @Mock private ProductTypeRepository productTypeRepo;

    private ProcessSheetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProcessSheetServiceImpl(
                clerkService, rowRepo, materialBatchRepo, productionBatchRepo,
                consumptionRepo, reportRepo, productionPlanRepository, changeLogRepo,
                objectMapper, wipRepo, taskRepo, processRepo, productTypeRepo);
    }

    // ─────────────────────────────────────────────────────────────
    // Helper builders
    // ─────────────────────────────────────────────────────────────

    private ProductionBatch batch(long id) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setFactoryId(FACTORY);
        b.setProductionPlanId(PLAN_ID);
        return b;
    }

    private SemiFinishedInventory wip(long batchId, int order, String produced, String consumed,
                                      String available, String unit, Long taskId) {
        return SemiFinishedInventory.builder()
                .id((long) order)
                .factoryId(FACTORY)
                .batchId(batchId)
                .intermediateBatchNo("WIP-" + batchId + "-" + order)
                .processOrder(order)
                .producedQuantity(new BigDecimal(produced))
                .consumedQuantity(new BigDecimal(consumed))
                .availableQuantity(new BigDecimal(available))
                .unit(unit)
                .status("AVAILABLE")
                .sourceWorkProcessTaskId(taskId)
                .build();
    }

    private ProductionReport yieldReport(int processOrder, String inputQty) {
        ProductionReport r = new ProductionReport();
        r.setProcessOrder(processOrder);
        r.setInputQuantity(new BigDecimal(inputQty));
        r.setOutputQuantity(new BigDecimal("0"));
        return r;
    }

    // ─────────────────────────────────────────────────────────────
    // YIELD-CARD-1: 线性三道 (拆包100→修油90→焯水80)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YIELD-CARD-1: 三道 stepYield 正确; 焯水 step=88.89% cumulative=80%")
    void threeStepLinearChain_yieldRatesCorrect() {
        // Given: 一个批次三道工序
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(batch(BATCH_ID)));

        // WIP 行: 拆包=100kg, 修油=90kg, 焯水=80kg
        List<SemiFinishedInventory> wips = List.of(
                wip(BATCH_ID, 1, "100", "90", "10", "kg", null),
                wip(BATCH_ID, 2, "90",  "80", "10", "kg", null),
                wip(BATCH_ID, 3, "80",  "0",  "80", "kg", null)
        );
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH_ID))
                .thenReturn(wips);

        // 首道报工: processOrder=1, inputQuantity=100
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH_ID))
                .thenReturn(List.of(yieldReport(1, "100")));

        // 无 task (processName 不测) → 空列表
        when(taskRepo.findByFactoryIdAndIdIn(eq(FACTORY), any(Collection.class)))
                .thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        // 同单位 kg → gramsPerUnit 不需要 (但 productTypeId=null → 不查)
        when(productTypeRepo.findById(anyString())).thenReturn(Optional.empty());

        // When
        List<ProcessSheetInventoryItem> result =
                service.getInventoryYieldCard(FACTORY, PLAN_ID);

        // Then
        assertThat(result).hasSize(3);

        ProcessSheetInventoryItem step1 = result.get(0);
        assertThat(step1.getProcessOrder()).isEqualTo(1);
        // step1: produced=100 / firstInput=100 = 100%
        assertThat(step1.getStepYieldRate())
                .as("拆包 stepYieldRate = 100/100 = 100%")
                .isEqualByComparingTo("100.0000");
        assertThat(step1.getCumulativeYieldRate())
                .as("拆包 cumulativeYieldRate = 100/100 = 100%")
                .isEqualByComparingTo("100.0000");

        ProcessSheetInventoryItem step2 = result.get(1);
        assertThat(step2.getProcessOrder()).isEqualTo(2);
        // step2: produced=90, input=prev_produced=100 → 90%
        assertThat(step2.getStepYieldRate())
                .as("修油 stepYieldRate = 90/100 = 90%")
                .isEqualByComparingTo("90.0000");
        // cumulative: 90/100 = 90%
        assertThat(step2.getCumulativeYieldRate())
                .as("修油 cumulativeYieldRate = 90/100 = 90%")
                .isEqualByComparingTo("90.0000");

        ProcessSheetInventoryItem step3 = result.get(2);
        assertThat(step3.getProcessOrder()).isEqualTo(3);
        // step3: produced=80, input=prev_produced=90 → 88.8889%
        assertThat(step3.getStepYieldRate())
                .as("焯水 stepYieldRate = 80/90 ≈ 88.89%")
                .isEqualByComparingTo("88.8889");
        // cumulative: 80/100 = 80%
        assertThat(step3.getCumulativeYieldRate())
                .as("焯水 cumulativeYieldRate = 80/100 = 80%")
                .isEqualByComparingTo("80.0000");
    }

    // ─────────────────────────────────────────────────────────────
    // YIELD-CARD-2: 首道 step == cumulative
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YIELD-CARD-2: 首道 stepYieldRate == cumulativeYieldRate")
    void firstStep_stepEquals累ulative() {
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(batch(BATCH_ID)));
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH_ID))
                .thenReturn(List.of(wip(BATCH_ID, 1, "90", "0", "90", "kg", null)));
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH_ID))
                .thenReturn(List.of(yieldReport(1, "100")));
        when(taskRepo.findByFactoryIdAndIdIn(eq(FACTORY), any(Collection.class)))
                .thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).hasSize(1);
        ProcessSheetInventoryItem item = result.get(0);
        assertThat(item.getStepYieldRate()).isEqualByComparingTo(item.getCumulativeYieldRate());
        assertThat(item.getStepYieldRate()).isEqualByComparingTo("90.0000");
    }

    // ─────────────────────────────────────────────────────────────
    // YIELD-CARD-3: 跨单位无 gramsPerUnit → cumulativeYieldRate=null
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YIELD-CARD-3: 跨单位无折算系数 → cumulativeYieldRate=null (诚实)")
    void crossUnit_noGramsPerUnit_cumulativeNull() {
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(batch(BATCH_ID)));

        // 首道 kg, 末道 份
        List<SemiFinishedInventory> wips = List.of(
                wip(BATCH_ID, 1, "100", "0", "100", "kg", null),
                wip(BATCH_ID, 2, "120", "0", "120", "份",  "PT-BOX-001", null)
        );
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH_ID))
                .thenReturn(wips);
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH_ID))
                .thenReturn(List.of(yieldReport(1, "100")));
        when(taskRepo.findByFactoryIdAndIdIn(eq(FACTORY), any(Collection.class)))
                .thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());
        // ProductType without gramsPerUnit → gramsPerUnit = null
        ProductType pt = new ProductType();
        pt.setGramsPerUnit(null);
        when(productTypeRepo.findById("PT-BOX-001")).thenReturn(Optional.of(pt));

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).hasSize(2);
        ProcessSheetInventoryItem step2 = result.get(1);
        assertThat(step2.getCumulativeYieldRate())
                .as("跨单位无 gramsPerUnit → cumulativeYieldRate 诚实为 null")
                .isNull();
        // stepYieldRate is still computable (unit mismatch only affects cumulative)
        // step2 input = step1 producedQuantity = 100 (kg); step2 output = 120 份
        // Both are different units but stepYieldRate is just arithmetic ratio
        assertThat(step2.getStepYieldRate()).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────
    // YIELD-CARD-4: 首道 inputQuantity=0 → null
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YIELD-CARD-4: 首道原料投入=0 → stepYieldRate=null, cumulativeYieldRate=null")
    void firstInputZero_bothNull() {
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(batch(BATCH_ID)));
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH_ID))
                .thenReturn(List.of(wip(BATCH_ID, 1, "100", "0", "100", "kg", null)));
        // inputQuantity = 0
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH_ID))
                .thenReturn(List.of(yieldReport(1, "0")));
        when(taskRepo.findByFactoryIdAndIdIn(eq(FACTORY), any(Collection.class)))
                .thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).hasSize(1);
        ProcessSheetInventoryItem item = result.get(0);
        assertThat(item.getStepYieldRate())
                .as("首道投入=0 → stepYieldRate=null")
                .isNull();
        assertThat(item.getCumulativeYieldRate())
                .as("首道投入=0 → cumulativeYieldRate=null")
                .isNull();
    }

    // ─────────────────────────────────────────────────────────────
    // YIELD-CARD-5: 无批次 → 空列表
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YIELD-CARD-5: 无生产批次 → 空列表")
    void noBatches_returnsEmpty() {
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of());

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────
    // YIELD-CARD-6: 有批次无 WIP → 空列表
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YIELD-CARD-6: 有批次但无 WIP 行 → 空列表")
    void batchExistsNoWip_returnsEmpty() {
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(batch(BATCH_ID)));
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH_ID))
                .thenReturn(List.of());

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────
    // YIELD-CARD-7: 有 gramsPerUnit 时 cumulativeYieldRate 正确折算
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YIELD-CARD-7: 跨单位有 gramsPerUnit=200 → cumulativeYieldRate 正确")
    void crossUnit_withGramsPerUnit_cumulativeCorrect() {
        // 100kg 原料 → 首道 100kg (kg) → 末道 450份 × 200g/份 = 90kg 等价
        // cumulative = 90/100 = 90%
        when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(batch(BATCH_ID)));

        SemiFinishedInventory step1 = wip(BATCH_ID, 1, "100", "0", "100", "kg", null);
        SemiFinishedInventory step2wip = SemiFinishedInventory.builder()
                .id(2L)
                .factoryId(FACTORY)
                .batchId(BATCH_ID)
                .intermediateBatchNo("WIP-42-2")
                .processOrder(2)
                .producedQuantity(new BigDecimal("450"))
                .consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal("450"))
                .unit("份")
                .status("AVAILABLE")
                .productTypeId("PT-BOX-001")
                .build();

        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH_ID))
                .thenReturn(List.of(step1, step2wip));
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH_ID))
                .thenReturn(List.of(yieldReport(1, "100")));
        when(taskRepo.findByFactoryIdAndIdIn(eq(FACTORY), any(Collection.class)))
                .thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        ProductType pt = new ProductType();
        pt.setGramsPerUnit(new BigDecimal("200")); // 200g/份
        when(productTypeRepo.findById("PT-BOX-001")).thenReturn(Optional.of(pt));

        List<ProcessSheetInventoryItem> result = service.getInventoryYieldCard(FACTORY, PLAN_ID);

        assertThat(result).hasSize(2);
        ProcessSheetInventoryItem finalStep = result.get(1);
        // 450份 × 200g/1000 = 90kg; 90/100 = 90%
        assertThat(finalStep.getCumulativeYieldRate())
                .as("450份×200g=90kg / 100kg = 90%")
                .isEqualByComparingTo("90.0000");
    }

    // ─────────────────────────────────────────────────────────────
    // Helper: 3-field wip overload (no taskId)
    // ─────────────────────────────────────────────────────────────

    private SemiFinishedInventory wip(long batchId, int order, String produced, String consumed,
                                      String available, String unit, String productTypeId,
                                      Long taskId) {
        return SemiFinishedInventory.builder()
                .id((long) order)
                .factoryId(FACTORY)
                .batchId(batchId)
                .intermediateBatchNo("WIP-" + batchId + "-" + order)
                .processOrder(order)
                .producedQuantity(new BigDecimal(produced))
                .consumedQuantity(new BigDecimal(consumed))
                .availableQuantity(new BigDecimal(available))
                .unit(unit)
                .status("AVAILABLE")
                .productTypeId(productTypeId)
                .sourceWorkProcessTaskId(taskId)
                .build();
    }
}
