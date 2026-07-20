package com.cretas.aims.dto.production;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Compare-and-set request for repairing a historical sales-derived plan batch date. */
public record RepairProductionPlanBatchDateRequest(
        @NotNull(message = "原批次日期不能为空")
        @Schema(description = "调用方已读取到的原批次日期；用于拒绝并发或错误覆盖")
        LocalDate expectedCurrentBatchDate,
        @NotNull(message = "目标批次日期不能为空")
        @Schema(description = "应修复为的实际转批次日期")
        LocalDate targetBatchDate) {
}
