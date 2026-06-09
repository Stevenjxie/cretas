package com.cretas.aims.service.rd;

import com.cretas.aims.dto.rd.MidQuoteCalculationResultDTO;

import java.math.BigDecimal;

/**
 * SP10: 产品中报价服务接口.
 */
public interface ProductMidQuoteService {

    /**
     * 根据试制批次数据汇算中报价.
     *
     * <p>幂等: 已 CALCULATED 的批次直接返回已有记录, 不重复计算.</p>
     *
     * @param factoryId          工厂 ID
     * @param trialBatchId       试制批次 ID (production_batches.id)
     * @param quotationTaskId    关联报价任务 ID
     * @param materialCostPerKg  原材料成本 元/kg (null = 数据缺失)
     * @param laborCostPerKg     人工成本 元/kg (null = 数据缺失)
     * @param overheadCostPerKg  制造费用 元/kg (null = 数据缺失)
     * @param varianceThresholdPct 偏差预警阈值 %
     * @return 计算结果含中报价实体 + warnings
     * @throws jakarta.persistence.EntityNotFoundException 批次 / 报价任务不存在
     * @throws IllegalStateException 批次未完成 (actualQuantity = null)
     */
    MidQuoteCalculationResultDTO calculate(
            String factoryId,
            Long trialBatchId,
            String quotationTaskId,
            BigDecimal materialCostPerKg,
            BigDecimal laborCostPerKg,
            BigDecimal overheadCostPerKg,
            BigDecimal varianceThresholdPct
    );
}
