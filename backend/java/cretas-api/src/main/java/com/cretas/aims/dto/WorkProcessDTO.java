package com.cretas.aims.dto;

import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkProcessDTO {

    private String id;

    @NotBlank(message = "工序名称不能为空")
    @Size(max = 100, message = "工序名称不能超过100个字符")
    private String processName;

    @NotBlank(message = "请选择工序类别")
    @Size(max = 50, message = "工序类别不能超过50个字符")
    private String processCategory;

    /**
     * Request-only acknowledgement that this write intentionally introduces a new category.
     * Categories remain derived from the factory's work-process catalog; this flag prevents
     * arbitrary free-text values from bypassing the controlled selector.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Boolean createCategory;

    @Size(max = 500, message = "描述不能超过500个字符")
    private String description;

    /** Legacy write-only compatibility; ignored by create/update. Units belong to Workflow nodes. */
    @Deprecated
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String unit;

    private Integer estimatedMinutes;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    /** Legacy write-only compatibility; ignored. Ordering belongs to Workflow steps. */
    @Deprecated
    private Integer sortOrder;

    private Boolean isActive;

    /** 标准出成率下限 (A7 越界软告警; null=不校验). 支持 0.0001..99.9999 (小数, 0.30=30%). */
    @DecimalMin(value = "0.0001", message = "标准出成率下限须大于 0")
    @DecimalMax(value = "99.9999", message = "标准出成率下限超出范围")
    private BigDecimal standardYieldMin;

    /** 标准出成率上限 (A7; 支持 >1 如滚揉保水 1.35; null=不校验). 超收预检基准. */
    @DecimalMin(value = "0.0001", message = "标准出成率上限须大于 0")
    @DecimalMax(value = "99.9999", message = "标准出成率上限超出范围")
    private BigDecimal standardYieldMax;

    /** 该工序是否需录投入量 (默认 true; 纯包装/检验可 false). */
    private Boolean needsInput;

    /** Legacy write-only compatibility; ignored by create/update. */
    @Deprecated
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String outputUnit;

    private String mergedIntoId;
    private LocalDateTime mergedAt;
    private String mergedBy;
    private String governanceReason;
    private Long lockVersion;
    private Boolean selectableForNew;
    private ReferenceStats referenceStats;

    private WorkProcessOutputMaterialKind defaultOutputMaterialKind;

    /**
     * 本工序产出的半成品编码。配置后, 报工产出阶段会出现"产半成品"选项,
     * 产出的 WIP 可供二次加工领用。null = 本工序不产半成品。
     */
    @Size(max = 50, message = "半成品产出编码不能超过50个字符")
    private String semiFinishedOutputCode;

    /**
     * Update payload presence marker. Distinguishes an explicit JSON null
     * (clear the semi-finished output code) from an omitted field (leave as-is).
     */
    @JsonIgnore
    private boolean semiFinishedOutputCodeSpecified;

    public void setSemiFinishedOutputCode(String semiFinishedOutputCode) {
        this.semiFinishedOutputCode = semiFinishedOutputCode;
        this.semiFinishedOutputCodeSpecified = true;
    }

    /** 标准时薪 (元/小时; null=未配置, 绝不默认 0; 用于逐道人工成本计算). NUMERIC(8,2) → 0..999999.99. */
    @DecimalMin(value = "0", message = "标准时薪不能为负")
    @DecimalMax(value = "999999.99", message = "标准时薪超出范围")
    private BigDecimal standardHourlyRate;

    /**
     * 预期副产物列表 (防呆 Rule 3: 报工 OUTPUT 阶段预填提示).
     * 格式: [{"name":"肥油","unit":"kg","defaultEnabled":true}, ...]
     * null = 本道无预期副产物。
     */
    private List<Map<String, Object>> expectedByproducts;

    /**
     * G2 自定义字段 schema (config-driven 逐工序电子表格自定义列)。
     * 格式: [{"key":"baume","label":"波美度","type":"number","enabled":true}, ...]
     * null = 本工序未开启自定义字段。
     */
    private List<Map<String, Object>> customFieldSchema;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SortOrderUpdate {
        private String id;
        private Integer sortOrder;
    }

    /**
     * C5: A cluster of duplicate work-processes that share the same
     * normalized processName + processCategory within a factory.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DuplicateGroup {
        /** The shared name. */
        private String processName;
        /** The shared category (may be null). */
        private String processCategory;
        /** All members of this duplicate cluster (always ≥ 2). */
        private List<WorkProcessDTO> members;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReferenceStats {
        private long skuAssociationCount;
        private long workflowVersionCount;
        private long productionTaskCount;
    }

    public enum GovernanceMode {
        MERGE,
        DEACTIVATE_OTHERS
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GovernanceRequest {
        @NotBlank(message = "masterProcessId must not be blank")
        private String masterProcessId;
        private List<String> duplicateProcessIds;
        private GovernanceMode mode;
        @NotBlank(message = "idempotencyKey must not be blank")
        @Size(max = 100)
        private String idempotencyKey;
        @Size(max = 500)
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GovernanceResult {
        private String masterProcessId;
        private GovernanceMode mode;
        private List<String> governedProcessIds;
        private boolean replayed;
    }
}
