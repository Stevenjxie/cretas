package com.cretas.aims.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessWorkReportSubmitRequest {

    @NotBlank(message = "processTaskId is required")
    private String processTaskId;

    @NotNull(message = "outputQuantity is required")
    private BigDecimal outputQuantity;

    private String reporterName;
    private Long targetWorkerId;
    private String processCategory;
    private String notes;

    private String reportMode;
    private String batchNumber;

    private BigDecimal inputQuantity;
    private BigDecimal warehouseOutQuantity;
    private BigDecimal feedInQuantity;
    private BigDecimal carryoverQuantity;
    private String sourceWipNo;
    private Integer totalWorkers;
    private Integer totalWorkMinutes;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate reportDate;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime productionStartTime;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime productionEndTime;

    private List<String> photos;
    private List<Long> workerIds;
    private Map<String, Object> customFields;
}
