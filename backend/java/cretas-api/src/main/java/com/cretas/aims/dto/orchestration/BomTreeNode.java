package com.cretas.aims.dto.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 物料需求树节点 — M-MATTREE-1, Sprint 4 W2.
 *
 * <p>递归数据结构, 表示多级 BOM 展开后的某个节点。
 * level=0 为根节点 (成品), level=1+ 为子节点 (原料 / 半成品)。</p>
 *
 * <ul>
 *   <li><b>isLeaf=true</b>: 此节点是原料 (无 BOM), 提供 availableQuantity + shortfallQuantity</li>
 *   <li><b>isLeaf=false</b>: 此节点是半成品 (有 sub-BOM), children 不空, 不直接计算 shortage</li>
 *   <li><b>cycleDetected=true</b>: 检测到该 typeId 在祖先链中已出现 — 切断展开, 防无限递归</li>
 * </ul>
 *
 * <p>shortfallQuantity 仅在叶子节点上计算 (基于 MaterialBatch 库存)。
 * 半成品节点的 shortage 由其子节点 (递归到叶子) 累加得到, 由前端 / 调用方按需聚合。</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BomTreeNode {

    /** 树深度. 0 = 根 (成品), 1+ = 子节点. */
    private int level;

    /**
     * 节点 typeId.
     * <ul>
     *   <li>level=0: productTypeId (成品 ID)</li>
     *   <li>level≥1: materialTypeId (原料或半成品 ID, 来自父节点 BomRecipeItem.materialTypeId)</li>
     * </ul>
     */
    private String typeId;

    /** 显示名称 (level=0 为产品名; level≥1 为父 BomRecipeItem.materialName) */
    private String name;

    /** 该节点对应的需求数量 (已经按父级数量乘 BomRecipeItem.actualQuantity 累积) */
    private BigDecimal requiredQuantity;

    /** 计量单位 (来自父 BomRecipeItem.unit, level=0 为成品单位/null) */
    private String unit;

    /** 损耗率 (0-100, 来自 100 - yieldRate); level=0 为 null */
    private BigDecimal wastageRate;

    /** 是否叶子节点 — true 表示无 sub-BOM, 为原料. */
    private boolean leaf;

    /** 是否检测到循环 (此 typeId 在祖先链中已出现, 展开被切断防无限递归) */
    private boolean cycleDetected;

    /**
     * 当前库存可用量 (仅 leaf=true 节点填充, 否则 null).
     * 通过 MaterialBatchRepository FEFO 查询并汇总得到。
     */
    private BigDecimal availableQuantity;

    /**
     * 短缺数量 = max(requiredQuantity - availableQuantity, 0). 仅叶子节点填充。
     * 0 表示库存充足, &gt; 0 表示缺料。
     */
    private BigDecimal shortfallQuantity;

    /** 子节点列表 (递归). 叶子节点为空 list. */
    @Builder.Default
    private List<BomTreeNode> children = new ArrayList<>();
}
