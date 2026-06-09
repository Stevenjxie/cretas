package com.cretas.aims.service.bom.impl;

import com.cretas.aims.repository.bom.ProductCostVarianceConfigRepository;
import com.cretas.aims.service.bom.CostVarianceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * SP3 成本超支阈值解析实现.
 *
 * <p>优先级链:
 * <ol>
 *   <li>产品专属配置 (factory_id + product_type_id)</li>
 *   <li>工厂全局配置 (factory_id + product_type_id IS NULL)</li>
 *   <li>系统默认: 10%</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostVarianceServiceImpl implements CostVarianceService {

    static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("10.00");
    private static final int VARIANCE_SCALE = 2;

    private final ProductCostVarianceConfigRepository configRepo;

    @Override
    @Transactional(readOnly = true)
    public BigDecimal resolveThreshold(String factoryId, String productTypeId) {
        if (factoryId == null) return DEFAULT_THRESHOLD;

        // 1. 产品专属
        if (productTypeId != null) {
            var specific = configRepo.findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNull(
                    factoryId, productTypeId);
            if (specific.isPresent()) {
                log.debug("[SP3] 使用产品专属阈值: factoryId={}, productTypeId={}, threshold={}",
                        factoryId, productTypeId, specific.get().getVarianceThreshold());
                return specific.get().getVarianceThreshold();
            }
        }

        // 2. 工厂全局
        var global = configRepo.findByFactoryIdAndProductTypeIdIsNullAndIsActiveTrueAndDeletedAtIsNull(factoryId);
        if (global.isPresent()) {
            log.debug("[SP3] 使用工厂全局阈值: factoryId={}, threshold={}",
                    factoryId, global.get().getVarianceThreshold());
            return global.get().getVarianceThreshold();
        }

        // 3. 系统默认
        log.debug("[SP3] 无配置, 使用系统默认阈值 10%: factoryId={}", factoryId);
        return DEFAULT_THRESHOLD;
    }

    @Override
    public BigDecimal computeVariancePct(BigDecimal actual, BigDecimal standard) {
        if (actual == null || standard == null) return null;
        if (standard.compareTo(BigDecimal.ZERO) <= 0) return null;
        // (actual - standard) / standard * 100, scale-2 HALF_UP
        return actual.subtract(standard)
                .divide(standard, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(VARIANCE_SCALE, RoundingMode.HALF_UP);
    }
}
