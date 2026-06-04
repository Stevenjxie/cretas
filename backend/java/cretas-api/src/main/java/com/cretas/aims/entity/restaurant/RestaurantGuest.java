package com.cretas.aims.entity.restaurant;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.restaurant.enums.RestaurantGuestLifecycle;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 餐饮散客（CRM 生命周期 + 营销员归属）实体。
 *
 * <p>#59 Phase 1 — 邓总模型（原话）：散客首次登记<b>不计业绩</b> → 营销员维护
 * （有进包厢/9折/赠果盘/2瓶啤酒权限）→ <b>第二次复购才计业绩</b>（归属维护的营销员）
 * → 重点客户（来 3 次+）<b>必须进包厢</b>。</p>
 *
 * <p>租户隔离方式与 {@link WastageRecord} / {@link Recipe} 一致：factory_id 列 +
 * repository 按 factoryId 过滤 + BaseEntity 软删除（@Where deleted_at IS NULL）。</p>
 *
 * <p>手机号为 PII，对非管理角色在 service 层脱敏为后 4 位（非 @PriceSensitive，
 * 后者只处理金额字段）。</p>
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "restaurant_guests",
        indexes = {
                @Index(name = "idx_rest_guest_factory", columnList = "factory_id"),
                @Index(name = "idx_rest_guest_rep", columnList = "factory_id,rep_id"),
                @Index(name = "idx_rest_guest_stage", columnList = "factory_id,lifecycle_stage"),
                @Index(name = "idx_rest_guest_last_visit", columnList = "factory_id,last_visit_at")
        }
)
@Where(clause = "deleted_at IS NULL")
public class RestaurantGuest extends BaseEntity {

    // ========== 主键 ==========

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    // ========== 归属 ==========

    @Column(name = "factory_id", nullable = false, length = 100)
    private String factoryId;

    /** 顾客姓名（或昵称）。 */
    @Column(name = "name", length = 100)
    private String name;

    /** 手机号（PII，对非管理角色脱敏为后 4 位）。partial unique (factory_id, phone) WHERE deleted_at IS NULL。 */
    @Column(name = "phone", length = 30)
    private String phone;

    // ========== 营销员归属 ==========

    /** 维护该客户的营销员 ID（FK users.id，可空——散客可能尚无营销员维护）。 */
    @Column(name = "rep_id")
    private Long repId;

    /** 营销员绑定时间。 */
    @Column(name = "rep_bound_at")
    private LocalDateTime repBoundAt;

    // ========== 到访统计 ==========

    /** 累计到访次数（首次到访 = 1，不计业绩；第 2 次起计业绩）。 */
    @Column(name = "visit_count", nullable = false)
    private Integer visitCount = 0;

    /** 首次到访时间。 */
    @Column(name = "first_visit_at")
    private LocalDateTime firstVisitAt;

    /** 最近到访时间（用于「即将流失」判断）。 */
    @Column(name = "last_visit_at")
    private LocalDateTime lastVisitAt;

    // ========== 权限配置 (JSONB) ==========

    /**
     * 营销员权限配置 JSONB。结构：
     * {@code {"boxRoom": true, "discountPct": 90, "fruitPlate": true, "beerBottles": 2}}
     * 对应邓总：进包厢 / 9折 / 赠果盘 / 2瓶啤酒。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "perk_config", columnDefinition = "jsonb")
    private Map<String, Object> perkConfig;

    // ========== 生命周期 ==========

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_stage", nullable = false, length = 20)
    private RestaurantGuestLifecycle lifecycleStage = RestaurantGuestLifecycle.NEW;

    /** 备注。 */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** 登记人 ID（FK users.id）。 */
    @Column(name = "created_by")
    private Long createdBy;

    // ========== 乐观锁 ==========

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
