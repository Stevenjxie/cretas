package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.logistics.entity.enums.DistanceEdgeSource;
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
 * 距离边 — 公里数不伪造 (spec §1/§2 决策 6): {@code DEPOT->store} / {@code store->store}
 * 有向边, 缺边时排线算法必须让计划/车次落 {@code NEEDS_ROUTE_DATA}, 禁止直线/伪造 km。
 *
 * <p>Demo 租户脱敏数据落 {@code source=DEMO_FIXTURE}; 客户真实维护矩阵落
 * {@code CUSTOMER_MAINTAINED}; 未来地图供应商落 {@code MAP_PROVIDER}。
 *
 * <p>Maps to table {@code logistics_distance_edges} (V20261028_01)。同厂内有向边唯一
 * (DB {@code uq_lde_edge}, 部分索引 WHERE deleted_at IS NULL — JPA 侧约束语义略严, 见
 * {@link LogisticsOrderBatch} 类注释同类说明)。
 */
@Entity
@Table(name = "logistics_distance_edges",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_lde_edge_jpa",
                        columnNames = {"factory_id", "from_point_id", "to_point_id"})
        },
        indexes = {
                @Index(name = "idx_lde_factory", columnList = "factory_id")
        })
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsDistanceEdge extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (source == null) {
            source = DistanceEdgeSource.DEMO_FIXTURE;
        }
    }

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    /** DEPOT (车场) 或门店 point key — 有向边起点. */
    @Column(name = "from_point_id", length = 64, nullable = false)
    private String fromPointId;

    /** DEPOT (车场) 或门店 point key — 有向边终点. */
    @Column(name = "to_point_id", length = 64, nullable = false)
    private String toPointId;

    @Column(name = "distance_km", nullable = false, precision = 12, scale = 2)
    private BigDecimal distanceKm;

    /** 路径几何 (JSONB, e.g. polyline). Nullable. H2 test 下置 null (同项目既有限制)。 */
    @Type(JsonBinaryType.class)
    @Column(name = "geometry", columnDefinition = "jsonb")
    private Map<String, Object> geometry;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 24, nullable = false)
    private DistanceEdgeSource source;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
