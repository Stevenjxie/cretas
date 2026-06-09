package com.cretas.aims.service;

import com.cretas.aims.dto.laborefficiency.LaborEfficiencyCompareDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * SP9: 人工双口径对比 + 人效分析服务接口.
 *
 * <ul>
 *   <li>M3: 批次级人工对比 (research estimate vs actual)</li>
 *   <li>M4: 达成率汇总 (按产品类型/时间段)</li>
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
}
