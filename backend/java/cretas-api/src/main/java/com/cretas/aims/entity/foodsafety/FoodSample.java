package com.cretas.aims.entity.foodsafety;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 食品留样实体 (Food Sample Retention) — Sprint 9 P2.A.
 *
 * <p>法定: GB 31654-2021 "餐饮服务从业人员食品安全管理规范" — 食品制售单位
 * 应对供应给消费者的食品按品种留样 ≥ 125g, 冷藏 (≤ 8 ℃) 保存 ≥ 48 小时,
 * 用于客户投诉时的食源性疾病溯源检测.
 *
 * <p>核心字段:
 * <ul>
 *   <li>{@code sampleTime} 留样开始时间</li>
 *   <li>{@code expireTime} 销毁时间 = sampleTime + 48h (per GB 31654-2021)</li>
 *   <li>{@code sampleQuantity} 留样重量 (≥ 125g 法规要求)</li>
 *   <li>{@code storageTemperatureMax} 冷藏上限 (≤ 8.0 ℃ 默认)</li>
 *   <li>{@code status} RETAINED → DISPOSED / USED_FOR_INSPECTION</li>
 * </ul>
 *
 * <p>关联 {@link com.cretas.aims.entity.MaterialBatch} 批次号 (batch_number string).
 * MaterialBatch.id 是 VARCHAR(191) UUID 字符串.
 *
 * <p>软删除 via BaseEntity @Where(deleted_at IS NULL). 已销毁/已使用的留样应保留
 * (audit trail), 状态机更新而非物理删除.
 *
 * <p>HJ 0 留样追踪 → Cretas 食品垂直 V2 差异化护城河 +1.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "food_samples",
        indexes = {
                @Index(name = "idx_food_samples_factory_batch",
                        columnList = "factory_id,batch_number"),
                @Index(name = "idx_food_samples_expire",
                        columnList = "factory_id,status,expire_time"),
                @Index(name = "idx_food_samples_status",
                        columnList = "factory_id,status")
        })
@Where(clause = "deleted_at IS NULL")
public class FoodSample extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 工厂 ID. */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 批次号 (关联 MaterialBatch.batchNumber 或 ProductionBatch). */
    @Column(name = "batch_number", nullable = false, length = 100)
    private String batchNumber;

    /** MaterialBatch.id — VARCHAR(191) UUID 字符串 (per Sprint 9 P1.1 finding). */
    @Column(name = "material_batch_id", nullable = false, length = 191)
    private String materialBatchId;

    /** 产品名称 (e.g. "卤猪蹄 200g"). */
    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    /** 留样重量 (g, 法规要求 ≥ 125g). */
    @Column(name = "sample_quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal sampleQuantity;

    /** 单位 (默认 "g"). */
    @Column(name = "unit", nullable = false, length = 10)
    @Builder.Default
    private String unit = "g";

    /** 留样开始时间. */
    @Column(name = "sample_time", nullable = false)
    private LocalDateTime sampleTime;

    /** 销毁时间 (sampleTime + 48h per GB 31654-2021). */
    @Column(name = "expire_time", nullable = false)
    private LocalDateTime expireTime;

    /** 冷藏温度上限 (℃, 默认 8.0 per GB 31654-2021). */
    @Column(name = "storage_temperature_max", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal storageTemperatureMax = new BigDecimal("8.0");

    /** 冷柜位置 (e.g. "1号冷柜A区"). */
    @Column(name = "storage_location", nullable = false, length = 100)
    private String storageLocation;

    /** 留样操作员 user_id. */
    @Column(name = "retained_by_user_id", nullable = false)
    private Long retainedByUserId;

    /** 状态: RETAINED / DISPOSED / USED_FOR_INSPECTION. */
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "RETAINED";

    /** 销毁/使用时间. */
    @Column(name = "disposed_at")
    private LocalDateTime disposedAt;

    /** 销毁/使用操作员 user_id. */
    @Column(name = "disposed_by_user_id")
    private Long disposedByUserId;

    /** 销毁/使用原因: SCHEDULED / INSPECTION / CONTAMINATED (+ 自由文本备注). */
    @Column(name = "dispose_reason", columnDefinition = "TEXT")
    private String disposeReason;
}
