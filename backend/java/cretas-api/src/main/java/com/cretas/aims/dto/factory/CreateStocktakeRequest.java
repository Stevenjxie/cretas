package com.cretas.aims.dto.factory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发起盘点任务请求 DTO (SP7 T1).
 */
@Data
public class CreateStocktakeRequest {

    @NotBlank(message = "仓库 ID 不能为空")
    private String warehouseId;

    /** 盘点月份，格式 "2026-06" */
    @NotBlank(message = "盘点月份不能为空")
    @Pattern(regexp = "\\d{4}-\\d{2}", message = "月份格式应为 YYYY-MM")
    private String periodMonth;

    private String notes;
}
