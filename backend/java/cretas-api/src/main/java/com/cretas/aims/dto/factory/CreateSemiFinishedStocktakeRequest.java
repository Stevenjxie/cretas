package com.cretas.aims.dto.factory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发起半成品盘点任务请求 DTO (镜像 SP7 {@link CreateStocktakeRequest})。
 *
 * <p>半成品 (WIP) 是工厂级库存, 无仓库维度, 故无 warehouseId。
 */
@Data
public class CreateSemiFinishedStocktakeRequest {

    /** 盘点月份, 格式 "2026-06" */
    @NotBlank(message = "盘点月份不能为空")
    @Pattern(regexp = "\\d{4}-\\d{2}", message = "月份格式应为 YYYY-MM")
    private String periodMonth;

    private String notes;
}
