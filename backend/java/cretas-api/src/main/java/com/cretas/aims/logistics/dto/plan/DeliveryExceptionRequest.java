package com.cretas.aims.logistics.dto.plan;

import lombok.Data;

/**
 * 门店配送异常上报(执行跟踪)。
 * reason: STORE_CLOSED/REJECTED/UNREACHABLE/DAMAGED/OTHER。
 * disposition: RESCHEDULE(明日再送)/REASSIGN(改派)/RETURN(退回仓库)/CANCEL(取消该单)。
 * note: 备注(改派目标车次名 / 其他原因说明)。
 */
@Data
public class DeliveryExceptionRequest {
    private String reason;
    private String disposition;
    private String note;
}
