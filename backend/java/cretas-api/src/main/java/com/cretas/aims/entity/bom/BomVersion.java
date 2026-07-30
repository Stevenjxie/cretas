package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * M-BOM-VER-1 BOM 版本独立化 (Sprint 3 Track-H).
 *
 * <p>区别于 {@link BomRecipe#getVersion()} 的 Integer 字段, BomVersion 是 <b>独立 row</b>:
 * 每次审批 / 重大改动产生一条新 row, 含完整 snapshot, 状态机, 审批链路.
 *
 * <p>双轨过渡 (6 月期): {@link BomRecipe#getVersion()} Integer + {@link BomRecipe#getIsCurrent()}
 * Boolean 不删. BomRecipe.version 是"快照式 latest", BomVersion 是历史追溯. 6 月过渡期后
 * deprecate BomRecipe.version.
 *
 * <p>状态机:
 * <pre>
 *   DRAFT ─ submitForApproval ─→ PENDING_APPROVAL ─ approve ──→ APPROVED ─ supersede ─→ OBSOLETE
 *                                 │
 *                                 └── reject ─→ REJECTED (terminal)
 * </pre>
 *
 * <p>APPROVED → OBSOLETE 由 PostgreSQL trigger {@code trg_bom_version_supersede} 自动触发:
 * 新 BomVersion 进入 APPROVED + effective_to=NULL 时, 同 bom_recipe_id 的旧 APPROVED 行
 * 自动 effective_to=NEW.effective_from-1 + status=OBSOLETE. 防御性 service 层也手动 sync
 * {@link BomRecipe#getIsCurrent()}.
 *
 * <p>RBAC: {@link #snapshotJson} 含 cost 字段 → {@link PriceSensitive}, RBAC strip 给无
 * {@code procurement:price:view} 权限的角色 (warehouse_manager, operator, quality_inspector).
 *
 * <p>反查: {@code BomReverseQueryService} 查询某物料关联的 BomVersion 走
 * {@code idx_bri_material} (BomRecipeItem.factory_id + material_type_id) 加 join.
 *
 * @author Cretas Team / Sprint 3 Track-H
 * @since 2026-05-16
 */
@Entity
@Table(name = "bom_versions", indexes = {
    @Index(name = "idx_bv_recipe_version",  columnList = "factory_id, bom_recipe_id, version_number", unique = true),
    @Index(name = "idx_bv_recipe_status",   columnList = "bom_recipe_id, status"),
    @Index(name = "idx_bv_factory_status",  columnList = "factory_id, status"),
    @Index(name = "idx_bv_ecn",             columnList = "ecn_id")
})
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Where(clause = "deleted_at IS NULL")
public class BomVersion extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** Logical FK to {@code bom_recipes.id} (no hard DB FK — concurrent migration coordination). */
    @Column(name = "bom_recipe_id", nullable = false, length = 191)
    private String bomRecipeId;

    /** 1, 2, 3 ... sequential per {@code bom_recipe_id}. Unique with {@code (factory_id, bom_recipe_id)}. */
    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    /**
     * Full snapshot of {@link BomRecipe} + {@link BomRecipeItem} list at approval time.
     * Jackson-serialized JSONB. Includes cost fields → {@link PriceSensitive}.
     *
     * <p>Service layer must {@code buildSnapshot(BomRecipe)} → Map containing recipe header
     * + items array (material_type_id / standard_quantity / yield_rate / unit_price / item_cost ...).
     */
    @PriceSensitive
    @Type(JsonBinaryType.class)
    @Column(name = "snapshot_json", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> snapshotJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private VersionStatus status = VersionStatus.DRAFT;

    /** Effective start date (set when APPROVED). null while DRAFT/PENDING/REJECTED. */
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /**
     * null = currently effective (only 1 per bom_recipe_id, partial unique
     * {@code uq_bv_one_current_per_recipe} enforces).
     * non-null = superseded on this date.
     */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /**
     * Logical FK to {@link EngineeringChangeNotice#getId()}.
     * null = manual version (no ECN, e.g. quick-fix or first version creation).
     */
    @Column(name = "ecn_id", length = 191)
    private String ecnId;

    /** Rejection reason (only set when status=REJECTED). */
    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    /**
     * State machine. Brief specifies {@code DRAFT → PENDING_APPROVAL → APPROVED → OBSOLETE};
     * {@code REJECTED} added as terminal sink for {@code BomVersionService.reject()}.
     */
    public enum VersionStatus {
        DRAFT,
        PENDING_APPROVAL,
        APPROVED,
        OBSOLETE,
        REJECTED
    }
}
