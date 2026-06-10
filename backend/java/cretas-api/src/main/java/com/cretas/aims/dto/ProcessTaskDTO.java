package com.cretas.aims.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessTaskDTO {

    private String id;
    private String factoryId;
    private String productionRunId;

    @NotBlank(message = "产品类型ID不能为空")
    private String productTypeId;

    @NotBlank(message = "工序ID不能为空")
    private String workProcessId;

    private String sourceCustomerName;
    private String sourceDocType;
    private String sourceDocId;
    private Integer workflowVersionId;
    private Long batchId;
    /** 批次号 (如 "MB-F006-0012"), 用于 R8 双栈合并 — RN 导航到 YieldStepReportScreen 时传入. */
    private String batchNumber;
    private Long workProcessTaskId;
    /**
     * 工序顺序号 (来自对应 WorkProcessTask.processOrder).
     * R8 双栈合并 — RN 导航到 YieldStepReportScreen 时传入 assignedProcessOrder.
     */
    private Integer processOrder;

    @NotNull(message = "计划量不能为空")
    @DecimalMin(value = "0.01", message = "计划量必须大于0")
    private BigDecimal plannedQuantity;

    private BigDecimal completedQuantity;
    private BigDecimal pendingQuantity;
    private BigDecimal inputQuantity;

    private String unit;
    private String inputUnit;
    private String outputUnit;
    private LocalDate startDate;
    private LocalDate expectedEndDate;
    private String status;
    private String previousTerminalStatus;
    private Long createdBy;
    private String notes;

    // Read-only enriched fields
    private String productName;
    private String processName;
    private String processCategory;
    private boolean overdue;

    /** Alias for productName — RN frontend reads this field */
    public String getProductTypeName() {
        return productName;
    }

    // Computed fields
    private BigDecimal estimatedProgress;
    private BigDecimal confirmedProgress;
    private Boolean targetReached;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusUpdateRequest {
        @NotBlank(message = "状态不能为空")
        private String status;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskSummary {
        private String taskId;
        private String processName;
        private String productName;
        private BigDecimal plannedQuantity;
        private BigDecimal completedQuantity;
        private BigDecimal pendingQuantity;
        private BigDecimal inputQuantity;
        private String unit;
        private String status;
        private Integer totalWorkers;
        private Integer totalReports;
        private List<WorkerSummary> workerSummaries;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkerSummary {
        private Long workerId;
        private String workerName;
        private BigDecimal totalQuantity;
        @Builder.Default
        private BigDecimal approvedQuantity = BigDecimal.ZERO;
        @Builder.Default
        private BigDecimal pendingQuantity = BigDecimal.ZERO;
        private Integer reportCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunOverview {
        private String productionRunId;
        private String productName;
        private String sourceCustomerName;
        private List<ProcessTaskDTO> tasks;
        private Integer totalTasks;
        private Integer completedTasks;
        private BigDecimal overallProgress;
    }
}
