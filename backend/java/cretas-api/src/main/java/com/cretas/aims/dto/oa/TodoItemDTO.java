package com.cretas.aims.dto.oa;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 统一待办项 DTO — RN OA「我的待办」聚合层输出形状。
 *
 * <p>5 类待办来源:
 * <ul>
 *   <li>PURCHASE_FINANCE_REVIEW — 采购单待财务审核</li>
 *   <li>SALES_FINANCE_REVIEW — 销售单待财务审核</li>
 *   <li>PRICE_ANOMALY — 送货单价格异常待审批</li>
 *   <li>STOCKTAKE_APPROVAL — 盘点任务待财务审批</li>
 *   <li>PAYMENT_DISBURSE — 已审批付款请求（出纳执行）</li>
 * </ul>
 *
 * <p>防呆 Rule 2: amount (金额) + counterparty (对方) + submittedBy (申请人) 供 RN 卡片
 * 展示完整上下文，避免仅显示单号。
 *
 * <p>needDetail: {@code amount > threshold (default ¥5000)} → true，RN 强制进详情页审批，
 * 防止大额单直接一键审批。amount==null 时保守默认 true。
 *
 * @since 2026-06-12 (OA 待办聚合 B1)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoItemDTO {

    /**
     * 待办类型枚举 — 5 个值，RN 据此路由到对应审批端点和详情页。
     */
    public enum TodoType {
        /** 采购单待财务审核 */
        PURCHASE_FINANCE_REVIEW,
        /** 销售单待财务审核 */
        SALES_FINANCE_REVIEW,
        /** 送货单价格异常待审批 */
        PRICE_ANOMALY,
        /** 盘点任务待财务审批 */
        STOCKTAKE_APPROVAL,
        /** 已审批付款请求（出纳执行付款） */
        PAYMENT_DISBURSE
    }

    /** 待办类型 */
    private TodoType type;

    /** 业务单 ID（用于 RN 调用对应审批端点） */
    private String refId;

    /** 业务单号（展示用，如 PO-20260601-001） */
    private String refNumber;

    /** 卡片标题（如 "采购财审 — 北京飞熊食材"） */
    private String title;

    /**
     * 金额（BigDecimal，阈值判定 + 展示）。
     * 注意：不加 @PriceSensitive — 待办金额是审批人本就该看到的，且 RN 端均为财务角色。
     */
    private BigDecimal amount;

    /** 对方（供应商名 / 客户名） */
    private String counterparty;

    /** 申请人名（提交财审/价格异常/盘点的人） */
    private String submittedBy;

    /** 提交时间（合并排序 + RN 展示用） */
    private LocalDateTime submittedAt;

    /**
     * 是否需要进详情页才能审批。
     * {@code true} 时 RN 隐藏一键通过/驳回，只显示「查看详情」按钮。
     * 计算逻辑: {@code amount == null || amount.compareTo(threshold) > 0}
     */
    private boolean needDetail;

    /**
     * RN 详情路由 hint（可选）。
     * 格式如 "TodoDetail/purchase/{refId}"，RN 无需 switch 即可直接 navigate。
     */
    private String detailPath;
}
