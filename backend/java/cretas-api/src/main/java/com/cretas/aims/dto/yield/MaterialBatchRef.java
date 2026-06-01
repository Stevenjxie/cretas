package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A2b: 领料关联原料批次引用 DTO.
 * 序列化为 production_reports.material_batch_refs jsonb 数组元素。
 * 1 个批次 = 1 元素列表; N 个批次 = N 元素列表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaterialBatchRef {
    /** 关联 MaterialBatch.id (varchar PK, UUID-style String) */
    private String materialBatchId;
    /** 本次从该批次领用量 */
    private BigDecimal quantity;
    /** 可选, 与 inputUnit 一致 */
    private String unit;
}
