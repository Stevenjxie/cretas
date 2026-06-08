package com.cretas.aims.dto;

import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工序任务 DTO (Track D2 — M-WP-1/2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkProcessTaskDTO {

    private Long id;
    private String factoryId;
    private Long productionBatchId;
    private Long productWorkProcessId;
    private String workProcessId;
    private String productTypeId;
    private Integer processOrder;
    private WorkProcessTask.Status status;

    private BigDecimal plannedQuantity;
    private String plannedUnit;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime plannedStartAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime plannedEndAt;

    private Integer estimatedMinutes;

    private BigDecimal actualQuantity;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualStartAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualEndAt;

    private Integer actualMinutes;

    private Long assignedTo;
    /** T142: 责任人姓名 (后端 batch join, 不查 DB 逐条, 参照 T135 responsibleWorkerName 模式). null = 未分配或用户已删除. */
    private String assignedToName;
    private Long completedBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime completedAt;

    private String notes;

    /** 关联展示字段 (后端 join 提供, 选填). */
    private String processName;
    private String processCategory;

    /** 标准出成率下限 (张权 A7; 来自该工序 WorkProcess, join 透出; null=不校验). */
    private BigDecimal standardYieldMin;

    /** 标准出成率上限 (张权 A7; 支持 >1 如滚揉保水 1.35; null=不校验). */
    private BigDecimal standardYieldMax;

    /** P0-2 产出单位 (来自该工序 WorkProcess.outputUnit, join 透出; null=沿用投入单位). */
    private String outputUnit;

    /**
     * 预期副产物列表 (防呆 Rule 3: 报工 OUTPUT 阶段预填提示; 来自 WorkProcess.expectedByproducts).
     * 格式: [{"name":"肥油","unit":"kg","defaultEnabled":true}, ...]
     * null = 本道无预期副产物, RN 不预填。
     */
    private List<Map<String, Object>> expectedByproducts;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    // ==================== sub-request DTOs ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompleteRequest {
        @NotNull(message = "actualQuantity 不能为空")
        @Positive(message = "actualQuantity 必须大于 0")
        private BigDecimal actualQuantity;

        @Size(max = 500, message = "notes 不能超过 500 字符")
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkipRequest {
        @NotNull(message = "原因不能为空")
        @Size(min = 1, max = 500, message = "原因 1-500 字符")
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssignRequest {
        @NotNull(message = "assignedTo 不能为空")
        private Long assignedTo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdatePlanRequest {
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime plannedStartAt;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime plannedEndAt;

        private BigDecimal plannedQuantity;
        private String plannedUnit;
        private Integer estimatedMinutes;
        private Long assignedTo;

        @Size(max = 500, message = "notes 不能超过 500 字符")
        private String notes;
    }
}
