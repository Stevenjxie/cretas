package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.LaborSegment;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.BatchEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.RawInput;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.UpstreamSource;
import com.cretas.aims.dto.processentry.ProcessChainEntryResult;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProcessEntryIdempotency;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessEntryIdempotencyRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.recipe.ProductRecipeRepository;
import com.cretas.aims.repository.recipe.RecipeIngredientRepository;
import com.cretas.aims.service.processentry.impl.ClerkProcessEntryServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SP-B1 Task 4 — ClerkProcessEntryServiceImpl 单元测试.
 *
 * <p>测试覆盖:
 * <ol>
 *   <li>T1 混锅 65.7:34.3 — 两路上游分别写一条 SEMI_FINISHED 消耗行</li>
 *   <li>T2 菱形拓扑 — 两个下游各消耗上游50kg, 各¥300, 合计¥600</li>
 *   <li>T3 幂等重放 — 同 key 第二次返回缓存, 不重复写库</li>
 *   <li>T4 跨租户 404 — 引用其他工厂 MaterialBatch 抛 BusinessException(404)</li>
 *   <li>T5 recordedBy 非 null — 每条 MaterialConsumption.recordedBy == operatorId</li>
 *   <li>T6 operatorId=null → BusinessException(401)</li>
 *   <li>T7 minutesBetween 基础计算 (包私有方法)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ClerkProcessEntryServiceImpl — SP-B1 Task 4")
class ClerkProcessEntryServiceImplTest {

    private static final String FACTORY = "DEMO_FACTORY";
    private static final String OTHER_FACTORY = "WRONG_FACTORY";
    private static final String PLAN_ID = "PLAN-TEST-001";
    private static final Long OPERATOR_ID = 42L;

    @Mock private ProductionBatchRepository batchRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private MaterialConsumptionRepository consumptionRepo;
    @Mock private ProcessEntryIdempotencyRepository idempotencyRepo;
    @Mock private FactoryWarehouseRepository warehouseRepo;
    @Mock private ProductRecipeRepository recipeRepo;
    @Mock private RecipeIngredientRepository ingredientRepo;
    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private com.cretas.aims.repository.ProductionReportRepository reportRepo;

    @InjectMocks
    private ClerkProcessEntryServiceImpl service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void injectObjectMapper() throws Exception {
        // @RequiredArgsConstructor includes ObjectMapper as a final field.
        // @InjectMocks doesn't inject it since it's not a @Mock.
        // Use reflection to set it.
        var f = ClerkProcessEntryServiceImpl.class.getDeclaredField("objectMapper");
        f.setAccessible(true);
        f.set(service, objectMapper);
    }

    // ─────────────────────────────────────────────────────────────
    // Common stubs
    // ─────────────────────────────────────────────────────────────

    private void stubNoIdempotency(String key) {
        when(idempotencyRepo.findByFactoryIdAndPlanIdAndIdempotencyKey(FACTORY, PLAN_ID, key))
                .thenReturn(Optional.empty());
    }

