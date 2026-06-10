package com.cretas.aims.dto.inventory;

import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.enums.SettlementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * D-9 G1 出纳付款视图 DTO
 *
 * <p>解决 /approved 原先返回裸实体（UUID 外键）问题。
 * 出纳需看到供应商名称、PO 单号、原料明细（名/单价/数量/单位）、金额、结算方式，
 * 即可直接付款，无需额外手查系统。
 *
 * <p>价格可见性：本 DTO 供 finance:read_write / finance:read / procurement:read_write /
 * procurement:read 角色使用（对应 /approved 端点权限）。cashier 角色 permissionPrefix=finance，
 * 拥有 finance:rw，具备 procurement:price:view（finance 隐含），
 * {@link com.cretas.aims.security.PriceFieldResponseAdvice} 不会剥价格字段。
 * 即使 procurement:price:view 缺失，出纳看到的也是订单层金额（amount），
 * 不是 PO 内部单价——这里 unitPrice 来自 PO items，属于采购明细，
 * 出纳需要此信息以核实付款金额，因此在 permissionCheck=finance 场景下不加 @PriceSensitive。
 *
 * @since D-9 G1
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaymentRequestApprovedDTO {

    /** 付款申请单 ID */
    private String id;

    /** 申请单编号（PR-F006-20261014-XXXX）*/
    private String requestNumber;

    /** 对应采购订单 ID（UUID）*/
    private String purchaseOrderId;

    /** 采购订单号（PO-F006-YYYYMMDD-NN，人读友好）*/
    private String purchaseOrderNumber;

    /** 供应商 ID（UUID）*/
    private String supplierId;

    /** 供应商名称 */
    private String supplierName;

    /** 付款总金额 */
    private BigDecimal amount;

    /** 结算方式（从 PO 继承）*/
    private SettlementType settlementType;

    /** 结算方式中文名 */
    private String settlementTypeDisplayName;

    /** 支付方式（BANK_TRANSFER / CASH / WECHAT / ALIPAY）*/
    private String paymentMethod;

    /** 银行名称 */
    private String bankName;

    /** 银行账号 */
    private String bankAccount;

    /** 审批通过时间 */
    private LocalDateTime approvedAt;

    /** 审批人 userId */
    private Long approvedBy;

    /** 申请备注 */
    private String remark;

    /** 付款申请状态（APPROVED）*/
    private PaymentRequestStatus status;

    /** 申请人 userId */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /**
     * 采购行明细列表（JOIN purchase_order_items）。
     * batch 加载，N+1 安全。
     * 出纳据此核实：原料名称、数量、单价、单位是否与付款金额匹配。
     */
    private List<ItemLine> items;

    /**
     * 采购订单行明细（出纳核实用）。
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ItemLine {
        /** 行 ID */
        private Long itemId;
        /** 原料/食材名称 */
        private String materialName;
        /** 采购数量 */
        private BigDecimal quantity;
        /** 计量单位 */
        private String unit;
        /** 单价（出纳核实用，finance 角色可见）*/
        private BigDecimal unitPrice;
        /** 行小计 = quantity × unitPrice */
        private BigDecimal lineAmount;
        /** 规格 */
        private String specification;
    }
}
