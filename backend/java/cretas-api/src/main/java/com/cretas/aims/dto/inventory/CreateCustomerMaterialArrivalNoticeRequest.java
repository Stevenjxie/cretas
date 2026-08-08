package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateCustomerMaterialArrivalNoticeRequest {

    @NotBlank(message = "归属客户不能为空")
    @Size(max = 191, message = "客户ID不能超过191个字符")
    private String customerId;

    private LocalDateTime expectedArrivalAt;

    @Size(max = 100, message = "联系人不能超过100个字符")
    private String contactName;

    @Size(max = 50, message = "联系电话不能超过50个字符")
    private String contactPhone;

    @Size(max = 1000, message = "备注不能超过1000个字符")
    private String remark;
}
