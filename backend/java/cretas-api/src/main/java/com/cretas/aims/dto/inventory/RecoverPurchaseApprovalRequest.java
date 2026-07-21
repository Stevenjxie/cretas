package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Restricted request for repairing a SUBMITTED purchase order without an OA instance. */
@Data
public class RecoverPurchaseApprovalRequest {

    @NotBlank(message = "必须填写并核对采购订单号")
    @Size(max = 100, message = "采购订单号长度不能超过100个字符")
    private String expectedOrderNumber;

    @NotBlank(message = "必须提供幂等键")
    @Size(min = 8, max = 120, message = "幂等键长度必须为8到120个字符")
    @Pattern(regexp = "^[A-Za-z0-9._:-]+$", message = "幂等键格式无效")
    private String idempotencyKey;

    @NotBlank(message = "必须填写恢复原因")
    @Size(min = 5, max = 500, message = "恢复原因长度必须为5到500个字符")
    private String reason;

    @AssertTrue(message = "必须明确确认执行 OA 审批实例恢复")
    private boolean confirm;
}
