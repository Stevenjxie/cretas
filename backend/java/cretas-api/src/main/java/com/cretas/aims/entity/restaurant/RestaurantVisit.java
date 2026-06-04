package com.cretas.aims.entity.restaurant;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 餐饮到访记录（CRM）实体。
 *
 * <p>#59 Phase 1 — 每次到访一条记录。关键设计：{@code repId} 是<b>到访时</b>绑定营销员的
 * <b>快照</b>（NOT 当前 guest.rep_id）——后续换绑营销员不影响历史到访的业绩归属
 * （Phase 2 阶梯提成按此快照结算）。</p>
 *
 * <p>{@code isQualifying} = (visit_number &gt;= 2)：首次到访不计业绩，第 2 次复购起计业绩。</p>
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
@Table(name = "restaurant_visits",
        indexes = {
                @Index(name = "idx_rest_visit_factory", columnList = "factory_id"),
                @Index(name = "idx_rest_visit_guest", columnList = "guest_id"),
                @Index(name = "idx_rest_visit_rep", columnList = "factory_id,rep_id"),
                @Index(name = "idx_rest_visit_at", columnList = "factory_id,visit_at")
        }
)
@Where(clause = "deleted_at IS NULL")
public class RestaurantVisit extends BaseEntity {

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

    /** 关联散客 ID（FK restaurant_guests.id）。 */
    @Column(name = "guest_id", nullable = false, length = 191)
    private String guestId;

    /**
     * 到访时绑定营销员的<b>快照</b>（FK users.id，可空）。
     *
     * <p>⚠️ 这是到访发生时刻 guest.rep_id 的快照，<b>不是</b>当前 guest.rep_id。
     * 之后换绑营销员（bindRep）不会回写已发生的历史到访，保证 Phase 2 业绩归属正确。</p>
     */
    @Column(name = "rep_id")
    private Long repId;

    // ========== 到访信息 ==========

    /** 第几次到访（1 = 首次，2 = 第二次复购……）。 */
    @Column(name = "visit_number", nullable = false)
    private Integer visitNumber;

    /** 到访时间。 */
    @Column(name = "visit_at", nullable = false)
    private LocalDateTime visitAt;

    /** 本次消费金额。 */
    @PriceSensitive
    @Column(name = "spend_amount", precision = 15, scale = 2)
    private BigDecimal spendAmount;

    /** 到访渠道：WALK_IN（散客到店）/ RESERVATION（预订）/ DELIVERY（外卖）。 */
    @Column(name = "channel", length = 20)
    private String channel;

    /** 桌台 / 包厢编号（可空）。 */
    @Column(name = "table_id", length = 50)
    private String tableId;

    /**
     * 是否计业绩：visit_number &gt;= 2 时为 true（首次到访不计业绩，第 2 次复购起计）。
     */
    @Column(name = "is_qualifying", nullable = false)
    private Boolean isQualifying = false;

    /** 备注。 */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** 录单人 ID（FK users.id）。 */
    @Column(name = "created_by")
    private Long createdBy;
}
