package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.QualityReturnStatus;
import com.cretas.aims.entity.enums.QualityReturnTargetType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import lombok.*;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sprint4-H Q-RETURN-1: 质检退回单实体.
 *
 * <p>质检发现不合格货品时, 退回上游 (供应商 / 委外加工厂). 与 T-RTA
 * customer 退货 (SalesOrder return) 区分 — T-RTA 是下游客户退货,
 * 此处是上游退回.
 *
 * <p>状态机: DRAFT → CONFIRMED → SHIPPED.
 *
 * <p>关联:
 * <ul>
 *   <li>{@code qualityInspectionId}: 触发退回的质检记录 (必填)</li>
 *   <li>{@code targetId}: 接收方 — 供应商 ID 或委外加工厂 ID (依 targetType)</li>
 * </ul>
 *
 * @author Sprint4-H Chat H
 * @since 2026-05-16
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"qualityInspection"})
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "quality_return_orders",
        indexes = {
                @Index(name = "idx_qro_factory", columnList = "factory_id"),
                @Index(name = "idx_qro_inspection", columnList = "quality_inspection_id"),
                @Index(name = "idx_qro_status", columnList = "status"),
                @Index(name = "idx_qro_target", columnList = "target_type, target_id"),
                @Index(name = "idx_qro_number", columnList = "return_number")
        }
)
@Where(clause = "deleted_at IS NULL")
public class QualityReturnOrder extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @NotNull
    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    /** 单号 — 自动生成 QR-YYYYMMDD-NNN */
    @Column(name = "return_number", length = 64, unique = false)
    private String returnNumber;

    /** 关联质检记录 ID (FK quality_inspections.id) */
    @NotNull
    @Column(name = "quality_inspection_id", nullable = false, length = 191)
    private String qualityInspectionId;

    /** 退回目标类型: SUPPLIER / SUBCONTRACT */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32)
    private QualityReturnTargetType targetType;

    /** 接收方 ID (supplier id 或 subcontract id) */
    @NotNull
    @Column(name = "target_id", nullable = false, length = 191)
    private String targetId;

    /** 接收方名称快照 (避免后续供应商改名导致历史单错乱) */
    @Column(name = "target_name", length = 255)
    private String targetName;

    /** 关联物料 ID (raw_material_types.id) — 可选 (跨多物料则留空, 详情在 lines) */
    @Column(name = "material_id", length = 191)
    private String materialId;

    /** 退回数量 */
    @NotNull
    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    /** 单位 (kg, 件, 箱...) */
    @Column(name = "unit", length = 32)
    private String unit;

    /** 退回原因 */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** 状态: DRAFT / CONFIRMED / SHIPPED */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private QualityReturnStatus status = QualityReturnStatus.DRAFT;

    /** 确认时间 (CONFIRMED 时设置) */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 确认人 user_id */
    @Column(name = "confirmed_by")
    private Long confirmedBy;

    /** 发出时间 (SHIPPED 时设置) */
    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    /** 发出人 user_id (物流操作员) */
    @Column(name = "shipped_by")
    private Long shippedBy;

    /** 物流单号 / 运单号 */
    @Column(name = "shipping_tracking_no", length = 128)
    private String shippingTrackingNo;

    /** 创建人 user_id */
    @Column(name = "created_by")
    private Long createdBy;

    /** AI 配置的扩展字段 */
    @Type(JsonBinaryType.class)
    @Column(name = "custom_fields", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> customFields = new HashMap<>();

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isEmpty()) {
            this.id = UUID.randomUUID().toString();
        }
        if (this.status == null) {
            this.status = QualityReturnStatus.DRAFT;
        }
    }

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_inspection_id", referencedColumnName = "id",
            insertable = false, updatable = false)
    private QualityInspection qualityInspection;
}
