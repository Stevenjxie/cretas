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
 * 🔴🔒🔒 {@link MaterialConsumptionRepository#sumUnsettledConsumptionGroupedByBatch} 结单族盘点盲区
 * 根因闭合回归 (2026-07-05, 修 #1216↔#1217 姊妹流的<b>反向</b>错误).
 *
 * <p><b>原 bug (被本 PR 推翻的旧结论)</b>: #1216 加 {@code p.sourceType = SAFETY_STOCK} 族门控, 把结单族
 * (CUSTOMER_ORDER/MANUAL) 的未结消耗<b>排除</b>, 假设「结单族行早已落库扣减」。<b>实际</b>: 结单族报工写
 * {@link MaterialConsumption} (未结, IS NULL) 但结单<b>前不扣</b> {@code usedQuantity} (延迟扣减 —
 * {@code writeConsumption} 不动 used, {@code settleProduction→postConsumptionToInventory} 才扣)。故结单族
 * IS NULL = 结单前、尚未扣减 (与 SAFETY_STOCK 待小结同构), 盘点<b>必须减去</b>; 排除 → 报工-结单窗口盘点
 * stale-high → 假盘亏 (借6602/贷1403)。
 *
 * <p><b>Fix</b>: 删族门控 (仅去 {@code p.sourceType} 谓词), IS NULL 现对<b>所有族</b>统一 = 「待扣」。结单后经
 * {@code stampInterimSettledForPlan} 打戳 → IS NOT NULL → 天然落谓词外, 不双减。<b>保留</b>
 * {@code productionBatchId ∈ process_sheet_rows.batch_id} 子查询 (非族门控, 而是 legacy/孤儿门控): 排除即时
 * 扣减 legacy 消耗 ({@code consumeBatchMaterial}/{@code ProcessingServiceImpl}: 写正数 + 同步扣 used 却 IS NULL,
 * productionBatchId 恒 null → 不减, 防对已扣批次二次减 → 假盘盈)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("MaterialConsumptionRepository 待结族门控闭合 (#1216 反向修 — 结单族盘点盲区)")
class MaterialConsumptionUnsettledFamilyGateTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MaterialConsumptionRepository consumptionRepo;
    @Autowired private ProcessSheetRowRepository rowRepo;
    @Autowired private ProductionPlanRepository planRepo;

    private static final String FACTORY_ID = "IT-FAMGATE-F";

    private String rawSafety;         // SAFETY_STOCK 待小结 (应减)
    private String rawCustomerPre;    // 结单族 CUSTOMER_ORDER 结单前 (未打戳 → 应减)
    private String rawManualPre;      // 结单族 MANUAL 结单前 (未打戳 → 应减)
    private String rawCustomerPost;   // 结单族 CUSTOMER_ORDER 结单后 (已打戳 → 不减)
    private String rawOrphan;         // productionBatchId 无匹配 process_sheet_row (legacy/孤儿, 不应减)

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");

        String safetyPlanId = seedPlan(PlanSourceType.SAFETY_STOCK);
        long pbSafety = 900001L;
        seedRow(safetyPlanId, pbSafety);

        String custPlanId = seedPlan(PlanSourceType.CUSTOMER_ORDER);
        long pbCust = 900002L;
        seedRow(custPlanId, pbCust);

        String manualPlanId = seedPlan(PlanSourceType.MANUAL);
        long pbManual = 900003L;
        seedRow(manualPlanId, pbManual);

        String custPlanPostId = seedPlan(PlanSourceType.CUSTOMER_ORDER);
        long pbCustPost = 900004L;
        seedRow(custPlanPostId, pbCustPost);

        long pbOrphan = 900999L;   // 不出现在任何 process_sheet_row

        rawSafety = "RAW-SAFE-" + UUID.randomUUID().toString().substring(0, 8);
        rawCustomerPre = "RAW-CUSTP-" + UUID.randomUUID().toString().substring(0, 8);
        rawManualPre = "RAW-MANP-" + UUID.randomUUID().toString().substring(0, 8);
        rawCustomerPost = "RAW-CUSTS-" + UUID.randomUUID().toString().substring(0, 8);
        rawOrphan = "RAW-ORPH-" + UUID.randomUUID().toString().substring(0, 8);

        // SAFETY_STOCK 待小结: 6+4=10kg, plan_id 故意 null (逐工序非末道形态), production_batch_id=pbSafety
        seedConsumption(rawSafety, pbSafety, new BigDecimal("6.00"), null, null);
        seedConsumption(rawSafety, pbSafety, new BigDecimal("4.00"), null, null);

        // 结单族 结单前 (未打戳, usedQuantity 尚未扣 → 现应减, 修 stale-high 假盘亏)
        seedConsumption(rawCustomerPre, pbCust, new BigDecimal("60.00"), null, null);
        seedConsumption(rawManualPre, pbManual, new BigDecimal("30.00"), null, null);

        // 结单族 结单后 (已打戳 interim_settled_at 非空 → 不减, usedQuantity 结单时已扣)
        seedConsumption(rawCustomerPost, pbCustPost, new BigDecimal("50.00"), null, LocalDateTime.now());

        // legacy/孤儿 (productionBatchId 无匹配 process_sheet_row → 如 consumeBatchMaterial 即时扣减但 IS NULL):
        //   不应减 (防对已扣 used 批次二次减 → 假盘盈)
        seedConsumption(rawOrphan, pbOrphan, new BigDecimal("99.00"), null, null);

        // 干扰行 (继承既有谓词, 必须排除):
        seedConsumption(rawSafety, pbSafety, new BigDecimal("5.00"), null, LocalDateTime.now());  // 已结 → 不计
        seedConsumption(rawSafety, pbSafety, new BigDecimal("-3.00"), null, null);                 // 退料负数 → 不计
    }

    private List<String> allBatchIds() {
        return List.of(rawSafety, rawCustomerPre, rawManualPre, rawCustomerPost, rawOrphan);
    }

    @Test
    @DisplayName("T1: SAFETY_STOCK 非末道 (plan_id=null) 待小结消耗仍被计入 (SUM=10; 未回退到 planId 门控)")
    void safetyStock_unsettled_isSubtracted() {
        Map<String, BigDecimal> byBatch = query(allBatchIds());
        assertThat(byBatch).containsKey(rawSafety);
        assertThat(byBatch.get(rawSafety)).isEqualByComparingTo("10.00");   // 6+4, 排除已结 5 + 负数 -3
    }

    @Test
    @DisplayName("T2 (核心修复): 结单族<b>结单前</b>未结消耗<b>现被计入</b> → 减去待扣量 → 无 stale-high 假盘亏")
    void settleFamily_preSettle_isNowSubtracted() {
        Map<String, BigDecimal> byBatch = query(allBatchIds());
        assertThat(byBatch).containsKey(rawCustomerPre);
        assertThat(byBatch.get(rawCustomerPre)).isEqualByComparingTo("60.00");
        assertThat(byBatch).containsKey(rawManualPre);
        assertThat(byBatch.get(rawManualPre)).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("T3 (防双减): 结单族<b>结单后</b>已打戳 (interim_settled_at 非空) → 不被计入 → 不双减")
    void settleFamily_postSettle_stamped_isNotSubtracted() {
        Map<String, BigDecimal> byBatch = query(allBatchIds());
        assertThat(byBatch).doesNotContainKey(rawCustomerPost);
    }

    @Test
    @DisplayName("T4 (legacy/孤儿保护): productionBatchId 无匹配 process_sheet_row (即时扣减 legacy) → 不减 (honest-null-safe)")
    void legacyOrphan_isNotSubtracted() {
        Map<String, BigDecimal> byBatch = query(allBatchIds());
        assertThat(byBatch).doesNotContainKey(rawOrphan);
    }

    @Test
    @DisplayName("T5: 混合仓一次查询 → 返回 SAFETY + 结单族结单前, 排除结单后已戳 + legacy 孤儿")
    void mixedWarehouse_returnsUnsettledAcrossFamilies() {
        Map<String, BigDecimal> byBatch = query(allBatchIds());
        assertThat(byBatch).containsOnlyKeys(rawSafety, rawCustomerPre, rawManualPre);
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
