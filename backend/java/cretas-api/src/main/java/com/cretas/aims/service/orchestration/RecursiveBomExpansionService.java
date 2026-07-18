package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.BomTreeNode;
import com.cretas.aims.dto.orchestration.BomTreeResult;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 递归 BOM 展开服务 — M-MATTREE-1, Sprint 4 W2.
 *
 * <p>区别于 {@link BomExpansionService} 的扁平单层展开, 本服务支持多级 BOM:
 * 当某个 {@code materialTypeId} 本身也有当前生效 BomRecipe 配置 (半成品 / 子配方),
 * 递归展开直到所有叶子节点都是无 BOM 的原料。</p>
 *
 * <p><b>循环检测</b>: 使用 DFS 路径 visited-set, 同一条路径上重复出现的 typeId
 * 被标记 {@code cycleDetected=true} 并切断展开, 防止无限递归。
 * 兄弟分支上重复 (diamond pattern) 不视为循环, 各自正常展开。</p>
 *
 * <p><b>短缺计算</b>: 仅叶子节点 (原料) 计算 availableQuantity + shortfallQuantity,
 * 通过 MaterialBatchRepository.findByFactoryIdAndMaterialTypeId 累加可用量。
 * 半成品节点不直接计算 shortage (它们的库存通常在 finished_goods_batches 等不同表中)。</p>
 *
 * @author Cretas Team Sprint 4 Wave 2 Chat G
 * @since 2026-05-16
 */
@Service
@RequiredArgsConstructor
public class RecursiveBomExpansionService {

