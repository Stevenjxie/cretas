package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.processentry.FinishedGoodsStockItem;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ①c 成品作投料来源 — {@link FinishedGoodsFeedService} 实现。
 *
 * <p>列表: {@code findAvailableForFeedByFactory} + 产品族过滤 (复用 {@link ProductFamilyResolver} 同 SFI 口径)。
 * 扣减: 悲观锁 + loud-fail (缺失/不足即抛)。成本: 只读 unitCost, 诚实 null。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinishedGoodsFeedServiceImpl implements FinishedGoodsFeedService {

    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    /** 产品族自动识别 (以原料为主) — FG 防呆过滤的"同族"信号来源, 与 SFI 过滤共用同一识别器。 */
    private final ProductFamilyResolver productFamilyResolver;

    @Override
    @Transactional(readOnly = true)
    public List<FinishedGoodsStockItem> listAvailableForFeed(String factoryId, String productTypeId) {
        List<FinishedGoodsBatch> batches = finishedGoodsBatchRepository.findAvailableForFeedByFactory(factoryId);
        if (batches.isEmpty()) {
            return List.of();
        }

        // 产品族过滤 (可选) — 宁缺勿藏: 仅排除"族已知且与计划族不同"的行。
        //   同族: productTypeId 非空 → 解析为族键, 仅同族成品 (猪蹄计划不显牛肉)。
        //   注意 (同 SFI): 不是按 productTypeId 精确匹配 — 熟制前半成品/成品在同族内通用 (兄弟成品共用主原料),
        //   故按族过滤 (以原料为主自动识别), 计划族识别不出 → 全放行 (务实, 不清空可选项)。
        final boolean filterFamily = productTypeId != null && !productTypeId.isBlank();
        if (filterFamily) {
            Set<String> ptForFamily = batches.stream()
                    .map(FinishedGoodsBatch::getProductTypeId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toSet());
            ptForFamily.add(productTypeId);
            Map<String, String> familyMap = productFamilyResolver.resolveFamilies(factoryId, ptForFamily);
            String planFamily = familyMap.get(productTypeId);
            if (planFamily != null) {
                batches = batches.stream()
                        .filter(b -> {
                            String f = b.getProductTypeId() == null ? null : familyMap.get(b.getProductTypeId());
                            return f == null || f.equals(planFamily);   // 族未知放行; 族相同保留; 族不同(牛肉)排除
                        })
                        .collect(Collectors.toList());
            }
            // planFamily == null → 计划族识别不出, 不按族过滤 (全放行, 只保留 available>0)。
        }

        return batches.stream()
                .map(b -> FinishedGoodsStockItem.builder()
                        .batchNumber(b.getBatchNumber())
                        .productTypeId(b.getProductTypeId())
                        .productTypeName(b.getProductName())
                        .productionDate(b.getProductionDate())
                        .availableQuantity(b.getAvailableQuantity())
                        .unit(b.getUnit())
                        .unitCost(b.getUnitCost())            // 诚实 null: 未接通成本 → null
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public BigDecimal consumeForFeedStrict(String factoryId, String batchNumber, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        FinishedGoodsBatch fg = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumberForUpdate(factoryId, batchNumber)
                .orElseThrow(() -> new BusinessException(409, "成品库存不存在: " + batchNumber)
                        .withCode("FG_NOT_FOUND")
                        .withHint("请重新选择仍有库存的成品批次")
                        .withSeverity("BLOCKING")
                        .withHintTarget(batchNumber));
        BigDecimal available = fg.getAvailableQuantity();
        if (qty.compareTo(available) > 0) {
            // 禁止降级: 不足即抛 (不 clamp), 防 phantom/不足库存生产。
            throw new BusinessException(409, "成品库存不足: " + batchNumber
                    + " 余" + available.stripTrailingZeros().toPlainString()
                    + " 需" + qty.stripTrailingZeros().toPlainString())
                    .withCode("FG_INSUFFICIENT")
                    .withHint("请减少投料量或选择其他成品批次")
                    .withSeverity("BLOCKING")
                    .withHintTarget(batchNumber);
        }
        // 物理出库口径 (mirror deductFinishedGoodsInventory): shippedQuantity += qty → 可用量下降。
        BigDecimal shipped = fg.getShippedQuantity() != null ? fg.getShippedQuantity() : BigDecimal.ZERO;
        fg.setShippedQuantity(shipped.add(qty));
        if (fg.isDepleted()) {
            fg.setStatus(FinishedGoodsBatch.Status.DEPLETED);
        }
        finishedGoodsBatchRepository.save(fg);
        log.info("[fg-feed] consumeForFeedStrict FG OUT: factory={}, batchNo={}, qty={}, shipped={}, available={}",
                factoryId, batchNumber, qty, fg.getShippedQuantity(), fg.getAvailableQuantity());
        return qty;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getFeedUnitCost(String factoryId, String batchNumber) {
        if (batchNumber == null || batchNumber.isBlank()) {
            return null;
        }
        return finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                .map(FinishedGoodsBatch::getUnitCost)
                .orElse(null);   // 🔴 诚实 null: 缺失/未知 → null
    }
}
