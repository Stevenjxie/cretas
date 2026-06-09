package com.cretas.aims.service.pricing;

import com.cretas.aims.dto.pricing.GrossMarginCheckResult;

/**
 * SP5 毛利红线检查服务.
 *
 * <p>根据产品标准成本和目标毛利率，判断报价是否低于最低可接受价。
 * 配置优先级: product-level FactoryGrossMarginConfig > factory-wide default > ProductType.targetGrossMargin inline.
 *
 * <p><strong>安全约束</strong>: 返回的 {@link GrossMarginCheckResult} 不含 minPrice / standardCost /
 * targetGrossMargin 等价格敏感字段，避免成本结构泄露。
 */
public interface GrossMarginRedlineService {

    /**
     * 检查报价是否满足毛利红线.
     *
     * @param factoryId     工厂 ID (租户隔离)
     * @param productTypeId 产品类型 ID
     * @param quotedPrice   报价金额
     * @return 检查结果 (pass / warn / skipped)
     */
    GrossMarginCheckResult checkMargin(String factoryId, String productTypeId,
                                       java.math.BigDecimal quotedPrice);
}
