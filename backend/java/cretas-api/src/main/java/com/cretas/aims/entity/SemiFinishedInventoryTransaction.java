package com.cretas.aims.entity;

import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 半成品库存流水账 (SP1 地基)。
 *
 * <p><b>设计约束</b>: 流水账不可修改 — 无 {@code updated_at} / {@code deleted_at}。
 * 冲销走 REVERSE 类型新行, 不修改原 IN 行。
 *
 * <p><b>支撑能力</b>:
 * <ul>
 *   <li>移动均价精确重放 (IN 行 + balanceAfter 快照)</li>
 *   <li>撤回回退 (REVERSE 行; SP2 复用)</li>
 *   <li>盘点调整 (ADJUST; SP7 复用)</li>
 *   <li>二次加工 lineage (OUT 行; SP2 复用)</li>
 * </ul>
 *
 * <p><b>幂等守卫</b>: 同 source_ref + txnType=IN 已存在 → 409 "已记录产出"。
 */
@Entity
@Table(name = "semi_finished_inventory_transactions", indexes = {
        @Index(name = "idx_sfit_semi_id",          columnList = "semi_finished_id"),
        @Index(name = "idx_sfit_factory_source",   columnList = "factory_id, source_ref"),
        @Index(name = "idx_sfit_factory_txn_type", columnList = "factory_id, txn_type"),
        @Index(name = "idx_sfit_report_id",        columnList = "report_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemiFinishedInventoryTransaction {

    /** 流水类型常量 */
    public static final class TxnType {
        /** 产出入库 (IN: 正数量) */
        public static final String IN      = "IN";
        /** 下道领用 / 二次加工 (OUT: 负数量) */
        public static final String OUT     = "OUT";
        /** 撤回冲销 (REVERSE: 负数量) */
        public static final String REVERSE = "REVERSE";
        /** 盘点调整 (ADJUST: 可正可负) */
        public static final String ADJUST  = "ADJUST";
    }

    /** 来源类型常量 */
    public static final class SourceType {
        public static final String PRODUCTION_OUTPUT   = "PRODUCTION_OUTPUT";
        public static final String SECONDARY_CONSUME   = "SECONDARY_CONSUME";
        public static final String TRANSFER_OUT        = "TRANSFER_OUT";
        public static final String REVERSAL            = "REVERSAL";
        public static final String STOCKTAKE           = "STOCKTAKE";
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 关联 semi_finished_inventory.id */
    @Column(name = "semi_finished_id", nullable = false)
    private Long semiFinishedId;

    /** IN / OUT / REVERSE / ADJUST */
    @Column(name = "txn_type", nullable = false, length = 20)
    private String txnType;

    /**
     * 来源类型: PRODUCTION_OUTPUT / SECONDARY_CONSUME / TRANSFER_OUT / REVERSAL / STOCKTAKE
     */
    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    /**
     * 来源引用 (业务键):
     * IN → intermediateBatchNo 或 productionBatch.batchNumber;
     * OUT → 下道 intermediateBatchNo 或 transferId;
     * REVERSE → ReportReversalLog.id (SP2);
     * ADJUST → StocktakeTask.id (SP7)
     */
    @Column(name = "source_ref", length = 100)
    private String sourceRef;

    /**
     * 数量: IN 为正; OUT/REVERSE 为负; ADJUST 可正可负。
     * 精度 scale-6 对齐 CostRollupUtil。
     */
    @Column(name = "quantity", precision = 12, scale = 6, nullable = false)
    private BigDecimal quantity;

    /**
     * 本次单位成本快照:
     * IN 时 = 本道产出成本 / 本道产出量 (诚实 null 传播: 成本未知则 null);
     * OUT/REVERSE/ADJUST 时 = 操作时的移动均价快照。
     * 精度 scale-4 对齐 CostRollupUtil。
     */
    @Column(name = "unit_cost_at_txn", precision = 14, scale = 4)
    private BigDecimal unitCostAtTxn;

    /** 写入后余量快照 (available_quantity) — 支持移动均价回放 */
    @Column(name = "balance_after", precision = 12, scale = 6)
    private BigDecimal balanceAfter;

    /** 写入后移动均价快照 (unit_cost) — 支持移动均价回放 */
    @Column(name = "balance_cost_after", precision = 14, scale = 4)
    private BigDecimal balanceCostAfter;

    /** 产生此流水的报工 ID (nullable) */
    @Column(name = "report_id")
    private Long reportId;

    /** 操作人 ID (nullable) */
    @Column(name = "operator_id")
    private Long operatorId;

    /**
     * 创建时间 (不可修改; 流水账无 updated_at/deleted_at)。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
