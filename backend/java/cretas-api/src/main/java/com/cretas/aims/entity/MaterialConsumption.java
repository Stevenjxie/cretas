package com.cretas.aims.entity;

import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;
/**
 * 原材料消耗记录实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "productionPlan", "productionBatch", "batch", "recorder"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "material_consumptions",
       indexes = {
           @Index(name = "idx_consumption_factory", columnList = "factory_id"),
           @Index(name = "idx_consumption_plan", columnList = "production_plan_id"),
           @Index(name = "idx_consumption_batch", columnList = "batch_id"),
           @Index(name = "idx_consumption_time", columnList = "consumption_time"),
           @Index(name = "idx_consumption_production_batch", columnList = "production_batch_id"),
           @Index(name = "idx_consumption_material_type", columnList = "material_type_id")
       }
)
@Where(clause = "deleted_at IS NULL")
public class MaterialConsumption extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;
    @Column(name = "factory_id", nullable = false)
    private String factoryId;
    @Column(name = "production_plan_id", length = 191)
    private String productionPlanId;
    @Column(name = "production_batch_id")
    private Long productionBatchId;
    @Column(name = "batch_id", nullable = false)
    private String batchId;
    @Column(name = "quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantity;
    @PriceSensitive
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;
    @PriceSensitive
    @Column(name = "total_cost", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalCost;
    @Column(name = "consumption_time", nullable = false)
    private LocalDateTime consumptionTime;
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;
    @Column(name = "recorded_by", nullable = false)
    private Long recordedBy;
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "planned_quantity", precision = 10, scale = 2)
    private BigDecimal plannedQuantity;

    @Column(name = "material_type_id", length = 191)
    private String materialTypeId;

    @Column(name = "source_type", length = 20)
    private String sourceType;

    /**
     * 标记本消耗行已被哪次小结 (interim settlement) 处理。
     * NULL = 尚未小结 (待扣减)；非 NULL = 已计入某次小结，不可重复扣减。
     * Task 3 (小结 Service) 在原子事务内批量设置此字段 + 写 production_interim_settlement 行。
     */
    @Column(name = "interim_settled_at")
    private LocalDateTime interimSettledAt;

    // 关联关系 (使用 @JsonIgnore 防止循环引用)
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_plan_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ProductionPlan productionPlan;

    /**
     * 关联的生产批次实体
     * <p>用于直接访问生产批次信息，避免额外查询</p>
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_batch_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ProductionBatch productionBatch;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", referencedColumnName = "id", insertable = false, updatable = false)
    private MaterialBatch batch;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User recorder;
}
