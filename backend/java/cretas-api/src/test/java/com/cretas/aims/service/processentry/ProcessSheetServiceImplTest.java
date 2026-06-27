package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.RawInput;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.UpstreamRef;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.recipe.ProductRecipe;
import com.cretas.aims.entity.recipe.RecipeIngredient;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ProcessSheetRowChangeLogRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.recipe.ProductRecipeRepository;
import com.cretas.aims.repository.recipe.RecipeIngredientRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SP-F Task 1.5 — ProcessSheetService.saveRow 新建路径集成测试 (真 H2 写库)。
 *
 * <p>模仿 ClerkProcessEntryIntegrationTest: SET REFERENTIAL_INTEGRITY FALSE 后种子,
 * 让 MaterialConsumption / WIP MaterialBatch / SEASONING ProductionReport 真落库并断言。
 *
 * <p>覆盖:
 * <ol>
 *   <li>修油单行 (rawInputs + output>0) → RAW consumption + WIP 批 (CLERK_WIP, materialTypeId from raw)</li>
 *   <li>熟制混锅多源 + seasoningStep → 2 SEMI edges + SEASONING report + materialTypeId from upstream WIP</li>
 *   <li>edges 全无 materialTypeId → 400 (SP-E FK 防线)</li>
 *   <li>output<=0 → DRAFT 行不物化</li>
 *   <li>跨租户 planId → 403; 未知上游 batchNumber → 409</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("ProcessSheetServiceImplTest - SP-F Task 1.5 saveRow 新建路径")
class ProcessSheetServiceImplTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProcessSheetService processSheetService;

    @Autowired
    private MaterialBatchRepository materialBatchRepo;

    @Autowired
    private MaterialConsumptionRepository consumptionRepo;

    @Autowired
    private ProductionBatchRepository batchRepo;

    @Autowired
    private ProductionReportRepository reportRepo;

    @Autowired
    private ProductionPlanRepository planRepo;

    @Autowired
    private ProcessSheetRowRepository rowRepo;

    @Autowired
    private ProcessSheetRowChangeLogRepository changeLogRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private ProductRecipeRepository recipeRepo;

    @Autowired
    private RecipeIngredientRepository ingredientRepo;

    @Autowired
    private FactoryWarehouseRepository warehouseRepo;

    private static final String FACTORY_ID = "PSF-FACTORY";
    private static final String OTHER_FACTORY_ID = "PSF-OTHER";
    private static final String PRODUCT_TYPE_ID = "PSF-PTYPE-001";
    private static final String RAW_MATERIAL_TYPE_ID = "PSF-MATTYPE-PORK";

    private static final BigDecimal RAW_PRICE = new BigDecimal("10.00"); // ¥10/kg

    private Long operatorId;
    private String planId;
    private String rawBatchId;
    private String rawWarehouseId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");

        // User (valid created_by id)
        User user = new User();
        user.setFactoryId(FACTORY_ID);
        user.setUsername("psf_clerk_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("$2a$10$DUMMYHASHFORTESTPLACEHOLDERONLY1");
        user.setIsActive(true);
        user = userRepo.saveAndFlush(user);
        operatorId = user.getId();

        FactoryWarehouse rawWarehouse = new FactoryWarehouse();
        rawWarehouseId = "PSF-WH-LOG-" + UUID.randomUUID().toString().substring(0, 8);
        rawWarehouse.setId(rawWarehouseId);
        rawWarehouse.setFactoryId(FACTORY_ID);
        rawWarehouse.setCode("WH-LOG");
        rawWarehouse.setName("测试物流仓");
        rawWarehouse.setType(WarehouseType.LOGISTICS);
        rawWarehouse.setIsActive(true);
        warehouseRepo.saveAndFlush(rawWarehouse);

        // Plan (belongs to FACTORY_ID)
        planId = "PSF-PLAN-" + UUID.randomUUID().toString().substring(0, 8);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("PSF-PN-" + System.currentTimeMillis() % 100000);
        plan.setProductTypeId(PRODUCT_TYPE_ID);
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setCreatedBy(operatorId);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        planRepo.save(plan);

        // Raw material batch (the 修油 source) — carries materialTypeId for FK safety net
        rawBatchId = "PSF-MB-RAW-" + UUID.randomUUID().toString().substring(0, 8);
        MaterialBatch raw = new MaterialBatch();
        raw.setId(rawBatchId);
        raw.setFactoryId(FACTORY_ID);
        raw.setBatchNumber("PSF-RAW-" + System.currentTimeMillis() % 100000);
        raw.setMaterialTypeId(RAW_MATERIAL_TYPE_ID);
        raw.setWarehouseId(rawWarehouseId);
        raw.setReceiptQuantity(new BigDecimal("100.00"));
        raw.setQuantityUnit("kg");
        raw.setUsedQuantity(BigDecimal.ZERO);
        raw.setReservedQuantity(BigDecimal.ZERO);
        raw.setUnitPrice(RAW_PRICE);
        raw.setStatus(MaterialBatchStatus.AVAILABLE);
        raw.setReceiptDate(LocalDate.now());
        raw.setCreatedBy(operatorId);
        materialBatchRepo.saveAndFlush(raw);
    }

    // ─────────────────────────────────────────────────────────────
    // Builders
    // ─────────────────────────────────────────────────────────────

    private RawInput rawInput(String mbId, String qty) {
        RawInput r = new RawInput();
        r.setMaterialBatchId(mbId);
        r.setQuantity(new BigDecimal(qty));
        return r;
    }

    private UpstreamRef upstreamRef(String batchNumber, String feedKg) {
        UpstreamRef u = new UpstreamRef();
        u.setSourceBatchNumber(batchNumber);
        u.setFeedQuantityKg(new BigDecimal(feedKg));
        return u;
    }

    private ProcessSheetRowRequest baseReq(String clientRowId, String processCode,
                                           int order, String output) {
        ProcessSheetRowRequest r = new ProcessSheetRowRequest();
        r.setClientRowId(clientRowId);
        r.setProcessCode(processCode);
        r.setProcessOrder(order);
        r.setProcessName(processCode);
        r.setProductTypeId(PRODUCT_TYPE_ID);
        r.setFinished(false);
        r.setOutputQuantity(new BigDecimal(output));
        r.setUnit("kg");
        return r;
    }

    /** Saves a xiuyou-style row and returns its result (materialized WIP). */
    private ProcessSheetRowResult saveXiuyou(String clientRowId, String rawQty, String output) {
        ProcessSheetRowRequest req = baseReq(clientRowId, "xiuyou", 1, output);
        req.setInputQuantity(new BigDecimal(rawQty));
        req.setRawMaterialInputs(List.of(rawInput(rawBatchId, rawQty)));
        return processSheetService.saveRow(FACTORY_ID, planId, req, operatorId);
    }

    // ─────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1: 修油行 → RAW consumption + CLERK_WIP 批, materialTypeId 来自原料")
    void saveRow_xiuyou_writesRawConsumptionAndWipBatch() {
        ProcessSheetRowResult result = saveXiuyou("row-xiuyou-1", "100", "80");

        assertThat(result.isMaterialized()).as("output>0 → materialized").isTrue();
        assertThat(result.isUpdated()).as("new create → updated=false").isFalse();
        assertThat(result.getBatchNumber()).as("batchNumber 系统生成").isNotBlank();
        assertThat(result.getBatchId()).isNotNull();

        // ProductionBatch is CLERK_WIP (planId=null on WIP)
        ProductionBatch pb = batchRepo.findByIdAndFactoryId(result.getBatchId(), FACTORY_ID).orElseThrow();
        assertThat(pb.getBatchType()).as("WIP → CLERK_WIP").isEqualTo("CLERK_WIP");
        assertThat(pb.getProductionPlanId()).as("WIP planId=null (avoid double-count)").isNull();

        // RAW MaterialConsumption on the new batch
        List<MaterialConsumption> cons = consumptionRepo.findByProductionBatchId(result.getBatchId());
        assertThat(cons).hasSize(1);
        MaterialConsumption c = cons.get(0);
        assertThat(c.getSourceType()).isEqualTo("RAW_MATERIAL");
        assertThat(c.getBatchId()).as("consumes the raw batch").isEqualTo(rawBatchId);
        assertThat(c.getQuantity()).isEqualByComparingTo("100");
        // 100kg × ¥10 = ¥1000
        assertThat(c.getTotalCost()).isEqualByComparingTo("1000.00");

        // WIP MaterialBatch via findByFactoryIdAndSourceDocTypeAndSourceDocId
        MaterialBatch wip = materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY_ID, "PRODUCTION_BATCH", String.valueOf(result.getBatchId())).orElseThrow();
        assertThat(wip.getMaterialTypeId())
                .as("WIP materialTypeId == raw's materialTypeId (SP-E FK safety)")
                .isEqualTo(RAW_MATERIAL_TYPE_ID);
        assertThat(wip.getReceiptQuantity()).isEqualByComparingTo("80");
        // unitPrice = rowTotalCost / output = 1000 / 80 = 12.5
        assertThat(wip.getUnitPrice()).isEqualByComparingTo("12.5");

        // yieldRate = 80/100*100 = 80
        assertThat(result.getYieldRate()).isEqualByComparingTo("80");
        assertThat(result.getRowTotalCost()).isEqualByComparingTo("1000.00");
        assertThat(result.getUnitPrice()).isEqualByComparingTo("12.5");
    }

    @Test
    @DisplayName("2: 熟制混锅 2 上游 + seasoningStep → 2 SEMI edges + SEASONING 报工, materialTypeId 来自上游 WIP")
    void saveRow_shuzhi_mixedPots_writesSemiEdgesAndSeasoning() {
        // Two upstream 修油 WIP batches
        ProcessSheetRowResult up1 = saveXiuyou("row-x1", "60", "50");
        ProcessSheetRowResult up2 = saveXiuyou("row-x2", "60", "50");

        // Recipe so seasoning cost > 0 (ACTIVE for PRODUCT_TYPE_ID)
        seedSeasoningRecipe();

        // 熟制 row: 2 upstreamSources by their batchNumbers, seasoningStep=true
        ProcessSheetRowRequest req = baseReq("row-shuzhi-1", "shuzhi", 3, "90");
        req.setInputQuantity(new BigDecimal("100"));
        req.setSeasoningStep(true);
        req.setPotCount(1);
        req.setUpstreamSources(List.of(
                upstreamRef(up1.getBatchNumber(), "50"),
                upstreamRef(up2.getBatchNumber(), "50")
        ));

        ProcessSheetRowResult result = processSheetService.saveRow(FACTORY_ID, planId, req, operatorId);

        assertThat(result.isMaterialized()).isTrue();

        // 2 SEMI_FINISHED MaterialConsumption edges
        List<MaterialConsumption> cons = consumptionRepo.findByProductionBatchId(result.getBatchId());
        List<MaterialConsumption> semi = cons.stream()
                .filter(c -> "SEMI_FINISHED".equals(c.getSourceType()))
                .toList();
        assertThat(semi).as("2 SEMI edges (混锅 2 来源)").hasSize(2);
        // each: 50kg × upstream unitPrice (¥12 = 600/50) = ¥600
        BigDecimal semiTotal = semi.stream()
                .map(MaterialConsumption::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // up unitPrice = 600/50 = 12; 50×12 × 2 = 1200
        assertThat(semiTotal).isEqualByComparingTo("1200.00");

        // SEASONING ProductionReport on the cooked batch
        List<ProductionReport> yields = reportRepo.findYieldReportsByBatch(FACTORY_ID, result.getBatchId());
        assertThat(yields)
                .as("SEASONING report present")
                .anyMatch(r -> "SEASONING".equals(r.getCostCategory())
                        && r.getMaterialCost() != null
                        && r.getMaterialCost().signum() > 0);

        // materialTypeId derived from an upstream WIP (no rawInputs on shuzhi)
        MaterialBatch cookedWip = materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY_ID, "PRODUCTION_BATCH", String.valueOf(result.getBatchId())).orElseThrow();
        assertThat(cookedWip.getMaterialTypeId())
                .as("materialTypeId from upstream WIP == raw's (SP-E FK safety)")
                .isEqualTo(RAW_MATERIAL_TYPE_ID);
    }

    // Test 3 (null-lineage → 400) lives in ProcessSheetServiceImplNullLineageTest (Mockito):
    // material_batches.material_type_id is NOT NULL (entity @Column nullable=false) so a
    // null-materialTypeId upstream WIP cannot be persisted in H2 — the SP-E guard is exercised
    // with a mocked repo returning a null-materialTypeId batch instead.

    @Test
    @DisplayName("4: output<=0 → DRAFT 行不物化")
    void saveRow_outputZero_savesDraftNoMaterialize() {
        ProcessSheetRowRequest req = baseReq("row-draft", "xiuyou", 1, "0");
        req.setInputQuantity(new BigDecimal("100"));
        req.setRawMaterialInputs(List.of(rawInput(rawBatchId, "100")));

        ProcessSheetRowResult result = processSheetService.saveRow(FACTORY_ID, planId, req, operatorId);

        assertThat(result.isMaterialized()).as("output<=0 → not materialized").isFalse();
        assertThat(result.getBatchId()).isNull();
        assertThat(result.getBatchNumber()).isNull();
        // No ProductionBatch created for this row → no consumption rows for a non-existent batch.
    }

    @Test
    @DisplayName("5a: 跨租户 planId → 403")
    void saveRow_crossTenantPlan_throws403() {
        // A plan belonging to OTHER_FACTORY_ID
        String otherPlanId = "PSF-OTHERPLAN-" + UUID.randomUUID().toString().substring(0, 8);
        ProductionPlan op = new ProductionPlan();
        op.setId(otherPlanId);
        op.setFactoryId(OTHER_FACTORY_ID);
        op.setPlanNumber("PSF-OPN-" + System.currentTimeMillis() % 100000);
        op.setProductTypeId(PRODUCT_TYPE_ID);
        op.setPlannedQuantity(new BigDecimal("100"));
        op.setStatus(ProductionPlanStatus.PENDING);
        op.setCreatedBy(operatorId);
        op.setIsLocked(false);
        op.setSkipProcessReporting(false);
        op.setCreatedAt(LocalDateTime.now());
        op.setUpdatedAt(LocalDateTime.now());
        planRepo.saveAndFlush(op);

        ProcessSheetRowRequest req = baseReq("row-x", "xiuyou", 1, "80");
        req.setInputQuantity(new BigDecimal("100"));
        req.setRawMaterialInputs(List.of(rawInput(rawBatchId, "100")));

        // FACTORY_ID user accessing OTHER_FACTORY_ID's plan → 403
        assertThatThrownBy(() -> processSheetService.saveRow(FACTORY_ID, otherPlanId, req, operatorId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
    }

    @Test
    @DisplayName("5b: 未知上游 batchNumber → 409")
    void saveRow_unknownUpstreamBatch_throws409() {
        ProcessSheetRowRequest req = baseReq("row-unknownup", "shuzhi", 3, "40");
        req.setInputQuantity(new BigDecimal("50"));
        req.setUpstreamSources(List.of(upstreamRef("DOES-NOT-EXIST-BN", "50")));

        assertThatThrownBy(() -> processSheetService.saveRow(FACTORY_ID, planId, req, operatorId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(409));
    }

    // ─────────────────────────────────────────────────────────────
    // SP-F Task 1.6 — re-save (update-in-place) path
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("6: re-save 无下游 → 原地更新保 id, 旧边软删, WIP 同 id 量价刷新")
    void resave_noDownstream_updatesInPlacePreservingIds() {
        // 初次保存 修油 output=80
        ProcessSheetRowResult first = saveXiuyou("row-resave", "100", "80");
        Long originalBatchId = first.getBatchId();
        String originalBatchNumber = first.getBatchNumber();
        assertThat(originalBatchId).isNotNull();

        MaterialBatch wipBefore = materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY_ID, "PRODUCTION_BATCH", String.valueOf(originalBatchId)).orElseThrow();
        String originalWipId = wipBefore.getId();
        assertThat(wipBefore.getReceiptQuantity()).isEqualByComparingTo("80");

        // re-save 同 clientRowId, output=90, 无下游
        ProcessSheetRowResult second = saveXiuyou("row-resave", "100", "90");

        assertThat(second.isUpdated()).as("re-save → updated=true").isTrue();
        assertThat(second.isMaterialized()).isTrue();
        assertThat(second.getBatchId()).as("batchId 保留不变").isEqualTo(originalBatchId);
        assertThat(second.getBatchNumber()).as("batchNumber 保留不变").isEqualTo(originalBatchNumber);

        // 恰好一条 active RAW consumption (旧的已软删, @Where deleted_at IS NULL 排除)
        List<MaterialConsumption> active = consumptionRepo.findByProductionBatchId(originalBatchId);
        assertThat(active).as("旧边软删后只剩 1 条 active").hasSize(1);
        assertThat(active.get(0).getSourceType()).isEqualTo("RAW_MATERIAL");
        // 新边量 100kg × ¥10 = ¥1000 (input 不变)
        assertThat(active.get(0).getTotalCost()).isEqualByComparingTo("1000.00");

        // WIP 同 id, receiptQuantity 刷为 90, unitPrice 重算 (1000/90 = 11.1111)
        MaterialBatch wipAfter = materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY_ID, "PRODUCTION_BATCH", String.valueOf(originalBatchId)).orElseThrow();
        assertThat(wipAfter.getId()).as("WIP id 保留不变").isEqualTo(originalWipId);
        assertThat(wipAfter.getReceiptQuantity()).isEqualByComparingTo("90");
        assertThat(wipAfter.getUnitPrice()).isEqualByComparingTo("11.1111");

        // ProductionBatch.quantity 刷为 90
        ProductionBatch pb = batchRepo.findByIdAndFactoryId(originalBatchId, FACTORY_ID).orElseThrow();
        assertThat(pb.getQuantity()).isEqualByComparingTo("90");
    }

    @Test
    @DisplayName("7: re-save 已被下游消耗 → 409")
    void resave_withDownstreamConsumed_throws409() {
        // 上游 修油 batch
        ProcessSheetRowResult up = saveXiuyou("row-up", "60", "50");

        // 下游 熟制 消耗它
        seedSeasoningRecipe();
        ProcessSheetRowRequest down = baseReq("row-down", "shuzhi", 3, "45");
        down.setInputQuantity(new BigDecimal("50"));
        down.setSeasoningStep(true);
        down.setPotCount(1);
        down.setUpstreamSources(List.of(upstreamRef(up.getBatchNumber(), "50")));
        processSheetService.saveRow(FACTORY_ID, planId, down, operatorId);

        // re-save 上游行 → 409 (已被下游消耗)
        ProcessSheetRowRequest upResave = baseReq("row-up", "xiuyou", 1, "55");
        upResave.setInputQuantity(new BigDecimal("60"));
        upResave.setRawMaterialInputs(List.of(rawInput(rawBatchId, "60")));

        assertThatThrownBy(() -> processSheetService.saveRow(FACTORY_ID, planId, upResave, operatorId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(409);
                    assertThat(ex.getMessage()).contains("已被下游");
                });
    }

    @Test
    @DisplayName("8: DRAFT 行 re-save 带产出 → 物化 (batchId 由 null 变非空)")
    void resave_draftThenMaterialize() {
        // 初次 output=0 → DRAFT
        ProcessSheetRowResult draft = saveXiuyou("row-draft-mat", "100", "0");
        assertThat(draft.getBatchId()).isNull();
        assertThat(draft.isMaterialized()).isFalse();

        // re-save 同 clientRowId, output=50 → 物化
        ProcessSheetRowResult materialized = saveXiuyou("row-draft-mat", "100", "50");

        assertThat(materialized.isUpdated()).isTrue();
        assertThat(materialized.isMaterialized()).as("DRAFT → 物化").isTrue();
        assertThat(materialized.getBatchId()).as("batchId 现已设置").isNotNull();

        // 行状态 SAVED, batchId 落库
        ProcessSheetRow row = rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
                FACTORY_ID, planId, "xiuyou", "row-draft-mat").orElseThrow();
        assertThat(row.getRowStatus()).isEqualTo("SAVED");
        assertThat(row.getBatchId()).isEqualTo(materialized.getBatchId());

        // WIP 批已物化, receiptQuantity=50
        MaterialBatch wip = materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY_ID, "PRODUCTION_BATCH", String.valueOf(materialized.getBatchId())).orElseThrow();
        assertThat(wip.getReceiptQuantity()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("9: 已物化行 re-save output=0 无下游 → 逆向为 DRAFT (软删 WIP/批)")
    void resave_materializedToZero_reversesToDraft() {
        // 初次 output=80 → 物化
        ProcessSheetRowResult mat = saveXiuyou("row-reverse", "100", "80");
        Long batchId = mat.getBatchId();
        assertThat(batchId).isNotNull();

        // re-save output=0 无下游 → 逆向 DRAFT
        ProcessSheetRowResult reversed = saveXiuyou("row-reverse", "100", "0");

        assertThat(reversed.isUpdated()).isTrue();
        assertThat(reversed.isMaterialized()).as("逆向 → 不再物化").isFalse();
        assertThat(reversed.getBatchId()).as("batchId 置 null").isNull();

        // 行 batchId null, status DRAFT
        ProcessSheetRow row = rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
                FACTORY_ID, planId, "xiuyou", "row-reverse").orElseThrow();
        assertThat(row.getBatchId()).isNull();
        assertThat(row.getRowStatus()).isEqualTo("DRAFT");

        // WIP MaterialBatch 软删 (@Where deleted_at IS NULL → 查询排除)
        assertThat(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY_ID, "PRODUCTION_BATCH", String.valueOf(batchId)))
                .as("WIP 软删后查不到").isEmpty();

        // ProductionBatch 软删 (@Where → findByIdAndFactoryId 排除)
        assertThat(batchRepo.findByIdAndFactoryId(batchId, FACTORY_ID))
                .as("ProductionBatch 软删后查不到").isEmpty();

        // 旧边软删 → 0 条 active consumption
        assertThat(consumptionRepo.findByProductionBatchId(batchId))
                .as("旧消耗边软删").isEmpty();
    }

    // ─────────────────────────────────────────────────────────────
    // SP-F Task 1.8 — deleteRow
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("10: deleteRow 已物化无下游 → 软删行 + WIP + ProductionBatch + 消耗边")
    void deleteRow_materializedNoDownstream_softDeletesAll() {
        // 创建物化行
        ProcessSheetRowResult mat = saveXiuyou("row-del-mat", "100", "80");
        Long batchId = mat.getBatchId();
        assertThat(batchId).isNotNull();

        // 确认 WIP 存在
        assertThat(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY_ID, "PRODUCTION_BATCH", String.valueOf(batchId))).isPresent();

        // 删除
        processSheetService.deleteRow(FACTORY_ID, planId, "row-del-mat", operatorId);

        // 行已软删 (@Where deleted_at IS NULL → 查不到)
        assertThat(rowRepo.findByFactoryIdAndPlanIdAndClientRowId(FACTORY_ID, planId, "row-del-mat"))
                .as("行已软删").isEmpty();

        // WIP MaterialBatch 软删
        assertThat(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY_ID, "PRODUCTION_BATCH", String.valueOf(batchId)))
                .as("WIP 软删后查不到").isEmpty();

        // ProductionBatch 软删
        assertThat(batchRepo.findByIdAndFactoryId(batchId, FACTORY_ID))
                .as("ProductionBatch 软删后查不到").isEmpty();

        // 消耗边 0 条 active
        assertThat(consumptionRepo.findByProductionBatchId(batchId))
                .as("消耗边全软删").isEmpty();
    }

    @Test
    @DisplayName("11: deleteRow 已被下游消耗 → 409 含 '已被下游'")
    void deleteRow_withDownstreamConsumed_throws409() {
        // 上游 修油 batch
        ProcessSheetRowResult up = saveXiuyou("row-del-up", "60", "50");

        // 下游 熟制 消耗它
        seedSeasoningRecipe();
        ProcessSheetRowRequest down = baseReq("row-del-down", "shuzhi", 3, "45");
        down.setInputQuantity(new BigDecimal("50"));
        down.setSeasoningStep(true);
        down.setPotCount(1);
        down.setUpstreamSources(List.of(upstreamRef(up.getBatchNumber(), "50")));
        processSheetService.saveRow(FACTORY_ID, planId, down, operatorId);

        // 删除上游行 → 409 (已被下游消耗)
        assertThatThrownBy(() -> processSheetService.deleteRow(FACTORY_ID, planId, "row-del-up", operatorId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(409);
                    assertThat(ex.getMessage()).contains("已被下游");
                });
    }

    @Test
    @DisplayName("12: deleteRow DRAFT 行 (batchId null) → 仅软删行, 无批次操作")
    void deleteRow_draftRow_removesRowOnly() {
        // output=0 → DRAFT
        ProcessSheetRowRequest req = baseReq("row-del-draft", "xiuyou", 1, "0");
        req.setInputQuantity(new BigDecimal("100"));
        req.setRawMaterialInputs(List.of(rawInput(rawBatchId, "100")));
        ProcessSheetRowResult draft = processSheetService.saveRow(FACTORY_ID, planId, req, operatorId);
        assertThat(draft.getBatchId()).isNull();

        // 删除
        processSheetService.deleteRow(FACTORY_ID, planId, "row-del-draft", operatorId);

        // 行已软删
        assertThat(rowRepo.findByFactoryIdAndPlanIdAndClientRowId(FACTORY_ID, planId, "row-del-draft"))
                .as("DRAFT 行已软删").isEmpty();
        // 无 ProductionBatch / WIP 创建, 断言 consumption 列表也为空 (无 batchId 可查)
    }

    @Test
    @DisplayName("13: deleteRow 不存在的行 → 404")
    void deleteRow_notFound_throws404() {
        assertThatThrownBy(() ->
                processSheetService.deleteRow(FACTORY_ID, planId, "DOES-NOT-EXIST", operatorId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(404));
    }

    // ─────────────────────────────────────────────────────────────
    // SP-G P3: 行级操作记录 (字段级 diff 审计)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SP-G P3: saveRow 首存 → 1 条 CREATE 操作记录")
    void saveRow_create_writesCreateChangeLog() {
        saveXiuyou("row-log-create", "100", "80");

        var history = processSheetService.getRowHistory(
                FACTORY_ID, planId, "xiuyou", "row-log-create");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getOperation()).isEqualTo("CREATE");
        assertThat(history.get(0).getDiffSummary()).isEqualTo("新建行");
        assertThat(history.get(0).getBeforeValue()).as("CREATE before 为 null").isNull();
        assertThat(history.get(0).getAfterValue()).as("CREATE after 含字段快照").isNotNull();
        assertThat(history.get(0).getOperatorId()).isEqualTo(operatorId);
    }

    @Test
    @DisplayName("SP-G P3: saveRow 覆盖已存行 → 追加 1 条 UPDATE, diffSummary 含变更字段")
    void resaveRow_update_writesUpdateChangeLogWithChangedField() {
        // 首存 output=80, 再覆盖 output=90 (同 clientRowId → resave UPDATE)
        saveXiuyou("row-log-update", "100", "80");
        saveXiuyou("row-log-update", "100", "90");

        var history = processSheetService.getRowHistory(
                FACTORY_ID, planId, "xiuyou", "row-log-update");
        // 倒序: [0]=UPDATE (最新), [1]=CREATE
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getOperation()).isEqualTo("UPDATE");
        assertThat(history.get(1).getOperation()).isEqualTo("CREATE");

        // UPDATE diffSummary 应含变更的产出字段 (outputQuantity: 80→90)
        assertThat(history.get(0).getDiffSummary())
                .as("diffSummary 列出变更字段").contains("outputQuantity");
        assertThat(history.get(0).getBeforeValue()).as("UPDATE before 非 null").isNotNull();
        assertThat(history.get(0).getAfterValue()).as("UPDATE after 非 null").isNotNull();
    }

    @Test
    @DisplayName("SP-G P3: deleteRow → 追加 1 条 DELETE 操作记录 (before=被删行, after=null)")
    void deleteRow_writesDeleteChangeLog() {
        saveXiuyou("row-log-delete", "100", "80");
        processSheetService.deleteRow(FACTORY_ID, planId, "row-log-delete", operatorId);

        var history = processSheetService.getRowHistory(
                FACTORY_ID, planId, "xiuyou", "row-log-delete");
        // [0]=DELETE (最新), [1]=CREATE
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getOperation()).isEqualTo("DELETE");
        assertThat(history.get(0).getDiffSummary()).isEqualTo("删除行");
        assertThat(history.get(0).getBeforeValue()).as("DELETE before 含被删行快照").isNotNull();
        assertThat(history.get(0).getAfterValue()).as("DELETE after 为 null").isNull();
    }

    // ─────────────────────────────────────────────────────────────
    // Seasoning recipe seed
    // ─────────────────────────────────────────────────────────────

    private void seedSeasoningRecipe() {
        String recipeId = "PSF-RECIPE-" + UUID.randomUUID().toString().substring(0, 8);
        ProductRecipe recipe = new ProductRecipe();
        recipe.setId(recipeId);
        recipe.setFactoryId(FACTORY_ID);
        recipe.setProductTypeId(PRODUCT_TYPE_ID);
        recipe.setName("PSF-Test-Recipe");
        recipe.setInjectionRate(null);
        recipe.setCookingPotBaseKg(new BigDecimal("100"));
        recipe.setSubsequentPotRatio(ProductRecipe.DEFAULT_SUBSEQUENT_POT_RATIO);
        recipe.setStatus("ACTIVE");
        recipeRepo.save(recipe);

        RecipeIngredient cook = new RecipeIngredient();
        cook.setRecipeId(recipeId);
        cook.setFactoryId(FACTORY_ID);
        cook.setSection(RecipeIngredient.SECTION_COOKING);
        cook.setSeq(1);
        cook.setName("PSF-Cooking-Spice");
        cook.setDosagePerKgG(new BigDecimal("310"));
        cook.setPriceSource1(new BigDecimal("10.0"));
        cook.setCountInSeasoning(true);
        ingredientRepo.save(cook);
    }
}
