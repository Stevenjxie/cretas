package com.cretas.aims.service.bom;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.shared.CostRollupUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * SP1: 嵌套 BOM 成本聚合服务。
 *
 * <h2>两类成本来源</h2>
 * <ol>
 *   <li><b>设计时成本 (递归 BOM)</b>: 当 {@link BomRecipeItem#getSubProductTypeId()} 非 null 时,
 *       查找该子产品的当前有效 BOM ({@code status=ACTIVE, is_current=TRUE}),
 *       取其 {@code totalCost / outputQuantityPerUnit} 得到单位成本, 再乘以本行实际用量。
 *       支持多层嵌套 (含循环检测, 最大 10 层)。</li>
 *   <li><b>运行时成本 (先做后用)</b>: 当 {@link BomRecipeItem#getSemiFinishedRefCode()} 非 null 时,
 *       查找 {@link SemiFinishedInventory} 的 {@code unitCost} (移动均价), 优先于 BOM 设计成本。
 *       此场景适用于"先生产半成品入库, 后续成品领用"。</li>
 * </ol>
 *
 * <h2>降级规则 (诚实 null 传播)</h2>
 * <ul>
 *   <li>子 BOM 不存在或 {@code totalCost} 为 null → 返 null (不伪造成本)。</li>
 *   <li>WIP unitCost 为 null → 降级到子 BOM 设计成本; 若亦 null → 返 null。</li>
 *   <li>循环 BOM 检测: 同路径出现已访问的 productTypeId → 返 null (切断递归), 并 WARN 日志。</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-06-11 (SP1 P1 nested BOM cost aggregation)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NestedBomCostService {

    /** 防御性递归深度上限, 防栈溢出 (实际生产 BOM 极少超过 5 层). */
    private static final int MAX_DEPTH = 10;

    private final BomRecipeRepository bomRecipeRepository;
    private final SemiFinishedInventoryRepository sfiRepository;

    /**
     * 解析单个 BOM 行的成本, 处理嵌套子产品和"先做后用" WIP 成本。
     *
     * <p>若 {@code item.subProductTypeId} 为 null, 直接返回 {@code item.computeItemCost()}
     * (即现有的 {@code actualQuantity × unitPrice} 逻辑, 不破坏单层 BOM 行为)。
     *
     * @param factoryId 工厂 ID
     * @param item      BOM 配方行
     * @return 本行成本 (BigDecimal, HALF_UP scale 4); null 表示成本未知 (诚实传播)
     */
    @Transactional(readOnly = true)
    public BigDecimal resolveItemCost(String factoryId, BomRecipeItem item) {
        if (item.getSubProductTypeId() == null || item.getSubProductTypeId().isBlank()) {
            // 普通原材料行 — 走现有逻辑
            return item.computeItemCost();
        }

        BigDecimal actualQty = item.calculateActualQuantity();
        if (actualQty == null || actualQty.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        // 1. "先做后用"优先: 若有 semiFinishedRefCode, 尝试从 WIP 取移动均价
        if (item.getSemiFinishedRefCode() != null && !item.getSemiFinishedRefCode().isBlank()) {
            Optional<SemiFinishedInventory> wip = sfiRepository
                    .findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                            factoryId, item.getSemiFinishedRefCode());
            if (wip.isPresent() && wip.get().getUnitCost() != null) {
                BigDecimal unitCost = wip.get().getUnitCost();
                log.debug("SP1 先做后用: item={} semiFinishedRefCode={} unitCost={}",
                        item.getId(), item.getSemiFinishedRefCode(), unitCost);
                return CostRollupUtil.calcItemCost(actualQty, unitCost);
            }
            // WIP unitCost null or WIP not found → 降级到子 BOM 设计成本
            log.debug("SP1 先做后用降级: semiFinishedRefCode={} WIP unitCost 为 null 或 WIP 不存在, 降级到子 BOM 设计成本",
                    item.getSemiFinishedRefCode());
        }

        // 2. 递归子 BOM 设计成本
        Set<String> visited = new HashSet<>();
        BigDecimal subUnitCost = resolveSubBomUnitCost(factoryId, item.getSubProductTypeId(), visited, 0);
        if (subUnitCost == null) {
            log.debug("SP1 嵌套成本: subProductTypeId={} totalCost 为 null (子 BOM 未配置或未定价)",
                    item.getSubProductTypeId());
            return null;
        }
        return CostRollupUtil.calcItemCost(actualQty, subUnitCost);
    }

    /**
     * 递归解析子产品 BOM 的单位成本 (= totalCost / outputQuantityPerUnit)。
     *
     * @param factoryId       工厂 ID
     * @param productTypeId   子产品 ID
     * @param visited         当前 DFS 路径已访问集合 (循环检测)
     * @param depth           当前递归深度
     * @return 子产品单位成本; null 表示成本未知
     */
    private BigDecimal resolveSubBomUnitCost(String factoryId,
                                              String productTypeId,
                                              Set<String> visited,
                                              int depth) {
        if (depth >= MAX_DEPTH) {
            log.warn("SP1 嵌套 BOM 深度超限 (≥{}): productTypeId={}, visited={}",
                    MAX_DEPTH, productTypeId, visited);
            return null;
        }

        if (visited.contains(productTypeId)) {
            log.warn("SP1 嵌套 BOM 循环检测: productTypeId={} 已在路径 {} 中, 切断递归",
                    productTypeId, visited);
            return null;
        }
        visited.add(productTypeId);

        Optional<BomRecipe> subRecipeOpt = bomRecipeRepository
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrue(factoryId, productTypeId);
        if (subRecipeOpt.isEmpty()) {
            log.debug("SP1 嵌套 BOM: productTypeId={} 无当前有效 BOM, 成本 null", productTypeId);
            visited.remove(productTypeId);
            return null;
        }

        BomRecipe subRecipe = subRecipeOpt.get();
        BigDecimal totalCost = subRecipe.getTotalCost();

        if (totalCost == null) {
            // 子 BOM 有未定价原料 → 整体成本 null (诚实传播)
            log.debug("SP1 嵌套 BOM: subRecipe={} productTypeId={} totalCost 为 null (含未定价原料)",
                    subRecipe.getId(), productTypeId);
            visited.remove(productTypeId);
            return null;
        }

        BigDecimal outputQtyPerUnit = subRecipe.getOutputQuantityPerUnit();
        if (outputQtyPerUnit == null || outputQtyPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("SP1 嵌套 BOM: subRecipe={} outputQuantityPerUnit={} 非正, 无法计算单位成本",
                    subRecipe.getId(), outputQtyPerUnit);
            visited.remove(productTypeId);
            return null;
        }

        // 单位成本 = totalCost / outputQuantityPerUnit, scale 4 HALF_UP
        BigDecimal unitCost = totalCost.divide(outputQtyPerUnit, CostRollupUtil.COST_SCALE, RoundingMode.HALF_UP);
        log.debug("SP1 嵌套 BOM: productTypeId={} totalCost={} / outputQtyPerUnit={} = unitCost={}",
                productTypeId, totalCost, outputQtyPerUnit, unitCost);

        visited.remove(productTypeId);
        return unitCost;
    }

    /**
     * 判断某 BOM 行是否为嵌套子产品行 (即需要递归成本聚合)。
     *
     * @param item BOM 配方行
     * @return true 当且仅当 {@code subProductTypeId} 非 null/blank
     */
    public boolean isNestedComponent(BomRecipeItem item) {
        return item.getSubProductTypeId() != null && !item.getSubProductTypeId().isBlank();
    }
}
