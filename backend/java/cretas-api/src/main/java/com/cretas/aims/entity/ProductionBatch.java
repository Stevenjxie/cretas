package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.QualityStatus;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.Where;
/**
 * 生产批次实体
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)  // 继承 BaseEntity，需要调用 super
@Entity
@Table(name = "production_batches",
       indexes = {
           @Index(name = "idx_batch_factory", columnList = "factory_id"),
           @Index(name = "idx_batch_number", columnList = "batch_number"),
           @Index(name = "idx_batch_status", columnList = "status"),
           @Index(name = "idx_batch_plan", columnList = "production_plan_id")
       }
)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class ProductionBatch extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;
    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;
     /**
      * 批次号
      */
    @NotBlank(message = "批次号不能为空")
    @Column(name = "batch_number", nullable = false, unique = true, length = 50)
    private String batchNumber;
     /**
      * 生产计划ID
      */
    @Column(name = "production_plan_id", length = 191)
    private String productionPlanId;
     /**
      * 产品类型ID (关联 ProductType.id，类型为 String)
      */
    @NotBlank(message = "产品类型ID不能为空")
    @Column(name = "product_type_id", nullable = false, length = 100)
    private String productTypeId;
     /**
      * 产品名称
      */
    @Column(name = "product_name", length = 100)
    private String productName;
     /**
      * 计划数量
      */
    @PositiveOrZero(message = "计划数量不能为负数")
    @Column(name = "planned_quantity", precision = 12, scale = 2)
    private BigDecimal plannedQuantity;
     /**
      * 数量 (数据库NOT NULL字段)
      */
    @NotNull(message = "数量不能为空")
    @Positive(message = "数量必须大于0")
    @Column(name = "quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantity;
     /**
      * 单位 (数据库NOT NULL字段)
      */
    @NotBlank(message = "单位不能为空")
    @Column(name = "unit", nullable = false, length = 20)
    private String unit;
     /**
      * 实际产量
      */
    @PositiveOrZero(message = "实际产量不能为负数")
    @Column(name = "actual_quantity", precision = 12, scale = 2)
    private BigDecimal actualQuantity;
     /**
      * 良品数量
      */
    @PositiveOrZero(message = "良品数量不能为负数")
    @Column(name = "good_quantity", precision = 12, scale = 2)
    private BigDecimal goodQuantity;
     /**
      * 不良品数量
      */
    @PositiveOrZero(message = "不良品数量不能为负数")
    @Column(name = "defect_quantity", precision = 12, scale = 2)
    private BigDecimal defectQuantity;
     /**
      * 批次状态: PLANNED, IN_PROGRESS, PAUSED, COMPLETED, CANCELLED
      */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductionBatchStatus status;
     /**
      * 质量状态: PENDING_INSPECTION, INSPECTING, PASSED, FAILED, PARTIAL_PASS, REWORK_REQUIRED, etc.
      */
    @Enumerated(EnumType.STRING)
    @Column(name = "quality_status", length = 30)
    private QualityStatus qualityStatus;
     /**
      * 开始时间
      */
    @Column(name = "start_time")
    private LocalDateTime startTime;
     /**
      * 结束时间
      */
    @Column(name = "end_time")
    private LocalDateTime endTime;
     /**
      * 生产线/设备ID (关联 FactoryEquipment.id，类型为 Long)
      */
    @Column(name = "equipment_id")
    private Long equipmentId;
     /**
      * 生产线名称
      */
    @Column(name = "equipment_name", length = 100)
    private String equipmentName;
     /**
      * 负责人ID
      */
    @Column(name = "supervisor_id")
    private Long supervisorId;
     /**
      * 负责人名称
      */
    @Column(name = "supervisor_name", length = 50)
    private String supervisorName;
     /**
      * 操作工人数
      */
    @Column(name = "worker_count")
    private Integer workerCount;
     /**
      * 工作时长（分钟）
      */
    @Column(name = "work_duration_minutes")
    private Integer workDurationMinutes;
     /**
      * 原料成本
      */
    @PriceSensitive
    @Column(name = "material_cost", precision = 12, scale = 2)
    private BigDecimal materialCost;
     /**
      * 人工成本
      */
    @PriceSensitive
    @Column(name = "labor_cost", precision = 12, scale = 2)
    private BigDecimal laborCost;
     /**
      * 设备成本
      */
    @PriceSensitive
    @Column(name = "equipment_cost", precision = 12, scale = 2)
    private BigDecimal equipmentCost;
     /**
      * 其他成本
      */
    @PriceSensitive
    @Column(name = "other_cost", precision = 12, scale = 2)
    private BigDecimal otherCost;
     /**
      * 总成本
      */
    @PriceSensitive
    @Column(name = "total_cost", precision = 12, scale = 2)
    private BigDecimal totalCost;
     /**
      * 单位成本
      */
    @PriceSensitive
    @Column(name = "unit_cost", precision = 12, scale = 4)
    private BigDecimal unitCost;
     /**
      * 良品率
      */
    @Column(name = "yield_rate", precision = 5, scale = 2)
    private BigDecimal yieldRate;
     /**
      * 效率（实际产量/计划产量）
      */
    @Column(name = "efficiency", precision = 5, scale = 2)
    private BigDecimal efficiency;
     /**
      * 备注
      */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
     /**
      * 创建人ID
      */
    @Column(name = "created_by")
    private Long createdBy;

    // ==================== Sprint 2 S2-4: 拍照证据 ====================

    /**
     * 是否需要拍照证据
     * 来自 SopConfig.photoConfig 或 ProductType 配置
     */
    @Builder.Default
    @Column(name = "photo_required")
    private Boolean photoRequired = false;

    /**
     * 关联的 SOP 配置 ID
     */
    @Column(name = "sop_config_id", length = 50)
    private String sopConfigId;

    /**
     * 已完成拍照的环节列表 (JSON数组)
     * 格式: ["RECEIVING", "SLICING", "PACKAGING"]
     */
    @Column(name = "photo_completed_stages", columnDefinition = "JSON")
    private String photoCompletedStages;

    // ==================== AI Onboarding: Custom Fields ====================

    /**
     * AI-configured custom fields stored as JSONB.
     * e.g. {"drying_temp": 85, "seasoning_code": "A-003"}
     */
    @Type(JsonBinaryType.class)
    @Column(name = "custom_fields", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> customFields = new HashMap<>();

    // ==========================================
    // 注意: createdAt 和 updatedAt 字段已从 BaseEntity 继承
    // 不再需要在此定义，避免字段重复
    // ==========================================

    @PrePersist
    protected void onCreate() {
        super.onCreate();  // 调用 BaseEntity 的时间戳初始化
        if (status == null) {
            status = ProductionBatchStatus.PLANNED;
        }
    }
    @PreUpdate
    protected void onUpdate() {
        super.onUpdate();  // 调用 BaseEntity 的时间戳更新
        calculateMetrics();  // 保留业务逻辑：自动计算指标
    }

    /**
     * 计算各项指标
     */
    public void calculateMetrics() {
        // 计算总成本
        if (materialCost != null || laborCost != null || equipmentCost != null || otherCost != null) {
            totalCost = BigDecimal.ZERO;
            if (materialCost != null) totalCost = totalCost.add(materialCost);
            if (laborCost != null) totalCost = totalCost.add(laborCost);
            if (equipmentCost != null) totalCost = totalCost.add(equipmentCost);
            if (otherCost != null) totalCost = totalCost.add(otherCost);
        }

        // 计算单位成本 - 优先使用良品数量，如果没有则使用实际产量
        BigDecimal quantityForUnitCost = goodQuantity != null && goodQuantity.compareTo(BigDecimal.ZERO) > 0
                ? goodQuantity : actualQuantity;
        if (totalCost != null && quantityForUnitCost != null && quantityForUnitCost.compareTo(BigDecimal.ZERO) > 0) {
            unitCost = totalCost.divide(quantityForUnitCost, 4, RoundingMode.HALF_UP);
        }

        // 计算良品率
        if (goodQuantity != null && actualQuantity != null && actualQuantity.compareTo(BigDecimal.ZERO) > 0) {
            yieldRate = goodQuantity.multiply(BigDecimal.valueOf(100))
                    .divide(actualQuantity, 2, RoundingMode.HALF_UP);
        }

        // 计算效率
        if (actualQuantity != null && plannedQuantity != null && plannedQuantity.compareTo(BigDecimal.ZERO) > 0) {
            efficiency = actualQuantity.multiply(BigDecimal.valueOf(100))
                    .divide(plannedQuantity, 2, RoundingMode.HALF_UP);
        }

        // 计算工作时长
        if (startTime != null && endTime != null) {
            workDurationMinutes = (int) java.time.Duration.between(startTime, endTime).toMinutes();
        }
    }

    // ==================== 前端字段别名 ====================

    /**
     * productType 别名（兼容前端）
     * 前端使用 productType，后端使用 productName
     */
    @JsonProperty("productType")
    public String getProductType() {
        return productName;
    }

    /**
     * targetQuantity 别名（兼容前端）
     * 前端使用 targetQuantity，后端使用 plannedQuantity
     */
    @JsonProperty("targetQuantity")
    public BigDecimal getTargetQuantity() {
        return plannedQuantity;
    }

    /**
     * startDate 别名（兼容前端）
     * 前端使用 startDate (ISO日期字符串)，后端使用 startTime (LocalDateTime)
     */
    @JsonProperty("startDate")
    public LocalDate getStartDate() {
        return startTime != null ? startTime.toLocalDate() : null;
    }

    /**
     * endDate 别名（兼容前端）
     * 前端使用 endDate (ISO日期字符串)，后端使用 endTime (LocalDateTime)
     */
    @JsonProperty("endDate")
    public LocalDate getEndDate() {
        return endTime != null ? endTime.toLocalDate() : null;
    }

    /**
     * supervisor 别名（兼容前端）
     * 前端期望 { id: number, fullName: string } 对象
     * 后端只有 supervisorId 和 supervisorName
     */
    @JsonProperty("supervisor")
    public Map<String, Object> getSupervisor() {
        if (supervisorId == null && supervisorName == null) {
            return null;
        }
        // 使用 HashMap 替代 Map.of()，避免不可变 Map 序列化问题
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", supervisorId != null ? supervisorId : 0);
        map.put("fullName", supervisorName != null ? supervisorName : "");
        return map;
    }

    /**
     * Sprint 10 AI invocation source tag — set when Workdesk AI 1-click create production batch.
     * Schema: {source: "sprint-10-loop-5", testRun: bool, createdAt: iso}.
     * NULL = manual UI create.
     */
    @Type(JsonBinaryType.class)
    @Column(name = "ai_invocation_metadata", columnDefinition = "jsonb")
    private Map<String, Object> aiInvocationMetadata;
}
