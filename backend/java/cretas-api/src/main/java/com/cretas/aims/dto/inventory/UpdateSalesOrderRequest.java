package com.cretas.aims.dto.inventory;

import com.cretas.aims.dto.sales.ExtraFeeItem;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 销售订单更新请求 (仅 DRAFT 状态可编辑)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateSalesOrderRequest {

    private String customerName;

    private String salesperson;

    private String deliveryAddress;

    private String remark;

    private Boolean shippingIncluded;

    private BigDecimal shippingFee;

    /** 其他费用 (装卸/包装/加急等) */
    private List<ExtraFeeItem> extraFees;

    private LocalDate requiredDeliveryDate;

    @NotNull(message = "加工方式不能为空")
    private SalesProcessingMode processingMode;

    @NotNull(message = "物料供应方式不能为空")
    private MaterialSupplyMode materialSupplyMode;

    /**
     * Null preserves existing customer-supplied requirements; a non-null list replaces them.
     * An explicit empty list is invalid for customer-supplied toll processing and clears stale
     * requirements only when switching to a mode that forbids them.
     */
    @Valid
    private List<CreateSalesOrderRequest.SuppliedMaterialRequirementDTO> suppliedMaterials;

    /** 行项目更新 (为null时不更新行项) */
    private List<CreateSalesOrderRequest.SalesOrderItemDTO> items;

    /** Canvas V3: 动态字段 (dual-track) — 为 null 时不修改 */
    private Map<String, Object> customFields;

    /** 乐观锁版本号 (编辑时必传, 来自 GET 响应); mismatch → 409 Conflict */
    private Long version;
}
