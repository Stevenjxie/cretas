package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 仓储少收关闭采购入库任务。
 *
 * <p>关闭只终止采购单剩余数量的后续收货，不回滚已经确认并入账的库存。
 */
@Data
public class ClosePurchaseReceivingTaskRequest {

    @NotNull(message = "请选择少收关闭原因")
    private ReasonCode reasonCode;

    @Size(max = 500, message = "补充说明不能超过500字")
    private String notes;

    public enum ReasonCode {
        SUPPLIER_SHORT_SHIPMENT,
        QUALITY_REJECTION,
        PURCHASE_BALANCE_CANCELLED,
        DEMAND_CHANGED,
        OTHER
    }
}
