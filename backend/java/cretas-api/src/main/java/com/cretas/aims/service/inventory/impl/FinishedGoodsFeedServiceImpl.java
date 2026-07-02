package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.processentry.FinishedGoodsStockItem;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.inventory.FeedUnitConverter;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
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
    /** 成品投料扣减留痕 (referenceType=PRODUCTION_FEED); 对齐报损 SCRAP 的 producedQuantity 调整审计。 */
    private final FinishedGoodsAdjustmentLogRepository finishedGoodsAdjustmentLogRepository;
    /** 盒⇄kg 折算依据: 读 ProductType.gramsPerUnit (每盒标准克重) — 盒装成品作 kg 道投料来源时换算。 */
    private final ProductTypeRepository productTypeRepository;

    /** ①c 成品投料扣减的调整来源标记 (审计口径, 区别于 SCRAP 报损 / 手工 adjust)。 */
    private static final String REF_PRODUCTION_FEED = "PRODUCTION_FEED";

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

        // 盒⇄kg 折算依据: 批量取每盒克重 (盒装成品作 kg 道投料来源时前端换算 + 拦截缺克重)。诚实 null: 未配 → null。
        Set<String> ptIds = batches.stream()
                .map(FinishedGoodsBatch::getProductTypeId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Map<String, BigDecimal> gramsMap = new HashMap<>();
        if (!ptIds.isEmpty()) {
            productTypeRepository.findByIdIn(ptIds)
                    .forEach(pt -> gramsMap.put(pt.getId(), pt.getGramsPerUnit()));
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
                        .gramsPerUnit(gramsMap.get(b.getProductTypeId()))   // 诚实 null: 未配每盒克重 → null
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public BigDecimal consumeForFeedStrict(String factoryId, String batchNumber, BigDecimal qty, String feedUnit) {
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

        // 🟠 单位折算 (禁止降级): 气调成品常按 盒/托 计量, 逐道投料量 feedUnit 为 kg。
        //   (a) 计数单位成品 (盒/个/件/只) + kg 投料: 依据 ProductType.gramsPerUnit 把 kg 折算为盒数扣减
        //       (盒 = feedKg × 1000 / gramsPerUnit, scale=2 HALF_UP)。🔴 缺每盒克重 → loud-fail (不臆造 1盒=1kg)。
        //   (b) 非计数单位的不一致 (如 托 vs kg, 无 gramsPerUnit 折算依据) → 保持 FG_UNIT_MISMATCH loud-fail。
        //   (c) 单位一致 / kg→kg / 空: 原样扣减 (deductQty = qty, 行为不变)。
        String fgUnit = fg.getUnit();
        BigDecimal deductQty;
        if (FeedUnitConverter.isCountUnit(fgUnit)) {
            deductQty = FeedUnitConverter.feedKgToSourceUnit(qty, fgUnit, resolveGramsPerUnit(fg.getProductTypeId()));
            if (deductQty == null) {
                // 🔴 诚实 null (禁止降级): 盒装成品未配每盒克重 → 无法折算 kg 投料 → 拒绝。
                throw new BusinessException(409, "成品批次 " + batchNumber + " 为" + fgUnit
                        + "装但未配置每盒标准克重(每盒克重), 无法把 kg 投料折算为" + fgUnit + "扣减")
                        .withCode("FG_NO_GRAMS_PER_UNIT")
                        .withHint("请先在产品配置为该成品设置标准克重(每盒克重), 再重试投料")
                        .withSeverity("BLOCKING")
                        .withHintTarget(batchNumber);
            }
        } else if (feedUnit != null && !feedUnit.isBlank() && fgUnit != null && !fgUnit.isBlank()
                && !fgUnit.trim().equalsIgnoreCase(feedUnit.trim())) {
            throw new BusinessException(409, "成品批次单位(" + fgUnit + ")与投料单位(" + feedUnit
                    + ")不一致，无法直接投料")
                    .withCode("FG_UNIT_MISMATCH")
                    .withHint("请选择与本道投料单位一致的成品批次，或改用相同计量单位的库存")
                    .withSeverity("BLOCKING")
                    .withHintTarget(batchNumber);
        } else {
            deductQty = qty; // kg→kg 直投 / 单位一致
        }

        BigDecimal available = fg.getAvailableQuantity();
        if (deductQty.compareTo(available) > 0) {
            // 禁止降级: 不足即抛 (不 clamp), 防 phantom/不足库存生产。余/需按成品原生单位 (盒装时为盒数)。
            String u = fgUnit != null ? fgUnit : "";
            throw new BusinessException(409, "成品库存不足: " + batchNumber
                    + " 余" + available.stripTrailingZeros().toPlainString() + u
                    + " 需" + deductQty.stripTrailingZeros().toPlainString() + u)
                    .withCode("FG_INSUFFICIENT")
                    .withHint("请减少投料量或选择其他成品批次")
                    .withSeverity("BLOCKING")
                    .withHintTarget(batchNumber);
        }

        // 🔴 扣减口径 = 减 producedQuantity (对齐报损 SCRAP, 绝不动 shippedQuantity → 不虚增发货/销售/COGS)。
        //   available = produced − shipped − reserved, 减 produced 即正确降 available。写调整日志留痕审计。
        //   扣减量 deductQty 为成品原生单位 (kg 源=qty; 盒源=折算盒数)。
        BigDecimal beforeProduced = fg.getProducedQuantity() != null ? fg.getProducedQuantity() : BigDecimal.ZERO;
        BigDecimal afterProduced = beforeProduced.subtract(deductQty);

        FinishedGoodsAdjustmentLog logEntry = FinishedGoodsAdjustmentLog.builder()
                .factoryId(fg.getFactoryId())
                .batchId(fg.getId())
                .adjustmentQuantity(deductQty.negate())       // 负数 = 扣减 (成品原生单位)
                .beforeProduced(beforeProduced)
                .afterProduced(afterProduced)
                .reason("生产投料领用 (逐道小结) " + deductQty.stripTrailingZeros().toPlainString()
                        + (fgUnit != null ? fgUnit : "")
                        + (FeedUnitConverter.isCountUnit(fgUnit) ? " (折自 " + qty.stripTrailingZeros().toPlainString() + "kg)" : ""))
                .referenceType(REF_PRODUCTION_FEED)
                .build();
        finishedGoodsAdjustmentLogRepository.save(logEntry);

        fg.setProducedQuantity(afterProduced);
        if (fg.isDepleted()) {
            fg.setStatus(FinishedGoodsBatch.Status.DEPLETED);
        }
        finishedGoodsBatchRepository.save(fg);
        // 返回<b>实际扣减量</b> (成品原生单位: kg 源=qty; 盒源=盒数) — 撤销小结据此 restoreForFeed 精确还回 (口径一致)。
        log.info("[fg-feed] consumeForFeedStrict FG OUT (减produced): factory={}, batchNo={}, feedKg={}, deducted={}{}, "
                        + "beforeProduced={}, afterProduced={}, shipped(不动)={}, available={}",
                factoryId, batchNumber, qty, deductQty, fgUnit != null ? fgUnit : "", beforeProduced, afterProduced,
                fg.getShippedQuantity(), fg.getAvailableQuantity());
        return deductQty;
    }

    /**
     * 盒⇄kg 成本口径: 把 kg 投料量折算为成品来源的原生单位数量 (计数单位→盒数; kg 源→原值), 供小结成本核算
     * (成本 = 折算后数量 × 每单位 {@link #getFeedUnitCost})。与 {@link #consumeForFeedStrict} 折算同源 (口径一致)。
     *
     * <p>🔴 诚实 null: 批次缺失 / 计数单位来源缺每盒克重 → null (调用方判本道产出成本未知, 禁止臆造)。只读不锁。
     */
    @Override
    @Transactional(readOnly = true)
    public BigDecimal resolveFeedQtyInSourceUnit(String factoryId, String batchNumber, BigDecimal feedKg) {
        if (feedKg == null || feedKg.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        FinishedGoodsBatch fg = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                .orElse(null);
        if (fg == null) {
            return null; // 诚实 null: 批次缺失 → 成本未知
        }
        if (!FeedUnitConverter.isCountUnit(fg.getUnit())) {
            return feedKg; // kg / 重量单位: 原值 (不查产品避免多余 IO)
        }
        return FeedUnitConverter.feedKgToSourceUnit(feedKg, fg.getUnit(), resolveGramsPerUnit(fg.getProductTypeId()));
    }

    /** 读产品每盒标准克重 (ProductType.gramsPerUnit); 缺 productTypeId / 产品缺失 / 未配 → null (诚实 null)。 */
    private BigDecimal resolveGramsPerUnit(String productTypeId) {
        if (productTypeId == null || productTypeId.isBlank()) {
            return null;
        }
        return productTypeRepository.findById(productTypeId).map(ProductType::getGramsPerUnit).orElse(null);
    }

    /** 撤销小结: 逆 createFinishedGoodsForInterim (成品入库 un-create) 的调整来源标记。 */
    private static final String REF_INTERIM_REVERSAL = "INTERIM_SETTLE_REVERSAL";

    @Override
    @Transactional
    public void reverseInterimCreate(String factoryId, String batchNumber, BigDecimal qty, Long operatorId) {
        if (qty == null || qty.signum() <= 0) {
            return;
        }
        FinishedGoodsBatch fg = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumberForUpdate(factoryId, batchNumber)
                .orElseThrow(() -> new BusinessException(409, "成品批次不存在, 无法撤销入库: " + batchNumber)
                        .withCode("FG_NOT_FOUND")
                        .withHint("该成品批次已不存在, 可能已被撤销/删除")
                        .withSeverity("BLOCKING")
                        .withHintTarget(batchNumber));
        BigDecimal before = fg.getProducedQuantity() != null ? fg.getProducedQuantity() : BigDecimal.ZERO;
        BigDecimal after = before.subtract(qty);
        BigDecimal shipped = fg.getShippedQuantity() != null ? fg.getShippedQuantity() : BigDecimal.ZERO;
        BigDecimal reserved = fg.getReservedQuantity() != null ? fg.getReservedQuantity() : BigDecimal.ZERO;
        BigDecimal availableAfter = after.subtract(shipped).subtract(reserved);
        if (availableAfter.signum() < 0) {
            // 🔴 下游守卫 (禁止降级): 已发货/预留/生产领用 → 冲销会致负库存 → loud-fail, 不产 phantom。
            throw new BusinessException(409, "成品批次 " + batchNumber + " 已发货/预留/领用 (发"
                    + shipped.stripTrailingZeros().toPlainString() + " 留"
                    + reserved.stripTrailingZeros().toPlainString() + "), 无法撤销小结入库 "
                    + qty.stripTrailingZeros().toPlainString() + "; 请先撤销下游发货/领用")
                    .withCode("FG_DOWNSTREAM_CONSUMED")
                    .withHint("该批次成品已被发货/预留/领用, 请先撤销下游单据再重试")
                    .withSeverity("BLOCKING")
                    .withHintTarget(batchNumber);
        }

        FinishedGoodsAdjustmentLog logEntry = FinishedGoodsAdjustmentLog.builder()
                .factoryId(fg.getFactoryId())
                .batchId(fg.getId())
                .adjustmentQuantity(qty.negate())       // 负数 = 冲销入库
                .beforeProduced(before)
                .afterProduced(after)
                .reason("撤销小结入库 " + qty.stripTrailingZeros().toPlainString()
                        + (fg.getUnit() != null ? fg.getUnit() : ""))
                .referenceType(REF_INTERIM_REVERSAL)
                .operatorId(operatorId)
                .build();
        finishedGoodsAdjustmentLogRepository.save(logEntry);

        fg.setProducedQuantity(after);
        if (availableAfter.signum() <= 0) {
            // 冲销至可用 0: 批次作废置 REVERSED (小结创建的批次被整撤); 其它耗尽走 DEPLETED。
            fg.setStatus(after.signum() <= 0
                    ? FinishedGoodsBatch.Status.REVERSED
                    : FinishedGoodsBatch.Status.DEPLETED);
        }
        finishedGoodsBatchRepository.save(fg);
        log.info("[interim-reverse] reverseInterimCreate FG 入库冲销: factory={}, batchNo={}, qty={}, "
                        + "before={}, after={}, status={}",
                factoryId, batchNumber, qty, before, after, fg.getStatus());
    }

    @Override
    @Transactional
    public void restoreForFeed(String factoryId, String batchNumber, BigDecimal qty, Long operatorId) {
        if (qty == null || qty.signum() <= 0) {
            return;
        }
        FinishedGoodsBatch fg = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumberForUpdate(factoryId, batchNumber)
                .orElseThrow(() -> new BusinessException(409, "成品批次不存在, 无法撤销投料: " + batchNumber)
                        .withCode("FG_NOT_FOUND")
                        .withHint("该成品批次已不存在, 无法还回投料领用")
                        .withSeverity("BLOCKING")
                        .withHintTarget(batchNumber));
        BigDecimal before = fg.getProducedQuantity() != null ? fg.getProducedQuantity() : BigDecimal.ZERO;
        BigDecimal after = before.add(qty);            // 还回 = 加 producedQuantity (对齐 consumeForFeedStrict 减 producedQuantity 的逆)

        FinishedGoodsAdjustmentLog logEntry = FinishedGoodsAdjustmentLog.builder()
                .factoryId(fg.getFactoryId())
                .batchId(fg.getId())
                .adjustmentQuantity(qty)                // 正数 = 还回
                .beforeProduced(before)
                .afterProduced(after)
                .reason("撤销小结投料领用 (还回) " + qty.stripTrailingZeros().toPlainString()
                        + (fg.getUnit() != null ? fg.getUnit() : ""))
                .referenceType(REF_INTERIM_REVERSAL)
                .operatorId(operatorId)
                .build();
        finishedGoodsAdjustmentLogRepository.save(logEntry);

        fg.setProducedQuantity(after);
        // 还量后有可用 → 从 DEPLETED/REVERSED 恢复 AVAILABLE。
        if (!fg.isDepleted() && !FinishedGoodsBatch.Status.EXPIRED.equals(fg.getStatus())
                && !FinishedGoodsBatch.Status.FROZEN.equals(fg.getStatus())) {
            fg.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        }
        finishedGoodsBatchRepository.save(fg);
        log.info("[interim-reverse] restoreForFeed FG 投料还回: factory={}, batchNo={}, qty={}, before={}, after={}",
                factoryId, batchNumber, qty, before, after);
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
