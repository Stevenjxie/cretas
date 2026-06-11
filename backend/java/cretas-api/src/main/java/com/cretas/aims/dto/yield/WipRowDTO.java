package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 半成品库存 (WIP) 行 — 只读视图 (GET /production/batches/{id}/wip 输出)。
 *
 * <p>RN / web-admin 展示该批次每道工序的中间品存量: 工序 / 产出 / 已领 / 余额 / 状态。
 * 镜像 {@link com.cretas.aims.entity.SemiFinishedInventory} 字段, 不暴露 version / 软删等内部列。</p>
 *
 * <p>设计依据: docs/superpowers/specs/2026-06-02-f006-wip-yield-G6G7G8-design.md 第二/三章 (G6/G7)。
 * Wave 4 (前端 UI) 新增此只读端点供看板/报工领用展示。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WipRowDTO {

    /** 工序批次号 (天然唯一键; RN 报工领用时作 sourceWipNo 传回)。 */
    private String intermediateBatchNo;

    /** 哪道工序任务产出 (关联 WorkProcessTask.id)。 */
    private Long sourceWorkProcessTaskId;

    /** 工序序 (第几道, 升序)。 */
    private Integer processOrder;

    /** 工序名 (焯水/卤制...; join 回填, 查不到留 null)。 */
    private String processName;

    /** 产品类型 id (冗余溯源)。 */
    private String productTypeId;

    /** 该道总产出 (Σ 该 task output, 跨天累加)。 */
    private BigDecimal producedQuantity;

    /** 已被下道领走 (Σ 下道引用本 WIP 的 input)。 */
    private BigDecimal consumedQuantity;

    /** 余额 = produced − consumed (G7 结余; 下道领用 :max)。 */
    private BigDecimal availableQuantity;

    /** 单位。 */
    private String unit;

    /** 状态: AVAILABLE / DEPLETED / RETURNED。 */
    private String status;
    // ==================== C3: 工厂级半成品重量库存视图扩展字段 ====================

    /**
     * C3: 产品类型名称 (join 回填; 工厂级视图用, 批次级视图留 null)。
     */
    private String productTypeName;

    /**
     * C3: 生产批次 ID (工厂级视图回填; 批次级视图留 null 因已在路径参数中)。
     */
    private Long batchId;
}