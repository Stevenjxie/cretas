package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 创建请购单 DTO — Sprint 5 Track D.
 *
 * <p>requestedItems 用 {@code List<Map>} 而非强类型 DTO list,
 * 是为了跟 PurchaseRequisition.requestedItems (JSONB) 一致 (Sprint 5 MVP).
 * Sprint 6 拆 RequisitionItem 关系表时再升级.
 *
 * <p>每个 item 期望字段:
 * {@code {materialTypeId, materialName, quantity, unit, suggestedSupplierId?, remark?}}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePurchaseRequisitionRequest {

    @Size(max = 64, message = "请购人部门长度不能超过64")
    private String requesterDeptId;

    @NotEmpty(message = "请购行项目不能为空")
    private List<Map<String, Object>> requestedItems;

    private LocalDate expectedDate;

    @Size(max = 5000, message = "原因长度不能超过5000")
    private String reason;

    @Size(max = 5000, message = "备注长度不能超过5000")
    private String remark;
}
