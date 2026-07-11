package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * 停靠点 — 车次内门店顺序 (人工可调序/跨车次移动)。
 *
 * <p>一订单只属一条有效车次, 由 DB {@code uq_ls_order_single_trip} 保障
 * (spec §3: 防重复分配/漏排)。同车次内顺序号唯一 (DB {@code uq_ls_trip_seq})。两者均为
 * 部分索引 (WHERE deleted_at IS NULL) — JPA 侧约束语义略严, 见 {@link LogisticsOrderBatch}
 * 类注释同类说明。
 *
 * <p>Maps to table {@code logistics_stops} (V20261028_01)。
 */
@Entity
@Table(name = "logistics_stops",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_ls_trip_seq_jpa", columnNames = {"trip_id", "sequence_no"}),
                @UniqueConstraint(name = "uq_ls_order_single_trip_jpa", columnNames = {"delivery_order_id"})
        },
        indexes = {
                @Index(name = "idx_ls_trip", columnList = "trip_id")
        })
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsStop extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (legDistanceKm == null) {
            legDistanceKm = BigDecimal.ZERO;
        }
    }

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    /** FK → {@link LogisticsTrip#getId()} (plain String, no JPA relation). */
    @Column(name = "trip_id", length = 36, nullable = false)
    private String tripId;

    /** FK → {@link LogisticsDeliveryOrder#getId()} (plain String, no JPA relation). Unique per DB {@code uq_ls_order_single_trip}. */
    @Column(name = "delivery_order_id", length = 36, nullable = false)
    private String deliveryOrderId;

    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    /** 与上一停靠点(或车场 DEPOT)间的公里数 — 不伪造, 缺边由 trip 落 NEEDS_ROUTE_DATA。 */
    @Column(name = "leg_distance_km", nullable = false, precision = 12, scale = 2)
    private BigDecimal legDistanceKm;

    @Column(name = "arrival_window_start", length = 8)
    private String arrivalWindowStart;

    @Column(name = "arrival_window_end", length = 8)
    private String arrivalWindowEnd;

    /**
     * 停靠点几何 (JSONB). Nullable — Phase 3 排线算法写入。H2 test 下置 null
     * (同项目 jsonb round-trip 既有限制)。
     */
    @Type(JsonBinaryType.class)
    @Column(name = "geometry", columnDefinition = "jsonb")
    private Map<String, Object> geometry;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
