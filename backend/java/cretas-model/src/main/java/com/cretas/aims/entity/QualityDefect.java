package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.DefectStatus;
import com.cretas.aims.entity.enums.DefectType;
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
 * Sprint4-H Q-PROCESS-1: 工序质检不良记录实体.
 *
 * <p>QualityInspection 检出 failCount > 0 时, 由检验员 / 质量经理逐条登记不良详情:
 * 缺陷类型 / 数量 / 原因 / 处置动作 — 直至 CLOSED 形成闭环.
 *
 * <p>与 {@link QualityInspection} 关系: 1 inspection → N defects.
 * 一次质检的多个缺陷分别记录, 便于按 defectType 报表统计 + 趋势分析.
 *
 * <p>与 {@link RawMaterialType} (materialId): 可选关联 — 物料相关缺陷 (例如原料
 * 不达标导致成品不良) 可挂物料. 成品缺陷则 materialId 留空.
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
@Table(name = "quality_defects",
        indexes = {
                @Index(name = "idx_qd_factory", columnList = "factory_id"),
                @Index(name = "idx_qd_inspection", columnList = "quality_inspection_id"),
                @Index(name = "idx_qd_status", columnList = "status"),
                @Index(name = "idx_qd_type", columnList = "defect_type"),
                @Index(name = "idx_qd_material", columnList = "material_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class QualityDefect extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @NotNull
    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    /** 关联质检记录 ID (FK quality_inspections.id) */
    @NotNull
    @Column(name = "quality_inspection_id", nullable = false, length = 191)
    private String qualityInspectionId;

    /** 关联物料 ID (FK raw_material_types.id) — 可选 */
    @Column(name = "material_id", length = 191)
    private String materialId;

    /** 缺陷类型 */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "defect_type", nullable = false, length = 32)
    private DefectType defectType;

    /** 不良数量 */
    @NotNull
    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    /** 缺陷原因 (文本描述) */
    @Column(name = "cause", columnDefinition = "TEXT")
    private String cause;

    /** 处置动作 (返工/报废/降级/退回供应商/其他) */
    @Column(name = "handling_action", columnDefinition = "TEXT")
    private String handlingAction;

    /** 处理人 user_id (分派后填充) */
    @Column(name = "assigned_to")
    private Long assignedTo;

    /** 状态: OPEN / IN_PROGRESS / CLOSED */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private DefectStatus status = DefectStatus.OPEN;

    /** 闭环时间 (status 进入 CLOSED 时设置) */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** 闭环验证人 user_id */
    @Column(name = "closed_by")
    private Long closedBy;

    /** 闭环备注 (验证结论) */
    @Column(name = "close_notes", columnDefinition = "TEXT")
    private String closeNotes;

    /** 记录人 user_id (登记时记录, 不会变) */
    @Column(name = "created_by")
    private Long createdBy;

    /** AI 配置的扩展字段 (jsonb) — 与 QualityInspection 同 pattern */
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
            this.status = DefectStatus.OPEN;
        }
    }

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_inspection_id", referencedColumnName = "id",
            insertable = false, updatable = false)
    private QualityInspection qualityInspection;
}
