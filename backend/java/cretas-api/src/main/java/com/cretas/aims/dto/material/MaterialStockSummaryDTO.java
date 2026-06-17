package com.cretas.aims.dto.material;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 工厂级原料总库存汇总 DTO (按物料类型聚合, 跨所有仓库).
 *
 * <p>区别于 {@code MaterialBatchDTO} (批次级) 与 分仓库存查询 (按单仓批次级):
 * 本 DTO 是 工厂级 + 按 (物料类型, 称重单位) 聚合 的视图 — 把该物料在所有仓库的
 * 所有在库批次 的当前剩余数量 / 价值 汇总。</p>
 *
 * <p><b>单位口径</b>: {@code unit} 取 {@code MaterialBatch.quantityUnit} (称重单位, 通常 kg),
 * 与被 SUM 的数量同口径; <b>不是</b> {@code RawMaterialType.unit} (显示标签, 可能 "箱")。
 * 聚合按 (materialTypeId, quantityUnit) 分组 — 同一物料若批次单位不一致 (如 g 与 kg 并存),
 * 会拆成多行, 每行内部单位一致, 绝不跨单位混加。详见 repository
 * {@code findStockSummaryByFactory} 文档与 .claude UoM 规则。</p>
 *
 * <h3>聚合口径</h3>
 * <ul>
 *   <li><b>仅在库批次</b>: status NOT IN (DEPLETED, USED_UP, EXPIRED, SCRAPPED, DEFECTIVE)
 *       且 当前剩余 (receiptQuantity - usedQuantity - reservedQuantity) &gt; 0</li>
 *   <li><b>totalQuantity</b>: SUM(receiptQuantity - usedQuantity - reservedQuantity)</li>
 *   <li><b>totalValue</b>: SUM((receiptQuantity - usedQuantity - reservedQuantity) * unitPrice)</li>
 *   <li><b>batchCount</b>: COUNT(批次)</li>
 *   <li><b>warehouseCount</b>: COUNT(DISTINCT warehouseId)</li>
 *   <li><b>avgUnitPrice</b>: totalValue / totalQuantity (移动均价, 除零保护 → null)</li>
 * </ul>
 *
 * <p>JPQL constructor expression 仅填充 SUM/COUNT 与物料标识字段; {@code avgUnitPrice}
 * 在 Service 层计算后回填 (非 SQL 聚合)。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialStockSummaryDTO {

    /** 原料类型ID */
    private String materialTypeId;

    /** 原料名称 */
    private String materialName;

    /** 原料编码 */
    private String materialCode;

    /** 原料类别 (如 主材/辅材/调味料/包材) */
    private String materialCategory;

    /** 单位 = MaterialBatch.quantityUnit (称重单位, 与 totalQuantity 同口径, 通常 kg) */
    private String unit;

    /** 当前总库存 = SUM(receiptQuantity - usedQuantity - reservedQuantity) 跨所有在库批次 */
    private BigDecimal totalQuantity;

    /** 库存总价值 = SUM(剩余数量 * 单价). 价格脱敏: 无 procurement:price:view 权限置 null。 */
    @PriceSensitive
    private BigDecimal totalValue;

    /** 在库批次数 */
    private Long batchCount;

    /** 涉及仓库数 (DISTINCT warehouseId) */
    private Long warehouseCount;

    /** 移动均价 = totalValue / totalQuantity (除零保护 → null). 价格脱敏: 无 procurement:price:view 权限置 null。 */
    @PriceSensitive
    private BigDecimal avgUnitPrice;

    /**
     * JPQL constructor expression 用构造器 — 不含 avgUnitPrice (在 Service 层回填)。
     * 参数顺序/类型必须与 repository 聚合查询 SELECT 严格对齐。
     */
    public MaterialStockSummaryDTO(String materialTypeId, String materialName, String materialCode,
                                   String materialCategory, String unit,
                                   BigDecimal totalQuantity, BigDecimal totalValue,
                                   Long batchCount, Long warehouseCount) {
        this.materialTypeId = materialTypeId;
        this.materialName = materialName;
        this.materialCode = materialCode;
        this.materialCategory = materialCategory;
        this.unit = unit;
        this.totalQuantity = totalQuantity;
        this.totalValue = totalValue;
        this.batchCount = batchCount;
        this.warehouseCount = warehouseCount;
    }
}
