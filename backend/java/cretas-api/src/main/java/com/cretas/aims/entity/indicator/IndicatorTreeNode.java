package com.cretas.aims.entity.indicator;

import com.cretas.aims.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * 指标树节点 — 父子关系 + 权重 + 深度 + 物化路径。
 *
 * <p>同一指标可能在不同上下文有不同父级，因此 (indicator_id, parent_id) 共同唯一。
 * 根节点的 parent_id = NULL。
 * materialized_path 形如 "ind-yield/ind-raw-qualify/ind-supplier-pass"。
 *
 * @author Cretas Team
 * @since 2026-05-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "indicator_tree_nodes",
        indexes = {
                @Index(name = "idx_itn_factory", columnList = "factory_id"),
                @Index(name = "idx_itn_indicator", columnList = "indicator_id"),
                @Index(name = "idx_itn_parent", columnList = "parent_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class IndicatorTreeNode extends BaseEntity {

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

    /** FK to indicators.id */
    @Column(name = "indicator_id", nullable = false, length = 191)
    private String indicatorId;

    /** FK to indicators.id (NULL = root) */
    @Column(name = "parent_id", length = 191)
    private String parentId;

    /** 此节点在父级中的权重 (用于聚合计算，例如加权平均) */
    @Column(name = "weight", precision = 10, scale = 4)
    private BigDecimal weight;

    /** 节点在树中的深度，根为 0 */
    @Column(name = "depth", nullable = false)
    private Integer depth = 0;

    /** 物化路径，例 "root-id/parent-id/this-id"，便于前缀查询整个子树 */
    @Column(name = "materialized_path", length = 1000)
    private String materializedPath;

    /** 同级排序顺序 */
    @Column(name = "display_order")
    private Integer displayOrder;
}
