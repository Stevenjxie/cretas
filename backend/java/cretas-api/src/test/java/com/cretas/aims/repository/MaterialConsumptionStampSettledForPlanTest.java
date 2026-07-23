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
 * 🔴🔒🔒 {@link MaterialConsumptionRepository#stampInterimSettledForPlan} 结单族「扣减即打戳」根修回归
 * (bug fix 2026-07-04, #1216↔#1217 姊妹流的<b>根因闭合</b>).
 *
 * <p>验证结单族计划结单时给其报工消耗行盖 {@code interim_settled_at} 后:
 * <ul>
 *   <li>T1: 按 {@code productionBatchId ∈ 本计划 process_sheet_rows.batch_id} 定位并打戳全部未结正向行
 *       (含 {@code plan_id=null} 非末道形态, 多批次聚合); 返回受影响行数。</li>
 *   <li>T2: 打戳后关单-前守卫 {@code countUnsettledConsumptionByBatchIds} 对结单族 WKS 批次归 0 → 残留 #2
 *       (跨计划投料留结单族未结行 → 关单误 409 永久卡死) 闭合。</li>
 *   <li>T3: <b>绝不</b>误打 SAFETY_STOCK 计划的未结行 (跨族安全) → 其小结待扣减盲区保护完好
 *       ({@code sumUnsettledConsumptionGroupedByBatch} 仍减除之)。</li>
 *   <li>T4: 幂等 (已戳行时间戳不被覆盖) + {@code quantity<0} 退料负数留痕不打戳。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("MaterialConsumptionRepository 结单族扣减打戳根修 (#1216↔#1217 根因闭合)")
class MaterialConsumptionStampSettledForPlanTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MaterialConsumptionRepository consumptionRepo;
    @Autowired private ProcessSheetRowRepository rowRepo;
    @Autowired private ProductionPlanRepository planRepo;

    private static final String FACTORY_ID = "IT-STAMP-F";
    private static final LocalDateTime PRIOR_STAMP = LocalDateTime.of(2026, 1, 1, 8, 0, 0);

    private String custPlanId;      // 结单族 CUSTOMER_ORDER (本次结单打戳目标)
    private long pbCust = 910001L;  // 结单族道批次
    private long pbCust2 = 910002L; // 结单族第二道批次
    private String safetyPlanId;    // SAFETY_STOCK (跨族安全: 绝不被误打戳)
    private long pbSafety = 910003L;

    private String wksCust;    // 结单族报工消耗落的 WKS 批次 (MC.batchId)
    private String wksCust2;
    private String wksSafety;  // SAFETY_STOCK 报工消耗落的 WKS 批次

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");

        custPlanId = seedPlan(PlanSourceType.CUSTOMER_ORDER);
        seedRow(custPlanId, pbCust);
        seedRow(custPlanId, pbCust2);

        safetyPlanId = seedPlan(PlanSourceType.SAFETY_STOCK);
        seedRow(safetyPlanId, pbSafety);

        wksCust = "WKS-CUST-" + UUID.randomUUID().toString().substring(0, 8);
        wksCust2 = "WKS-CUST2-" + UUID.randomUUID().toString().substring(0, 8);
        wksSafety = "WKS-SAFE-" + UUID.randomUUID().toString().substring(0, 8);

        // 结单族报工消耗 (plan_id 故意 null = 逐工序非末道形态), 两道两批次, 各未结正向:
        seedConsumption(wksCust, pbCust, new BigDecimal("60.00"), null, null);
        seedConsumption(wksCust2, pbCust2, new BigDecimal("40.00"), null, null);
        // 结单族退料负数留痕 (quantity<0) → 不打戳
        seedConsumption(wksCust, pbCust, new BigDecimal("-3.00"), null, null);
        // 结单族已被更早某次结单打戳的行 → 幂等: 时间戳不被覆盖
        seedConsumption(wksCust, pbCust, new BigDecimal("5.00"), null, PRIOR_STAMP);

        // SAFETY_STOCK 报工消耗 (跨族: 绝不被结单族打戳误触) 50kg 未结
        seedConsumption(wksSafety, pbSafety, new BigDecimal("50.00"), null, null);
    }

    @Test
    @DisplayName("T1: 打戳结单族计划 → 命中全部未结正向行 (含 plan_id=null 非末道, 跨道聚合), 返回 2 笔")
    void stampsAllUnsettledPositiveRowsOfPlan() {
        int stamped = consumptionRepo.stampInterimSettledForPlan(FACTORY_ID, custPlanId, LocalDateTime.now());
        assertThat(stamped).isEqualTo(2); // 60(pbCust) + 40(pbCust2); 排除 -3 负数 + 已戳 5

        // 打戳后两笔正向行已非 null → wksCust(60 已戳, -3 被 quantity>0 排除, 5 提前已戳) + wksCust2(40 已戳)
        // 上均无未结正向消耗 → count 归 0
        assertThat(countUnsettledOnBatches(List.of(wksCust, wksCust2))).isEqualTo(0L);
    }

    @Test
    @DisplayName("T2: 打戳后关单-前守卫 count 对结单族 WKS 批次归 0 → 残留 #2 闭合")
    void closeGuardCountDropsToZeroAfterStamp() {
        // 打戳前: 结单族 WKS 批次尚有未结正向消耗 (60 + 40) → 守卫会 count-and-block
        assertThat(countUnsettledOnBatches(List.of(wksCust, wksCust2))).isEqualTo(2L);

        consumptionRepo.stampInterimSettledForPlan(FACTORY_ID, custPlanId, LocalDateTime.now());

        // 打戳后: 归 0 → 关单守卫不再统计 → 不再误 409
        assertThat(countUnsettledOnBatches(List.of(wksCust, wksCust2))).isEqualTo(0L);
    }

    @Test
    @DisplayName("T3: 跨族安全 — 打戳结单族绝不触碰 SAFETY_STOCK 未结行 (小结待扣减盲区保护完好)")
    void neverStampsSafetyStockRows() {
        consumptionRepo.stampInterimSettledForPlan(FACTORY_ID, custPlanId, LocalDateTime.now());

        // SAFETY_STOCK 行仍未结 → 仍被盘点 SUM 减除 (未被结单族打戳误触, 证明跨族安全)
        Map<String, BigDecimal> sum = sumUnsettled(List.of(wksCust, wksCust2, wksSafety));
        assertThat(sum).containsKey(wksSafety);
        assertThat(sum.get(wksSafety)).isEqualByComparingTo("50.00");
        // 结单族批次在本次调用前已被上方 stampInterimSettledForPlan 打戳 (interim_settled_at 非空) → 排除。
        // (2026-07-05: SUM 已去族门控, 结单<b>前</b>结单族本会被计入; 此处因已打戳而排除 —— 打戳=结单后不双减。)
        assertThat(sum).doesNotContainKey(wksCust);
        assertThat(sum).doesNotContainKey(wksCust2);
        // count 视角: SAFETY_STOCK WKS 批次仍有 1 笔未结正向
        assertThat(countUnsettledOnBatches(List.of(wksSafety))).isEqualTo(1L);
    }

    @Test
    @DisplayName("T4: 幂等 (已戳时间戳不被覆盖) + 负数留痕不打戳; 重复调用返回 0")
    void idempotentAndSkipsNegativeTraces() {
        LocalDateTime settleTime = LocalDateTime.now();
        int first = consumptionRepo.stampInterimSettledForPlan(FACTORY_ID, custPlanId, settleTime);
        assertThat(first).isEqualTo(2);

        // 重复调用 → 已全部打戳 → 0 笔受影响 (幂等)
        int second = consumptionRepo.stampInterimSettledForPlan(FACTORY_ID, custPlanId, LocalDateTime.now());
        assertThat(second).isZero();

        // 更早已戳的 5kg 行时间戳未被覆盖 (仍 = PRIOR_STAMP)
        Integer priorStamped = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM material_consumptions WHERE factory_id = ? AND batch_id = ? "
                        + "AND quantity = 5.00 AND interim_settled_at = ?",
                Integer.class, FACTORY_ID, wksCust, PRIOR_STAMP);
        assertThat(priorStamped).isEqualTo(1);

        // 负数留痕 (-3) 仍未打戳 (interim_settled_at IS NULL)
        Integer negUnstamped = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM material_consumptions WHERE factory_id = ? AND batch_id = ? "
                        + "AND quantity = -3.00 AND interim_settled_at IS NULL",
                Integer.class, FACTORY_ID, wksCust);
        assertThat(negUnstamped).isEqualTo(1);
    }

    // ── helpers ──

    private long countUnsettledOnBatches(List<String> batchIds) {
        return consumptionRepo.countUnsettledConsumptionByBatchIds(FACTORY_ID, batchIds);
    }

    private Map<String, BigDecimal> sumUnsettled(List<String> batchIds) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (Object[] row : consumptionRepo.sumUnsettledConsumptionGroupedByBatch(FACTORY_ID, batchIds)) {
            map.put((String) row[0], (BigDecimal) row[1]);
        }
        return map;
    }

    private String seedPlan(PlanSourceType sourceType) {
        String planId = "IT-STAMP-PLAN-" + UUID.randomUUID().toString().substring(0, 12);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("IT-STAMP-" + UUID.randomUUID().toString().substring(0, 8));
        plan.setProductTypeId("IT-STAMP-PT");
        plan.setPlannedQuantity(new BigDecimal("1000"));
        plan.setPlannedUnit("kg");
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
