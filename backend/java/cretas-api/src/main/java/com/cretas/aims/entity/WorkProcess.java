package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "work_processes", indexes = {
    @Index(name = "idx_wp_factory", columnList = "factory_id"),
    @Index(name = "idx_wp_factory_active", columnList = "factory_id, is_active")
})
@Where(clause = "deleted_at IS NULL")
public class WorkProcess extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 50)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "process_name", nullable = false, length = 100)
    private String processName;

    @Column(name = "process_category", length = 50)
    private String processCategory;

    @Column(name = "description", length = 500)
    private String description;

    /** Legacy compatibility only. Units belong to Workflow node snapshots. */
    @Deprecated
    @Column(name = "unit", nullable = false, length = 20)
    @Builder.Default
    private String unit = "kg";

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    /** Legacy compatibility only. Execution order belongs to Workflow steps. */
    @Deprecated
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /** 标准出成率下限 (张权 A7, 报工时越界软告警; null=不校验) */
    @Column(name = "standard_yield_min", precision = 6, scale = 4)
    private BigDecimal standardYieldMin;

    /** 标准出成率上限 (张权 A7; 支持 >1 如滚揉保水 1.35) */
    @Column(name = "standard_yield_max", precision = 6, scale = 4)
    private BigDecimal standardYieldMax;

    /** 该工序是否需录投入量 (默认 true; 纯包装/检验可 false) */
    @Column(name = "needs_input")
    @Builder.Default
    private Boolean needsInput = true;

    /** 产出单位 (kg→盒; 为空沿用 unit) */
    /** Legacy compatibility only. Output units belong to Workflow node snapshots. */
    @Deprecated
    @Column(name = "output_unit", length = 20)
    private String outputUnit;

    /** Canonical process offered for future selection after duplicate governance. */
    @Column(name = "merged_into_id", length = 50)
    private String mergedIntoId;

    @Column(name = "merged_at")
    private LocalDateTime mergedAt;

    @Column(name = "merged_by", length = 100)
    private String mergedBy;

    @Column(name = "governance_reason", length = 500)
    private String governanceReason;

    @Version
    @Column(name = "lock_version", nullable = false)
    @Builder.Default
    private Long lockVersion = 0L;

    public boolean isSelectableForNew() {
        return Boolean.TRUE.equals(isActive) && mergedIntoId == null;
    }

    @Enumerated(EnumType.STRING)
    @Column(
            name = "default_output_material_kind",
            nullable = false,
            length = 32,
            columnDefinition = "VARCHAR(32) DEFAULT 'SEMI_FINISHED' "
                    + "CHECK (default_output_material_kind IN ('SEMI_FINISHED', 'FINISHED_GOOD'))"
    )
    private WorkProcessOutputMaterialKind defaultOutputMaterialKind;

    /** 标准时薪 (元/小时; null=未配置, 用于逐道人工成本计算, 绝不默认 0) */
    @Column(name = "standard_hourly_rate", precision = 8, scale = 2)
    private BigDecimal standardHourlyRate;

    /**
     * 预期副产物列表 (防呆 Rule 3: 报工 OUTPUT 阶段预填提示).
     * 格式: [{"name":"肥油","unit":"kg","defaultEnabled":true}, ...]
     * null = 本道无预期副产物, 不预填。
     */
    @Type(JsonType.class)
    @Column(name = "expected_byproducts", columnDefinition = "jsonb")
    private List<Map<String, Object>> expectedByproducts;

    /**
     * SP1: 本道产出的半成品编码前缀 (如 "猪舌-滚揉后");
     * 非空时 outputKind=SEMI/BOTH 的报工可将产出写入 SemiFinishedInventory。
     * null = 本道不产出半成品 (普通工序)。
     */
    @Column(name = "semi_finished_output_code", length = 50)
    private String semiFinishedOutputCode;

    /**
     * G2 自定义字段 schema (config-driven 逐工序电子表格自定义列, 如 波美度/添加剂量/备注)。
     * 格式: [{"key":"baume","label":"波美度","type":"number","enabled":true}, ...]。
     * null = 本工序未开启自定义字段 (逐工序录入不接受任何自定义 key, 保存时校验层拒绝)。
     */
    @Type(JsonType.class)
    @Column(name = "custom_field_schema", columnDefinition = "jsonb")
    private List<Map<String, Object>> customFieldSchema;
}
