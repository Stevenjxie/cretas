package com.cretas.aims.entity.lineage;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 批次溯源有向边 — 食品行业批次级 lineage 的核心载体。
 *
 * <p><b>多态设计</b>: source/target 用 (type + id VARCHAR(191)) 而非 dual nullable FK，
 * 因为 ProductionBatch.id 是 Long，MaterialBatch.id 和 FinishedGoodsBatch.id 是 String UUID
 * (见 spec § Critical Type Resolution)。Long 类型批次以字符串形式存入。
 *
 * <p><b>Forensic 记录</b>: 没有 {@code @Where(clause="deleted_at IS NULL")}，
 * lineage 一旦写入永不物理删除 — 客户投诉追溯必须看到完整链路，即便相关批次被软删。
 *
 * <p>插入此表会触发 {@code fn_maintain_lineage_closure} 自动维护 batch_lineage_closure。
 *
 * @author Cretas Team
 * @since 2026-05-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "batch_lineage_edges",
        indexes = {
                @Index(name = "idx_ble_source", columnList = "factory_id, source_id, source_type"),
                @Index(name = "idx_ble_target", columnList = "factory_id, target_id, target_type"),
                @Index(name = "idx_ble_event_time", columnList = "factory_id, event_time")
        }
)
// Intentionally NO @Where — forensic lineage, never physically deleted.
public class BatchLineageEdge extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 边类型: RAW_TO_PRODUCTION / PRODUCTION_TO_FINISHED / FINISHED_TO_SHIPMENT / REWORK / BLEND
     */
    @Column(name = "edge_type", nullable = false, length = 30)
    private String edgeType;

    /**
     * 源批次类型: MATERIAL_BATCH / PRODUCTION_BATCH / FINISHED_BATCH / SHIPMENT_RECORD
     */
    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    /** 源批次 ID (Long 类型批次以字符串形式存入) */
    @Column(name = "source_id", nullable = false, length = 191)
    private String sourceId;

    /** 目标批次类型: 同 source_type 取值集合 */
    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 191)
    private String targetId;

    /** 该边对应的使用数量 */
    @Column(name = "quantity_used", precision = 15, scale = 4)
    private BigDecimal quantityUsed;

    @Column(name = "unit", length = 20)
    private String unit;

    /** 业务发生时刻 (与 created_at 区分; created_at 是物理落库时刻) */
    @Column(name = "event_time")
    private LocalDateTime eventTime;

    @Column(name = "operator_id")
    private Long operatorId;

    /** 附加元数据 (例如 CCP 监控记录引用、回流原因等) */
    @Type(JsonBinaryType.class)
    @Column(name = "meta", columnDefinition = "jsonb")
    private Map<String, Object> meta = new HashMap<>();

    /**
     * 防止物理删除 — forensic lineage 记录。
     *
     * <p>本实体无 {@code @Where(deleted_at IS NULL)}, 客户投诉追溯必须看到完整链路即便相关
     * 批次被软删。意外 {@code repo.delete(entity)} 会触发 {@link BaseEntity} 的
     * {@code @SQLDelete} 软删除, 但 lineage 不应被任何方式遮盖。
     * {@code @PreRemove} 显式阻止。
     */
    @PreRemove
    protected void preventRemove() {
        throw new UnsupportedOperationException(
                "BatchLineageEdge is append-only audit; physical removal not allowed");
    }
}
