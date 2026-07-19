package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.BatchEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.RawInput;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.UpstreamSource;
import com.cretas.aims.dto.processentry.ProcessChainEntryResult;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SP-B1 integration test: 文员录入链物化 + 成本桶分配验证。
 *
 * <p>验证三件事：
 * <ol>
 *   <li>原料成本按实际投料量精确归集（原料A 78kg vs 原料B 22kg，非按比例糊平均）</li>
 *   <li>调料成本落入 seasoning 桶，不污染 raw 桶（Fix 1 核心修复）</li>
 *   <li>菱形测试：共享上游批次被两个熟制锅消耗时，成本只计一次（不重复）</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("ClerkProcessEntryIntegrationTest - SP-B1 物化链集成测试")
class ClerkProcessEntryIntegrationTest {

    /**
     * JdbcTemplate for H2-specific DDL (SET REFERENTIAL_INTEGRITY FALSE).
     * Disables FK checks so we can seed test fixtures without a full dependency tree
     * (factories → product_types → raw_material_types → etc.).
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ClerkProcessEntryService clerkProcessEntryService;

    @Autowired
    private OrderCostBreakdownService orderCostBreakdownService;

    @Autowired
    private BomRecipeRepository recipeRepo;

    @Autowired
    private BomSeasoningItemRepository ingredientRepo;

    @Autowired
    private MaterialBatchRepository materialBatchRepo;

    @Autowired
    private ProductionPlanRepository planRepo;

    @Autowired
    private UserRepository userRepo;

    private static final String FACTORY_ID = "IT-FACTORY";
    private static final String PRODUCT_TYPE_ID = "IT-PTYPE-001";

    /** Seeded in setUp() from H2 IDENTITY — not hardcoded to avoid FK violation. */
    private Long operatorId;

    // 原料单价
    private static final BigDecimal PRICE_A = new BigDecimal("71.70");  // ¥71.70/kg
    private static final BigDecimal PRICE_B = new BigDecimal("170.00"); // ¥170/kg

    private String planId;
    private String orderId;
    private String matBatchAId;
    private String matBatchBId;

