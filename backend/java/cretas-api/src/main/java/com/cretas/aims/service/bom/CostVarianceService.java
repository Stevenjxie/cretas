package com.cretas.aims.service.bom;

import java.math.BigDecimal;

/**
 * SP3 成本超支阈值解析服务.
 *
 * <p>优先级链: 产品专属 → 工厂全局 → 系统默认 10%。
 */
public interface CostVarianceService {

    /**
     * 解析成本超支预警阈值 (百分比, 如 10 表示 10%).
     *
     * @param factoryId     工厂ID
     * @param productTypeId 产品类型ID (可 null, 表示查工厂全局)
     * @return 阈值, 永不 null (fallback 到 10%)
     */
    BigDecimal resolveThreshold(String factoryId, String productTypeId);

    /**
     * 计算超支百分比.
     *
     * <p>公式: {@code (actual - standard) / standard * 100}, scale=2 HALF_UP.
     * 任一参数 null 或 standard <= 0 则返 null.
     *
     * @param actual   实际单价
     * @param standard 标准单价
     * @return 超支百分比 (正=超支, 负=节约), 或 null
     */
    BigDecimal computeVariancePct(BigDecimal actual, BigDecimal standard);
}
