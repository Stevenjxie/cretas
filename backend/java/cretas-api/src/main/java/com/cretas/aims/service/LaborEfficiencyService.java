package com.cretas.aims.service;

import com.cretas.aims.dto.laborefficiency.LaborAchievementSummaryDTO;
import com.cretas.aims.dto.laborefficiency.LaborEfficiencyCompareDTO;
import com.cretas.aims.dto.laborefficiency.LaborEfficiencyOrderAggregateDTO;
import com.cretas.aims.dto.laborefficiency.LaborStepBreakdownDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * SP9/SP3: 人工双口径对比 + 人效分析服务接口.
 *
 * <ul>
 *   <li>M3: 批次级人工对比 (research estimate vs actual)</li>
 *   <li>M3b: 订单/成本组级人工双口径聚合 (SP3 P1 #39)</li>
 *   <li>M4: 达成率汇总 (按产品类型/时间段)</li>
 *   <li>M5: 看板图表数据 (ECharts series — 达成率趋势/工序对比)</li>
 *   <li>step-breakdown: 批次逐工序人效拆分</li>
 * </ul>
 */
public interface LaborEfficiencyService {

    /**
     * M3: 批次级人工双口径对比列表.
     *
     * <p>返回 factoryId 在 [startDate, endDate] 区间内的已完工批次列表,
     * 每批次含研发预估 vs 实际人工成本对比 + 逐工序分解.</p>
     *
     * @param factoryId     工厂 ID
     * @param startDate     开始日期 (inclusive); 按批次 createdAt 筛选
     * @param endDate       结束日期 (inclusive)
     * @param productTypeId 产品类型 ID (可选; null = 全部产品类型)
     * @return 人工对比 DTO 列表, 按批次 createdAt 降序
     */
    List<LaborEfficiencyCompareDTO> getLaborEfficiencyComparison(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            String productTypeId
    );

    /**
     * M3b: SP3 成本组/公单级人工双口径聚合.
     *
     * <p>将同一销售订单下的多批次人工成本归拢到订单级,
     * 返回每销售订单的研发预估 vs 实际人工汇总 + 批次明细.</p>
     *
     * <p>聚合逻辑:
     * <ul>
     *   <li>totalQuotedLaborCost = Σ(quotedLaborCostPerKg × goodQuantityKg) per batch</li>
     *   <li>totalActualLaborCost = Σ(batch.laborCost) per batch</li>
     *   <li>varianceRate = (actual - quoted) / quoted × 100%; null = quoted 未配</li>
     * </ul>
     * </p>
     *
     * @param factoryId     工厂 ID
     * @param startDate     开始日期 (inclusive); 按批次 createdAt 筛选
     * @param endDate       结束日期 (inclusive)
     * @param productTypeId 产品类型 ID (可选; null = 全部产品类型)
     * @return 订单级聚合 DTO 列表, 按 salesOrderId 排序
     */
    List<LaborEfficiencyOrderAggregateDTO> getLaborCostOrderAggregate(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            String productTypeId
    );

    /**
     * M4: 人效达成率汇总.
     *
     * <p>按产品类型聚合 [startDate, endDate] 区间内已完工批次的达成率汇总:
     * 实际总工时 vs 计划工时(WorkProcess.estimatedMinutes 之和 × 批次数).
     * 无 estimatedMinutes 配置时 achievementRatePct 诚实 null.</p>
     *
     * @param factoryId     工厂 ID
     * @param startDate     开始日期 (inclusive)
     * @param endDate       结束日期 (inclusive)
     * @param productTypeId 产品类型 ID (可选; null = 全部产品, 返回总体汇总 + 各品汇总)
     * @return 达成率汇总列表
     */
    List<LaborAchievementSummaryDTO> getLaborAchievementSummary(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            String productTypeId
    );

    /**
     * step-breakdown: 批次逐工序人效拆分.
     *
     * <p>返回单个批次每道工序的工时/人次/达成率/成本明细.</p>
     *
     * @param factoryId 工厂 ID
     * @param batchId   批次 ID
     * @return 逐工序拆分 DTO; null 批次不存在
     */
    LaborStepBreakdownDTO getStepBreakdown(String factoryId, Long batchId);
}
