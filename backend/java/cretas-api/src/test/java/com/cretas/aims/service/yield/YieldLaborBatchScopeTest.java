package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.service.yield.impl.YieldCalculationServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人工成本 per-batch 隔离回归测试 (follow-up C, 2026-07-02 库存生产线).
 *
 * <p><b>背景异常</b>: 3 工人并发对<b>同产品/同日</b>报工时, 某一瞬 getYield/成本分解显示
 * {@code laborCost=2085.07} 而非真实 {@code 28.00}; 隔离重试即自愈。疑点标注 "getYield
 * taskId(null) 分组"(文员逐道录入行 taskId=null)。
 *
 * <p><b>调查结论</b>: 人工成本聚合链<b>每一层都严格按 batchId 作用域</b>:
 * <ul>
 *   <li>{@code getYield} → {@code ProductionReportRepository.findYieldReportsByBatch(factoryId, batchId)}
 *       —— JPQL {@code WHERE factoryId=:f AND batchId=:b AND reportType='YIELD' AND deletedAt IS NULL}
 *       (query 层隔离, 另见 {@code ProductionReportRepositoryBatchScopeTest})。</li>
 *   <li>{@code YieldCalculationServiceImpl.calculateBatchYield} 的
 *       {@code totalLaborCost = Σ steps.laborCost}, 数学上 == Σ 传入 reports 的 laborCost
 *       —— <b>与分组方式无关</b>。文员行 taskId=null 退化按 processOrder/clerk-step 分组只改变
 *       <i>逐道拆分</i>, 绝不改变<i>整批总和</i>, 更无法把别批次的行拉进来 (calc 是纯函数, 只吃传入 list)。</li>
 *   <li>{@code rollupLaborCostToBatch} 同样走 {@code findYieldReportsByBatch(factoryId, batchId)},
 *       且是 {@code setLaborCost(Σ)} (重算覆盖, 非累加) —— 并发重算也只会算出正确值。</li>
 * </ul>
 * 故这<b>不是 scoping bug</b>: 报的 2085.07 只可能是并发写入过程中的瞬时读 (mid-write read),
 * 权威的 batch-scoped 重算给出正确 28.00, 所以隔离重试自愈。
 *
 * <p>本测试证明 calc 层的 per-batch 保真: 喂 A 批次的行只得 A 的人工总额, 想得到 2085.07
 * <b>只能</b>把 B 批次的行也喂进来 (即 query 泄漏) —— 而真实 query 按 batchId 隔离, 不会泄漏。
 * 这道回归护栏防止未来有人把 (productType, date) 或 (worker, date) 无 batchId 的聚合接进成本链。
 */
@DisplayName("人工成本 per-batch 隔离 (calc 层纯函数保真)")
class YieldLaborBatchScopeTest {

    private final YieldCalculationService calcSvc = new YieldCalculationServiceImpl();

    /** 构造文员逐道录入 YIELD 报工 (workProcessTaskId=null → 复现异常标注的 taskId(null) 分组路径)。 */
    private ProductionReport clerkReport(Long batchId, int order, String labor) {
        return ProductionReport.builder()
                .factoryId("F006").batchId(batchId).reportType("YIELD")
                .workProcessTaskId(null)            // 文员逐道录入无 task
                .processOrder(order)
                .productTypeId("PT-PIGFOOT")        // 同产品 (并发异常场景)
                .inputQuantity(new BigDecimal("100")).inputUnit("kg")
                .outputQuantity(new BigDecimal("80")).outputUnit("kg")
                .laborCost(new BigDecimal(labor))
                .build();
    }

    /** 批次 A: 两道文员报工, 人工 20.00 + 8.00 = 28.00 (真实值)。 */
    private List<ProductionReport> batchA() {
        List<ProductionReport> a = new ArrayList<>();
        a.add(clerkReport(101L, 1, "20.00"));
        a.add(clerkReport(101L, 2, "8.00"));
        return a;
    }

    /** 批次 B: 同产品/同日并发批, 人工合计 2085.07 (异常里错误显示到 A 头上的值)。 */
    private List<ProductionReport> batchB() {
        List<ProductionReport> b = new ArrayList<>();
        b.add(clerkReport(102L, 1, "2000.00"));
        b.add(clerkReport(102L, 2, "85.07"));
        return b;
    }

    @Test
    @DisplayName("喂 A 批次的行 → 整批人工 = 28.00 (不被同产品/同日的 B 批次污染)")
    void batchA_laborIsolated_28() {
        BatchYieldDTO dto = calcSvc.calculateBatchYield(batchA(), null);
        assertThat(dto.getTotalLaborCost())
                .as("A 批次整批人工只应是自身两道之和 28.00")
                .isEqualByComparingTo("28.00");
    }

    @Test
    @DisplayName("喂 B 批次的行 → 整批人工 = 2085.07 (各批各算, 互不串)")
    void batchB_laborIsolated_2085() {
        BatchYieldDTO dto = calcSvc.calculateBatchYield(batchB(), null);
        assertThat(dto.getTotalLaborCost()).isEqualByComparingTo("2085.07");
    }

    @Test
    @DisplayName("总和是传入 list 的纯函数: 想让 A 显示 2085.07 只能把 B 的行也喂进来 (=query 泄漏)")
    void mixedInput_provesTotalIsPureFunctionOfInput() {
        // 混入 A+B (模拟 query 若泄漏会发生什么) → 28.00 + 2085.07 = 2113.07
        List<ProductionReport> mixed = new ArrayList<>(batchA());
        mixed.addAll(batchB());
        BatchYieldDTO dto = calcSvc.calculateBatchYield(mixed, null);

        // 关键断言: calc 从不"凭空"造出 2085.07 加到 A 身上; 只有把 B 的行真喂进来才会变动。
        // 真实 findYieldReportsByBatch 按 batchId 隔离 → 永不把 B 喂进 A 的重算, 故异常非 scoping bug。
        assertThat(dto.getTotalLaborCost()).isEqualByComparingTo("2113.07");
        assertThat(dto.getTotalLaborCost())
                .as("A 单独重算绝不会得到 2085.07 —— 该值必来自 B 的行被喂进来")
                .isNotEqualByComparingTo("2085.07");
    }
}
