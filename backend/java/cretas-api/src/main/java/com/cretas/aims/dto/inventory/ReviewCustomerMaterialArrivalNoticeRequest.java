package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Optional audit note supplied when warehouse approves or rejects a non-order inbound request. */
@Data
public class ReviewCustomerMaterialArrivalNoticeRequest {

    @Size(max = 1000, message = "审批意见不能超过1000个字符")
    private String remark;
}
