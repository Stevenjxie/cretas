package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴🔒🔒 {@link MaterialConsumptionRepository#sumUnsettledConsumptionGroupedByBatch} 计划族门控回归
 * (bug fix 2026-07-04, #1216 ↔ #1217 姊妹流缺口).
 *
 * <p><b>Bug</b>: #1216 首版按 {@code interimSettledAt IS NULL AND quantity>0} 汇总"待小结"消耗, 被盘点
 * 从每个批次账面 (physical=receipt−used) 减去。但 {@code interimSettledAt IS NULL} 只对 SAFETY_STOCK
 * (存货生产) 计划代表"待小结、账面未扣"; 结单族 (MANUAL / CUSTOMER_ORDER / …) 走「结单」即时扣
 * {@code usedQuantity} 却<b>永不 stamp</b> → 其报工消耗行恒 {@code interimSettledAt IS NULL} 但早已落库扣减。
 * 无族门控地一并减去 → 结单族批次 physical(已扣 used) 被二次减同一消耗 → 账面虚低 → 仓管数到实物 →
 * 假盘盈 + 假 借1403/贷6301 凭证 → 幻库存累积 (每次盘点腐蚀财务)。
 *
 * <p><b>Fix</b>: SUM 仅计入<b>真 SAFETY_STOCK 待小结</b>消耗, 门控经 {@code mc.production_batch_id ∈
 * process_sheet_rows.batch_id 且该 row 的 plan_id 对应计划 sourceType=SAFETY_STOCK} —— 与
 * {@code InterimSettleServiceImpl §①} 定位待扣减行的<b>同一 key</b>, 故减除集 == 小结将扣减集。
 *
 * <p><b>为何不经 mc.production_plan_id / ProductionBatch.production_plan_id 门控</b>: 逐工序非末道消耗行
 * {@code mc.production_plan_id} 与其 WIP {@code ProductionBatch.production_plan_id} <b>均故意为 null</b>
 * (防成本双计, 仅末道/成品挂计划)。经二者门控会漏掉<b>真 SAFETY_STOCK 待小结</b>的非末道行 → 重开 #1216
 * 原盲区 (假盘亏)。故本测试特意让 SAFETY_STOCK 场景走"非末道 null-plan"形态, 若改回 ProductionBatch/planId
 * 门控则本测试 T1 会失败。honest-null-safe: 族未知 (无匹配 process_sheet_row / productionBatchId null) → 不减。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("MaterialConsumptionRepository 待小结族门控 (#1216↔#1217 姊妹流缺口)")
class MaterialConsumptionUnsettledFamilyGateTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MaterialConsumptionRepository consumptionRepo;
    @Autowired private ProcessSheetRowRepository rowRepo;
    @Autowired private ProductionPlanRepository planRepo;

    private static final String FACTORY_ID = "IT-FAMGATE-F";

    private String rawSafety;      // SAFETY_STOCK 待小结 (应减)
    private String rawCustomer;    // 结单族 CUSTOMER_ORDER (不应减)
    private String rawManual;      // 结单族 MANUAL (不应减)
    private String rawOrphan;      // 无匹配 process_sheet_row (族未知, 不应减)

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");

        // ── SAFETY_STOCK 计划 + 一道 process_sheet_row (batchId=WIP 批 id) ──
        String safetyPlanId = seedPlan(PlanSourceType.SAFETY_STOCK);
        long pbSafety = 900001L;
        seedRow(safetyPlanId, pbSafety);

        // ── 结单族: CUSTOMER_ORDER + MANUAL 各一计划一道 ──
        String custPlanId = seedPlan(PlanSourceType.CUSTOMER_ORDER);
        long pbCust = 900002L;
        seedRow(custPlanId, pbCust);

        String manualPlanId = seedPlan(PlanSourceType.MANUAL);
        long pbManual = 900003L;
        seedRow(manualPlanId, pbManual);

        // 孤儿: pbOrphan 不出现在任何 process_sheet_row
        long pbOrphan = 900999L;

        rawSafety = "RAW-SAFE-" + UUID.randomUUID().toString().substring(0, 8);
        rawCustomer = "RAW-CUST-" + UUID.randomUUID().toString().substring(0, 8);
        rawManual = "RAW-MAN-" + UUID.randomUUID().toString().substring(0, 8);
        rawOrphan = "RAW-ORPH-" + UUID.randomUUID().toString().substring(0, 8);

        // SAFETY_STOCK 待小结: 6kg, plan_id 故意 null (逐工序非末道形态), production_batch_id=pbSafety
        seedConsumption(rawSafety, pbSafety, new BigDecimal("6.00"), null, null);
        // + 同批次第二笔 4kg → 验证 SUM 聚合 (总应减 10)
        seedConsumption(rawSafety, pbSafety, new BigDecimal("4.00"), null, null);

        // 结单族 CUSTOMER_ORDER: 60kg 已经结单扣减但 interim_settled_at 仍 null (不应再减)
        seedConsumption(rawCustomer, pbCust, new BigDecimal("60.00"), null, null);
        // 结单族 MANUAL: 30kg (不应减)
        seedConsumption(rawManual, pbManual, new BigDecimal("30.00"), null, null);

        // 孤儿 (无 process_sheet_row 匹配): 99kg (族未知 → 不应减, honest-null-safe)
        seedConsumption(rawOrphan, pbOrphan, new BigDecimal("99.00"), null, null);

        // 干扰行 (继承既有谓词, 必须排除):
        //   已结 SAFETY_STOCK 行 (interim_settled_at 非空) → 不计
        seedConsumption(rawSafety, pbSafety, new BigDecimal("5.00"), null, LocalDateTime.now());
        //   退料负数留痕 (quantity<0) → 不计
        seedConsumption(rawSafety, pbSafety, new BigDecimal("-3.00"), null, null);
    }

    @Test
    @DisplayName("T1: SAFETY_STOCK 非末道 (plan_id=null) 待小结消耗仍被计入 (SUM 聚合=10; #1216 修复保留, 未回退到 ProductionBatch/planId 门控)")
    void safetyStock_unsettled_isSubtracted_evenWhenPlanIdNull() {
        Map<String, BigDecimal> byBatch = query(List.of(rawSafety, rawCustomer, rawManual, rawOrphan));
        // 6 + 4 = 10 (排除已结 5 + 负数 -3)
        assertThat(byBatch).containsKey(rawSafety);
        assertThat(byBatch.get(rawSafety)).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("T2: 结单族 (CUSTOMER_ORDER / MANUAL) 待小结-null 消耗<b>不</b>被计入 → 无二次减 → 无幻影盘盈")
    void settleFamily_isNotSubtracted() {
        Map<String, BigDecimal> byBatch = query(List.of(rawSafety, rawCustomer, rawManual, rawOrphan));
        assertThat(byBatch).doesNotContainKey(rawCustomer);
        assertThat(byBatch).doesNotContainKey(rawManual);
    }

    @Test
    @DisplayName("T3: 族未知 (productionBatchId 无匹配 process_sheet_row) → 不减 (honest-null-safe)")
    void unknownFamily_orphan_isNotSubtracted() {
        Map<String, BigDecimal> byBatch = query(List.of(rawSafety, rawCustomer, rawManual, rawOrphan));
        assertThat(byBatch).doesNotContainKey(rawOrphan);
    }

    @Test
    @DisplayName("T4: 混合仓 (SAFETY + 结单族 + 孤儿) 一次查询 → 只返回 SAFETY_STOCK 批次一条")
    void mixedWarehouse_onlySafetyStockReturned() {
        Map<String, BigDecimal> byBatch = query(List.of(rawSafety, rawCustomer, rawManual, rawOrphan));
        assertThat(byBatch).containsOnlyKeys(rawSafety);
    }

    // ── helpers ──

    private Map<String, BigDecimal> query(List<String> batchIds) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (Object[] row : consumptionRepo.sumUnsettledConsumptionGroupedByBatch(FACTORY_ID, batchIds)) {
            map.put((String) row[0], (BigDecimal) row[1]);
        }
        return map;
    }

    private String seedPlan(PlanSourceType sourceType) {
        String planId = "IT-FG-PLAN-" + UUID.randomUUID().toString().substring(0, 12);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("IT-FG-" + UUID.randomUUID().toString().substring(0, 8));
        plan.setProductTypeId("IT-FG-PT");
        plan.setPlannedQuantity(new BigDecimal("1000"));
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setSourceType(sourceType);
        plan.setCreatedBy(1L);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        planRepo.saveAndFlush(plan);
        return planId;
    }

    private void seedRow(String planId, long batchId) {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(FACTORY_ID);
        row.setPlanId(planId);
        row.setProcessCode("chaoshui");
        row.setProcessOrder(1);
        row.setClientRowId("row-" + batchId);
        row.setBatchId(batchId);
        row.setBatchNumber("WIP-" + batchId);
        row.setRowPayload("{}");
        row.setRowStatus("SAVED");
        rowRepo.saveAndFlush(row);
    }

    private void seedConsumption(String batchId, Long productionBatchId, BigDecimal qty,
                                 String productionPlanId, LocalDateTime interimSettledAt) {
        MaterialConsumption mc = new MaterialConsumption();
        mc.setFactoryId(FACTORY_ID);
        mc.setProductionPlanId(productionPlanId);
        mc.setProductionBatchId(productionBatchId);
        mc.setBatchId(batchId);
        mc.setQuantity(qty);
        mc.setUnitPrice(new BigDecimal("50.00"));
        mc.setTotalCost(qty.multiply(new BigDecimal("50.00")));
        mc.setConsumptionTime(LocalDateTime.now());
        mc.setRecordedBy(1L);
        mc.setInterimSettledAt(interimSettledAt);
        consumptionRepo.saveAndFlush(mc);
    }
}