    private static final Logger log = LoggerFactory.getLogger(RecursiveBomExpansionService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * 防御性递归深度上限 fallback 默认值, 避免极端配置导致栈溢出 (实际生产 BOM 极少超过 5 级).
     * 通过 {@link #maxDepth(String)} 读取实际值, DB 未配置时 fallback 到此处.
     */
    private static final int FALLBACK_MAX_DEPTH = 10;

    private final BomRecipeItemRepository bomRecipeItemRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final ProductTypeRepository productTypeRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    /**
     * Canvas-Thresholds resolver (Phase A) — overlays FALLBACK_MAX_DEPTH with per-factory config.
     */
    private final ThresholdResolverService thresholdResolver;

    private int maxDepth(String factoryId) {
        return thresholdResolver.getInteger(factoryId, ThresholdKeys.BOM_MAX_DEPTH, FALLBACK_MAX_DEPTH);
    }

    /**
     * 入口方法: 展开指定产品的多级 BOM 树.
     *
     * @param factoryId         工厂 ID
     * @param productTypeId     根产品 ID
     * @param productionQuantity 计划生产数量
     * @return BomTreeResult 包含树根 + 统计信息
     */
    @Transactional(readOnly = true)
    public BomTreeResult expandTree(String factoryId,
                                    String productTypeId,
                                    BigDecimal productionQuantity) {
        if (productTypeId == null || productTypeId.isBlank()) {
            throw new IllegalArgumentException("productTypeId 不能为空");
        }
        if (productionQuantity == null || productionQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("productionQuantity 必须大于 0");
        }

        // 根节点名称查找
        String rootName = productTypeRepository.findById(productTypeId)
                .map(ProductType::getName)
                .orElse(productTypeId);

        // DFS 路径 visited-set + 全局收集循环 typeId
        Set<String> pathVisited = new LinkedHashSet<>();
        Set<String> cycleTypeIds = new HashSet<>();

        BomTreeNode root = expandNode(
                factoryId, productTypeId, rootName, productionQuantity,
                /* level */ 0,
                /* unit */ null,
                /* wastageRate */ null,
                pathVisited,
                cycleTypeIds
        );

        // 后置统计: 计算 maxDepth + leafCount + shortfallLeafCount
        Stats stats = new Stats();
        collectStats(root, stats);

        BomTreeResult result = BomTreeResult.builder()
                .rootProductTypeId(productTypeId)
                .rootQuantity(productionQuantity)
                .maxDepth(stats.maxDepth)
                .leafCount(stats.leafCount)
                .shortfallLeafCount(stats.shortfallLeafCount)
                .cycleDetected(!cycleTypeIds.isEmpty())
                .cycleTypeIds(new ArrayList<>(cycleTypeIds))
                .root(root)
                .build();

        log.info("[M-MATTREE-1] 递归 BOM 展开完成: factoryId={}, productTypeId={}, quantity={}, " +
                        "maxDepth={}, leafCount={}, shortfallLeafCount={}, cycleDetected={}",
                factoryId, productTypeId, productionQuantity,
                stats.maxDepth, stats.leafCount, stats.shortfallLeafCount, result.isCycleDetected());

        return result;
    }

    /**
     * 递归展开单个节点.
     *
     * @param factoryId    工厂 ID
     * @param typeId       本节点 typeId (level=0 为 productTypeId, level≥1 为 materialTypeId)
     * @param name         节点名 (来自父 BomRecipeItem.materialName 或 ProductType.name)
     * @param quantity     本节点需求量
     * @param level        当前深度
     * @param unit         来自父 BomRecipeItem.unit (level=0 为 null)
     * @param wastageRate  来自父 BomRecipeItem 推导 (level=0 为 null)
     * @param pathVisited  DFS 路径上已访问的 typeId (用于循环检测)
     * @param cycleTypeIds 全局循环 typeId 集合 (输出参数)
     */
    private BomTreeNode expandNode(String factoryId,
                                   String typeId,
                                   String name,
                                   BigDecimal quantity,
                                   int level,
                                   String unit,
                                   BigDecimal wastageRate,
                                   Set<String> pathVisited,
                                   Set<String> cycleTypeIds) {

        // 循环检测
        if (pathVisited.contains(typeId)) {
            cycleTypeIds.add(typeId);
            log.warn("[M-MATTREE-1] 循环检测命中: typeId={} 已在路径中 ({}), 切断展开",
                    typeId, pathVisited);
            return BomTreeNode.builder()
                    .level(level)
                    .typeId(typeId)
                    .name(name)
                    .requiredQuantity(quantity)
                    .unit(unit)
                    .wastageRate(wastageRate)
                    .leaf(true)
                    .cycleDetected(true)
                    .children(new ArrayList<>())
                    .build();
        }

        // 深度上限
        int depthLimit = maxDepth(factoryId);
        if (level > depthLimit) {
            log.warn("[M-MATTREE-1] 达到 MAX_DEPTH={} 切断展开: typeId={}", depthLimit, typeId);
            return buildLeaf(factoryId, typeId, name, quantity, level, unit, wastageRate);
        }

        // 查找该 typeId 是否有 BOM (有则视为半成品 / 成品, 无则为叶子原料)
        List<BomRecipeItem> bomItems = bomRecipeItemRepository.findCurrentByProduct(factoryId, typeId);

        if (bomItems == null || bomItems.isEmpty()) {
            // 叶子节点: 查库存 + 计算短缺
            return buildLeaf(factoryId, typeId, name, quantity, level, unit, wastageRate);
        }

        // 半成品节点: 递归展开 children
        pathVisited.add(typeId);
        List<BomTreeNode> children = new ArrayList<>();
        for (BomRecipeItem item : bomItems) {
            BigDecimal childRequired = item.calculateActualQuantity() != null
                    ? item.calculateActualQuantity().multiply(quantity).setScale(6, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            String childUnit = item.getUnit();
            BigDecimal childWastage = deriveWastageFromYield(item.getYieldRate());
            String childName = item.getMaterialName() != null ? item.getMaterialName() : item.getMaterialTypeId();

            BomTreeNode child = expandNode(
                    factoryId,
                    item.getMaterialTypeId(),
                    childName,
                    childRequired,
                    level + 1,
                    childUnit,
                    childWastage,
                    pathVisited,
                    cycleTypeIds
            );
            children.add(child);
        }
        pathVisited.remove(typeId);  // backtrack — 同一 typeId 在兄弟分支 (diamond) 仍可展开

        return BomTreeNode.builder()
                .level(level)
                .typeId(typeId)
                .name(name)
                .requiredQuantity(quantity)
                .unit(unit)
                .wastageRate(wastageRate)
                .leaf(false)
                .cycleDetected(false)
                .availableQuantity(null)  // 半成品库存暂不计算
                .shortfallQuantity(null)
                .children(children)
                .build();
    }

    /**
     * 构造叶子节点 (原料), 查询 MaterialBatch 库存累加可用量并计算 shortage.
     */
    private BomTreeNode buildLeaf(String factoryId, String typeId, String name,
                                  BigDecimal quantity, int level, String unit, BigDecimal wastageRate) {
        // 用名称兜底: 若父传 name 为空, 查 raw_material_types 获取
        if (name == null || name.isBlank() || name.equals(typeId)) {
            try {
                String matName = rawMaterialTypeRepository.findById(typeId)
                        .map(rmt -> rmt.getName())
                        .orElse(null);
                if (matName != null) name = matName;
            } catch (Exception ignored) {
                // 容忍 — name 保持原值
            }
        }

        // 库存累加 (跨 warehouse 简化合计 — 多仓策略由 BomExpansionService 主路径处理)
        BigDecimal available = BigDecimal.ZERO;
        try {
            List<MaterialBatch> batches = materialBatchRepository
                    .findByFactoryIdAndMaterialTypeId(factoryId, typeId);
            for (MaterialBatch b : batches) {
                if (b.getReceiptQuantity() == null) continue;
                BigDecimal used = b.getUsedQuantity() != null ? b.getUsedQuantity() : BigDecimal.ZERO;
                BigDecimal reserved = b.getReservedQuantity() != null ? b.getReservedQuantity() : BigDecimal.ZERO;
                BigDecimal remaining = b.getReceiptQuantity().subtract(used).subtract(reserved);
                if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                    available = available.add(remaining);
                }
            }
        } catch (Exception e) {
            log.warn("[M-MATTREE-1] 库存查询失败 leaf typeId={}: {}", typeId, e.getMessage());
        }

        BigDecimal shortfall = quantity.subtract(available);
        if (shortfall.compareTo(BigDecimal.ZERO) < 0) shortfall = BigDecimal.ZERO;

        return BomTreeNode.builder()
                .level(level)
                .typeId(typeId)
                .name(name)
                .requiredQuantity(quantity)
                .unit(unit)
                .wastageRate(wastageRate)
                .leaf(true)
                .cycleDetected(false)
                .availableQuantity(available)
                .shortfallQuantity(shortfall)
                .children(new ArrayList<>())
                .build();
    }

    /** 后序遍历统计深度 / 叶子数 / 短缺叶子数 */
    private void collectStats(BomTreeNode node, Stats stats) {
        if (node == null) return;
        if (node.getLevel() > stats.maxDepth) stats.maxDepth = node.getLevel();
        if (node.isLeaf()) {
            stats.leafCount++;
            if (node.getShortfallQuantity() != null
                    && node.getShortfallQuantity().compareTo(BigDecimal.ZERO) > 0) {
                stats.shortfallLeafCount++;
            }
        } else if (node.getChildren() != null) {
            for (BomTreeNode child : node.getChildren()) {
                collectStats(child, stats);
            }
        }
    }

    private BigDecimal deriveWastageFromYield(BigDecimal yieldRate) {
        if (yieldRate == null) return null;
        return HUNDRED.subtract(yieldRate).max(BigDecimal.ZERO);
    }

    /** mutable accumulator for tree statistics */
    private static class Stats {
        int maxDepth;
        int leafCount;
        int shortfallLeafCount;
    }
}
