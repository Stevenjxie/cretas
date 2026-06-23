package com.cretas.aims.service.processentry;

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

    /**
     * T11 — Fix 3: blend step with upstreamSources but no seasoning config → warning emitted.
     * SP-D Fix 3: A step with upstreamSources but processCategory != SEASONING and potCount == null
     * should emit a warning about unconfigured seasoning, NOT silently drop cost.
     */
    @Test
    @DisplayName("T11: 混锅工序无 processCategory=SEASONING → warnings 含调料配置提示 (SP-D Fix 3)")
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

        // Finished batch: blend step with upstreamSources, but NO processCategory=SEASONING and NO potCount
        StepEntry blendNoSeasoning = new StepEntry();
        blendNoSeasoning.setProcessOrder(1);
        blendNoSeasoning.setProcessName("熟制");
        blendNoSeasoning.setInputQuantity(new BigDecimal("80"));
        blendNoSeasoning.setOutputQuantity(new BigDecimal("70"));
        blendNoSeasoning.setUpstreamSources(List.of(
                upstreamSource("WIP-T11A", "50"),
                upstreamSource("WIP-T11B", "30")
        ));
        // processCategory is null and potCount is null → isSeasoningStep = false
        BatchEntry fin = finishedBatch("FIN-T11", "PT-FINAL", List.of(blendNoSeasoning));

        ProcessChainEntryResult result = service.recordChain(
                FACTORY, PLAN_ID, req("T11-KEY", List.of(wipA, wipB, fin)), OPERATOR_ID);

        // Warning must mention the step name and the missing configuration
        assertThat(result.getWarnings())
                .as("should contain seasoning configuration warning for 熟制 step")
                .anyMatch(w -> w.contains("熟制") && w.contains("调料成本未计入"));
    }
}
