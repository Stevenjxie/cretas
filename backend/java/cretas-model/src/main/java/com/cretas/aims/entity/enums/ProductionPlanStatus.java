package com.cretas.aims.entity.enums;

/**
 * 生产计划状态枚举
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public enum ProductionPlanStatus {
    /**
     * 已计划
     */
    PLANNED("已计划", "计划已创建"),
    /**
     * 草稿态 (M-PREP-1, Sprint 4 W2): 计划已创建但未提交。
     * 用于在正式提交 (PENDING) 之前预览物料短缺情况, 可编辑或删除。
     * 转换规则: PREPARED → PENDING (提交) 或 PREPARED → CANCELLED (丢弃)。
     * 不允许直接 PREPARED → IN_PROGRESS, 必须先 commit 到 PENDING。
     */
    PREPARED("草稿", "草稿态, 可预览物料短缺后提交或丢弃"),
    /**
     * 待处理
     */
    PENDING("待处理", "计划已创建，等待开始"),
     /**
      * 进行中
      */
    IN_PROGRESS("进行中", "生产正在进行"),
     /**
      * 已完成
      */
    COMPLETED("已完成", "生产已完成"),
     /**
      * 已取消
      */
    CANCELLED("已取消", "计划已取消"),
     /**
      * 暂停
      */
    PAUSED("暂停", "生产暂时停止"),
     /**
      * 待审批 (SP12 T3 生产撤回审批流).
      *
      * <p>已完成计划申请撤回/取消时进入此状态，由 WorkflowEngineService
      * 驱动 PRODUCTION_REVERSAL 审批流。审批通过 → CANCELLED；审批拒绝 → COMPLETED。
      * 六扇门红线：操作员不可直接撤回已完成计划，必须主管审批。
      */
    PENDING_APPROVAL("待审批", "已申请撤回/取消，等待主管审批");
    private final String displayName;
    private final String description;
    ProductionPlanStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