    @BeforeEach
    void setUp() {
        // 0. Disable H2 FK constraints so we can seed fixtures without full dependency tree
        //    (production_plans.created_by→users, users.factory_id→factories,
        //     production_plans.product_type_id→product_types, batches.material_type_id→raw_material_types, …)
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");

        // Seed a User row so created_by has a valid long id from IDENTITY sequence
        User user = new User();
        user.setFactoryId(FACTORY_ID);
        user.setUsername("it_operator_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("$2a$10$DUMMYHASHFORTESTPLACEHOLDERONLY1");
        user.setIsActive(true);
        user = userRepo.saveAndFlush(user);
        operatorId = user.getId();

        // 1. 创建 ProductionPlan (供 OrderCostBreakdownService 通过 sourceOrderId 查找)
        orderId = "IT-ORDER-" + UUID.randomUUID().toString().substring(0, 8);
        planId = "PLAN-" + UUID.randomUUID().toString().substring(0, 8);

        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("IT-PLAN-" + System.currentTimeMillis() % 100000);
        plan.setProductTypeId(PRODUCT_TYPE_ID);
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setPlannedUnit("kg");
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setCreatedBy(operatorId);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setSourceOrderId(orderId);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        planRepo.save(plan);

        // 2. 创建当前 BOM: INJECTION ~0.24/kg, 熟制 COOKING ~0.31/kg
        //    INJECTION: 240g/kg dosage, countInSeasoning=true
        //    COOKING:   310g/kg dosage, countInSeasoning=true
        //    季节成本 = 100kg × (0.240 × 10.0) + 1 pot (100kg) × 1 × (0.310 × 10.0)
        //             = 100 × 2.40 + 100 × 3.10 = 240 + 310 = 550  (用 priceSource1=10.0)
        String recipeId = "RECIPE-" + UUID.randomUUID().toString().substring(0, 8);
        BomRecipe recipe = new BomRecipe();
        recipe.setId(recipeId);
        recipe.setFactoryId(FACTORY_ID);
        recipe.setRecipeCode("BOM-IT-" + recipeId);
        recipe.setProductTypeId(PRODUCT_TYPE_ID);
        recipe.setProductName("IT-Test-Recipe");
        recipe.setVersion(1);
        recipe.setIsCurrent(true);
        recipe.setOutputQuantityPerUnit(new BigDecimal("1000"));
        recipe.setOutputUnit("g");
        recipe.setInjectionRate(null);
        recipe.setCookingPotBaseKg(new BigDecimal("100"));
        recipe.setSubsequentPotRatio(new BigDecimal("0.3333"));
        recipe.setSeasoningRevision(0L);
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setSourceType(BomRecipe.SourceType.MANUAL);
        recipeRepo.save(recipe);

        BomSeasoningItem injIngredient = new BomSeasoningItem();
        injIngredient.setRecipeId(recipeId);
        injIngredient.setFactoryId(FACTORY_ID);
        injIngredient.setSection(BomSeasoningItem.SECTION_INJECTION);
        injIngredient.setSeq(1);
        injIngredient.setName("IT-Injection-Spice");
        injIngredient.setDosagePerKgG(new BigDecimal("240")); // 240g/kg
        injIngredient.setPriceSource1(new BigDecimal("10.0"));
        injIngredient.setCountInSeasoning(true);
        ingredientRepo.save(injIngredient);

        BomSeasoningItem cookIngredient = new BomSeasoningItem();
        cookIngredient.setRecipeId(recipeId);
        cookIngredient.setFactoryId(FACTORY_ID);
        cookIngredient.setSection(BomSeasoningItem.SECTION_COOKING);
        cookIngredient.setSeq(2);
        cookIngredient.setName("IT-Cooking-Spice");
        cookIngredient.setDosagePerKgG(new BigDecimal("310")); // 310g/kg
        cookIngredient.setPriceSource1(new BigDecimal("10.0"));
        cookIngredient.setCountInSeasoning(true);
        ingredientRepo.save(cookIngredient);

        // 3. 创建原料 MaterialBatch
        //    原料A: 100kg @ ¥71.70 → 焯水A 消耗后输出 78kg (WIP)
        //    原料B: 22kg @ ¥170   → 焯水B 消耗后输出 22kg (WIP)
        matBatchAId = "MB-A-" + UUID.randomUUID().toString().substring(0, 8);
        MaterialBatch mbA = new MaterialBatch();
        mbA.setId(matBatchAId);
        mbA.setFactoryId(FACTORY_ID);
        mbA.setBatchNumber("IT-RAW-A-" + System.currentTimeMillis() % 100000);
        mbA.setMaterialTypeId("IT-MATTYPE-A");
        mbA.setWarehouseId("WH-IT-001");
        mbA.setReceiptQuantity(new BigDecimal("100.00"));
        mbA.setQuantityUnit("kg");
        mbA.setUsedQuantity(BigDecimal.ZERO);
        mbA.setReservedQuantity(BigDecimal.ZERO);
        mbA.setUnitPrice(PRICE_A);
        mbA.setStatus(MaterialBatchStatus.AVAILABLE);
        mbA.setReceiptDate(LocalDate.now());
        mbA.setCreatedBy(operatorId);
        materialBatchRepo.save(mbA);

        matBatchBId = "MB-B-" + UUID.randomUUID().toString().substring(0, 8);
        MaterialBatch mbB = new MaterialBatch();
        mbB.setId(matBatchBId);
        mbB.setFactoryId(FACTORY_ID);
        mbB.setBatchNumber("IT-RAW-B-" + System.currentTimeMillis() % 100000);
        mbB.setMaterialTypeId("IT-MATTYPE-B");
        mbB.setWarehouseId("WH-IT-001");
        mbB.setReceiptQuantity(new BigDecimal("22.00"));
        mbB.setQuantityUnit("kg");
        mbB.setUsedQuantity(BigDecimal.ZERO);
        mbB.setReservedQuantity(BigDecimal.ZERO);
        mbB.setUnitPrice(PRICE_B);
        mbB.setStatus(MaterialBatchStatus.AVAILABLE);
        mbB.setReceiptDate(LocalDate.now());
        mbB.setCreatedBy(operatorId);
        materialBatchRepo.save(mbB);
    }

    /**
     * 主集成测试: 焯水A + 焯水B → 熟制成品批
     *
     * <p>traceCost 追溯到叶节点原料成本（含焯水损耗），原料A/B 全量归集至成品批：
     * <ul>
     *   <li>原料A: 100kg × ¥71.70 = ¥7170.00 (焯水A 消耗 100kg 原料 → 78kg WIP，全量归成品)</li>
     *   <li>原料B: 22kg × ¥170 = ¥3740.00</li>
     *   <li>raw 桶 = ¥7170 + ¥3740 = ¥10910.00 (含焯水损耗成本)</li>
     *   <li>调料成本 > 0 且落入 seasoning 桶 (不在 raw 桶)</li>
     * </ul>
     *
     * <p>算法说明: traceCost 按 consumedQty/upstreamReceiptQty 比例分摊上游成本。
     * 焯水A 输出 78kg WIP，成品消耗全部 78kg (比例=1.0)，故成品承担焯水A 的全部原料成本 ¥7170。
     */
    @Test
    @DisplayName("T1: 原料成本精确归集 + 调料落 seasoning 桶 (Fix 1 核心)")
    void testSeasoningLandsInSeasoningBucket() {
        // ── Build request ──
        ProcessChainEntryRequest req = buildTwoUpstreamRequest("CHAIN-T1-" + UUID.randomUUID());

        // ── Execute ──
        ProcessChainEntryResult result = clerkProcessEntryService.recordChain(
                FACTORY_ID, planId, req, operatorId);

        assertThat(result.isIdempotentReplay()).isFalse();
        assertThat(result.getConsumptionsWritten()).as("应写入 2 条 MaterialConsumption (焯水A原料 + 焯水B原料 + 熟制两条混锅来源 = 4)")
                .isGreaterThanOrEqualTo(2);

        // ── Cost breakdown ──
        OrderCostBreakdownDTO dto = orderCostBreakdownService.compute(FACTORY_ID, orderId, false);
        assertThat(dto).isNotNull();

        // 原料桶: traceCost 追溯到叶节点
        //   焯水A: 消耗原料A 100kg × ¥71.70 = ¥7170; 成品消耗 WIP-A 全部 78/78=1 → raw += ¥7170
        //   焯水B: 消耗原料B 22kg × ¥170 = ¥3740; 成品消耗 WIP-B 全部 22/22=1 → raw += ¥3740
        //   Total raw = ¥10910
        BigDecimal expectedRaw = new BigDecimal("10910.00");
        assertThat(dto.getRawMaterialCost())
                .as("raw 桶应 = 原料A¥7170 + 原料B¥3740 = ¥10910 (含焯水损耗，无调料)")
                .isNotNull()
                .isEqualByComparingTo(expectedRaw);

        // 调料桶: > 0 (配方有调料)
        assertThat(dto.getSeasoningCost())
                .as("seasoning 桶应 > 0 (Fix 1: 调料走 ProductionReport 而非 SEASONING_VIRTUAL)")
                .isNotNull()
                .isGreaterThan(BigDecimal.ZERO);

        // 关键不等式: seasoning 未混入 raw
        // 旧代码 (SEASONING_VIRTUAL) → seasoning 变成 traceCost 的叶子，累入 raw
        // 修复后 seasoning 完全独立
        BigDecimal rawPlusSeasoning = dto.getRawMaterialCost().add(dto.getSeasoningCost() == null ? BigDecimal.ZERO : dto.getSeasoningCost());
        assertThat(dto.getRawMaterialCost())
                .as("raw 桶不应包含调料成本 (raw < raw+seasoning)")
                .isLessThan(rawPlusSeasoning);

        // ── Per-source assertion (dto.sources 有 SourceCost.cost 字段) ──
        // sources = [焯水A WIP (traced ¥7170), 焯水B WIP (traced ¥3740)]
        // cost-share: 焯水A ≈ 65.7%, 焯水B ≈ 34.3%
        assertThat(dto.getSources())
                .as("sources 列表应有 2 条上游来源 (焯水A + 焯水B)")
                .isNotNull()
                .hasSize(2);
        // Sort by cost descending for stable assertion order
        List<OrderCostBreakdownDTO.SourceCost> sortedSources = dto.getSources().stream()
                .sorted((a, b) -> b.getCost().compareTo(a.getCost()))
                .toList();
        assertThat(sortedSources.get(0).getCost())
                .as("最大上游来源 (焯水A) traced 成本应 = ¥7170 (100kg × ¥71.70)")
                .isNotNull()
                .isEqualByComparingTo(new BigDecimal("7170.00"));
        assertThat(sortedSources.get(1).getCost())
                .as("第二上游来源 (焯水B) traced 成本应 = ¥3740 (22kg × ¥170)")
                .isNotNull()
                .isEqualByComparingTo(new BigDecimal("3740.00"));
        // Cost-share percentages: 7170/10910 ≈ 65.7%, 3740/10910 ≈ 34.3%
        assertThat(sortedSources.get(0).getCostSharePct())
                .as("焯水A 成本占比 ≈ 65.7%")
                .isNotNull()
                .isBetween(new BigDecimal("65.0"), new BigDecimal("66.5"));
        assertThat(sortedSources.get(1).getCostSharePct())
                .as("焯水B 成本占比 ≈ 34.3%")
                .isNotNull()
                .isBetween(new BigDecimal("33.5"), new BigDecimal("35.0"));
    }

    /**
     * 菱形测试: 焯水A(100kg输出) → 熟制锅1(50kg) + 熟制锅2(50kg)
     * 原料成本应只归集一次 ¥600，不是 ¥1200。
     *
     * <p>设: 焯水A 消耗原料C 100kg @ ¥6/kg → raw cost = ¥600，WIP单价 = ¥6/kg
     * 熟制批 消耗焯水A WIP 50kg (锅1) + 50kg (锅2) = 100kg 共 ¥600
     * traceCost: 锅1 (50/100) × ¥600 + 锅2 (50/100) × ¥600 = ¥300 + ¥300 = ¥600 ✓
     */
    @Test
    @DisplayName("T2: 菱形测试 — 共享上游 WIP 被分两次消耗时成本不重复计入")
    void testDiamondNoCostDoubleCount() {
        // ── 额外原料批次 C: 100kg @ ¥6/kg ──
        String matCId = "MB-C-" + UUID.randomUUID().toString().substring(0, 8);
        MaterialBatch mbC = new MaterialBatch();
        mbC.setId(matCId);
        mbC.setFactoryId(FACTORY_ID);
        mbC.setBatchNumber("IT-RAW-C-" + System.currentTimeMillis() % 100000);
        mbC.setMaterialTypeId("IT-MATTYPE-C");
        mbC.setWarehouseId("WH-IT-001");
        mbC.setReceiptQuantity(new BigDecimal("100.00"));
        mbC.setQuantityUnit("kg");
        mbC.setUsedQuantity(BigDecimal.ZERO);
        mbC.setReservedQuantity(BigDecimal.ZERO);
        mbC.setUnitPrice(new BigDecimal("6.00"));
        mbC.setStatus(MaterialBatchStatus.AVAILABLE);
        mbC.setReceiptDate(LocalDate.now());
        mbC.setCreatedBy(operatorId);
        materialBatchRepo.save(mbC);

        // ── 独立 planId / orderId for this test ──
        String diamondOrderId = "DIAMOND-ORDER-" + UUID.randomUUID().toString().substring(0, 8);
        String diamondPlanId = "DIAMOND-PLAN-" + UUID.randomUUID().toString().substring(0, 8);

        ProductionPlan diamondPlan = new ProductionPlan();
        diamondPlan.setId(diamondPlanId);
        diamondPlan.setFactoryId(FACTORY_ID);
        diamondPlan.setPlanNumber("IT-DPLAN-" + System.currentTimeMillis() % 100000);
        diamondPlan.setProductTypeId(PRODUCT_TYPE_ID);
        diamondPlan.setPlannedQuantity(new BigDecimal("100"));
        diamondPlan.setPlannedUnit("kg");
        diamondPlan.setStatus(ProductionPlanStatus.PENDING);
        diamondPlan.setCreatedBy(operatorId);
        diamondPlan.setIsLocked(false);
        diamondPlan.setSkipProcessReporting(false);
        diamondPlan.setSourceOrderId(diamondOrderId);
        diamondPlan.setCreatedAt(LocalDateTime.now());
        diamondPlan.setUpdatedAt(LocalDateTime.now());
        planRepo.save(diamondPlan);

        // ── Request: 焯水A (WIP) → 熟制 (分两锅各 50kg) ──
        // 焯水A: 领料C 100kg, 输出 100kg
        StepEntry blanchStep = new StepEntry();
        blanchStep.setProcessOrder(1);
        blanchStep.setProcessName("焯水");
        blanchStep.setProcessCategory("RAW_MATERIAL");
        blanchStep.setInputQuantity(new BigDecimal("100"));
        blanchStep.setOutputQuantity(new BigDecimal("100"));
        blanchStep.setUnit("kg");
        blanchStep.setRawMaterialInputs(List.of(rawInput(matCId, new BigDecimal("100"))));

        BatchEntry blanchBatch = new BatchEntry();
        blanchBatch.setClientBatchKey("焯水A-diamond");
        blanchBatch.setProductTypeId("IT-WIP-TYPE");
        blanchBatch.setFinished(false);
        blanchBatch.setSteps(List.of(blanchStep));

        // 熟制: potCount=2, 两锅各 50kg, 上游来自 焯水A-diamond 全部 100kg
        StepEntry cookStep = new StepEntry();
        cookStep.setProcessOrder(2);
        cookStep.setProcessName("熟制");
        cookStep.setProcessCategory("SEASONING");
        cookStep.setInputQuantity(new BigDecimal("100"));
        cookStep.setOutputQuantity(new BigDecimal("90"));
        cookStep.setUnit("kg");
        cookStep.setPotCount(2);
        cookStep.setPotRawKgs(List.of(new BigDecimal("50"), new BigDecimal("50")));
        cookStep.setUpstreamSources(List.of(upstream("焯水A-diamond", new BigDecimal("100"))));

        BatchEntry finishedBatch = new BatchEntry();
        finishedBatch.setClientBatchKey("熟制-diamond");
        finishedBatch.setProductTypeId(PRODUCT_TYPE_ID);
        finishedBatch.setFinished(true);
        finishedBatch.setSteps(List.of(cookStep));

        ProcessChainEntryRequest req = new ProcessChainEntryRequest();
        req.setIdempotencyKey("DIAMOND-" + UUID.randomUUID());
        req.setBatches(List.of(blanchBatch, finishedBatch));

        ProcessChainEntryResult result = clerkProcessEntryService.recordChain(
                FACTORY_ID, diamondPlanId, req, operatorId);
        assertThat(result.isIdempotentReplay()).isFalse();

        // ── Cost breakdown ──
        OrderCostBreakdownDTO dto = orderCostBreakdownService.compute(FACTORY_ID, diamondOrderId, false);
        assertThat(dto).isNotNull();
        assertThat(dto.getRawMaterialCost())
                .as("菱形测试: 100kg @ ¥6 = ¥600, 两锅各 50kg 占比 0.5 → 300+300=600 (不是 ¥1200)")
                .isNotNull()
                .isEqualByComparingTo(new BigDecimal("600.00"));
    }

    // ─────────────────────────────────────────────────────────────
    // Builder helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * 焯水A (消耗原料A 100kg → 78kg) + 焯水B (消耗原料B 22kg → 22kg) + 熟制 (混锅).
     */
    private ProcessChainEntryRequest buildTwoUpstreamRequest(String idempotencyKey) {
        // 焯水A
        StepEntry blanchAStep = new StepEntry();
        blanchAStep.setProcessOrder(1);
        blanchAStep.setProcessName("焯水A");
        blanchAStep.setProcessCategory("RAW_MATERIAL");
        blanchAStep.setInputQuantity(new BigDecimal("100"));
        blanchAStep.setOutputQuantity(new BigDecimal("78"));
        blanchAStep.setUnit("kg");
        blanchAStep.setRawMaterialInputs(List.of(rawInput(matBatchAId, new BigDecimal("100"))));

        BatchEntry blanchA = new BatchEntry();
        blanchA.setClientBatchKey("焯水A");
        blanchA.setProductTypeId("IT-WIP-TYPE-A");
        blanchA.setFinished(false);
        blanchA.setSteps(List.of(blanchAStep));

        // 焯水B
        StepEntry blanchBStep = new StepEntry();
        blanchBStep.setProcessOrder(1);
        blanchBStep.setProcessName("焯水B");
        blanchBStep.setProcessCategory("RAW_MATERIAL");
        blanchBStep.setInputQuantity(new BigDecimal("22"));
        blanchBStep.setOutputQuantity(new BigDecimal("22"));
        blanchBStep.setUnit("kg");
        blanchBStep.setRawMaterialInputs(List.of(rawInput(matBatchBId, new BigDecimal("22"))));

        BatchEntry blanchB = new BatchEntry();
        blanchB.setClientBatchKey("焯水B");
        blanchB.setProductTypeId("IT-WIP-TYPE-B");
        blanchB.setFinished(false);
        blanchB.setSteps(List.of(blanchBStep));

        // 熟制成品批: 混锅 (焯水A 78kg + 焯水B 22kg), potCount=1, 无 potRawKgs → 服务内自动填
        StepEntry cookStep = new StepEntry();
        cookStep.setProcessOrder(2);
        cookStep.setProcessName("熟制");
        cookStep.setProcessCategory("SEASONING");
        cookStep.setInputQuantity(new BigDecimal("100")); // 78+22
        cookStep.setOutputQuantity(new BigDecimal("90")); // 假设出成率 90%
        cookStep.setUnit("kg");
        cookStep.setPotCount(1);
        cookStep.setUpstreamSources(List.of(
                upstream("焯水A", new BigDecimal("78")),
                upstream("焯水B", new BigDecimal("22"))
        ));

        BatchEntry finished = new BatchEntry();
        finished.setClientBatchKey("熟制成品");
        finished.setProductTypeId(PRODUCT_TYPE_ID);
        finished.setFinished(true);
        finished.setSteps(List.of(cookStep));

        ProcessChainEntryRequest req = new ProcessChainEntryRequest();
        req.setIdempotencyKey(idempotencyKey);
        req.setBatches(List.of(blanchA, blanchB, finished));
        return req;
    }

    private RawInput rawInput(String batchId, BigDecimal qty) {
        RawInput ri = new RawInput();
        ri.setMaterialBatchId(batchId);
        ri.setQuantity(qty);
        return ri;
    }

    private UpstreamSource upstream(String key, BigDecimal feedKg) {
        UpstreamSource us = new UpstreamSource();
        us.setSourceClientBatchKey(key);
        us.setFeedQuantityKg(feedKg);
        return us;
    }
}
