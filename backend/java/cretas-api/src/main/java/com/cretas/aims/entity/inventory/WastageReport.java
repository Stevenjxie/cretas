package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 报损单实体 (SP7 §3.3).
 *
 * <p>双轨审批:
 * <ul>
 *   <li>WAREHOUSE 轨 → 财务角色(FINANCE)审批</li>
 *   <li>FACTORY 轨  → 厂长角色(FACTORY_MANAGER)审批</li>
 * </ul>
 *
 * <p>照片强制: photo_urls NOT NULL，service 层在 create/submit 时验证 JSON 数组至少1张。
 *
 * <p>生效动作: approve 时原子写 MaterialBatchAdjustment（type=WASTAGE, 负数），更新 MaterialBatch.quantity。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "wastage_reports",
        indexes = {
                @Index(name = "idx_wastage_factory_track_status", columnList = "factory_id,track_type,status"),
                @Index(name = "idx_wastage_batch",                columnList = "material_batch_id"),
                @Index(name = "idx_wastage_warehouse",            columnList = "warehouse_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_wastage_report_no", columnNames = {"factory_id", "report_no"})
        }
)
@Where(clause = "deleted_at IS NULL")
public class WastageReport extends BaseEntity {

    /**
     * 报损轨道:
     * WAREHOUSE = 仓库报损，需财务审批;
     * FACTORY   = 工厂报损，需厂长审批
     */
    public enum TrackType {
        WAREHOUSE, FACTORY
    }

    public enum Status {
        DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, APPLIED
    }

    public enum WastageReason {
        EXPIRED, DAMAGED, CONTAMINATED, THEFT, OTHER
    }

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 报损单号，工厂内唯一，格式 WR-{YYYYMMDD}-{SEQ} */
    @Column(name = "report_no", nullable = false, length = 50)
    private String reportNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "track_type", nullable = false, length = 20)
    private TrackType trackType;

    /** WAREHOUSE 轨必填，FK → factory_warehouses.id */
    @Column(name = "warehouse_id", length = 64)
    private String warehouseId;

    /** FK → material_batches.id */
    @Column(name = "material_batch_id", nullable = false, length = 191)
    private String materialBatchId;

    /** 冗余品名 ID，展示用，FK → raw_material_types.id */
    @Column(name = "raw_material_type_id", length = 191)
    private String rawMaterialTypeId;

    /** 报损数量，精度 scale-4，ROUND_HALF_UP */
    @Column(name = "wastage_qty", nullable = false, precision = 12, scale = 4)
    private BigDecimal wastageQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "wastage_reason", nullable = false, length = 30)
    private WastageReason wastageReason;

    /** 选 OTHER 时必填的补充说明 */
    @Column(name = "reason_detail", columnDefinition = "TEXT")
    private String reasonDetail;

    /**
     * 照片 URL JSON 数组，NOT NULL。
     * 服务层在 create/submit 时验证至少1张 (fool-proof Rule 3 + 客户硬性要求)。
     * 示例: ["https://oss.../photo1.jpg","https://oss.../photo2.jpg"]
     */
    @Column(name = "photo_urls", nullable = false, columnDefinition = "TEXT")
    private String photoUrls;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private Status status = Status.DRAFT;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    /** 审批角色：FINANCE 或 FACTORY_MANAGER */
    @Column(name = "approver_role", length = 50)
    private String approverRole;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    /** 生效时间（inventory 实际扣减后设置）*/
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
