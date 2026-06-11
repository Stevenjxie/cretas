package com.cretas.aims.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 采购订单更新请求 (仅 DRAFT 状态可编辑).
 *
 * 与 CreatePurchaseOrderRequest 的区别: 全部字段 optional, 允许 partial update.
 * 保留 @Size 格式校验, 加 version 乐观锁.
 *
 * Pattern reference: UpdateSalesOrderRequest (inventory).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePurchaseOrderRequest {

    @Size(max = 191, message = "供应商ID长度不能超过191个字符")
    private String supplierId;

    /** 采购类型: DIRECT / HQ_UNIFIED / URGENT */
    @Size(max = 50, message = "采购类型长度不能超过50个字符")
    private String purchaseType;

    private LocalDate orderDate;

    private LocalDate expectedDeliveryDate;

    @Size(max = 5000, message = "备注长度不能超过5000个字符")
    private String remark;

    /** 关联 SO id (可选, 跨模块追踪) */
    @Size(max = 191, message = "销售订单ID长度不能超过191个字符")
    private String salesOrderId;

    /** 行项目更新 (null 时不更新, 全量替换) */
    @Valid
    private List<CreatePurchaseOrderRequest.PurchaseOrderItemDTO> items;

    /**
     * SP6 — 合同编号。null = 不更新（保留原值）。
     * 传空字符串 "" 表示清除合同号。
     */
    @Size(max = 100, message = "合同号长度不能超过100个字符")
    private String contractNumber;

    /**
     * SP6 — 结算方式。null = 不更新（保留原值）。
     * 合法值: PREPAID / CREDIT_FIRST / NO_INVOICE / MONTHLY / CREDIT_PERIOD / IMMEDIATE
     */
    @Size(max = 32, message = "结算方式长度不能超过32个字符")
    private String settlementType;

    /**
     * SP6 — 开票提醒天数。null = 不更新（保留原值）。
     */
    private Integer invoiceReminderDays;

    /** 乐观锁版本号 (编辑时必传, 来自 GET 响应); mismatch → 409 Conflict */
    private Long version;
}
