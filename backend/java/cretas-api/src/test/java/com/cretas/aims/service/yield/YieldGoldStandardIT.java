package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报工体系统一 Phase A — 金标准端到端集成测试 (猪舌链 998→382.08 累计出成率 0.3828 + 双写 + C1 分次报工).
 *
 * <p><b>为何 @Disabled (与项目 21+ 重 @SpringBootTest IT 一致, 如 ProductionFlowIntegrationTest /
 * MaterialBatchFlowTest / ProductTraceabilityIntegrationTest):</b>
 * <ol>
 *   <li>test profile 用 H2, 但全应用 ~200 实体在 H2 ddl-auto=create-drop 下建表会撞 H2 预存限制
 *       (BLOB 类型 / month / value 保留字), ApplicationContext 加载失败 — 这是 repo 级 H2 限制, 非本功能代码问题。</li>
 *   <li>C1 回归 (sameTask_multipleReports 撞 uq_pr_intermediate_batch_no) 的价值依赖**真实 PG 的 Flyway
 *       partial unique index** — 该索引是 Flyway 迁移 (非 Hibernate @Index), test profile 禁 Flyway, H2 下根本
 *       没这约束 → 在 H2 跑会是 false-green no-op, 无意义。</li>
 * </ol>
 * 出成率数学已由 {@code YieldCalculationServiceImplTest} (6 纯函数单测) + 写入路径由
 * {@code YieldReportServiceImplTest} (4 mock 单测) 全绿覆盖。本 IT 作为端到端文档保留, 需真实 PG 手动跑:
 * <pre>mvn -o test -Dtest=YieldGoldStandardIT -Dspring-boot.run.profiles=pg-test  (本地 PG + Flyway 已 apply)</pre>
 */
@Disabled("需真实 PG + Flyway (H2 建不出全 schema: BLOB/month/value; C1 唯一约束仅 PG Flyway 存在). 端到端文档保留, 数学已由单测覆盖.")
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class YieldGoldStandardIT {

    @Autowired YieldReportService yieldReportService;
    @Autowired WorkProcessTaskRepository taskRepo;
    @Autowired ProductionBatchRepository batchRepo;
    @Autowired ProductionReportRepository reportRepo;

    private WorkProcessTask seedTask(String factoryId, Long batchId, int order, String wpId) {
        WorkProcessTask t = new WorkProcessTask();
        t.setFactoryId(factoryId); t.setProductionBatchId(batchId);
        t.setProductWorkProcessId(1L); t.setWorkProcessId(wpId);
        t.setProductTypeId("CPDX0001"); t.setProcessOrder(order);
        t.setStatus(WorkProcessTask.Status.IN_PROGRESS);
        return taskRepo.save(t);
    }

    private void report(String factoryId, Long batchId, Long taskId, String in, String out) {
        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(taskId);
        req.setInputQuantity(new BigDecimal(in)); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal(out)); req.setOutputUnit("kg");
        yieldReportService.submitReport(factoryId, batchId, 5L, req);
    }

    @Test
    void zhuShe_endToEnd_cumulativeYield_0_3828() {
        String f = "F006";
        ProductionBatch batch = ProductionBatch.builder()
                .factoryId(f).batchNumber("ZS-IT-001").productTypeId("CPDX0001")
                .quantity(new BigDecimal("998")).unit("kg").status(ProductionBatchStatus.IN_PROGRESS)
                .build();
        batch = batchRepo.save(batch);
        Long bid = batch.getId();

        WorkProcessTask t1 = seedTask(f, bid, 1, "WP-1");
        WorkProcessTask t2 = seedTask(f, bid, 2, "WP-2");
        WorkProcessTask t3 = seedTask(f, bid, 3, "WP-3");

        report(f, bid, t1.getId(), "998", "935.5");
        report(f, bid, t2.getId(), "935.5", "1262.9");  // 滚揉保水
        report(f, bid, t3.getId(), "1262.9", "382.08");

        BatchYieldDTO dto = yieldReportService.getYield(f, bid);
        assertThat(dto.getCumulativeYieldRate()).isEqualByComparingTo("0.3828");
        assertThat(dto.getComplete()).isTrue();
        assertThat(dto.getSteps()).hasSize(3);
        // 双写校验: t1.actualQuantity 应 = 935.5
        WorkProcessTask reloaded = taskRepo.findByFactoryIdAndId(f, t1.getId()).orElseThrow();
        assertThat(reloaded.getActualQuantity()).isEqualByComparingTo("935.5");
    }

    /**
     * C1 回归 (code review I2/Mn2): 同一工序任务分次报工 2 次, 在真实 DB 验证
     * intermediateBatchNo 唯一约束 (uq_pr_intermediate_batch_no) 不复发 —
     * mock 单测无法覆盖唯一约束, 必须真实 DB/H2 验证.
     */
    @Test
    void sameTask_multipleReports_noBatchNoCollision() {
        String f = "F006";
        ProductionBatch batch = ProductionBatch.builder()
                .factoryId(f).batchNumber("ZS-IT-002").productTypeId("CPDX0001")
                .quantity(new BigDecimal("998")).unit("kg").status(ProductionBatchStatus.IN_PROGRESS)
                .build();
        batch = batchRepo.save(batch);
        Long bid = batch.getId();

        WorkProcessTask t1 = seedTask(f, bid, 1, "WP-1");

        // 同一 task 分次报工 2 次 — 不得抛 DataIntegrityViolationException
        report(f, bid, t1.getId(), "500", "480");
        report(f, bid, t1.getId(), "498", "450");

        // 该 task 应能查到 2 条 YIELD 报工
        List<ProductionReport> taskReports = reportRepo.findYieldReportsByTask(f, t1.getId());
        assertThat(taskReports).hasSize(2);

        // 双写累加: task.actualQuantity = 480 + 450 = 930
        WorkProcessTask reloaded = taskRepo.findByFactoryIdAndId(f, t1.getId()).orElseThrow();
        assertThat(reloaded.getActualQuantity()).isEqualByComparingTo("930");

        // 第 2 条 intermediateBatchNo 应为 null (仅首条生成, 避免唯一约束冲突)
        List<ProductionReport> withBatchNo = taskReports.stream()
                .filter(r -> r.getIntermediateBatchNo() != null)
                .toList();
        assertThat(withBatchNo).hasSize(1);
    }
}
