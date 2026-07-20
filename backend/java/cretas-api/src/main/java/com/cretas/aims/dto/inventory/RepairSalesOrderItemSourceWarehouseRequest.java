package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Restricted request for filling a missing historical sales-order source warehouse. */
public record RepairSalesOrderItemSourceWarehouseRequest(
        @NotBlank(message = "来源仓库编码不能为空")
        @Size(max = 20, message = "来源仓库编码长度不能超过20个字符")
        String sourceWarehouseCode) {
}
