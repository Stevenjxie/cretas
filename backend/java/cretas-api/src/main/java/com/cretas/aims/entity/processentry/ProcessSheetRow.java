package com.cretas.aims.entity.processentry;

import com.cretas.aims.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;

/**
 * 逐工序电子表格行级追踪表 (SP-F Task 1.1).
 *
 * <p>每行存储用户的原始录入 JSON (row_payload) 以及物化后的 ProductionBatch 引用
 * (batch_id / batch_number)。后续 upsert 端点以 uk_sheet_row 唯一约束为键。
 *
 * <p>row_status: SAVED (草稿) → SUBMITTED (已提交物化)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "process_sheet_rows")
@Where(clause = "deleted_at IS NULL")
public class ProcessSheetRow extends BaseEntity {

    public static final String SUBMISSION_LEGACY = "LEGACY";
    public static final String SUBMISSION_DRAFT = "DRAFT";
    public static final String SUBMISSION_SUBMITTED = "SUBMITTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "process_code", nullable = false)
    private String processCode;

    /**
     * 链内唯一工序序号 (SP-F role-mode fix)。
     *
     * <p>role-mode 下多道普通工序可共享同一 archetype process_code (如 'chaoshui'),
     * 故 process_code 不是唯一工序标识; process_order (产品工序链内唯一) 才是。
     * getInventory/getRows 在 process_order 非空时用 (process_code, process_order) 双键过滤,
     * 避免同 archetype 多工序库存/行碰撞。历史行由 V20261027_17 从 row_payload 回填。
     */
    @Column(name = "process_order")
    private Integer processOrder;

    @Column(name = "client_row_id", nullable = false)
    private String clientRowId;

    /** 物化后关联的 ProductionBatch.id；草稿阶段为 null */
    @Column(name = "batch_id")
    private Long batchId;

    /** 物化后关联的批次号快照；草稿阶段为 null */
    @Column(name = "batch_number")
    private String batchNumber;

    /** 原始录入 JSON (JSONB 列) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "row_payload", nullable = false, columnDefinition = "jsonb")
    private String rowPayload;

    /**
     * option F: 纯半成品(SFI)喂的非成品中间道行标记 —— 该行<b>不物化</b> WIP MaterialBatch
     * (无 raw lineage 无法派生 material_type_id), 产出在小结直接入半成品库(SFI)。
     *
     * <p>此类行 {@code batchId == null} (无 WIP/ProductionBatch) 但 {@code batchNumber} = SFI 锚
     * (见 {@link com.cretas.aims.service.wip.WipInventoryService#clerkSemiAnchor}),
     * 借 {@code rowStatus} 与普通 DRAFT (batchNumber==null) / 已物化行 (batchId!=null) 区分,
     * 使小结 SFI in/out 结算能定位其产出过账。
     */
    public static final String STATUS_SAVED_SFI = "SAVED_SFI";

    @Column(name = "row_status", nullable = false)
    private String rowStatus = "SAVED";

    /** 独立于 rowStatus 物化语义的新报工提交状态。 */
    @Column(name = "submission_status", nullable = false, length = 16)
    private String submissionStatus = SUBMISSION_LEGACY;

    /**
     * BY_STOCK 小结 (interim-settle) 产出过账标记 (Task 3 / V20261027_21)。
     *
     * <p>NULL = 待小结 (本行产出尚未过账到 SFI 半成品库 / FG 成品库);
     * 非 NULL = 已计入某次小结，不可重复过账。
     *
     * <p>小结只处理 {@code interim_settled_at IS NULL} 的行，处理后打戳 ——
     * 与 {@link com.cretas.aims.entity.MaterialConsumption#getInterimSettledAt()} (扣减侧)
     * 对称，构成产出侧幂等。重复点击小结找不到未结行 → 0 过账 (天然幂等)。
     */
    @Column(name = "interim_settled_at")
    private java.time.LocalDateTime interimSettledAt;
}
