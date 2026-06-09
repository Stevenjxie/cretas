package com.cretas.aims.service.pricing.impl;

import com.cretas.aims.dto.pricing.GrossMarginCheckResult;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.pricing.FactoryGrossMarginConfig;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.pricing.FactoryGrossMarginConfigRepository;
import com.cretas.aims.service.pricing.GrossMarginRedlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * SP5 毛利红线检查服务实现.
 *
 * <p>配置优先级:
 * <ol>
 *   <li>ProductType.targetGrossMargin (inline override) — 直接绑定在产品类型上，跳过 config repo</li>
 *   <li>FactoryGrossMarginConfig (product-level) — factory_id + product_type_id</li>
 *   <li>FactoryGrossMarginConfig (factory-wide default) — factory_id, product_type_id IS NULL</li>
 *   <li>任何配置都未找到 → skipped</li>
 * </ol>
 *
 * <p>minPrice 计算: {@code standardCost / (1 - targetGrossMargin)}
 *
 * <p><strong>安全约束</strong>: 仅返回 belowRedline (boolean) + warningMessage，不暴露 minPrice。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrossMarginRedlineServiceImpl implements GrossMarginRedlineService {

    private final ProductTypeRepository productTypeRepository;
    private final FactoryGrossMarginConfigRepository configRepository;

    @Override
    @Transactional(readOnly = true)
    public GrossMarginCheckResult checkMargin(String factoryId, String productTypeId,
                                               BigDecimal quotedPrice) {
        // Step 1: 查产品，取标准成本
        Optional<ProductType> ptOpt = productTypeRepository.findById(productTypeId);
        if (ptOpt.isEmpty()) {
            log.debug("SP5 checkMargin: productType {} not found, skipped", productTypeId);
            return GrossMarginCheckResult.skipped();
        }
        ProductType pt = ptOpt.get();

        BigDecimal standardCost = pt.getStandardCost();
        if (standardCost == null) {
            log.debug("SP5 checkMargin: productType {} has no standardCost, skipped", productTypeId);
            return GrossMarginCheckResult.skipped();
        }

        // Step 2: 取目标毛利率 (优先级见类注释)
        BigDecimal targetGrossMargin = resolveTargetGrossMargin(factoryId, productTypeId, pt);
        if (targetGrossMargin == null) {
            log.debug("SP5 checkMargin: no grossMarginConfig for factory={} product={}, skipped",
                    factoryId, productTypeId);
            return GrossMarginCheckResult.skipped();
        }

        // Step 3: 计算 minPrice = standardCost / (1 - targetGrossMargin)
        //         注意: targetGrossMargin 是 0-1 小数 (e.g. 0.20 = 20%)
        BigDecimal divisor = BigDecimal.ONE.subtract(targetGrossMargin);
        if (divisor.compareTo(BigDecimal.ZERO) <= 0) {
            // targetGrossMargin >= 1.0 → 无意义，跳过
            log.warn("SP5 checkMargin: targetGrossMargin {} >= 1.0 for product {}, skipped",
                    targetGrossMargin, productTypeId);
            return GrossMarginCheckResult.skipped();
        }

        BigDecimal minPrice = standardCost.divide(divisor, 4, RoundingMode.HALF_UP);

        // Step 4: 比较报价 vs minPrice
        if (quotedPrice.compareTo(minPrice) >= 0) {
            return GrossMarginCheckResult.pass();
        } else {
            return GrossMarginCheckResult.warn();
        }
    }

    /**
     * 解析目标毛利率, 优先级: inline > product-level config > factory-wide default.
     *
     * @return targetGrossMargin (0-1 小数), 或 null 表示未配置
     */
    private BigDecimal resolveTargetGrossMargin(String factoryId, String productTypeId,
                                                 ProductType pt) {
        // Priority 1: ProductType.targetGrossMargin inline override (bypasses config repo)
        if (pt.getTargetGrossMargin() != null) {
            return pt.getTargetGrossMargin();
        }

        // Priority 2: product-level FactoryGrossMarginConfig
        Optional<FactoryGrossMarginConfig> productLevelConfig =
                configRepository.findByFactoryIdAndProductTypeIdAndIsActiveTrue(
                        factoryId, productTypeId);
        if (productLevelConfig.isPresent()) {
            return productLevelConfig.get().getTargetGrossMargin();
        }

        // Priority 3: factory-wide default (productTypeId IS NULL)
        Optional<FactoryGrossMarginConfig> factoryDefault =
                configRepository.findFactoryDefault(factoryId);
        return factoryDefault.map(FactoryGrossMarginConfig::getTargetGrossMargin).orElse(null);
    }
}
