package com.cretas.aims.repository;

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴🔒🔒 {@link ProcessSheetRowRepository#findUnsettledStockFeedRows} 去族门控 + 结单族「结单即打戳」
 * ({@link ProcessSheetRowRepository#stampInterimSettledForPlan}) 回归 (2026-07-05).
 *
 * <p>SFI/FG 投料延迟扣减: SAFETY_STOCK 待小结 + 结单族结单前 都尚未扣减 → pending 查询须覆盖两族;
 * 结单族结单时打戳后排除 (防双减)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("ProcessSheetRow 待结投料查询 + 结单族打戳 (BUG1 SFI 侧)")
class ProcessSheetRowUnsettledStockFeedTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ProcessSheetRowRepository rowRepo;
    @Autowired private ProductionPlanRepository planRepo;

    private static final String FACTORY_ID = "IT-PSRFEED-F";

    private String safetyPlanId;
    private String customerPlanId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        safetyPlanId = seedPlan(PlanSourceType.SAFETY_STOCK);
        customerPlanId = seedPlan(PlanSourceType.CUSTOMER_ORDER);

        seedRow(safetyPlanId, "SFI-SAFE", 810001L, null);        // SAFETY_STOCK 未结
        seedRow(customerPlanId, "SFI-CUST", 810002L, null);      // 结单族 未结 (结单前)
        // 干扰: 已结行 (interim_settled_at 非空) → 不返回
        seedRow(safetyPlanId, "SFI-SAFE-DONE", 810003L, LocalDateTime.now());
    }

    @Test
    @DisplayName("T1: findUnsettledStockFeedRows 覆盖<b>所有族</b> (SAFETY_STOCK + 结单族), 排除已结")
    void unsettledStockFeedRows_coversAllFamilies() {
        List<String> planIds = rowRepo.findUnsettledStockFeedRows(FACTORY_ID).stream()
                .map(ProcessSheetRow::getPlanId).collect(Collectors.toList());
        assertThat(planIds).contains(safetyPlanId, customerPlanId);   // 两族均在 (结单族不再被门控排除)
        // 已结行的 plan 出现 (因 SAFETY 计划也有未结行), 但不能有 3 条 → 精确校验行数
        assertThat(rowRepo.findUnsettledStockFeedRows(FACTORY_ID)).hasSize(2);
    }

    @Test
    @DisplayName("T2: 结单族「结单打戳」stampInterimSettledForPlan → 该族行被排除, SAFETY_STOCK 未结行保留")
    void stampSettleFamily_excludesFromPending_keepsSafetyStock() {
        int stamped = rowRepo.stampInterimSettledForPlan(FACTORY_ID, customerPlanId, LocalDateTime.now());
        assertThat(stamped).isEqualTo(1);   // 只结单族那 1 行

        List<String> planIds = rowRepo.findUnsettledStockFeedRows(FACTORY_ID).stream()
                .map(ProcessSheetRow::getPlanId).collect(Collectors.toList());
        assertThat(planIds).contains(safetyPlanId);          // SAFETY_STOCK 未结行仍在
        assertThat(planIds).doesNotContain(customerPlanId);  // 结单族已打戳 → 排除 (防双减)
    }

    @Test
    @DisplayName("T3: 打戳幂等 — 已戳行再次 stamp 返回 0, 不覆盖时间戳")
    void stamp_isIdempotent() {
        rowRepo.stampInterimSettledForPlan(FACTORY_ID, customerPlanId, LocalDateTime.now());
        int again = rowRepo.stampInterimSettledForPlan(FACTORY_ID, customerPlanId, LocalDateTime.now());
        assertThat(again).isEqualTo(0);
    }

    // ── helpers ──

    private String seedPlan(PlanSourceType sourceType) {
        String planId = "IT-PSR-PLAN-" + UUID.randomUUID().toString().substring(0, 12);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("IT-PSR-" + UUID.randomUUID().toString().substring(0, 8));
        plan.setProductTypeId("IT-PSR-PT");
        plan.setPlannedQuantity(new java.math.BigDecimal("1000"));
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

    private void seedRow(String planId, String batchNumber, long batchId, LocalDateTime settledAt) {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(FACTORY_ID);
        row.setPlanId(planId);
        row.setProcessCode("chaoshui");
        row.setProcessOrder(1);
        row.setClientRowId("row-" + batchId);
        row.setBatchId(batchId);
        row.setBatchNumber(batchNumber);
        row.setRowPayload("{}");
        row.setRowStatus("SAVED");
        row.setInterimSettledAt(settledAt);
        rowRepo.saveAndFlush(row);
    }
}
