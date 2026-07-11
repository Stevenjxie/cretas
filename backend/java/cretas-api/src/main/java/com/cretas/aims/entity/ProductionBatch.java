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
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
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

    public enum WorkflowSelectionMode {
        LEGACY,
        WORKFLOW
    }

    /** Immutable creation-time decision populated atomically by the PostgreSQL insert trigger. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Generated(event = EventType.INSERT)
    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_selection_mode", insertable = false, updatable = false, length = 16)
    private WorkflowSelectionMode workflowSelectionMode;

    /** Exact published Workflow selected when this batch row was inserted. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Generated(event = EventType.INSERT)
    @Column(name = "selected_workflow_id", insertable = false, updatable = false)
    private Long selectedWorkflowId;

    /** Exact definition version selected when this batch row was inserted. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Generated(event = EventType.INSERT)
    @Column(name = "selected_workflow_version", insertable = false, updatable = false)
    private Integer selectedWorkflowVersion;
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

    // ==================== 报工 P0-2: 跨单位指标守卫 ====================

    /**
     * plannedQuantity 所属的原计划单位。
     *
     * <p>末道报工产出单位 (份/盒) 与批次原计划单位 (kg) 不同时, completeProduction 会把
     * {@code unit} 覆盖成产出单位 (份), 使 batch/成品入库单位一致。但此时 {@code plannedQuantity}
     * 仍是原 kg 数 → {@code efficiency = actualQuantity(份)/plannedQuantity(kg)} 与
     * {@code unitCost = totalCost(kg基)/goodQuantity(份)} 跨单位无意义。</p>
     *
     * <p>completeProduction 覆盖 unit 前把原单位写入此列; {@link #calculateMetrics()}
     * 检测到 {@code plannedUnit != null && !plannedUnit.equals(unit)} 时, 把 efficiency / unitCost
     * 置 null (诚实留空, 镜像 yield 层 cumulative=null 做法), 而非算出垃圾值。</p>
     *
     * <p><b>持久化原因</b>: {@code @PreUpdate onUpdate()} 每次 save 都重算 calculateMetrics —
     * 若仅瞬态, 完工后质检改 qualityStatus 等无关更新会用 plannedUnit=null 重新算出垃圾值,
     * 覆盖已诚实置 null 的结果。持久化保证跨单位事实在批次生命周期内稳定。
     * 同单位批次此列为 null, calculateMetrics 行为零变化。</p>
     */
    @Column(name = "planned_unit", length = 20)
    private String plannedUnit;

    // ==================== SP-D: WIP 批次判别 ====================

    /**
     * 批次类型判别列 (SP-D Fix 1a).
     *
     * <p>REGULAR (默认) — 常规生产批次, 计入仪表盘统计和批次列表.<br>
     * CLERK_WIP — 文员录入的中间半成品批次 (CLK-W- 前缀, isFinished=false 路径).
     * 这类批次是成本路由的内部工件, 不应出现在面向用户的统计和列表中,
     * 否则会虚增"今日批次""在制批次""总产量"等指标.
     * </p>
     *
     * <p>DB 列 batch_type VARCHAR(20) NOT NULL DEFAULT 'REGULAR' (V20261027_05).</p>
     */
    @Builder.Default
    @Column(name = "batch_type", nullable = false, length = 20)
    private String batchType = "REGULAR";

    // ==================== SP10: 试制批次标记 ====================

    /**
     * 是否为研发试制批次 (SP10).
     * 试制批次用于中报价成本汇算, 不计入常规生产统计.
     */
    @Builder.Default
    @Column(name = "is_trial")
    private Boolean isTrial = false;

    /**
     * 关联的研发试样 ID (SP10).
     * FK → quotation_tasks.sample_id (业务键).
     * 非 null 时此批次属于指定试样的试制链路.
     */
    @Column(name = "trial_sample_id", length = 191)
    private String trialSampleId;

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
        // 报工 P0-2: 末道产出单位 (份/盒) ≠ 原计划单位 (kg) → 跨单位.
        // actualQuantity/goodQuantity 是产出单位计数, plannedQuantity/成本基是原单位 →
        // efficiency / unitCost 跨单位无意义, 置 null 诚实留空 (镜像 yield 层 cumulative=null).
        // 同单位 (plannedUnit 为 null 或与 unit 相同) 行为零变化。
        boolean crossUnit = plannedUnit != null && unit != null && !plannedUnit.equals(unit);

        // 计算总成本
        if (materialCost != null || laborCost != null || equipmentCost != null || otherCost != null) {
            totalCost = BigDecimal.ZERO;
            if (materialCost != null) totalCost = totalCost.add(materialCost);
            if (laborCost != null) totalCost = totalCost.add(laborCost);
            if (equipmentCost != null) totalCost = totalCost.add(equipmentCost);
            if (otherCost != null) totalCost = totalCost.add(otherCost);
        }

        // 计算单位成本 - 优先使用良品数量，如果没有则使用实际产量
        // 跨单位时: 成本按原单位 (kg) 归集, 产量是产出单位 (份) → 元/份 但基是 kg, 无意义 → 留空
        if (crossUnit) {
            unitCost = null;
        } else {
            BigDecimal quantityForUnitCost = goodQuantity != null && goodQuantity.compareTo(BigDecimal.ZERO) > 0
                    ? goodQuantity : actualQuantity;
            if (totalCost != null && quantityForUnitCost != null && quantityForUnitCost.compareTo(BigDecimal.ZERO) > 0) {
                unitCost = totalCost.divide(quantityForUnitCost, 4, RoundingMode.HALF_UP);
            }
        }

        // 计算良品率 — good/actual 同为产出单位, 跨单位下仍是同单位比值, 正常计算
        if (goodQuantity != null && actualQuantity != null && actualQuantity.compareTo(BigDecimal.ZERO) > 0) {
            yieldRate = goodQuantity.multiply(BigDecimal.valueOf(100))
                    .divide(actualQuantity, 2, RoundingMode.HALF_UP);
        }

        // 计算效率 — actual(产出单位) / planned(原单位), 跨单位时无意义 → 留空
        if (crossUnit) {
            efficiency = null;
        } else if (actualQuantity != null && plannedQuantity != null && plannedQuantity.compareTo(BigDecimal.ZERO) > 0) {
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

    // ==================== BUG-2 修复: 批次来源类型 (计算字段, 非持久化) ====================

    /**
     * 批次来源类型 (非持久化 @Transient, 从关联 ProductionPlan.planSourceType 派生)。
     *
     * <p>在 ProcessingServiceImpl.getBatchById() 中按需填充。
     * "SECONDARY" 表示半成品/二次加工; "NORMAL" 表示正常生产计划。
     * null 表示无关联计划或计划已删除。</p>
     */
    @Transient
    private String batchSourceType;

    @Transient
    private String sourcePlanId;

    @Transient
    private String sourcePlanNumber;

    @Transient
    private Integer sourceProcessOrder;

    @Transient
    private String sourceProcessCode;
}
