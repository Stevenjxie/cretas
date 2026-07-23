package com.cretas.aims.service.yield;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.processentry.ProcessSheetService;
import com.cretas.aims.service.yield.InterimSettleService;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 🔒 小结原料扣减 幻库存 bug 回归集成测试 (2026-07-02).
 *
 * <p><b>Bug</b>: 逐工序首/中间道 (finished=false) 消耗原料时, 真实写入的 {@link MaterialConsumption}
 * 其 {@code production_plan_id} <b>故意为 null</b> (防 OrderCostBreakdownService 按 plan-scoped SUM
 * 双计在制 WIP 原料), 但小结原料扣减循环原按 {@code production_plan_id = planId} 定位待扣减消耗 →
 * 漏掉这些 null-plan 在制道消耗 → 扣减循环从不执行 → 原料 {@code usedQuantity} 零扣减:
 * 小结把半成品/成品入库, 却不减任何原料 = production creates stock from nothing (幻库存).
 * (LIUSHANMEN prod: 小结入 4115.5kg SFI, 原料批 used_quantity 停在 0.)
 *
 * <p><b>Fix</b>: 扣减侧改按 {@code (factory, production_batch_id ∈ 本计划各道 process_sheet_rows.batch_id)}
 * 定位 — 消耗行的 {@code production_batch_id} (per-道 WIP ProductionBatch id) 恒有值, 故能找到 null-plan
 * 消耗。<b>不改写入路径</b> (production_plan_id 仍为 null → 成本逻辑完全不动)。
 *
 * <p><b>为什么是集成测试而非 mock</b>: 掩盖此 bug 的正是一个 mock 单测 —— 它 hand-set 消耗的
 * {@code productionPlanId = PLAN_ID}, 让旧查询能找到。本测试走<b>真实写入路径</b>
 * ({@link ProcessSheetService#saveRow} → materializeBatch → writeConsumption, 由生产代码决定
 * production_plan_id = null), 故能真正复现并守住修复: 若回退 fix, 本测试的 usedQuantity 断言会失败.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("InterimSettleRawDeductionIntegrationTest - 🔒 小结原料扣减幻库存回归")
class InterimSettleRawDeductionIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProcessSheetService processSheetService;

    @Autowired
    private InterimSettleService interimSettleService;

    @Autowired
    private MaterialBatchRepository materialBatchRepo;

    @Autowired
    private MaterialConsumptionRepository consumptionRepo;

    @Autowired
    private ProductionPlanRepository planRepo;

    @Autowired
    private ProductTypeRepository productTypeRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private FactoryWarehouseRepository factoryWarehouseRepo;

    private static final String FACTORY_ID = "IT-SETTLE-FACTORY";
    private static final String PRODUCT_TYPE_ID = "IT-SETTLE-PTYPE";
    private static final BigDecimal RAW_PRICE = new BigDecimal("50.00");
    private static final BigDecimal CONSUMED = new BigDecimal("200"); // 首道领料量 kg

    private Long operatorId;
    private String planId;
    private String rawBatchId;
    private String rawWarehouseId;

    @BeforeEach
    void setUp() {
        // 关 H2 FK 约束: 允许只 seed 必需 fixture (不建全依赖树)
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");

        User user = new User();
        user.setFactoryId(FACTORY_ID);
        user.setUsername("it_settle_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("$2a$10$DUMMYHASHFORTESTPLACEHOLDERONLY1");
        user.setIsActive(true);
        user = userRepo.saveAndFlush(user);
        operatorId = user.getId();

        ProductType productType = new ProductType();
        productType.setId(PRODUCT_TYPE_ID);
        productType.setFactoryId(FACTORY_ID);
        productType.setCode("IT-SETTLE-PTYPE");
        productType.setName("IT Settle WIP");
        productType.setCategory("SEMI_FINISHED");
        productType.setProductCategory("SEMI_FINISHED");
        productType.setUnit("kg");
        productType.setIsActive(true);
        productType.setCreatedBy(operatorId);
        productTypeRepo.saveAndFlush(productType);

        // 原料仓 (WH-LOG): ProcessSheetService.ensureRawMaterialWarehouse 要求原料批次落原料/物流仓。
        rawWarehouseId = "WH-" + UUID.randomUUID().toString().substring(0, 8);
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId(rawWarehouseId);
        wh.setFactoryId(FACTORY_ID);
        wh.setCode("WH-LOG");
        wh.setName("IT 物流仓");
        wh.setType(FactoryWarehouse.WarehouseType.LOGISTICS);
        wh.setIsActive(true);
        factoryWarehouseRepo.saveAndFlush(wh);

        // 存货生产计划 (SAFETY_STOCK — 仅此类可小结)
        planId = "IT-SPLAN-" + UUID.randomUUID().toString().substring(0, 8);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("IT-SPP-" + System.currentTimeMillis() % 100000);
        plan.setProductTypeId(PRODUCT_TYPE_ID);
        plan.setPlannedQuantity(new BigDecimal("1000"));
        plan.setPlannedUnit("kg");
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setSourceType(PlanSourceType.SAFETY_STOCK);
        plan.setCreatedBy(operatorId);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        planRepo.save(plan);

        // 原料批次: 1000kg @ ¥50, usedQuantity 0, 落原料仓
        rawBatchId = "IT-RAW-" + UUID.randomUUID().toString().substring(0, 8);
        MaterialBatch raw = new MaterialBatch();
        raw.setId(rawBatchId);
        raw.setFactoryId(FACTORY_ID);
        raw.setBatchNumber("IT-RAWNO-" + System.currentTimeMillis() % 100000);
        raw.setMaterialTypeId("IT-RAWTYPE-1");
        raw.setWarehouseId(rawWarehouseId);
        raw.setReceiptQuantity(new BigDecimal("1000.00"));
        raw.setQuantityUnit("kg");
        raw.setUsedQuantity(BigDecimal.ZERO);
        raw.setReservedQuantity(BigDecimal.ZERO);
        raw.setUnitPrice(RAW_PRICE);
        raw.setStatus(MaterialBatchStatus.AVAILABLE);
        raw.setReceiptDate(LocalDate.now());
        raw.setCreatedBy(operatorId);
        materialBatchRepo.saveAndFlush(raw);
    }

    @Test
    @DisplayName("逐工序首道消耗原料(plan_id=null) → 小结扣减原料 usedQuantity + 打戳 + 幂等 + 成本写路径不变")
    void interimSettleDeductsNullPlanRawConsumption() {
        // ── 真实写路径: 逐工序首道 (finished=false) 领料 200kg ──
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("row-1");
        req.setProcessCode("chaoshui");
        req.setProcessOrder(1);
        req.setProcessName("焯水");
        req.setProductTypeId(PRODUCT_TYPE_ID);
        req.setFinished(false);
        req.setInputQuantity(CONSUMED);
        req.setOutputQuantity(new BigDecimal("150")); // >0 → 物化 WIP 批
        req.setUnit("kg");
        ProcessSheetRowRequest.RawInput ri = new ProcessSheetRowRequest.RawInput();
        ri.setMaterialBatchId(rawBatchId);
        ri.setQuantity(CONSUMED);
        req.setRawMaterialInputs(List.of(ri));

        ProcessSheetRowResult saved = processSheetService.saveRow(FACTORY_ID, planId, req, operatorId);
        assertThat(saved.getBatchNumber()).as("首道应物化 WIP 批 (batchNumber 非空)").isNotBlank();

        // ── 验证真实写路径的关键前提: 消耗 production_plan_id = null 但 production_batch_id 有值 ──
        List<MaterialConsumption> written = consumptionRepo.findByBatchId(rawBatchId);
        assertThat(written).as("首道应写 1 条原料消耗").hasSize(1);
        MaterialConsumption mc = written.get(0);
        assertThat(mc.getProductionPlanId())
                .as("🔴 真实写路径: 非成品道原料消耗 production_plan_id 故意为 null (防成本双计)")
                .isNull();
        assertThat(mc.getProductionBatchId())
                .as("production_batch_id (per-道 WIP 批 id) 恒有值 — 扣减改按它定位")
                .isNotNull();
        assertThat(mc.getQuantity()).isEqualByComparingTo(CONSUMED);
        assertThat(mc.getInterimSettledAt()).as("小结前未打戳").isNull();

        // 小结前原料未扣减
        assertThat(reloadRaw().getUsedQuantity())
                .as("小结前 usedQuantity 仍为 0 (扣减只在小结发生)")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // ── 小结 #1 ──
        Map<String, Object> s1 = interimSettleService.interimSettle(FACTORY_ID, planId, operatorId);

        // 🔴 核心断言: 原料 usedQuantity 增加了消耗量 (bug 时为 0 → 幻库存)
        assertThat(reloadRaw().getUsedQuantity())
                .as("🔴 修复: 小结扣减 null-plan 在制道原料 → usedQuantity 0 → 200")
                .isEqualByComparingTo(CONSUMED);
        // 消耗行已打戳 (扣减侧幂等标记)
        assertThat(reloadConsumption().getInterimSettledAt()).as("消耗行小结后已打戳").isNotNull();
        // summary 记录扣减
        assertThat(s1.get("deductedConsumptionCount")).isEqualTo(1);
        assertThat((BigDecimal) s1.get("deductedQuantity")).isEqualByComparingTo(CONSUMED);

        // 🔴 写路径未变 → 成本逻辑不动: production_plan_id 小结后仍为 null (OrderCostBreakdownService
        //   plan-scoped SUM 看不到本行 → 不会双计 WIP 原料成本)。
        assertThat(reloadConsumption().getProductionPlanId())
                .as("🔴 小结不改消耗写路径: production_plan_id 仍 null (成本 SUM 口径不变, 无双计回归)")
                .isNull();

        // ── 小结 #2 (重复点击, 幂等): 不再二次扣减 ──
        Map<String, Object> s2 = interimSettleService.interimSettle(FACTORY_ID, planId, operatorId);
        assertThat(s2.get("deductedConsumptionCount"))
                .as("再次小结: 该消耗已打戳 → 0 扣减 (幂等, 不双扣)")
                .isEqualTo(0);
        assertThat(reloadRaw().getUsedQuantity())
                .as("🔴 幂等: usedQuantity 仍 200 (不双扣至 400)")
                .isEqualByComparingTo(CONSUMED);
    }

    // ── MES↔ERP Fix #6: 报工超投 → 小结负库存守卫 (loud-fail, 不静默产幻库存) ──

    @Test
    @DisplayName("Fix#6: 报工投料 300kg > 批次可用 100kg (3×超投) → 小结 loud-fail 409 可用不足, usedQuantity 不被扣成负")
    void interimSettleGuardsAgainstNegativeStock_onOverConsumption() {
        // 小库存原料批次: 仅 100kg 可用 (落原料仓, 满足 ensureRawMaterialWarehouse)
        String smallBatchId = "IT-RAWSMALL-" + UUID.randomUUID().toString().substring(0, 8);
        MaterialBatch small = new MaterialBatch();
        small.setId(smallBatchId);
        small.setFactoryId(FACTORY_ID);
        small.setBatchNumber("IT-SMALLNO-" + System.currentTimeMillis() % 100000);
        small.setMaterialTypeId("IT-RAWTYPE-SMALL");
        small.setWarehouseId(rawWarehouseId);
        small.setReceiptQuantity(new BigDecimal("100.00"));
        small.setQuantityUnit("kg");
        small.setUsedQuantity(BigDecimal.ZERO);
        small.setReservedQuantity(BigDecimal.ZERO);
        small.setUnitPrice(RAW_PRICE);
        small.setStatus(MaterialBatchStatus.AVAILABLE);
        small.setReceiptDate(LocalDate.now());
        small.setCreatedBy(operatorId);
        materialBatchRepo.saveAndFlush(small);

        // 真实写路径: 首道报工投 300kg (3× 可用) — resolveEdges 不拦 (延迟扣减设计), 写下 300kg 消耗
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("row-over-1");
        req.setProcessCode("chaoshui");
        req.setProcessOrder(1);
        req.setProcessName("焯水");
        req.setProductTypeId(PRODUCT_TYPE_ID);
        req.setFinished(false);
        req.setInputQuantity(new BigDecimal("300"));
        req.setOutputQuantity(new BigDecimal("250"));
        req.setUnit("kg");
        ProcessSheetRowRequest.RawInput ri = new ProcessSheetRowRequest.RawInput();
        ri.setMaterialBatchId(smallBatchId);
        ri.setQuantity(new BigDecimal("300"));
        req.setRawMaterialInputs(List.of(ri));

        ProcessSheetRowResult saved = processSheetService.saveRow(FACTORY_ID, planId, req, operatorId);
        assertThat(saved.getBatchNumber()).as("报工写路径本身不拦超投 (延迟扣减设计)").isNotBlank();

        // 小结 → 累计扣减 300 > 可用 100 → currentQuantity 将成 -200 → loud-fail 409, 整事务回滚
        assertThatThrownBy(() -> interimSettleService.interimSettle(FACTORY_ID, planId, operatorId))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("可用不足");

        // 守卫在 setUsedQuantity 之前抛 → 批次 usedQuantity 未被扣成负 (无静默幻库存)
        assertThat(materialBatchRepo.findById(smallBatchId).orElseThrow().getCurrentQuantity())
                .as("🔴 负库存守卫: currentQuantity 不被扣成负")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    private MaterialBatch reloadRaw() {
        return materialBatchRepo.findById(rawBatchId).orElseThrow();
    }

    private MaterialConsumption reloadConsumption() {
        List<MaterialConsumption> list = consumptionRepo.findByBatchId(rawBatchId);
        assertThat(list).hasSize(1);
        return list.get(0);
    }
}
