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

    @Column(name = "row_status", nullable = false)
    private String rowStatus = "SAVED";
}
