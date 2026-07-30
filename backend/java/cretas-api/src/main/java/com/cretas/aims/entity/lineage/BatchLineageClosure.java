package com.cretas.aims.entity.lineage;

import com.cretas.aims.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * 批次 lineage 闭包 — 物化的传递闭包，提供 O(1) 祖先/后代查询。
 *
 * <p><b>由触发器维护</b>: {@code fn_maintain_lineage_closure} 在
 * {@code batch_lineage_edges} INSERT 时自动写入: 自引用深度 0 (source+target 各一条)、
 * 直接边深度 1、source 的所有祖先到 target 深度+1、source 到 target 的所有后代深度+1。
 *
 * <p><b>不参与软删除过滤</b>: 没有 {@code @Where}，因为 closure 从 edges 派生而稳定建索，
 * forensic 追溯必须保证查询返回完整链路。
 *
 * @author Cretas Team
 * @since 2026-05-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "batch_lineage_closure",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_closure",
                        columnNames = {"factory_id", "ancestor_id", "ancestor_type", "descendant_id", "descendant_type"}
                )
        },
        indexes = {
                @Index(name = "idx_blc_anc", columnList = "factory_id, ancestor_id, ancestor_type"),
                @Index(name = "idx_blc_desc", columnList = "factory_id, descendant_id, descendant_type")
        }
)
// Intentionally NO @Where — derivable from edges, but stable indexed.
public class BatchLineageClosure extends BaseEntity {

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

    /** 祖先批次类型 (与 BatchLineageEdge.sourceType 同枚举集) */
    @Column(name = "ancestor_type", nullable = false, length = 30)
    private String ancestorType;

    @Column(name = "ancestor_id", nullable = false, length = 191)
    private String ancestorId;

    @Column(name = "descendant_type", nullable = false, length = 30)
    private String descendantType;

    @Column(name = "descendant_id", nullable = false, length = 191)
    private String descendantId;

    /** 0=self / 1=直接边 / >1=多跳 */
    @Column(name = "depth", nullable = false)
    private Integer depth = 0;

    /**
     * 防止物理删除 — append-only forensic 闭包记录。
     *
     * <p>闭包行由 {@code fn_maintain_lineage_closure} 触发器自动维护, 应用层永不调用
     * delete。意外 {@code repo.delete(entity)} 会触发 {@link BaseEntity} 的
     * {@code @SQLDelete} 软删除, 但闭包不应被任何方式遮盖。
     * {@code @PreRemove} 显式阻止。
     */
    @PreRemove
    protected void preventRemove() {
        throw new UnsupportedOperationException(
                "BatchLineageClosure is append-only audit; physical removal not allowed");
    }
}