    /** SP-D Fix 2: stub planRepository to allow planId for FACTORY. */
    private void stubPlan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY))
                .thenReturn(Optional.of(plan));
    }

    private void stubWarehouse() {
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId("WH-WKS-1");
        wh.setFactoryId(FACTORY);
        wh.setCode("WH-WKS");
        wh.setName("车间仓");
        when(warehouseRepo.findByFactoryIdAndCodeAndDeletedAtIsNull(eq(FACTORY), anyString()))
                .thenReturn(Optional.of(wh));
    }

    private void stubBatchSave() {
        when(batchRepo.existsByFactoryIdAndBatchNumber(any(), any())).thenReturn(false);
        when(batchRepo.save(any(ProductionBatch.class))).thenAnswer(inv -> {
            ProductionBatch b = inv.getArgument(0);
            if (b.getId() == null) b.setId(System.nanoTime() % 100_000L);
            return b;
        });
    }

    private void stubMbSave() {
        // Track saved WIP batches so findByIdAndFactoryId can return them
        when(materialBatchRepo.save(any(MaterialBatch.class))).thenAnswer(inv -> {
            MaterialBatch mb = inv.getArgument(0);
            // Stub subsequent findByIdAndFactoryId(mb.id, FACTORY) to return this saved batch
            if (mb.getId() != null) {
                when(materialBatchRepo.findByIdAndFactoryId(mb.getId(), FACTORY))
                        .thenReturn(Optional.of(mb));
            }
            return mb;
        });
        when(materialBatchRepo.findByBatchNumber(anyString())).thenReturn(Optional.empty());
    }

    private void stubConsumptionSave() {
        when(consumptionRepo.save(any(MaterialConsumption.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubIdempotencySave() {
        when(idempotencyRepo.save(any(ProcessEntryIdempotency.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubNoRecipe() {
        when(recipeRepo.findByFactoryIdAndProductTypeIdAndStatus(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    // ─────────────────────────────────────────────────────────────
    // Entity builders
    // ─────────────────────────────────────────────────────────────

    private MaterialBatch rawMb(String id, String factoryId, BigDecimal unitPrice) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId(id);
        mb.setFactoryId(factoryId);
        mb.setBatchNumber("RAW-" + id);
        mb.setMaterialTypeId("MT-PORK");
        mb.setReceiptQuantity(new BigDecimal("500"));
        mb.setUnitPrice(unitPrice);
        mb.setQuantityUnit("kg");
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setReceiptDate(LocalDate.now());
        return mb;
    }

    // ─────────────────────────────────────────────────────────────
    // DTO builders
    // ─────────────────────────────────────────────────────────────

    private RawInput rawInput(String mbId, String qty) {
        RawInput r = new RawInput();
        r.setMaterialBatchId(mbId);
        r.setQuantity(new BigDecimal(qty));
        return r;
    }

    private UpstreamSource upstreamSource(String key, String feedKg) {
        UpstreamSource u = new UpstreamSource();
        u.setSourceClientBatchKey(key);
        u.setFeedQuantityKg(new BigDecimal(feedKg));
        return u;
    }

    private StepEntry rawStep(int order, String inQty, String outQty, List<RawInput> inputs) {
        StepEntry s = new StepEntry();
        s.setProcessOrder(order);
        s.setProcessName("领料");
        s.setInputQuantity(new BigDecimal(inQty));
        s.setOutputQuantity(new BigDecimal(outQty));
        s.setRawMaterialInputs(inputs);
        return s;
    }

    private StepEntry blendStep(int order, String inQty, String outQty, List<UpstreamSource> upstreams) {
        StepEntry s = new StepEntry();
        s.setProcessOrder(order);
        s.setProcessName("混锅");
        s.setInputQuantity(new BigDecimal(inQty));
        s.setOutputQuantity(new BigDecimal(outQty));
        s.setUpstreamSources(upstreams);
        return s;
    }

    private BatchEntry wipBatch(String key, String productTypeId, List<StepEntry> steps) {
        BatchEntry b = new BatchEntry();
        b.setClientBatchKey(key);
        b.setProductTypeId(productTypeId);
        b.setFinished(false);
        b.setSteps(steps);
        return b;
    }

    private BatchEntry finishedBatch(String key, String productTypeId, List<StepEntry> steps) {
        BatchEntry b = new BatchEntry();
        b.setClientBatchKey(key);
        b.setProductTypeId(productTypeId);
        b.setFinished(true);
        b.setSteps(steps);
        return b;
    }

    private ProcessChainEntryRequest req(String idempKey, List<BatchEntry> batches) {
        ProcessChainEntryRequest r = new ProcessChainEntryRequest();
        r.setIdempotencyKey(idempKey);
        r.setBatches(batches);
        return r;
    }

    // ─────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────

    /**
     * T1 — 混锅 65.7:34.3.
     *
     * <p>两个 WIP 半成品批 (A: 100kg@¥10 = ¥1000, B: 50kg@¥8 = ¥400) 先物化,
     * 成品批混锅消耗 65.7kg from A + 34.3kg from B.
     * 期望: 2 条 SEMI_FINISHED 行, 合计成本 65.7×10 + 34.3×8 = ¥657 + ¥274.40 = ¥931.40.
     */
    @Test
    @DisplayName("T1: 混锅 65.7:34.3 — 两路上游各写一条 SEMI_FINISHED 行, 合计成本正确")
    void t1_blendedPot_65_7_34_3() {
        stubNoIdempotency("T1-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch rawA = rawMb("RAW-A", FACTORY, new BigDecimal("10"));
        MaterialBatch rawB = rawMb("RAW-B", FACTORY, new BigDecimal("8"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-A", FACTORY)).thenReturn(Optional.of(rawA));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-B", FACTORY)).thenReturn(Optional.of(rawB));

        // Two WIP batches + one finished that blends both
        BatchEntry wipA = wipBatch("WIP-A", "PT-PORK", List.of(
                rawStep(1, "100", "100", List.of(rawInput("RAW-A", "100")))
        ));
        BatchEntry wipB = wipBatch("WIP-B", "PT-SEASON", List.of(
                rawStep(1, "50", "50", List.of(rawInput("RAW-B", "50")))
        ));
        BatchEntry finished = finishedBatch("FINAL", "PT-PRODUCT", List.of(
                blendStep(1, "100", "80", List.of(
                        upstreamSource("WIP-A", "65.7"),
                        upstreamSource("WIP-B", "34.3")
                ))
        ));

        ProcessChainEntryResult result = service.recordChain(
                FACTORY, PLAN_ID, req("T1-KEY", List.of(wipA, wipB, finished)), OPERATOR_ID);

        assertThat(result.getWipBatchesMaterialized()).as("2 WIP batches materialized").isEqualTo(2);
        // 2 raw (one per WIP) + 2 SEMI_FINISHED (in the finished batch) = 4 total
        assertThat(result.getConsumptionsWritten()).as("4 consumption rows").isEqualTo(4);
        assertThat(result.getFinishedBatchNumber()).as("finished batch number set").isNotNull();

        // Verify SEMI_FINISHED cost totals
        ArgumentCaptor<MaterialConsumption> cap = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(consumptionRepo, times(4)).save(cap.capture());

        BigDecimal totalBlendCost = cap.getAllValues().stream()
                .filter(c -> "SEMI_FINISHED".equals(c.getSourceType()))
                .map(MaterialConsumption::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 65.7 × 10 + 34.3 × 8 = 657.00 + 274.40 = 931.40
        assertThat(totalBlendCost)
                .as("混锅合计成本 65.7×¥10 + 34.3×¥8 = ¥931.40")
                .isEqualByComparingTo("931.40");
    }

    /**
     * T2 — 菱形拓扑: 两个下游各消耗上游 50 kg.
     *
     * <p>上游 WIP 100kg@¥6 (成本¥600 from raw).
     * 两次独立 recordChain: 下游A消耗50kg → SEMI_FINISHED ¥300; 下游B消耗50kg → ¥300.
     * 合计¥600 = 上游总成本, 无双计.
     */
    @Test
    @DisplayName("T2: 菱形拓扑 — 两下游各消耗50kg, 各¥300, 合计¥600 == 上游总成本")
    void t2_diamond_noDoubleCount() {
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch rawMat = rawMb("RAW-DIAMOND", FACTORY, new BigDecimal("6"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-DIAMOND", FACTORY))
                .thenReturn(Optional.of(rawMat));

        // === Call 1: upstream WIP + downstream A (50 kg) ===
        when(idempotencyRepo.findByFactoryIdAndPlanIdAndIdempotencyKey(FACTORY, PLAN_ID, "T2-KEY-A"))
                .thenReturn(Optional.empty());

        BatchEntry upperWip = wipBatch("UPPER", "PT-UPPER", List.of(
                rawStep(1, "100", "100", List.of(rawInput("RAW-DIAMOND", "100")))
        ));
        BatchEntry downA = finishedBatch("DOWN-A", "PT-DOWN", List.of(
                blendStep(1, "50", "45", List.of(upstreamSource("UPPER", "50")))
        ));

        service.recordChain(FACTORY, PLAN_ID, req("T2-KEY-A", List.of(upperWip, downA)), OPERATOR_ID);

        ArgumentCaptor<MaterialConsumption> capA = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(consumptionRepo, times(2)).save(capA.capture()); // 1 raw + 1 semi

        BigDecimal costA = capA.getAllValues().stream()
                .filter(c -> "SEMI_FINISHED".equals(c.getSourceType()))
                .findFirst()
                .map(MaterialConsumption::getTotalCost)
                .orElse(BigDecimal.ZERO);

        // WIP unit price = ¥600/100 = ¥6; 50 kg × ¥6 = ¥300
        assertThat(costA).as("下游A: 50kg × ¥6 = ¥300").isEqualByComparingTo("300.00");

        // Reset counters for call 2
        reset(consumptionRepo, batchRepo, materialBatchRepo, idempotencyRepo);
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        when(materialBatchRepo.findByIdAndFactoryId("RAW-DIAMOND", FACTORY))
                .thenReturn(Optional.of(rawMat));
        when(idempotencyRepo.findByFactoryIdAndPlanIdAndIdempotencyKey(FACTORY, PLAN_ID, "T2-KEY-B"))
                .thenReturn(Optional.empty());

        // === Call 2: fresh upstream WIP + downstream B (50 kg) ===
        BatchEntry upperWip2 = wipBatch("UPPER2", "PT-UPPER", List.of(
                rawStep(1, "100", "100", List.of(rawInput("RAW-DIAMOND", "100")))
        ));
        BatchEntry downB = finishedBatch("DOWN-B", "PT-DOWN", List.of(
                blendStep(1, "50", "45", List.of(upstreamSource("UPPER2", "50")))
        ));

        service.recordChain(FACTORY, PLAN_ID, req("T2-KEY-B", List.of(upperWip2, downB)), OPERATOR_ID);

        ArgumentCaptor<MaterialConsumption> capB = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(consumptionRepo, times(2)).save(capB.capture());

        BigDecimal costB = capB.getAllValues().stream()
                .filter(c -> "SEMI_FINISHED".equals(c.getSourceType()))
                .findFirst()
                .map(MaterialConsumption::getTotalCost)
                .orElse(BigDecimal.ZERO);

        assertThat(costB).as("下游B: 50kg × ¥6 = ¥300").isEqualByComparingTo("300.00");

        // The two costs together equal the upstream total — no double-count
        assertThat(costA.add(costB))
                .as("两下游合计¥600 == 上游总成本(不双计为¥1200)")
                .isEqualByComparingTo("600.00");
    }

    /**
     * T3 — 幂等重放.
     *
     * <p>首次调用正常录入; 第二次同 key 返回缓存结果 (idempotentReplay=true),
     * 不再写新的 ProductionBatch 或 MaterialConsumption.
     */
    @Test
    @DisplayName("T3: 幂等重放 — 同 key 第二次返回缓存, 不重复写库")
    void t3_idempotency_replay() {
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch rawMat = rawMb("RAW-IDEM", FACTORY, new BigDecimal("5"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-IDEM", FACTORY))
                .thenReturn(Optional.of(rawMat));

        // First call: no cache
        when(idempotencyRepo.findByFactoryIdAndPlanIdAndIdempotencyKey(FACTORY, PLAN_ID, "KEY-IDEM"))
                .thenReturn(Optional.empty());

        BatchEntry batch1 = finishedBatch("F1", "PT-F1", List.of(
                rawStep(1, "10", "8", List.of(rawInput("RAW-IDEM", "10")))
        ));
        ProcessChainEntryResult firstResult = service.recordChain(
                FACTORY, PLAN_ID, req("KEY-IDEM", List.of(batch1)), OPERATOR_ID);

        assertThat(firstResult.isIdempotentReplay()).as("首次 idempotentReplay=false").isFalse();
        assertThat(firstResult.getConsumptionsWritten()).as("写 1 条消耗").isEqualTo(1);

        // Capture the idempotency record that was saved
        ArgumentCaptor<ProcessEntryIdempotency> idemCap = ArgumentCaptor.forClass(ProcessEntryIdempotency.class);
        verify(idempotencyRepo).save(idemCap.capture());
        ProcessEntryIdempotency savedRecord = idemCap.getValue();

        // Second call: cache hit
        when(idempotencyRepo.findByFactoryIdAndPlanIdAndIdempotencyKey(FACTORY, PLAN_ID, "KEY-IDEM"))
                .thenReturn(Optional.of(savedRecord));

        ProcessChainEntryResult replayResult = service.recordChain(
                FACTORY, PLAN_ID, req("KEY-IDEM", List.of(batch1)), OPERATOR_ID);

        assertThat(replayResult.isIdempotentReplay())
                .as("第二次 idempotentReplay=true")
                .isTrue();
        assertThat(replayResult.getConsumptionsWritten())
                .as("重放结果 consumptionsWritten 与首次一致")
                .isEqualTo(firstResult.getConsumptionsWritten());

        // No additional writes after replay
        verify(batchRepo, times(1)).save(any(ProductionBatch.class));
        verify(consumptionRepo, times(1)).save(any(MaterialConsumption.class));
    }

    /**
     * T4 — 跨租户隔离.
     *
     * <p>请求引用的 MaterialBatch 属于 OTHER_FACTORY.
     * findByIdAndFactoryId(id, FACTORY) 返回 empty → 抛 BusinessException(404).
     */
    @Test
    @DisplayName("T4: 跨租户 404 — 引用其他工厂 MaterialBatch 抛 BusinessException(404)")
    void t4_crossTenant_404() {
        stubNoIdempotency("T4-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubNoRecipe();

        // Cross-tenant batch: belongs to OTHER_FACTORY, not found when queried with FACTORY
        when(materialBatchRepo.findByIdAndFactoryId("RAW-FOREIGN", FACTORY))
                .thenReturn(Optional.empty());

        BatchEntry batch = finishedBatch("CROSS-TENANT", "PT-X", List.of(
                rawStep(1, "100", "80", List.of(rawInput("RAW-FOREIGN", "100")))
        ));

        assertThatThrownBy(() ->
                service.recordChain(FACTORY, PLAN_ID, req("T4-KEY", List.of(batch)), OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .as("HTTP 404").isEqualTo(404));

        verify(consumptionRepo, never()).save(any());
    }

    /**
     * T5 — recordedBy 非 null.
     *
     * <p>每条 MaterialConsumption.recordedBy 必须等于 operatorId.
     * 防止使用 SecurityUtils.getCurrentUserId() (本项目永返 null, 已在 SP-B1 Javadoc 禁用).
     */
    @Test
    @DisplayName("T5: recordedBy == operatorId — 不能为 null (SecurityUtils 永返 null 已禁用)")
    void t5_recordedBy_nonNull() {
        stubNoIdempotency("T5-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch rawA = rawMb("RAW-T5-A", FACTORY, new BigDecimal("15"));
        MaterialBatch rawB = rawMb("RAW-T5-B", FACTORY, new BigDecimal("20"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T5-A", FACTORY)).thenReturn(Optional.of(rawA));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T5-B", FACTORY)).thenReturn(Optional.of(rawB));

        BatchEntry wip1 = wipBatch("WIP-T5", "PT-A", List.of(
                rawStep(1, "30", "28", List.of(rawInput("RAW-T5-A", "15"), rawInput("RAW-T5-B", "15")))
        ));
        BatchEntry fin = finishedBatch("FIN-T5", "PT-FINAL", List.of(
                blendStep(1, "28", "25", List.of(upstreamSource("WIP-T5", "28")))
        ));

        service.recordChain(FACTORY, PLAN_ID, req("T5-KEY", List.of(wip1, fin)), OPERATOR_ID);

        ArgumentCaptor<MaterialConsumption> cap = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(consumptionRepo, atLeast(1)).save(cap.capture());

        for (MaterialConsumption c : cap.getAllValues()) {
            assertThat(c.getRecordedBy())
                    .as("MaterialConsumption.recordedBy must be operatorId, never null")
                    .isEqualTo(OPERATOR_ID);
        }
    }

    /**
     * T6 — operatorId=null 抛 BusinessException(401).
     */
    @Test
    @DisplayName("T6: operatorId=null → BusinessException(401), 不写任何行")
    void t6_operatorIdNull_throws() {
        when(idempotencyRepo.findByFactoryIdAndPlanIdAndIdempotencyKey(any(), any(), any()))
                .thenReturn(Optional.empty());

        BatchEntry batch = finishedBatch("F-NULL", "PT-X", List.of(
                rawStep(1, "10", "8", List.of(rawInput("RAW-NULL", "10")))
        ));

        assertThatThrownBy(() ->
                service.recordChain(FACTORY, PLAN_ID, req("KEY-NULL", List.of(batch)), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .as("HTTP 401").isEqualTo(401));

        verify(consumptionRepo, never()).save(any());
        verify(batchRepo, never()).save(any());
    }

    /**
     * T7 — minutesBetween (package-private helper).
     */
    @Test
    @DisplayName("T7: minutesBetween — 08:00→10:30=150, 22:00→02:00(隔夜)=240, 同时=0")
    void t7_minutesBetween_basic() {
        assertThat(service.minutesBetween("08:00", "10:30")).isEqualTo(150);
        assertThat(service.minutesBetween("22:00", "02:00")).isEqualTo(240);
        assertThat(service.minutesBetween("09:00", "09:00")).isEqualTo(0);
    }

    // ─────────────────────────────────────────────────────────────
    // SP-D Fix 1a regression: REGULAR vs CLERK_WIP batchType
    // ─────────────────────────────────────────────────────────────

    /**
     * T8 — REGULAR batchType set for finished batch.
     * SP-D Fix 1a: isFinished=true → batchType must be "REGULAR" so it appears in dashboard/list.
     */
    @Test
    @DisplayName("T8: isFinished=true → batchType=REGULAR (SP-D Fix 1a)")
    void t8_finishedBatch_isRegular() {
        stubNoIdempotency("T8-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch raw = rawMb("RAW-T8", FACTORY, new BigDecimal("5"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T8", FACTORY)).thenReturn(Optional.of(raw));

        BatchEntry batch = finishedBatch("FIN-T8", "PT-X", List.of(
                rawStep(1, "10", "8", List.of(rawInput("RAW-T8", "10")))
        ));
        service.recordChain(FACTORY, PLAN_ID, req("T8-KEY", List.of(batch)), OPERATOR_ID);

        ArgumentCaptor<ProductionBatch> cap = ArgumentCaptor.forClass(ProductionBatch.class);
        verify(batchRepo).save(cap.capture());
        assertThat(cap.getValue().getBatchType())
                .as("finished batch batchType must be REGULAR")
                .isEqualTo("REGULAR");
        assertThat(cap.getValue().getMaterialCost())
                .as("ProductionBatch materialCost should mirror materialized consumption cost")
                .isEqualByComparingTo("50.00");
        assertThat(cap.getValue().getTotalCost())
                .as("ProductionBatch totalCost should not remain blank after materialization")
                .isEqualByComparingTo("50.00");
        assertThat(cap.getValue().getUnitCost())
                .as("unitCost = totalCost / outputQuantity")
                .isEqualByComparingTo("6.2500");
        assertThat(cap.getValue().getYieldRate())
                .as("yieldRate = outputQuantity / inputQuantity * 100")
                .isEqualByComparingTo("80.00");
    }

    /**
     * T9 — CLERK_WIP batchType set for WIP batch.
     * SP-D Fix 1a: isFinished=false → batchType must be "CLERK_WIP" to be excluded from dashboard/list.
     */
    @Test
    @DisplayName("T9: isFinished=false → batchType=CLERK_WIP (SP-D Fix 1a)")
    void t9_wipBatch_isClerkWip() {
        stubNoIdempotency("T9-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch raw = rawMb("RAW-T9", FACTORY, new BigDecimal("5"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T9", FACTORY)).thenReturn(Optional.of(raw));

        // Only a WIP batch (no finished batch)
        BatchEntry wip = wipBatch("WIP-T9", "PT-X", List.of(
                rawStep(1, "20", "18", List.of(rawInput("RAW-T9", "20")))
        ));

        // Need a finished batch too (WIP-only raises no FINISHED batch but finishedBatchNumber is null)
        // Use a simple finished batch to make the chain valid
        BatchEntry fin = finishedBatch("FIN-T9", "PT-Y", List.of(
                blendStep(1, "18", "15", List.of(upstreamSource("WIP-T9", "18")))
        ));
        service.recordChain(FACTORY, PLAN_ID, req("T9-KEY", List.of(wip, fin)), OPERATOR_ID);

        ArgumentCaptor<ProductionBatch> cap = ArgumentCaptor.forClass(ProductionBatch.class);
        // Two batches saved: first the WIP then the finished
        verify(batchRepo, times(2)).save(cap.capture());
        List<ProductionBatch> saved = cap.getAllValues();

        // The first saved batch is the WIP (sorted: finished=false first)
        assertThat(saved.get(0).getBatchType())
                .as("WIP batch (isFinished=false) batchType must be CLERK_WIP")
                .isEqualTo("CLERK_WIP");
        assertThat(saved.get(1).getBatchType())
                .as("Finished batch (isFinished=true) batchType must be REGULAR")
                .isEqualTo("REGULAR");
    }

    // ─────────────────────────────────────────────────────────────
    // SP-D Fix 2 regression: cross-tenant planId guard
    // ─────────────────────────────────────────────────────────────

    /**
     * T10 — cross-tenant planId guard.
     * SP-D Fix 2: planId that belongs to another factory → BusinessException(404).
     */
    @Test
    @DisplayName("T10: planId 归属其他工厂 → BusinessException(404) (SP-D Fix 2)")
    void t10_crossTenantPlanId_throws404() {
        // planRepository returns empty → plan not found for this factory
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.empty());

        BatchEntry batch = finishedBatch("F-CROSS", "PT-X", List.of(
                rawStep(1, "10", "8", List.of(rawInput("RAW-CROSS", "10")))
        ));

        assertThatThrownBy(() ->
                service.recordChain(FACTORY, PLAN_ID, req("T10-KEY", List.of(batch)), OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .as("HTTP 404 for cross-tenant planId")
                        .isEqualTo(404));

        // No batches or consumptions should be written
        verify(batchRepo, never()).save(any());
        verify(consumptionRepo, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────
    // SP-D Fix 3 regression: Q2 seasoning silent-0 warning
    // ─────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────
    // SP-F Task 1.4 — computeLaborCost(List<LaborSegment>, BigDecimal)
    // ─────────────────────────────────────────────────────────────

    /**
     * T12 — 多段工时求和.
     *
     * <p>段A: 08:00→10:00, 2人 → 2h × 2 = 4 工时
     * 段B: 13:00→14:00, 3人 → 1h × 3 = 3 工时
     * 合计 7 工时 × ¥26 = ¥182.00
     */
    @Test
    @DisplayName("T12: 多段工时求和 — 2h×2 + 1h×3 = 7工时 × ¥26 = ¥182.00")
    void computeLaborCost_sumsMultipleSegments() {
        LaborSegment a = new LaborSegment();
        a.setStartTime("08:00"); a.setEndTime("10:00"); a.setWorkerCount(2); // 2h×2=4
        LaborSegment b = new LaborSegment();
        b.setStartTime("13:00"); b.setEndTime("14:00"); b.setWorkerCount(3); // 1h×3=3
        BigDecimal rate = new BigDecimal("26");
        // (4+3)=7 工时 × 26 = 182.00
        assertThat(service.computeLaborCost(java.util.List.of(a, b), rate))
                .isEqualByComparingTo("182.00");
    }

    /**
     * T13 — null 或空 segments 返回零.
     */
    @Test
    @DisplayName("T13: null 或空 segments → BigDecimal.ZERO")
    void computeLaborCost_nullOrEmptySegments_returnsZero() {
        assertThat(service.computeLaborCost((java.util.List<LaborSegment>) null, new BigDecimal("26")))
                .isEqualByComparingTo("0");
        assertThat(service.computeLaborCost(java.util.List.of(), new BigDecimal("26")))
                .isEqualByComparingTo("0");
    }

    // ─────────────────────────────────────────────────────────────
    // SP-F Fix 1 — multi-pot N>1 requires per-pot kg
    // ─────────────────────────────────────────────────────────────

    /**
     * T14a — potCount=2, potRawKgs=null → BusinessException(400).
     * N>1 에서 per-pot 원료量 미입력 시 靜默 等分을 허용하면 안 됨.
     */
    @Test
    @DisplayName("T14a: potCount=2, potRawKgs=null → BusinessException(400) 不允许静默等分")
    void t14a_multiPot_noPerPotKgs_throws400() {
        stubNoIdempotency("T14A-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        // Need a recipe so computeSeasoningCost is actually called
        com.cretas.aims.entity.recipe.ProductRecipe recipe = new com.cretas.aims.entity.recipe.ProductRecipe();
        recipe.setId("R-MULTI");
        recipe.setSubsequentPotRatio(new java.math.BigDecimal("0.5"));
        when(recipeRepo.findByFactoryIdAndProductTypeIdAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(recipe));
        when(ingredientRepo.findByRecipeIdOrderBySeqAsc(any())).thenReturn(List.of());

        MaterialBatch raw = rawMb("RAW-T14A", FACTORY, new BigDecimal("10"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T14A", FACTORY)).thenReturn(Optional.of(raw));

        // Seasoning step: potCount=2 but NO potRawKgs
        StepEntry seasoning = new StepEntry();
        seasoning.setProcessOrder(1);
        seasoning.setProcessName("熟制");
        seasoning.setProcessCategory("SEASONING");
        seasoning.setPotCount(2);
        seasoning.setPotRawKgs(null); // ← missing!
        seasoning.setInputQuantity(new BigDecimal("100"));
        seasoning.setOutputQuantity(new BigDecimal("90"));

        BatchEntry batch = finishedBatch("FIN-T14A", "PT-X", List.of(
                rawStep(0, "100", "100", List.of(rawInput("RAW-T14A", "100"))),
                seasoning
        ));

        assertThatThrownBy(() ->
                service.recordChain(FACTORY, PLAN_ID, req("T14A-KEY", List.of(batch)), OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).as("HTTP 400").isEqualTo(400);
                    assertThat(ex.getMessage()).as("message mentions pot count").contains("锅数=2");
                });
    }

    /**
     * T14b — potCount=2, potRawKgs size!=potCount → BusinessException(400).
     * 如只填了 1 个锅的数据，同样拒绝。
     */
    @Test
    @DisplayName("T14b: potCount=2, potRawKgs.size=1 (不足) → BusinessException(400)")
    void t14b_multiPot_wrongSizePerPotKgs_throws400() {
        stubNoIdempotency("T14B-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        com.cretas.aims.entity.recipe.ProductRecipe recipe = new com.cretas.aims.entity.recipe.ProductRecipe();
        recipe.setId("R-MULTI2");
        recipe.setSubsequentPotRatio(new java.math.BigDecimal("0.5"));
        when(recipeRepo.findByFactoryIdAndProductTypeIdAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(recipe));
        when(ingredientRepo.findByRecipeIdOrderBySeqAsc(any())).thenReturn(List.of());

        MaterialBatch raw = rawMb("RAW-T14B", FACTORY, new BigDecimal("10"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T14B", FACTORY)).thenReturn(Optional.of(raw));

        StepEntry seasoning = new StepEntry();
        seasoning.setProcessOrder(1);
        seasoning.setProcessCategory("SEASONING");
        seasoning.setPotCount(2);
        seasoning.setPotRawKgs(List.of(new BigDecimal("60"))); // only 1, need 2
        seasoning.setInputQuantity(new BigDecimal("100"));
        seasoning.setOutputQuantity(new BigDecimal("90"));

        BatchEntry batch = finishedBatch("FIN-T14B", "PT-X", List.of(
                rawStep(0, "100", "100", List.of(rawInput("RAW-T14B", "100"))),
                seasoning
        ));

        assertThatThrownBy(() ->
                service.recordChain(FACTORY, PLAN_ID, req("T14B-KEY", List.of(batch)), OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(400));
    }

    /**
     * T14c — potCount=2, potRawKgs=[60,40] (correct size) → OK, no exception.
     */
    @Test
    @DisplayName("T14c: potCount=2, potRawKgs=[60,40] (size 匹配) → 正常执行，无异常")
    void t14c_multiPot_correctPerPotKgs_ok() {
        stubNoIdempotency("T14C-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        com.cretas.aims.entity.recipe.ProductRecipe recipe = new com.cretas.aims.entity.recipe.ProductRecipe();
        recipe.setId("R-MULTI3");
        recipe.setSubsequentPotRatio(new java.math.BigDecimal("0.5"));
        when(recipeRepo.findByFactoryIdAndProductTypeIdAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(recipe));
        when(ingredientRepo.findByRecipeIdOrderBySeqAsc(any())).thenReturn(List.of());

        MaterialBatch raw = rawMb("RAW-T14C", FACTORY, new BigDecimal("10"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T14C", FACTORY)).thenReturn(Optional.of(raw));

        StepEntry seasoning = new StepEntry();
        seasoning.setProcessOrder(1);
        seasoning.setProcessCategory("SEASONING");
        seasoning.setPotCount(2);
        seasoning.setPotRawKgs(List.of(new BigDecimal("60"), new BigDecimal("40")));
        seasoning.setInputQuantity(new BigDecimal("100"));
        seasoning.setOutputQuantity(new BigDecimal("90"));

        BatchEntry batch = finishedBatch("FIN-T14C", "PT-X", List.of(
                rawStep(0, "100", "100", List.of(rawInput("RAW-T14C", "100"))),
                seasoning
        ));

        // Should not throw
        ProcessChainEntryResult result = service.recordChain(
                FACTORY, PLAN_ID, req("T14C-KEY", List.of(batch)), OPERATOR_ID);
        assertThat(result.getFinishedBatchNumber()).as("finished batch produced").isNotNull();
    }

    /**
     * T14d — potCount=1 (单锅), potRawKgs=null → OK (N==1 不强制 per-pot kg).
     */
    @Test
    @DisplayName("T14d: potCount=1, potRawKgs=null → OK (单锅不需要逐锅填写)")
    void t14d_singlePot_noPotRawKgs_ok() {
        stubNoIdempotency("T14D-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch raw = rawMb("RAW-T14D", FACTORY, new BigDecimal("10"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T14D", FACTORY)).thenReturn(Optional.of(raw));

        // potCount=1 (or null) → no guard triggered
        StepEntry seasoning = new StepEntry();
        seasoning.setProcessOrder(1);
        seasoning.setProcessCategory("SEASONING");
        seasoning.setPotCount(1);
        seasoning.setPotRawKgs(null); // fine for single pot
        seasoning.setInputQuantity(new BigDecimal("100"));
        seasoning.setOutputQuantity(new BigDecimal("90"));

        BatchEntry batch = finishedBatch("FIN-T14D", "PT-X", List.of(
                rawStep(0, "100", "100", List.of(rawInput("RAW-T14D", "100"))),
                seasoning
        ));

        ProcessChainEntryResult result = service.recordChain(
                FACTORY, PLAN_ID, req("T14D-KEY", List.of(batch)), OPERATOR_ID);
        assertThat(result.getFinishedBatchNumber()).isNotNull();
    }

    /**
     * T14e — potCount=null (未指定锅数), potRawKgs=null → OK.
     */
    @Test
    @DisplayName("T14e: potCount=null (未指定), potRawKgs=null → OK (视同单锅)")
    void t14e_nullPotCount_ok() {
        stubNoIdempotency("T14E-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch raw = rawMb("RAW-T14E", FACTORY, new BigDecimal("10"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T14E", FACTORY)).thenReturn(Optional.of(raw));

        StepEntry seasoning = new StepEntry();
        seasoning.setProcessOrder(1);
        seasoning.setPotCount(null); // ← null → treated as single pot
        seasoning.setPotRawKgs(null);
        seasoning.setInputQuantity(new BigDecimal("50"));
        seasoning.setOutputQuantity(new BigDecimal("45"));

        BatchEntry batch = finishedBatch("FIN-T14E", "PT-X", List.of(
                rawStep(0, "50", "50", List.of(rawInput("RAW-T14E", "50"))),
                seasoning
        ));

        ProcessChainEntryResult result = service.recordChain(
                FACTORY, PLAN_ID, req("T14E-KEY", List.of(batch)), OPERATOR_ID);
        assertThat(result.getFinishedBatchNumber()).isNotNull();
    }

    /**
     * T15 — SP-F ①a: 人工成本写一条 ProductionReport(costCategory=LABOR, laborCost 字段)。
     *
     * <p>单道领料 08:00→10:00 2人 = 2h×2 = 4 工时 × ¥26 = ¥104.00。
     * 期望: reportRepo.save 收到一条 reportType=YIELD, costCategory=LABOR, laborCost=104.00 的报工。
     */
    @Test
    @DisplayName("T15: 人工写 ProductionReport(costCategory=LABOR, laborCost=104.00) (SP-F ①a)")
    void t15_laborReport_written() {
        stubNoIdempotency("T15-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch raw = rawMb("RAW-T15", FACTORY, new BigDecimal("5"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T15", FACTORY)).thenReturn(Optional.of(raw));

        // 单道领料 + 人工 08:00→10:00, 2 人 → 4 工时 × ¥26(默认) = ¥104.00
        StepEntry step = rawStep(1, "100", "80", List.of(rawInput("RAW-T15", "100")));
        step.setLaborStartTime("08:00");
        step.setLaborEndTime("10:00");
        step.setWorkerCount(2);

        BatchEntry batch = finishedBatch("FIN-T15", "PT-X", List.of(step));
        service.recordChain(FACTORY, PLAN_ID, req("T15-KEY", List.of(batch)), OPERATOR_ID);

        ArgumentCaptor<com.cretas.aims.entity.ProductionReport> cap =
                ArgumentCaptor.forClass(com.cretas.aims.entity.ProductionReport.class);
        verify(reportRepo, atLeastOnce()).save(cap.capture());

        com.cretas.aims.entity.ProductionReport laborRpt = cap.getAllValues().stream()
                .filter(r -> "LABOR".equals(r.getCostCategory()))
                .findFirst()
                .orElse(null);
        assertThat(laborRpt).as("a LABOR-category ProductionReport must be written").isNotNull();
        assertThat(laborRpt.getReportType()).isEqualTo("YIELD");
        assertThat(laborRpt.getLaborCost())
                .as("4 工时 × ¥26 = ¥104.00").isEqualByComparingTo("104.00");
        assertThat(laborRpt.getMaterialCost())
                .as("labor report 不设 materialCost (不进材料分桶)").isNull();
        assertThat(laborRpt.getBatchId()).as("挂在本批").isNotNull();
        assertThat(laborRpt.getWorkerId()).isEqualTo(OPERATOR_ID);
    }

    /**
     * 跨天: step.processDate 设置时, 成本报工 reportDate 取该工序实际操作日 (非录入当天)。
     * 让焯水周一/熟制周三等跨天工序成本归到各自真实日期。
     */
    @Test
    @DisplayName("跨天: step.processDate → 报工 reportDate 取该操作日 (非当天)")
    void crossDay_reportDate_fromProcessDate() {
        stubNoIdempotency("CROSSDAY-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch raw = rawMb("RAW-CD", FACTORY, new BigDecimal("5"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-CD", FACTORY)).thenReturn(Optional.of(raw));

        java.time.LocalDate opDay = java.time.LocalDate.of(2026, 6, 16);
        StepEntry step = rawStep(1, "100", "80", List.of(rawInput("RAW-CD", "100")));
        step.setLaborStartTime("08:00");
        step.setLaborEndTime("10:00");
        step.setWorkerCount(2);
        step.setProcessDate(opDay);  // 跨天: 该工序实际操作日

        BatchEntry batch = finishedBatch("FIN-CD", "PT-X", List.of(step));
        service.recordChain(FACTORY, PLAN_ID, req("CROSSDAY-KEY", List.of(batch)), OPERATOR_ID);

        ArgumentCaptor<com.cretas.aims.entity.ProductionReport> cap =
                ArgumentCaptor.forClass(com.cretas.aims.entity.ProductionReport.class);
        verify(reportRepo, atLeastOnce()).save(cap.capture());
        com.cretas.aims.entity.ProductionReport rpt = cap.getAllValues().stream()
                .filter(r -> "LABOR".equals(r.getCostCategory())).findFirst().orElse(null);
        assertThat(rpt).as("LABOR 报工应写出").isNotNull();
        assertThat(rpt.getReportDate())
                .as("报工日期 = 工序操作日 2026-06-16, 非录入当天").isEqualTo(opDay);
    }

    /**
     * T16 — SP-F ①a: laborCost=0 时不写 LABOR 报工 (无工时/人数 → ¥0, 诚实不造空行)。
     */
    @Test
    @DisplayName("T16: laborCost=0 → 不写 LABOR 报工 (SP-F ①a)")
    void t16_zeroLabor_noReport() {
        stubNoIdempotency("T16-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch raw = rawMb("RAW-T16", FACTORY, new BigDecimal("5"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T16", FACTORY)).thenReturn(Optional.of(raw));

        // 无 labor 字段 → computeLaborCost 返回 0 → 不写 LABOR 报工
        StepEntry step = rawStep(1, "100", "80", List.of(rawInput("RAW-T16", "100")));
        BatchEntry batch = finishedBatch("FIN-T16", "PT-X", List.of(step));
        service.recordChain(FACTORY, PLAN_ID, req("T16-KEY", List.of(batch)), OPERATOR_ID);

        ArgumentCaptor<com.cretas.aims.entity.ProductionReport> cap =
                ArgumentCaptor.forClass(com.cretas.aims.entity.ProductionReport.class);
        verify(reportRepo, atLeast(0)).save(cap.capture());

        boolean anyLabor = cap.getAllValues().stream()
                .anyMatch(r -> "LABOR".equals(r.getCostCategory()));
        assertThat(anyLabor).as("零人工不应写 LABOR 报工").isFalse();
    }

    /**
     * T11 — blend step named like seasoning but without a seasoning recipe → warning emitted.
     * Process names such as 熟制 are now treated as seasoning steps even when processCategory
     * and potCount are blank; the expected guard is the missing-recipe warning.
     */
    @Test
    @DisplayName("T11: 熟制混锅无调料配方 → warnings 含调料配方提示")
    void t11_nonSeasoningBlendStep_emitsWarning() {
        stubNoIdempotency("T11-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch rawA = rawMb("RAW-T11A", FACTORY, new BigDecimal("10"));
        MaterialBatch rawB = rawMb("RAW-T11B", FACTORY, new BigDecimal("8"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T11A", FACTORY)).thenReturn(Optional.of(rawA));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T11B", FACTORY)).thenReturn(Optional.of(rawB));

        // WIP batches
        BatchEntry wipA = wipBatch("WIP-T11A", "PT-A", List.of(
                rawStep(1, "50", "50", List.of(rawInput("RAW-T11A", "50")))
        ));
        BatchEntry wipB = wipBatch("WIP-T11B", "PT-B", List.of(
                rawStep(1, "30", "30", List.of(rawInput("RAW-T11B", "30")))
        ));

        // Finished batch: 熟制 name makes the step seasoning even without processCategory/potCount.
        StepEntry blendNoSeasoning = new StepEntry();
        blendNoSeasoning.setProcessOrder(1);
        blendNoSeasoning.setProcessName("熟制");
        blendNoSeasoning.setInputQuantity(new BigDecimal("80"));
        blendNoSeasoning.setOutputQuantity(new BigDecimal("70"));
        blendNoSeasoning.setUpstreamSources(List.of(
                upstreamSource("WIP-T11A", "50"),
                upstreamSource("WIP-T11B", "30")
        ));
        // processCategory is null and potCount is null, but processName 熟制 triggers seasoning recognition.
        BatchEntry fin = finishedBatch("FIN-T11", "PT-FINAL", List.of(blendNoSeasoning));

        ProcessChainEntryResult result = service.recordChain(
                FACTORY, PLAN_ID, req("T11-KEY", List.of(wipA, wipB, fin)), OPERATOR_ID);

        // Warning must point to the missing seasoning recipe; "未识别为调味步骤" would be stale.
        assertThat(result.getWarnings())
                .as("should contain missing seasoning recipe warning for 熟制 step")
                .anyMatch(w -> w.contains("PT-FINAL") && w.contains("未设置调料配方"));
    }

    /**
     * SP-G G3a — 副产物/留样/包装明细 写 YIELD 报工。
     *
     * <p>单道领料 step 带 byproducts(1项) + sampleRetainQuantity(3) + packagingDetail(1项)
     * → recordChain 物化 → 捕获的某条 ProductionReport 含这 3 字段且值正确。
     */
    @Test
    @DisplayName("SP-G G3a: byproducts/留样/packagingDetail → 写 YIELD 报工 (byproducts map, sampleRetain=3, packagingDetail)")
    void spg_g3a_auxFields_yieldReportWritten() {
        stubNoIdempotency("G3A-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        com.cretas.aims.entity.MaterialBatch raw = rawMb("RAW-G3A", FACTORY, new BigDecimal("5"));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-G3A", FACTORY)).thenReturn(Optional.of(raw));

        // 构造带副产物/留样/包装明细的 step
        StepEntry step = rawStep(1, "100", "80", List.of(rawInput("RAW-G3A", "100")));

        // byproducts: 1 项 (料头, 8kg)
        com.cretas.aims.dto.processentry.ProcessChainEntryRequest.Byproduct bp =
                new com.cretas.aims.dto.processentry.ProcessChainEntryRequest.Byproduct();
        bp.setName("料头");
        bp.setQuantity(new java.math.BigDecimal("8"));
        bp.setUnit("kg");
        bp.setUnitPrice(new java.math.BigDecimal("8"));   // 回收价 (可选, 提供时须流到报工→副产回收)
        step.setByproducts(List.of(bp));

        // sampleRetainQuantity: 3 盒
        step.setSampleRetainQuantity(3);

        // packagingDetail: 1 项 (包装膜, ¥1.50)
        java.util.Map<String, Object> pkgItem = new java.util.LinkedHashMap<>();
        pkgItem.put("name", "包装膜");
        pkgItem.put("cost", new java.math.BigDecimal("1.50"));
        step.setPackagingDetail(List.of(pkgItem));

        BatchEntry batch = finishedBatch("FIN-G3A", "PT-X", List.of(step));
        service.recordChain(FACTORY, PLAN_ID, req("G3A-KEY", List.of(batch)), OPERATOR_ID);

        ArgumentCaptor<com.cretas.aims.entity.ProductionReport> cap =
                ArgumentCaptor.forClass(com.cretas.aims.entity.ProductionReport.class);
        verify(reportRepo, atLeastOnce()).save(cap.capture());

        // 找到写了副产物/留样/包装明细的那条 YIELD 报工
        com.cretas.aims.entity.ProductionReport auxRpt = cap.getAllValues().stream()
                .filter(r -> r.getByproducts() != null || r.getSampleRetainQuantity() != null
                        || r.getPackagingDetail() != null)
                .findFirst()
                .orElse(null);

        assertThat(auxRpt).as("应写出一条含副产物/留样/包装明细的 ProductionReport").isNotNull();
        assertThat(auxRpt.getReportType()).isEqualTo("YIELD");
        assertThat(auxRpt.getBatchId()).as("挂在本批").isNotNull();
        assertThat(auxRpt.getWorkerId()).isEqualTo(OPERATOR_ID);

        // byproducts 转 Map: [{name=料头, quantity=8, unit=kg, unitPrice=8}]
        assertThat(auxRpt.getByproducts()).as("byproducts 非空").isNotNull().hasSize(1);
        assertThat(auxRpt.getByproducts().get(0))
                .containsEntry("name", "料头")
                .containsKey("quantity")
                .containsEntry("unitPrice", new java.math.BigDecimal("8"));   // 严格测试: 回收价须流到报工

        // sampleRetainQuantity = 3
        assertThat(auxRpt.getSampleRetainQuantity()).as("留样件数 = 3").isEqualTo(3);

        // packagingDetail = [{name=包装膜, cost=1.50}]
        assertThat(auxRpt.getPackagingDetail()).as("packagingDetail 非空").isNotNull().hasSize(1);
        assertThat(auxRpt.getPackagingDetail().get(0))
                .containsEntry("name", "包装膜");
    }

    /**
     * T17 — 🔒 honest-null: 混批含未计价源 (源 unitPrice==null) → 批次 ROLL-UP totalCost/unitCost 诚实置 null。
     *
     * <p>与纯 SFI 投料路径 (ProcessSheetServiceImpl:151「成本诚实 null」) 一致 —— 未知成本不假造 0。
     * 区分 null(未知) vs 0(真免费): 仅 null unitPrice 触发 honest-null。
     *
     * <p>全部已计价的姊妹批 (WIP-C) 仍精确求和 (回归 all-costed 保证; T8/T1 亦覆盖)。
     */
    @Test
    @DisplayName("T17: 混批含未计价源 (unitPrice=null) → totalCost/unitCost honest-null; 已计价批仍精确")
    void t17_mixedUncostedSource_honestNull() {
        stubNoIdempotency("T17-KEY");
        stubPlan();
        stubWarehouse();
        stubBatchSave();
        stubMbSave();
        stubConsumptionSave();
        stubIdempotencySave();
        stubNoRecipe();

        MaterialBatch rawCosted = rawMb("RAW-T17-C", FACTORY, new BigDecimal("10"));   // 已计价
        MaterialBatch rawUncosted = rawMb("RAW-T17-U", FACTORY, null);                 // 未计价 (unitPrice=null)
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T17-C", FACTORY)).thenReturn(Optional.of(rawCosted));
        when(materialBatchRepo.findByIdAndFactoryId("RAW-T17-U", FACTORY)).thenReturn(Optional.of(rawUncosted));

        BatchEntry wipCosted = wipBatch("WIP-C", "PT-A", List.of(
                rawStep(1, "100", "100", List.of(rawInput("RAW-T17-C", "100")))
        ));
        BatchEntry wipUncosted = wipBatch("WIP-U", "PT-B", List.of(
                rawStep(1, "50", "50", List.of(rawInput("RAW-T17-U", "50")))
        ));
        BatchEntry finished = finishedBatch("FIN-T17", "PT-PRODUCT", List.of(
                blendStep(1, "100", "80", List.of(
                        upstreamSource("WIP-C", "65.7"),
                        upstreamSource("WIP-U", "34.3")
                ))
        ));

        service.recordChain(FACTORY, PLAN_ID,
                req("T17-KEY", List.of(wipCosted, wipUncosted, finished)), OPERATOR_ID);

        ArgumentCaptor<ProductionBatch> cap = ArgumentCaptor.forClass(ProductionBatch.class);
        verify(batchRepo, times(3)).save(cap.capture());
        List<ProductionBatch> saved = cap.getAllValues();

        // 成品批 (REGULAR) 混入未计价源 → ROLL-UP honest-null
        ProductionBatch finBatch = saved.stream()
                .filter(b -> "REGULAR".equals(b.getBatchType()))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(finBatch.getTotalCost()).as("含未计价源 → totalCost honest-null (不假造 0)").isNull();
        assertThat(finBatch.getUnitCost()).as("含未计价源 → unitCost honest-null").isNull();

        // 未计价 WIP 批也 honest-null (诚实链传播), 已计价 WIP 批仍精确
        ProductionBatch wipUncostedBatch = saved.stream()
                .filter(b -> "CLERK_WIP".equals(b.getBatchType()) && b.getTotalCost() == null)
                .findFirst().orElse(null);
        assertThat(wipUncostedBatch).as("未计价 WIP 批也 honest-null").isNotNull();

        ProductionBatch wipCostedBatch = saved.stream()
                .filter(b -> "CLERK_WIP".equals(b.getBatchType()) && b.getTotalCost() != null)
                .findFirst().orElse(null);
        assertThat(wipCostedBatch).as("已计价 WIP 批 totalCost 仍精确 100kg×¥10=¥1000")
                .isNotNull();
        assertThat(wipCostedBatch.getTotalCost()).isEqualByComparingTo("1000.00");

        // WIP-U 的产出 MaterialBatch 单价诚实 null (供下游复用时再次触发 anyUncosted)
        ArgumentCaptor<MaterialBatch> mbCap = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepo, atLeastOnce()).save(mbCap.capture());
        boolean anyWipUnitPriceNull = mbCap.getAllValues().stream()
                .anyMatch(mb -> mb.getUnitPrice() == null);
        assertThat(anyWipUnitPriceNull)
                .as("未计价 WIP 产出批 unitPrice 诚实 null (honest-null 复用链)").isTrue();
    }
}
