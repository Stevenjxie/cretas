package com.cretas.aims.entity.restaurant;

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
 * 配方版本 (餐饮版 RecipeVersion) — #60 Phase 2 配方版本化.
 *
 * <p>区别于 {@link Recipe} 的扁平 productType×rawMaterial 行 (无版本概念), RecipeVersion
 * 是 <b>独立 row</b>: 每次审批 / 重大改动产生一条新 row, 含完整 snapshot, 状态机, 审批链.
 * 一道菜 (product_type_id) 的完整配方 = 多条 {@link Recipe} 行, RecipeVersion 把这组行
 * 在审批时刻冻结为一份 {@code snapshot_json}.
 *
 * <p>本类借用 {@code bom.BomVersion} 的状态机模式, 但 <b>本地复制 enum</b>
 * ({@link VersionStatus}) 避免 bom↔restaurant 包耦合 (BomVersion 服务于工厂 BOM,
 * RecipeVersion 服务于餐饮配方, 两者业务/数据完全独立).
 *
 * <p>状态机:
 * <pre>
 *   DRAFT ── submitForApproval ──→ PENDING_APPROVAL ── approve ──→ APPROVED ── supersede ──→ OBSOLETE
 *                                   │
 *                                   └── reject ──→ REJECTED (terminal)
 * </pre>
 *
 * <p>不变量: 同 (factory_id, product_type_id) 任一时刻只有 1 条 APPROVED + effective_to IS NULL
 * 的版本 (partial unique {@code uq_rv_one_approved_per_dish} DB 层保证, service 层 approve()
 * 也显式 supersede 旧 APPROVED — defense-in-depth, 与 BomVersion #724 修复同理).
 *
 * <p>RBAC: {@link #snapshotJson} 含食材单价/成本字段 → {@link PriceSensitive}, 响应时
 * RBAC strip 给无 {@code procurement:price:view} 权限的角色.
 *
 * @author Cretas Team / #60 Phase 2
 * @since 2026-06-04
 */
@Entity
@Table(name = "recipe_versions", indexes = {
    @Index(name = "idx_rv_dish_version",  columnList = "factory_id, product_type_id, version_number", unique = true),
    @Index(name = "idx_rv_dish_status",   columnList = "product_type_id, status"),
    @Index(name = "idx_rv_factory_status", columnList = "factory_id, status")
})
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Where(clause = "deleted_at IS NULL")
public class RecipeVersion extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 100)
    private String factoryId;

    /** Logical FK to {@code product_types.id} (the dish). */
    @Column(name = "product_type_id", nullable = false, length = 191)
    private String productTypeId;

    /** 1, 2, 3 ... sequential per {@code (factory_id, product_type_id)}. */
    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    /**
     * Full snapshot of the dish's recipe (all {@link Recipe} rows + qty + cost) at the
     * moment this version was created. Jackson-serialized JSONB. Contains food-cost
     * fields → {@link PriceSensitive} (RBAC strip at response time).
     *
     * <p>Shape (built by service): {@code {productTypeId, productName, items: [{rawMaterialTypeId,
     * standardQuantity, unit, netYieldRate, isMainIngredient, unitPrice, lineCost}, ...],
     * totalFoodCost, snapshotTakenAt}}.
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
     * null = currently effective (only 1 APPROVED per dish, partial unique
     * {@code uq_rv_one_approved_per_dish} enforces). non-null = superseded on this date.
     */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** Rejection reason (only set when status=REJECTED). */
    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    /**
     * State machine (local copy — intentionally NOT reusing {@code bom.BomVersion.VersionStatus}
     * to avoid bom↔restaurant package coupling). {@code DRAFT → PENDING_APPROVAL → APPROVED →
     * OBSOLETE}; {@code REJECTED} terminal sink for reject().
     */
    public enum VersionStatus {
        DRAFT,
        PENDING_APPROVAL,
        APPROVED,
        OBSOLETE,
        REJECTED
    }
}
