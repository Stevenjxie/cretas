package com.cretas.aims.dto.supplier;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Auditable supplier lifecycle transition request. */
@Data
public class SupplierStatusChangeRequest {
    @NotNull(message = "isActive 是必需的")
    private Boolean isActive;

    @NotBlank(message = "状态变更原因不能为空")
    @Size(max = 500, message = "状态变更原因不能超过500个字符")
    private String reason;

    private Long version;
}
