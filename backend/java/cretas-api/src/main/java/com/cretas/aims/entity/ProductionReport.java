package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.ReportMode;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import lombok.*;
import org.hibernate.annotations.Type;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "production_reports", indexes = {
        @Index(name = "idx_pr_factory_date", columnList = "factory_id, report_date"),
        @Index(name = "idx_pr_batch", columnList = "batch_id"),
        @Index(name = "idx_pr_worker", columnList = "worker_id"),
        @Index(name = "idx_pr_type", columnList = "report_type")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionReport {

    public static class ReportType {
        public static final String PROGRESS = "PROGRESS";
        public static final String HOURS = "HOURS";
    }

    public enum Status {
        DRAFT, SUBMITTED, APPROVED, REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @jakarta.persistence.Version
    @Column(name = "version")
    private Long version;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "worker_id", nullable = false)
    private Long workerId;

    @Column(name = "report_type", nullable = false, length = 30)
    private String reportType;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_mode", nullable = false, length = 16)
    @Builder.Default
    private ReportMode reportMode = ReportMode.MODE_1;

    @Column(name = "schema_id", length = 36)
    private String schemaId;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "reporter_name", length = 100)
    private String reporterName;

    @Column(name = "process_category", length = 200)
    private String processCategory;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(name = "output_quantity", precision = 12, scale = 2)
    private BigDecimal outputQuantity;

    @Column(name = "good_quantity", precision = 12, scale = 2)
    private BigDecimal goodQuantity;

    @Column(name = "defect_quantity", precision = 12, scale = 2)
    private BigDecimal defectQuantity;

    @Column(name = "total_work_minutes")
    private Integer totalWorkMinutes;

    @Column(name = "total_workers")
    private Integer totalWorkers;

    @Column(name = "operation_volume", precision = 10, scale = 2)
    private BigDecimal operationVolume;

    @Type(JsonType.class)
    @Column(name = "hour_entries", columnDefinition = "jsonb")
    private List<Map<String, Object>> hourEntries;

    @Type(JsonType.class)
    @Column(name = "non_production_entries", columnDefinition = "jsonb")
    private List<Map<String, Object>> nonProductionEntries;

    @Column(name = "production_start_time")
    private LocalTime productionStartTime;

    @Column(name = "production_end_time")
    private LocalTime productionEndTime;

    @Type(JsonType.class)
    @Column(name = "custom_fields", columnDefinition = "jsonb")
    private Map<String, Object> customFields;

    @Type(JsonType.class)
    @Column(name = "photos", columnDefinition = "jsonb")
    private List<String> photos;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private Status status = Status.SUBMITTED;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "synced_to_smartbi")
    @Builder.Default
    private Boolean syncedToSmartbi = false;

    // PROCESS mode fields
    @Column(name = "process_task_id", length = 50)
    private String processTaskId;

    @Column(name = "is_supplemental")
    @Builder.Default
    private Boolean isSupplemental = false;

    @Column(name = "approval_status", length = 20)
    @Builder.Default
    private String approvalStatus = "PENDING";

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejected_reason", length = 500)
    private String rejectedReason;

    @Column(name = "notes", length = 500)
    private String notes;

    // ==================== Phase A 报工事实层 (report_type='YIELD') ====================

    /** 对应工序任务 (关联 WorkProcessTask.id);权威规则: 有 YIELD report 的批次以此为产出权威源 */
    @Column(name = "work_process_task_id")
    private Long workProcessTaskId;

    /** 工序序 (冗余 WorkProcessTask.processOrder, 便于按序聚合出成率) */
    @Column(name = "process_order")
    private Integer processOrder;

    /** 产品类型 id (冗余, 便于跨批归因) */
    @Column(name = "product_type_id", length = 100)
    private String productTypeId;

    /** 本道投入量 (中道=实际上料, 预填上道产出可改; 首道=feedInQuantity) */
    @Column(name = "input_quantity", precision = 12, scale = 2)
    private BigDecimal inputQuantity;

    @Column(name = "input_unit", length = 16)
    private String inputUnit;

    /** 产出单位 (可能 != inputUnit, 如装盒 kg→盒) */
    @Column(name = "output_unit", length = 16)
    private String outputUnit;

    /** 本道余料结转 = 上道产出 − 本道投入 (张权 A2b; Phase A 仅记录值不进库存) */
    @Column(name = "carryover_quantity", precision = 12, scale = 2)
    private BigDecimal carryoverQuantity;

    /** 跨批料来源 (张权 A3): [{"source_batch_id":Long,"source_work_process_task_id":Long|null,"quantity_from_source":Number,"source_unit":String}] */
    @Type(JsonType.class)
    @Column(name = "source_batch_refs", columnDefinition = "jsonb")
    private List<Map<String, Object>> sourceBatchRefs;

    /** 领料出库量 (张权 A1, 如 998; 仅首道领料填) */
    @Column(name = "warehouse_out_quantity", precision = 12, scale = 2)
    private BigDecimal warehouseOutQuantity;

    /** 领料投料量 (张权 A1, 如 935.5; 与出库量差额是对账缺口) */
    @Column(name = "feed_in_quantity", precision = 12, scale = 2)
    private BigDecimal feedInQuantity;

    /** 工序批次号 (张权 A6, 系统生成, 工厂内唯一) */
    @Column(name = "intermediate_batch_no", length = 64)
    private String intermediateBatchNo;

    /** 是否已结清 (每日结清打标) */
    @Column(name = "settled")
    @Builder.Default
    private Boolean settled = false;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "reversal_of_id")
    private Long reversalOfId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
