package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findYieldReportsByBatch} query 层 per-batch 隔离 (follow-up C, 2026-07-02 库存生产线).
 *
 * <p>证明: 即使<b>同工厂/同产品/同日/同工人/同工序序/文员 taskId=null</b> 的两个批次 A、B 并存,
 * {@code findYieldReportsByBatch(F, batchA)} 只返回 A 的行, 其人工成本 Σ 恒为 A 自身
 * (28.00), 绝不把 B 的行 (2085.07) 拉进来。这从 query 层封死了并发异常里 "2085.07 串到 A"
 * 的唯一可能来源 —— 该 JPQL 硬性 {@code batchId=:batchId} 作用域, 与并发无关。
 *
 * <p>与 {@code YieldLaborBatchScopeTest} (calc 层纯函数保真) 合起来 = 全链 per-batch 隔离证明。
 *
 * <p>JSONB 列 (custom_fields/photos/... ) 全留 null (H2 PG-compat jsonb 限制, 同
 * {@code ProductionInterimSettlementRepositoryTest})。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("ProductionReportRepository.findYieldReportsByBatch — per-batch 隔离 (H2 PG-compat)")
class ProductionReportRepositoryBatchScopeTest {

    private static final String F = "F-PRBS";
    private static final Long BATCH_A = 9101L;
    private static final Long BATCH_B = 9102L;
    private static final Long WORKER = 7001L;
    private static final LocalDate DAY = LocalDate.of(2026, 7, 2);

    @Autowired
    private ProductionReportRepository repo;

    /** 文员逐道 YIELD 报工 (workProcessTaskId=null) —— 复现异常标注的 taskId(null) 场景。 */
    private ProductionReport clerkYield(Long batchId, int order, String labor) {
        return ProductionReport.builder()
                .factoryId(F)
                .batchId(batchId)
                .workerId(WORKER)               // 同工人 (并发同产品/同日)
                .reportType("YIELD")
                .reportDate(DAY)                // 同日
                .workProcessTaskId(null)        // 文员逐道录入无 task
                .processOrder(order)
                .productTypeId("PT-PIGFOOT")    // 同产品
                .inputQuantity(new BigDecimal("100")).inputUnit("kg")
                .outputQuantity(new BigDecimal("80")).outputUnit("kg")
                .laborCost(new BigDecimal(labor))
                .build();
    }

    @Test
    @DisplayName("A、B 同产品/同日/同工人并存 → findYieldReportsByBatch(A) 只返回 A 的行, 人工 Σ=28.00")
    void findByBatch_isolatesBatchA_notContaminatedByB() {
        // 批次 A: 20.00 + 8.00 = 28.00
        repo.saveAndFlush(clerkYield(BATCH_A, 1, "20.00"));
        repo.saveAndFlush(clerkYield(BATCH_A, 2, "8.00"));
        // 批次 B (并发, 同产品/同日/同工人): 2000.00 + 85.07 = 2085.07 —— 异常里错误串到 A 的值
        repo.saveAndFlush(clerkYield(BATCH_B, 1, "2000.00"));
        repo.saveAndFlush(clerkYield(BATCH_B, 2, "85.07"));

        List<ProductionReport> aRows = repo.findYieldReportsByBatch(F, BATCH_A);

        assertThat(aRows).hasSize(2);
        assertThat(aRows).allMatch(r -> BATCH_A.equals(r.getBatchId()));
        BigDecimal aLaborSum = aRows.stream()
                .map(ProductionReport::getLaborCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(aLaborSum)
                .as("A 批次人工 Σ 恒为 28.00, 绝不含 B 的 2085.07")
                .isEqualByComparingTo("28.00");
    }

    @Test
    @DisplayName("软删的 YIELD 行不返回 (deletedAt IS NULL 过滤)")
    void findByBatch_excludesSoftDeleted() {
        repo.saveAndFlush(clerkYield(BATCH_A, 1, "20.00"));
        ProductionReport deleted = clerkYield(BATCH_A, 2, "8.00");
        deleted.setDeletedAt(LocalDateTime.now());
        repo.saveAndFlush(deleted);

        List<ProductionReport> aRows = repo.findYieldReportsByBatch(F, BATCH_A);

        assertThat(aRows).hasSize(1);
        assertThat(aRows.get(0).getLaborCost()).isEqualByComparingTo("20.00");
    }
}
