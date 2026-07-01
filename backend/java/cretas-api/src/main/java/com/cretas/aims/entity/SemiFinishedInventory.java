package com.cretas.aims.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import lombok.*;
import org.hibernate.annotations.Type;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 半成品库存 (WIP) — G6 地基 (Phase B 方案1)。
 *
 * <p>每道工序产出 = 一笔有余额的半成品存量行。下道领用它, 未领的留作结余 WIP (张权 G7)。
 * 现状 (Phase A) 只有 {@code carryover_quantity} 单批记录值 + {@code intermediate_batch_no}
 * 工序批次号, 都不是"有余额的库存账"。本实体补这个缺口。
 *
 * <p><b>设计依据</b>: docs/superpowers/specs/2026-06-02-f006-wip-yield-G6G7G8-design.md 第二章方案1。
 *
 * <p><b>权威源职责</b>: WIP 余额 / 领用 / 退回 / 防呆 {@code :max} 全靠本表 (库存权威账)。
 * lineage 边只做溯源副产物, 不做库存计算 (Wave 4)。
 *
 * <p><b>幂等键</b>: {@code intermediate_batch_no} (复用 Phase A 已生成的工序批次号, 工厂内唯一)。
 * 跨天多次报工同一 task 累加 {@code produced_quantity} 到同一行 (Wave 2 接入 submitReport)。
 *
 * <p><b>jsonb 字段</b>: {@code materialBatchRefs} 镜像 {@link ProductionReport#getMaterialBatchRefs()}
 * (同 {@code @Type(JsonType.class)} + {@code columnDefinition="jsonb"}), 从产出报工继承溯源。
 *
 * <p><b>本 Wave 范围</b>: 仅地基 (实体 + 表 + repository)。不改报工/出成率行为 (Wave2/3)。
 */
@Entity
@Table(name = "semi_finished_inventory", indexes = {
        @Index(name = "idx_sfi_factory_batch", columnList = "factory_id, batch_id"),
        @Index(name = "idx_sfi_intermediate_batch_no", columnList = "intermediate_batch_no"),
        @Index(name = "idx_sfi_factory_status", columnList = "factory_id, status")
}, uniqueConstraints = {
        // W8 BUG-SP1-NEW-ROW 修复: 工序批次号工厂内唯一 (幂等键防并发重复 insert)。
        //
        // 生产 PG 的权威约束是 Flyway 的 partial unique index
        //   uq_sfi_intermediate_batch_no ON (factory_id, intermediate_batch_no) WHERE deleted_at IS NULL
        // (允许软删除行 + 一条 live 行同 code)。JPA @UniqueConstraint 无法表达 partial WHERE,
        // 这里声明的是 non-partial composite —— 仅在 H2 测试 (ddl-auto=create-drop) 下建表生效,
        // 让 SP1 new-row 并发竞争触发 ConstraintViolation → 走 catch-retry-into-existing 路径。
        //
        // prod 不受此注解影响: pg-prod=ddl-auto:none (不建), prod=validate (Hibernate validate
        // 不校验/不新增 unique 约束, 仅校验表+列)。prod 防护靠上述 Flyway partial index。
        @UniqueConstraint(name = "uq_sfi_factory_intermediate_batch_no",
                columnNames = {"factory_id", "intermediate_batch_no"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemiFinishedInventory {

    /** WIP 库存状态 */
    public static class Status {
        /** 有余额可领用 */
        public static final String AVAILABLE = "AVAILABLE";
        /** 余额清零 (全部被下道领走) */
        public static final String DEPLETED = "DEPLETED";
        /** 结余余料退回总仓 (D3, Wave 4) */
        public static final String RETURNED = "RETURNED";
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 乐观锁: 并发领用扣减 available_quantity 时防超领 (设计章四风险表) */
    @jakarta.persistence.Version
    @Column(name = "version")
    private Long version;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 生产批次 (关联 ProductionBatch.id) */
    @Column(name = "batch_id")
    private Long batchId;

    /** 工序批次号 — 复用 Phase A 已生成的 (天然幂等键, 工厂内唯一) */
    @Column(name = "intermediate_batch_no", nullable = false, length = 64)
    private String intermediateBatchNo;

    /** 哪道工序任务产出的 (关联 WorkProcessTask.id) */
    @Column(name = "source_work_process_task_id")
    private Long sourceWorkProcessTaskId;

    /** 工序序 (冗余, 便于按序聚合 / 排序) */
    @Column(name = "process_order")
    private Integer processOrder;

    /** 产品类型 id (冗余溯源) */
    @Column(name = "product_type_id", length = 100)
    private String productTypeId;

    /** 该道总产出 (= Σ该 task 的 output, 跨天多次报工累加) */
    @Column(name = "produced_quantity", precision = 12, scale = 2)
    private BigDecimal producedQuantity;

    /** 已被下道领走 (Σ下道引用本 WIP 的 input) */
    @Column(name = "consumed_quantity", precision = 12, scale = 2)
    private BigDecimal consumedQuantity;

    /** 余额 = produced − consumed + adjustment (G7 结余; 防呆下道领用 :max) */
    @Column(name = "available_quantity", precision = 12, scale = 2)
    private BigDecimal availableQuantity;

    /**
     * 盘点净修正量 (可正可负, default 0) — 半成品盘点专用。
     *
     * <p>本系统 {@code availableQuantity} 是"派生-on-mutation"字段 (每次 IN/OUT 由
     * {@code producedQuantity − consumedQuantity} 重算)。盘点差异若直接写 availableQuantity
     * 会被后续报工/领用重算覆盖; 且 producedQuantity/consumedQuantity/accumulatedCost/unitCost
     * 喂 {@code VoucherExportServiceImpl} 半成品入库/发出凭证 + unitCost 分母, 盘点不得改动。
     *
     * <p>故盘点差异只累加进本列, 所有 availableQuantity 重算站点纳入本列:
     * <pre>availableQuantity = producedQuantity − consumedQuantity + adjustmentQuantity</pre>
     * → produced/consumed/成本 保持不变 (凭证+成本不变式保住), 修正持久 (重算不丢)。
     */
    @Column(name = "adjustment_quantity", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal adjustmentQuantity = BigDecimal.ZERO;

    /** 滚动累计成本(人工+材料); null=无成本数据 (单元 A.2 地基; 算法在后续任务) */
    @Column(name = "accumulated_cost", precision = 14, scale = 2)
    private BigDecimal accumulatedCost;

    /** 单位成本 = accumulatedCost / producedQuantity; null=无成本数据 (单元 A.2 地基) */
    @Column(name = "unit_cost", precision = 14, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "unit", length = 16)
    private String unit;

    /** 状态: AVAILABLE / DEPLETED / RETURNED */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = Status.AVAILABLE;

    /**
     * 溯源 (从产出报工继承)。
     * 格式镜像 {@link ProductionReport#getMaterialBatchRefs()}:
     * [{"materialBatchId": Long, "quantity": Number, "unit": String|null}]。
     * 同用 {@code @Type(JsonType.class)} + {@code columnDefinition="jsonb"}。
     */
    @Type(JsonType.class)
    @Column(name = "material_batch_refs", columnDefinition = "jsonb")
    private List<Map<String, Object>> materialBatchRefs;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
