package com.cretas.aims.entity.enums;

/**
 * 采购订单状态枚举
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
public enum PurchaseOrderStatus {
    /** 草稿 */
    DRAFT("草稿", "采购订单草稿，尚未提交"),
    /** 已提交待审批 */
    SUBMITTED("已提交", "已提交等待审批"),
    /**
     * 工作流审批中 (Phase 1 Canvas-Workflow, 2026-05-18) —
     * approveOrder 启动了 Canvas-configured workflow, 当前在 RUNNING approval node 等待人工.
     * 等 transitionNode 推进到终态后再切换 APPROVED / REJECTED / PENDING_FINANCE_REVIEW.
     */
    WORKFLOW_RUNNING("审批中", "工作流审批进行中，等待审批人操作"),
    /** 已审批 (运营审批) */
    APPROVED("已审批", "运营审批通过，等待财务审核"),
    /** 待财务审核 */
    PENDING_FINANCE_REVIEW("待财务审核", "运营审批通过，等待财务审核"),
    /** 财务审核通过 */
    FINANCE_APPROVED("财务已审核", "财务审核通过，允许采购执行"),
    /** 财务驳回 */
    FINANCE_REJECTED("财务驳回", "财务审核未通过，需修改后重新提交"),
    /** 部分到货 */
    PARTIAL_RECEIVED("部分到货", "部分物料已到货入库"),
    /** 全部到货 */
    COMPLETED("已完成", "全部物料已到货入库"),
    /** 已取消 */
    CANCELLED("已取消", "采购订单已取消"),
    /** 已关闭 */
    CLOSED("已关闭", "采购订单已关闭（含部分到货后关闭）");

    private final String displayName;
    private final String description;

    PurchaseOrderStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
