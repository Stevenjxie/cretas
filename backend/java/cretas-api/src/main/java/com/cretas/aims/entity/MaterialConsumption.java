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
     * 「已扣减」标记 (deducted-at marker)。
     * <b>NULL = 尚未扣减 (待处理)；非 NULL = usedQuantity 已扣减, 不可重复扣减。</b>
     *
     * <p>语义按计划族由两条扣减路径盖戳, 但含义<b>统一</b> (NULL 恒 = 待扣减):
     * <ul>
     *   <li><b>SAFETY_STOCK (存货生产)</b>: 「小结」({@code InterimSettleServiceImpl §①}) 在原子事务内逐笔扣
     *       {@code MaterialBatch.usedQuantity} + 盖此戳 (= 小结 postedAt) + 写 production_interim_settlement 行。
     *       「撤销小结」按 {@code interim_settled_at == postedAt} 逆转并清戳。</li>
     *   <li><b>结单族 (CUSTOMER_ORDER / MANUAL / …)</b>: 「结单」({@code ProductionPlanServiceImpl.settleProduction}
     *       → {@code MaterialConsumptionRepository.stampInterimSettledForPlan}) 在结单时盖此戳 (= settledAt)。
     *       此族不走小结/撤销, 故戳的时间戳值仅作「已扣减」布尔标记, 不被任何 postedAt 逆转匹配。</li>
     * </ul>
     *
     * <p>统一语义使 {@code interim_settled_at IS NULL} 谓词对所有族一致代表「待扣减」: 盘点账面减除
     * ({@code sumUnsettledConsumptionGroupedByBatch}) 与关单-前守卫 ({@code countUnsettledConsumptionByBatchIds})
     * 据此判定, 无族错配 (2026-07-04 结单族根修前, 结单族行永久 NULL 使谓词失真, 靠 SAFETY_STOCK 族门控绕开)。
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
