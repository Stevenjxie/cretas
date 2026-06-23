package com.cretas.aims.service.rd;

import com.cretas.aims.dto.rd.MidQuoteCalculationResultDTO;
import com.cretas.aims.entity.rd.ProductMidQuote;

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
     * @throws com.cretas.aims.exception.EntityNotFoundException 批次 / 报价任务不存在
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

    /**
     * 确认中报价 (CALCULATED → CONFIRMED).
     *
     * <p>操作人写入 confirmedBy, 状态置 CONFIRMED, 可附加备注.</p>
     *
     * @param factoryId    工厂 ID (隔离鉴权)
     * @param midQuoteId   ProductMidQuote.id
     * @param notes        可选确认备注
     * @param confirmedBy  操作人 userId
     * @return 已确认的中报价实体
     * @throws com.cretas.aims.exception.EntityNotFoundException 中报价不存在或不属于该工厂
     * @throws IllegalStateException 状态非 CALCULATED (不能重复确认或确认草稿)
     */
    ProductMidQuote confirmMidQuote(String factoryId, String midQuoteId, String notes, Long confirmedBy);

    /**
     * 按 id 查询中报价详情.
     *
     * @param factoryId   工厂 ID (隔离鉴权)
     * @param midQuoteId  ProductMidQuote.id
     * @return 中报价实体
     * @throws com.cretas.aims.exception.EntityNotFoundException 不存在或不属于该工厂
     */
    ProductMidQuote getMidQuoteById(String factoryId, String midQuoteId);
}
