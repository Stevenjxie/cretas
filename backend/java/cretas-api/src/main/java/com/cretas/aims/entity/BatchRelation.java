package com.cretas.aims.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 批次关联实体类
 * 用于管理生产批次与原材料批次之间的追溯关联关系
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Data
@Entity
@Table(name = "batch_relations")
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class BatchRelation extends BaseEntity {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 20)
    private String factoryId;

    /**
     * 生产批次ID（输出批次）
     */
    @Column(name = "production_batch_id", nullable = false)
    private Long productionBatchId;

    /**
     * 原材料批次ID（输入批次）
     */
    @Column(name = "material_batch_id", nullable = false, length = 50)
    private String materialBatchId;

    /**
     * 关联类型: INPUT/OUTPUT/REWORK/BLEND
     */
    @Column(name = "relation_type", length = 20)
    private String relationType;

    /**
     * 使用数量
     */
    @Column(name = "quantity_used", precision = 15, scale = 3)
    private BigDecimal quantityUsed;

    /**
     * 单位
     */
    @Column(name = "unit", length = 20)
    private String unit;

    /**
     * 使用时间
     */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /**
     * 批次位置/阶段
     */
    @Column(name = "stage", length = 50)
    private String stage;

    /**
     * 操作人ID
     */
    @Column(name = "operator_id")
    private Long operatorId;

    /**
     * 备注
     */
    @Column(name = "remarks", length = 500)
    private String remarks;

    /**
     * 是否验证通过
     */
    @Column(name = "verified")
    private Boolean verified;

    /**
     * 验证时间
     */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /**
     * 验证人ID
     */
    @Column(name = "verified_by")
    private Long verifiedBy;
}
